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

package eu.akoos.photos.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One photo or video on Drive queued to be compressed in place, persisted so the drain survives a process
 * kill and a selection of thousands is worked through a slice at a time. Keyed on the original [linkId], so
 * asking again for a queued photo changes nothing. The photo itself is not stored: the drain reads it back
 * from the listing by its link.
 *
 * [newLinkId] is the compressed copy once its upload returned, recorded before the album re-add and the
 * trash so a kill there resumes from that link instead of uploading a second copy. [attemptRun] marks the
 * drain run that last tried the row and kept it for a retry, so the same run never picks it up again, and
 * [attempts] counts the failed tries of this photo alone, so a long batch never gives a photo up early.
 * [carriedOver] records that the original's labels, faces and favourite moved to the copy, after which the
 * copy is never taken back.
 */
@Entity(tableName = "pending_cloud_compress", indices = [Index(value = ["enqueuedAt"])])
data class PendingCloudCompressEntity(
    @PrimaryKey val linkId: String,
    val userId: String,
    val enqueuedAt: Long,
    val newLinkId: String? = null,
    val attemptRun: String? = null,
    @ColumnInfo(defaultValue = "0") val attempts: Int = 0,
    @ColumnInfo(defaultValue = "0") val carriedOver: Boolean = false,
)
