/*
 * Photos for Proton
 * Copyright (C) 2026 Akoos <https://akoos.eu>
 *
 * Source:  https://github.com/gitakoos/proton-photos
 * Website: https://photos.akoos.eu
 *
 * This file is part of Photos for Proton.
 *
 * Photos for Proton is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License version 3 as
 * published by the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */

package eu.akoos.photos.data.repository.drive

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import me.proton.core.auth.domain.repository.AuthRepository
import me.proton.core.domain.entity.UserId
import me.proton.core.key.domain.entity.key.canEncrypt
import me.proton.core.key.domain.repository.PublicAddressRepository
import me.proton.core.network.data.ApiProvider
import me.proton.core.network.domain.session.SessionProvider
import eu.akoos.photos.data.api.DriveApiService
import eu.akoos.photos.data.api.dto.AcceptInvitationRequest
import eu.akoos.photos.data.api.dto.AlbumDto
import eu.akoos.photos.data.api.dto.BatchLinksRequest
import eu.akoos.photos.data.api.dto.CreateInvitationRequest
import eu.akoos.photos.data.api.dto.CreateShareRequest
import eu.akoos.photos.data.api.dto.CreateShareUrlRequest
import eu.akoos.photos.data.api.dto.GlobalInvitationDto
import eu.akoos.photos.data.api.dto.InvitationBodyDto
import eu.akoos.photos.data.api.dto.LinkCoreDto
import eu.akoos.photos.data.api.dto.SharedWithMeLinkDto
import eu.akoos.photos.data.api.dto.UpdateShareMemberRequest
import eu.akoos.photos.data.api.dto.UpdateShareUrlRequest
import eu.akoos.photos.data.crypto.DriveCryptoHelper
import eu.akoos.photos.data.db.dao.PhotoListingDao
import eu.akoos.photos.domain.entity.Album
import eu.akoos.photos.domain.entity.PendingInvitation
import eu.akoos.photos.domain.entity.ShareInvitation
import eu.akoos.photos.domain.entity.ShareMember
import eu.akoos.photos.domain.entity.SharedPhoto
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "AlbumSharingSvc"
private const val BATCH_SIZE = 50

/**
 * Album-sharing + invitation flows: public share-URL creation, invite-by-email,
 * accept/decline/list pending invitations on the receiving side, member listing/removal,
 * and the `sharedwithme` album listing. Looks up plaintext album names via
 * [AlbumService.loadAlbums] when minting a new share.
 */
@Singleton
class AlbumSharingService @Inject constructor(
    private val apiProvider: ApiProvider,
    private val cryptoHelper: DriveCryptoHelper,
    private val publicAddressRepository: PublicAddressRepository,
    private val shareService: PhotosShareService,
    private val linkDetailHelpers: LinkDetailHelpers,
    private val albumService: AlbumService,
    private val photoListingDao: PhotoListingDao,
    private val albumPhotoMembershipDao: eu.akoos.photos.data.db.dao.AlbumPhotoMembershipDao,
    private val cloudAlbumDao: eu.akoos.photos.data.db.dao.CloudAlbumDao,
    private val authRepository: AuthRepository,
    private val sessionProvider: SessionProvider,
    private val cryptoContext: me.proton.core.crypto.common.context.CryptoContext,
    private val userAddressRepository: me.proton.core.user.domain.repository.UserAddressRepository,
    @ApplicationContext private val context: Context,
) {
    /**
     * Rehydrates a shared-with-me album cover from local sources — the shared endpoint never
     * returns a CDN thumbnail URL, so we look it up in the listing DB then the on-disk cache.
     */
    private suspend fun resolveOfflineCoverUrl(coverLinkId: String?, albumLinkId: String? = null): String? {
        val thumbnailCacheDir = File(context.cacheDir, "thumbnails")
        if (coverLinkId != null) {
            photoListingDao.getByLinkId(coverLinkId)?.thumbnailUrl?.let { return it }
            val file = File(thumbnailCacheDir, "thumb_$coverLinkId.jpg")
            if (file.exists() && file.length() > 0) return "file://${file.absolutePath}"
        }
        // Membership fallback: cross-account shares rewrite linkIds, so coverLinkId often
        // doesn't match the recipient's view. Any decrypted photo works as a stand-in cover.
        if (albumLinkId != null) {
            val memberIds = runCatching { albumPhotoMembershipDao.getPhotoLinkIds(albumLinkId) }
                .getOrDefault(emptyList())
            if (memberIds.isNotEmpty()) {
                val firstFromDb = photoListingDao.getByLinkIds(memberIds)
                    .firstOrNull { !it.thumbnailUrl.isNullOrBlank() }
                    ?.thumbnailUrl
                if (firstFromDb != null) return firstFromDb
                for (mid in memberIds) {
                    val file = File(thumbnailCacheDir, "thumb_$mid.jpg")
                    if (file.exists() && file.length() > 0) return "file://${file.absolutePath}"
                }
            }
        }
        return null
    }
    private val semaphore get() = shareService.networkSemaphore

    /**
     * Creates (or reuses) the album's Drive share and then mints a public share-URL on
     * top of it. Returns the user-shareable URL.
     */
    suspend fun createAlbumShareLink(userId: UserId, albumLinkId: String): String = withContext(Dispatchers.IO) {
        Log.d(TAG, "createAlbumShareLink: ENTER albumLinkId=$albumLinkId")
        val volumeId = shareService.getVolumeId(userId)
        val manager = apiProvider.get<DriveApiService>(userId)
        val signingKey = cryptoHelper.getAddressSigningKey(userId)
        Log.d(TAG, "createAlbumShareLink: prepared volumeId=$volumeId signingEmail=${signingKey.email}")

        // Step 1 — find or create the album's share (same path as inviteToAlbum). On reuse we
        // need the share session key (PKESK plaintext) so step 2 can re-encrypt it under the
        // salted URL password; for new shares it falls out of generateShareForAlbum.
        val albumLinkDetails = linkDetailHelpers.batchFetchLinkDetails(userId, volumeId, listOf(albumLinkId))[albumLinkId]
            ?: error("Album $albumLinkId not found while creating share link")
        val existingShareId: String? = albumLinkDetails.sharing?.shareId
        val (albumShareId, shareSessionKey) = if (existingShareId != null) {
            // v1 bootstrap endpoint — v2 404s on newly created album shares (type 4).
            val bootstrap = semaphore.withPermit {
                manager.invoke { getShareBootstrap(existingShareId) }.valueOrThrow
            }
            val passphraseArmored = bootstrap.passphrase
                ?: error("Existing share $existingShareId has no Passphrase — cannot mint a URL")
            Log.d(TAG, "createAlbumShareLink: reusing existing share $existingShareId")
            existingShareId to cryptoHelper.decryptSharePassphraseSessionKey(userId, passphraseArmored)
        } else {
            Log.d(TAG, "createAlbumShareLink: no existing share — creating one")
            val generated = buildAlbumShareMaterial(userId, albumLinkId, albumLinkDetails, signingKey)
            val resp = semaphore.withPermit {
                manager.invoke {
                    createVolumeShare(
                        volumeId,
                        CreateShareRequest(
                            // STANDARD (2): sub-share exposing one link. PHOTO (4) is the root
                            // Photos volume share and is rejected for individual album shares.
                            type = 2,
                            addressId = signingKey.addressId,
                            name = SHARE_DISPLAY_NAME,
                            rootLinkId = albumLinkId,
                            shareKey = generated.shareKeyArmored,
                            sharePassphrase = generated.sharePassphraseArmored,
                            sharePassphraseSignature = generated.sharePassphraseSignature,
                            passphraseKeyPacket = generated.passphraseKeyPacketBase64,
                            nameKeyPacket = generated.nameKeyPacketBase64,
                        ),
                    )
                }.valueOrThrow
            }
            Log.d(TAG, "createAlbumShareLink: share created shareId=${resp.share.shareId} — will roll back if URL minting fails")
            resp.share.shareId to generated.passphraseSessionKey
        }
        val isFreshShare = existingShareId == null

        // Step 2 — mint the public URL on the share (shared with the photo-link path).
        val finalUrl = mintShareUrlOnShare(userId, manager, albumShareId, shareSessionKey, signingKey, isFreshShare)
        Log.d(TAG, "createAlbumShareLink: SUCCESS url=$finalUrl")
        finalUrl
    }

    /**
     * Mints a public share-URL on an already-resolved share (album or single photo). The
     * backend requires the full SRP + salted-passphrase + encrypted-URL-password bundle (8
     * fields) on every new share URL. [shareSessionKey] is the PKESK plaintext of the share's
     * passphrase, re-encrypted under the salted URL password. On failure, an orphan share (no
     * URL/members/invitations) is deleted so it doesn't strand a misleading "shared by me" badge.
     */
    private suspend fun mintShareUrlOnShare(
        userId: UserId,
        manager: me.proton.core.network.domain.ApiManager<out DriveApiService>,
        shareId: String,
        shareSessionKey: me.proton.core.crypto.common.pgp.SessionKey,
        signingKey: eu.akoos.photos.data.crypto.AddressSigningKey,
        isFreshShare: Boolean,
    ): String {
        Log.d(TAG, "mintShareUrlOnShare: shareId=$shareId fetching modulus")
        val sessionId = sessionProvider.getSessionId(userId)
        val modulus = authRepository.randomModulus(sessionId)
        Log.d(TAG, "mintShareUrlOnShare: modulus fetched (id=${modulus.modulusId.take(8)}…) — building crypto package")
        val pkg = cryptoHelper.buildShareUrlCryptoPackage(
            addressPublicKeyArmored = signingKey.publicKeyArmored,
            shareSessionKey = shareSessionKey,
            modulus = modulus.modulus,
            modulusId = modulus.modulusId,
        )
        Log.d(TAG, "mintShareUrlOnShare: crypto package built — POSTing /urls")
        val urlResp = try {
            semaphore.withPermit {
                manager.invoke {
                    createShareUrl(
                        shareId,
                        CreateShareUrlRequest(
                            // Required field — null is rejected with "Missing required attribute
                            // MaxAccesses". 0 means unlimited.
                            maxAccesses = 0L,
                            creatorEmail = signingKey.email,
                            permissions = pkg.permissions,
                            urlPasswordSalt = pkg.urlPasswordSalt,
                            sharePasswordSalt = pkg.sharePasswordSalt,
                            srpVerifier = pkg.srpVerifier,
                            srpModulusId = pkg.srpModulusId,
                            flags = pkg.flags,
                            sharePassphraseKeyPacket = pkg.sharePassphraseKeyPacketBase64,
                            encryptedUrlPassword = pkg.encryptedUrlPassword,
                        ),
                    )
                }.valueOrThrow
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.e(TAG, "mintShareUrlOnShare: createShareUrl failed shareId=$shareId msg=${e.message}", e)
            // Auto-rollback an orphan share (no URL/members/invitations) so it doesn't surface a
            // misleading "shared by me" badge. Runs unconditionally so a retry after a prior
            // failed mint isn't permanently stuck with the leftover share.
            runCatching {
                val members = runCatching {
                    semaphore.withPermit { manager.invoke { getShareMembers(shareId) }.valueOrThrow }.members
                }.getOrDefault(emptyList())
                val invitations = runCatching {
                    semaphore.withPermit { manager.invoke { listShareInvitations(shareId) }.valueOrThrow }.invitations
                }.getOrDefault(emptyList())
                val urls = runCatching {
                    semaphore.withPermit { manager.invoke { getShareUrls(shareId) }.valueOrThrow }.shareUrls
                }.getOrDefault(emptyList())
                if (members.isEmpty() && invitations.isEmpty() && urls.isEmpty()) {
                    Log.d(TAG, "mintShareUrlOnShare: rolling back orphan share $shareId (no members/invites/urls)")
                    semaphore.withPermit { manager.invoke { deleteShare(shareId) }.valueOrThrow }
                } else {
                    Log.d(TAG, "mintShareUrlOnShare: keeping share $shareId — has ${members.size}m/${invitations.size}i/${urls.size}u")
                }
            }.onFailure { rb ->
                Log.w(TAG, "mintShareUrlOnShare: orphan cleanup attempt failed: ${rb.message}", rb)
            }
            Log.d(TAG, "mintShareUrlOnShare: cleanup pass complete (isFreshShare=$isFreshShare)")
            throw e
        }
        // A freshly minted URL is a random-password link (Flags=2): the password must ride in
        // the #fragment or the recipient gets prompted. pkg.urlPassword is the SRP plaintext.
        val baseUrl = urlResp.shareUrl?.publicUrl
            ?: "https://drive.proton.me/urls/${urlResp.shareUrl?.token}"
        return "$baseUrl#${pkg.urlPassword}"
    }

    /**
     * Reassembles the full openable public link for an existing/refetched share-URL item by
     * re-deriving the #fragment: a random-password link's password lives in the fragment, which
     * the bare PublicUrl omits, so we decrypt the stored password with the user's address key.
     * Legacy links (Flags=0) and links with no stored password return the bare URL. Decrypt-only.
     */
    private suspend fun fullPublicLink(userId: UserId, dto: eu.akoos.photos.data.api.dto.ShareUrlDto): String {
        val baseUrl = dto.publicUrl ?: "https://drive.proton.me/urls/${dto.token}"
        val encryptedPassword = dto.encryptedUrlPassword
        // Only Flags with the random-password bit (==2) carry a fragment; Flags==0 is legacy.
        val isLegacy = (dto.flags ?: 0L) == 0L
        if (isLegacy || encryptedPassword.isNullOrBlank()) return baseUrl
        val password = runCatching {
            cryptoHelper.decryptUrlPassword(userId, encryptedPassword)
        }.getOrElse { e ->
            // Password from another address we can't open — fall back to the bare URL.
            // The share token is a capability secret, so it stays out of the log line.
            Log.w(TAG, "fullPublicLink: URL password decrypt failed — returning bare URL: ${e.message}")
            return baseUrl
        }
        return "$baseUrl#$password"
    }

    /**
     * Creates (or reuses) a Drive share on a single photo link and mints (or reuses) a public
     * share-URL on top of it. Like [createAlbumShareLink], plus a reuse guard: if the photo
     * already has a share with a public URL, return that instead of minting a duplicate.
     * Only share metadata is created — the photo's content blocks and revision are untouched.
     */
    suspend fun createPhotoShareLink(userId: UserId, photoLinkId: String): String = withContext(Dispatchers.IO) {
        Log.d(TAG, "createPhotoShareLink: ENTER photoLinkId=$photoLinkId")
        val volumeId = shareService.getVolumeId(userId)
        val manager = apiProvider.get<DriveApiService>(userId)
        val signingKey = cryptoHelper.getAddressSigningKey(userId)
        Log.d(TAG, "createPhotoShareLink: prepared volumeId=$volumeId signingEmail=${signingKey.email}")

        // Step 1 — find or create the photo's share. On reuse we need the share session key
        // (PKESK plaintext) so the mint step can re-encrypt it under the salted URL password.
        val photoLinkDetails = linkDetailHelpers.batchFetchLinkDetails(userId, volumeId, listOf(photoLinkId))[photoLinkId]
            ?: error("Photo $photoLinkId not found while creating share link")
        val existingShareId: String? = photoLinkDetails.sharing?.shareId
        val (photoShareId, shareSessionKey) = if (existingShareId != null) {
            // Reuse guard, before any crypto: if the share already has a public URL, hand it back.
            val existingUrl = runCatching {
                semaphore.withPermit {
                    manager.invoke { getShareUrls(existingShareId) }.valueOrThrow
                }.shareUrls
            }.getOrDefault(emptyList())
                .firstOrNull()
            if (existingUrl != null) {
                val reused = fullPublicLink(userId, existingUrl)
                Log.d(TAG, "createPhotoShareLink: reusing existing share $existingShareId + url=$reused")
                return@withContext reused
            }
            // v1 bootstrap endpoint — v2 404s on newly created shares. Recover the passphrase
            // session key to mint the URL.
            val bootstrap = semaphore.withPermit {
                manager.invoke { getShareBootstrap(existingShareId) }.valueOrThrow
            }
            val passphraseArmored = bootstrap.passphrase
                ?: error("Existing share $existingShareId has no Passphrase — cannot mint a URL")
            Log.d(TAG, "createPhotoShareLink: reusing existing share $existingShareId (no url yet — will mint)")
            existingShareId to cryptoHelper.decryptSharePassphraseSessionKey(userId, passphraseArmored)
        } else {
            Log.d(TAG, "createPhotoShareLink: no existing share — creating one")
            // buildAlbumShareMaterial is link-agnostic: the parent is always the Photos root key.
            val generated = buildAlbumShareMaterial(userId, photoLinkId, photoLinkDetails, signingKey)
            val resp = semaphore.withPermit {
                manager.invoke {
                    createVolumeShare(
                        volumeId,
                        CreateShareRequest(
                            // STANDARD (2): sub-share exposing one link. PHOTO (4) is the root
                            // Photos volume share and is rejected for individual link shares.
                            type = 2,
                            addressId = signingKey.addressId,
                            name = SHARE_DISPLAY_NAME,
                            rootLinkId = photoLinkId,
                            shareKey = generated.shareKeyArmored,
                            sharePassphrase = generated.sharePassphraseArmored,
                            sharePassphraseSignature = generated.sharePassphraseSignature,
                            passphraseKeyPacket = generated.passphraseKeyPacketBase64,
                            nameKeyPacket = generated.nameKeyPacketBase64,
                        ),
                    )
                }.valueOrThrow
            }
            Log.d(TAG, "createPhotoShareLink: share created shareId=${resp.share.shareId} — will roll back if URL minting fails")
            resp.share.shareId to generated.passphraseSessionKey
        }
        val isFreshShare = existingShareId == null

        // Step 2 — mint the public URL on the share (shared SRP-bundle path + orphan rollback).
        val finalUrl = mintShareUrlOnShare(userId, manager, photoShareId, shareSessionKey, signingKey, isFreshShare)
        Log.d(TAG, "createPhotoShareLink: SUCCESS url=$finalUrl")
        finalUrl
    }

    /**
     * Returns the existing public share-URL for a single photo, or null if the photo isn't
     * shared by link (no share, or a share with no URL). Read-only — no share or URL is
     * created here.
     */
    suspend fun getPhotoShareLink(userId: UserId, photoLinkId: String): String? = withContext(Dispatchers.IO) {
        val volumeId = shareService.getVolumeId(userId)
        val manager = apiProvider.get<DriveApiService>(userId)
        val photoLinkDetails = linkDetailHelpers.batchFetchLinkDetails(userId, volumeId, listOf(photoLinkId))[photoLinkId]
            ?: return@withContext null
        val shareId = photoLinkDetails.sharing?.shareId ?: return@withContext null
        val urls = runCatching {
            semaphore.withPermit {
                manager.invoke { getShareUrls(shareId) }.valueOrThrow
            }.shareUrls
        }.getOrDefault(emptyList())
        val first = urls.firstOrNull() ?: return@withContext null
        fullPublicLink(userId, first)
    }

    /**
     * Stops sharing a single photo by link: deletes every public URL on the share, then the
     * share itself. Each step is wrapped independently; "already gone" is treated as success.
     * Only share metadata is removed — the photo's content blocks and revision are untouched.
     */
    suspend fun revokePhotoShareLink(userId: UserId, photoLinkId: String): Unit = withContext(Dispatchers.IO) {
        val volumeId = shareService.getVolumeId(userId)
        val manager = apiProvider.get<DriveApiService>(userId)
        val photoLinkDetails = linkDetailHelpers.batchFetchLinkDetails(userId, volumeId, listOf(photoLinkId))[photoLinkId]
        val shareId = photoLinkDetails?.sharing?.shareId
        Log.w(TAG, "revokePhotoShareLink: photo=$photoLinkId resolvedShareId=$shareId")
        if (shareId == null) {
            Log.w(TAG, "revokePhotoShareLink: photo $photoLinkId has no share — nothing to revoke (stale link details?)")
            return@withContext
        }

        // 1. Delete every URL on the share, tolerating "already gone".
        val urls = runCatching {
            semaphore.withPermit {
                manager.invoke { getShareUrls(shareId) }.valueOrThrow
            }.shareUrls
        }.getOrDefault(emptyList())
        Log.w(TAG, "revokePhotoShareLink: shareId=$shareId urlCount=${urls.size}")
        for (url in urls) {
            val urlId = url.shareUrlId ?: continue
            runCatching {
                semaphore.withPermit {
                    manager.invoke { deleteShareUrl(shareId, urlId) }.valueOrThrow
                }
                Log.w(TAG, "revokePhotoShareLink: deleted url $urlId on share $shareId")
            }.onFailure { e ->
                Log.w(TAG, "revokePhotoShareLink: deleteShareUrl failed shareId=$shareId urlId=$urlId msg=${e.message}", e)
            }
        }

        // 2. Delete the share, wrapped independently from the URL deletes above.
        runCatching {
            semaphore.withPermit {
                manager.invoke { deleteShare(shareId) }.valueOrThrow
            }
            Log.w(TAG, "revokePhotoShareLink: deleted share $shareId for photo $photoLinkId")
        }.onFailure { e ->
            Log.w(TAG, "revokePhotoShareLink: deleteShare failed shareId=$shareId msg=${e.message}", e)
        }
    }

    /**
     * Sets or clears the custom password on a single photo's public link, returning the new
     * full link string. Blank/null [password] → random-password link (Flags=2) with the
     * password in the #fragment; non-blank → custom-password link (Flags=1), bare URL (the
     * recipient types it). The share session key is recovered via bootstrap and the SRP
     * verifier/salts re-derived from the password. Only share metadata is touched.
     */
    suspend fun setPhotoLinkPassword(
        userId: UserId,
        photoLinkId: String,
        password: String?,
    ): String = withContext(Dispatchers.IO) {
        val volumeId = shareService.getVolumeId(userId)
        val manager = apiProvider.get<DriveApiService>(userId)
        val signingKey = cryptoHelper.getAddressSigningKey(userId)

        val photoLinkDetails = linkDetailHelpers.batchFetchLinkDetails(userId, volumeId, listOf(photoLinkId))[photoLinkId]
            ?: error("Photo $photoLinkId not found while updating share-link password")
        val shareId = photoLinkDetails.sharing?.shareId
            ?: error("Photo $photoLinkId has no share — create a public link before setting a password")
        val shareUrl = runCatching {
            semaphore.withPermit { manager.invoke { getShareUrls(shareId) }.valueOrThrow }.shareUrls
        }.getOrDefault(emptyList()).firstOrNull()
            ?: error("Share $shareId has no public URL — create a public link before setting a password")
        val shareUrlId = shareUrl.shareUrlId
            ?: error("Share URL on share $shareId is missing its ShareURLID")

        // Recover the share's passphrase session key to re-wrap under the new salted URL password.
        val bootstrap = semaphore.withPermit {
            manager.invoke { getShareBootstrap(shareId) }.valueOrThrow
        }
        val passphraseArmored = bootstrap.passphrase
            ?: error("Share $shareId has no Passphrase — cannot rebuild the URL password")
        val shareSessionKey = cryptoHelper.decryptSharePassphraseSessionKey(userId, passphraseArmored)

        val sessionId = sessionProvider.getSessionId(userId)
        val modulus = authRepository.randomModulus(sessionId)

        // Custom (1) when a password is given; random (2) when clearing it (a fresh password
        // is minted so a previously-shared link's old fragment stops working).
        val custom = !password.isNullOrBlank()
        val effectivePassword = if (custom) password!! else cryptoHelper.generateRandomUrlPassword()
        val flags = if (custom) 1L else 2L
        val pkg = cryptoHelper.buildShareUrlCryptoPackageFromPassword(
            addressPublicKeyArmored = signingKey.publicKeyArmored,
            shareSessionKey = shareSessionKey,
            modulus = modulus.modulus,
            modulusId = modulus.modulusId,
            password = effectivePassword,
            flags = flags,
        )

        val resp = semaphore.withPermit {
            manager.invoke {
                updateShareUrl(
                    shareId,
                    shareUrlId,
                    UpdateShareUrlRequest(
                        flags = pkg.flags,
                        sharePassphraseKeyPacket = pkg.sharePassphraseKeyPacketBase64,
                        encryptedUrlPassword = pkg.encryptedUrlPassword,
                        urlPasswordSalt = pkg.urlPasswordSalt,
                        sharePasswordSalt = pkg.sharePasswordSalt,
                        srpVerifier = pkg.srpVerifier,
                        srpModulusId = pkg.srpModulusId,
                    ),
                )
            }.valueOrThrow
        }
        val baseUrl = resp.shareUrl?.publicUrl
            ?: shareUrl.publicUrl
            ?: "https://drive.proton.me/urls/${resp.shareUrl?.token ?: shareUrl.token}"
        // Custom-password link → bare URL; random link → append the #fragment.
        if (custom) baseUrl else "$baseUrl#${pkg.urlPassword}"
    }

    /**
     * Deletes every public URL on a share without touching its member shares, so members and
     * invitations keep working. Looks up the URLs via [getShareUrls] since callers usually
     * only hold the parent shareId.
     */
    suspend fun revokeShareUrlOnly(userId: UserId, shareId: String): Unit = withContext(Dispatchers.IO) {
        val manager = apiProvider.get<DriveApiService>(userId)
        val urls = semaphore.withPermit {
            manager.invoke { getShareUrls(shareId) }.valueOrThrow
        }.shareUrls
        if (urls.isEmpty()) return@withContext
        for (url in urls) {
            val urlId = url.shareUrlId ?: continue
            try {
                semaphore.withPermit {
                    manager.invoke { deleteShareUrl(shareId, urlId) }.valueOrThrow
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.e(TAG, "revokeShareUrlOnly failed: shareId=$shareId urlId=$urlId msg=${e.message}", e)
                throw e
            }
        }
    }

    /** Changes a member's permission bitmap on an album share. Drive accepts only 4 (viewer)
     *  and 6 (editor); other bitmaps are rejected server-side. */
    suspend fun changeMemberPermission(
        userId: UserId,
        shareId: String,
        memberId: String,
        permissions: Int,
    ): Unit = withContext(Dispatchers.IO) {
        val manager = apiProvider.get<DriveApiService>(userId)
        try {
            semaphore.withPermit {
                manager.invoke {
                    updateShareMember(
                        shareId,
                        memberId,
                        UpdateShareMemberRequest(permissions = permissions.toLong()),
                    )
                }.valueOrThrow
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.e(TAG, "changeMemberPermission failed: shareId=$shareId memberId=$memberId perms=$permissions msg=${e.message}", e)
            throw e
        }
    }

    /** Permission swap for a pending (not-yet-accepted) invitation — permissions-only, no
     *  key-packet rework. */
    suspend fun changeInvitationPermission(
        userId: UserId,
        shareId: String,
        invitationId: String,
        permissions: Int,
    ): Unit = withContext(Dispatchers.IO) {
        val manager = apiProvider.get<DriveApiService>(userId)
        try {
            semaphore.withPermit {
                manager.invoke {
                    updateShareInvitation(
                        shareId,
                        invitationId,
                        UpdateShareMemberRequest(permissions = permissions.toLong()),
                    )
                }.valueOrThrow
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.e(TAG, "changeInvitationPermission failed: shareId=$shareId invitationId=$invitationId perms=$permissions msg=${e.message}", e)
            throw e
        }
    }

    /**
     * Invites a Proton account by email to an album. Reuses the album's existing
     * `sharing.shareId` when present, else POSTs `drive/volumes/{volumeId}/shares` — the only
     * endpoint the backend accepts (the legacy `drive/v2/shares/.../share` returns "Path not found").
     */
    suspend fun inviteToAlbum(userId: UserId, albumLinkId: String, email: String): Unit = withContext(Dispatchers.IO) {
        val volumeId = shareService.getVolumeId(userId)
        val manager = apiProvider.get<DriveApiService>(userId)
        val signingKey = cryptoHelper.getAddressSigningKey(userId)

        val albumLinkDetails = linkDetailHelpers.batchFetchLinkDetails(userId, volumeId, listOf(albumLinkId))[albumLinkId]
            ?: error("Album $albumLinkId not found while inviting to share")
        val existingShareId: String? = albumLinkDetails.sharing?.shareId

        // All Photos crypto must sign with the address that bootstrapped the volume, not
        // today's primary (which differs after an alias promotion). A mismatch surfaces on the
        // recipient as "Item cannot be decrypted" — they verify against the volume-owner address.
        var inviterSigningKey: eu.akoos.photos.data.crypto.AddressSigningKey = signingKey
        val volumeMainShareIdEarly = shareService.shareId()
        if (volumeMainShareIdEarly != null) {
            runCatching {
                val volumeMainBootstrap = semaphore.withPermit {
                    manager.invoke { getShareBootstrap(volumeMainShareIdEarly) }.valueOrThrow
                }
                val volumeOwnerAddressId = volumeMainBootstrap.addressId
                if (volumeOwnerAddressId != null && volumeOwnerAddressId != inviterSigningKey.addressId) {
                    Log.d(TAG, "inviteToAlbum: Photos volume owner address ($volumeOwnerAddressId) differs from current primary (${inviterSigningKey.addressId}) — switching inviter key BEFORE share work")
                    inviterSigningKey = cryptoHelper.getAddressSigningKeyById(userId, volumeOwnerAddressId)
                } else {
                    Log.d(TAG, "inviteToAlbum: Photos volume owner address matches current primary ${inviterSigningKey.addressId}")
                }
            }.onFailure { ex ->
                Log.w(TAG, "inviteToAlbum: could not read Photos main-share bootstrap — falling back to primary ${inviterSigningKey.email}: ${ex.message}")
            }
        } else {
            Log.w(TAG, "inviteToAlbum: photos main shareId not cached — using current primary ${inviterSigningKey.email}")
        }

        // shareSessionKey encrypts the share's passphrase message; it gets re-encrypted to the
        // invitee's public key in the invitation.
        // A share created with the wrong owner address (earlier-build bug) makes the backend
        // reject every invitation with code 2008 "inviter address is not the one used in the
        // context share." Detect the mismatch, delete the stale share, recreate consistent
        // with the volume owner (deleting an un-decryptable share is safe).
        val staleShareId: String? = if (existingShareId != null) {
            val staleBootstrap = runCatching {
                semaphore.withPermit {
                    manager.invoke { getShareBootstrap(existingShareId) }.valueOrThrow
                }
            }.getOrNull()
            val staleAddressId = staleBootstrap?.addressId
            if (staleAddressId != null && staleAddressId != inviterSigningKey.addressId) {
                Log.w(TAG, "inviteToAlbum: existing share $existingShareId is owned by $staleAddressId (≠ volume owner ${inviterSigningKey.addressId}) — deleting stale share and recreating")
                existingShareId
            } else null
        } else null
        if (staleShareId != null) {
            runCatching {
                semaphore.withPermit {
                    manager.invoke { deleteShare(staleShareId) }.valueOrThrow
                }
                Log.d(TAG, "inviteToAlbum: deleted stale share $staleShareId — fresh share will be created with volume owner")
            }.onFailure { ex ->
                Log.w(TAG, "inviteToAlbum: stale share $staleShareId delete failed: ${ex.message} — continuing with existing share path; invitation will likely fail")
            }
        }
        val effectiveExistingShareId = if (staleShareId != null) null else existingShareId
        val (albumShareId, shareSessionKey) = if (effectiveExistingShareId != null) {
            // v1 bootstrap endpoint — v2 404s on newly created album shares (type 4).
            val bootstrap = semaphore.withPermit {
                manager.invoke { getShareBootstrap(effectiveExistingShareId) }.valueOrThrow
            }
            val passphraseArmored = bootstrap.passphrase
                ?: error("Existing share $effectiveExistingShareId has no Passphrase")
            effectiveExistingShareId to cryptoHelper.decryptSharePassphraseSessionKey(userId, passphraseArmored)
        } else {
            // Build share material via the public-link path, signed with the VOLUME-OWNER's key
            // (not the current primary): the share key's address-PKESK must target the volume
            // owner or the recipient can't verify the chain. A self-encrypted/wrong-shape body
            // gets rejected with code 2001 "outdated version of the app".
            val generated = buildAlbumShareMaterial(userId, albumLinkId, albumLinkDetails, inviterSigningKey)
            // Per-field length trace — the backend's "latest version" rejection is opaque on the
            // wire, so log the measurable inputs to pinpoint which field changed between releases.
            Log.d(TAG, "inviteToAlbum: createVolumeShare PREP" +
                " volumeId=$volumeId" +
                " albumLinkId=$albumLinkId" +
                " addressId=${inviterSigningKey.addressId}" +
                " shareKeyLen=${generated.shareKeyArmored.length}" +
                " sharePassphraseLen=${generated.sharePassphraseArmored.length}" +
                " sharePassphraseSigLen=${generated.sharePassphraseSignature.length}" +
                " passphraseKeyPacketLen=${generated.passphraseKeyPacketBase64.length}" +
                " nameKeyPacketLen=${generated.nameKeyPacketBase64.length}")
            val createResp = try {
                semaphore.withPermit {
                    manager.invoke {
                        createVolumeShare(
                            volumeId,
                            CreateShareRequest(
                                // STANDARD (2): sub-share exposing one link. PHOTO (4) is the
                                // root Photos volume share, rejected for individual album shares.
                                type = 2,
                                addressId = inviterSigningKey.addressId,
                                name = SHARE_DISPLAY_NAME,
                                rootLinkId = albumLinkId,
                                shareKey = generated.shareKeyArmored,
                                sharePassphrase = generated.sharePassphraseArmored,
                                sharePassphraseSignature = generated.sharePassphraseSignature,
                                passphraseKeyPacket = generated.passphraseKeyPacketBase64,
                                nameKeyPacket = generated.nameKeyPacketBase64,
                            ),
                        )
                    }.valueOrThrow
                }
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                // Surface every cause-chain layer — the rejection could be a SemVer parse
                // failure, a missing field, or a crypto-shape mismatch.
                Log.e(TAG, "inviteToAlbum: createVolumeShare FAILED" +
                    " volumeId=$volumeId" +
                    " albumLinkId=$albumLinkId" +
                    " type=${t.javaClass.name}" +
                    " message=${t.message}" +
                    " cause=${t.cause?.let { "${it.javaClass.name}: ${it.message}" } ?: "null"}", t)
                throw t
            }
            Log.d(TAG, "inviteToAlbum: created share ${createResp.share.shareId} for album $albumLinkId (inviterAddressId=${inviterSigningKey.addressId})")
            createResp.share.shareId to generated.passphraseSessionKey
        }

        // PublicAddressInfo groups keys into address / catchAll / unverified buckets; the
        // address bucket is the direct match for the email.
        val publicAddressInfo = runCatching {
            publicAddressRepository.getPublicAddressInfo(userId, email)
        }.getOrElse { e ->
            // Only claim "not a Proton account" when the error matches a known unknown-recipient
            // shape; anything else is transient and surfaces the raw cause without blaming the user.
            val raw = e.message.orEmpty()
            val isNotProton = raw.contains("does not exist", ignoreCase = true) ||
                raw.contains("invalid recipient", ignoreCase = true) ||
                raw.contains("no such user", ignoreCase = true) ||
                raw.contains("Email address is not allowed", ignoreCase = true) ||
                raw.contains("Address does not exist", ignoreCase = true)
            // The keys lookup occasionally returns HTML (404 page, login redirect, captcha),
            // surfacing as a JSON parse error — treat as transient, not a bad recipient.
            val isHtmlResponse = raw.contains("Unexpected JSON token", ignoreCase = true) ||
                raw.contains("Expected start of the object", ignoreCase = true) ||
                raw.contains("<html", ignoreCase = true) ||
                raw.contains("but had '<'", ignoreCase = true)
            val friendly = when {
                isNotProton -> "Couldn't send the invite to this address. Please check it and try again."
                isHtmlResponse -> "Couldn't reach the directory for $email — try again in a moment or double-check the address"
                else -> "Could not invite $email: ${raw.take(140)}"
            }
            throw IllegalArgumentException(friendly)
        }
        // Pick an encryption-capable key for the invitee, preferring primary then any
        // canEncrypt key, address bucket before unverified. Without the canEncrypt filter
        // gopenpgp can pick an obsolete/emailNoEncrypt key and bail with "SessionKey cannot
        // be encrypted".
        val candidateKey =
            publicAddressInfo.address.keys
                .firstOrNull { it.publicKey.isPrimary && it.canEncrypt() }
                ?: publicAddressInfo.address.keys
                    .firstOrNull { it.canEncrypt() }
                ?: publicAddressInfo.unverified?.keys
                    ?.firstOrNull { it.publicKey.isPrimary && it.canEncrypt() }
                ?: publicAddressInfo.unverified?.keys
                    ?.firstOrNull { it.canEncrypt() }
        Log.d(TAG, "inviteToAlbum: recipient=$email addressKeys=${publicAddressInfo.address.keys.size} " +
            "unverifiedKeys=${publicAddressInfo.unverified?.keys?.size ?: 0} " +
            "chosen=${if (candidateKey == null) "none" else "primary=${candidateKey.publicKey.isPrimary} canEncrypt=${candidateKey.canEncrypt()} flags=${candidateKey.flags}"} " +
            "sessionKeySize=${shareSessionKey.key.size}")
        val inviteePublicKeyArmored = candidateKey?.publicKey?.key
            ?: throw IllegalArgumentException("Couldn't send the invite to this address. Please check it and try again.")

        // PKESK-encrypt the share-passphrase session key to the invitee, detached-signed with
        // the SHARE OWNER's keys (not primary) — the backend matches InviterAddressID against
        // the share's owner address.
        val (keyPacket, keyPacketSignature) = cryptoHelper.encryptAndSignSessionKeyForInvitee(
            sessionKey              = shareSessionKey,
            inviteePublicKeyArmored = inviteePublicKeyArmored,
            signerKeyBytes          = inviterSigningKey.unlockedKeyBytes,
        )

        // POST the invitation. Permissions=6 (viewer+editor) is Drive's default for album
        // invitations; 4 would be viewer-only.
        try {
            semaphore.withPermit {
                manager.invoke {
                    inviteToShare(
                        albumShareId,
                        CreateInvitationRequest(
                            invitation = InvitationBodyDto(
                                inviterEmail       = inviterSigningKey.email,
                                inviteeEmail       = email,
                                permissions        = 6,
                                keyPacket          = keyPacket,
                                keyPacketSignature = keyPacketSignature,
                            ),
                        ),
                    )
                }.valueOrThrow
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.e(TAG, "inviteToAlbum failed: shareId=$albumShareId msg=${e.message}", e)
            throw e
        }
    }

    suspend fun deleteShare(userId: UserId, shareId: String): Unit = withContext(Dispatchers.IO) {
        val manager = apiProvider.get<DriveApiService>(userId)
        try {
            semaphore.withPermit {
                manager.invoke { deleteShare(shareId) }.valueOrThrow
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.e(TAG, "deleteShare failed: shareId=$shareId msg=${e.message}", e)
            throw e
        }
    }

    /**
     * Recipient-side "Leave album" — removes the user's own membership from a shared-with-me
     * album so it disappears from their library. Owner's copy and other members stay intact.
     */
    suspend fun leaveSharedAlbum(userId: UserId, shareId: String, albumLinkId: String): Unit = withContext(Dispatchers.IO) {
        val manager = apiProvider.get<DriveApiService>(userId)

        // 1. Resolve our own memberId from the share bootstrap's Memberships array (scoped to
        //    the caller). The v2 members listing is admin-only — a plain member gets 403 there,
        //    which is the error recipients saw when leaving.
        val ownAddressIds = runCatching {
            userAddressRepository.getAddresses(userId).map { it.addressId.id }.toSet()
        }.getOrDefault(emptySet())
        val bootstrapMember = runCatching {
            val bootstrap = semaphore.withPermit {
                manager.invoke { getShareBootstrap(shareId) }.valueOrThrow
            }
            // Memberships are caller-scoped; the addressId filter only matters for
            // multi-address invitations returning multiple rows.
            bootstrap.memberships.firstOrNull { it.addressId in ownAddressIds }
                ?: bootstrap.memberships.firstOrNull()
        }.getOrNull()

        // 2. Owner-side fallback (owners can list members; their own membership isn't in a
        //    member-share bootstrap).
        val ownMemberId = bootstrapMember?.memberId ?: run {
            val ownEmails = cryptoHelper.getOwnEmailAddresses(userId).toSet()
            val membersResp = runCatching {
                semaphore.withPermit {
                    manager.invoke { getShareMembers(shareId) }.valueOrThrow
                }
            }.getOrNull()
            membersResp?.members
                ?.filter { m ->
                    val inviter = m.inviterEmail?.takeIf { it.isNotBlank() }?.lowercase()
                    inviter == null || inviter !in ownEmails
                }
                ?.firstOrNull { m ->
                    listOfNotNull(
                        m.email?.takeIf { it.isNotBlank() }?.lowercase(),
                        m.inviteeEmail?.takeIf { it.isNotBlank() }?.lowercase(),
                    ).any { it in ownEmails }
                }
                ?.memberId
        }

        if (ownMemberId == null) {
            // No resolvable membership — server leave is a no-op, but still wipe local cache so
            // the album disappears instead of lingering as an orphan until the next refresh.
            Log.w(TAG, "leaveSharedAlbum: own membership not found on shareId=$shareId — local-cache wipe only")
            cloudAlbumDao.deleteByLinkId(albumLinkId)
            albumPhotoMembershipDao.deleteAllForAlbum(albumLinkId)
            return@withContext
        }

        // 3. Delete the membership.
        try {
            semaphore.withPermit {
                manager.invoke {
                    removeShareMember(shareId, ownMemberId)
                }.valueOrThrow
            }
            Log.d(TAG, "leaveSharedAlbum: left shareId=$shareId memberId=$ownMemberId")
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.e(TAG, "leaveSharedAlbum failed: shareId=$shareId memberId=$ownMemberId msg=${e.message}", e)
            throw e
        }

        // 4. Local cache cleanup so the grid updates immediately.
        cloudAlbumDao.deleteByLinkId(albumLinkId)
        albumPhotoMembershipDao.deleteAllForAlbum(albumLinkId)
    }

    /**
     * Lists albums other users have shared with this user. Primary path is
     * `drive/photos/albums/shared-with-me`, the only endpoint that reliably returns
     * Photos-album member-shares (`drive/v2/sharedwithme` excludes album-type shares). The v2
     * feed is used as a backup, merged + de-duplicated by linkId.
     */
    suspend fun loadSharedWithMeAlbums(userId: UserId): List<Album> = withContext(Dispatchers.IO) {
        val manager = apiProvider.get<DriveApiService>(userId)
        val result = mutableListOf<Album>()
        val seenLinkIds = mutableSetOf<String>()

        // Primary: Photos-specific endpoint — the official client's path for album shares.
        val albumStubs = mutableListOf<AlbumDto>()
        var anchorId: String? = null
        var photosEndpointPages = 0
        try {
            do {
                val response = semaphore.withPermit {
                    manager.invoke { getSharedWithMeAlbums(anchorId) }.valueOrThrow
                }
                photosEndpointPages++
                Log.d(TAG, "loadSharedWithMeAlbums: photos endpoint page=$photosEndpointPages albums=${response.albums.size} more=${response.more} anchor=${response.anchorId}")
                albumStubs.addAll(response.albums)
                anchorId = if (response.more) response.anchorId else null
            } while (anchorId != null)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            // Don't fail the whole call if the Photos endpoint hiccups — let the v2 backup run.
            Log.w(TAG, "loadSharedWithMeAlbums: drive/photos/albums/shared-with-me failed: ${e.message}", e)
        }
        Log.d(TAG, "loadSharedWithMeAlbums: photos endpoint total stubs=${albumStubs.size}")

        // Group by shareId — each album needs its shareId to decrypt its metadata.
        val byShare = albumStubs.groupBy { it.shareId }
        Log.d(TAG, "loadSharedWithMeAlbums: stubs grouped by shareId — groups=${byShare.size} nullShareIdGroup=${byShare[null]?.size ?: 0}")

        for ((shareId, stubs) in byShare) {
            if (shareId == null) {
                // No shareId — can't decrypt; fall back to a linkId stub name.
                for (stub in stubs) {
                    if (!seenLinkIds.add(stub.linkId)) continue
                    result.add(Album(
                        linkId = stub.linkId,
                        name = stub.linkId.take(8),
                        photoCount = stub.photoCount,
                        coverLinkId = stub.coverLinkId,
                        lastActivityTimeMs = stub.lastActivityTime?.let { it * 1000L },
                        coverThumbnailUrl = resolveOfflineCoverUrl(stub.coverLinkId, stub.linkId),
                    ))
                }
                continue
            }

            try {
                // v1 bootstrap (`drive/shares/{id}`), not v2 — v2 404s for freshly created
                // album sub-shares while v1 resolves both.
                val shareDetails = semaphore.withPermit {
                    manager.invoke { getShareBootstrap(shareId) }.valueOrThrow
                }
                val shareKeyArmored = shareDetails.key
                val sharePassphraseArmored = shareDetails.passphrase
                if (shareKeyArmored == null || sharePassphraseArmored == null) {
                    Log.w(TAG, "loadSharedWithMeAlbums: share $shareId returned without Key/Passphrase — dropping ${stubs.size} stub(s). creator=${shareDetails.creator}")
                    continue
                }

                val shareKeyBytes = try {
                    cryptoHelper.decryptExternalShareKey(userId, shareKeyArmored, sharePassphraseArmored)
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    Log.w(TAG, "loadSharedWithMeAlbums: share key decrypt failed shareId=$shareId: ${e.message}", e)
                    continue
                }
                Log.d(TAG, "loadSharedWithMeAlbums: shareKey decrypted for shareId=$shareId, processing ${stubs.size} stub(s)")

                val fetchedLinks = mutableListOf<LinkCoreDto>()
                for (chunk in stubs.map { it.linkId }.chunked(BATCH_SIZE)) {
                    try {
                        semaphore.withPermit {
                            val resp = manager.invoke {
                                fetchLinkMetadata(shareId, BatchLinksRequest(chunk))
                            }.valueOrThrow
                            fetchedLinks.addAll(resp.links)
                        }
                    } catch (e: Exception) {
                        if (e is kotlinx.coroutines.CancellationException) throw e
                        Log.w(TAG, "loadSharedWithMeAlbums: fetchLinkMetadata failed shareId=$shareId: ${e.message}", e)
                    }
                }

                val linkMap = fetchedLinks.associateBy { it.linkId }

                for (stub in stubs) {
                    if (!seenLinkIds.add(stub.linkId)) continue
                    val link = linkMap[stub.linkId]
                    var name = stub.linkId.take(8)
                    if (link != null) {
                        val nodeKey = link.nodeKey
                        val nodePassphrase = link.nodePassphrase
                        val encName = link.name
                        if (nodeKey != null && nodePassphrase != null && encName != null) {
                            try {
                                val nodeKeyBytes = cryptoHelper.decryptNodeKey(nodeKey, nodePassphrase, shareKeyBytes)
                                // Album Name is encrypted to the PARENT (root link) key; the backend substitutes
                                // the PKESK to one the share holder can open. Fall back to the share key bytes in
                                // case the substitution landed at the share level instead.
                                name = cryptoHelper.decryptLinkName(encName, nodeKeyBytes)
                                    ?: cryptoHelper.decryptLinkName(encName, shareKeyBytes)
                                    ?: stub.linkId.take(8)
                            } catch (e: Exception) {
                                if (e is kotlinx.coroutines.CancellationException) throw e
                                Log.w(TAG, "loadSharedWithMeAlbums: name decrypt failed linkId=${stub.linkId}: ${e.message}")
                            }
                        }
                    }
                    result.add(Album(
                        linkId = stub.linkId,
                        name = name,
                        photoCount = stub.photoCount,
                        coverLinkId = stub.coverLinkId,
                        lastActivityTimeMs = stub.lastActivityTime?.let { it * 1000L },
                        coverThumbnailUrl = resolveOfflineCoverUrl(stub.coverLinkId, stub.linkId),
                        sharingShareId = shareId,
                        sharedByEmail = shareDetails.creator,
                        volumeId = stub.volumeId,
                    ))
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.w(TAG, "loadSharedWithMeAlbums: failed for shareId=$shareId: ${e.message}", e)
            }
        }

        val resultsAfterPhotosEndpoint = result.size
        Log.d(TAG, "loadSharedWithMeAlbums: after photos endpoint decryption — albums=$resultsAfterPhotosEndpoint")

        // Backup: drive/v2/sharedwithme — catches album shares the Photos endpoint missed
        // (e.g. backend race after accept). Merged with primary results.
        val sharedLinks = mutableListOf<SharedWithMeLinkDto>()
        var swmAnchor: String? = null
        var v2EndpointPages = 0
        var v2RawTotal = 0
        try {
            do {
                val resp = semaphore.withPermit {
                    manager.invoke { getSharedWithMe(swmAnchor) }.valueOrThrow
                }
                v2EndpointPages++
                v2RawTotal += resp.links.size
                val typeHistogram = resp.links.groupingBy { it.shareTargetType }.eachCount()
                Log.d(TAG, "loadSharedWithMeAlbums: v2 page=$v2EndpointPages total=${resp.links.size} types=$typeHistogram more=${resp.more}")
                // ShareTargetType: 1=File 2=Folder 3=Album 4=Photo. Cross-account sharing rewrites
                // a Photos album to type 2 (Folder), so a type-3-only filter loses albums shared
                // from outside this app. Accept 2 and 3 and let the downstream decrypt decide:
                // unlockable NodePassphrase+Name = a real album, otherwise dropped.
                val acceptedTargetTypes = setOf(2, 3)
                sharedLinks.addAll(
                    resp.links.filter {
                        it.shareTargetType in acceptedTargetTypes && it.linkId !in seenLinkIds
                    },
                )
                swmAnchor = if (resp.more) resp.anchorId else null
            } while (swmAnchor != null)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.w(TAG, "loadSharedWithMeAlbums: drive/v2/sharedwithme backup failed: ${e.message}", e)
        }
        Log.d(TAG, "loadSharedWithMeAlbums: v2 backup raw=$v2RawTotal filteredAlbumOnly=${sharedLinks.size}")

        for (link in sharedLinks) {
            if (!seenLinkIds.add(link.linkId)) continue
            try {
                // v1 bootstrap — album sub-shares 404 on v2 (see above).
                val shareDetails = semaphore.withPermit {
                    manager.invoke { getShareBootstrap(link.shareId) }.valueOrThrow
                }
                val shareKeyArmored = shareDetails.key ?: continue
                val sharePassphraseArmored = shareDetails.passphrase ?: continue
                val shareKeyBytes = try {
                    cryptoHelper.decryptExternalShareKey(userId, shareKeyArmored, sharePassphraseArmored)
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    Log.w(TAG, "loadSharedWithMeAlbums: v2 share key decrypt failed: ${e.message}")
                    continue
                }
                val linkDetails = try {
                    semaphore.withPermit {
                        manager.invoke { getFullLinkDetails(link.shareId, link.linkId) }.valueOrThrow
                    }.link
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    Log.w(TAG, "loadSharedWithMeAlbums: v2 link fetch failed: ${e.message}")
                    null
                }
                var decryptedName: String? = null
                if (linkDetails != null) {
                    val nodeKey = linkDetails.nodeKey
                    val nodePassphrase = linkDetails.nodePassphrase
                    val encName = linkDetails.name
                    if (nodeKey != null && nodePassphrase != null && encName != null) {
                        try {
                            val nodeKeyBytes = cryptoHelper.decryptNodeKey(nodeKey, nodePassphrase, shareKeyBytes)
                            decryptedName = cryptoHelper.decryptLinkName(encName, nodeKeyBytes)
                            Log.d(TAG, "loadSharedWithMeAlbums: linkId=${link.linkId.take(12)} decrypt OK name='${(decryptedName ?: "").take(40)}'")
                        } catch (e: Exception) {
                            if (e is kotlinx.coroutines.CancellationException) throw e
                            Log.w(TAG, "loadSharedWithMeAlbums: v2 name decrypt failed for ${link.linkId.take(12)}: ${e.message}")
                        }
                    }
                }
                // Drop entries whose name couldn't be decrypted — a regular folder share the
                // Photos app can't render, or a backend bug; either way not gallery material.
                if (decryptedName.isNullOrBlank()) {
                    Log.d(TAG, "loadSharedWithMeAlbums: dropping ${link.linkId.take(12)} — decrypt failed or non-album share")
                    continue
                }
                result.add(Album(
                    linkId = link.linkId,
                    name = decryptedName,
                    photoCount = 0,
                    coverLinkId = null,
                    lastActivityTimeMs = null,
                    sharingShareId = link.shareId,
                    sharedByEmail = shareDetails.creator,
                    volumeId = link.volumeId,
                ))
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.w(TAG, "loadSharedWithMeAlbums: v2 failed for ${link.linkId}: ${e.message}")
            }
        }

        Log.d(TAG, "loadSharedWithMeAlbums: DONE — photosStubs=${albumStubs.size} afterPhotosDecrypt=$resultsAfterPhotosEndpoint v2AlbumRaw=${sharedLinks.size} finalTotal=${result.size}")
        result
    }

    /**
     * Lists individual photos shared by public link (the photo-link counterpart to shared
     * albums). Read-only — walks the "shared by me" feed, drops album linkIds (surfaced
     * separately), and keeps only linkIds present in the local photo-listing DB, which both
     * confirms they're library photos and supplies the already-decrypted name/thumbnail/MIME
     * for free. Public URLs are resolved lazily by the manage-link sheet, not here.
     */
    suspend fun loadSharedByMePhotos(userId: UserId): List<SharedPhoto> = withContext(Dispatchers.IO) {
        val volumeId = shareService.getVolumeId(userId)
        val manager = apiProvider.get<DriveApiService>(userId)

        // 1. Walk the shared-by-me feed.
        val sharedLinkIds = mutableListOf<String>()
        var anchorId: String? = null
        var page = 0
        try {
            do {
                val resp = semaphore.withPermit {
                    manager.invoke { getSharedByMeListings(volumeId, anchorId) }.valueOrThrow
                }
                page++
                sharedLinkIds.addAll(resp.links.map { it.linkId })
                anchorId = if (resp.more) resp.anchorId else null
                Log.d(TAG, "loadSharedByMePhotos: feed page=$page links=${resp.links.size} more=${resp.more}")
            } while (anchorId != null)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            // No feed → no shared photos; don't fail the caller (shared-albums section renders).
            Log.w(TAG, "loadSharedByMePhotos: shares feed failed: ${e.message}", e)
            return@withContext emptyList()
        }
        if (sharedLinkIds.isEmpty()) return@withContext emptyList()

        // 2. Exclude albums already represented in the shared-albums section.
        val sharedAlbumLinkIds = runCatching {
            albumService.loadAlbums(userId).filter { it.isShared }.map { it.linkId }.toSet()
        }.getOrDefault(emptySet())
        val candidateLinkIds = sharedLinkIds.distinct().filter { it !in sharedAlbumLinkIds }
        if (candidateLinkIds.isEmpty()) return@withContext emptyList()

        // 3. Keep only library photos (present in the stream listing) and build the item
        //    straight from the already-decrypted row.
        val rows = runCatching { photoListingDao.getByLinkIds(candidateLinkIds) }
            .getOrDefault(emptyList())
            .associateBy { it.linkId }
        val result = candidateLinkIds.mapNotNull { linkId ->
            val row = rows[linkId] ?: return@mapNotNull null
            runCatching {
                SharedPhoto(
                    linkId = linkId,
                    displayName = row.displayName,
                    isVideo = row.mimeType.startsWith("video/"),
                    thumbnailUrl = row.thumbnailUrl,
                )
            }.getOrNull()
        }
        Log.d(TAG, "loadSharedByMePhotos: feedLinks=${sharedLinkIds.size} candidates=${candidateLinkIds.size} photos=${result.size}")
        result
    }

    suspend fun loadShareInvitations(userId: UserId, shareId: String): List<ShareInvitation> = withContext(Dispatchers.IO) {
        val manager = apiProvider.get<DriveApiService>(userId)
        val resp = semaphore.withPermit {
            manager.invoke { listShareInvitations(shareId) }.valueOrThrow
        }
        resp.invitations.map { ShareInvitation(it.invitationId, it.inviteeEmail, it.permissions) }
    }

    suspend fun revokeShareInvitation(userId: UserId, shareId: String, invitationId: String): Unit = withContext(Dispatchers.IO) {
        val manager = apiProvider.get<DriveApiService>(userId)
        semaphore.withPermit {
            manager.invoke { revokeInvitation(shareId, invitationId) }.valueOrThrow
        }
        Log.d(TAG, "revokeShareInvitation: revoked $invitationId from share $shareId")
    }

    suspend fun loadShareMembers(userId: UserId, shareId: String): List<ShareMember> = withContext(Dispatchers.IO) {
        val manager = apiProvider.get<DriveApiService>(userId)
        val resp = semaphore.withPermit {
            manager.invoke { getShareMembers(shareId) }.valueOrThrow
        }
        resp.members.map { dto ->
            val email = dto.email ?: dto.inviteeEmail ?: dto.memberId
            ShareMember(dto.memberId, email, dto.permissions)
        }
    }

    suspend fun removeShareMember(userId: UserId, shareId: String, memberId: String): Unit = withContext(Dispatchers.IO) {
        val manager = apiProvider.get<DriveApiService>(userId)
        semaphore.withPermit {
            manager.invoke { removeShareMember(shareId, memberId) }.valueOrThrow
        }
        Log.d(TAG, "removeShareMember: removed $memberId from share $shareId")
    }

    suspend fun loadPendingInvitations(userId: UserId): List<PendingInvitation> = withContext(Dispatchers.IO) {
        val manager = apiProvider.get<DriveApiService>(userId)
        val stubs = mutableListOf<GlobalInvitationDto>()
        var anchorId: String? = null
        var page = 0
        var rawTotal = 0
        do {
            val resp = semaphore.withPermit {
                manager.invoke { getPendingInvitations(anchorId) }.valueOrThrow
            }
            page++
            rawTotal += resp.invitations.size
            val typeHistogram = resp.invitations.groupingBy { it.shareTargetType }.eachCount()
            Log.d(TAG, "loadPendingInvitations: page=$page total=${resp.invitations.size} types=$typeHistogram more=${resp.more}")
            stubs.addAll(resp.invitations.filter { it.shareTargetType == 3 })
            anchorId = if (resp.more) resp.anchorId else null
        } while (anchorId != null)
        Log.d(TAG, "loadPendingInvitations: raw=$rawTotal filteredAlbumOnly=${stubs.size}")

        val result = stubs.mapNotNull { stub ->
            try {
                val detail = semaphore.withPermit {
                    manager.invoke { getInvitationDetail(stub.invitationId) }.valueOrThrow
                }.invitation
                PendingInvitation(
                    invitationId = stub.invitationId,
                    shareId = stub.shareId,
                    inviterEmail = detail.inviterEmail,
                )
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.w(TAG, "loadPendingInvitations: detail fetch failed for ${stub.invitationId}: ${e.message}", e)
                null
            }
        }
        Log.d(TAG, "loadPendingInvitations: DONE — raw=$rawTotal albumStubs=${stubs.size} withDetail=${result.size}")
        result
    }

    suspend fun declineInvitation(userId: UserId, invitationId: String): Unit = withContext(Dispatchers.IO) {
        val manager = apiProvider.get<DriveApiService>(userId)
        semaphore.withPermit {
            manager.invoke { declineInvitation(invitationId) }.valueOrThrow
        }
        Log.d(TAG, "declineInvitation: declined $invitationId")
    }

    suspend fun acceptInvitation(userId: UserId, invitationId: String): Unit = withContext(Dispatchers.IO) {
        val manager = apiProvider.get<DriveApiService>(userId)
        val detail = semaphore.withPermit {
            manager.invoke { getInvitationDetail(invitationId) }.valueOrThrow.invitation
        }
        val keyPacket = detail.keyPacket
            ?: error("Invitation $invitationId has no KeyPacket")
        val sessionKeySignature = cryptoHelper.signInvitationKeyPacket(
            userId = userId,
            keyPacketBase64 = keyPacket,
            inviteeEmail = detail.inviteeEmail,
        )
        semaphore.withPermit {
            manager.invoke {
                acceptInvitation(
                    invitationId,
                    AcceptInvitationRequest(sessionKeySignature = sessionKeySignature),
                )
            }.valueOrThrow
        }
        Log.d(TAG, "acceptInvitation: accepted $invitationId")
    }

    /**
     * Builds the album-shaped share material the create-share endpoint expects. The album
     * link's passphrase + name are both encrypted under the root link key (Photos hierarchy),
     * so this pulls the parent key via [PhotosShareService.getRootLinkKeyBytes].
     */
    private suspend fun buildAlbumShareMaterial(
        userId: UserId,
        albumLinkId: String,
        albumLinkDetails: eu.akoos.photos.data.api.dto.BatchLinkDto,
        signingKey: eu.akoos.photos.data.crypto.AddressSigningKey,
    ): eu.akoos.photos.data.crypto.DriveCryptoHelper.GeneratedAlbumShare {
        val nodeKey = albumLinkDetails.link.nodeKey
            ?: error("Album $albumLinkId is missing NodeKey — cannot create share")
        val nodePassphrase = albumLinkDetails.link.nodePassphrase
            ?: error("Album $albumLinkId is missing NodePassphrase — cannot create share")
        val nameArmored = albumLinkDetails.link.name
            ?: error("Album $albumLinkId is missing Name — cannot create share")
        val parentKeyBytes = shareService.getRootLinkKeyBytes(userId)
            ?: error("Root link key unavailable — cannot create share for $albumLinkId")
        return cryptoHelper.generateShareForAlbum(
            albumNodeKeyArmored = nodeKey,
            albumNodePassphraseArmored = nodePassphrase,
            albumNameArmored = nameArmored,
            parentLinkKeyBytes = parentKeyBytes,
            addressPublicKeyArmored = signingKey.publicKeyArmored,
            signerKeyBytes = signingKey.unlockedKeyBytes,
        )
    }

    /**
     * Singleton-scoped state for the in-flight Save-to-library job, exposed as a StateFlow so
     * any VM opening the same album sees live copy progress across navigations. The coroutine
     * is rooted in the singleton [saveToLibraryScope] so backing out mid-copy doesn't lose
     * progress or leave a half-populated album on the server.
     */
    sealed interface SaveToLibraryState {
        data object Idle : SaveToLibraryState
        data class Running(
            val sourceAlbumLinkId: String,
            val copied: Int,
            val total: Int,
        ) : SaveToLibraryState
        data class Done(
            val sourceAlbumLinkId: String,
            val newAlbumLinkId: String,
            val copiedCount: Int,
            val failedCount: Int,
            val totalRequested: Int,
        ) : SaveToLibraryState
        data class Failed(
            val sourceAlbumLinkId: String,
            val reason: String?,
        ) : SaveToLibraryState
        /** User aborted mid-flight; carries the copied/total snapshot at cancellation. */
        data class Cancelled(
            val sourceAlbumLinkId: String,
            val copied: Int,
            val total: Int,
        ) : SaveToLibraryState
    }

    private val saveToLibraryScope = CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + Dispatchers.IO,
    )
    private val _saveToLibraryState = kotlinx.coroutines.flow.MutableStateFlow<SaveToLibraryState>(SaveToLibraryState.Idle)
    val saveToLibraryState: kotlinx.coroutines.flow.StateFlow<SaveToLibraryState> = _saveToLibraryState

    /** Handle on the in-flight job so [cancelSaveToLibrary] can abort one copy without
     *  cancelling the whole [saveToLibraryScope]. */
    private var saveToLibraryJob: Job? = null

    /**
     * Fire-and-forget launcher; ignored while a copy is already running. Callers observe
     * [saveToLibraryState] for progress + outcome rather than awaiting a return.
     */
    fun startSaveSharedAlbumToOwnLibrary(
        userId: UserId,
        sharingShareId: String,
        sourceAlbumLinkId: String,
        sourceAlbumDecryptedName: String,
        sourceVolumeId: String,
    ) {
        val current = _saveToLibraryState.value
        if (current is SaveToLibraryState.Running) return
        _saveToLibraryState.value = SaveToLibraryState.Running(sourceAlbumLinkId, 0, 0)
        saveToLibraryJob = saveToLibraryScope.launch {
            try {
                val r = saveSharedAlbumToOwnLibrary(
                    userId = userId,
                    sharingShareId = sharingShareId,
                    sourceAlbumLinkId = sourceAlbumLinkId,
                    sourceAlbumDecryptedName = sourceAlbumDecryptedName,
                    sourceVolumeId = sourceVolumeId,
                    onProgress = { copied, total ->
                        _saveToLibraryState.value =
                            SaveToLibraryState.Running(sourceAlbumLinkId, copied, total)
                    },
                )
                _saveToLibraryState.value = SaveToLibraryState.Done(
                    sourceAlbumLinkId = sourceAlbumLinkId,
                    newAlbumLinkId = r.newAlbumLinkId,
                    copiedCount = r.copiedCount,
                    failedCount = r.failedCount,
                    totalRequested = r.totalRequested,
                )
            } catch (ce: CancellationException) {
                // Capture the last running snapshot for the UI. Partial copies already on the
                // server stay — rolling them back here would race the user re-tapping Save.
                val last = _saveToLibraryState.value
                val (copied, total) = if (last is SaveToLibraryState.Running)
                    last.copied to last.total else 0 to 0
                _saveToLibraryState.value = SaveToLibraryState.Cancelled(
                    sourceAlbumLinkId = sourceAlbumLinkId,
                    copied = copied,
                    total = total,
                )
                throw ce
            } catch (e: Exception) {
                _saveToLibraryState.value = SaveToLibraryState.Failed(
                    sourceAlbumLinkId = sourceAlbumLinkId,
                    reason = e.message,
                )
            } finally {
                saveToLibraryJob = null
            }
        }
    }

    /** Cancels an in-flight save-to-library job; safe to call when none is running. */
    fun cancelSaveToLibrary() {
        saveToLibraryJob?.cancel()
        saveToLibraryJob = null
    }

    /** Resets to Idle after the VM consumes a terminal Done/Failed/Cancelled state. */
    fun acknowledgeSaveToLibraryResult() {
        val current = _saveToLibraryState.value
        if (current is SaveToLibraryState.Done ||
            current is SaveToLibraryState.Failed ||
            current is SaveToLibraryState.Cancelled
        ) {
            _saveToLibraryState.value = SaveToLibraryState.Idle
        }
    }

    suspend fun saveSharedAlbumToOwnLibrary(
        userId: UserId,
        sharingShareId: String,
        sourceAlbumLinkId: String,
        sourceAlbumDecryptedName: String,
        sourceVolumeId: String,
        onProgress: (copied: Int, total: Int) -> Unit = { _, _ -> },
    ): SaveSharedAlbumResult = withContext(Dispatchers.IO) {
        val manager = apiProvider.get<DriveApiService>(userId)

        // === Phase 1: pin down the caller's own photos infrastructure ============
        val ownVolumeId = shareService.getVolumeId(userId)
        val signingKey = cryptoHelper.getAddressSigningKey(userId)

        // === Phase 2: decrypt the source album's keys ============================
        val sharedShareBootstrap = semaphore.withPermit {
            manager.invoke { getShareBootstrap(sharingShareId) }.valueOrThrow
        }
        val sharedShareKeyBytes = cryptoHelper.decryptExternalShareKey(
            userId,
            sharedShareBootstrap.key ?: error("Shared share bootstrap missing Key"),
            sharedShareBootstrap.passphrase ?: error("Shared share bootstrap missing Passphrase"),
        )
        val sourceAlbumDetail = linkDetailHelpers
            .batchFetchLinkDetailsViaShare(userId, sharingShareId, listOf(sourceAlbumLinkId))[sourceAlbumLinkId]
            ?.link ?: error("Could not fetch source album link metadata")
        val sourceAlbumKeyBytes = cryptoHelper.decryptNodeKey(
            sourceAlbumDetail.nodeKey ?: error("Source album has no NodeKey"),
            sourceAlbumDetail.nodePassphrase ?: error("Source album has no NodePassphrase"),
            sharedShareKeyBytes,
        )

        // === Phase 3: create the new owned album =================================
        val sanitizedName = sourceAlbumDecryptedName.ifBlank { "Saved album" }
        val newAlbum = albumService.createDriveAlbum(userId, sanitizedName)
        val newAlbumLinkId = newAlbum.linkId

        // === Phase 4: gather the Photos library root keys =========================
        // Drive Photos albums are memberships, not folder children: photos live under the
        // Photos root and albums reference them by linkId. So the copy lands in the root (a
        // normal library photo) and a follow-up addPhotosToAlbum attaches it — copying into the
        // album folder directly would keep the photos off the timeline.
        val ownPhotosRootLinkId = shareService.photosRootLinkId()
            ?: error("Photos root linkId unavailable")
        val ownRootArmoredKey = shareService.rootLinkArmoredKey()
            ?: error("Photos root armored key unavailable")
        val ownRootHashKeyBytes = shareService.rootNodeHashKeyBytes()
            ?: error("Photos root NodeHashKey unavailable")
        val ownRootPublicKey = cryptoHelper.withCryptoLock {
            cryptoContext.pgpCrypto.getPublicKey(ownRootArmoredKey)
        }

        // === Phase 5: walk the shared album's children + copy each ===============
        val children = mutableListOf<String>()
        var anchor: String? = null
        do {
            val resp = semaphore.withPermit {
                manager.invoke { getAlbumChildren(sourceVolumeId, sourceAlbumLinkId, anchor) }.valueOrThrow
            }
            resp.photos.forEach { children.add(it.linkId) }
            anchor = if (resp.more) resp.anchorId else null
        } while (anchor != null)

        val photoDetailMap = linkDetailHelpers
            .batchFetchLinkDetailsViaShare(userId, sharingShareId, children.distinct())

        var copied = 0
        val failures = mutableListOf<String>()
        val copiedLinkIds = mutableListOf<String>()
        val totalToCopy = children.distinct().size
        // Initial emission so the UI shows "0 of N" instead of a generic spinner once the
        // children list is in hand.
        onProgress(0, totalToCopy)
        for (photoLinkId in children.distinct()) {
            val detail = photoDetailMap[photoLinkId]
            val photoLink = detail?.link
            if (photoLink?.nodePassphrase == null || photoLink.name == null) {
                failures.add(photoLinkId)
                continue
            }
            try {
                val newNodePass = cryptoHelper.reencryptNodePassphraseForCopy(
                    sourceNodePassphraseArmored = photoLink.nodePassphrase,
                    sourceParentKeyBytes = sourceAlbumKeyBytes,
                    targetParentPublicKeyArmored = ownRootPublicKey,
                    signerKeyBytes = signingKey.unlockedKeyBytes,
                )
                val newName = cryptoHelper.reencryptLinkNameForCopy(
                    sourceNameArmored = photoLink.name,
                    sourceParentKeyBytes = sourceAlbumKeyBytes,
                    targetParentPublicKeyArmored = ownRootPublicKey,
                    signerKeyBytes = signingKey.unlockedKeyBytes,
                )
                val plainName = String(newName.plainBytes, Charsets.UTF_8)
                val newNameHash = cryptoHelper.computeNameHash(plainName, ownRootHashKeyBytes)

                // Reuse the source's existing contentHash. The share endpoint nests the Photo
                // block under FileProperties.ActiveRevision (the BatchLink wrapper is null for
                // shared-with-me fetches), so walk that first and fall back to the wrapper.
                val sourceContentHash = photoLink.fileProperties?.activeRevision?.photo?.contentHash
                    ?: detail.photo?.contentHash
                val photosBlock = sourceContentHash?.let {
                    eu.akoos.photos.data.api.dto.CopyLinkRequest.PhotosCopyData(
                        contentHash = it,
                        relatedPhotos = emptyList(),
                    )
                }
                if (photosBlock == null) {
                    Log.w(TAG, "saveSharedAlbumToOwnLibrary: no contentHash for $photoLinkId — will skip Photos block")
                }

                // The backend accepts NodePassphraseSignature + SignatureEmail ONLY when the
                // source link is anonymous (blank signatureEmail, e.g. public-folder uploads);
                // setting them on an owner-signed photo is rejected with code 2001.
                // NameSignatureEmail is always our address (we re-signed the Name above).
                val sourceSignatureEmail = photoLink.signatureEmail.orEmpty()
                val sourceIsAnonymous = sourceSignatureEmail.isEmpty()
                val nameSignatureEmail = signingKey.email
                val signatureEmailToSend = if (sourceIsAnonymous) signingKey.email else null
                val nodePassphraseSigToSend = if (sourceIsAnonymous) newNodePass.armoredSignature else null

                val request = eu.akoos.photos.data.api.dto.CopyLinkRequest(
                    name = newName.armoredName,
                    hash = newNameHash,
                    targetVolumeId = ownVolumeId,
                    targetParentLinkId = ownPhotosRootLinkId,
                    nodePassphrase = newNodePass.armoredPassphrase,
                    nameSignatureEmail = nameSignatureEmail,
                    nodePassphraseSignature = nodePassphraseSigToSend,
                    signatureEmail = signatureEmailToSend,
                    photos = photosBlock,
                )
                val resp = semaphore.withPermit {
                    manager.invoke { copyLink(sourceVolumeId, photoLinkId, request) }.valueOrThrow
                }
                resp.linkId?.let { copiedLinkIds.add(it) }
                copied++
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.w(TAG, "saveSharedAlbumToOwnLibrary: copy failed linkId=$photoLinkId msg=${e.message}", e)
                failures.add(photoLinkId)
            }
            // Emit on every iteration so the counter walks forward instead of jumping 0 → N.
            onProgress(copied, totalToCopy)
        }

        // === Phase 6: attach the freshly-copied photos to the new album ==========
        // The copies landed in the library root; this step attaches them to the album
        // (otherwise it stays empty despite the photos being in the library).
        if (copiedLinkIds.isNotEmpty()) {
            try {
                albumService.addPhotosToAlbum(userId, newAlbumLinkId, copiedLinkIds)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.w(TAG, "saveSharedAlbumToOwnLibrary: addPhotosToAlbum failed " +
                    "(${copiedLinkIds.size} copies landed in library but didn't reach album): ${e.message}", e)
            }
        }

        SaveSharedAlbumResult(
            newAlbumLinkId = newAlbumLinkId,
            copiedCount = copied,
            failedCount = failures.size,
            totalRequested = children.distinct().size,
        )
    }

    data class SaveSharedAlbumResult(
        val newAlbumLinkId: String,
        val copiedCount: Int,
        val failedCount: Int,
        val totalRequested: Int,
    )

    private companion object {
        // Wire Name is just a label for the share record; the album's real name reaches the
        // server via [GeneratedAlbumShare.nameKeyPacketBase64].
        private const val SHARE_DISPLAY_NAME = "New Share"
    }
}
