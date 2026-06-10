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

package eu.akoos.photos.domain.entity

sealed interface GalleryItem {
    /**
     * Effective capture timestamp in epoch-ms, the single sort + grouping key for every photo
     * list. Derived through [TimestampSanity] so all three states resolve a sub-floor value the
     * same way — see each subtype for which fallback applies.
     */
    val captureTimeMs: Long

    /** Stable per-item identity used as the sort tiebreak so equal-timestamp bursts keep a
     *  total order across re-emissions (mirrors the album comparator's linkId tiebreak). */
    val stableId: String

    data class CloudOnly(val cloud: CloudPhoto) : GalleryItem {
        // No local twin to borrow a DATE_TAKEN from, so a sub-floor cloud captureTime stays
        // as-is (a stripped upload already carries the upload-moment time, not 0).
        override val captureTimeMs get() = cloud.captureTimeMs
        override val stableId get() = cloud.linkId
    }

    data class LocalOnly(val local: LocalMediaItem) : GalleryItem {
        // dateTaken already self-heals to DATE_ADDED at MediaStore query time, so it never
        // arrives as a sub-floor sentinel here.
        override val captureTimeMs get() = local.dateTaken
        override val stableId get() = local.uri
    }

    data class Synced(val cloud: CloudPhoto, val local: LocalMediaItem) : GalleryItem {
        // Cloud captureTime is set at upload from the original DATE_TAKEN and survives the
        // MediaStore quirk where a downloaded file is dated "now"; fall back to the local
        // DATE_TAKEN only when the cloud value is sub-floor.
        override val captureTimeMs get() =
            TimestampSanity.effectiveMs(cloud.captureTimeMs, local.dateTaken)
        override val stableId get() = cloud.linkId
    }
}
