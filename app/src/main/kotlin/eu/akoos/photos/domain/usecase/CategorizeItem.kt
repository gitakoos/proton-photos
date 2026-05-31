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

package eu.akoos.photos.domain.usecase

import eu.akoos.photos.domain.entity.GalleryItem

// Determines whether [item] belongs to a PhotoCategory based on whatever info is locally
// available (mimeType, filename, dimensions, bucket name) PLUS any server-assigned PhotoTag
// already present on the cloud counterpart. Server tags always win when set; the local
// heuristics are a fallback so freshly-uploaded photos and pure local-only items can still
// be filtered while the server-side AI runs.
//
// Mapping (matches Drive PhotoTag enum):
//   0 Favorites    — cloud-only (DataStore + tag 0)
//   1 Screenshots  — image mime AND bucket "Screenshots" or filename "Screenshot_..."
//   2 Videos       — mime startsWith "video/"
//   3 LivePhotos   — bucket "Live", "Live Photos"
//   4 MotionPhotos — bucket "Motion Photos" or filename starts with MVIMG_
//   5 Selfies      — server tag only
//   6 Portraits    — server tag only
//   7 Bursts       — bucket "Burst" or filename starts with BURST
//   8 Panoramas    — aspect ratio more extreme than 2:1 (either direction)
//   9 Raw          — RAW mime types or extensions (dng, cr2, nef, arw, ...)
object CategorizeItem {
    fun belongsTo(item: GalleryItem, tagId: Int): Boolean {
        // Server tag always wins.
        val cloudTags = when (item) {
            is GalleryItem.Synced    -> item.cloud.tags
            is GalleryItem.CloudOnly -> item.cloud.tags
            is GalleryItem.LocalOnly -> emptySet()
        }
        if (tagId in cloudTags) return true

        val mime = mimeTypeOf(item).lowercase()
        val name = displayNameOf(item).lowercase()
        val bucket = bucketOf(item)?.lowercase() ?: ""
        val w = widthOf(item)
        val h = heightOf(item)

        return when (tagId) {
            2 -> mime.startsWith("video/")
            1 -> mime.startsWith("image/") && (
                bucket.contains("screenshot") ||
                name.startsWith("screenshot") ||
                name.startsWith("screen_shot")
            )
            3 -> bucket.contains("live photo") || bucket == "live"
            4 -> bucket.contains("motion photo") || name.startsWith("mvimg_")
            7 -> bucket.contains("burst") || name.startsWith("burst")
            8 -> {
                if (w > 0 && h > 0) {
                    val ratio = maxOf(w, h).toFloat() / minOf(w, h).toFloat()
                    ratio >= 2.0f
                } else false
            }
            9 -> {
                val ext = name.substringAfterLast('.', "")
                ext in setOf("dng", "cr2", "cr3", "nef", "arw", "raf", "orf", "rw2", "pef", "srw") ||
                    mime in setOf("image/x-adobe-dng", "image/x-canon-cr2", "image/x-nikon-nef", "image/x-raw")
            }
            else -> false   // 0 (Favorites), 5 (Selfies), 6 (Portraits) → server-only
        }
    }

    private fun mimeTypeOf(item: GalleryItem): String = when (item) {
        is GalleryItem.LocalOnly -> item.local.mimeType
        is GalleryItem.Synced    -> item.local.mimeType.ifEmpty { item.cloud.mimeType }
        is GalleryItem.CloudOnly -> item.cloud.mimeType
    }

    private fun displayNameOf(item: GalleryItem): String = when (item) {
        is GalleryItem.LocalOnly -> item.local.displayName
        is GalleryItem.Synced    -> item.local.displayName
        is GalleryItem.CloudOnly -> item.cloud.displayName
    }

    private fun bucketOf(item: GalleryItem): String? = when (item) {
        is GalleryItem.LocalOnly -> item.local.bucketName
        is GalleryItem.Synced    -> item.local.bucketName
        is GalleryItem.CloudOnly -> null
    }

    private fun widthOf(item: GalleryItem): Int = when (item) {
        is GalleryItem.LocalOnly -> item.local.width
        is GalleryItem.Synced    -> item.local.width
        is GalleryItem.CloudOnly -> 0
    }

    private fun heightOf(item: GalleryItem): Int = when (item) {
        is GalleryItem.LocalOnly -> item.local.height
        is GalleryItem.Synced    -> item.local.height
        is GalleryItem.CloudOnly -> 0
    }
}
