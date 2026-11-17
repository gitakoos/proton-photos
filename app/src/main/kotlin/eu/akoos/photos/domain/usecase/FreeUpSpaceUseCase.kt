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

package eu.akoos.photos.domain.usecase

import android.app.PendingIntent
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.proton.core.domain.entity.UserId
import eu.akoos.photos.data.api.dto.BatchLinkDto
import eu.akoos.photos.data.repository.drive.LinkDetailHelpers
import eu.akoos.photos.domain.entity.SyncState
import eu.akoos.photos.domain.entity.SyncStatus
import eu.akoos.photos.domain.repository.DrivePhotoRepository
import eu.akoos.photos.domain.repository.FreeUpUncheckedStore
import eu.akoos.photos.domain.repository.LocalMediaRepository
import eu.akoos.photos.domain.repository.SyncStateRepository
import eu.akoos.photos.data.preferences.ShortCloudCopyStoreImpl
import eu.akoos.photos.domain.repository.ShortCloudCopyStore
import javax.inject.Inject

class FreeUpSpaceUseCase @Inject constructor(
    @ApplicationContext private val context: Context,
    private val syncStateRepo: SyncStateRepository,
    private val cloudRepo: DrivePhotoRepository,
    private val linkDetailHelpers: LinkDetailHelpers,
    private val localRepo: LocalMediaRepository,
    private val uncheckedStore: FreeUpUncheckedStore,
    private val shortCopies: ShortCloudCopyStore = ShortCloudCopyStoreImpl(context),
) {
    /** What a device copy is, measured against the backup it is about to be deleted on. */
    enum class DeviceCopyCheck {
        /** The bytes are the ones backed up, or are on Drive as they are: safe to delete. */
        UNCHANGED,

        /** The file changed since its backup: kept, and queued to be backed up again. */
        CHANGED,

        /**
         * Drive's copy holds fewer bytes than the device file it claims to be: kept, the Drive copy recorded
         * as short ([ShortCloudCopyStore]) so nothing pairs with it again, and the file queued to be backed up
         * again whole.
         */
        SHORT_ON_DRIVE,

        /** The file could not be read just now: kept as it is, and checked again next run. */
        UNREADABLE,

        /**
         * The file was read, but nothing tells whether it still holds what was backed up: kept, left
         * out of the free-up list until the file changes ([FreeUpUncheckedStore]), and not backed up
         * again, since that could put a second copy of the same photo on Drive.
         */
        UNCHECKED,
    }

    sealed class FreeUpResult {
        /** [skipped] counts device copies this app could not delete itself on Android 10 and older,
         *  where there is no system dialog to ask for them. [unchecked] counts the copies kept because
         *  they could not be checked against their backup ([DeviceCopyCheck.UNREADABLE],
         *  [DeviceCopyCheck.UNCHECKED]). */
        data class Done(val freed: Int, val skipped: Int = 0, val unchecked: Int = 0) : FreeUpResult()

        /** Android 11+: the system delete dialog must be shown for [localUris], at most
         *  [DELETE_REQUEST_MAX_URIS] of them. [remaining] are the confirmed copies still waiting for a
         *  dialog of their own, asked for through [consentFor] once this one is answered. [unchecked]
         *  is as in [Done], for the copies checked so far. */
        data class NeedsPermission(
            val pendingIntent: PendingIntent,
            val localUris: List<String>,
            val remaining: List<SyncState> = emptyList(),
            val unchecked: Int = 0,
        ) : FreeUpResult()
    }

    /** The rows a device check let through for deletion, and how many it kept unchecked. */
    private class Checked(val deletable: List<SyncState>, val unchecked: Int)

    /**
     * Exactly the rows [invoke] would act on, for a caller that has to show the user what is about to
     * leave their device before it does. Sharing one query is the point: a list drawn from a second,
     * similar-looking filter could name a photo the sweep spares, or spare one the sweep takes, and
     * the screen would be a promise about a different set than the one that runs.
     *
     * Every row is `SYNCED`, so every photo in it has a Drive copy. That is what makes the backed-up
     * badge on each cell a check the user can make rather than a decoration.
     *
     * A copy an earlier run kept unchecked ([DeviceCopyCheck.UNCHECKED]) stays out while its file is
     * as it was then; one written since comes back to be checked again.
     */
    suspend fun candidates(
        userId: UserId,
        olderThanMs: Long,
        protectDownloaded: Boolean = false,
    ): List<SyncState> = withContext(Dispatchers.IO) {
        withoutUnchecked(
            syncStateRepo.getSyncedBefore(userId, olderThanMs)
                .filter { isEligibleForReclamation(it, olderThanMs, protectDownloaded) },
        )
    }

    /** What of [row] its UNCHECKED verdict was read from, so a row backed up or recorded again is checked again. */
    private fun uncheckedRowKey(row: SyncState): String =
        listOf(row.sizeBytes, row.localHash, row.originalHash, row.cloudFileId, row.backedUpAtMs)
            .joinToString("/").hashCode().toUInt().toString(16)

    private suspend fun withoutUnchecked(rows: List<SyncState>): List<SyncState> {
        val kept = uncheckedStore.all()
        if (kept.isEmpty()) return rows
        val changed = mutableListOf<String>()
        val result = rows.filter { row ->
            val mark = kept[row.localUri] ?: return@filter true
            if (mark.rowKey == uncheckedRowKey(row) && localRepo.fileStamp(row.localUri) == mark.stamp) {
                false
            } else {
                changed += row.localUri
                true
            }
        }
        uncheckedStore.unmark(changed)
        return result
    }

    /**
     * The subset of [candidates] whose Drive copy is confirmed active right now, so only those get
     * deleted. Each candidate's cloudFileId is looked up in one batched link-details call (chunked at
     * 50), and a candidate is kept only when its link comes back State == 1: present on Drive and not
     * trashed. Checking the candidates alone is bounded by the on-device count, where the listing walk
     * this replaced grew with the whole Drive volume and on a large library ran long enough that the
     * sweep never started.
     *
     * A candidate is DROPPED (kept on the device, never deleted) when its link is absent from the
     * returned map, comes back at any State other than 1, or carries no cloudFileId to check with. A
     * transient failure (429 / 5xx / network) from the lookup PROPAGATES so the caller aborts and
     * deletes nothing against an unconfirmed batch, rather than reading a rate-limited short map as an
     * all-trashed answer. So does any other failed batch apart from a server answer that the link
     * does not exist, which only drops that link.
     */
    suspend fun verifyActiveBackups(
        userId: UserId,
        candidates: List<SyncState>,
    ): List<SyncState> = withContext(Dispatchers.IO) {
        if (candidates.none { !it.cloudFileId.isNullOrBlank() }) return@withContext emptyList<SyncState>()
        confirmActive(userId, cloudRepo.getVolumeId(userId), candidates).map { it.first }
    }

    /**
     * The rows of [rows] whose device copy may be deleted right now: the Drive link is active
     * ([verifyActiveBackups]) and the device file still holds what was backed up ([checkDeviceCopy]).
     * A file changed since its backup is kept and queued to be backed up again; one that cannot be
     * measured is kept. A failed Drive lookup throws, as in [verifyActiveBackups].
     */
    suspend fun confirmForDeletion(userId: UserId, rows: List<SyncState>): List<SyncState> =
        withContext(Dispatchers.IO) {
            checkedForDeletion(userId, rows).deletable
        }

    private suspend fun checkedForDeletion(userId: UserId, rows: List<SyncState>): Checked {
        if (rows.none { !it.cloudFileId.isNullOrBlank() }) return Checked(emptyList(), 0)
        return confirmUnchanged(userId, confirmActive(userId, cloudRepo.getVolumeId(userId), rows))
    }

    private suspend fun confirmActive(
        userId: UserId,
        volumeId: String,
        rows: List<SyncState>,
    ): List<Pair<SyncState, BatchLinkDto>> {
        val checkable = rows.filter { !it.cloudFileId.isNullOrBlank() }
        if (checkable.isEmpty()) return emptyList()
        val linkIds = checkable.mapNotNull { it.cloudFileId }.distinct()
        val details = linkDetailHelpers.batchFetchLinkDetails(userId, volumeId, linkIds, strict = true)
        return checkable.mapNotNull { row ->
            val link = row.cloudFileId?.let { details[it] } ?: return@mapNotNull null
            if (link.link.state == ACTIVE_LINK_STATE) row to link else null
        }
    }

    /** The rows of [confirmed] whose device file still holds what was backed up; see [checkDeviceCopy]. */
    private suspend fun confirmUnchanged(
        userId: UserId,
        confirmed: List<Pair<SyncState, BatchLinkDto>>,
    ): Checked {
        var unchecked = 0
        val shortIds = if (confirmed.isEmpty()) emptySet() else shortCopies.all()
        // Until reconcile has looked for the photo editor's short copies, each row is looked at here.
        val editorLookSince = if (confirmed.isEmpty() || shortCopies.editorCopiesChecked(userId.id)) {
            null
        } else {
            shortCopies.editorCopiesCheckStartedMs(userId.id, System.currentTimeMillis())
        }
        val deletable = confirmed.mapNotNull { (row, link) ->
            when (checkDeviceCopy(userId, row, link, shortIds, editorLookSince)) {
                DeviceCopyCheck.UNCHANGED -> row
                DeviceCopyCheck.UNREADABLE, DeviceCopyCheck.UNCHECKED -> {
                    unchecked++
                    null
                }
                DeviceCopyCheck.CHANGED, DeviceCopyCheck.SHORT_ON_DRIVE -> null
            }
        }
        return Checked(deletable, unchecked)
    }

    /**
     * Measures [row]'s device file against its backup just before it is deleted. Reconcile notices a
     * file another app edited in place only by its size, and only for so many files a pass, so a row
     * can read backed up while the device holds an edit Drive never saw. Here the file is read in full;
     * [deviceCopyVerdict] decides. A CHANGED file is queued to be backed up again as a photo of its own
     * (the Drive copy stays as it is); an UNCHECKED one is recorded with its file's stamp so the list
     * leaves it out until the file changes.
     *
     * Drive's content hash is what the uploading app said the bytes hash to, so a match alone does not
     * prove Drive holds them: a copy recorded as short ([shortIds]), or one whose stored size is below the
     * device file's while its hash claims those very bytes, is SHORT_ON_DRIVE. It is recorded as short and
     * its file queued to be backed up again whole. So is a copy the photo editor may have cut short
     * ([maybeShortEditorCopy]) while reconcile's look for them has not run ([editorLookSince] set); a file
     * that cannot be looked up then is kept unchecked.
     */
    private suspend fun checkDeviceCopy(
        userId: UserId,
        row: SyncState,
        link: BatchLinkDto,
        shortIds: Set<String>,
        editorLookSince: Long?,
    ): DeviceCopyCheck {
        val stamp = localRepo.fileStamp(row.localUri) ?: return DeviceCopyCheck.UNREADABLE
        val sha1 = localRepo.sha1(row.localUri) ?: return DeviceCopyCheck.UNREADABLE
        val driveHash = link.photo?.contentHash
        val onDrive = !driveHash.isNullOrEmpty() && cloudRepo.cloudContentHash(sha1) == driveHash
        val editorSuspect = if (editorLookSince == null) {
            false
        } else {
            val file = localRepo.queryByUri(row.localUri) ?: return DeviceCopyCheck.UNREADABLE
            maybeShortEditorCopy(row, file.displayName, file.mimeType, editorLookSince)
        }
        val driveShort = editorSuspect || row.cloudFileId in shortIds ||
            (onDrive && driveCopyShortOf(link.link.size, stamp.sizeBytes))
        val verdict = deviceCopyVerdict(row, stamp.sizeBytes, stamp.modifiedSec, sha1, onDrive, driveHash, driveShort)
        try {
            when (verdict) {
                DeviceCopyCheck.CHANGED ->
                    syncStateRepo.requeueChangedDeviceCopy(userId, row, sha1, stamp.sizeBytes)
                DeviceCopyCheck.SHORT_ON_DRIVE -> {
                    row.cloudFileId?.let { shortCopies.add(listOf(it)) }
                    syncStateRepo.requeueChangedDeviceCopy(userId, row, sha1, stamp.sizeBytes)
                }
                DeviceCopyCheck.UNCHECKED ->
                    uncheckedStore.mark(row.localUri, FreeUpUncheckedStore.Kept(stamp, uncheckedRowKey(row)))
                DeviceCopyCheck.UNCHANGED, DeviceCopyCheck.UNREADABLE -> Unit
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            // The file stays on the device either way; the next run looks at it again.
        }
        return verdict
    }

    /**
     * [protectDownloaded] keeps copies the user put on the device on purpose (a download, or a delete
     * they undid) out of the sweep. The automatic schedule passes true; the manual "free up space"
     * button leaves it false, so a deliberate tap still reclaims every backed-up copy as before.
     *
     * [onProgress] is called with (done, total) as the sweep advances, so a caller can show movement
     * over a run that takes minutes on a large library. It is called from the IO context this runs on.
     *
     * Selects the candidates and reclaims them in one call, with no cloud check. A caller that deletes
     * on a schedule uses [sweepConfirmed]; the manual button runs [verifyAndReclaim] itself.
     */
    suspend operator fun invoke(
        userId: UserId,
        olderThanMs: Long,
        protectDownloaded: Boolean = false,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): FreeUpResult =
        reclaimCandidates(candidates(userId, olderThanMs, protectDownloaded), onProgress)

    /**
     * The scheduled sweep: [candidates], narrowed to the rows [verifyActiveBackups] confirms against
     * Drive right now, then reclaimed.
     *
     * The sync state alone is not enough to delete on. Reconcile stamps the cloud state as checked
     * whether or not a listing walk ran before it, which a Wi-Fi-only backup on mobile data skips, so
     * a photo already gone from Drive can still read SYNCED, and its device copy would be the last
     * one. A lookup that fails throws, so a batch that could not be confirmed deletes nothing.
     */
    suspend fun sweepConfirmed(
        userId: UserId,
        olderThanMs: Long,
        protectDownloaded: Boolean = false,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): FreeUpResult = verifyAndReclaim(userId, candidates(userId, olderThanMs, protectDownloaded), onProgress)

    /**
     * Confirms [candidates] against Drive and deletes them a slice of [VERIFY_CHUNK] at a time, each
     * slice right after its own check, so no copy is deleted on an answer minutes old. A slice whose
     * check throws stops the run there: what earlier slices freed stays freed, nothing past it is
     * touched. Copies this app may not delete itself are gathered for the system dialog
     * ([consentFor]), checked again just before it.
     */
    suspend fun verifyAndReclaim(
        userId: UserId,
        candidates: List<SyncState>,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): FreeUpResult = withContext(Dispatchers.IO) {
        val total = candidates.size
        onProgress(0, total)
        if (candidates.isEmpty()) return@withContext FreeUpResult.Done(0)
        val volumeId = cloudRepo.getVolumeId(userId)
        var freed = 0
        var unchecked = 0
        val needsDialog = mutableListOf<SyncState>()
        var done = 0
        for (slice in candidates.chunked(VERIFY_CHUNK)) {
            val checked = confirmUnchanged(userId, confirmActive(userId, volumeId, slice))
            unchecked += checked.unchecked
            for (row in checked.deletable) {
                if (deleteDeviceCopy(row)) freed++ else needsDialog += row
            }
            done += slice.size
            onProgress(done, total)
        }
        consentFor(userId, needsDialog, freed, deviceChecked = true, unchecked = unchecked)
    }

    /**
     * The system delete dialog for [rows], which this app could not delete itself: checked against
     * Drive once more, since the dialog can come long after the sweep, and capped at
     * [DELETE_REQUEST_MAX_URIS] per dialog, the most one request may carry. [freed] is what the
     * caller already reclaimed, reported back when no dialog is needed. Below Android 11 there is no
     * dialog, so the rows are reported as skipped and stay on the device.
     *
     * Each device file in the dialog is measured again too ([confirmForDeletion]), unless
     * [deviceChecked] says the caller measured these rows a moment ago. The rows handed back for a
     * later dialog are measured when that dialog comes. [unchecked] is what the caller already kept
     * unchecked, reported back together with what this check keeps.
     */
    suspend fun consentFor(
        userId: UserId,
        rows: List<SyncState>,
        freed: Int = 0,
        deviceChecked: Boolean = false,
        unchecked: Int = 0,
    ): FreeUpResult =
        withContext(Dispatchers.IO) {
            if (rows.isEmpty()) return@withContext FreeUpResult.Done(freed, unchecked = unchecked)
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                return@withContext FreeUpResult.Done(freed, skipped = rows.size, unchecked = unchecked)
            }
            var kept = unchecked
            var batch: List<SyncState> = emptyList()
            var rest = rows
            // A dialog's worth that comes back empty after the checks moves on to the next one.
            while (batch.isEmpty() && rest.isNotEmpty()) {
                val (first, later) = splitForConsent(rest)
                batch = if (deviceChecked) {
                    verifyActiveBackups(userId, first)
                } else {
                    checkedForDeletion(userId, first).also { kept += it.unchecked }.deletable
                }
                rest = later
            }
            if (batch.isEmpty()) return@withContext FreeUpResult.Done(freed, unchecked = kept)
            // Building the consent request can throw on some OEMs for a foreign batch; fall through to
            // Done rather than letting it escape uncaught.
            val pi = runCatching {
                MediaStore.createDeleteRequest(context.contentResolver, batch.map { Uri.parse(it.localUri) })
            }.getOrNull() ?: return@withContext FreeUpResult.Done(freed, unchecked = kept)
            FreeUpResult.NeedsPermission(pi, batch.map { it.localUri }, rest, kept)
        }

    /** Deletes [state]'s device copy and marks its row CLOUD_ONLY; false when the system has to ask. */
    private suspend fun deleteDeviceCopy(state: SyncState): Boolean = try {
        if (context.contentResolver.delete(Uri.parse(state.localUri), null, null) > 0) {
            syncStateRepo.updateStatusAndDeleteLocal(state.localUri, SyncStatus.CLOUD_ONLY)
            true
        } else {
            // delete() returned 0, likely needs permission on API 30+
            false
        }
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        // SecurityException / RecoverableSecurityException on Android 11+
        false
    }

    /**
     * Deletes the device copy of each row in [candidates], in order, committing each on its own, and
     * reports how many were freed (or the batched consent request for files this app does not own).
     * The caller states the exact set and has checked it already, if at all: [invoke] passes what
     * [candidates] selected, with no cloud check. [verifyAndReclaim] is the checked path.
     *
     * The body runs on [Dispatchers.IO] rather than the caller's context. `ContentResolver.delete` is
     * a blocking binder call and there is one per photo, so on a library of thousands the loop owns
     * whatever thread it is given for minutes. Called from a ViewModel that is exactly the main
     * thread: the deletes still land, while the UI cannot repaint, which reads as a spinner that
     * never stops. The scheduled run never showed it because a CoroutineWorker is already off-main.
     */
    suspend fun reclaimCandidates(
        candidates: List<SyncState>,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): FreeUpResult = withContext(Dispatchers.IO) {
        var freed = 0
        val needsDialog = mutableListOf<SyncState>()
        val total = candidates.size
        onProgress(0, total)

        for ((index, state) in candidates.withIndex()) {
            if (deleteDeviceCopy(state)) freed++ else needsDialog += state
            // Each photo is committed on its own, so a run the user walks away from keeps everything
            // it already reclaimed; the next open lists what is left and picks up from there.
            val done = index + 1
            if (done % PROGRESS_STEP == 0 || done == total) onProgress(done, total)
        }

        if (needsDialog.isEmpty()) return@withContext FreeUpResult.Done(freed)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return@withContext FreeUpResult.Done(freed, skipped = needsDialog.size)
        }
        val (batch, rest) = splitForConsent(needsDialog)
        val pi = runCatching {
            MediaStore.createDeleteRequest(context.contentResolver, batch.map { Uri.parse(it.localUri) })
        }.getOrNull() ?: return@withContext FreeUpResult.Done(freed)
        FreeUpResult.NeedsPermission(pi, batch.map { it.localUri }, rest)
    }

    companion object {
        /** How many photos pass between progress reports. One report per photo would repaint the
         *  screen thousands of times for a number the user reads as it moves. */
        private const val PROGRESS_STEP = 20

        /** Link.State the batch link-details endpoint returns for a link that exists and is not
         *  trashed. Any other value (or an absent link) means the cloud copy is not confirmed. */
        private const val ACTIVE_LINK_STATE = 1

        /** Candidates checked against Drive at a time, each slice deleted right after its check. */
        const val VERIFY_CHUNK = 50

        /** The most URIs one system delete request may carry (Android caps it); a larger set is asked
         *  for in several dialogs. */
        const val DELETE_REQUEST_MAX_URIS = 2_000

        /** [rows] split into the first system dialog's worth, in order, and the rest. Pure. */
        fun <T> splitForConsent(rows: List<T>, max: Int = DELETE_REQUEST_MAX_URIS): Pair<List<T>, List<T>> =
            rows.take(max) to rows.drop(max)

        /** DATE_MODIFIED is in whole seconds while the backup stamp is in milliseconds. */
        private const val MODIFIED_SLACK_MS = 2_000L

        /** How long before its row was linked a download's copy may have been written: the save and the
         *  link run back to back, a large video's copy included. */
        private const val LINKED_WRITE_WINDOW_MS = 10 * 60_000L

        /**
         * Whether a device copy still holds what was backed up, from what was measured of it: its size
         * [deviceSizeBytes] and DATE_MODIFIED [deviceModifiedSec] (0 when the device keeps none), its
         * SHA-1 [deviceSha1] (null when it could not be read), whether those bytes are on Drive as they
         * are [deviceOnDrive], and the Drive copy's content hash [driveHash]. Pure.
         *
         * A wrong CHANGED puts a second copy of the photo on Drive and a wrong UNCHANGED deletes the only
         * copy of an edit, so CHANGED needs evidence of a write, and a copy that cannot be told apart is
         * UNCHECKED: kept, and neither deleted nor backed up again.
         *
         * In order:
         * - bytes that could not be read are UNREADABLE;
         * - a Drive copy that holds fewer bytes than the device file it claims to be ([driveShort]) is
         *   SHORT_ON_DRIVE, whatever the hashes say: the device copy may be the only whole one;
         * - bytes Drive holds as they are are UNCHANGED, and so are bytes with the hash the row recorded,
         *   on a row from before device bytes were recorded only when written as the row was stamped;
         * - a Drive copy whose content hash is no longer the one paired is UNCHECKED: the device copy may
         *   be the only one of what was backed up;
         * - a row that recorded what the device file held ([SyncState.originalHash]: as the backup read
         *   it, or as this app last wrote it itself) is decided by that alone;
         * - otherwise the row's hash may be that of rewritten upload bytes, so size and modified time
         *   decide: a file written after the backup is CHANGED. At a size other than the recorded one it is
         *   CHANGED for an uploaded row, which records the device size, and UNCHECKED for a download, whose
         *   row recorded the Drive size before the app stamped a date or place into the saved copy. At the
         *   recorded size, written before the backup, it is UNCHANGED, except that a download row with no
         *   recorded bytes is UNCHANGED only when its file was written just before the row was linked: an
         *   older version linked a gallery file it found by name and size alone, which can be a different
         *   photo. No size to compare, or no modified time to tell the same size apart by, is UNCHECKED.
         */
        fun deviceCopyVerdict(
            row: SyncState,
            deviceSizeBytes: Long,
            deviceModifiedSec: Long,
            deviceSha1: String?,
            deviceOnDrive: Boolean,
            driveHash: String?,
            driveShort: Boolean = false,
        ): DeviceCopyCheck {
            if (deviceSha1 == null) return DeviceCopyCheck.UNREADABLE
            if (driveShort) return DeviceCopyCheck.SHORT_ON_DRIVE
            if (deviceOnDrive) return DeviceCopyCheck.UNCHANGED
            val pairedHash = row.cloudHash
            if (!pairedHash.isNullOrEmpty() && !driveHash.isNullOrEmpty() && pairedHash != driveHash) {
                return DeviceCopyCheck.UNCHECKED
            }
            if (row.localHash.isNotEmpty() && deviceSha1.equals(row.localHash, ignoreCase = true)) {
                if (!row.originalHash.isNullOrEmpty()) return DeviceCopyCheck.UNCHANGED
                // Rows from before device bytes were recorded: an older version adopted the file's own
                // hash for a download row, also for a file it had linked by name and size alone, so the
                // match counts only for a file written as its row was stamped.
                val stampedAt = row.backedUpAtMs ?: return DeviceCopyCheck.UNCHECKED
                return if (deviceModifiedSec > 0L && deviceModifiedSec * 1000L >= stampedAt - LINKED_WRITE_WINDOW_MS) {
                    DeviceCopyCheck.UNCHANGED
                } else {
                    DeviceCopyCheck.UNCHECKED
                }
            }
            val originalHash = row.originalHash
            if (!originalHash.isNullOrEmpty()) {
                return if (deviceSha1.equals(originalHash, ignoreCase = true)) {
                    DeviceCopyCheck.UNCHANGED
                } else {
                    DeviceCopyCheck.CHANGED
                }
            }
            val backedUpAtMs = row.backedUpAtMs ?: return DeviceCopyCheck.UNCHECKED
            if (deviceModifiedSec > 0L && deviceModifiedSec * 1000L > backedUpAtMs + MODIFIED_SLACK_MS) {
                return DeviceCopyCheck.CHANGED
            }
            if (row.sizeBytes <= 0L || deviceSizeBytes <= 0L) return DeviceCopyCheck.UNCHECKED
            if (deviceSizeBytes != row.sizeBytes) {
                return if (row.localHash.isNotEmpty()) DeviceCopyCheck.CHANGED else DeviceCopyCheck.UNCHECKED
            }
            if (deviceModifiedSec <= 0L) return DeviceCopyCheck.UNCHECKED
            if (row.localHash.isEmpty() && deviceModifiedSec * 1000L < backedUpAtMs - LINKED_WRITE_WINDOW_MS) {
                return DeviceCopyCheck.UNCHECKED
            }
            return DeviceCopyCheck.UNCHANGED
        }

        /**
         * Whether free-up-space may reclaim the device copy behind [state]: true only for a photo
         * whose cloud copy is confirmed, i.e. a SYNCED row carrying a real backedUpAtMs stamp that
         * predates [olderThanMs]. Pure and side-effect-free so the reclamation gate can be pinned by
         * a plain JVM test.
         *
         * A LOCAL_ONLY / CLOUD_ONLY / UPLOADING / HIDDEN row, or a SYNCED row with a null backedUpAtMs
         * (only name/size-paired to a cloud photo, never actually uploaded), is never eligible:
         * deleting its device file could destroy the only copy of an un-backed-up original.
         *
         * [protectDownloaded] adds one more exclusion for the AUTOMATIC schedule only: a copy the user
         * put on the device on purpose. An upload computes a real content hash for the row; a photo
         * that instead came FROM the cloud onto the device carries an empty hash (a download, or a
         * delete the user just undid, which re-links it SYNCED). The background sweep leaves those be,
         * since silently removing a copy the user placed would fight the intent that placed it. The
         * manual button passes false, so a deliberate "free up space" tap still reclaims them as before.
         */
        fun isEligibleForReclamation(
            state: SyncState,
            olderThanMs: Long,
            protectDownloaded: Boolean = false,
        ): Boolean {
            val backedUpAtMs = state.backedUpAtMs
            return state.status == SyncStatus.SYNCED &&
                backedUpAtMs != null &&
                backedUpAtMs < olderThanMs &&
                (!protectDownloaded || state.localHash.isNotEmpty())
        }
    }
}
