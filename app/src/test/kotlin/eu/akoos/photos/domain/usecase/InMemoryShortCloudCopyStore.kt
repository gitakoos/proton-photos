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

import eu.akoos.photos.domain.repository.ShortCloudCopyStore

/** A [ShortCloudCopyStore] held in memory, for the tests. The editor-copy look counts as done unless [checked] is false. */
internal class InMemoryShortCloudCopyStore(var checked: Boolean = true) : ShortCloudCopyStore {
    val ids = LinkedHashSet<String>()
    var startedMs: Long? = null

    override suspend fun all(): Set<String> = ids.toSet()

    override suspend fun add(linkIds: Collection<String>) {
        ids += linkIds
    }

    val unverifiableIds = LinkedHashSet<String>()
    val replacements = LinkedHashMap<String, String>()
    val deleteAfterWhole = LinkedHashSet<String>()

    override suspend fun unverifiable(): Set<String> = unverifiableIds.toSet()

    override suspend fun addUnverifiable(linkIds: Collection<String>) {
        unverifiableIds += linkIds
    }

    override suspend fun replacedBy(deviceUri: String): String? = replacements[deviceUri]

    override suspend fun addReplacement(deviceUri: String, linkId: String) {
        replacements[deviceUri] = linkId
    }

    override suspend fun replacements(): Map<String, String> = replacements.toMap()

    override suspend fun removeReplacement(deviceUri: String) {
        replacements.remove(deviceUri)
    }

    override suspend fun deleteAfterWholeBackup(deviceUri: String): Boolean = deviceUri in deleteAfterWhole

    override suspend fun addDeleteAfterWholeBackup(deviceUris: Collection<String>) {
        deleteAfterWhole += deviceUris
    }

    override suspend fun removeDeleteAfterWholeBackup(deviceUri: String) {
        deleteAfterWhole.remove(deviceUri)
    }

    override suspend fun editorCopiesCheckStartedMs(userId: String, nowMs: Long): Long =
        startedMs ?: nowMs.also { startedMs = it }

    override suspend fun editorCopiesChecked(userId: String): Boolean = checked

    override suspend fun markEditorCopiesChecked(userId: String) {
        checked = true
    }
}
