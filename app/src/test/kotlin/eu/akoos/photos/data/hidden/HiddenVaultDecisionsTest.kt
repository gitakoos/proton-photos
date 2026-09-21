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
import eu.akoos.photos.domain.entity.LocalMediaItem
import eu.akoos.photos.domain.entity.SyncStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the decisions that stand between a hide and losing a photo: whether a vault copy holds the
 * whole photo, which originals a hide may delete, what a reveal may drop, what a restored file may be
 * called, which restored file may take the Drive pairing back, which vaulted row the device
 * contradicts, and which reveal still has such a row to answer for. Every one of them loses a photo
 * when it answers wrong, whether by destroying it, by leaving a second copy of it on Drive, or by
 * leaving it in neither place, and none of them needs a device to answer.
 */
class HiddenVaultDecisionsTest {

    private fun local(uri: String) = GalleryItem.LocalOnly(
        LocalMediaItem(
            uri = uri,
            dateTaken = 100L,
            displayName = uri.substringAfterLast('/'),
            mimeType = "image/jpeg",
            sizeBytes = 1_000L,
            bucketName = "Camera",
        ),
    )

    /** The same photo as a hide holds it on its way into the vault. */
    private fun target(uri: String): HiddenFolderRecords.VaultTarget {
        val item = local(uri)
        return HiddenFolderRecords.VaultTarget(
            item = item,
            local = item.local,
            cloudLinkId = null,
            sizeBytes = item.local.sizeBytes,
        )
    }

    private fun entry(sourceUri: String) = HiddenVaultJournal.Entry(
        privateUri = "file:///vault/${sourceUri.substringAfterLast('/')}__100.jpg",
        sourceUri = sourceUri,
    )

    /** The device uri of each item a delete was handed. */
    private fun urisOf(items: List<GalleryItem>) = items.map { (it as GalleryItem.LocalOnly).local.uri }

    // ── whether the copy may be trusted at all ───────────────────────────────────────────────────

    @Test
    fun `a copy holding every byte the source stated is whole`() {
        assertTrue(HiddenVaultDecisions.vaultCopyIsWhole(expectedBytes = 4_096L, copiedBytes = 4_096L, storedBytes = 4_096L))
    }

    @Test
    fun `a copy that stopped short of the source is refused, and the vault file goes with it`() {
        // The case the check exists for: a stream that ends early without ever failing. Refusing is
        // what deletes the part-copy and keeps the original, which the hide would otherwise destroy.
        assertFalse(HiddenVaultDecisions.vaultCopyIsWhole(expectedBytes = 4_096L, copiedBytes = 1_200L, storedBytes = 1_200L))
    }

    @Test
    fun `a source that yielded nothing at all is refused however it is described`() {
        assertFalse(HiddenVaultDecisions.vaultCopyIsWhole(expectedBytes = 4_096L, copiedBytes = 0L, storedBytes = 0L))
        assertFalse(HiddenVaultDecisions.vaultCopyIsWhole(expectedBytes = -1L, copiedBytes = 0L, storedBytes = 0L))
        assertFalse(HiddenVaultDecisions.vaultCopyIsWhole(expectedBytes = 0L, copiedBytes = 0L, storedBytes = 0L))
    }

    @Test
    fun `a source stating no length is held to the bytes it produced arriving`() {
        // A provider that declines to say how much it holds still cannot be allowed to hand back an
        // empty file, and everything it did produce has to have landed.
        assertTrue(HiddenVaultDecisions.vaultCopyIsWhole(expectedBytes = -1L, copiedBytes = 2_048L, storedBytes = 2_048L))
        assertFalse(HiddenVaultDecisions.vaultCopyIsWhole(expectedBytes = -1L, copiedBytes = 2_048L, storedBytes = 1_024L))
    }

    @Test
    fun `a count that disagrees with what the file holds is refused`() {
        // The count comes from the stream and the length from the disk, so the two parting is the
        // write not having landed whole, whatever the source said.
        assertFalse(HiddenVaultDecisions.vaultCopyIsWhole(expectedBytes = 4_096L, copiedBytes = 4_096L, storedBytes = 3_000L))
        assertFalse(HiddenVaultDecisions.vaultCopyIsWhole(expectedBytes = 4_096L, copiedBytes = 3_000L, storedBytes = 4_096L))
    }

    @Test
    fun `a copy holding more than the source stated is refused too`() {
        // Nothing should be able to produce it, and a vault file that is not the source's own bytes is
        // not a copy of the photo the original is about to be deleted for.
        assertFalse(HiddenVaultDecisions.vaultCopyIsWhole(expectedBytes = 4_096L, copiedBytes = 8_192L, storedBytes = 8_192L))
    }

    // ── which originals a hide may delete ────────────────────────────────────────────────────────

    @Test
    fun `a photo whose copy failed keeps its original`() {
        // The whole point: the hide's delete is permanent, so an original deleted without a vault
        // copy behind it leaves the photo nowhere at all.
        val items = listOf(target("content://media/1"), target("content://media/2"), target("content://media/3"))
        val stored = listOf(entry("content://media/1"), entry("content://media/3"))

        val deletable = HiddenVaultDecisions.deletableOriginals(items, stored)

        assertEquals(listOf("content://media/1", "content://media/3"), urisOf(deletable))
    }

    @Test
    fun `nothing copied means nothing may be deleted`() {
        val items = listOf(target("content://media/1"), target("content://media/2"))
        assertTrue(HiddenVaultDecisions.deletableOriginals(items, emptyList()).isEmpty())
        assertTrue(HiddenVaultDecisions.deletableOriginals(emptyList(), listOf(entry("content://media/1"))).isEmpty())
    }

    @Test
    fun `a batch copied whole is deletable whole, in the order it was given`() {
        val items = listOf(target("content://media/3"), target("content://media/1"), target("content://media/2"))
        val stored = items.map { entry(it.local.uri) }.reversed()

        assertEquals(items.map { it.item }, HiddenVaultDecisions.deletableOriginals(items, stored))
    }

    @Test
    fun `a copy of a photo outside this batch deletes nothing of it`() {
        val items = listOf(target("content://media/1"))
        val stored = listOf(entry("content://media/9"))

        assertTrue(HiddenVaultDecisions.deletableOriginals(items, stored).isEmpty())
    }

    @Test
    fun `a stopped pass deletes exactly its own prefix`() {
        val items = (1..5).map { target("content://media/$it") }
        val stored = items.take(2).map { entry(it.local.uri) }

        val deletable = HiddenVaultDecisions.deletableOriginals(items, stored)

        assertEquals(listOf("content://media/1", "content://media/2"), urisOf(deletable))
    }

    // ── what a reveal may drop ───────────────────────────────────────────────────────────────────

    @Test
    fun `a file written back to the device drops its records`() {
        val outcome = HiddenVaultDecisions.restoreOutcome(
            isVaultEntry = true, vaultFileExists = true, restoredUri = "content://media/7", rowSettled = true,
        )
        assertEquals(HiddenRestoreOutcome.RESTORED, outcome)
        assertTrue(outcome.clearsRecords)
        assertTrue(outcome.revealed)
    }

    @Test
    fun `a hide that was only a filter is revealed by dropping the record`() {
        val outcome = HiddenVaultDecisions.restoreOutcome(
            isVaultEntry = false, vaultFileExists = false, restoredUri = null, rowSettled = true,
        )
        assertEquals(HiddenRestoreOutcome.FILTERED, outcome)
        assertTrue(outcome.clearsRecords)
        assertTrue(outcome.revealed)
    }

    @Test
    fun `a file that could not be written back keeps every record`() {
        // The data loss this closes: dropping the records here leaves the vault file on disk with its
        // name, its folder and its cloud pairing gone, to be adopted nameless or taken by a sign-out.
        val outcome = HiddenVaultDecisions.restoreOutcome(
            isVaultEntry = true, vaultFileExists = true, restoredUri = null, rowSettled = true,
        )
        assertEquals(HiddenRestoreOutcome.FAILED, outcome)
        assertFalse(outcome.clearsRecords)
        assertFalse(outcome.revealed)
    }

    @Test
    fun `an empty destination is a failure, not a restore`() {
        assertEquals(
            HiddenRestoreOutcome.FAILED,
            HiddenVaultDecisions.restoreOutcome(
                isVaultEntry = true, vaultFileExists = true, restoredUri = "   ", rowSettled = true,
            ),
        )
    }

    @Test
    fun `a vault file that is gone leaves nothing to keep and nothing to show`() {
        val outcome = HiddenVaultDecisions.restoreOutcome(
            isVaultEntry = true, vaultFileExists = false, restoredUri = null, rowSettled = true,
        )
        assertEquals(HiddenRestoreOutcome.VANISHED, outcome)
        assertTrue(outcome.clearsRecords)
        assertFalse(outcome.revealed)
    }

    @Test
    fun `a file back on the device whose row still hides it is not a reveal`() {
        // The measured bug: the bytes land, the row goes on marking the photo vaulted, and the photo
        // is then in neither place: dropped from this app's listings while every other gallery app
        // shows it. Saying RESTORED here is a reveal reporting a success it did not have.
        val outcome = HiddenVaultDecisions.restoreOutcome(
            isVaultEntry = true, vaultFileExists = true, restoredUri = "content://media/7", rowSettled = false,
        )
        assertEquals(HiddenRestoreOutcome.STRANDED, outcome)
        assertFalse("the vault copy and the records are what the photo is still reachable by", outcome.clearsRecords)
        assertFalse("a half reveal must be counted and reported as a failure", outcome.revealed)
    }

    @Test
    fun `a reveal that put no file back answers for no row`() {
        // A row is only ever settled for a file that came back, so the three outcomes before it stand
        // whatever is said about the row: the photo is still in the vault, or was never in it.
        for (settled in listOf(true, false)) {
            assertEquals(
                HiddenRestoreOutcome.FILTERED,
                HiddenVaultDecisions.restoreOutcome(false, vaultFileExists = false, restoredUri = null, rowSettled = settled),
            )
            assertEquals(
                HiddenRestoreOutcome.VANISHED,
                HiddenVaultDecisions.restoreOutcome(true, vaultFileExists = false, restoredUri = null, rowSettled = settled),
            )
            assertEquals(
                HiddenRestoreOutcome.FAILED,
                HiddenVaultDecisions.restoreOutcome(true, vaultFileExists = true, restoredUri = null, rowSettled = settled),
            )
        }
    }

    @Test
    fun `a failed write and a row still hiding the photo are the only outcomes that keep the records`() {
        for (isVaultEntry in listOf(true, false)) {
            for (exists in listOf(true, false)) {
                for (uri in listOf(null, "", "content://media/7")) {
                    for (settled in listOf(true, false)) {
                        val outcome = HiddenVaultDecisions.restoreOutcome(isVaultEntry, exists, uri, settled)
                        assertEquals(
                            "records kept for $outcome",
                            outcome == HiddenRestoreOutcome.FAILED || outcome == HiddenRestoreOutcome.STRANDED,
                            !outcome.clearsRecords,
                        )
                        assertEquals(
                            "failure for ($isVaultEntry, $exists, $uri)",
                            isVaultEntry && exists && uri.isNullOrBlank(),
                            outcome == HiddenRestoreOutcome.FAILED,
                        )
                        assertEquals(
                            "stranded for ($isVaultEntry, $exists, $uri, $settled)",
                            isVaultEntry && exists && !uri.isNullOrBlank() && !settled,
                            outcome == HiddenRestoreOutcome.STRANDED,
                        )
                    }
                }
            }
        }
    }

    // ── which reveal still has a vaulted row to answer for ───────────────────────────────────────

    @Test
    fun `a pairing that moved leaves no row marking the photo vaulted`() {
        // The move rewrites the row onto the restored photo and deletes the one the hide wrote, so
        // there is nothing left to look for.
        assertFalse(HiddenVaultDecisions.revealNeedsRowRepair(HiddenPairingMove.MOVED))
    }

    @Test
    fun `every other pairing answer leaves a row to look for`() {
        // ABSENT is the one the vault was blind to: no pairing was recorded, so nothing could look the
        // row up by a Drive copy and nothing ever came back to the row the hide wrote.
        for (move in HiddenPairingMove.entries.filter { it != HiddenPairingMove.MOVED }) {
            assertTrue("$move left its row alone", HiddenVaultDecisions.revealNeedsRowRepair(move))
        }
    }

    @Test
    fun `the row is looked for under the uri the photo was hidden from first`() {
        assertEquals(
            listOf("content://media/1", "content://media/9"),
            HiddenVaultDecisions.revealRowCandidates(
                sourceUri = "content://media/1", restoredUri = "content://media/9",
            ),
        )
    }

    @Test
    fun `a photo whose source uri was never recorded is still looked for`() {
        // A photo vaulted before the record existed: the uri it came back as is the whole handle.
        assertEquals(
            listOf("content://media/9"),
            HiddenVaultDecisions.revealRowCandidates(sourceUri = null, restoredUri = "content://media/9"),
        )
        assertEquals(
            listOf("content://media/9"),
            HiddenVaultDecisions.revealRowCandidates(sourceUri = "   ", restoredUri = "content://media/9"),
        )
    }

    @Test
    fun `one uri is never asked for twice, and no uri is never asked for at all`() {
        assertEquals(
            listOf("content://media/9"),
            HiddenVaultDecisions.revealRowCandidates("content://media/9", "content://media/9"),
        )
        assertTrue(HiddenVaultDecisions.revealRowCandidates(null, null).isEmpty())
        assertTrue(HiddenVaultDecisions.revealRowCandidates("", "  ").isEmpty())
    }

    @Test
    fun `only a row left marking the photo vaulted holds a reveal back`() {
        for (revival in HiddenRowRevival.entries) {
            assertEquals(
                "settled for $revival",
                revival != HiddenRowRevival.STRANDED,
                revival.settled,
            )
        }
    }

    @Test
    fun `an unpaired reveal returns its row by the uri the photo was hidden from`() {
        // The whole of the measured bug in one walk: the pairing was never recorded, so the reveal
        // has a row to answer for, finds it under the source uri, and returns it to the live status
        // its Drive copy makes it.
        assertTrue(HiddenVaultDecisions.revealNeedsRowRepair(HiddenPairingMove.ABSENT))
        val candidates = HiddenVaultDecisions.revealRowCandidates(
            sourceUri = "content://media/1", restoredUri = "content://media/9",
        )
        assertEquals("content://media/1", candidates.first())
        assertEquals(
            SyncStatus.SYNCED,
            HiddenVaultDecisions.revealedRowStatus(
                status = SyncStatus.HIDDEN, hasCloudCopy = true, deviceFileExists = true,
            ),
        )
    }

    // ── what a restored file may be called ───────────────────────────────────────────────────────

    @Test
    fun `a free name is used as it is`() {
        assertEquals("IMG_0042.jpg", HiddenVaultDecisions.uniqueRestoreName("IMG_0042.jpg") { false })
    }

    @Test
    fun `a name the folder already holds is never returned`() {
        // Returning it would open the existing file for writing and truncate it: the reveal would
        // destroy a photo that has nothing to do with the one coming back.
        val taken = setOf("IMG_0042.jpg")
        val name = HiddenVaultDecisions.uniqueRestoreName("IMG_0042.jpg") { it in taken }
        assertNotEquals("IMG_0042.jpg", name)
        assertEquals("IMG_0042 (1).jpg", name)
    }

    @Test
    fun `the suffix counts up past every name the folder holds`() {
        val taken = setOf("IMG_0042.jpg", "IMG_0042 (1).jpg", "IMG_0042 (2).jpg")
        assertEquals("IMG_0042 (3).jpg", HiddenVaultDecisions.uniqueRestoreName("IMG_0042.jpg") { it in taken })
    }

    @Test
    fun `the suffix goes before the extension, whichever one that is`() {
        val taken = setOf("clip.tar.gz", "photo", ".stripped")
        assertEquals("clip.tar (1).gz", HiddenVaultDecisions.uniqueRestoreName("clip.tar.gz") { it in taken })
        assertEquals("photo (1)", HiddenVaultDecisions.uniqueRestoreName("photo") { it in taken })
        assertEquals(".stripped (1)", HiddenVaultDecisions.uniqueRestoreName(".stripped") { it in taken })
    }

    @Test
    fun `a folder full of collisions still yields a free name`() {
        val taken = (1..200).mapTo(mutableSetOf("VID_1.mp4")) { "VID_1 ($it).mp4" }
        val name = HiddenVaultDecisions.uniqueRestoreName("VID_1.mp4") { it in taken }
        assertEquals("VID_1 (201).mp4", name)
        assertFalse(name in taken)
    }

    // ── which restored file may take the Drive pairing ───────────────────────────────────────────

    @Test
    fun `a file the media index named takes the pairing, which is what stops a second upload`() {
        val pairing = HiddenVaultDecisions.transplantedPairing(
            cloudLinkId = "link-abc",
            restoredUri = "content://media/external/images/media/91",
        )
        assertEquals(
            HiddenVaultDecisions.PairingTransplant("link-abc", "content://media/external/images/media/91"),
            pairing,
        )
    }

    @Test
    fun `a plain file uri takes no pairing, so no synced row is keyed on one`() {
        // What a reveal into a folder outside the media index's roots hands back when the scan stays
        // silent. A SYNCED row on such a uri is demoted within the hour, so the pairing stays put.
        assertNull(
            HiddenVaultDecisions.transplantedPairing(
                cloudLinkId = "link-abc",
                restoredUri = "file:///storage/emulated/0/Pictures/IMG_0042.jpg",
            ),
        )
    }

    @Test
    fun `no scheme at all is not the media index either`() {
        assertNull(HiddenVaultDecisions.transplantedPairing("link-abc", "/storage/emulated/0/DCIM/IMG_0042.jpg"))
    }

    @Test
    fun `a photo with no Drive copy has no pairing to move`() {
        val restored = "content://media/external/images/media/91"
        assertNull(HiddenVaultDecisions.transplantedPairing(cloudLinkId = null, restoredUri = restored))
        assertNull(HiddenVaultDecisions.transplantedPairing(cloudLinkId = "", restoredUri = restored))
        assertNull(HiddenVaultDecisions.transplantedPairing(cloudLinkId = "   ", restoredUri = restored))
    }

    @Test
    fun `a reveal that produced no uri names no row`() {
        assertNull(HiddenVaultDecisions.transplantedPairing(cloudLinkId = "link-abc", restoredUri = null))
        assertNull(HiddenVaultDecisions.transplantedPairing(cloudLinkId = "link-abc", restoredUri = ""))
        assertNull(HiddenVaultDecisions.transplantedPairing(cloudLinkId = "link-abc", restoredUri = "   "))
    }

    @Test
    fun `the vault's own uri can never take the pairing`() {
        // The vault writes app-private files, and one of those keying a SYNCED row would claim a
        // device copy no gallery can open.
        assertNull(HiddenVaultDecisions.transplantedPairing("link-abc", "file:///data/vault/a1b2c3__100.jpg"))
    }

    // ── which vaulted row the device contradicts ─────────────────────────────────────────────────

    @Test
    fun `a vaulted row whose file is back takes its Drive pairing with it`() {
        // The photo this exists for: the file is on the device and the row still filters its Drive
        // copy out of every listing, so the photo is in neither place until the row says SYNCED.
        assertEquals(
            SyncStatus.SYNCED,
            HiddenVaultDecisions.revealedRowStatus(
                status = SyncStatus.HIDDEN, hasCloudCopy = true, deviceFileExists = true,
            ),
        )
    }

    @Test
    fun `a vaulted row with no Drive copy comes back as a device photo`() {
        assertEquals(
            SyncStatus.LOCAL_ONLY,
            HiddenVaultDecisions.revealedRowStatus(
                status = SyncStatus.HIDDEN, hasCloudCopy = false, deviceFileExists = true,
            ),
        )
    }

    @Test
    fun `a vaulted row whose file is gone is a hidden photo and stays one`() {
        // The direction that must never be got wrong: this row's file is in the vault, so answering
        // a live status here would put a photo the user hid back in front of everyone.
        assertNull(
            HiddenVaultDecisions.revealedRowStatus(
                status = SyncStatus.HIDDEN, hasCloudCopy = true, deviceFileExists = false,
            ),
        )
        assertNull(
            HiddenVaultDecisions.revealedRowStatus(
                status = SyncStatus.HIDDEN, hasCloudCopy = false, deviceFileExists = false,
            ),
        )
    }

    @Test
    fun `a row the vault never claimed is left to whatever owns it`() {
        // Every other status belongs to a photo the vault has nothing to say about, file present or
        // not: a live status written over one would be this sweep overruling the row's real owner.
        for (status in SyncStatus.entries.filter { it != SyncStatus.HIDDEN }) {
            for (hasCloudCopy in listOf(true, false)) {
                for (exists in listOf(true, false)) {
                    assertNull(
                        "touched $status (cloud=$hasCloudCopy, onDevice=$exists)",
                        HiddenVaultDecisions.revealedRowStatus(status, hasCloudCopy, exists),
                    )
                }
            }
        }
    }

    @Test
    fun `only a file the device still holds moves a vaulted row at all`() {
        for (hasCloudCopy in listOf(true, false)) {
            for (exists in listOf(true, false)) {
                val repaired = HiddenVaultDecisions.revealedRowStatus(
                    SyncStatus.HIDDEN, hasCloudCopy, exists,
                )
                assertEquals("repaired for (cloud=$hasCloudCopy, onDevice=$exists)", exists, repaired != null)
            }
        }
    }

    // ── which vaulted row the sweep drops as a leftover ──────────────────────────────────────────

    @Test
    fun `a vaulted row whose photo is already back under a live row, and the vault has let go, is dropped`() {
        // The measured strand: a reveal wrote the SYNCED row on the uri the device minted for the
        // restored file and left the HIDDEN row keyed on the dead pre-hide uri, whichever row
        // getByCloudId returned. Its own file is gone, the vault no longer holds it, and a live sibling
        // proves the photo is back, so the leftover is dropped. Demoting would put a second row on one
        // cloud copy.
        assertEquals(
            VaultedRowFix.CLEAR_STRANDED,
            HiddenVaultDecisions.vaultedRowFix(
                status = SyncStatus.HIDDEN, hasCloudCopy = true, cloudHasLivePairing = true,
                vaultStillHoldsPhoto = false, ownFileExists = false,
            ),
        )
    }

    @Test
    fun `a photo the vault still holds is left hidden even when a duplicate pairs its cloud copy`() {
        // The safety regression this guards: a byte-identical duplicate the user never hid can be paired
        // to the same Drive copy by content hash, minting a SYNCED sibling. That reads as a live pairing,
        // but the vault still holds the photo the user hid, so clearing here would put it back in front
        // of everyone. The vault's own record overrules the sibling: the row stays hidden.
        assertEquals(
            VaultedRowFix.LEAVE,
            HiddenVaultDecisions.vaultedRowFix(
                status = SyncStatus.HIDDEN, hasCloudCopy = true, cloudHasLivePairing = true,
                vaultStillHoldsPhoto = true, ownFileExists = false,
            ),
        )
    }

    @Test
    fun `a live sibling drops the row even where its own uri also resolves`() {
        // Drop wins over demote wherever both could fire: two live rows on one cloud copy is exactly the
        // state the strand fix exists to avoid, so the leftover goes rather than being revived beside it.
        assertEquals(
            VaultedRowFix.CLEAR_STRANDED,
            HiddenVaultDecisions.vaultedRowFix(
                status = SyncStatus.HIDDEN, hasCloudCopy = true, cloudHasLivePairing = true,
                vaultStillHoldsPhoto = false, ownFileExists = true,
            ),
        )
    }

    @Test
    fun `a vaulted backed-up photo with no live sibling stays hidden`() {
        // A correctly vaulted backed-up photo has a Drive copy and no device file, and no other row
        // carries its cloud id. Only a SYNCED or LOCAL_ONLY sibling counts as live, so a lingering cloud
        // stub for the same id (which the sweep's query excludes) never reaches this as a live pairing.
        assertEquals(
            VaultedRowFix.LEAVE,
            HiddenVaultDecisions.vaultedRowFix(
                status = SyncStatus.HIDDEN, hasCloudCopy = true, cloudHasLivePairing = false,
                vaultStillHoldsPhoto = false, ownFileExists = false,
            ),
        )
    }

    @Test
    fun `a vaulted row whose own file is back is revived in place`() {
        // The other shape of the strand: the reveal left the file on this row's own uri. With the vault
        // letting go and no live sibling to defer to, its own file being present returns it to a live
        // status.
        assertEquals(
            VaultedRowFix.REVEAL_SYNCED,
            HiddenVaultDecisions.vaultedRowFix(
                status = SyncStatus.HIDDEN, hasCloudCopy = true, cloudHasLivePairing = false,
                vaultStillHoldsPhoto = false, ownFileExists = true,
            ),
        )
        assertEquals(
            VaultedRowFix.REVEAL_LOCAL_ONLY,
            HiddenVaultDecisions.vaultedRowFix(
                status = SyncStatus.HIDDEN, hasCloudCopy = false, cloudHasLivePairing = false,
                vaultStillHoldsPhoto = false, ownFileExists = true,
            ),
        )
    }

    @Test
    fun `a device-only vaulted photo is never dropped as a strand`() {
        // No Drive copy means no cloud id to share, so a live-sibling reading can only be spurious; the
        // hasCloudCopy guard makes sure a device-only vaulted photo whose file is gone is left hidden,
        // not cleared, whatever the sibling flag says.
        assertEquals(
            VaultedRowFix.LEAVE,
            HiddenVaultDecisions.vaultedRowFix(
                status = SyncStatus.HIDDEN, hasCloudCopy = false, cloudHasLivePairing = true,
                vaultStillHoldsPhoto = false, ownFileExists = false,
            ),
        )
    }

    @Test
    fun `the vault holding the photo overrules every other signal`() {
        // Whatever the sibling and own-file flags read, a vault that still holds the photo leaves the row
        // hidden. The one authority that cannot be spoofed by a duplicate is the vault's own record.
        for (cloud in listOf(true, false)) {
            for (sibling in listOf(true, false)) {
                for (own in listOf(true, false)) {
                    assertEquals(
                        "cleared/revived a still-held photo (cloud=$cloud, sibling=$sibling, own=$own)",
                        VaultedRowFix.LEAVE,
                        HiddenVaultDecisions.vaultedRowFix(SyncStatus.HIDDEN, cloud, sibling, vaultStillHoldsPhoto = true, ownFileExists = own),
                    )
                }
            }
        }
    }

    @Test
    fun `a row the vault never claimed is left whatever the rest of the table says`() {
        for (status in SyncStatus.entries.filter { it != SyncStatus.HIDDEN }) {
            for (cloud in listOf(true, false)) {
                for (sibling in listOf(true, false)) {
                    for (held in listOf(true, false)) {
                        for (own in listOf(true, false)) {
                            assertEquals(
                                "touched $status (cloud=$cloud, sibling=$sibling, held=$held, own=$own)",
                                VaultedRowFix.LEAVE,
                                HiddenVaultDecisions.vaultedRowFix(status, cloud, sibling, held, own),
                            )
                        }
                    }
                }
            }
        }
    }

    // ── which hidden photos a confirm may actually publish ───────────────────────────────────────

    @Test
    fun `a hide whose original is gone is published as hidden`() {
        val map = mapOf("v1" to "s1", "v2" to "s2")
        val split = HiddenVaultDecisions.confirmSplit(listOf("v1", "v2"), map) { false }
        assertEquals(listOf("v1", "v2"), split.publish)
        assertTrue(split.survivors.isEmpty())
    }

    @Test
    fun `an original still on the device is kept out of the hidden set`() {
        // The false-privacy this guards: the delete reported success but the file is still there, so
        // publishing would file the photo as hidden in this app while every other gallery app shows it.
        val map = mapOf("v1" to "s1", "v2" to "s2", "v3" to "s3")
        val present = setOf("s2")
        val split = HiddenVaultDecisions.confirmSplit(listOf("v1", "v2", "v3"), map) { it in present }
        assertEquals(listOf("v2"), split.survivors)
        assertEquals(listOf("v1", "v3"), split.publish)
    }

    @Test
    fun `a vault uri the journal never mapped a source for is published, never diverted`() {
        // No recorded source is no handle to check, and a check that cannot answer must not block an
        // ordinary hide, so the photo publishes exactly as it did before this guard existed.
        val split = HiddenVaultDecisions.confirmSplit(listOf("v1"), emptyMap()) { true }
        assertEquals(listOf("v1"), split.publish)
        assertTrue(split.survivors.isEmpty())
    }

    @Test
    fun `every original still present keeps the whole batch visible`() {
        val map = mapOf("v1" to "s1", "v2" to "s2")
        val split = HiddenVaultDecisions.confirmSplit(listOf("v1", "v2"), map) { true }
        assertEquals(listOf("v1", "v2"), split.survivors)
        assertTrue(split.publish.isEmpty())
    }

    @Test
    fun `nothing to confirm splits into nothing`() {
        val split = HiddenVaultDecisions.confirmSplit(emptyList(), mapOf("v1" to "s1")) { true }
        assertTrue(split.publish.isEmpty())
        assertTrue(split.survivors.isEmpty())
    }
}
