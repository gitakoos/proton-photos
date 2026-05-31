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
    val captureTimeMs: Long

    data class CloudOnly(val cloud: CloudPhoto) : GalleryItem {
        override val captureTimeMs get() = cloud.captureTime * 1000L
    }

    data class LocalOnly(val local: LocalMediaItem) : GalleryItem {
        override val captureTimeMs get() = local.dateTaken
    }

    data class Synced(val cloud: CloudPhoto, val local: LocalMediaItem) : GalleryItem {
        /**
         * Cloud captureTime is authoritative — it's set at upload from the original local
         * DATE_TAKEN and survives MediaStore quirks (some emulator builds + some Android
         * versions silently drop a DATE_TAKEN write on a newly-inserted row, leaving the
         * downloaded file dated "now"). Falling back to local.dateTaken only when the
         * cloud carries no captureTime — rare for anything uploaded by our own app.
         */
        override val captureTimeMs get() =
            if (cloud.captureTime > 0) cloud.captureTime * 1000L else local.dateTaken
    }
}
