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

import eu.akoos.photos.domain.repository.LocalMediaRepository
import eu.akoos.photos.domain.repository.SyncStateRepository
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Where a rewrite this app makes of a user's device file on its own account (a metadata write or strip,
 * not a pixel edit) is announced, so the file's backup row can record the bytes it left
 * ([OwnDeviceWriteRecorder]). A rewrite that lands while nothing listens, as in a test, records nothing,
 * and the file is then judged by its modified time like any other change.
 */
object OwnDeviceWrites {
    @Volatile
    private var sink: ((String) -> Unit)? = null

    fun install(onWrite: (uri: String) -> Unit) {
        sink = onWrite
    }

    /** The device file at [uri] has just been rewritten by this app and is complete. */
    fun wrote(uri: String) {
        sink?.invoke(uri)
    }

    internal fun clear() {
        sink = null
    }
}

/**
 * Records what each [OwnDeviceWrites] announcement left on the device: the file's SHA-1 read right after
 * the write goes on its row ([SyncStateRepository.recordOwnDeviceWrite]). Free-up then deletes those
 * bytes as backed up, and reconcile keeps the backup stamp, where the file's later modified time would
 * otherwise read as an edit and put a second copy on Drive.
 */
@Singleton
class OwnDeviceWriteRecorder @Inject constructor(
    private val syncStateRepo: SyncStateRepository,
    private val localRepo: LocalMediaRepository,
) {
    suspend fun record(uri: String) {
        try {
            val sha1 = localRepo.sha1(uri) ?: return
            syncStateRepo.recordOwnDeviceWrite(uri, sha1)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            // Nothing recorded: the file is judged by its modified time, like a change made elsewhere.
        }
    }
}
