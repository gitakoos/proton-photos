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
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The whole point of the vault's crash recovery is this one decision, so it is checked over its
 * COMPLETE input space rather than on a few examples: every combination of blob present, record
 * present, journal present and source present/absent/unknown. Runs WITHOUT a device — the decision
 * is plain boolean logic over facts the caller gathers.
 *
 * The property that matters most has its own test: a vault copy is only ever destroyed when the
 * MediaStore original is PROVEN to still exist.
 */
class HiddenVaultLeftoverTest {

    private val vaultUri = "file:///data/user/0/eu.akoos.photos/files/hidden/abc__1700000000000.jpg"
    private val sourceUri = "content://media/external/images/media/42"

    private fun leftover(
        blobPresent: Boolean,
        recorded: Boolean,
        journalled: Boolean,
        sourcePresent: Boolean?,
    ) = HiddenLeftover(vaultUri, blobPresent, recorded, journalled, sourcePresent)

    /** Every point of the input space, and the action each one must produce. */
    private fun expected(
        blobPresent: Boolean,
        recorded: Boolean,
        journalled: Boolean,
        sourcePresent: Boolean?,
    ): HiddenLeftoverAction = when {
        // A hide that never confirmed.
        journalled && !blobPresent -> HiddenLeftoverAction.FORGET
        journalled && sourcePresent == true -> HiddenLeftoverAction.DISCARD
        journalled -> HiddenLeftoverAction.CONFIRM
        // No journal: only an unreferenced blob needs anything.
        blobPresent && !recorded -> HiddenLeftoverAction.ADOPT
        else -> HiddenLeftoverAction.NONE
    }

    @Test
    fun `every combination of blob record journal and source has one settled answer`() {
        var checked = 0
        for (blobPresent in listOf(true, false)) {
            for (recorded in listOf(true, false)) {
                for (journalled in listOf(true, false)) {
                    for (sourcePresent in listOf(true, false, null)) {
                        val case = leftover(blobPresent, recorded, journalled, sourcePresent)
                        assertEquals(
                            "blob=$blobPresent recorded=$recorded journalled=$journalled source=$sourcePresent",
                            expected(blobPresent, recorded, journalled, sourcePresent),
                            HiddenVaultLeftovers.decide(case),
                        )
                        checked++
                    }
                }
            }
        }
        assertEquals(24, checked)
    }

    @Test
    fun `a copy is only destroyed when the original is proven to still exist`() {
        // The one irreversible outcome. Anything short of a positive check on the source has to keep
        // the bytes, since the vault copy may be the last one in existence.
        for (blobPresent in listOf(true, false)) {
            for (recorded in listOf(true, false)) {
                for (journalled in listOf(true, false)) {
                    for (sourcePresent in listOf(true, false, null)) {
                        val action = HiddenVaultLeftovers.decide(
                            leftover(blobPresent, recorded, journalled, sourcePresent),
                        )
                        if (action == HiddenLeftoverAction.DISCARD) {
                            assertTrue(sourcePresent == true)
                            assertTrue(journalled)
                            assertTrue(blobPresent)
                        }
                    }
                }
            }
        }
    }

    @Test
    fun `an unconfirmed hide whose original survived drops the copy`() {
        // The delete never ran: the photo is still in the gallery, so the vault copy is a stray
        // duplicate. This is the outcome the cancel path already produced.
        assertEquals(
            HiddenLeftoverAction.DISCARD,
            HiddenVaultLeftovers.decide(
                leftover(blobPresent = true, recorded = false, journalled = true, sourcePresent = true),
            ),
        )
    }

    @Test
    fun `an unconfirmed hide whose original is gone becomes visible`() {
        // The delete DID run, so the vault copy is the only copy. Publishing it is what turns a
        // silently lost photo back into one the user can see and restore.
        assertEquals(
            HiddenLeftoverAction.CONFIRM,
            HiddenVaultLeftovers.decide(
                leftover(blobPresent = true, recorded = false, journalled = true, sourcePresent = false),
            ),
        )
    }

    @Test
    fun `an unconfirmed hide with an uncheckable original is kept`() {
        // An unreadable or malformed source proves nothing, and discarding on a guess destroys bytes.
        assertEquals(
            HiddenLeftoverAction.CONFIRM,
            HiddenVaultLeftovers.decide(
                leftover(blobPresent = true, recorded = false, journalled = true, sourcePresent = null),
            ),
        )
    }

    @Test
    fun `a blob with no record at all is adopted`() {
        assertEquals(
            HiddenLeftoverAction.ADOPT,
            HiddenVaultLeftovers.decide(
                leftover(blobPresent = true, recorded = false, journalled = false, sourcePresent = null),
            ),
        )
    }

    @Test
    fun `a healthy vault entry is left alone`() {
        assertEquals(
            HiddenLeftoverAction.NONE,
            HiddenVaultLeftovers.decide(
                leftover(blobPresent = true, recorded = true, journalled = false, sourcePresent = null),
            ),
        )
    }

    @Test
    fun `a journal entry whose bytes are gone is forgotten whatever the original did`() {
        for (sourcePresent in listOf(true, false, null)) {
            for (recorded in listOf(true, false)) {
                assertEquals(
                    HiddenLeftoverAction.FORGET,
                    HiddenVaultLeftovers.decide(
                        leftover(blobPresent = false, recorded = recorded, journalled = true, sourcePresent = sourcePresent),
                    ),
                )
            }
        }
    }

    @Test
    fun `a recorded uri with neither blob nor journal is not this reconciliation's business`() {
        assertEquals(
            HiddenLeftoverAction.NONE,
            HiddenVaultLeftovers.decide(
                leftover(blobPresent = false, recorded = true, journalled = false, sourcePresent = null),
            ),
        )
    }

    // ── Journal flattening ──────────────────────────────────────────────────────────────────────

    @Test
    fun `a journal entry round-trips through the flattened form`() {
        val entry = HiddenVaultLeftovers.journalEntry(vaultUri, sourceUri)
        assertEquals(mapOf(vaultUri to sourceUri), HiddenVaultLeftovers.parseJournal(setOf(entry)))
    }

    @Test
    fun `a journal entry with no separator keeps a blank source`() {
        // Blank reads as "cannot be checked", which the decision resolves towards keeping the bytes.
        assertEquals(mapOf(vaultUri to ""), HiddenVaultLeftovers.parseJournal(setOf(vaultUri)))
    }

    @Test
    fun `a journal entry naming no vault file is dropped`() {
        assertEquals(emptyMap<String, String>(), HiddenVaultLeftovers.parseJournal(setOf("|$sourceUri", "")))
    }

    // ── Candidate gathering ─────────────────────────────────────────────────────────────────────

    @Test
    fun `leftovers cover both a blob with no journal and a journal with no blob`() {
        val orphan = "file:///hidden/orphan.jpg"
        val vanished = "file:///hidden/vanished.jpg"
        val healthy = "file:///hidden/healthy.jpg"
        val result = HiddenVaultLeftovers.leftovers(
            blobUris = setOf(orphan, healthy),
            recordedUris = setOf(healthy),
            journal = mapOf(vanished to sourceUri),
            sourcePresence = mapOf(sourceUri to false),
        ).associateBy { it.privateUri }

        assertEquals(setOf(orphan, healthy, vanished), result.keys)
        assertEquals(HiddenLeftoverAction.ADOPT, HiddenVaultLeftovers.decide(result.getValue(orphan)))
        assertEquals(HiddenLeftoverAction.NONE, HiddenVaultLeftovers.decide(result.getValue(healthy)))
        assertEquals(HiddenLeftoverAction.FORGET, HiddenVaultLeftovers.decide(result.getValue(vanished)))
    }

    @Test
    fun `a source that could not be checked stays unknown`() {
        val result = HiddenVaultLeftovers.leftovers(
            blobUris = setOf(vaultUri),
            recordedUris = emptySet(),
            journal = mapOf(vaultUri to sourceUri),
            sourcePresence = emptyMap(),
        ).single()
        assertEquals(null, result.sourcePresent)
        assertEquals(HiddenLeftoverAction.CONFIRM, HiddenVaultLeftovers.decide(result))
    }

    @Test
    fun `a recorded uri that is neither on disk nor journalled is not a candidate`() {
        val result = HiddenVaultLeftovers.leftovers(
            blobUris = emptySet(),
            recordedUris = setOf(vaultUri),
            journal = emptyMap(),
            sourcePresence = emptyMap(),
        )
        assertTrue(result.isEmpty())
    }

    // ── What a sign-out destroys ────────────────────────────────────────────────────────────────

    @Test
    fun `a healthy vault counts each photo once`() {
        val second = "file:///hidden/second.jpg"
        assertEquals(
            2,
            HiddenVaultLeftovers.vaultedCount(
                blobUris = setOf(vaultUri, second),
                recordedVaultUris = setOf(vaultUri, second),
            ),
        )
    }

    @Test
    fun `a file the index never gained is still counted`() {
        // The interrupted hide: the original is already deleted, so this file is the only copy of the
        // photo in existence, and the wipe takes it whether or not anything refers to it.
        assertEquals(
            1,
            HiddenVaultLeftovers.vaultedCount(blobUris = setOf(vaultUri), recordedVaultUris = emptySet()),
        )
    }

    @Test
    fun `the count never sits below the number of files the wipe deletes`() {
        // The one property the sign-out warning rests on, over every combination of three files being
        // present on disk and being listed in the index.
        val universe = listOf(vaultUri, "file:///hidden/b.jpg", "file:///hidden/c.jpg")
        var checked = 0
        for (blobMask in 0 until 8) {
            for (recordMask in 0 until 8) {
                val blobs = universe.filterIndexedTo(HashSet()) { i, _ -> blobMask and (1 shl i) != 0 }
                val recorded = universe.filterIndexedTo(HashSet()) { i, _ -> recordMask and (1 shl i) != 0 }
                val count = HiddenVaultLeftovers.vaultedCount(blobs, recorded)
                assertTrue(
                    "blobs=$blobs recorded=$recorded count=$count",
                    count >= blobs.size,
                )
                checked++
            }
        }
        assertEquals(64, checked)
    }

    @Test
    fun `an empty vault warns about nothing`() {
        assertEquals(0, HiddenVaultLeftovers.vaultedCount(emptySet(), emptySet()))
    }
}
