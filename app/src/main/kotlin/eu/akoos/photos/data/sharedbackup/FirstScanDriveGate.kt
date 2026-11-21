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

package eu.akoos.photos.data.sharedbackup

import android.content.Context
import android.util.Log
import androidx.work.WorkInfo
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.akoos.photos.data.mlsync.MlDriveFileKind
import eu.akoos.photos.data.mlsync.MlDriveStore
import eu.akoos.photos.data.sdk.SdkGate
import eu.akoos.photos.di.AppScope
import eu.akoos.photos.util.SyncDiagnostics
import eu.akoos.photos.worker.MlSyncWorker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import me.proton.core.accountmanager.domain.AccountManager
import me.proton.core.domain.entity.UserId
import javax.inject.Inject
import javax.inject.Singleton

/** How a first scan's wait for the other devices' copies on Drive ended. Every outcome lets the scan start. */
internal enum class FirstScanWait {
    /** The backup is off, no account is signed in, or this device cannot reach Drive's copies at all. */
    NOT_APPLICABLE,

    /** Drive holds no copy of this kind that this device has not taken in. */
    NOTHING_PENDING,

    /** Drive could not be listed in time: the scan starts as it would without the backup. */
    UNREACHABLE,

    /** A round was asked for but did not start in time (no connection it may use, the job queue busy). */
    ROUND_NOT_STARTED,

    /** A round ran, though it outlasted the wait; the scan starts beside whatever it still does. */
    ROUND_TOO_LONG,

    /** A round ran to its end, so the copies it could read are in before the scan. */
    TOOK_IN,
}

/** What the first-scan wait needs from Drive and the shared backup, so the wait itself is tested without them. */
internal interface FirstScanDrive {
    /** The shared backup is on, an account is signed in and this device can run the Drive SDK. */
    suspend fun mayWait(): Boolean

    /** The kinds of ML copy on Drive a round would take in now, or null when Drive could not be listed. */
    suspend fun pendingKinds(): Set<MlDriveFileKind>?

    /** Asks for a round now on any connection; returns when it was asked for, in the time [awaitRoundEnd] reads. */
    suspend fun askRound(): Long

    /** Suspends until a round runs, or one asked at [askedAtMs] has ended already. */
    suspend fun awaitRoundStart(askedAtMs: Long)

    /** Suspends until a round asked at [askedAtMs] has ended, however it ended. */
    suspend fun awaitRoundEnd(askedAtMs: Long)
}

/**
 * Before a first scan of [kind] on this device: when the shared backup is on and Drive holds another device's copy
 * of that kind not taken in yet, runs a round and waits for it, so the photos that device scanned are not scanned
 * here again. Fails open: whatever goes wrong, or takes longer than its bound, ends the wait and the scan starts.
 * Only the round's start is bounded loosely; once it runs it gets [runTimeoutMs], so no walk races its import.
 */
internal suspend fun waitForDriveCopies(
    kind: MlDriveFileKind,
    drive: FirstScanDrive,
    listTimeoutMs: Long = FIRST_SCAN_LIST_TIMEOUT_MS,
    startTimeoutMs: Long = FIRST_SCAN_START_TIMEOUT_MS,
    runTimeoutMs: Long = FIRST_SCAN_RUN_TIMEOUT_MS,
): FirstScanWait {
    try {
        if (!drive.mayWait()) return FirstScanWait.NOT_APPLICABLE
        val pending = withTimeoutOrNull(listTimeoutMs) { drive.pendingKinds() } ?: return FirstScanWait.UNREACHABLE
        if (kind !in pending) return FirstScanWait.NOTHING_PENDING
        val askedAt = drive.askRound()
        withTimeoutOrNull(startTimeoutMs) { drive.awaitRoundStart(askedAt) } ?: return FirstScanWait.ROUND_NOT_STARTED
        withTimeoutOrNull(runTimeoutMs) { drive.awaitRoundEnd(askedAt) } ?: return FirstScanWait.ROUND_TOO_LONG
        return FirstScanWait.TOOK_IN
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "first scan does not wait for Drive: ${e.javaClass.simpleName}")
        return FirstScanWait.UNREACHABLE
    }
}

/**
 * Holds the first face or search scan this device runs until the other devices' copies of that kind on Drive are
 * taken in ([waitForDriveCopies]). While it holds a kind, the background kicks of that scan ([holds]) stand aside;
 * the scan the caller asked for starts as the wait ends, whatever its outcome. In memory only, so a process that
 * dies mid-wait comes back with nothing held.
 */
@Singleton
class FirstScanDriveGate @Inject constructor(
    @ApplicationContext private val context: Context,
    private val store: SharedBackupStore,
    private val accountManager: AccountManager,
    private val scheduler: dagger.Lazy<SharedBackupScheduler>,
    private val runner: dagger.Lazy<SharedBackupRunner>,
    private val driveStore: dagger.Lazy<MlDriveStore>,
    private val sdkGate: dagger.Lazy<SdkGate>,
    @AppScope private val appScope: CoroutineScope,
) {
    private val held = MutableStateFlow<Set<MlDriveFileKind>>(emptySet())

    /** Whether a first scan of [kind] waits for Drive's copies now, so another kick of that scan holds off. */
    fun holds(kind: MlDriveFileKind): Boolean = kind in held.value

    /**
     * Runs [start], the first scan of [kind] for [userId], once the copies on Drive are in. On the app's own scope,
     * so leaving the screen that asked does not drop the scan. A guest has no Drive, so it starts at once.
     */
    fun startFirstScan(kind: MlDriveFileKind, userId: UserId?, start: suspend () -> Unit) {
        appScope.launch {
            if (userId != null) {
                held.update { it + kind }
                try {
                    val outcome = waitForDriveCopies(kind, DriveSide(userId))
                    Log.i(TAG, "first ${kind.prefix} scan: $outcome")
                    SyncDiagnostics.log("first ${kind.prefix} scan waited for Drive: ${outcome.name.lowercase()}")
                } finally {
                    held.update { it - kind }
                }
            }
            runCatching { start() }.onFailure { if (it is CancellationException) throw it }
        }
    }

    private inner class DriveSide(private val userId: UserId) : FirstScanDrive {
        private val workManager: WorkManager
            get() = WorkManager.getInstance(context)

        override suspend fun mayWait(): Boolean =
            store.isEnabled() && accountManager.getPrimaryUserId().first() == userId && sdkGate.get().isCapable

        // Read under the Drive job lock, as a round reads, so a round running now ends before the listing.
        override suspend fun pendingKinds(): Set<MlDriveFileKind>? = DriveJobLock.mutex.withLock {
            val listing = runner.get().list(userId) ?: return@withLock null
            val readsAs = if (store.ownCopyTaken()) driveStore.get().deviceId() else MlSyncWorker.EARLIER_INSTALL
            runner.get().mlFilesToTake(
                userId,
                listing,
                readsAs,
                facesWiped = store.facesWipedAt() > 0L,
                seen = runCatching { store.seen() }.getOrDefault(emptyMap()),
            ).mapTo(HashSet()) { it.kind }
        }

        override suspend fun askRound(): Long {
            val askedAt = System.currentTimeMillis()
            scheduler.get().syncNow(userAsked = true)
            return askedAt
        }

        override suspend fun awaitRoundStart(askedAtMs: Long) {
            merge(
                workManager.getWorkInfosForUniqueWorkFlow(MlSyncWorker.UNIQUE_WORK_NAME)
                    .map { infos -> infos.any { it.state == WorkInfo.State.RUNNING } },
                store.status.map { it.lastAtMs >= askedAtMs },
            ).first { it }
        }

        override suspend fun awaitRoundEnd(askedAtMs: Long) {
            store.status.first { it.lastAtMs >= askedAtMs }
        }
    }
}

/** Listing the backup folders, the Drive job lock's wait included, takes at most this long. */
internal const val FIRST_SCAN_LIST_TIMEOUT_MS = 2 * 60_000L

/** A round asked for on any connection starts within this, or the scan goes ahead without it. */
internal const val FIRST_SCAN_START_TIMEOUT_MS = 2 * 60_000L

/** A running round gets this long to take a large library's copies in before the scan starts beside it. */
internal const val FIRST_SCAN_RUN_TIMEOUT_MS = 30 * 60_000L

private const val TAG = "FirstScanDriveGate"
