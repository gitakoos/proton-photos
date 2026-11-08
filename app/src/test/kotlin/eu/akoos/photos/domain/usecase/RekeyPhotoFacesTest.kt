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

import eu.akoos.photos.data.db.entity.FaceCurationTimeEntity
import org.junit.Assert.assertEquals
import org.junit.Test

class RekeyPhotoFacesTest {

    private val devA = "content://media/1"
    private val devB = "content://media/2"
    private val cloudA = "linkA"
    private val cloudB = "linkB"

    @Test
    fun `moves a scanned device photo onto its fresh cloud key`() {
        val pairs = listOf(devA to cloudA)
        assertEquals(listOf(devA to cloudA), backupRekeyPairs(pairs, scannedKeys = setOf(devA)))
    }

    @Test
    fun `skips when the device copy was never scanned`() {
        // Nothing to move; the Synced copy is scanned normally by the walk.
        assertEquals(emptyList<Pair<String, String>>(), backupRekeyPairs(listOf(devA to cloudA), scannedKeys = emptySet()))
    }

    @Test
    fun `keeps the pair when the cloud copy is already scanned, so the two sets merge`() {
        // A restored face snapshot scanned the cloud copy; skipping would leave the device set beside it.
        assertEquals(listOf(devA to cloudA), backupRekeyPairs(listOf(devA to cloudA), scannedKeys = setOf(devA, cloudA)))
    }

    @Test
    fun `skips a self pair`() {
        assertEquals(emptyList<Pair<String, String>>(), backupRekeyPairs(listOf(cloudA to cloudA), scannedKeys = setOf(cloudA)))
    }

    private fun time(name: String, ref: String, at: Long, kind: Int = FaceCurationTimeEntity.KIND_NOT_PERSON) =
        FaceCurationTimeEntity("acct", kind, name, ref, removed = false, changedAt = at)

    @Test
    fun `curation times move to their new refs, and one with nowhere to go is dropped`() {
        val rows = rekeyCurationTimes(
            moving = listOf(time("Bob", "$devA#0", 10L), time("Bob", "$devA#5", 20L)),
            present = emptyList(),
        ) { row -> if (row.ref == "$devA#0") "$cloudA#0" else null }

        assertEquals(listOf(time("Bob", "$cloudA#0", 10L)), rows)
    }

    @Test
    fun `a later time already on the new key is kept, an earlier one is replaced`() {
        val rows = rekeyCurationTimes(
            moving = listOf(time("Bob", "$devA#0", 10L), time("Eve", "$devA#0", 30L)),
            present = listOf(time("Bob", "$cloudA#0", 15L), time("Eve", "$cloudA#0", 25L)),
        ) { "$cloudA#0" }

        assertEquals(listOf(time("Eve", "$cloudA#0", 30L)), rows)
    }

    @Test
    fun `two times landing on one piece keep the later`() {
        val rows = rekeyCurationTimes(
            moving = listOf(time("Bob", "$devA#0", 40L), time("Bob", "$devA#1", 35L)),
            present = emptyList(),
        ) { "$cloudA#0" }

        assertEquals(listOf(time("Bob", "$cloudA#0", 40L)), rows)
    }

    @Test
    fun `filters a mixed batch to only the movable ones`() {
        val pairs = listOf(devA to cloudA, devB to cloudB)
        // devA scanned + cloudA fresh -> move; devB not scanned -> skip.
        assertEquals(listOf(devA to cloudA), backupRekeyPairs(pairs, scannedKeys = setOf(devA)))
        // devB scanned + cloudB scanned -> merge.
        assertEquals(pairs, backupRekeyPairs(pairs, scannedKeys = setOf(devA, devB, cloudB)))
    }
}
