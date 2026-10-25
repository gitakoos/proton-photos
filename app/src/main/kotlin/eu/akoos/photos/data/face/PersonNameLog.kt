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

import eu.akoos.photos.data.db.dao.PersonNameDao
import eu.akoos.photos.data.db.entity.PersonNameChangeEntity
import eu.akoos.photos.data.db.entity.PersonNameStateEntity

/**
 * Notes the names the user gives people and the renames and merges they make, with their times, for the shared
 * face copy: another install takes a rename or merge made after the name it holds, so the later one wins
 * everywhere. Only what the user does is noted; a clustering rebuild that carries a name over is not a change.
 */
object PersonNameLog {

    /** The renames and merges kept, and carried in a face copy; older ones have long reached every install. */
    const val MAX_CHANGES = 500

    /** [name] given at [at] to a person who had none. */
    suspend fun named(dao: PersonNameDao, account: String, name: String, at: Long) {
        dao.setState(PersonNameStateEntity(account, name, at))
    }

    /** The person named [from] called [to] from [at] on, renamed or folded into the person already called that. */
    suspend fun renamed(dao: PersonNameDao, account: String, from: String, to: String, at: Long) {
        if (from == to) return
        dao.addChange(PersonNameChangeEntity(account, from, to, at))
        dao.deleteState(account, from)
        dao.setState(PersonNameStateEntity(account, to, at))
        dao.pruneChanges(account, MAX_CHANGES)
    }
}
