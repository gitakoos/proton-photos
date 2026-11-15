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
package eu.akoos.photos.data.db.encryption

import eu.akoos.photos.data.db.encryption.SqlCipherBudget.MIB
import org.junit.Assert.assertEquals
import org.junit.Test

/** The page cache and pool sizes the encrypted database runs with. */
class SqlCipherBudgetTest {

    private val gib = 1024 * MIB

    @Test
    fun theCacheIsAFortyEighthOfTheMemoryBetween32And128Mb() {
        assertEquals(128 * MIB, SqlCipherBudget.cacheBytes(8 * gib, lowRamDevice = false))
        assertEquals(128 * MIB, SqlCipherBudget.cacheBytes(6 * gib, lowRamDevice = false))
        assertEquals(4 * gib / 48, SqlCipherBudget.cacheBytes(4 * gib, lowRamDevice = false))
        assertEquals(32 * MIB, SqlCipherBudget.cacheBytes(1 * gib, lowRamDevice = false))
        assertEquals(32 * MIB, SqlCipherBudget.cacheBytes(0, lowRamDevice = false))
    }

    @Test
    fun aLowRamDeviceKeeps16Mb() {
        assertEquals(16 * MIB, SqlCipherBudget.cacheBytes(8 * gib, lowRamDevice = true))
    }

    @Test
    fun thePoolMatchesThePlatformSqliteWithinTwoToTen() {
        assertEquals(4, SqlCipherBudget.poolSize(null))
        assertEquals(4, SqlCipherBudget.poolSize(4))
        assertEquals(2, SqlCipherBudget.poolSize(1))
        assertEquals(6, SqlCipherBudget.poolSize(6))
        assertEquals(10, SqlCipherBudget.poolSize(32))
    }

    @Test
    fun theCacheSizePragmaIsNegativeKib() {
        assertEquals(-131072L, SqlCipherBudget.cacheSizePragma(128 * MIB))
    }
}
