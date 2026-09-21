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
 * One completed import run, recorded so the history screen can show what was brought in and when. A row
 * is written once a run finishes; the staged rows it summarises are cleared, so the counts here are the
 * durable record of the run rather than a live view.
 *
 * [id] auto-generates so each run needs no id chosen up front, and [importedAt] carries an index so the
 * history reads back newest first without sorting the whole table. [zipId] and [fileName] name the
 * archive that was imported; [total] is how many entries the run reviewed, split into how many were
 * [uploaded], [skipped] (the entries the user excluded), and [failed] (the entries that errored).
 *
 * [runId] ties this summary to the per-photo upload ledger recording what the run sent, so an undo can
 * move exactly those photos to the trash. It is null where a run has no ledger.
 */
@Entity(
    tableName = "import_history",
    indices = [Index("importedAt")],
)
data class ImportHistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val zipId: String,
    val fileName: String,
    val importedAt: Long,
    val total: Int,
    val uploaded: Int,
    val skipped: Int,
    val failed: Int,
    val runId: String? = null,
)
