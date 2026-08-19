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

package eu.akoos.photos.presentation.common

import eu.akoos.photos.domain.entity.GalleryItem
import eu.akoos.photos.domain.usecase.WriteLocalPhotoMetadataUseCase

/**
 * Single source of truth for multi-select action visibility over a [GalleryItem] selection.
 *
 * Every surface's selection drawer gates its rows on the photo states in the current selection (any
 * device copy present, any cloud-only present, all on-device, etc.). These helpers centralise that
 * type-gating so every surface answers it the same way; read them as the canonical predicates rather
 * than re-deriving the `is LocalOnly` / `is CloudOnly` checks inline.
 *
 * The album detail screen keys its selection on `CloudPhoto` + `linkId`, not [GalleryItem], so it
 * does not consume these helpers; its on-device check lives next to its own selection state.
 */

/** The selection holds at least one device-only photo (no cloud copy yet). Gates Back up / Hide. */
fun anyLocalOnly(items: Collection<GalleryItem>): Boolean =
    items.any { it is GalleryItem.LocalOnly }

/** The selection holds at least one photo with a file on the device (device-only or synced). */
fun anyOnDevice(items: Collection<GalleryItem>): Boolean =
    items.any { it is GalleryItem.LocalOnly || it is GalleryItem.Synced }

/** Every selected photo lives on the device (device-only or synced) and the selection is non-empty. */
fun allDeviceOnly(items: Collection<GalleryItem>): Boolean =
    items.isNotEmpty() && items.all { it is GalleryItem.LocalOnly || it is GalleryItem.Synced }

/** Every selected photo is device-only (no cloud copy) and the selection is non-empty. Gates the
 *  metadata strip, which only applies to a wholly un-uploaded selection. */
fun allLocalOnly(items: Collection<GalleryItem>): Boolean =
    items.isNotEmpty() && items.all { it is GalleryItem.LocalOnly }

/** The selection holds at least one cloud-only photo. */
fun anyCloudOnly(items: Collection<GalleryItem>): Boolean =
    items.any { it is GalleryItem.CloudOnly }

/** The selection holds at least one photo whose metadata the editor can write: a device photo, a
 *  cloud image whose container takes an EXIF rewrite (replaced by a corrected copy), or a synced image
 *  (its device file is edited in place and its cloud copy replaced). A cloud or synced VIDEO stays out,
 *  so the Edit-metadata entry shows only when the editor has something it can change. Gates the
 *  multi-select Edit metadata action. */
fun anyMetadataEditable(items: Collection<GalleryItem>): Boolean =
    items.any {
        it is GalleryItem.LocalOnly ||
            (it is GalleryItem.CloudOnly &&
                WriteLocalPhotoMetadataUseCase.isExifWritableImageMime(it.cloud.mimeType)) ||
            (it is GalleryItem.Synced &&
                WriteLocalPhotoMetadataUseCase.isExifWritableImageMime(it.local.mimeType))
    }

/** Something in the selection can be pulled down from Drive. Mirrors [anyCloudOnly] — a cloud-only
 *  photo has no local file yet, so it is the downloadable case. Gates the Download row. */
fun hasDownloadable(items: Collection<GalleryItem>): Boolean = anyCloudOnly(items)

/**
 * Which way a selection's offline action goes: true pins the photos in [pinnableLinkIds] that are
 * not pinned yet, false drops the pins off all of them.
 *
 * One photo that is not pinned makes the whole press a pin, so a selection that happens to hold an
 * already-pinned photo finishes the job rather than un-pinning that single one. That is the rule
 * every surface's toggle runs, and reading it here is what keeps the row's label on the direction
 * the tap actually takes.
 *
 * [pinnableLinkIds] names the cloud photos with no device file: a photo whose bytes are already on
 * the device has nothing to pin and takes no part in the answer.
 */
fun offlineTurnsOn(pinnableLinkIds: Collection<String>, offlinePinIds: Set<String>): Boolean =
    pinnableLinkIds.any { it !in offlinePinIds }

/** Drive linkIds of the cloud-only photos in [items], the selection's pinnable half, for
 *  [offlineTurnsOn]. A device-only or synced photo already has its bytes here, so it contributes
 *  none. */
fun offlinePinnableLinkIds(items: Collection<GalleryItem>): List<String> =
    items.mapNotNull { (it as? GalleryItem.CloudOnly)?.cloud?.linkId }

/** The selection holds at least one synced photo (present both on device and on Drive). */
fun anySynced(items: Collection<GalleryItem>): Boolean =
    items.any { it is GalleryItem.Synced }

/** Photo / video split of the selection, used for the "X photos, Y videos" count label. A video is
 *  any item whose effective mime type starts with `video/`; everything else counts as a photo. */
data class SelectionMimeCounts(val photos: Int, val videos: Int)

private fun GalleryItem.effectiveMimeType(): String = when (this) {
    is GalleryItem.LocalOnly -> local.mimeType
    is GalleryItem.Synced    -> local.mimeType
    is GalleryItem.CloudOnly -> cloud.mimeType
}

fun selectionMimeCounts(items: Collection<GalleryItem>): SelectionMimeCounts {
    val videos = items.count { it.effectiveMimeType().startsWith("video/") }
    return SelectionMimeCounts(photos = items.size - videos, videos = videos)
}

/** Drive linkIds of the selection. A device-only photo has no cloud copy, so it contributes no id
 *  and drops out of every album-membership question. */
fun selectionCloudLinkIds(items: Collection<GalleryItem>): Set<String> =
    items.mapNotNullTo(mutableSetOf()) { item ->
        when (item) {
            is GalleryItem.Synced    -> item.cloud.linkId
            is GalleryItem.CloudOnly -> item.cloud.linkId
            is GalleryItem.LocalOnly -> null
        }
    }

/**
 * How much of a selection an album already holds, as the add-to-album picker reports it.
 *
 * - [All]: every cloud-backed selected photo is already in the album.
 * - [Some]: [inAlbum] of [total] are, so the picker shows the fraction.
 * - [None]: the album holds nothing in the selection, and the picker stays blank.
 */
sealed interface AlbumMembership {
    data object None : AlbumMembership
    data object All : AlbumMembership
    data class Some(val inAlbum: Int, val total: Int) : AlbumMembership
}

/**
 * Membership of [selectedPhotoLinkIds] (a selection's Drive linkIds, per [selectionCloudLinkIds])
 * against one album's [albumMemberIds].
 *
 * The denominator counts only cloud-backed photos: a device-only photo cannot be in a Drive album,
 * so counting it would read as a permanent shortfall no action could clear. A selection with no
 * cloud copy at all therefore resolves to [AlbumMembership.None] rather than a zero fraction.
 */
fun albumMembershipState(
    selectedPhotoLinkIds: Set<String>,
    albumMemberIds: Set<String>,
): AlbumMembership {
    if (selectedPhotoLinkIds.isEmpty()) return AlbumMembership.None
    val inAlbum = selectedPhotoLinkIds.count { it in albumMemberIds }
    return when (inAlbum) {
        0 -> AlbumMembership.None
        selectedPhotoLinkIds.size -> AlbumMembership.All
        else -> AlbumMembership.Some(inAlbum, selectedPhotoLinkIds.size)
    }
}

/**
 * Whether [item] sits in no album at all, against [inAnyAlbumLinkIds] — every photo linkId held by
 * any album. Backs the album picker's unfiled-only filter.
 *
 * A device-only photo has no cloud copy for an album to hold, so it is unfiled by construction and
 * counts as unfiled rather than dropping out of the filter.
 */
fun isUnfiled(item: GalleryItem, inAnyAlbumLinkIds: Set<String>): Boolean = when (item) {
    is GalleryItem.LocalOnly -> true
    is GalleryItem.Synced    -> item.cloud.linkId !in inAnyAlbumLinkIds
    is GalleryItem.CloudOnly -> item.cloud.linkId !in inAnyAlbumLinkIds
}
