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

package eu.akoos.photos.domain.usecase

import eu.akoos.photos.domain.entity.GalleryItem

// Determines whether [item] belongs to a PhotoCategory based on whatever info is locally
// available (mimeType, filename, dimensions, bucket name) PLUS any server-assigned PhotoTag
// already present on the cloud counterpart and whatever the background scanner detected off the
// device file itself. Server tags always win when set; the local heuristics are a fallback so
// freshly-uploaded photos and pure local-only items can still be filtered while the server-side
// AI runs, and the scanner's detections are added to them rather than replacing them, because
// each side sees things the other cannot. See [detectedTagsOf].
//
// Above all of that sits the set a user picked for a device photo: non-empty, it is the complete
// answer and every automatic source is skipped. See [userTagsOf].
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
        // The user's own set outranks every automatic source, so it is read before the detector
        // cache below rather than merged into it.
        val userTags = userTagsOf(item)
        if (userTags.isNotEmpty()) return tagId in userTags

        // The cached detector result joins the heuristics rather than replacing them (see
        // [detectedTagsOf]).
        val detected = detectedTagsOf(item)
        if (tagId in detected) return true

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
                // Panorama as a heuristic fallback only (nothing detected off the file's own bytes
                // yet): require a ratio far past a phone screen's ~2.2:1 AND exclude screenshots, so
                // a tall screenshot or a long receipt scan is never mistaken for a panorama.
                if (detected.isEmpty() && w > 0 && h > 0 && !belongsTo(item, tagId = 1)) {
                    val ratio = maxOf(w, h).toFloat() / minOf(w, h).toFloat()
                    ratio >= 2.8f
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

    /**
     * Every type tag an item matches, computed in a SINGLE pass. Equivalent to calling [belongsTo]
     * once per tag, but lowercases mime/name/bucket once rather than per tag. The grid cell checks
     * several tags per tile, so at the densest zoom that repeated string work dominated the per-cell
     * cost; this collapses it to one pass. Semantics match [belongsTo] tag-for-tag.
     */
    fun classify(item: GalleryItem): Set<Int> {
        // The user's own set outranks every automatic source (same ordering as belongsTo).
        val userTags = userTagsOf(item)
        if (userTags.isNotEmpty()) return userTags

        val detected = detectedTagsOf(item)
        val cloudTags = when (item) {
            is GalleryItem.Synced    -> item.cloud.tags
            is GalleryItem.CloudOnly -> item.cloud.tags
            is GalleryItem.LocalOnly -> emptySet()
        }
        val mime = mimeTypeOf(item).lowercase()
        val name = displayNameOf(item).lowercase()
        val bucket = bucketOf(item)?.lowercase() ?: ""
        val w = widthOf(item)
        val h = heightOf(item)

        val result = HashSet<Int>(cloudTags)   // server tags always win
        result.addAll(detected)
        if (mime.startsWith("video/")) result.add(2)
        val isScreenshot = mime.startsWith("image/") && (
            bucket.contains("screenshot") ||
            name.startsWith("screenshot") ||
            name.startsWith("screen_shot")
        )
        if (isScreenshot) result.add(1)
        if (bucket.contains("live photo") || bucket == "live") result.add(3)
        if (bucket.contains("motion photo") || name.startsWith("mvimg_")) result.add(4)
        if (bucket.contains("burst") || name.startsWith("burst")) result.add(7)
        // Panorama heuristic only for a file nothing was detected off, and only when the tile isn't
        // already a screenshot (by tag or heuristic).
        if (detected.isEmpty() && w > 0 && h > 0 && 1 !in result) {
            val ratio = maxOf(w, h).toFloat() / minOf(w, h).toFloat()
            if (ratio >= 2.8f) result.add(8)
        }
        val ext = name.substringAfterLast('.', "")
        if (ext in RAW_EXTS || mime in RAW_MIMES) result.add(9)
        return result
    }

    private val RAW_EXTS = setOf("dng", "cr2", "cr3", "nef", "arw", "raf", "orf", "rw2", "pef", "srw")
    private val RAW_MIMES = setOf("image/x-adobe-dng", "image/x-canon-cr2", "image/x-nikon-nef", "image/x-raw")

    /**
     * The categories a user picked for the item's device file, empty when they picked none.
     *
     * Empty means "no choice made, categorise automatically", which is also what clearing every
     * category leaves behind, so an untouched photo keeps exactly the categorisation it has.
     * Non-empty it is complete, and returned with no union and no heuristics on top, because the
     * set is seeded from the photo's effective categories the moment the first one is toggled.
     *
     * Read from its own field rather than folded into the detector's cache: an empty detector set
     * means "not scanned yet" and lets the heuristics run, so a user's choice living there would
     * pass as a finished detection on an unscanned file and strip, say, the video marker off a
     * video.
     *
     * Only a photo living solely on the device answers with one. A backed-up photo falls through to
     * its server set even though it still has a device file, because its chips write straight to
     * Drive and another client can change them too, so letting a device-side copy of the answer win
     * would show a stale set after either. A choice made before a photo is backed up survives by
     * riding along in the upload, not by outranking what the server then reports.
     */
    private fun userTagsOf(item: GalleryItem): Set<Int> = when (item) {
        is GalleryItem.LocalOnly -> item.local.userTags
        is GalleryItem.Synced    -> emptySet()
        is GalleryItem.CloudOnly -> emptySet()
    }

    /**
     * The tags the background scanner detected off the device file's own bytes, empty when it has
     * not run yet.
     *
     * Added to the heuristics below, never in place of them. The detector is handed a uri, a mime,
     * a name and a size, so it is the only source for the XMP-derived Motion Photo and Panorama
     * markers, and it is blind to the folder the file sits in. Letting a detection stand as the
     * whole answer therefore silenced every folder rule for any file the detector found anything
     * at all in: a video in a Live Photos or Burst folder detects as a video and stopped matching
     * either of those chips once its scan landed.
     *
     * The folder side stays here rather than moving into the detector because the cached row is
     * keyed on the file's modified time and size. Moving a file between folders changes neither, so
     * a folder-derived tag written into that cache would never be re-detected; read from the live
     * MediaStore row it cannot go stale.
     *
     * Only a photo living solely on the device answers with one, matching [userTagsOf]: a backed-up
     * photo's categories are the server's, and it already runs the heuristics on top of them.
     */
    private fun detectedTagsOf(item: GalleryItem): Set<Int> = when (item) {
        is GalleryItem.LocalOnly -> item.local.tags
        is GalleryItem.Synced    -> emptySet()
        is GalleryItem.CloudOnly -> emptySet()
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
