package eu.akoos.photos.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Per-day user-authored metadata for the Calendar view.
 *
 * Each row is keyed on an ISO-8601 date string (e.g. "2026-05-28"). The Calendar view
 * surfaces these so users can pin a cover thumbnail, add a place name or jot a note for
 * a specific day. Nothing here is required — every field defaults to null and the row
 * simply doesn't exist if the user hasn't touched that day yet.
 *
 * Note: we intentionally key on the date string rather than a (userId, date) composite
 * primary key, because Room's @PrimaryKey requires a single column. To keep multi-user
 * installs honest, [DayMetaDao.observeAll] / [getByDate] still filter by [userId] so a
 * second account can never read or overwrite the previous account's day notes.
 */
@Entity(tableName = "day_meta")
data class DayMetaEntity(
    @PrimaryKey val date: String,
    val userId: String,
    /**
     * URI (local content://...) or cloud linkId of the user-picked cover photo. Null
     * means "auto-pick the first photo by captureTime for this day".
     */
    val coverPhotoUri: String? = null,
    /** Free-text location label — user types things like "Budapest" or "Mom's house". */
    val locationText: String? = null,
    /** Longer-form note about this day. */
    val description: String? = null,
    val updatedAt: Long = System.currentTimeMillis(),
)
