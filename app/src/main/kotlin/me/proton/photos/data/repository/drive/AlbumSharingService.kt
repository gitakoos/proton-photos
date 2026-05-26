package me.proton.photos.data.repository.drive

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import me.proton.core.domain.entity.UserId
import me.proton.core.key.domain.repository.PublicAddressRepository
import me.proton.core.network.data.ApiProvider
import me.proton.photos.data.api.DriveApiService
import me.proton.photos.data.api.dto.AcceptInvitationRequest
import me.proton.photos.data.api.dto.AlbumDto
import me.proton.photos.data.api.dto.BatchLinksRequest
import me.proton.photos.data.api.dto.CreateInvitationRequest
import me.proton.photos.data.api.dto.CreateShareRequest
import me.proton.photos.data.api.dto.CreateShareUrlRequest
import me.proton.photos.data.api.dto.GlobalInvitationDto
import me.proton.photos.data.api.dto.InvitationBodyDto
import me.proton.photos.data.api.dto.LinkCoreDto
import me.proton.photos.data.api.dto.SharedWithMeLinkDto
import me.proton.photos.data.crypto.DriveCryptoHelper
import me.proton.photos.domain.entity.Album
import me.proton.photos.domain.entity.PendingInvitation
import me.proton.photos.domain.entity.ShareInvitation
import me.proton.photos.domain.entity.ShareMember
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
) {
    private val semaphore get() = shareService.networkSemaphore

    /**
     * Creates (or reuses) the album's Drive share and then mints a public share-URL on
     * top of it. Returns the user-shareable URL.
     */
    suspend fun createAlbumShareLink(userId: UserId, albumLinkId: String): String = withContext(Dispatchers.IO) {
        val volumeId = shareService.getVolumeId(userId)
        val manager = apiProvider.get<DriveApiService>(userId)
        val signingKey = cryptoHelper.getAddressSigningKey(userId)

        // Step 1 — find or create the album's share. We use the same path as inviteToAlbum so
        // the public-link flow and the invite-by-email flow share the same share object.
        val existingShareId: String? = runCatching {
            linkDetailHelpers.batchFetchLinkDetails(userId, volumeId, listOf(albumLinkId))[albumLinkId]?.sharing?.shareId
        }.getOrNull()
        val albumShareId = existingShareId ?: run {
            val plaintextAlbumName = lookupAlbumName(userId, albumLinkId)
            val generated = cryptoHelper.generateNewShareForLink(
                addressPublicKeyArmored = signingKey.publicKeyArmored,
                signerKeyBytes = signingKey.unlockedKeyBytes,
                plaintextName = plaintextAlbumName,
            )
            val resp = semaphore.withPermit {
                manager.invoke {
                    createVolumeShare(
                        volumeId,
                        CreateShareRequest(
                            type = 4,
                            addressId = signingKey.addressId,
                            name = generated.nameEncryptedArmored,
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
            resp.share.shareId
        }

        // Step 2 — create the public URL backed by that share.
        val urlResp = semaphore.withPermit {
            manager.invoke {
                createShareUrl(albumShareId, CreateShareUrlRequest(creatorEmail = signingKey.email))
            }.valueOrThrow
        }
        urlResp.shareUrl?.publicUrl
            ?: "https://drive.proton.me/urls/${urlResp.shareUrl?.token}"
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

        val existingShareId: String? = runCatching {
            linkDetailHelpers.batchFetchLinkDetails(userId, volumeId, listOf(albumLinkId))[albumLinkId]?.sharing?.shareId
        }.getOrNull()

        val (albumShareId, shareSessionKeyBytes) = if (existingShareId != null) {
            val sd = semaphore.withPermit {
                manager.invoke { getShareById(existingShareId) }.valueOrThrow
            }.share
            val k = sd.key ?: error("Existing share $existingShareId has no Key")
            val p = sd.passphrase ?: error("Existing share $existingShareId has no Passphrase")
            existingShareId to cryptoHelper.decryptExternalShareKey(userId, k, p)
        } else {
            // The share passphrase and name must be encrypted to OUR primary address public
            // key so we can unlock the share later. AddressSigningKey already carries the
            // armored public key for the same address whose private key is used for signing.
            val plaintextAlbumName = lookupAlbumName(userId, albumLinkId)
            val generated = cryptoHelper.generateNewShareForLink(
                addressPublicKeyArmored = signingKey.publicKeyArmored,
                signerKeyBytes = signingKey.unlockedKeyBytes,
                plaintextName = plaintextAlbumName,
            )
            val createResp = semaphore.withPermit {
                manager.invoke {
                    createVolumeShare(
                        volumeId,
                        CreateShareRequest(
                            type = 4,
                            addressId = signingKey.addressId,
                            name = generated.nameEncryptedArmored,
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
            Log.d(TAG, "inviteToAlbum: created share ${createResp.share.shareId} for album $albumLinkId")
            createResp.share.shareId to generated.rawPassphraseBytes
        }

        // Resolve the invitee's primary public address key via the v2 endpoint.
        // PublicAddressInfo groups keys into address / catchAll / unverified buckets; the
        // address bucket is the direct match for the email and what we need for invites.
        val publicAddressInfo = runCatching {
            publicAddressRepository.getPublicAddressInfo(userId, email)
        }.getOrElse { e -> error("Cannot resolve Proton account for $email: ${e.message}") }
        val inviteePublicKeyArmored = publicAddressInfo.address.keys
            .firstOrNull()?.publicKey?.key
            ?: error("Invitee $email has no public key")

        // PKESK-encrypt the share session key to the invitee + detached-sign it with us.
        val (keyPacket, keyPacketSignature) = cryptoHelper.encryptAndSignSessionKeyForInvitee(
            sessionKeyBytes        = shareSessionKeyBytes,
            inviteePublicKeyArmored = inviteePublicKeyArmored,
            signerKeyBytes         = signingKey.unlockedKeyBytes,
        )

        // POST the invitation. Permissions=6 matches Drive's default for "viewer + editor"
        // (4 = viewer-only, but the official client sends 6 for album invitations).
        semaphore.withPermit {
            manager.invoke {
                inviteToShare(
                    albumShareId,
                    CreateInvitationRequest(
                        invitation = InvitationBodyDto(
                            inviterEmail       = signingKey.email,
                            inviteeEmail       = email,
                            permissions        = 6,
                            keyPacket          = keyPacket,
                            keyPacketSignature = keyPacketSignature,
                        ),
                    ),
                )
            }.valueOrThrow
        }
        Log.d(TAG, "inviteToAlbum: invited $email to album $albumLinkId via share $albumShareId")
    }

    suspend fun deleteShare(userId: UserId, shareId: String): Unit = withContext(Dispatchers.IO) {
        val manager = apiProvider.get<DriveApiService>(userId)
        semaphore.withPermit {
            manager.invoke { deleteShare(shareId) }.valueOrThrow
        }
        Log.d(TAG, "deleteShare: deleted share $shareId")
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
                    ))
                }
                continue
            }

            try {
                val shareDetails = semaphore.withPermit {
                    manager.invoke { getShareById(shareId) }.valueOrThrow
                }.share
                val shareKeyArmored = shareDetails.key
                val sharePassphraseArmored = shareDetails.passphrase
                if (shareKeyArmored == null || sharePassphraseArmored == null) {
                    Log.w(TAG, "loadSharedWithMeAlbums: share $shareId returned without Key/Passphrase — dropping ${stubs.size} stub(s). creator=${shareDetails.creatorEmail}")
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
                                name = cryptoHelper.decryptLinkName(encName, nodeKeyBytes) ?: stub.linkId.take(8)
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
                        coverThumbnailUrl = null,
                        sharingShareId = shareId,
                        sharedByEmail = shareDetails.creatorEmail,
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
                // ShareTargetType 3 = Album per the official Drive Android client enum.
                sharedLinks.addAll(resp.links.filter { it.shareTargetType == 3 && it.linkId !in seenLinkIds })
                swmAnchor = if (resp.more) resp.anchorId else null
            } while (swmAnchor != null)
        } catch (e: Exception) {
            Log.w(TAG, "loadSharedWithMeAlbums: drive/v2/sharedwithme backup failed: ${e.message}", e)
        }
        Log.d(TAG, "loadSharedWithMeAlbums: v2 backup raw=$v2RawTotal filteredAlbumOnly=${sharedLinks.size}")

        for (link in sharedLinks) {
            if (!seenLinkIds.add(link.linkId)) continue
            try {
                val shareDetails = semaphore.withPermit {
                    manager.invoke { getShareById(link.shareId) }.valueOrThrow
                }.share
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
                var name = link.linkId.take(8)
                if (linkDetails != null) {
                    val nodeKey = linkDetails.nodeKey
                    val nodePassphrase = linkDetails.nodePassphrase
                    val encName = linkDetails.name
                    if (nodeKey != null && nodePassphrase != null && encName != null) {
                        try {
                            val nodeKeyBytes = cryptoHelper.decryptNodeKey(nodeKey, nodePassphrase, shareKeyBytes)
                            name = cryptoHelper.decryptLinkName(encName, nodeKeyBytes) ?: link.linkId.take(8)
                        } catch (e: Exception) {
                            Log.w(TAG, "loadSharedWithMeAlbums: v2 name decrypt failed: ${e.message}")
                        }
                    }
                }
                result.add(Album(
                    linkId = link.linkId,
                    name = name,
                    photoCount = 0,
                    coverLinkId = null,
                    lastActivityTimeMs = null,
                    sharingShareId = link.shareId,
                    sharedByEmail = shareDetails.creatorEmail,
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
        val sessionKeySignature = cryptoHelper.signInvitationKeyPacket(userId, keyPacket)
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
     * Reads the plaintext album name from [AlbumService.loadAlbums]. Falls back to
     * `"Shared album"` if the lookup fails. Used only when creating a brand-new share
     * that needs an encrypted display name.
     */
    private suspend fun lookupAlbumName(userId: UserId, albumLinkId: String): String =
        runCatching {
            albumService.loadAlbums(userId).firstOrNull { it.linkId == albumLinkId }?.name
        }.getOrNull() ?: "Shared album"
}
