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

package eu.akoos.photos.data.repository

import eu.akoos.photos.data.db.dao.SyncStateDao
import eu.akoos.photos.domain.entity.SyncStatus
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins that permission-granted reclaim paths batch CLOUD_ONLY updates in 500-URI slices (matching
 * [eu.akoos.photos.util.SQL_CHUNK_SIZE]) rather than one Room write per URI.
 */
class SyncStatusBatchUpdateTest {

    /** Same floor as [eu.akoos.photos.util.SQL_CHUNK_SIZE]; duplicated so this test stays public. */
    private val bindChunk = 500

    @Test
    fun `an empty uri list never touches the dao`() = runTest {
        val dao = mockk<SyncStateDao>(relaxed = true)
        val repo = SyncStateRepositoryImpl(dao)

        repo.updateStatusAndDeleteLocal(emptyList(), SyncStatus.CLOUD_ONLY)

        coVerify(exactly = 0) { dao.updateStatusByLocalUris(any(), any()) }
    }

    @Test
    fun `uris are updated in chunks that never exceed the bind limit`() = runTest {
        val dao = mockk<SyncStateDao>(relaxed = true)
        coEvery { dao.updateStatusByLocalUris(any(), any()) } returns 0
        val repo = SyncStateRepositoryImpl(dao)
        val uris = (1..1_001).map { "content://media/external/images/media/$it" }

        repo.updateStatusAndDeleteLocal(uris, SyncStatus.CLOUD_ONLY)

        val chunks = mutableListOf<List<String>>()
        coVerify { dao.updateStatusByLocalUris(capture(chunks), SyncStatus.CLOUD_ONLY) }
        assertEquals(listOf(500, 500, 1), chunks.map { it.size })
        assertTrue(chunks.all { it.size <= bindChunk })
        assertEquals(uris.size, chunks.sumOf { it.size })
    }
}
