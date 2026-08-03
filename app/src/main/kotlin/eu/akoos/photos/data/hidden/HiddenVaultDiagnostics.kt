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

package eu.akoos.photos.data.hidden

import eu.akoos.photos.util.SyncDiagnostics
import eu.akoos.photos.util.uploadLogRef
import java.util.Locale

/**
 * What the vault says about itself in the one channel a tester can hand over.
 *
 * Each milestone below is on the path a hide or a reveal takes when it WORKS, which is the half a
 * failure log cannot cover: a vault that records only its failures writes nothing at all for an
 * operation that succeeded, and a question about that operation has nothing to read against.
 *
 * Every line is AGGREGATE — one per milestone per batch, counts, byte totals and flags only. A folder
 * hide carries thousands of photos while the buffer holds ~600 lines, so a line per photo would evict
 * the whole session to say what one count says. A per-photo line belongs to a failure alone, and
 * carries a hashed ref rather than anything that names the photo.
 *
 * The buffer is pasted into public issues, so nothing here may hold a file name, a path, a uri, a
 * folder, album or bucket name, a Drive link id, an account id or an address. Where a value would be
 * any of those, its COUNT goes in instead.
 */
object HiddenVaultDiagnostics {

    private const val BYTES_PER_MB = 1024.0 * 1024.0

    /**
     * A stable, non-reversible short token for the photo at [uri], so the lines of one operation can
     * be read together without any of them recording what the photo is.
     *
     * The one form every vault line uses, shareable and verbose alike, so two lines about one photo
     * are recognisably about one photo.
     */
    fun ref(uri: String): String = uploadLogRef(uri).take(6)

    /** The hide about to run, read off the split that decides which half each photo falls in. */
    fun hideStarted(split: HiddenFolderRecords.HideSplit) = hideStarted(
        vaultedCount = split.vaultable.size,
        pairedCount = split.vaultable.count { it.cloudLinkId != null },
        filteredCount = split.cloudLinkIds.size,
        totalBytes = split.vaultable.sumOf { it.sizeBytes },
    )

    /**
     * The same milestone for the viewer, which hides one photo and holds its facts loose rather than
     * as a split.
     *
     * [vaultedCount] photos move into the vault, [pairedCount] of them carrying a Drive copy the
     * reveal re-pairs to; [filteredCount] are cloud-only and hide by filter alone, with no bytes to
     * move. [totalBytes] is what the copies have to fit.
     */
    fun hideStarted(vaultedCount: Int, pairedCount: Int, filteredCount: Int, totalBytes: Long) {
        SyncDiagnostics.log(
            "hide: start vault=$vaultedCount paired=$pairedCount filtered=$filteredCount " +
                "size=${mb(totalBytes)}",
        )
    }

    /** The free-space gate: what the copies need, and what the volume is short by when it refuses. */
    fun spaceChecked(requiredBytes: Long, shortfallBytes: Long) {
        SyncDiagnostics.log(
            if (shortfallBytes > 0L) "hide: refused, short ${mb(shortfallBytes)} of ${mb(requiredBytes)}"
            else "hide: space ok for ${mb(requiredBytes)}",
        )
    }

    /** Free space could not be read, so the hide proceeds and the copies answer for themselves. */
    fun spaceUnreadable() {
        SyncDiagnostics.log("hide: space unreadable, proceeding")
    }

    /** The copy phase is over: [copiedCount] photos are in the vault, [failedCount] stayed put. */
    fun copied(copiedCount: Int, failedCount: Int) {
        SyncDiagnostics.log("hide: copied $copiedCount, failed $failedCount")
    }

    /** The intent for [count] photos is on disk, so the delete of their originals may run. */
    fun journalled(count: Int) {
        SyncDiagnostics.log("hide: journalled $count")
    }

    /** Nothing could be recorded, so the delete must not run. [reason] is an exception TYPE name. */
    fun journalFailed(reason: String) {
        SyncDiagnostics.log("hide: journal write failed ($reason)")
    }

    /** The originals are gone. [neededConsent] is false only where the delete ran without the system
     *  dialog, which is the pre-Android-11 path. */
    fun originalsRemoved(count: Int, neededConsent: Boolean) {
        SyncDiagnostics.log("hide: originals removed $count, consent ${yesNo(neededConsent)}")
    }

    /** The delete is with the system consent dialog and the user has not answered it yet. */
    fun originalsAwaitingConsent(count: Int) {
        SyncDiagnostics.log("hide: originals $count awaiting consent")
    }

    /** The hide landed: [published] photos are in the vault, [hiddenRows] sync rows moved to the
     *  vaulted state, and [queueCleared] pending upload intents were dropped with their files. */
    fun confirmed(published: Int, hiddenRows: Int, queueCleared: Int, survivorsKept: Int) {
        SyncDiagnostics.log(
            "hide: confirmed $published, rows hidden $hiddenRows, queued cleared $queueCleared, " +
                "kept visible $survivorsKept",
        )
    }

    /** A hide that never reached its delete gave [count] vault copies back. */
    fun discarded(count: Int) {
        SyncDiagnostics.log("hide: discarded $count")
    }

    /** The reconciliation ran and the vault was already self-consistent. */
    fun reconciledNothing() {
        SyncDiagnostics.log("vault: reconcile found nothing")
    }

    /** What the reconciliation decided for the leftovers of an interrupted hide, one count per
     *  outcome — the one line that says whether a tester's vault is self-consistent. */
    fun reconciled(confirm: Int, adopt: Int, discard: Int, forget: Int) {
        SyncDiagnostics.log(
            "vault: reconciled confirm=$confirm adopt=$adopt discard=$discard forget=$forget",
        )
    }

    /**
     * The sweep over the rows the vault claims: [examined] of them, of which [synced] and [localOnly]
     * were returned to a live status because the device still holds their own files, and [stranded]
     * were dropped because the photo is already back on the device under another row.
     *
     * Logged on every run, zeros included, since "the sweep found nothing to correct" and "the sweep
     * never ran" are the two answers a report about a photo missing from both places has to separate.
     */
    fun vaultedRowsSwept(examined: Int, synced: Int, localOnly: Int, stranded: Int) {
        SyncDiagnostics.log(
            "vault: swept $examined row(s), revived synced=$synced local=$localOnly stranded=$stranded",
        )
    }

    /** A reveal is starting over [count] photos. */
    fun revealStarted(count: Int) {
        SyncDiagnostics.log("reveal: start $count")
    }

    /** What the reveal did, in two lines: the outcome, then how it got there. */
    fun revealFinished(revealed: Int, failed: Int, tally: RevealTally) {
        SyncDiagnostics.log("reveal: done back=$revealed failed=$failed")
        SyncDiagnostics.log(
            "reveal: mediaStore=${tally.mediaStoreBranch} originalPath=${tally.originalPathBranch} " +
                "paired=${tally.pairingMoved} pairSkipped=${tally.pairingSkipped} " +
                "unpaired=${tally.pairingAbsent} pairFailed=${tally.pairingFailed} " +
                "carried=${tally.carriedReapplied} recordsDropped=${tally.recordsDropped}",
        )
        SyncDiagnostics.log(
            "reveal: rows paired=${tally.rowPaired} bySource=${tally.rowBySource} " +
                "byRestored=${tally.rowByRestored} unclaimed=${tally.rowUnclaimed} " +
                "stranded=${tally.rowStranded}",
        )
    }

    /** [bytes] as MB, one decimal, so a copy smaller than a megabyte still reads as a size. */
    private fun mb(bytes: Long): String =
        String.format(Locale.US, "%.1fMB", bytes.coerceAtLeast(0L) / BYTES_PER_MB)

    private fun yesNo(value: Boolean): String = if (value) "yes" else "no"
}

/**
 * The running counts of one reveal, so a run of thousands reports itself in two lines rather than in
 * thousands.
 *
 * Filled per photo as the reveal walks it and read once at the end. Every field is a count of photos:
 * which branch put the file back, what happened to its Drive pairing, and how much of the record the
 * run was able to drop. The photos themselves are never named.
 */
class RevealTally {
    /** Written back through the media index, which is the ordinary branch. */
    var mediaStoreBranch: Int = 0

    /** Written straight to the folder the photo came from, for a location the media index does not
     *  manage. */
    var originalPathBranch: Int = 0

    /** The Drive pairing moved onto the restored photo. */
    var pairingMoved: Int = 0

    /** The pairing stayed where it was, the restored uri not being one the media index minted. */
    var pairingSkipped: Int = 0

    /** The photo had no Drive copy, so there was no pairing to move. */
    var pairingAbsent: Int = 0

    /** There was a pairing to move and the rows could not be rewritten. */
    var pairingFailed: Int = 0

    /** The heart, categories, pinned cover and album queue the hide carried forward were put back. */
    var carriedReapplied: Int = 0

    /** The index entry and the per-uri records were dropped, the photo being back on the device. */
    var recordsDropped: Int = 0

    /** The pairing move retired the vaulted row on its own. */
    var rowPaired: Int = 0

    /** The vaulted row was found under the uri the photo was hidden from. */
    var rowBySource: Int = 0

    /** The vaulted row was found under the uri the photo came back as. */
    var rowByRestored: Int = 0

    /** No row anywhere still marked the photo as vaulted. */
    var rowUnclaimed: Int = 0

    /** A row goes on marking the photo as vaulted, so the reveal is only half done and the vault copy
     *  was kept. The one count that says a photo is in neither place. */
    var rowStranded: Int = 0

    /** Record one photo's pairing outcome, so the caller counts by naming the outcome rather than by
     *  picking the field. */
    fun count(move: HiddenPairingMove) {
        when (move) {
            HiddenPairingMove.MOVED -> pairingMoved++
            HiddenPairingMove.SKIPPED -> pairingSkipped++
            HiddenPairingMove.ABSENT -> pairingAbsent++
            HiddenPairingMove.FAILED -> pairingFailed++
        }
    }

    /** The same for what became of the row the hide marked vaulted. */
    fun count(revival: HiddenRowRevival) {
        when (revival) {
            HiddenRowRevival.PAIRED -> rowPaired++
            HiddenRowRevival.BY_SOURCE -> rowBySource++
            HiddenRowRevival.BY_RESTORED -> rowByRestored++
            HiddenRowRevival.UNCLAIMED -> rowUnclaimed++
            HiddenRowRevival.STRANDED -> rowStranded++
        }
    }
}
