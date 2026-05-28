package eu.akoos.photos.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow
import eu.akoos.photos.data.db.entity.DayMetaEntity

@Dao
interface DayMetaDao {

    /**
     * Stream all DayMeta rows for [userId]. The Calendar view uses this to overlay user-
     * picked covers + place labels on top of the auto-computed month grid.
     */
    @Query("SELECT * FROM day_meta WHERE userId = :userId")
    fun observeAll(userId: String): Flow<List<DayMetaEntity>>

    /** Live row for a specific day — backs the Day Detail screen's editable fields. */
    @Query("SELECT * FROM day_meta WHERE userId = :userId AND date = :date LIMIT 1")
    fun observeByDate(userId: String, date: String): Flow<DayMetaEntity?>

    @Query("SELECT * FROM day_meta WHERE userId = :userId AND date = :date LIMIT 1")
    suspend fun getByDate(userId: String, date: String): DayMetaEntity?

    /**
     * Returns days whose user-authored location or description text contains [needle]
     * (case-insensitive). Backs the Calendar search bar's "location" + "description"
     * matchers — date-string and month-name matching is done in-memory by the VM since
     * those keys are derived from the ISO `date` column, not stored as free text.
     *
     * [needle] should already include the `%` wildcards (e.g. `"%budapest%"`) so the
     * caller controls anchored vs contains-style matching.
     */
    @Query(
        """
        SELECT * FROM day_meta
        WHERE userId = :userId
          AND (
            LOWER(IFNULL(locationText, '')) LIKE :needle
            OR LOWER(IFNULL(description, '')) LIKE :needle
          )
        """
    )
    suspend fun searchByText(userId: String, needle: String): List<DayMetaEntity>

    @Upsert
    suspend fun upsert(entity: DayMetaEntity)

    @Query("DELETE FROM day_meta WHERE userId = :userId AND date = :date")
    suspend fun delete(userId: String, date: String)
}
