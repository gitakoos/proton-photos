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

import eu.akoos.photos.domain.entity.GalleryItem
import eu.akoos.photos.domain.entity.SyncStatus

/** What a reveal did, and therefore what the vault may drop for the photo. */
enum class HiddenRestoreOutcome {
    /** The file is back on the device and no row hides it any more. */
    RESTORED,

    /** The entry names no vault file: that hide was a filter, so dropping the record IS the reveal. */
    FILTERED,

    /** The vault no longer holds the file. Nothing can come back, and nothing is left to keep. */
    VANISHED,

    /** The vault copy is still there and could not be written back to the device. */
    FAILED,

    /** The file is back on the device and a sync row still marks the photo as vaulted, which drops
     *  its Drive copy from every listing this app draws. Half a reveal, and the half that is missing
     *  is the one the user cannot see. */
    STRANDED,
    ;

    /** Whether the index entry and the per-uri records may go. Keeping them is the only thing that
     *  ties a surviving vault file to its name, its folder and its cloud twin, and the only thing
     *  that leaves a photo whose row still hides it reachable from the hidden area. */
    val clearsRecords: Boolean get() = this != FAILED && this != STRANDED

    /** Whether the photo ended up where the user asked for it. */
    val revealed: Boolean get() = this == RESTORED || this == FILTERED
}

/** How a reveal returned the sync row its photo's hide left at [SyncStatus.HIDDEN]. */
enum class HiddenRowRevival {
    /** The Drive pairing moved onto the restored photo, which retires the vaulted row by itself. */
    PAIRED,

    /** The row was found under the uri the photo was hidden from, and returned to a live status. */
    BY_SOURCE,

    /** The row was found under the uri the photo came back as, and returned to a live status. */
    BY_RESTORED,

    /** Neither handle names a row the vault claims, so nothing is left marking the photo hidden. */
    UNCLAIMED,

    /** A row still marks the photo hidden and could not be rewritten. */
    STRANDED,
    ;

    /** Whether the record half of the reveal landed. Only a reveal with both halves done may release
     *  the vault copy and drop what the vault recorded for the photo. */
    val settled: Boolean get() = this != STRANDED
}

/** What the startup sweep does with one row still at [SyncStatus.HIDDEN]. */
enum class VaultedRowFix {
    /** Drop the row outright: the photo is already back on the device under another row, so this
     *  HIDDEN one names nothing the vault holds and only keeps the Drive copy filtered. */
    CLEAR_STRANDED,

    /** Return the row to SYNCED in place: its own device file resolves and it carries a Drive copy. */
    REVEAL_SYNCED,

    /** Return the row to LOCAL_ONLY in place: its own device file resolves and it has no Drive copy. */
    REVEAL_LOCAL_ONLY,

    /** Leave the row: a correctly vaulted photo whose file is genuinely gone into the vault, or a row
     *  the sweep cannot answer for. */
    LEAVE,
}

/**
 * The decisions a hide and a reveal turn on: what each one may destroy, and which photos a vaulted
 * row still speaks for, expressed without Android so they can be reasoned about and tested on their
 * own.
 */
object HiddenVaultDecisions {

    /**
     * Whether a vault copy is provably the whole photo. [expectedBytes] is what the source states it
     * holds, negative when it states nothing; [copiedBytes] is what the copy reported writing, and
     * [storedBytes] what the vault file holds once the write has reached the disk.
     *
     * The device original is destroyed the moment a copy is accepted, so anything short of proof is
     * refused and the original stays where it is. A source that ends early without ever failing is the
     * case this exists for: it hands back a file that looks like a photo and holds part of one, or none
     * of it. An empty file is no photo whatever the source claims, and a source that states no length
     * can be held to nothing beyond every byte it produced arriving.
     */
    fun vaultCopyIsWhole(expectedBytes: Long, copiedBytes: Long, storedBytes: Long): Boolean = when {
        storedBytes <= 0L -> false
        copiedBytes != storedBytes -> false
        expectedBytes < 0L -> true
        else -> storedBytes == expectedBytes
    }

    /**
     * The originals a hide may delete: exactly the ones a vault copy was written for.
     *
     * The delete is permanent — it bypasses the device trash — so a photo whose copy failed, and
     * everything a stopped pass never reached, has to stay on the device or it exists nowhere at all.
     * The match is on the source uri each copy recorded rather than on position, which is what keeps
     * the answer right when a copy fails in the middle of a batch.
     *
     * Each survivor comes back as the gallery item its surface held, because that is what the delete
     * is run against and what tells it a backed-up photo apart from a device-only one: the two leave
     * the sync table in different states, and only the item carries the difference.
     */
    fun deletableOriginals(
        vaultable: List<HiddenFolderRecords.VaultTarget>,
        stored: List<HiddenVaultJournal.Entry>,
    ): List<GalleryItem> {
        if (stored.isEmpty() || vaultable.isEmpty()) return emptyList()
        val copied = stored.mapTo(HashSet(stored.size)) { it.sourceUri }
        return vaultable.filter { it.local.uri in copied }.map { it.item }
    }

    /**
     * What a reveal attempt leaves the vault holding.
     *
     * [isVaultEntry] is false for a hide that only ever filtered, where the record is the whole hide.
     * [vaultFileExists] separates a vault file that is gone from one that is still there: only the
     * second can be tried again, and only for it is keeping the records worth anything. A
     * [restoredUri] is the proof the device took the file back.
     *
     * [rowSettled] is the other half of a reveal, and a file back on the device without it is the one
     * state where the photo is in neither place: the bytes sit where every other gallery app can open
     * them while a row still at [SyncStatus.HIDDEN] drops the Drive copy from every listing this app
     * draws. Saying so is what keeps the vault copy and the records, so the photo is still reachable
     * from the hidden area and the reveal can be asked for again.
     */
    fun restoreOutcome(
        isVaultEntry: Boolean,
        vaultFileExists: Boolean,
        restoredUri: String?,
        rowSettled: Boolean,
    ): HiddenRestoreOutcome = when {
        !isVaultEntry -> HiddenRestoreOutcome.FILTERED
        !vaultFileExists -> HiddenRestoreOutcome.VANISHED
        restoredUri.isNullOrBlank() -> HiddenRestoreOutcome.FAILED
        !rowSettled -> HiddenRestoreOutcome.STRANDED
        else -> HiddenRestoreOutcome.RESTORED
    }

    /**
     * Whether a reveal still has a vaulted row to answer for once its pairing move came to [move].
     *
     * [HiddenPairingMove.MOVED] rewrote the row onto the restored photo and deleted the one the hide
     * left, so nothing marks the photo hidden any more. Every other answer leaves the reveal without
     * proof of that, and the one the vault was blind to is [HiddenPairingMove.ABSENT]: no pairing was
     * recorded, so there is no Drive copy to look a row up by and nothing ever came back to the row
     * the hide wrote.
     */
    fun revealNeedsRowRepair(move: HiddenPairingMove): Boolean = move != HiddenPairingMove.MOVED

    /**
     * The uris a reveal looks its photo's vaulted row up under, in the order they are tried.
     *
     * [sourceUri] is what the photo was hidden from, which is the uri the hide keyed the row on, so it
     * is asked first and answers for the ordinary case. [restoredUri] is what the device handed the
     * file back as, and covers a row a sync pass minted for the restored file before the reveal
     * finished. Blanks and a repeat of the first are left out, so a caller never asks twice for one
     * answer or asks for a row that no uri names.
     */
    fun revealRowCandidates(sourceUri: String?, restoredUri: String?): List<String> =
        listOfNotNull(sourceUri, restoredUri)
            .filter { it.isNotBlank() }
            .distinct()

    /**
     * The live status a sync row has to take when the device still holds the file it claims to have
     * vaulted, or null when the row stands as it is.
     *
     * [SyncStatus.HIDDEN] is the whole of what keeps a photo out of the listings: the gallery drops
     * the Drive copy that row's cloudFileId names, and the vault is expected to hold the device file
     * in its place. The vault's premise is that hiding MOVES the file into app-private storage and
     * removes the original, so a row still at HIDDEN over a [deviceFileExists] file describes a photo
     * that is not hidden at all: one filtered out of every listing while sitting in plain view of
     * every other gallery app, which leaves it nowhere.
     *
     * [deviceFileExists] false is a correctly vaulted photo and answers null. That direction is the
     * one that matters: returning a status there would reveal a photo the user meant to hide, so a
     * file the resolver cannot answer for has to be counted as gone rather than as present.
     *
     * [hasCloudCopy] picks which live status the row returns to. A row carrying a Drive copy becomes
     * [SyncStatus.SYNCED], which is exactly what a file on the device beside a copy in Drive is, and
     * one carrying none becomes [SyncStatus.LOCAL_ONLY], the device-only photo it describes.
     *
     * Any other [status] belongs to a row the vault never claimed, and this answers nothing for it.
     */
    fun revealedRowStatus(
        status: SyncStatus,
        hasCloudCopy: Boolean,
        deviceFileExists: Boolean,
    ): SyncStatus? = when {
        status != SyncStatus.HIDDEN -> null
        !deviceFileExists -> null
        hasCloudCopy -> SyncStatus.SYNCED
        else -> SyncStatus.LOCAL_ONLY
    }

    /**
     * What the startup sweep does with one row still at [SyncStatus.HIDDEN], given what the rest of the
     * table and the device say about the same photo.
     *
     * [revealedRowStatus] answers the case where the reveal put the file back on THIS row's own uri and
     * only left the status behind. It cannot answer the other shape the strand takes: a reveal writes
     * the live SYNCED row on the uri the device minted for the restored file, and a separate HIDDEN row
     * keyed on the dead pre-hide uri survives whichever row [getByCloudId] returned. That row's own uri
     * resolves to nothing, so [ownFileExists] is false and the in-place repair never fires, yet its
     * cloudFileId goes on dropping the Drive copy from every listing while every other gallery app shows
     * the file.
     *
     * [cloudHasLivePairing] is the handle on that: the same cloud copy carrying a SYNCED or LOCAL_ONLY
     * row means a device file is paired to it, so the HIDDEN row could be a leftover and is dropped rather
     * than demoted, since demoting would put a second row on the one cloud copy. [hasCloudCopy] guards it,
     * since a strand is only visible when a Drive copy is there to filter.
     *
     * [vaultStillHoldsPhoto] is what keeps that from revealing a photo the user meant to hide. A live
     * sibling does NOT prove the hidden photo came back: a byte-identical duplicate the user never hid can
     * pair a second device file to the very same Drive copy by content hash, and that reads here as a live
     * sibling too. The vault's own record is the authority the sibling is not: while the vault still holds
     * this photo's blob it is genuinely hidden, so the row is left however the rest of the table reads.
     * Only once the vault has let go of it is a live sibling taken as the photo being back. It is checked
     * first for that reason.
     *
     * With the vault letting go and no live sibling, the row is judged by its own file: present returns it
     * to a live status, gone leaves it as the correctly vaulted photo it is. Any non-HIDDEN status is left
     * to whatever owns it.
     */
    fun vaultedRowFix(
        status: SyncStatus,
        hasCloudCopy: Boolean,
        cloudHasLivePairing: Boolean,
        vaultStillHoldsPhoto: Boolean,
        ownFileExists: Boolean,
    ): VaultedRowFix = when {
        status != SyncStatus.HIDDEN -> VaultedRowFix.LEAVE
        vaultStillHoldsPhoto -> VaultedRowFix.LEAVE
        hasCloudCopy && cloudHasLivePairing -> VaultedRowFix.CLEAR_STRANDED
        !ownFileExists -> VaultedRowFix.LEAVE
        hasCloudCopy -> VaultedRowFix.REVEAL_SYNCED
        else -> VaultedRowFix.REVEAL_LOCAL_ONLY
    }

    /** How [confirmSplit] divides a hide batch: the vault uris to publish as hidden, and the ones whose
     *  device original the check still finds, which must not be hidden. */
    data class ConfirmSplit(val publish: List<String>, val survivors: List<String>)

    /**
     * Splits [privateUris] by whether each photo's device original is still present, so a confirm files
     * a photo as hidden only once the device no longer holds it.
     *
     * The whole promise of a hide is that the original leaves the device; the vault copy is the backup
     * that makes removing it safe. So a photo is published as hidden only when its original is gone. One
     * the check still finds is a hide whose delete did not land, whatever the delete flow reported: the
     * system can answer a delete request OK on a device that does not act on it, and publishing that
     * photo would file it as hidden in this app while its bytes stay in plain view of every other gallery
     * app, the exact false-privacy this guards. Such a photo is a survivor, kept out of the hidden set so
     * the caller can drop its now-redundant vault copy (the original is the surviving copy, so nothing is
     * lost) and leave it visible.
     *
     * [sourceByVaultUri] maps a vault uri to the uri the photo was hidden from; [originalStillPresent] is
     * asked of that source uri. Only a source mapped AND answered present diverts a photo: a vault uri the
     * journal never recorded a source for, and any source the caller could not resolve to a definite
     * "still there", both publish, so a check that goes quiet never blocks an ordinary hide.
     */
    fun confirmSplit(
        privateUris: List<String>,
        sourceByVaultUri: Map<String, String>,
        originalStillPresent: (sourceUri: String) -> Boolean,
    ): ConfirmSplit {
        if (privateUris.isEmpty()) return ConfirmSplit(emptyList(), emptyList())
        val publish = ArrayList<String>(privateUris.size)
        val survivors = ArrayList<String>()
        for (vaultUri in privateUris) {
            val source = sourceByVaultUri[vaultUri]
            if (source != null && originalStillPresent(source)) survivors += vaultUri else publish += vaultUri
        }
        return ConfirmSplit(publish, survivors)
    }

    /**
     * The pairing a reveal is about to re-point: the Drive copy [cloudLinkId] names, and the
     * [restoredUri] the device handed the file back under.
     */
    data class PairingTransplant(val cloudLinkId: String, val restoredUri: String)

    /**
     * The pairing a reveal may carry onto the restored file, or null when it may not touch the sync
     * table at all.
     *
     * A photo with no Drive copy has no pairing to move, and a reveal that produced no uri put no file
     * anywhere, so neither can name a row.
     *
     * The [MEDIA_STORE_URI_PREFIX] test is the one that matters. A reveal into a folder outside the
     * media index's own roots hands back a plain file uri when the scan does not answer in time, and a
     * SYNCED row keyed on one of those is demoted to CLOUD_ONLY by the next reconcile pass — the reveal
     * would end by telling the user their photo is only on Drive. Refusing leaves the pairing on the row
     * it is already on, which is a true record rather than one destroyed within the hour, and the sync
     * pass re-pairs the restored file to the very same Drive copy once the index names it.
     */
    fun transplantedPairing(cloudLinkId: String?, restoredUri: String?): PairingTransplant? {
        if (cloudLinkId.isNullOrBlank() || restoredUri.isNullOrBlank()) return null
        if (!restoredUri.startsWith(MEDIA_STORE_URI_PREFIX)) return null
        return PairingTransplant(cloudLinkId, restoredUri)
    }

    /**
     * The name a restored file may be written under where [taken] answers for what the folder holds.
     *
     * A reveal puts the file back under the name it was hidden with, and a folder that has since
     * gained a file of that name would be overwritten by it — the one case where returning a photo
     * destroys a different one. The suffix follows what the media provider does on the folders it
     * manages, so a file written by hand and one written through MediaStore land under the same kind
     * of name.
     *
     * Terminates because [taken] answers for a finite folder: at most one more name is tried than
     * the folder holds.
     */
    fun uniqueRestoreName(desired: String, taken: (String) -> Boolean): String {
        if (!taken(desired)) return desired
        val dot = desired.lastIndexOf('.')
        val stem = if (dot > 0) desired.substring(0, dot) else desired
        val ext = if (dot > 0) desired.substring(dot) else ""
        var n = 1
        while (true) {
            val candidate = "$stem ($n)$ext"
            if (!taken(candidate)) return candidate
            n++
        }
    }

    /** What a uri the media index minted starts with. The vault's own files and a file written straight
     *  to a folder the index does not manage both carry a different scheme, and neither may key a row
     *  that claims a device copy the index can find. */
    private const val MEDIA_STORE_URI_PREFIX = "content://"
}
