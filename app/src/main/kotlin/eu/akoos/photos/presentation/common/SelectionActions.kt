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
