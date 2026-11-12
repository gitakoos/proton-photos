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

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * What the device EXIF walk already learned from one file, so the next walk does not open it again.
 * The walk records a GPS fix as a [PhotoLocationEntity] row and a wrong capture date as an override
 * entry, but a file with no GPS, or whose date was already right, leaves neither behind; without this
 * row every walk would re-read every such file, which on a large library is most of it.
 *
 * Both flags hold only while the file still reads [modifiedSeconds] (MediaStore DATE_MODIFIED): an
 * edit moves that time, and the walk reads the file again.
 *
 * [gpsNone]: the file was read for GPS (with the media location grant) and carries none.
 * [dateRead]: the file's own capture date was read and compared with the stored one.
 *
 * Keyed by the device content URI, the same id [PhotoLocationEntity] uses for a device photo. Not
 * per account: these are facts about the file, whoever is signed in.
 */
@Entity(tableName = "local_exif_check")
data class LocalExifCheckEntity(
    @PrimaryKey val uri: String,
    val modifiedSeconds: Long,
    val gpsNone: Boolean,
    val dateRead: Boolean,
)
