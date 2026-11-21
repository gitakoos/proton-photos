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

package eu.akoos.photos.util

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.work.WorkManager
import coil.imageLoader
import eu.akoos.photos.data.importer.ImportScanCache
import eu.akoos.photos.data.localcrypto.VaultFiles
import eu.akoos.photos.data.preferences.SettingsKeys
import eu.akoos.photos.data.preferences.settingsDataStore
import eu.akoos.photos.data.preferences.settingsDataStoreStrict
import eu.akoos.photos.domain.usecase.wifiOnlySetting
import eu.akoos.photos.domain.usecase.StageImportUseCase
import eu.akoos.photos.service.BackgroundSyncService
import eu.akoos.photos.data.transfer.SaveBatchStore
import eu.akoos.photos.worker.AlbumDownloadWorker
import eu.akoos.photos.worker.CloudCompressWorker
import eu.akoos.photos.worker.ImportWorker
import eu.akoos.photos.worker.MetadataEditWorker
import eu.akoos.photos.worker.PhotoSaveWorker
import eu.akoos.photos.worker.SyncWorker
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first

/**
 * The parts of leaving an account that are plain files and scheduled work, in one place both ways out
 * of a session reach: the Settings sign-out and the handler a session ended by the server falls into.
 *
 * While these lived inside the Settings screen, only the deliberate sign-out ran them. A session the
 * server ended left the decrypted thumbnails, the full-resolution cache and the staged import
 * thumbnails on disk, and left the backup, import and metadata-edit work scheduled against an account
 * that was gone.
 */
object SignOutCleanup {

    /** The cache subdirectories that hold decrypted photo content of the account that is leaving. */
    val CACHE_DIRS: List<String> = listOf(
        "thumbnails",
        "fullres",
        "fullres-session",
        "editor",
        "video_editor",
        "motion",
        StageImportUseCase.THUMB_DIR,
        ImportScanCache.DIR_NAME,
        // The plaintext copies an upload, an import or an editor save works on.
        VaultFiles.PLAIN_STAGING_DIR,
    )

    /** Stop everything scheduled against the account: backup, album downloads, an import, queued
     *  metadata edits, saves to the device, the re-compression of Drive photos and the service that keeps
     *  the process alive. */
    fun stopBackgroundWork(context: Context, workManager: WorkManager) {
        runCatching {
            SyncWorker.cancel(workManager)
            workManager.cancelUniqueWork(SyncWorker.NAME_ONESHOT)
            workManager.cancelUniqueWork(SyncWorker.NAME_TRIGGERED)
            workManager.cancelUniqueWork(SyncWorker.NAME_CONTENT_OBSERVER)
            workManager.cancelAllWorkByTag(AlbumDownloadWorker.TAG)
            workManager.cancelAllWorkByTag(PhotoSaveWorker.TAG)
            workManager.cancelAllWorkByTag(ImportWorker.TAG)
            workManager.cancelUniqueWork(MetadataEditWorker.UNIQUE_NAME)
            workManager.cancelUniqueWork(CloudCompressWorker.UNIQUE_NAME)
            BackgroundSyncService.stop(context)
        }
    }

    /** Puts back what [stopBackgroundWork] stopped, for a sign-out that did not let the account go. */
    suspend fun restartBackgroundWork(context: Context) {
        runCatching { SyncWorker.reconcileBackgroundWork(context) }
        runCatching { MetadataEditWorker.enqueue(context) }
        runCatching {
            CloudCompressWorker.enqueue(context, wifiOnlySetting(context.settingsDataStore.data.first()[SettingsKeys.SYNC_WIFI_ONLY]))
        }
    }

    /**
     * The closing [steps] of a sign-out from Settings, then [letGo] of the account. A step that fails does not
     * stop the ones after it, since the cleanup every sign-out reaches runs again once the account is let go, so
     * the sign-out never stops half way. Answers whether the account was let go.
     */
    suspend fun closeUserSignOut(steps: List<suspend () -> Unit>, letGo: suspend () -> Unit): Boolean {
        for (step in steps) {
            try {
                step()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
            }
        }
        return try {
            letGo()
            true
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            false
        }
    }

    /** Delete the decrypted photo content cached on disk and in the image loader. Blocking file work,
     *  so a caller runs it off the main thread. */
    fun clearDecryptedCaches(context: Context) {
        CACHE_DIRS.forEach { File(context.cacheDir, it).deleteRecursively() }
        context.cacheDir.listFiles { f -> f.name.startsWith("album_dl_") }?.forEach { it.delete() }
        // The kept save-to-device batches list the account's photos by name.
        File(context.filesDir, SaveBatchStore.DIR).deleteRecursively()
        val loader = context.imageLoader
        loader.memoryCache?.clear()
        loader.diskCache?.clear()
    }

    /** Empty the diagnostics buffers and their saved copy, which would otherwise carry the account that is
     *  leaving into the log of whoever uses the app next. Blocking file work, so run off the main thread. */
    fun clearDiagnostics(context: Context) {
        SyncDiagnostics.clear()
        PerfDiagnostics.clear()
        ImportDiagnostics.clear()
        File(context.filesDir, "diagnostics").deleteRecursively()
    }

    private val running = ConcurrentHashMap.newKeySet<String>()
    private val userSignOutsRunning = ConcurrentHashMap.newKeySet<String>()
    private val unfinishedSignIns = ConcurrentHashMap.newKeySet<String>()

    /** Claims the cleanup of [userId] for this process; false while another run of it is still going. */
    fun claim(userId: String): Boolean = running.add(userId)

    fun release(userId: String) {
        running.remove(userId)
    }

    /** Notes that [userId] is being disabled because its sign-in gave up, so its cleanup is not
     *  replayed by a later launch: that one leaves the vault alone, and a replay could not tell. */
    fun noteUnfinishedSignIn(userId: String) {
        unfinishedSignIns += userId
    }

    fun consumeUnfinishedSignIn(userId: String): Boolean = unfinishedSignIns.remove(userId)

    /** Where the markers are written; a test puts a store here that refuses every write. */
    @androidx.annotation.VisibleForTesting
    internal var markerStoreOverride: DataStore<Preferences>? = null

    /** The markers go through the strict store: a marker that is not saved is reported, never taken as stored. */
    private fun markers(context: Context): DataStore<Preferences> =
        markerStoreOverride ?: context.settingsDataStoreStrict

    suspend fun markPending(context: Context, userId: String) {
        markers(context).edit { p ->
            p[SettingsKeys.SIGN_OUT_CLEANUP_PENDING] = p[SettingsKeys.SIGN_OUT_CLEANUP_PENDING].orEmpty() + userId
        }
    }

    /** Writes the cleanup marker of [userId]; false, without throwing, when it could not be saved. */
    suspend fun tryMarkPending(context: Context, userId: String): Boolean =
        try {
            markPending(context, userId)
            true
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            false
        }

    suspend fun clearPending(context: Context, userId: String) {
        markers(context).edit { p ->
            val left = p[SettingsKeys.SIGN_OUT_CLEANUP_PENDING].orEmpty() - userId
            if (left.isEmpty()) p.remove(SettingsKeys.SIGN_OUT_CLEANUP_PENDING)
            else p[SettingsKeys.SIGN_OUT_CLEANUP_PENDING] = left
        }
    }

    suspend fun pending(context: Context): Set<String> =
        markers(context).data.first()[SettingsKeys.SIGN_OUT_CLEANUP_PENDING].orEmpty()

    fun runningNow(): Set<String> = running.toSet()

    /** Claims the Settings sign-out of [userId] for this process; false while one is already running. */
    fun claimUserSignOut(userId: String): Boolean = userSignOutsRunning.add(userId)

    fun releaseUserSignOut(userId: String) {
        userSignOutsRunning.remove(userId)
    }

    fun userSignOutsRunningNow(): Set<String> = userSignOutsRunning.toSet()

    suspend fun markUserSignOutPending(context: Context, userId: String) {
        markers(context).edit { p ->
            p[SettingsKeys.USER_SIGN_OUT_PENDING] = p[SettingsKeys.USER_SIGN_OUT_PENDING].orEmpty() + userId
        }
    }

    suspend fun clearUserSignOutPending(context: Context, userId: String) {
        markers(context).edit { p ->
            val left = p[SettingsKeys.USER_SIGN_OUT_PENDING].orEmpty() - userId
            if (left.isEmpty()) p.remove(SettingsKeys.USER_SIGN_OUT_PENDING)
            else p[SettingsKeys.USER_SIGN_OUT_PENDING] = left
        }
    }

    suspend fun userSignOutsPending(context: Context): Set<String> =
        markers(context).data.first()[SettingsKeys.USER_SIGN_OUT_PENDING].orEmpty()

    /**
     * Runs the Settings sign-out of [userId] ([signOut]) behind its marker. The marker is written first and nothing
     * of the sign-out runs until it is stored, so a sign-out cut short always leaves the next launch something to
     * finish. A marker that cannot be saved starts nothing: everything stays as it was, [onNotStarted] tells the
     * user, and the answer is false. A marker that cannot be cleared at the end is dropped by the next launch,
     * which finds the account disabled. One sign-out of an account runs at a time; a second one answers false.
     */
    suspend fun startUserSignOut(
        context: Context,
        userId: String,
        onNotStarted: () -> Unit,
        signOut: suspend () -> Unit,
    ): Boolean {
        if (!claimUserSignOut(userId)) return false
        try {
            try {
                markUserSignOutPending(context, userId)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                runCatching { SyncDiagnostics.log("sign-out: by user, marker not saved, not started") }
                onNotStarted()
                return false
            }
            signOut()
            try {
                clearUserSignOutPending(context, userId)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
            }
            return true
        } finally {
            releaseUserSignOut(userId)
        }
    }

    /**
     * What a launch does with each Settings sign-out an earlier process started and never finished. The user
     * asked to leave, and part of the leaving (the vault, the caches) may already have happened, so an account
     * still enabled ([enabledUserIds]) is signed out now, leaving the rest of the vault as it is. An account that
     * is already disabled or gone only loses the marker: the cleanup every sign-out reaches has its own. One
     * still running in this process is left to it.
     */
    fun userSignOutReplayPlan(
        pending: Set<String>,
        enabledUserIds: Set<String>,
        running: Set<String>,
    ): Map<String, UserSignOutReplay> =
        pending.associateWith { userId ->
            when {
                userId in running -> UserSignOutReplay.SKIP
                userId in enabledUserIds -> UserSignOutReplay.FINISH
                else -> UserSignOutReplay.DROP
            }
        }

    /**
     * What a launch does with each cleanup an earlier process started and never finished. It runs again
     * only while nobody is signed in ([presentUserId] null), since most of it is not scoped to the
     * account that left and would land on whoever is there now; with anybody present, the same account
     * signed back in or another one, the entry is dropped. A cleanup still running here is left to it.
     */
    fun replayPlan(pending: Set<String>, presentUserId: String?, running: Set<String>): Map<String, SignOutReplay> =
        pending.associateWith { userId ->
            when {
                userId in running -> SignOutReplay.SKIP
                presentUserId == null -> SignOutReplay.RUN
                else -> SignOutReplay.DROP
            }
        }
}

enum class SignOutReplay { RUN, DROP, SKIP }

enum class UserSignOutReplay { FINISH, DROP, SKIP }
