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

package eu.akoos.photos.data.mlsync

import android.content.Context
import android.util.Log
import androidx.work.WorkManager
import androidx.work.await
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.akoos.photos.data.face.FaceModelAssets
import eu.akoos.photos.data.face.FaceReattachSnapshot
import eu.akoos.photos.data.sdk.SdkDriveClientProvider
import eu.akoos.photos.worker.LabelSyncWorker
import eu.akoos.photos.worker.MlSyncWorker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import me.proton.core.domain.entity.UserId
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The ML sync side of a sign-out, which both sign-out paths run first.
 *
 * A save or restore runs as [MlSyncWorker] in this process, on the account's Drive client, and writes the
 * account's face and search data, while the sign-out frees that client and wipes that data. So [stopFor]
 * closes the client to new calls, cancels the job and waits for the run to end, its hand-back included,
 * before the sign-out goes on. A run that outlives the wait is refused every further Drive call, and once it
 * ends it finds the account signing out and deletes what an import of its may have committed after the wipe.
 * [wipePlaintext] then deletes what an import or export leaves on disk in the clear.
 */
@Singleton
class MlSyncSignOut @Inject constructor(
    @ApplicationContext private val context: Context,
    private val clientProvider: SdkDriveClientProvider,
) {
    /** The ML sync runs executing in this process, at most one. */
    private val runs = MutableStateFlow<Set<Job>>(emptySet())

    /** Runs [block], an ML sync run, where [stopFor] can cancel it and wait for it to end. */
    suspend fun <T> track(block: suspend () -> T): T {
        val job = currentCoroutineContext()[Job] ?: return block()
        runs.update { it + job }
        try {
            return block()
        } finally {
            runs.update { it - job }
        }
    }

    /** Whether [userId] is signing out, or has signed out and not signed in again. */
    suspend fun isSigningOut(userId: UserId): Boolean = clientProvider.isClosed(userId)

    /**
     * Stops ML sync for [userId]'s sign-out: closes the account's Drive client to new calls, cancels the job,
     * and waits, at most [STOP_TIMEOUT_MS], for a run of it in this process to end. Returns whether none is
     * left running.
     */
    suspend fun stopFor(userId: UserId): Boolean {
        clientProvider.close(userId)
        // Cancelled in WorkManager first, so the job is not started again once its run ends.
        try {
            withTimeoutOrNull(CANCEL_TIMEOUT_MS) {
                val work = WorkManager.getInstance(context)
                work.cancelUniqueWork(MlSyncWorker.UNIQUE_WORK_NAME).await()
                work.cancelUniqueWork(LabelSyncWorker.UNIQUE_WORK_NAME).await()
                for (kind in eu.akoos.photos.data.autosave.AutoSaveKind.entries) {
                    work.cancelUniqueWork(eu.akoos.photos.data.autosave.AutoSaveCoordinator.uniqueName(kind)).await()
                }
                eu.akoos.photos.data.autosave.AutoSaveStore(context).clearAll()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "job not cancelled: ${e.javaClass.simpleName}")
        }
        runs.value.forEach { it.cancel() }
        val ended = withTimeoutOrNull(STOP_TIMEOUT_MS) { runs.first { it.isEmpty() } } != null
        if (!ended) Log.w(TAG, "run still ending after the sign-out's wait")
        return ended
    }

    /**
     * Deletes the plaintext ML sync leaves on disk ([MlSyncScratch]) and the names waiting to be put back on
     * detected faces, which a names-only import or a model change writes in the clear.
     */
    suspend fun wipePlaintext() {
        withContext(Dispatchers.IO) {
            MlSyncScratch.sweep(context.cacheDir)
            FaceReattachSnapshot.clear(FaceReattachSnapshot.file(File(context.filesDir, FaceModelAssets.DIRECTORY)))
        }
    }

    private companion object {
        const val TAG = "MlSyncSignOut"

        /** How long a sign-out waits for WorkManager to take the job's cancel. */
        const val CANCEL_TIMEOUT_MS = 5_000L

        /** How long a sign-out waits for a cancelled run to end before going on without it. */
        const val STOP_TIMEOUT_MS = 15_000L
    }
}

/**
 * Where ML sync plaintext passes through under the app cache: the face snapshot scratch, and the
 * [DIRECTORY] folder, which holds the search snapshot scratch and the files a run moves. Each run deletes
 * its own files; one killed part way leaves them behind, which [sweep] takes at app start and on sign-out.
 */
internal object MlSyncScratch {
    const val DIRECTORY = "mlsync"

    /** The folders swept, under [cacheDir]. */
    fun dirs(cacheDir: File): List<File> =
        listOf(File(cacheDir, FaceSnapshotScratch.DIRECTORY), File(cacheDir, DIRECTORY))

    /**
     * Deletes every file under [dirs] last written before [before] (epoch millis), or every file when it is
     * null. A file a run of this process is writing is newer than the process, so the start-up sweep passes
     * the process start and never takes it. Returns how many files went.
     */
    fun sweep(cacheDir: File, before: Long? = null): Int {
        var deleted = 0
        for (dir in dirs(cacheDir)) {
            dir.walkBottomUp()
                .filter { it.isFile && (before == null || it.lastModified() < before) }
                .forEach { if (it.delete()) deleted++ }
        }
        return deleted
    }
}
