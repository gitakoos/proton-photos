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
    fun `skips when the cloud copy is already scanned`() {
        // The cloud copy already has its own faces; re-keying would collide.
        assertEquals(emptyList<Pair<String, String>>(), backupRekeyPairs(listOf(devA to cloudA), scannedKeys = setOf(devA, cloudA)))
    }

    @Test
    fun `skips a self pair`() {
        assertEquals(emptyList<Pair<String, String>>(), backupRekeyPairs(listOf(cloudA to cloudA), scannedKeys = setOf(cloudA)))
    }

    @Test
    fun `filters a mixed batch to only the movable ones`() {
        val pairs = listOf(devA to cloudA, devB to cloudB)
        // devA scanned + cloudA fresh -> move; devB not scanned -> skip.
        assertEquals(listOf(devA to cloudA), backupRekeyPairs(pairs, scannedKeys = setOf(devA)))
    }
}
