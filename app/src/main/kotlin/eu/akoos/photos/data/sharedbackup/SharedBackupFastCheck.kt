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

import android.util.Log
import eu.akoos.photos.data.mlsync.DriveDataFolder
import eu.akoos.photos.data.mlsync.MlDriveFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** While the app is in the foreground, the backup folders are looked at this often for another device's newer copy. */
internal const val CHECK_EVERY_MS = 2L * 60_000L

/** Two looks at the backup folders, the one at start or on coming back to the app included, are at least this far apart. */
internal const val CHECK_AT_MOST_EVERY_MS = 60_000L

/** How long after [nowMs] the next look at the backup folders is due: [CHECK_EVERY_MS] after the last look or round
 *  asked for or ended ([lastMs]), whichever came later. Zero or less when it is due. */
internal fun nextCheckInMs(nowMs: Long, lastMs: Long): Long = lastMs + CHECK_EVERY_MS - nowMs

/** Whether a look at the backup folders at [nowMs] comes too soon after the one at [lastCheckMs] (0 when none yet). */
internal fun checkTooSoon(nowMs: Long, lastCheckMs: Long): Boolean =
    lastCheckMs > 0L && nowMs >= lastCheckMs && nowMs - lastCheckMs < CHECK_AT_MOST_EVERY_MS

/**
 * Whether the round runs at once as the app leaves the foreground: a change the user made waits for it
 * ([firstWaitingChangeMs] set), and no failed round's wait ([SharedBackupScheduler.SAVE_AFTER_MS] after it ended at
 * [lastAtMs]) is still running.
 */
internal fun flushOnBackground(firstWaitingChangeMs: Long, lastFailed: Boolean, lastAtMs: Long, nowMs: Long): Boolean =
    firstWaitingChangeMs > 0L && !failedRoundWaiting(lastFailed, lastAtMs, nowMs)

private fun failedRoundWaiting(lastFailed: Boolean, lastAtMs: Long, nowMs: Long): Boolean =
    lastFailed && nowMs - lastAtMs < SharedBackupScheduler.SAVE_AFTER_MS

/** What a look at the backup folders came to: not made, made with nothing new, or a round asked for. */
internal enum class CheckOutcome { SKIPPED, NOTHING_NEW, ROUND }

/** What decides whether the backup folders may be looked at now. */
internal data class CheckConditions(
    val enabled: Boolean,
    /** An account is signed in: false for a guest and when signed out. */
    val signedIn: Boolean,
    val signingOut: Boolean,
    val online: Boolean,
    /** The round may use the connection there is: any one, or an unmetered one when backups keep to those. */
    val networkAllowed: Boolean,
    /** The last round failed: the round after it is already planned, [SharedBackupScheduler.SAVE_AFTER_MS] later. */
    val lastFailed: Boolean,
)

/** Whether the backup folders are looked at: only with the backup on, an account signed in, and a usable connection. */
internal fun checkAllowed(c: CheckConditions): Boolean =
    c.enabled && c.signedIn && !c.signingOut && c.online && c.networkAllowed && !c.lastFailed

/** The listing's files by the folder they came from, so each is read by its folder's naming. */
internal fun SharedBackupListing.byFolder(): Map<DriveDataFolder, List<MlDriveFile>> = mapOf(
    DriveDataFolder.ML to ml,
    DriveDataFolder.LABELS to labels,
    DriveDataFolder.HIDDEN to hidden,
    DriveDataFolder.SETTINGS to settings,
    DriveDataFolder.ALBUM_ORDER to albumOrder,
)

/** What a round saw of the backup folders: each file's node and its active revision. */
internal fun seenRevisions(listing: SharedBackupListing): Map<String, String> =
    listing.byFolder().values.flatten().associate { it.nodeUid to it.activeRevisionUid }

/**
 * The other devices' files in [listing] that are new since a round last saw the folders ([seen], node to active
 * revision): a file not seen before, or one with another revision. This install's own files count only while the
 * copies named after it were not taken in yet ([ownCopyTaken] false, after a fresh install or a sign-out), and a
 * file whose name no install writes never does. Pure.
 */
internal fun newerFiles(
    listing: SharedBackupListing,
    ownDeviceId: String,
    ownCopyTaken: Boolean,
    seen: Map<String, String>,
): Map<DriveDataFolder, List<MlDriveFile>> =
    listing.byFolder()
        .mapValues { (folder, files) ->
            files.filter { file ->
                val deviceId = folder.deviceIdOf(file.name) ?: return@filter false
                (deviceId != ownDeviceId || !ownCopyTaken) && seen[file.nodeUid] != file.activeRevisionUid
            }
        }
        .filterValues { it.isNotEmpty() }

/**
 * Runs [check] at once and then whenever the next one is due ([untilNextMs], see [nextCheckInMs]) while the app is in
 * the foreground ([foreground]), and stops when it leaves ([background]). A check that did not look is tried again
 * [CHECK_EVERY_MS] later. The check keeps its own [CHECK_AT_MOST_EVERY_MS] gap.
 */
internal class ForegroundCheckLoop(
    private val scope: CoroutineScope,
    private val untilNextMs: () -> Long = { 0L },
    private val check: suspend () -> Unit,
) {
    private var job: Job? = null

    val running: Boolean
        get() = job?.isActive == true

    @Synchronized
    fun foreground() {
        if (job?.isActive == true) return
        job = scope.launch {
            runCheck()
            while (isActive) {
                delay(untilNextMs().takeIf { it > 0L } ?: CHECK_EVERY_MS)
                if (untilNextMs() <= 0L) runCheck()
            }
        }
    }

    private suspend fun runCheck() {
        try {
            check()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "check failed: ${e.javaClass.simpleName}")
        }
    }

    @Synchronized
    fun background() {
        job?.cancel()
        job = null
    }

    private companion object {
        const val TAG = "SharedBackup"
    }
}
