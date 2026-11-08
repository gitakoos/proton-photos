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

package eu.akoos.photos.data.face

import eu.akoos.photos.data.db.dao.FaceCurationTimeDao
import eu.akoos.photos.data.db.entity.FaceCurationTimeEntity
import eu.akoos.photos.data.db.entity.FaceCurationTimeEntity.Companion.KIND_COVER
import eu.akoos.photos.data.db.entity.FaceCurationTimeEntity.Companion.KIND_MANUAL_PHOTO
import eu.akoos.photos.data.db.entity.FaceCurationTimeEntity.Companion.KIND_NOT_PERSON

/**
 * Notes the curation the user changes by hand, with its time, for the shared face copy: a photo attached to a person
 * or taken off them, a face marked as not being them or the mark taken back, a cover picked or dropped. Another
 * install takes the later change of each, a removal included. A rebuild, a restore or a rekey is not noted.
 */
object FaceCurationLog {

    /** The removals kept, and carried in a face copy; older ones have long reached every install. */
    const val MAX_REMOVALS = 2000

    suspend fun manualPhotos(dao: FaceCurationTimeDao, account: String, name: String, photoKeys: Collection<String>, removed: Boolean, at: Long) =
        note(dao, account, KIND_MANUAL_PHOTO, name, photoKeys, removed, at)

    suspend fun notPerson(dao: FaceCurationTimeDao, account: String, name: String, faceIds: Collection<String>, removed: Boolean, at: Long) =
        note(dao, account, KIND_NOT_PERSON, name, faceIds, removed, at)

    suspend fun cover(dao: FaceCurationTimeDao, account: String, name: String, removed: Boolean, at: Long) =
        note(dao, account, KIND_COVER, name, listOf(""), removed, at)

    private suspend fun note(
        dao: FaceCurationTimeDao,
        account: String,
        kind: Int,
        name: String,
        refs: Collection<String>,
        removed: Boolean,
        at: Long,
    ) {
        if (name.isBlank() || refs.isEmpty()) return
        dao.set(refs.map { FaceCurationTimeEntity(account, kind, name, it, removed, at) })
        if (removed) dao.pruneRemovals(account, MAX_REMOVALS)
    }
}
