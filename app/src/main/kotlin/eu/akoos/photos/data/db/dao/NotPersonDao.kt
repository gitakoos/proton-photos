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

package eu.akoos.photos.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import eu.akoos.photos.data.db.entity.NotPersonEntity

/**
 * One "not this person" mark paired with the excluded face's photo and box, so the exclusions screen
 * can show a face crop per mark. Inner-joined on the face, so a mark whose face no longer exists is
 * left out (there is nothing to render or undo for it).
 */
data class NotPersonFace(
    val personName: String,
    val faceId: String,
    val photoKey: String,
    val boxLeft: Float,
    val boxTop: Float,
    val boxRight: Float,
    val boxBottom: Float,
)

/**
 * The "not this person" feedback the suggestion review records, keyed by person name so it survives a
 * clustering rebuild. Read at suggestion time (to never re-offer a rejected match) and at cluster time
 * (so the attract pass never pulls a rejected face into that person).
 */
@Dao
interface NotPersonDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun add(rows: List<NotPersonEntity>)

    /** Every not-person mark for the account, so a rebuild and the suggestion pass read it in one go. */
    @Query("SELECT * FROM not_person WHERE userId = :userId")
    suspend fun allForUser(userId: String): List<NotPersonEntity>

    /** Every "not this person" mark for the account paired with its face's photo and box, the excluded
     *  faces screen's source. */
    @Query(
        "SELECT np.personName AS personName, np.faceId AS faceId, f.photoKey AS photoKey, " +
            "f.`left` AS boxLeft, f.`top` AS boxTop, f.`right` AS boxRight, f.`bottom` AS boxBottom " +
            "FROM not_person np JOIN face f ON f.id = np.faceId " +
            "WHERE np.userId = :userId ORDER BY np.personName, np.faceId",
    )
    suspend fun facesForUser(userId: String): List<NotPersonFace>

    /** Undo one "not this person" mark, so the face can rejoin that name on the next clustering pass. */
    @Query("DELETE FROM not_person WHERE userId = :userId AND personName = :personName AND faceId = :faceId")
    suspend fun deleteMark(userId: String, personName: String, faceId: String)

    /** Re-key the marks when a person is renamed, so the rejections follow the new name. */
    @Query("UPDATE not_person SET personName = :newName WHERE userId = :userId AND personName = :oldName")
    suspend fun rename(userId: String, oldName: String, newName: String)

    /** Drop the "not this person" marks for a name, when that person is dismissed as "not a person". */
    @Query("DELETE FROM not_person WHERE userId = :userId AND personName = :personName")
    suspend fun clearForName(userId: String, personName: String)

    @Query("DELETE FROM not_person WHERE userId = :userId")
    suspend fun clearForUser(userId: String)
}
