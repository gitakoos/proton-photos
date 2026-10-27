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

import android.os.Build
import eu.akoos.photos.data.repository.drive.LinkDetailHelpers
import eu.akoos.photos.domain.entity.SyncState
import eu.akoos.photos.domain.entity.SyncStatus
import eu.akoos.photos.domain.repository.DrivePhotoRepository
import eu.akoos.photos.domain.repository.LocalMediaRepository
import eu.akoos.photos.domain.repository.SyncStateRepository
import eu.akoos.photos.util.isOfflineError
import eu.akoos.photos.util.isTransientApiError
import eu.akoos.photos.util.retryWithBackoff
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import me.proton.core.domain.entity.UserId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Free-up candidates and a single-pass chunked Drive-verify-and-delete reclaim pipeline.
 *
 * A [Mutex] serialises [runPipeline] / [tryRunPipeline] so manual and scheduled paths cannot
 * process the same rows concurrently: manual waits; scheduled uses try-lock and skips when busy.
 */
@Singleton
class FreeUpSpaceUseCase @Inject constructor(
    private val syncStateRepo: SyncStateRepository,
    private val cloudRepo: DrivePhotoRepository,
    private val linkDetailHelpers: LinkDetailHelpers,
    private val localMediaRepo: LocalMediaRepository,
) {
    private val mutex = Mutex()

    /** Progress phase for [runPipeline] (WorkInfo maps these to UI / notification copy). */
    enum class PipelinePhase {
        Verifying,
        Deleting,
    }

    /** Bounded progress for [runPipeline]: phase + counters only (never URI lists). */
    data class PipelineProgress(
        val phase: PipelinePhase,
        val processed: Int,
        val total: Int,
        val removed: Int = 0,
    )

    sealed class FreeUpResult {
        data class Done(
            val checked: Int,
            val freed: Int,
            /** Device copies that could not be deleted on API &lt; R (no consent dialog path). */
            val skipped: Int = 0,
        ) : FreeUpResult()

        /**
         * Android 11+: surviving local URIs still need a system delete dialog. The use case returns
         * **all** surviving URI strings; [MediaStore.createDeleteRequest] is owned by the consent
         * handler, not this domain API.
         */
        data class NeedsPermission(
            val checked: Int,
            val freed: Int,
            val localUris: List<String>,
        ) : FreeUpResult()
    }

    /**
     * Exactly the rows the reclaim pipeline would act on, for a caller that has to show the user
     * what is about to leave their device before it does. Sharing one query is the point: a list
     * drawn from a second, similar-looking filter could name a photo the sweep spares, or spare
     * one the sweep takes, and the screen would be a promise about a different set than the one
     * that runs.
     *
     * Every row is `SYNCED` with a non-blank `cloudFileId`, so every photo in it has a Drive copy
     * the pipeline can verify. That is what makes the backed-up badge on each cell a check the
     * user can make rather than a decoration.
     */
    suspend fun candidates(
        userId: UserId,
        olderThanMs: Long,
        protectDownloaded: Boolean = false,
    ): List<SyncState> = withContext(Dispatchers.IO) {
        syncStateRepo.getSyncedBefore(userId, olderThanMs)
            .filter { isEligibleForReclamation(it, olderThanMs, protectDownloaded) }
            .filter { !it.cloudFileId.isNullOrBlank() }
    }

    /**
     * Mutex-held single-pass verify+delete scoped to [olderThanMs] / [protectDownloaded].
     * Automatic runs recompute eligibility so they cannot reclaim protected downloads.
     * [onProgress] carries phase + counts only.
     */
    suspend fun runPipeline(
        userId: UserId,
        olderThanMs: Long,
        protectDownloaded: Boolean = false,
        onProgress: suspend (PipelineProgress) -> Unit = {},
    ): FreeUpResult = mutex.withLock {
        runPipelineLocked(userId, olderThanMs, protectDownloaded, onProgress)
    }

    /**
     * Scheduled-path entry: try-lock and skip when manual pipeline holds the mutex. Returns null
     * when skipped so the automatic worker can end success without waiting on a foreground run.
     */
    suspend fun tryRunPipeline(
        userId: UserId,
        olderThanMs: Long,
        protectDownloaded: Boolean = true,
        onProgress: suspend (PipelineProgress) -> Unit = {},
    ): FreeUpResult? {
        if (!mutex.tryLock()) return null
        try {
            return runPipelineLocked(userId, olderThanMs, protectDownloaded, onProgress)
        } finally {
            mutex.unlock()
        }
    }

    /**
     * Revalidates a pending permanent-delete batch immediately before the system consent request.
     *
     * The account-scoped sync-state lookup supplies each URI's current cloud link. Only URIs whose
     * current link is returned as active by the strict Drive endpoint survive, in input order.
     * Missing rows, missing links, and inactive links are rejected. Lookup failures propagate so
     * the caller launches no delete request from an uncertain answer.
     */
    suspend fun verifyPendingConsentBatch(
        userId: UserId,
        localUris: Set<String>,
    ): Set<String> = withContext(Dispatchers.IO) {
        if (localUris.isEmpty()) return@withContext emptySet()

        val requested = localUris.toList()
        val linkIdByUri = syncStateRepo.cloudPairedLinkIds(userId, requested)
        val linkIds = requested.mapNotNull { uri ->
            linkIdByUri[uri]?.takeIf { it.isNotBlank() }
        }.distinct()
        if (linkIds.isEmpty()) return@withContext emptySet()

        val volumeId = cloudRepo.getVolumeId(userId)
        val details = retryWithBackoff(
            shouldRetry = { isTransientApiError(it) || isOfflineError(it) },
        ) {
            linkDetailHelpers.batchFetchLinkDetailsStrict(userId, volumeId, linkIds)
        }
        requested.filterTo(linkedSetOf()) { uri ->
            val linkId = linkIdByUri[uri] ?: return@filterTo false
            details[linkId]?.link?.state == ACTIVE_LINK_STATE
        }
    }

    private suspend fun runPipelineLocked(
        userId: UserId,
        olderThanMs: Long,
        protectDownloaded: Boolean,
        onProgress: suspend (PipelineProgress) -> Unit,
    ): FreeUpResult = withContext(Dispatchers.IO) {
        val eligible = candidates(userId, olderThanMs, protectDownloaded)
        val total = eligible.size
        if (total == 0) {
            onProgress(PipelineProgress(PipelinePhase.Verifying, 0, 0))
            return@withContext FreeUpResult.Done(checked = 0, freed = 0)
        }

        var freed = 0
        var skipped = 0
        val needsPermission = mutableListOf<String>()
        var processed = 0
        onProgress(PipelineProgress(PipelinePhase.Verifying, 0, total))

        val volumeId = cloudRepo.getVolumeId(userId)
        for (chunk in eligible.chunked(VERIFY_CHUNK_SIZE)) {
            currentCoroutineContext().ensureActive()
            onProgress(
                PipelineProgress(
                    phase = PipelinePhase.Verifying,
                    processed = processed,
                    total = total,
                    removed = freed,
                ),
            )
            val linkIds = chunk.mapNotNull { it.cloudFileId }.distinct()
            // Failed / offline fetch must not delete anything in this chunk (exception propagates).
            val details = retryWithBackoff(
                shouldRetry = { isTransientApiError(it) || isOfflineError(it) },
            ) {
                linkDetailHelpers.batchFetchLinkDetailsStrict(userId, volumeId, linkIds)
            }
            currentCoroutineContext().ensureActive()
            val active = chunk.filter { row ->
                val linkId = row.cloudFileId ?: return@filter false
                details[linkId]?.link?.state == ACTIVE_LINK_STATE
            }
            if (active.isNotEmpty()) {
                onProgress(
                    PipelineProgress(
                        phase = PipelinePhase.Deleting,
                        processed = processed,
                        total = total,
                        removed = freed,
                    ),
                )
                val deleteResult = localMediaRepo.deleteDeviceCopies(active.map { it.localUri })
                val toCloudOnly = deleteResult.deleted + deleteResult.missing
                if (toCloudOnly.isNotEmpty()) {
                    withContext(NonCancellable) {
                        syncStateRepo.updateStatusAndDeleteLocal(toCloudOnly, SyncStatus.CLOUD_ONLY)
                    }
                    freed += toCloudOnly.size
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    needsPermission += deleteResult.needsPermission
                } else {
                    skipped += deleteResult.needsPermission.size
                }
            }
            processed += chunk.size
            onProgress(
                PipelineProgress(
                    phase = if (active.isEmpty()) PipelinePhase.Verifying else PipelinePhase.Deleting,
                    processed = processed,
                    total = total,
                    removed = freed,
                ),
            )
        }

        if (needsPermission.isNotEmpty()) {
            return@withContext FreeUpResult.NeedsPermission(
                checked = total,
                freed = freed,
                localUris = needsPermission,
            )
        }
        FreeUpResult.Done(
            checked = total,
            freed = freed,
            skipped = skipped,
        )
    }

    companion object {
        /** Link.State the batch link-details endpoint returns for a link that exists and is not
         *  trashed. Any other value (or an absent link) means the cloud copy is not confirmed. */
        const val ACTIVE_LINK_STATE = 1

        /** Drive batch link-details chunk size (matches [LinkDetailHelpers]). */
        const val VERIFY_CHUNK_SIZE = 50

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
