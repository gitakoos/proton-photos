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
import eu.akoos.photos.data.api.dto.AlbumDto
import eu.akoos.photos.data.api.dto.BatchLinkDto
import eu.akoos.photos.data.api.dto.PhotoLinkDto
import eu.akoos.photos.domain.entity.AlbumChild
import kotlinx.coroutines.flow.toList
import me.proton.core.domain.entity.UserId
import me.proton.drive.sdk.entity.AlbumNode
import me.proton.drive.sdk.entity.FileThumbnail
import me.proton.drive.sdk.entity.LegacyNodeUid
import me.proton.drive.sdk.entity.Node
import me.proton.drive.sdk.entity.NodeUid
import me.proton.drive.sdk.entity.ThumbnailType
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/** A photo as an SDK listing names it: its link id and its capture time in epoch seconds. */
data class SdkListedPhoto(val linkId: String, val captureTime: Long)

/** One of this user's own albums, carrying exactly the fields of the album list the app reads. */
data class ListedAlbum(
    val linkId: String,
    val photoCount: Int,
    val coverLinkId: String?,
    /** Epoch seconds, as the album list sends it. */
    val lastActivityTime: Long?,
)

/** What the app's timeline listing carries for a photo beyond its id and capture time, read from its link. */
data class ListedPhotoFacts(
    val hash: String?,
    val contentHash: String?,
    val tags: List<Int>,
    val captureTime: Long?,
)

/** Thrown when the SDK cannot give a whole, matching list or thumbnail, so the app's own read answers instead. */
class SdkListingException(message: String) : IllegalStateException(message)

/**
 * Library reads through the Drive SDK's Photos client: the timeline, this user's albums, an album's photos
 * and a photo's thumbnail. Every list is all or nothing: an item the SDK names on another volume, or without
 * a link id, fails the whole call, so the app's own listing answers instead. Run only through [SdkRouting]
 * ([SdkRoute.TIMELINE], [SdkRoute.ALBUM_LIST], [SdkRoute.ALBUM_PHOTOS], [SdkRoute.THUMBNAILS]).
 */
@Singleton
class SdkListingOps @Inject constructor(
    private val clientProvider: SdkDriveClientProvider,
) {
    /** Every photo of the timeline on [volumeId], in the server's order, with its capture time. */
    suspend fun timeline(userId: UserId, volumeId: String): List<SdkListedPhoto> = timed("timeline") {
        photos(userId) { client ->
            listedPhotosOf(client.enumerateTimeline().toList().map { it.nodeUid to it.captureTime }, volumeId)
        }
    }

    /**
     * This user's own albums on [volumeId], in the server's order. The ids come from the album list, the
     * count, cover and last activity from the albums' nodes.
     */
    suspend fun albums(userId: UserId, volumeId: String): List<ListedAlbum> = timed("albums") {
        photos(userId) { client ->
            val uids = client.enumerateAlbumNodeUids().toList()
            val nodes = if (uids.isEmpty()) emptyList() else client.enumerateNodes(uids.distinct()).toList()
            listedAlbumsOf(uids, nodes, volumeId)
        }
    }

    /** The photos of the album [albumLinkId] on [volumeId], in the server's order, with their capture times. */
    suspend fun albumPhotos(userId: UserId, volumeId: String, albumLinkId: String): List<SdkListedPhoto> =
        timed("album photos") {
            photos(userId) { client ->
                val items = client.enumerateAlbum(LegacyNodeUid(volumeId = volumeId, linkId = albumLinkId)).toList()
                listedPhotosOf(items.map { it.nodeUid to it.captureTime }, volumeId)
            }
        }

    /** The decrypted [type] thumbnail of the photo [linkId] on [volumeId]. */
    suspend fun thumbnail(userId: UserId, volumeId: String, linkId: String, type: ThumbnailType): ByteArray =
        timed("thumbnail") {
            photos(userId) { client ->
                val uid = LegacyNodeUid(volumeId = volumeId, linkId = linkId)
                thumbnailBytesOf(client.enumerateThumbnails(listOf(uid), type).toList(), linkId)
            }
        }

    private suspend fun <T> photos(userId: UserId, block: suspend (me.proton.drive.sdk.ProtonPhotosClient) -> T): T =
        clientProvider.withPhotosClient(userId, block) ?: throw SdkListingException("the SDK cannot run on this device")

    private suspend fun <T> timed(what: String, block: suspend () -> T): T {
        val started = System.nanoTime()
        val result = block()
        runCatching {
            val size = (result as? Collection<*>)?.size?.let { " ($it)" }.orEmpty()
            Log.d(TAG, "$what through the SDK$size in ${(System.nanoTime() - started) / 1_000_000} ms")
        }
        return result
    }
}

private const val TAG = "SdkListingOps"

/** The link id of [uid], which must be a legacy uid on [volumeId]; any other uid throws. */
internal fun linkIdOn(uid: NodeUid?, volumeId: String): String {
    val legacy = uid as? LegacyNodeUid ?: throw SdkListingException("an item came back without a link id")
    if (legacy.volumeId != volumeId) throw SdkListingException("an item came back on another volume")
    return legacy.linkId
}

/** [items] as listed photos, in their order; every uid must be on [volumeId]. */
internal fun listedPhotosOf(items: List<Pair<NodeUid, Instant>>, volumeId: String): List<SdkListedPhoto> =
    items.map { (uid, captureTime) -> SdkListedPhoto(linkIdOn(uid, volumeId), captureTime.epochSecond) }

/**
 * The albums [uids] names, in its order, each from its node in [nodes]. A uid without an album node fails
 * the whole list, as does a cover the SDK names without a link id.
 */
internal fun listedAlbumsOf(uids: List<NodeUid>, nodes: List<Node>, volumeId: String): List<ListedAlbum> {
    val byUid = nodes.associateBy { it.uid.value }
    return uids.map { uid ->
        val linkId = linkIdOn(uid, volumeId)
        val album = byUid[uid.value] as? AlbumNode ?: throw SdkListingException("an album came back without its node")
        ListedAlbum(
            linkId = linkId,
            photoCount = album.photoCount.toInt(),
            coverLinkId = album.coverPhotoNodeUid?.let { cover ->
                (cover as? LegacyNodeUid)?.linkId ?: throw SdkListingException("an album cover came back without a link id")
            },
            lastActivityTime = album.lastActivityTime?.epochSecond,
        )
    }
}

/**
 * The timeline stubs the app's own listing would have produced for [listed]: the id, order and capture time
 * come from the SDK, the name hash, content hash and tags from each photo's link in [facts]. A photo without
 * its link, or whose link names another capture time, fails the whole list. RelatedPhotos stays empty: the
 * app never reads it.
 */
internal fun timelineStubsOf(listed: List<SdkListedPhoto>, facts: Map<String, ListedPhotoFacts>): List<PhotoLinkDto> =
    listed.map { photo ->
        val fact = facts[photo.linkId] ?: throw SdkListingException("a timeline photo came back without its link")
        if (fact.captureTime != null && fact.captureTime != photo.captureTime) {
            throw SdkListingException("a timeline photo came back with another capture time")
        }
        PhotoLinkDto(
            linkId = photo.linkId,
            captureTime = photo.captureTime,
            hash = fact.hash,
            contentHash = fact.contentHash,
            tags = fact.tags,
        )
    }

/** The facts of the timeline listing that a photo's link carries too. */
internal fun BatchLinkDto.toListedPhotoFacts(): ListedPhotoFacts = ListedPhotoFacts(
    hash = link.hash,
    contentHash = photo?.contentHash,
    tags = photo?.tags.orEmpty(),
    captureTime = photo?.captureTime,
)

/** An entry of the app's own album list, cut to the fields it reads. */
internal fun AlbumDto.toListedAlbum(): ListedAlbum = ListedAlbum(
    linkId = linkId,
    photoCount = photoCount,
    coverLinkId = coverLinkId,
    lastActivityTime = lastActivityTime,
)

/**
 * A listed album photo as the app's album child. The SDK gives no added time, which nothing in the app
 * reads, and no IsChildOfAlbum flag: the one place that reads it rebuilds the row from the photo's link,
 * whose parent gives the same answer ([eu.akoos.photos.data.repository.drive.isDirectAlbumChild]).
 */
internal fun SdkListedPhoto.toAlbumChild(): AlbumChild = AlbumChild(linkId = linkId, captureTime = captureTime, addedTime = null)

/** The bytes of [linkId]'s thumbnail among [thumbnails]; a missing, failed or empty one throws. */
internal fun thumbnailBytesOf(thumbnails: List<FileThumbnail>, linkId: String): ByteArray {
    val match = thumbnails.firstOrNull { (it.uid as? LegacyNodeUid)?.linkId == linkId }
        ?: throw SdkListingException("the thumbnail did not come back")
    return match.result.getOrNull()?.takeIf { it.isNotEmpty() }
        ?: throw SdkListingException("the thumbnail came back empty")
}
