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

package eu.akoos.photos.presentation.albums

import eu.akoos.photos.domain.entity.CloudPhoto
import eu.akoos.photos.domain.entity.GalleryItem
import eu.akoos.photos.domain.entity.LocalMediaItem
import eu.akoos.photos.presentation.common.PhotoSortOrder

/**
 * The direction an album — or a device folder, which offers the same choice — lists its photos in
 * (#85).
 *
 * Both values sort on the same effective capture time, so the month headers, the scrubber and the
 * viewer-return index maths read one order and need no direction of their own.
 *
 * Persisted as an ordinal in [eu.akoos.photos.data.preferences.SettingsKeys.ALBUM_PHOTO_SORT_MODE]
 * and, for a folder, [eu.akoos.photos.data.preferences.SettingsKeys.DEVICE_FOLDER_PHOTO_SORT_MODE],
 * the same way the Albums-grid sort is stored, so these values must keep their positions.
 */
enum class AlbumPhotoSortMode {
    /** Most recently taken photo first. */
    NewestFirst,

    /** Oldest photo first. */
    OldestFirst;

    companion object {
        /** What an album has always opened on, so an install that never picks a direction sees no
         *  change. */
        val Default = NewestFirst

        /** Read back a persisted ordinal, tolerating an absent or out-of-range value. */
        fun fromOrdinal(ordinal: Int?): AlbumPhotoSortMode =
            ordinal?.let { entries.getOrNull(it) } ?: Default
    }
}

/**
 * Turns an album member into the same [GalleryItem] every other surface builds for that photo, so the
 * album's month headers, order, scrubber, scroll pill and viewer read one capture time and one set of
 * file facts rather than the raw Drive values.
 *
 * An album row is a bare [CloudPhoto]. [twin] is its device file as the merged library paired it,
 * carrying the name, size, folder and dimensions only that file knows; a null one means the library
 * paired nothing. [localUri] is the weaker signal the sync table alone gives: a device copy exists,
 * and nothing else about it.
 */
object AlbumPhotoItems {

    /**
     * Effective capture time for [photo], the album's grouping and sort key.
     *
     * With a [twin] this is [GalleryItem.Synced]: the Drive capture time, or the file's own
     * DATE_TAKEN where the Drive one falls below the shared sanity floor. Without one it is
     * [GalleryItem.CloudOnly], which keeps the raw value on purpose: no better timestamp is in hand,
     * and a bare cloud photo has to land in the album exactly where the timeline puts it.
     */
    fun captureTimeMs(photo: CloudPhoto, twin: LocalMediaItem?): Long =
        if (twin == null) GalleryItem.CloudOnly(photo).captureTimeMs
        else GalleryItem.Synced(photo, twin).captureTimeMs

    /**
     * [photo] as the viewer, the details sheet, the delete sheet and the categoriser want it:
     * [GalleryItem.Synced] over the real device file where one is paired, [GalleryItem.Synced] over
     * [standInTwin] where only [localUri] is known, else [GalleryItem.CloudOnly].
     */
    fun galleryItem(photo: CloudPhoto, twin: LocalMediaItem?, localUri: String?): GalleryItem = when {
        twin != null -> GalleryItem.Synced(photo, twin)
        localUri != null -> GalleryItem.Synced(photo, standInTwin(photo, localUri))
        else -> GalleryItem.CloudOnly(photo)
    }

    /**
     * [photos] in the album's canonical order: [PhotoSortOrder] over the effective captureTime, with
     * the linkId as the stable id (the descending direction matches AlbumService.photoOrder).
     *
     * A member whose Drive captureTime is sub-floor sorts on its twin's date and lands where the
     * timeline already puts it, instead of sinking to the tail. The key map is built once here so a
     * member's twin costs one hash lookup rather than a fresh resolve.
     */
    fun ordered(
        photos: List<CloudPhoto>,
        twins: Map<String, LocalMediaItem>,
        mode: AlbumPhotoSortMode = AlbumPhotoSortMode.Default,
    ): List<CloudPhoto> {
        if (photos.size < 2) return photos
        val keys = HashMap<String, Long>(photos.size)
        for (photo in photos) keys[photo.linkId] = captureTimeMs(photo, twins[photo.linkId])
        return PhotoSortOrder.ordered(
            items = photos,
            newestFirst = mode == AlbumPhotoSortMode.NewestFirst,
            timeOf = { keys[it.linkId] ?: it.captureTimeMs },
            idOf = { it.linkId },
        )
    }

    /**
     * The device files [library] paired to [photos], keyed by cloud linkId. Bounded by the album, so
     * a large library never grows the result past the album's own size, and one pass over [library]
     * answers every member.
     */
    fun twinsFor(photos: List<CloudPhoto>, library: List<GalleryItem>): Map<String, LocalMediaItem> {
        if (photos.isEmpty()) return emptyMap()
        val members = photos.mapTo(HashSet(photos.size)) { it.linkId }
        val twins = HashMap<String, LocalMediaItem>()
        for (item in library) {
            if (item is GalleryItem.Synced && item.cloud.linkId in members) {
                twins[item.cloud.linkId] = item.local
            }
        }
        return twins
    }

    /**
     * Stand-in for a member the sync table puts on this device while the merged library has not
     * paired its MediaStore row, so the file's own facts are genuinely unavailable. Each field states
     * what is known: the uri, plus the name and type of the photo the file is a copy of. Size, folder
     * and dimensions stay at their absent values, so the details sheet dashes them and the categoriser
     * skips the heuristics that need them instead of reading a blank as an answer. dateTaken is 0
     * because no device date was read: a real Drive capture time still wins, and a sub-floor one has
     * nothing here to borrow.
     */
    private fun standInTwin(photo: CloudPhoto, localUri: String) = LocalMediaItem(
        uri = localUri,
        dateTaken = 0L,
        displayName = photo.displayName,
        mimeType = photo.mimeType,
        sizeBytes = 0L,
        bucketName = null,
    )
}
