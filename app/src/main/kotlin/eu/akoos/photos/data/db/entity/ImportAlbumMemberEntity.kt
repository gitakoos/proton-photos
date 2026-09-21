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
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One membership edge tying a photo an import run uploaded to the export album it came from, recorded so a
 * later pass can recreate that album in Drive from the links the run just created. The upload pass appends
 * one row per (uploaded photo, album) pair; the album phase groups the run's rows by [albumName] to build
 * each album's member set.
 *
 * [id] auto-generates so each edge needs no id chosen up front, and [runId] groups every edge of one run
 * behind an index so the run's albums read back without scanning the whole table. [albumName] is the export
 * album folder the photo belonged to and [linkId] is the Drive link the upload created. A photo that lived
 * in several export albums produces one row per album, same [linkId] under a different [albumName], so
 * recreating each album picks up that photo independently.
 */
@Entity(
    tableName = "import_album_member",
    indices = [Index("runId")],
)
data class ImportAlbumMemberEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val runId: String,
    val albumName: String,
    val linkId: String,
)
