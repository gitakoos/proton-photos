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

package eu.akoos.photos.presentation.gallery

import eu.akoos.photos.domain.entity.LocalMediaItem

/** One existing device folder the "Move to folder" picker offers as a target: its bucket [name], a
 *  [coverUri] for the newest photo in it, and how many device photos it holds ([count]). */
data class DeviceFolderChoice(val name: String, val coverUri: String?, val count: Int)

/**
 * Group device photos into one [DeviceFolderChoice] per MediaStore bucket for the move picker: the
 * newest photo's uri as the cover and the device-photo count, most-populated first then by name. A
 * photo with no bucket is skipped, and a cloud-only photo carries no [LocalMediaItem] so it never
 * reaches here. Pure, so it runs off-Main and is unit-testable.
 */
fun deviceFolderChoices(items: List<LocalMediaItem>): List<DeviceFolderChoice> {
    val byBucket = LinkedHashMap<String, MutableList<LocalMediaItem>>()
    for (local in items) {
        val bucket = local.bucketName?.takeIf { it.isNotBlank() } ?: continue
        byBucket.getOrPut(bucket) { mutableListOf() }.add(local)
    }
    return byBucket
        .map { (name, locals) ->
            DeviceFolderChoice(name, locals.maxByOrNull { it.dateTaken }?.uri, locals.size)
        }
        .sortedWith(compareByDescending<DeviceFolderChoice> { it.count }.thenBy { it.name.lowercase() })
}
