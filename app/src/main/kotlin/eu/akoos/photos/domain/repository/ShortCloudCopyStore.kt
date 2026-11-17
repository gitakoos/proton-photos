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

package eu.akoos.photos.domain.repository

/**
 * Drive copies known to hold fewer bytes than the device file they were uploaded from, by link id. Their
 * content hash still names the whole file, so nothing may pair a device file to one by that hash or count
 * it as that file's backup: reconcile leaves them out of its matching, and free-up keeps any device copy
 * paired to one. A link stays here for good; it never becomes whole.
 */
interface ShortCloudCopyStore {
    /** Every link id recorded as short. */
    suspend fun all(): Set<String>

    /** Records [linkIds] as short. */
    suspend fun add(linkIds: Collection<String>)

    /** Photo editor copies whose shortness cannot be told from what the device holds: never freed. */
    suspend fun unverifiable(): Set<String>

    /** Records [linkIds] as copies whose shortness cannot be told. */
    suspend fun addUnverifiable(linkIds: Collection<String>)

    /** The short Drive copy, by link id, that the whole backup of [deviceUri] replaces, if any. */
    suspend fun replacedBy(deviceUri: String): String?

    /** Records that the whole backup of [deviceUri] replaces the short copy [linkId]. */
    suspend fun addReplacement(deviceUri: String, linkId: String)

    /** Every recorded replacement: device uri to the short copy's link id. */
    suspend fun replacements(): Map<String, String>

    /** Forgets the replacement recorded for [deviceUri]. */
    suspend fun removeReplacement(deviceUri: String)

    /** Whether [deviceUri] left the pending delete queue for a short Drive copy and is deleted after its whole backup. */
    suspend fun deleteAfterWholeBackup(deviceUri: String): Boolean

    /** Records [deviceUris] to be deleted after their whole backup. */
    suspend fun addDeleteAfterWholeBackup(deviceUris: Collection<String>)

    /** Forgets the delete recorded for [deviceUri]. */
    suspend fun removeDeleteAfterWholeBackup(deviceUri: String)

    /** When the one-time look for the photo editor's short copies first ran for [userId]: the stored moment,
     *  or [nowMs], stored now, on the first call. */
    suspend fun editorCopiesCheckStartedMs(userId: String, nowMs: Long): Long

    /** Whether the one-time look for the photo editor's short copies ran for [userId]. */
    suspend fun editorCopiesChecked(userId: String): Boolean

    /** Records that the look for the photo editor's short copies ran for [userId]. */
    suspend fun markEditorCopiesChecked(userId: String)
}
