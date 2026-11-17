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

    /** When the one-time look for the photo editor's short copies first ran for [userId]: the stored moment,
     *  or [nowMs], stored now, on the first call. */
    suspend fun editorCopiesCheckStartedMs(userId: String, nowMs: Long): Long

    /** Whether the one-time look for the photo editor's short copies ran for [userId]. */
    suspend fun editorCopiesChecked(userId: String): Boolean

    /** Records that the look for the photo editor's short copies ran for [userId]. */
    suspend fun markEditorCopiesChecked(userId: String)
}
