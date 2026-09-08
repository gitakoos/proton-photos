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
 * One photo an import run uploaded to Drive, recorded so a later "undo this import" can move exactly the
 * photos that run sent to the trash. The upload pass appends one row per successful upload; the row lives
 * until the run's ledger is cleared, so an undo survives a process kill and a re-import of the same
 * archive.
 *
 * [id] auto-generates so each uploaded photo needs no id chosen up front, and [runId] groups every row of
 * one run behind an index so the ledger reads back per run without scanning the whole table. [linkId] is
 * the Drive link the upload created and [sha1] is the content digest it sent; an undo trashes a link only
 * while both still match what the run uploaded, so a photo edited or replaced afterwards is left alone.
 * [name] and [dateMs] are carried for the undo preview, and [undone] marks a row whose link has already
 * been trashed so a repeated undo skips it. [alreadyInDrive] marks a photo the run did not upload because
 * an identical one already lived in Drive; the row then carries that pre-existing link, so the history can
 * badge it as skipped and an undo passes it by rather than trashing a photo the run never created.
 */
@Entity(
    tableName = "import_uploaded",
    indices = [Index("runId")],
)
data class ImportUploadedEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val runId: String,
    val linkId: String,
    val sha1: String,
    val name: String? = null,
    val dateMs: Long? = null,
    val undone: Boolean = false,
    val alreadyInDrive: Boolean = false,
)
