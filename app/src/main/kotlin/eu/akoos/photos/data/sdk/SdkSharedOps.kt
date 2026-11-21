/*
 * Photos for Proton
 * Copyright (C) 2026 Akoos <https://akoos.eu>
 *
 * Source:  https://github.com/gitakoos/proton-photos
 * Website: https://www.photosforproton.eu
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

package eu.akoos.photos.data.sdk

import android.util.Log
import kotlinx.coroutines.flow.toList
import me.proton.core.domain.entity.UserId
import me.proton.drive.sdk.ProtonSdkError
import me.proton.drive.sdk.entity.AlbumNode
import me.proton.drive.sdk.entity.LegacyNodeUid
import me.proton.drive.sdk.entity.MemberRole
import me.proton.drive.sdk.entity.NodeResultPair
import me.proton.drive.sdk.entity.NodeUid
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/** One album shared with this user, as the SDK lists it; [name] is null when it would not decrypt. */
data class SdkSharedAlbum(
    val linkId: String,
    val volumeId: String,
    val shareId: String,
    val name: String?,
    val photoCount: Int,
    val coverLinkId: String?,
    val lastActivityTimeMs: Long?,
    val sharedByEmail: String?,
    val permissions: Long?,
)

/**
 * Albums other users shared with this user, through the Drive SDK: the list and leaving one. The list is
 * all or nothing: an album the SDK names without its share id cannot be opened by the rest of the app,
 * so it fails the whole call and the app's own listing answers instead. Run only through [SdkRouting]
 * ([SdkRoute.SHARED]). Also the feed of items this user shares ([SdkRoute.SHARED_BY_ME]), and saving photos
 * of a shared album into this user's library ([SdkRoute.SAVE_TO_LIBRARY]).
 */
@Singleton
class SdkSharedOps @Inject constructor(
    private val clientProvider: SdkDriveClientProvider,
) {
    suspend fun listSharedAlbums(userId: UserId): List<SdkSharedAlbum> =
        clientProvider.withPhotosClient(userId) { client ->
            val uids = client.enumerateSharedWithMeNodeUids().toList().distinct()
            if (uids.isEmpty()) return@withPhotosClient emptyList()
            client.enumerateNodes(uids).toList().filterIsInstance<AlbumNode>().map { it.toSharedAlbum() }
        } ?: throw SdkSharedException("the SDK cannot run on this device")

    /**
     * The link ids of every item this user shares from the photo volume [volumeId], in the server's order:
     * the same feed the app's own listing walks, page by page to the end. All or nothing: an item on any
     * other volume fails the call, so the app's own listing answers instead. Run only through [SdkRouting]
     * ([SdkRoute.SHARED_BY_ME]).
     */
    suspend fun sharedByMeLinkIds(userId: UserId, volumeId: String): List<String> =
        clientProvider.withPhotosClient(userId) { client ->
            val started = System.nanoTime()
            val linkIds = sharedByMeLinkIdsOf(client.enumerateSharedNodeUids().toList(), volumeId)
            runCatching {
                Log.d(TAG, "shared-by-me through the SDK: ${linkIds.size} links in ${(System.nanoTime() - started) / 1_000_000} ms")
            }
            linkIds
        } ?: throw SdkSharedException("the SDK cannot run on this device")

    /**
     * Copies photos of an album another user shared, on [sourceVolumeId], into this user's own timeline,
     * each with its Live Photo or burst companions, and hands each photo's answer to [onOutcome] as it
     * arrives. Run only through [saveSharedPhotosRouted] ([SdkRoute.SAVE_TO_LIBRARY]).
     */
    suspend fun saveToTimeline(
        userId: UserId,
        sourceVolumeId: String,
        photoLinkIds: List<String>,
        onOutcome: (linkId: String, outcome: SdkSaveOutcome) -> Unit,
    ) {
        clientProvider.withPhotosClient(userId) { client ->
            val started = System.nanoTime()
            val uids = photoLinkIds.map { LegacyNodeUid(volumeId = sourceVolumeId, linkId = it) }
            client.savePhotosToTimeline(uids).collect { result ->
                val linkId = (result.nodeUid as? LegacyNodeUid)?.linkId ?: return@collect
                val outcome = saveOutcomeOf(result)
                if (result is NodeResultPair.Failure) {
                    runCatching {
                        Log.w(TAG, "save to library through the SDK: $linkId $outcome (${result.error.error?.type})")
                    }
                }
                onOutcome(linkId, outcome)
            }
            runCatching {
                Log.d(TAG, "save to library through the SDK: ${uids.size} photos in ${(System.nanoTime() - started) / 1_000_000} ms")
            }
        } ?: throw SdkSharedException("the SDK cannot run on this device")
    }

    /** Leaves the album on [volumeId]: this user's membership ends, the album stays with its owner. */
    suspend fun leave(userId: UserId, volumeId: String, albumLinkId: String) {
        clientProvider.withPhotosClient(userId) { client ->
            client.leaveSharedNode(LegacyNodeUid(volumeId = volumeId, linkId = albumLinkId))
        } ?: throw SdkSharedException("the SDK cannot run on this device")
    }
}

/** Thrown when the SDK cannot do the shared-album operation, so the app's own path does it instead. */
class SdkSharedException(message: String) : IllegalStateException(message)

private const val TAG = "SdkSharedOps"

/** What the SDK answered for one photo it was asked to save into this library. */
enum class SdkSaveOutcome {
    /** Copied into this library. */
    COPIED,

    /** Already in this library's timeline, so nothing was copied. */
    ALREADY_THERE,

    /** Not copied, for any reason; the app's own copy takes the photo. */
    NOT_DONE,
}

/** The SDK's answer for one photo, as [SdkSaveOutcome]. */
internal fun saveOutcomeOf(result: NodeResultPair): SdkSaveOutcome = when {
    result !is NodeResultPair.Failure -> SdkSaveOutcome.COPIED
    result.error.error?.type == "PhotoAlreadyInTargetException" -> SdkSaveOutcome.ALREADY_THERE
    else -> SdkSaveOutcome.NOT_DONE
}

/** How many photos a save into this library copied, failed and found already there. */
internal data class SaveToLibraryCounts(val copied: Int, val failed: Int, val alreadyThere: Int)

/**
 * Saves the photos [linkIds] of a shared album into this library. With [sdkOn] off every photo goes through
 * [copyOwnPath] (true when it copied), one at a time and in order, exactly as the app's own path does. With
 * it on the SDK goes first ([viaSdk]); a photo it copied or found already there is not sent again, and every
 * other photo, also each one it never answered because the call failed partway, goes through [copyOwnPath].
 * That second try cannot copy a photo twice: a copy the SDK did make holds the same name in the same
 * folder, which the server refuses.
 */
internal suspend fun saveSharedPhotosRouted(
    linkIds: List<String>,
    sdkOn: Boolean,
    viaSdk: suspend (linkIds: List<String>, onOutcome: (String, SdkSaveOutcome) -> Unit) -> Unit,
    copyOwnPath: suspend (linkId: String) -> Boolean,
): SaveToLibraryCounts {
    val answered = ConcurrentHashMap<String, SdkSaveOutcome>()
    if (sdkOn && linkIds.isNotEmpty()) {
        runRouted(
            SdkRoute.SAVE_TO_LIBRARY.label,
            on = true,
            viaSdk = { viaSdk(linkIds) { linkId, outcome -> answered[linkId] = outcome } },
            fallback = {},
        )
    }
    var copied = 0
    var failed = 0
    var alreadyThere = 0
    for (linkId in linkIds) {
        when (answered[linkId]) {
            SdkSaveOutcome.COPIED -> copied++
            SdkSaveOutcome.ALREADY_THERE -> alreadyThere++
            else -> if (copyOwnPath(linkId)) copied++ else failed++
        }
    }
    return SaveToLibraryCounts(copied, failed, alreadyThere)
}

/** The link ids of [uids], which must all be legacy uids on [volumeId]; any other uid throws. */
internal fun sharedByMeLinkIdsOf(uids: List<NodeUid>, volumeId: String): List<String> = uids.map { uid ->
    val legacy = uid as? LegacyNodeUid ?: throw SdkSharedException("a shared item came back without a link id")
    if (legacy.volumeId != volumeId) throw SdkSharedException("a shared item came back on another volume")
    legacy.linkId
}

internal fun AlbumNode.toSharedAlbum(): SdkSharedAlbum {
    val uid = uid as? LegacyNodeUid ?: throw SdkSharedException("an album came back without a link id")
    @Suppress("DEPRECATION")
    val shareId = deprecatedShareId?.takeIf { it.isNotBlank() }
        ?: throw SdkSharedException("an album came back without its share id")
    return SdkSharedAlbum(
        linkId = uid.linkId,
        volumeId = uid.volumeId,
        shareId = shareId,
        name = name.getOrNull()?.takeIf { it.isNotBlank() },
        photoCount = photoCount.toInt(),
        coverLinkId = (coverPhotoNodeUid as? LegacyNodeUid)?.linkId,
        lastActivityTimeMs = lastActivityTime?.toEpochMilli(),
        sharedByEmail = ownedBy.email ?: membership?.sharedBy?.getOrNull()?.emailAddress,
        permissions = membership?.role?.let(::permissionsOf),
    )
}

/** Drive's member permission bits for [role]: read 4, write 2, admin 16. */
internal fun permissionsOf(role: MemberRole): Long? = when (role) {
    MemberRole.VIEWER -> 4L
    MemberRole.EDITOR -> 6L
    MemberRole.ADMIN -> 22L
    MemberRole.INHERITED -> null
}
