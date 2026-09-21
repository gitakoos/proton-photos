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

import kotlinx.coroutines.flow.Flow
import eu.akoos.photos.domain.entity.LocalMediaItem

interface LocalMediaRepository {
    fun observeLocalMedia(): Flow<List<LocalMediaItem>>
    fun observeTrashedMedia(): Flow<List<LocalMediaItem>>
    fun hasMediaPermission(): Flow<Boolean>
    suspend fun queryByUri(uri: String): LocalMediaItem?

    /**
     * The device photos of one MediaStore bucket, newest first.
     *
     * A caller that only needs one folder asks for that folder rather than filtering a whole-library
     * scan down to it: the query is bounded by the folder, so a surface acting on a single folder
     * never materialises every row the device holds.
     */
    suspend fun queryByBucket(bucketName: String): List<LocalMediaItem>

    /** Lowercase SHA-1 hex of the file's plaintext bytes, or null if it can't be read. Lets
     *  reconcile pair a local file with its cloud copy by content after a reinstall wiped the
     *  stored hash — name-independent, so a rename-on-upload cloud copy still matches. */
    suspend fun sha1(uri: String): String?

    /**
     * Forces a re-query of MediaStore. Use after the user grants the media permission: the
     * MediaStore [android.database.ContentObserver] does not fire on a permission change, so
     * without this trigger the gallery would stay empty until the app restarts. Also the way a
     * side store the scan reads (the capture-date overrides) announces a change the provider
     * itself never sees, so a correction shows without waiting for the next MediaStore event.
     */
    fun notifyMediaChanged()
}
