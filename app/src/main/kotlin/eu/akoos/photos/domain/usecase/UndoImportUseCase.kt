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

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.proton.core.domain.entity.UserId
import eu.akoos.photos.data.db.dao.ImportUploadedDao
import eu.akoos.photos.data.db.entity.ImportUploadedEntity
import eu.akoos.photos.data.repository.drive.LinkDetailHelpers
import eu.akoos.photos.domain.repository.DrivePhotoRepository
import eu.akoos.photos.util.ImportDiagnostics
import eu.akoos.photos.util.SyncDiagnostics
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Counts for one undo of an import run: [undone] photos moved to the Drive trash, [keptChanged] photos
 * left in place because their bytes no longer match what the import uploaded, and [failed] photos that
 * could not be verified or that the trash call rejected.
 */
data class UndoResult(
    val undone: Int,
    val keptChanged: Int,
    val failed: Int,
)

/**
 * Reverses one import run by moving the photos it uploaded to the Drive trash, but only the photos
 * whose bytes still match what the run sent. Each candidate's current cloud ContentHash is read straight
 * from Drive and compared to the hash the stored upload sha1 maps to; a photo the user has since
 * replaced or edited no longer matches and is kept. Every removal is a recoverable trash move, never a
 * permanent delete, and any uncertainty (a link the fetch could not read, an underivable expected hash,
 * a hash that differs) resolves to keeping the photo, so an undo can never take a file the run did not
 * upload or a newer version the user chose to keep.
 */
@Singleton
class UndoImportUseCase @Inject constructor(
    private val cloudRepo: DrivePhotoRepository,
    private val importUploadedDao: ImportUploadedDao,
    private val linkDetailHelpers: LinkDetailHelpers,
) {
    suspend fun undo(userId: UserId, runId: String): UndoResult = withContext(Dispatchers.IO) {
        // Only the run's REAL uploads are undo candidates. A deduped row records a pre-existing Drive link
        // the run never created, so it is excluded here and can never be trashed by undoing this run.
        val rows = importUploadedDao.pendingRealUploadsByRun(runId)
        if (rows.isEmpty()) return@withContext UndoResult(0, 0, 0)

        val linkIds = rows.map { it.linkId }.distinct()

        // Current cloud ContentHash per candidate link, read from Drive in one batched call. A link the
        // fetch does not return (already gone, or the server will not surface it) is simply absent from
        // this map and is never trashed. A transient failure of the whole batch throws and is caught
        // here so the run keeps every photo rather than trashing against an answer it could not read.
        val currentByLink: Map<String, String?> = try {
            val volumeId = cloudRepo.getVolumeId(userId)
            linkDetailHelpers.batchFetchLinkDetails(userId, volumeId, linkIds)
                .mapValues { (_, detail) -> detail.photo?.contentHash }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            SyncDiagnostics.log("import undo: link fetch failed, kept ${rows.size} unverified")
            ImportDiagnostics.recordError(e.javaClass.simpleName)
            ImportDiagnostics.recordUndo(0, 0, rows.size)
            return@withContext UndoResult(0, 0, rows.size)
        }

        // Expected hash = the cloud ContentHash the stored upload sha1 maps to, null when the root hash
        // key is not cached. A null expected never equals a real current hash, so the partition keeps
        // those rows instead of guessing.
        val expectedByLink: Map<String, String?> =
            rows.associate { it.linkId to cloudRepo.cloudContentHash(it.sha1) }

        val plan = planImportUndo(rows, expectedByLink, currentByLink)

        var trashCallFailed = 0
        val outcome = if (plan.toTrash.isEmpty()) {
            null
        } else {
            try {
                cloudRepo.deleteFiles(userId, plan.toTrash)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // A hard failure of the bulk trash leaves the rows pending for a later retry rather than
                // aborting the run. Nothing was trashed, so nothing is at risk.
                trashCallFailed = plan.toTrash.size
                null
            }
        }
        val trashed = outcome?.trashedLinkIds ?: emptySet()
        val failedTrash = outcome?.failedLinkIds ?: emptySet()

        if (trashed.isNotEmpty()) {
            try {
                // Chunk the ledger update so a very large undo never overflows the host-variable cap the
                // IN clause binds against (999 on older Android). The trash call above already chunks.
                trashed.toList().chunked(500).forEach { batch ->
                    importUploadedDao.markUndone(runId, batch)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // The photos are trashed; a failed ledger write only means a later undo re-checks them,
                // finds them gone, and skips them, so it never double-acts.
            }
        }

        val result = UndoResult(
            undone = trashed.size,
            keptChanged = plan.changed.size,
            failed = failedTrash.size + plan.unverifiable.size + trashCallFailed,
        )
        SyncDiagnostics.log(
            "import undo: trashed ${result.undone}, kept ${result.keptChanged} changed, failed ${result.failed}",
        )
        ImportDiagnostics.recordUndo(result.undone, result.keptChanged, result.failed)
        result
    }
}

/**
 * How an undo classifies its candidate rows, split off from the IO so the safety decision is a pure
 * value a test can pin. [toTrash] holds the links cleared for the recoverable trash move, [changed] the
 * ones kept because the user replaced the bytes, and [unverifiable] the ones kept because no reliable
 * hash was available to compare. A link with no current detail at all is treated as already gone and
 * appears in none of the three.
 */
internal data class ImportUndoPlan(
    val toTrash: List<String>,
    val changed: List<String>,
    val unverifiable: List<String>,
)

/**
 * Decides per row what an undo does with it, using only content-hash equality so the rule stays pure
 * and testable. A row is cleared for [ImportUndoPlan.toTrash] only when its expected hash and the
 * current cloud hash are both present and equal: identical bytes, safe to trash. A present-but-different
 * current hash means the user replaced the photo, so the row is kept as [ImportUndoPlan.changed]. A row
 * whose current hash is missing (the link resolved but carried none) or whose expected hash could not be
 * derived is kept as [ImportUndoPlan.unverifiable], because trashing on an unconfirmed match could take
 * a photo the run never uploaded. A link entirely absent from [currentByLink] is treated as already gone
 * and left out of every bucket.
 */
internal fun planImportUndo(
    rows: List<ImportUploadedEntity>,
    expectedByLink: Map<String, String?>,
    currentByLink: Map<String, String?>,
): ImportUndoPlan {
    val toTrash = mutableListOf<String>()
    val changed = mutableListOf<String>()
    val unverifiable = mutableListOf<String>()
    for (row in rows) {
        val linkId = row.linkId
        if (!currentByLink.containsKey(linkId)) continue
        val current = currentByLink[linkId]
        val expected = expectedByLink[linkId]
        when {
            current == null || expected == null -> unverifiable += linkId
            expected == current -> toTrash += linkId
            else -> changed += linkId
        }
    }
    return ImportUndoPlan(toTrash, changed, unverifiable)
}
