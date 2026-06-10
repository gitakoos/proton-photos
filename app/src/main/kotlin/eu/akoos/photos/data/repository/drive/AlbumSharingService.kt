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
import eu.akoos.photos.data.crypto.DriveCryptoHelper
import eu.akoos.photos.data.db.dao.PhotoListingDao
import eu.akoos.photos.domain.entity.Album
import eu.akoos.photos.domain.entity.PendingInvitation
import eu.akoos.photos.domain.entity.ShareInvitation
import eu.akoos.photos.domain.entity.ShareMember
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
     * Mirrors the owned-album cover rehydrate in [AlbumService.loadAlbums]: when the
     * shared-with-me listing returns a `coverLinkId` without a CDN thumbnail URL (the
     * shared endpoint never gives us one), look the cover up in the local listing DB and
     * then in the on-disk thumbnail cache so previously-opened shared albums keep their
     * covers offline instead of falling back to a placeholder.
     */
    private suspend fun resolveOfflineCoverUrl(coverLinkId: String?, albumLinkId: String? = null): String? {
        val thumbnailCacheDir = File(context.cacheDir, "thumbnails")
        // 1. Server-named cover photo, if we have one. For owner-side albums the
        //    coverLinkId points at the photo whose thumbnail Drive web considers
        //    the canonical cover.
        if (coverLinkId != null) {
            photoListingDao.getByLinkId(coverLinkId)?.thumbnailUrl?.let { return it }
            val file = File(thumbnailCacheDir, "thumb_$coverLinkId.jpg")
            if (file.exists() && file.length() > 0) return "file://${file.absolutePath}"
        }
        // 2. Membership fallback. Cross-account shared albums often surface a
        //    coverLinkId that doesn't line up with the recipient's view of the
        //    same photos (the backend rewrites linkIds on its side), so the
        //    direct lookup returns null. Any decrypted photo in the album
        //    works as a stand-in cover until the recipient navigates in deep
        //    enough for the actual cover to land in cache.
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

        // Step 1 — find or create the album's share. We use the same path as inviteToAlbum so
        // the public-link flow and the invite-by-email flow share the same share object.
        // For reuse we also need the share session key (PKESK plaintext) so step 2 can
        // re-encrypt it under the salted URL password. For new shares it falls out of
        // generateShareForAlbum for free.
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
                            // STANDARD (2) — the share type for a sub-share that exposes a
                            // single link to recipients. PHOTO (4) is reserved for the user's
                            // own root Photos volume share and is rejected by Drive web's
                            // photos UI when used for individual album shares.
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

        // Step 2 — build the SRP + salted-passphrase + encrypted-URL-password bundle the
        // backend insists on for every new share URL. Earlier builds posted only
        // `CreatorEmail`; the request was rejected with a generic error from the backend
        // because the 8 SRP / passphrase / password fields are all required. The bundle
        // mirrors the official Drive Android client's CreateShareUrlInfo pipeline.
        Log.d(TAG, "createAlbumShareLink: shareId=$albumShareId fetching modulus")
        val sessionId = sessionProvider.getSessionId(userId)
        val modulus = authRepository.randomModulus(sessionId)
        Log.d(TAG, "createAlbumShareLink: modulus fetched (id=${modulus.modulusId.take(8)}…) — building crypto package")
        val pkg = cryptoHelper.buildShareUrlCryptoPackage(
            addressPublicKeyArmored = signingKey.publicKeyArmored,
            shareSessionKey = shareSessionKey,
            modulus = modulus.modulus,
            modulusId = modulus.modulusId,
        )
        Log.d(TAG, "createAlbumShareLink: crypto package built — POSTing /urls")
        val urlResp = try {
            semaphore.withPermit {
                manager.invoke {
                    createShareUrl(
                        albumShareId,
                        CreateShareUrlRequest(
                            // The backend treats this field as required and rejects null with
                            // "Missing required attribute MaxAccesses". 0 means unlimited —
                            // same constant as Drive Android's `CreateShareUrlInfo.MAX_ACCESSES`.
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
            Log.e(TAG, "createAlbumShareLink: createShareUrl failed shareId=$albumShareId msg=${e.message}", e)
            // Auto-rollback: an album share that never got a URL minted AND has no members
            // / invitations / other URLs is an orphan — leaving it surfaces a misleading
            // "shared by me" badge on the album even though no one has access. We always
            // do this orphan check (not just when we minted the share in this call) so a
            // user who retries after a previous failed mint isn't permanently stuck with
            // the leftover share.
            runCatching {
                val members = runCatching {
                    semaphore.withPermit { manager.invoke { getShareMembers(albumShareId) }.valueOrThrow }.members
                }.getOrDefault(emptyList())
                val invitations = runCatching {
                    semaphore.withPermit { manager.invoke { listShareInvitations(albumShareId) }.valueOrThrow }.invitations
                }.getOrDefault(emptyList())
                val urls = runCatching {
                    semaphore.withPermit { manager.invoke { getShareUrls(albumShareId) }.valueOrThrow }.shareUrls
                }.getOrDefault(emptyList())
                if (members.isEmpty() && invitations.isEmpty() && urls.isEmpty()) {
                    Log.d(TAG, "createAlbumShareLink: rolling back orphan share $albumShareId (no members/invites/urls)")
                    semaphore.withPermit { manager.invoke { deleteShare(albumShareId) }.valueOrThrow }
                } else {
                    Log.d(TAG, "createAlbumShareLink: keeping share $albumShareId — has ${members.size}m/${invitations.size}i/${urls.size}u")
                }
            }.onFailure { rb ->
                Log.w(TAG, "createAlbumShareLink: orphan cleanup attempt failed: ${rb.message}", rb)
            }
            // Reference `isFreshShare` so the unused-warning doesn't fire and we keep a
            // visible record of whether THIS call minted the share (for future log triage).
            Log.d(TAG, "createAlbumShareLink: cleanup pass complete (isFreshShare=$isFreshShare)")
            throw e
        }
        val finalUrl = urlResp.shareUrl?.publicUrl
            ?: "https://drive.proton.me/urls/${urlResp.shareUrl?.token}"
        Log.d(TAG, "createAlbumShareLink: SUCCESS url=$finalUrl")
        finalUrl
    }

    /**
     * Disables a single public share-URL without touching member shares on the same share.
     * Looks up the URLs via [getShareUrls] (callers typically only have the parent shareId
     * in state) and issues `DELETE drive/shares/{shareId}/urls/{shareUrlId}` for each.
     * A share with no URLs is left intact so its members and invitations keep working.
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
                Log.e(TAG, "revokeShareUrlOnly failed: shareId=$shareId urlId=$urlId msg=${e.message}", e)
                throw e
            }
        }
    }

    /**
     * Upgrades or downgrades a member's permission bitmap on an album share.
     * Permissions: 4 = viewer (read), 6 = editor (read + write). Drive currently
     * accepts 4 and 6; other bitmaps are rejected server-side.
     */
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
            Log.e(TAG, "changeMemberPermission failed: shareId=$shareId memberId=$memberId perms=$permissions msg=${e.message}", e)
            throw e
        }
    }

    /**
     * Same permission swap for a PENDING invitation the invitee has not accepted
     * yet. Plain permissions-only update — no key-packet rework, mirroring the
     * official client's UpdateInvitationPermissions use case.
     */
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
            Log.e(TAG, "changeInvitationPermission failed: shareId=$shareId invitationId=$invitationId perms=$permissions msg=${e.message}", e)
            throw e
        }
    }

    /**
     * Invites a Proton account by email to an album.
     *
     * If the album already carries a `sharing.shareId` we reuse it (avoids duplicate
     * shares). Otherwise we POST `drive/volumes/{volumeId}/shares` — the only endpoint
     * the backend accepts. The legacy `drive/v2/shares/{shareId}/links/{linkId}/share`
     * returns "Path not found".
     */
    suspend fun inviteToAlbum(userId: UserId, albumLinkId: String, email: String): Unit = withContext(Dispatchers.IO) {
        val volumeId = shareService.getVolumeId(userId)
        val manager = apiProvider.get<DriveApiService>(userId)
        val signingKey = cryptoHelper.getAddressSigningKey(userId)

        val albumLinkDetails = linkDetailHelpers.batchFetchLinkDetails(userId, volumeId, listOf(albumLinkId))[albumLinkId]
            ?: error("Album $albumLinkId not found while inviting to share")
        val existingShareId: String? = albumLinkDetails.sharing?.shareId

        // Determine the volume-owner address BEFORE doing any share work. All Photos
        // crypto operations (share creation, invitation, key-packet re-encryption)
        // must use the address that originally bootstrapped the Photos volume — which
        // may differ from today's primary if the user added an alias and promoted it
        // later. A mismatch surfaces on the recipient as "Item cannot be decrypted"
        // because their client verifies the chain against the volume-owner address,
        // not the invite-time primary.
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

        // `shareSessionKey` is the SESSION KEY that encrypts the share's passphrase MESSAGE
        // (PKESK + SEIPD over passphrase bytes) — it's what gets re-encrypted to the invitee's
        // public key in the invitation.
        // If an existing share exists but was created with the WRONG owner address (a
        // bug from earlier builds that passed the user's current primary instead of the
        // volume owner), the backend will reject every invitation through it with code
        // 2008 "The inviter address is not the one used in the context share." Detect
        // this mismatch, delete the stale share, and fall through to fresh creation so
        // the new share is consistent with the volume owner. The album only has invites
        // / members on shares we control; deleting an un-decryptable share is safe.
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
            // v1 bootstrap endpoint — same rationale as createAlbumShareLink: the v2 path
            // 404s on newly created album shares, the v1 path matches Drive Android's
            // `ShareApi.getShareBootstrap` and is what the backend expects for type-4 shares.
            val bootstrap = semaphore.withPermit {
                manager.invoke { getShareBootstrap(effectiveExistingShareId) }.valueOrThrow
            }
            val passphraseArmored = bootstrap.passphrase
                ?: error("Existing share $effectiveExistingShareId has no Passphrase")
            effectiveExistingShareId to cryptoHelper.decryptSharePassphraseSessionKey(userId, passphraseArmored)
        } else {
            // Build the share material the same way as the public-link path — multi-recipient
            // passphrase to (album-node + address), key packets re-encrypted from parent→share.
            // Earlier builds posted self-encrypted gobbledygook here and the backend rejected
            // share creation with "outdated version of the app" (code 2001) because the request
            // describes a share structurally disconnected from the link it claims to share.
            // Build the share material using the VOLUME-OWNER's key — not the user's
            // current primary. The share key wraps the album passphrase the recipient
            // will need to decrypt; its address-PKESK must be encrypted to the address
            // that owns the volume so the chain stays consistent with what Drive web
            // verifies when serving shared-album metadata.
            val generated = buildAlbumShareMaterial(userId, albumLinkId, albumLinkDetails, inviterSigningKey)
            // Verbose pre-POST trace so a logcat capture pinpoints which field length
            // changed between releases — the backend "Make sure you are using the latest
            // version" rejection is opaque on the wire, but the input we hand to it is
            // measurable here.
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
                                // STANDARD (2) — the share type for a sub-share that exposes a
                                // single link to recipients. PHOTO (4) is reserved for the user's
                                // own root Photos volume share and is rejected by Drive web's
                                // photos UI when used for individual album shares.
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
                // The wrapped exception from ProtonCore's ApiManager carries the server's
                // raw response in its message + cause chain. Surface every layer so we can
                // tell whether the rejection is a SemVer parse failure, a missing field,
                // or a crypto-shape mismatch downstream of the request body.
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

        // Resolve the invitee's primary public address key via the v2 endpoint.
        // PublicAddressInfo groups keys into address / catchAll / unverified buckets; the
        // address bucket is the direct match for the email and what we need for invites.
        val publicAddressInfo = runCatching {
            publicAddressRepository.getPublicAddressInfo(userId, email)
        }.getOrElse { e ->
            // Distinguish "not a Proton account" (the recipient doesn't have an account)
            // from "lookup failed" (network / serialization / server hiccup). The first
            // case is permanent and users need to know the address is wrong; the second
            // is transient and shouldn't blame the recipient. We claim "not a Proton
            // account" ONLY when the error text matches one of the known shapes for
            // unknown-recipient responses; anything else surfaces the raw cause so it
            // shows up in bug reports without misleading the user.
            val raw = e.message.orEmpty()
            val isNotProton = raw.contains("does not exist", ignoreCase = true) ||
                raw.contains("invalid recipient", ignoreCase = true) ||
                raw.contains("no such user", ignoreCase = true) ||
                raw.contains("Email address is not allowed", ignoreCase = true) ||
                raw.contains("Address does not exist", ignoreCase = true)
            // The keys lookup occasionally returns HTML (e.g. 404 page, login redirect, or
            // captcha intercept) which kotlinx.serialization surfaces as a JSON parse
            // error mentioning `<html` or "Unexpected JSON token". Don't blame the
            // recipient for that — show a transient-server message instead.
            val isHtmlResponse = raw.contains("Unexpected JSON token", ignoreCase = true) ||
                raw.contains("Expected start of the object", ignoreCase = true) ||
                raw.contains("<html", ignoreCase = true) ||
                raw.contains("but had '<'", ignoreCase = true)
            val friendly = when {
                isNotProton -> "$email is not a Proton account"
                isHtmlResponse -> "Couldn't reach the directory for $email — try again in a moment or double-check the address"
                else -> "Could not invite $email: ${raw.take(140)}"
            }
            throw IllegalArgumentException(friendly)
        }
        // Pick an encryption-capable key for the invitee. Try in this order, matching
        // the official Drive Android client's selection logic combined with Proton's
        // KeyFlags semantics:
        //   1. Address bucket — primary AND canEncrypt (not obsolete, not email-no-encrypt)
        //   2. Address bucket — any key that canEncrypt (handles primary-rotated accounts)
        //   3. Unverified bucket — primary AND canEncrypt
        //   4. Unverified bucket — any key that canEncrypt
        // Without the canEncrypt filter, gopenpgp v2 can pick a key marked obsolete /
        // emailNoEncrypt / forwarding-only and bail with "SessionKey cannot be encrypted".
        val candidateKey =
            publicAddressInfo.address.keys
                .firstOrNull { it.publicKey.isPrimary && it.canEncrypt() }
                ?: publicAddressInfo.address.keys
                    .firstOrNull { it.canEncrypt() }
                ?: publicAddressInfo.unverified?.keys
                    ?.firstOrNull { it.publicKey.isPrimary && it.canEncrypt() }
                ?: publicAddressInfo.unverified?.keys
                    ?.firstOrNull { it.canEncrypt() }
        // Trace the bucket each key was picked from so failure logs identify whether the
        // recipient has any encryption-capable primary at all. Useful when a custom-domain
        // address returns keys that all fail the `canEncrypt()` flag check.
        Log.d(TAG, "inviteToAlbum: recipient=$email addressKeys=${publicAddressInfo.address.keys.size} " +
            "unverifiedKeys=${publicAddressInfo.unverified?.keys?.size ?: 0} " +
            "chosen=${if (candidateKey == null) "none" else "primary=${candidateKey.publicKey.isPrimary} canEncrypt=${candidateKey.canEncrypt()} flags=${candidateKey.flags}"} " +
            "sessionKeySize=${shareSessionKey.key.size}")
        val inviteePublicKeyArmored = candidateKey?.publicKey?.key
            ?: throw IllegalArgumentException("$email is not a Proton account that accepts encrypted invites")

        // PKESK-encrypt the share-passphrase session key to the invitee + detached-sign
        // it. Sign with the SHARE OWNER's address keys (inviterSigningKey), not the
        // user's primary — the backend matches `InviterAddressID` against the share's
        // owner address and rejects mismatches.
        val (keyPacket, keyPacketSignature) = cryptoHelper.encryptAndSignSessionKeyForInvitee(
            sessionKey              = shareSessionKey,
            inviteePublicKeyArmored = inviteePublicKeyArmored,
            signerKeyBytes          = inviterSigningKey.unlockedKeyBytes,
        )

        // POST the invitation. Permissions=6 matches Drive's default for "viewer + editor"
        // (4 = viewer-only, but the official client sends 6 for album invitations).
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
            // Server's "outdated app" text usually corresponds to a specific Code (5003 etc.);
            // log the full exception so bug reports include the server response code.
            Log.e(TAG, "inviteToAlbum failed: shareId=$albumShareId msg=${e.message}", e)
            throw e
        }
    }

    suspend fun deleteShare(userId: UserId, shareId: String): Unit = withContext(Dispatchers.IO) {
        // The DELETE drive/shares/{shareId} (v1) endpoint occasionally no-ops on album
        // member-shares even though it returns 2xx — we re-emit through AlbumListEventBus
        // afterwards so the UI doesn't lie about removal status when that happens. The
        // error path keeps a stacktrace because share-revoke failures are rare and worth
        // surfacing in bug reports.
        val manager = apiProvider.get<DriveApiService>(userId)
        try {
            semaphore.withPermit {
                manager.invoke { deleteShare(shareId) }.valueOrThrow
            }
        } catch (e: Exception) {
            Log.e(TAG, "deleteShare failed: shareId=$shareId msg=${e.message}", e)
            throw e
        }
    }

    /**
     * Recipient-side "Leave album" — the user removes themselves from a shared-with-me
     * album so the entry disappears from their library. The owner's copy and any other
     * members stay intact.
     *
     * Mirrors the official `LeaveShare` use case (Drive Android
     * `me.proton.core.drive.share.user.domain.usecase.LeaveShare`):
     *   1. Resolve the user's own membership entry on the share — `GET drive/v2/shares/
     *      {shareId}/members` returns every accepted member; we match by primary
     *      address email.
     *   2. `DELETE drive/v2/shares/{shareId}/members/{memberId}` removes the membership.
     *   3. Clear local cache (CloudAlbumEntity + AlbumPhotoMembership for this album)
     *      so the grid updates immediately without waiting for a network refresh.
     */
    suspend fun leaveSharedAlbum(userId: UserId, shareId: String, albumLinkId: String): Unit = withContext(Dispatchers.IO) {
        val manager = apiProvider.get<DriveApiService>(userId)

        // 1. Resolve our own MemberID from the share bootstrap. `GET drive/shares/
        //    {shareId}` returns a `Memberships` array holding the CALLING user's
        //    membership rows — this is the official client's source for the leave
        //    memberId (`driveLink.shareUser.id`, populated from the bootstrap).
        //    The previous implementation walked `GET drive/v2/shares/{shareId}/
        //    members`, but that listing is admin-only: a plain member gets a 403
        //    ("no permission") before ever reaching the DELETE, which is exactly
        //    the error recipients saw when leaving.
        val ownAddressIds = runCatching {
            userAddressRepository.getAddresses(userId).map { it.addressId.id }.toSet()
        }.getOrDefault(emptySet())
        val bootstrapMember = runCatching {
            val bootstrap = semaphore.withPermit {
                manager.invoke { getShareBootstrap(shareId) }.valueOrThrow
            }
            // Bootstrap memberships are scoped to the caller, so a single row is
            // ours by definition; the addressId filter only matters if the server
            // ever returns multiple rows (multi-address invitations).
            bootstrap.memberships.firstOrNull { it.addressId in ownAddressIds }
                ?: bootstrap.memberships.firstOrNull()
        }.getOrNull()

        // 2. Fallback for the OWNER-side call path (owners can list members, and
        //    their own membership is not part of a member-share bootstrap).
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
            // No resolvable membership — the leave is a no-op on the server. Still
            // wipe local cache so the user sees the album disappear; otherwise an
            // orphan entry sticks around until the next refresh.
            Log.w(TAG, "leaveSharedAlbum: own membership not found on shareId=$shareId — local-cache wipe only")
            cloudAlbumDao.deleteByLinkId(albumLinkId)
            albumPhotoMembershipDao.deleteAllForAlbum(albumLinkId)
            return@withContext
        }

        // 3. DELETE the membership.
        try {
            semaphore.withPermit {
                manager.invoke {
                    removeShareMember(shareId, ownMemberId)
                }.valueOrThrow
            }
            Log.d(TAG, "leaveSharedAlbum: left shareId=$shareId memberId=$ownMemberId")
        } catch (e: Exception) {
            Log.e(TAG, "leaveSharedAlbum failed: shareId=$shareId memberId=$ownMemberId msg=${e.message}", e)
            throw e
        }

        // 4. Local cache cleanup so the grid updates immediately.
        cloudAlbumDao.deleteByLinkId(albumLinkId)
        albumPhotoMembershipDao.deleteAllForAlbum(albumLinkId)
    }

    /**
     * Lists albums that other users have shared with this user.
     *
     * Primary path: `drive/photos/albums/shared-with-me` — this is the canonical
     * endpoint per the official Proton Drive Android client (AlbumRepositoryImpl
     * + PhotoApi.getAlbumSharedWithMeListings). It is the ONLY endpoint that
     * reliably returns Photos-album member-shares; `drive/v2/sharedwithme`
     * covers files/folders/photos but excludes album-type shares.
     *
     * Backup path: `drive/v2/sharedwithme` filtered by `ShareTargetType == 3`,
     * in case Proton ever extends v2 to include album shares. Results are
     * merged and de-duplicated by linkId.
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
            // Don't fail the whole call if the Photos endpoint hiccups — let the v2 backup run.
            Log.w(TAG, "loadSharedWithMeAlbums: drive/photos/albums/shared-with-me failed: ${e.message}", e)
        }
        Log.d(TAG, "loadSharedWithMeAlbums: photos endpoint total stubs=${albumStubs.size}")

        // Group by shareId — each album must have a shareId to decrypt its metadata
        val byShare = albumStubs.groupBy { it.shareId }
        Log.d(TAG, "loadSharedWithMeAlbums: stubs grouped by shareId — groups=${byShare.size} nullShareIdGroup=${byShare[null]?.size ?: 0}")

        for ((shareId, stubs) in byShare) {
            if (shareId == null) {
                // No shareId — can't decrypt; use linkId as fallback name
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
                // Use the v1 bootstrap endpoint (`drive/shares/{id}`), NOT the v2 wrapped
                // one (`drive/v2/shares/{id}`). The v2 endpoint 404s for freshly created
                // album sub-shares (it only resolves for already-migrated main shares),
                // while v1 returns the share for both — which is why the same call works
                // on the owner side from `createAlbumShareLink` / `inviteToAlbum`.
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
                                // Albums encrypt the Name field to the PARENT (root link) public key, not the album's
                                // own. Backend should substitute this PKESK to a key the share holder can open; if it
                                // doesn't, we need to try the share key bytes directly (the substitution might have
                                // happened at the share level instead). Fall back to share key bytes before giving up.
                                name = cryptoHelper.decryptLinkName(encName, nodeKeyBytes)
                                    ?: cryptoHelper.decryptLinkName(encName, shareKeyBytes)
                                    ?: stub.linkId.take(8)
                            } catch (e: Exception) {
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
                Log.w(TAG, "loadSharedWithMeAlbums: failed for shareId=$shareId: ${e.message}", e)
            }
        }

        val resultsAfterPhotosEndpoint = result.size
        Log.d(TAG, "loadSharedWithMeAlbums: after photos endpoint decryption — albums=$resultsAfterPhotosEndpoint")

        // Backup: drive/v2/sharedwithme — covers any album shares the Photos endpoint
        // somehow missed (e.g. backend race after accept). Merged with primary results.
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
                // Log the targetType distribution so we can spot a server schema change (e.g. Album = 5 not 3).
                val typeHistogram = resp.links.groupingBy { it.shareTargetType }.eachCount()
                Log.d(TAG, "loadSharedWithMeAlbums: v2 page=$v2EndpointPages total=${resp.links.size} types=$typeHistogram more=${resp.more}")
                // ShareTargetType per the official Drive Android client enum:
                //   1 = File · 2 = Folder · 3 = Album · 4 = Photo
                // The cross-account share flow rewrites a Photos album so the recipient
                // sees it with shareTargetType = 2 (Folder) — the album-ness is hidden
                // at the share boundary. A type-3-only filter therefore loses every
                // album shared from outside this app (notably anything shared from
                // Drive web). We accept 2 and 3 here and let the downstream decrypt
                // attempt decide: if the link's NodePassphrase + Name unlock cleanly,
                // it's a shared album worth surfacing; if they don't, the entry is
                // dropped silently. Type 1 (File) and Type 4 (Photo) shares have no
                // album-shaped UI hook and stay out of the list.
                val acceptedTargetTypes = setOf(2, 3)
                sharedLinks.addAll(
                    resp.links.filter {
                        it.shareTargetType in acceptedTargetTypes && it.linkId !in seenLinkIds
                    },
                )
                swmAnchor = if (resp.more) resp.anchorId else null
            } while (swmAnchor != null)
        } catch (e: Exception) {
            Log.w(TAG, "loadSharedWithMeAlbums: drive/v2/sharedwithme backup failed: ${e.message}", e)
        }
        Log.d(TAG, "loadSharedWithMeAlbums: v2 backup raw=$v2RawTotal filteredAlbumOnly=${sharedLinks.size}")

        for (link in sharedLinks) {
            if (!seenLinkIds.add(link.linkId)) continue
            try {
                // v1 bootstrap — see note above; album sub-shares 404 on the v2 wrapped path.
                val shareDetails = semaphore.withPermit {
                    manager.invoke { getShareBootstrap(link.shareId) }.valueOrThrow
                }
                val shareKeyArmored = shareDetails.key ?: continue
                val sharePassphraseArmored = shareDetails.passphrase ?: continue
                val shareKeyBytes = try {
                    cryptoHelper.decryptExternalShareKey(userId, shareKeyArmored, sharePassphraseArmored)
                } catch (e: Exception) {
                    Log.w(TAG, "loadSharedWithMeAlbums: v2 share key decrypt failed: ${e.message}")
                    continue
                }
                val linkDetails = try {
                    semaphore.withPermit {
                        manager.invoke { getFullLinkDetails(link.shareId, link.linkId) }.valueOrThrow
                    }.link
                } catch (e: Exception) {
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
                            Log.w(TAG, "loadSharedWithMeAlbums: v2 name decrypt failed for ${link.linkId.take(12)}: ${e.message}")
                        }
                    }
                }
                // Drop entries whose name couldn't be decrypted. A blank name signals
                // either a regular Drive folder share the Photos app can't render OR
                // an upstream backend bug masking an actual album; in either case the
                // gallery surface is the wrong place to expose the unreadable row.
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
                Log.w(TAG, "loadSharedWithMeAlbums: v2 failed for ${link.linkId}: ${e.message}")
            }
        }

        Log.d(TAG, "loadSharedWithMeAlbums: DONE — photosStubs=${albumStubs.size} afterPhotosDecrypt=$resultsAfterPhotosEndpoint v2AlbumRaw=${sharedLinks.size} finalTotal=${result.size}")
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
     * Builds the album-shaped share material the create-share endpoint expects, mirroring
     * the canonical `CreateShareInfo` pipeline in Drive Android. Pulls the parent (root
     * link) key bytes via [PhotosShareService.getRootLinkKeyBytes] — the album link's
     * passphrase + name are both encrypted under the root link key in the Photos hierarchy.
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
     * Saves every photo from a shared-with-me album into a new album owned by the
     * caller. The flow mirrors Drive Android's `AddPhotosToFolder` use case:
     *
     *  1. Bootstrap the shared album's share key (so we can decrypt source photos'
     *     NodePassphrase, which the recipient opens via the share, not the volume).
     *  2. Decrypt the source album NodeKey + NodeHashKey via the share key bytes.
     *  3. Create a new owned album in the caller's own photos volume with the same
     *     display name as the shared album.
     *  4. Decrypt the new album NodeKey + NodeHashKey + extract its public key.
     *  5. For every photo in the shared album: re-encrypt the photo's NodePassphrase
     *     to the new album NodeKey, re-encrypt + re-sign the photo's Name with the
     *     caller's address key, recompute the name's HMAC under the new NodeHashKey,
     *     and POST `drive/volumes/{ownVolumeId}/links/{sourcePhotoLinkId}/copy`. The
     *     backend duplicates the encrypted blob server-side; no photo bytes ever
     *     transit the wire on the recipient side.
     *  6. The recipient's regular Photos refresh + backup-everything sync brings the
     *     local copy down on top, so the new owned album shows up with the green-
     *     cloud badge once the local cache lands.
     *
     * Returns a [SaveSharedAlbumResult] with the new album's linkId + per-photo
     * copy outcomes so the caller can render a summary toast / partial-failure UI.
     */
    /**
     * Singleton-scoped state for the in-flight Save-to-library job. Exposed as a
     * StateFlow so any VM that opens the same album can subscribe and see the
     * current copy progress, including across screen navigations. The underlying
     * coroutine lives in [saveToLibraryScope] which is rooted in the singleton —
     * it survives the album-detail VM's destruction so a user who backs out
     * mid-copy doesn't lose their progress (or worse, leave a half-populated
     * album behind on the server).
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
        /** User aborted the copy mid-flight. Carries the copied/total snapshot at the
         *  moment of cancellation so the UI snackbar can show "Save cancelled at N / M". */
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

    /** Handle on the in-flight save-to-library coroutine. Held in a field so
     *  [cancelSaveToLibrary] can abort the copy mid-walk without blowing up the
     *  whole [saveToLibraryScope]. */
    private var saveToLibraryJob: Job? = null

    /**
     * Fire-and-forget launcher backed by [saveToLibraryScope]. Subsequent calls
     * are ignored while a copy is already running; the caller observes
     * [saveToLibraryState] for progress + outcome instead of awaiting a return.
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
                // User-initiated cancellation. Capture the last running snapshot so
                // the UI can show "Save cancelled at N / M". The partial copies that
                // already landed on the server stay there — the new owned album was
                // created up-front and any photos copied before the cancel remain in
                // the caller's library; rolling that back here would race the user
                // re-tapping Save.
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

    /** Cancels an in-flight save-to-library job. Safe to call when no copy is
     *  running — the launched coroutine clears the field in its finally block. */
    fun cancelSaveToLibrary() {
        saveToLibraryJob?.cancel()
        saveToLibraryJob = null
    }

    /** Called by the VM after it consumes a terminal Done/Failed/Cancelled state so a
     *  subsequent save-to-library run starts from a clean Idle. */
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
        // Drive Photos albums are MEMBERSHIPS, not folder children — every photo
        // lives under the Photos stream root, and album entries reference them by
        // linkId. So the copy itself lands in the root (becomes a normal library
        // photo), and a follow-up addPhotosToAlbum call attaches the new linkIds
        // to the album. If we copied straight into the album folder instead, the
        // photos would NOT show up on the user's Photos timeline — only inside
        // the album — and Drive web would render them the same way (album-folder
        // children, not library members).
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
        // Initial progress emission so the UI can flip the indicator from a generic
        // spinner to a real "0 of N" copied state the moment the children list is in
        // hand (which is also when the user first feels the wait — pagination just
        // finished but no copies have run yet).
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

                // For the content hash we reuse the source's existing photoContentHash
                // — Drive Android's fallback path does the same when the contentDigest
                // isn't available, and the recipient never sees the plaintext digest.
                // The share endpoint nests the Photo block inside FileProperties >
                // ActiveRevision, not on the BatchLink wrapper (which is null for
                // shared-with-me link fetches), so we walk that path first and only
                // fall back to the wrapper if nothing landed in the nested spot.
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

                // Conditional signature payload — see Drive Android's
                // `Link.signatureEmail` / `Link.nodePassphraseSignature` extensions
                // in `drive/key/domain/.../extension/Link.kt`. The backend
                // accepts a `NodePassphraseSignature` + `SignatureEmail` ONLY
                // when the source link is "anonymous" (its own signatureEmail
                // is blank, which happens e.g. for shared-by-link uploads to a
                // public folder). Every other case — including normal owner-
                // signed shared-with-me photos — rejects the request with
                // code 2001 if we set those fields. `NameSignatureEmail` is
                // always our caller's address because we re-signed the Name
                // with our address key just above.
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
                Log.w(TAG, "saveSharedAlbumToOwnLibrary: copy failed linkId=$photoLinkId msg=${e.message}", e)
                failures.add(photoLinkId)
            }
            // Emit on every iteration (success or failure) so the UI counter walks
            // forward in real time instead of jumping from 0 → N at the end.
            onProgress(copied, totalToCopy)
        }

        // === Phase 6: attach the freshly-copied photos to the new album ==========
        // The copies above landed in the user's Photos library root; without this
        // step the album would stay empty even though the photos are now in the
        // library. addPhotosToAlbum walks the linkIds through the album's crypto
        // chain and POSTs the membership entries via /drive/volumes/.../albums.
        if (copiedLinkIds.isNotEmpty()) {
            try {
                albumService.addPhotosToAlbum(userId, newAlbumLinkId, copiedLinkIds)
            } catch (e: Exception) {
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
        // Mirrors `CreateShare.DEFAULT_SHARE_NAME` in the official Drive Android client.
        // The wire `Name` field is just a label for the share record; the album's actual
        // name reaches the server via [GeneratedAlbumShare.nameKeyPacketBase64].
        private const val SHARE_DISPLAY_NAME = "New Share"
    }
}
