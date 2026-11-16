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

package eu.akoos.photos.data.upload

import eu.akoos.photos.domain.repository.SyncStateRepository
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Where a rewrite this app makes of a user's device file on its own account (a metadata write or strip,
 * not a pixel edit) is announced, with the file's SHA-1 just before and just after it, so the file's
 * backup row can take the new bytes as backed up when the old ones were ([OwnDeviceWriteRecorder]). A
 * rewrite that lands while nothing listens, as in a test, records nothing, and the file is then judged
 * by its modified time like any other change.
 */
object OwnDeviceWrites {
    @Volatile
    private var sink: ((uri: String, before: String, after: String) -> Unit)? = null

    /** Whether anything listens, so a writer hashes the file around its write only when it counts. */
    val listening: Boolean get() = sink != null

    fun install(onWrite: (uri: String, before: String, after: String) -> Unit) {
        sink = onWrite
    }

    /** The device file at [uri] held [before] and now, rewritten by this app and complete, holds [after]. */
    fun wrote(uri: String, before: String, after: String) {
        sink?.invoke(uri, before, after)
    }

    internal fun clear() {
        sink = null
    }
}

/**
 * Records what each [OwnDeviceWrites] announcement left on the device on the file's backup row
 * ([SyncStateRepository.recordOwnDeviceWrite]), only when the bytes it rewrote were the backed-up ones.
 * Free-up then deletes the new bytes as backed up, and reconcile keeps the backup stamp, where the file's
 * later modified time would otherwise read as an edit and put a second copy on Drive. A file that held
 * anything else before the write (an edit made elsewhere, a pixel edit) is left to read as changed.
 */
@Singleton
class OwnDeviceWriteRecorder @Inject constructor(
    private val syncStateRepo: SyncStateRepository,
) {
    suspend fun record(uri: String, before: String, after: String) {
        try {
            syncStateRepo.recordOwnDeviceWrite(uri, before, after)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            // Nothing recorded: the file is judged by its modified time, like a change made elsewhere.
        }
    }
}
