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

/**
 * Single source of truth for multi-select action visibility over a [GalleryItem] selection.
 *
 * The timeline's selection header and bottom dock both gate their pills on the photo states in
 * the current selection (any device copy present, any cloud-only present, all on-device, etc.).
 * These helpers centralise that type-gating so every surface answers it the same way; read them as
 * the canonical predicates rather than re-deriving the `is LocalOnly` / `is CloudOnly` checks inline.
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
 *  timeline's More menu (Strip / Hide), which only applies to a wholly un-uploaded selection. */
fun allLocalOnly(items: Collection<GalleryItem>): Boolean =
    items.isNotEmpty() && items.all { it is GalleryItem.LocalOnly }

/** The selection holds at least one cloud-only photo. */
fun anyCloudOnly(items: Collection<GalleryItem>): Boolean =
    items.any { it is GalleryItem.CloudOnly }

/** Something in the selection can be pulled down from Drive. Mirrors [anyCloudOnly] — a cloud-only
 *  photo has no local file yet, so it is the downloadable case. Gates the Download pill. */
fun hasDownloadable(items: Collection<GalleryItem>): Boolean = anyCloudOnly(items)

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

/**
 * Where the unified hide action routes a single [GalleryItem].
 *
 * - [VAULT]: a device-backed photo (device-only or synced) moves into the app-private Hidden vault;
 *   its file leaves MediaStore, and for a synced photo the cloud twin is dropped from every listing.
 * - [CLIENT_SIDE]: a cloud-only photo has no device file to vault, so it is hidden client-side by
 *   linkId (the HIDDEN_CLOUD_PHOTO_IDS set); nothing on Drive is changed.
 */
enum class HideTarget { VAULT, CLIENT_SIDE }

/** The hide route for [item]: a device-backed photo (device-only or synced) goes to the
 *  [HideTarget.VAULT]; a cloud-only photo is hidden [HideTarget.CLIENT_SIDE]. */
fun hideTargetFor(item: GalleryItem): HideTarget = when (item) {
    is GalleryItem.LocalOnly, is GalleryItem.Synced -> HideTarget.VAULT
    is GalleryItem.CloudOnly -> HideTarget.CLIENT_SIDE
}

/** Any non-empty selection can be hidden, regardless of the per-item [HideTarget]. Gates whether the
 *  Hide action is offered at all. */
fun anyHideable(items: Collection<GalleryItem>): Boolean = items.isNotEmpty()

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
