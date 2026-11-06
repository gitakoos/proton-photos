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

import eu.akoos.photos.domain.entity.LocalMediaItem
import eu.akoos.photos.domain.repository.LocalMediaRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import me.proton.core.accountmanager.domain.AccountManager
import me.proton.core.domain.entity.UserId
import org.junit.Assert.assertEquals
import org.junit.Test

/** Turning a folder's album on reads what is on Drive only once the upload lock is held. */
class FolderAlbumBackfillTest {

    private val userId = UserId("u")

    @Test
    fun `the photos on Drive are read inside the backfill, not before it waits for the lock`() = runTest {
        val accounts = mockk<AccountManager> { coEvery { getPrimaryUserId() } returns flowOf(userId) }
        val item = LocalMediaItem(
            uri = "content://media/1", dateTaken = 1L, displayName = "a.jpg", mimeType = "image/jpeg",
            sizeBytes = 1L, bucketName = "PfpTest", width = 1, height = 1,
        )
        val local = mockk<LocalMediaRepository> { coEvery { queryByBucket("PfpTest") } returns listOf(item) }
        val pairs = mockk<DrivePairLookup>()
        val order = mutableListOf<String>()
        coEvery { pairs.pairs(userId, any(), any()) } answers {
            order += "pairs"
            mapOf(item.uri to "link-1")
        }
        val upload = mockk<UploadPendingUseCase>()
        var seen: Map<String, String>? = null
        coEvery { upload.backfillFolderAlbum(userId, "PfpTest", any()) } coAnswers {
            order += "locked"
            seen = thirdArg<suspend () -> Map<String, String>>().invoke()
            1
        }

        FolderAlbumBackfill(accounts, upload, local, pairs, CoroutineScope(Dispatchers.Unconfined))
            .onAlbumTurnedOn("PfpTest")

        assertEquals(listOf("locked", "pairs"), order)
        assertEquals(mapOf(item.uri to "link-1"), seen)
        coVerify(exactly = 1) { pairs.pairs(userId, any(), any()) }
    }
}
