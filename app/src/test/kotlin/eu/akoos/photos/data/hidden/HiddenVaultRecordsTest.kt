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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins what changing one vault file writes into each of the vault's four records.
 *
 * The uri of the file IS the key of every one of them, so a rename that moved the file without moving
 * the records would leave the photo's name, its folder and its cloud twin pointing at somewhere it no
 * longer is. The recorded name gets the most attention here, because it is the one a reveal writes the
 * file back to the device under: carry the old one and the rename the user made in the vault is
 * silently undone the moment the photo comes back out.
 */
class HiddenVaultRecordsTest {

    private val old = "file:///data/user/0/eu.akoos.photos/files/hidden/3f1a__1783507135000.jpg"
    private val new = "file:///data/user/0/eu.akoos.photos/files/hidden/Beach trip__1783507135000.jpg"
    private val other = "file:///data/user/0/eu.akoos.photos/files/hidden/c1d2__1783507135000.png"

    private fun records() = VaultRecords(
        index = setOf(old, other),
        names = setOf("$old|IMG_1234.jpg", "$other|IMG_9999.png"),
        folders = setOf("$old|DCIM/Camera", "$other|Pictures/Trips"),
        cloudIds = setOf("$old|link-1", "$other|link-2"),
    )

    // ── the name a person types ──────────────────────────────────────────────────────────────────

    @Test
    fun `a typed name loses the extension the user may have typed and keeps the file's own`() {
        assertEquals("Beach trip.jpg", HiddenVaultRecords.recordedName("Beach trip", "jpg"))
        assertEquals("Beach trip.jpg", HiddenVaultRecords.recordedName("Beach trip.png", "jpg"))
        assertEquals("Beach trip.jpg", HiddenVaultRecords.recordedName("Beach trip", ".jpg"))
    }

    @Test
    fun `a name the filesystem would refuse is made safe rather than rejected`() {
        assertEquals("a_b_c.jpg", HiddenVaultRecords.recordedName("a/b:c", "jpg"))
        assertEquals("___.jpg", HiddenVaultRecords.recordedName("?|*", "jpg"))
    }

    @Test
    fun `a name that reduces to nothing still names the file`() {
        assertEquals("renamed.jpg", HiddenVaultRecords.recordedName("   ", "jpg"))
        assertEquals("renamed.jpg", HiddenVaultRecords.recordedName(".", "jpg"))
    }

    @Test
    fun `a file with no extension is recorded without one`() {
        assertEquals("Beach trip", HiddenVaultRecords.recordedName("Beach trip", ""))
    }

    // ── a rename ─────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `a rename writes the NEW name, which is what a reveal uses`() {
        val after = HiddenVaultRecords.renamed(records(), old, new, "Beach trip.jpg")

        assertEquals(setOf("$new|Beach trip.jpg", "$other|IMG_9999.png"), after.names)
    }

    @Test
    fun `a rename moves the index entry and every per-uri record onto the new uri`() {
        val after = HiddenVaultRecords.renamed(records(), old, new, "Beach trip.jpg")

        assertEquals(setOf(new, other), after.index)
        assertEquals(setOf("$new|DCIM/Camera", "$other|Pictures/Trips"), after.folders)
        assertEquals(setOf("$new|link-1", "$other|link-2"), after.cloudIds)
    }

    @Test
    fun `a rename leaves every other photo's records untouched`() {
        val after = HiddenVaultRecords.renamed(records(), old, new, "Beach trip.jpg")

        assertTrue("$other|IMG_9999.png" in after.names)
        assertTrue("$other|Pictures/Trips" in after.folders)
        assertTrue("$other|link-2" in after.cloudIds)
    }

    @Test
    fun `a photo the vault recorded no name for gains one when it is renamed`() {
        // A vault file adopted by the reconciliation has no name record at all. Without this the
        // rename would land nowhere and the reveal would name the file after its private code.
        val bare = VaultRecords(index = setOf(old), names = emptySet(), folders = emptySet(), cloudIds = emptySet())

        val after = HiddenVaultRecords.renamed(bare, old, new, "Beach trip.jpg")

        assertEquals(setOf("$new|Beach trip.jpg"), after.names)
    }

    @Test
    fun `a rename that records no folder or cloud twin invents neither`() {
        val bare = VaultRecords(index = setOf(old), names = emptySet(), folders = emptySet(), cloudIds = emptySet())

        val after = HiddenVaultRecords.renamed(bare, old, new, "Beach trip.jpg")

        assertEquals(emptySet<String>(), after.folders)
        assertEquals(emptySet<String>(), after.cloudIds)
    }

    // ── a move made for some other reason than a rename ──────────────────────────────────────────

    @Test
    fun `a move with no new name carries the recorded one across`() {
        // A capture-date edit restamps the file name, which moves the uri; the name the user gave the
        // photo has nothing to do with that and must survive it.
        val after = HiddenVaultRecords.renamed(records(), old, new, displayName = null)

        assertEquals(setOf("$new|IMG_1234.jpg", "$other|IMG_9999.png"), after.names)
        assertEquals(setOf(new, other), after.index)
    }

    @Test
    fun `a move with no new name invents none for a photo that had none`() {
        // The file's own name is a private code, and recording that would put it on the user's device
        // at the next reveal.
        val bare = VaultRecords(index = setOf(old), names = emptySet(), folders = emptySet(), cloudIds = emptySet())

        val after = HiddenVaultRecords.renamed(bare, old, new, displayName = null)

        assertEquals(emptySet<String>(), after.names)
    }

    // ── a copy ───────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `a copy is added as a hidden photo of its own and leaves the source whole`() {
        val copy = "file:///data/user/0/eu.akoos.photos/files/hidden/9b7e__1783507135000.jpg"

        val after = HiddenVaultRecords.copied(records(), old, copy, "Beach trip.jpg")

        assertEquals(setOf(old, other, copy), after.index)
        assertTrue("$copy|Beach trip.jpg" in after.names)
        assertTrue("$old|IMG_1234.jpg" in after.names)
    }

    @Test
    fun `a copy returns to the same folder the photo it was made from came from`() {
        val copy = "file:///data/user/0/eu.akoos.photos/files/hidden/9b7e__1783507135000.jpg"

        val after = HiddenVaultRecords.copied(records(), old, copy, "Beach trip.jpg")

        assertTrue("$copy|DCIM/Camera" in after.folders)
        assertTrue("$old|DCIM/Camera" in after.folders)
    }

    @Test
    fun `a copy claims no cloud twin`() {
        // One cloud link names one photo. A second vault entry claiming it would have both reveals
        // transplant the same sync row, leaving one of the two paired to a file that is not its own.
        val copy = "file:///data/user/0/eu.akoos.photos/files/hidden/9b7e__1783507135000.jpg"

        val after = HiddenVaultRecords.copied(records(), old, copy, "Beach trip.jpg")

        assertEquals(records().cloudIds, after.cloudIds)
    }

    @Test
    fun `a copy of a photo with no recorded folder records none of its own`() {
        val copy = "file:///data/user/0/eu.akoos.photos/files/hidden/9b7e__1783507135000.jpg"
        val bare = VaultRecords(index = setOf(old), names = emptySet(), folders = emptySet(), cloudIds = emptySet())

        val after = HiddenVaultRecords.copied(bare, old, copy, "Beach trip.jpg")

        assertEquals(emptySet<String>(), after.folders)
        assertEquals(setOf("$copy|Beach trip.jpg"), after.names)
    }

    // ── the separator the records are built on ───────────────────────────────────────────────────

    @Test
    fun `a recorded value containing the separator is carried whole`() {
        val piped = VaultRecords(
            index = setOf(old),
            names = setOf("$old|holiday|2026.jpg"),
            folders = setOf("$old|Pictures/a|b"),
            cloudIds = emptySet(),
        )

        val after = HiddenVaultRecords.renamed(piped, old, new, displayName = null)

        assertEquals(setOf("$new|holiday|2026.jpg"), after.names)
        assertEquals(setOf("$new|Pictures/a|b"), after.folders)
    }

    @Test
    fun `a uri that merely starts like another one is not moved with it`() {
        // The records are matched on the whole key, so a photo whose uri is a prefix of another's
        // cannot drag its neighbour along.
        val prefix = "$old.jpg"
        val shared = VaultRecords(
            index = setOf(old, prefix),
            names = setOf("$old|IMG_1234.jpg", "$prefix|IMG_5678.jpg"),
            folders = emptySet(),
            cloudIds = emptySet(),
        )

        val after = HiddenVaultRecords.renamed(shared, old, new, "Beach trip.jpg")

        assertEquals(setOf("$new|Beach trip.jpg", "$prefix|IMG_5678.jpg"), after.names)
        assertEquals(setOf(new, prefix), after.index)
    }

    // ── the stores the vault does not own but the photo answers under ────────────────────────────

    @Test
    fun `the heart follows the file and leaves nothing on the uri it left`() {
        val after = HiddenVaultRecords.favoritesAfterMove(setOf(old, other), old, new)

        assertEquals(setOf(other, new), after)
    }

    @Test
    fun `a photo that was never a favourite changes nothing`() {
        assertNull(HiddenVaultRecords.favoritesAfterMove(setOf(other), old, new))
        assertNull(HiddenVaultRecords.favoritesAfterMove(emptySet(), old, new))
    }

    @Test
    fun `a folder pinned to the photo follows it`() {
        val covers = setOf("Camera|$old", "Trips|$other")

        val after = HiddenVaultRecords.coversAfterMove(covers, old, new)

        assertEquals(setOf("Camera|$new", "Trips|$other"), after)
    }

    @Test
    fun `every folder pinned to the same photo follows it`() {
        val covers = setOf("Camera|$old", "Trips|$old")

        val after = HiddenVaultRecords.coversAfterMove(covers, old, new)

        assertEquals(setOf("Camera|$new", "Trips|$new"), after)
    }

    @Test
    fun `a folder name holding the separator keeps its own name`() {
        // The cover map reads its uri from the RIGHT of the last separator, so a folder literally
        // called "Trip|2026" survives the move whole rather than collapsing to "Trip".
        val covers = setOf("Trip|2026|$old")

        val after = HiddenVaultRecords.coversAfterMove(covers, old, new)

        assertEquals(setOf("Trip|2026|$new"), after)
    }

    @Test
    fun `a photo no folder is pinned to changes nothing`() {
        assertNull(HiddenVaultRecords.coversAfterMove(setOf("Trips|$other"), old, new))
        assertNull(HiddenVaultRecords.coversAfterMove(emptySet(), old, new))
    }

    // ── which vaulted photos still have a Drive copy ─────────────────────────────────────────────

    @Test
    fun `every vault photo with a recorded Drive copy is named`() {
        assertEquals(setOf(old, other), HiddenVaultRecords.pairedUris(records().cloudIds))
        assertEquals(emptySet<String>(), HiddenVaultRecords.pairedUris(emptySet()))
    }

    @Test
    fun `a record that names no Drive copy raises no badge`() {
        // The badge means a Drive copy is there, so an entry that cannot name one has to answer no
        // rather than be counted for having an entry at all.
        val entries = setOf("$old|link-1", "$other|", "|link-3")

        assertEquals(setOf(old), HiddenVaultRecords.pairedUris(entries))
    }

    @Test
    fun `a record with no separator at all names no Drive copy`() {
        // A truncated write leaves a bare uri behind. It states a vault file and no cloud id, so it is
        // read as the second thing rather than the first: raising the badge on it would promise a Drive
        // copy for a photo whose only bytes are the vault file.
        assertEquals(setOf(old), HiddenVaultRecords.pairedUris(setOf("$old|link-1", other, "")))
    }

    @Test
    fun `a rename carries the pairing to the photo's new path`() {
        val after = HiddenVaultRecords.renamed(records(), old, new, "Beach trip.jpg")

        val paired = HiddenVaultRecords.pairedUris(after.cloudIds)
        assertTrue("the badge follows the file", new in paired)
        assertTrue("nothing is left on the path the photo left", old !in paired)
    }
}
