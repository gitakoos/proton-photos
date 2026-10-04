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

package eu.akoos.photos.data.label

import android.content.Context
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.akoos.photos.worker.LabelSyncWorker
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/** Thrown by a label edit the user made while the labels were locked ([LabelWriteGate]). */
class LabelsLockedException : IllegalStateException("labels are locked while they are backed up")

/**
 * Whether the user may edit labels right now. While the labels are being saved to or restored from Drive
 * they stay as they are, so the copy that goes up or comes down is not changed under the run. Only the
 * user's own edits wait ([PhotoLabelRepository]'s create, add, remove, rename and delete); the moves the app
 * makes itself when a photo is backed up or hidden do not.
 */
interface LabelWriteGate {
    /** True while a save or restore of the labels is queued or running. */
    val locked: Flow<Boolean>

    suspend fun isLocked(): Boolean = locked.first()

    companion object {
        /** A gate that never locks. */
        val Open: LabelWriteGate = object : LabelWriteGate {
            override val locked: Flow<Boolean> = flowOf(false)
        }
    }
}

/** The production gate: locked for as long as the unique [LabelSyncWorker] job is queued or running. */
@Singleton
class WorkLabelWriteGate @Inject constructor(
    @ApplicationContext private val context: Context,
) : LabelWriteGate {
    override val locked: Flow<Boolean>
        get() = WorkManager.getInstance(context)
            .getWorkInfosForUniqueWorkFlow(LabelSyncWorker.UNIQUE_WORK_NAME)
            .map { infos -> infos.any { !it.state.isFinished } }
            .distinctUntilChanged()
}
