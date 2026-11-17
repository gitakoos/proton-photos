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

package eu.akoos.photos.data.upload

import eu.akoos.photos.data.db.dao.AlbumPhotoMembershipDao
import eu.akoos.photos.data.db.dao.AlbumPhotoMembershipLite
import eu.akoos.photos.data.repository.drive.CloudTrashOutcome
import eu.akoos.photos.domain.entity.SyncState
import eu.akoos.photos.domain.entity.SyncStatus
import eu.akoos.photos.domain.repository.DrivePhotoRepository
import eu.akoos.photos.domain.usecase.InMemoryShortCloudCopyStore
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import me.proton.core.domain.entity.UserId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A short Drive copy goes to the trash only once its whole replacement is up and holds its albums. */
class ShortCopyReplacerTest {

    private val userId = UserId("u1")
    private val uri = "content://media/external/images/media/1"
    private val cloud = mockk<DrivePhotoRepository>(relaxed = true)
    private val membership = mockk<AlbumPhotoMembershipDao>()
    private val store = InMemoryShortCloudCopyStore()
    private val replacer = ShortCopyReplacer(cloud, membership, store)

    private fun inAlbums(vararg albums: String) {
        every { membership.observeAlbumIdsForPhotos(setOf("short")) } returns
            flowOf(albums.map { AlbumPhotoMembershipLite(it, "short") })
    }

    private fun trashAnswers(trashed: Boolean) {
        coEvery { cloud.deleteFiles(userId, listOf("short")) } returns
            if (trashed) CloudTrashOutcome(setOf("short"), emptySet()) else CloudTrashOutcome(emptySet(), setOf("short"))
    }

    @Test
    fun `the short copy goes to the trash after the whole one joins its albums`() = runTest {
        store.replacements[uri] = "short"
        inAlbums("album-1")
        coEvery { cloud.addPhotosToAlbum(userId, "album-1", listOf("whole")) } returns
            DrivePhotoRepository.AddPhotosToAlbumResult(listOf("whole"), emptyList())
        trashAnswers(trashed = true)

        replacer.replaced(userId, uri, "whole")

        coVerify(exactly = 1) { cloud.addPhotosToAlbum(userId, "album-1", listOf("whole")) }
        coVerify(exactly = 1) { cloud.deleteFiles(userId, listOf("short")) }
        assertNull(store.replacements[uri])
    }

    @Test
    fun `an album that does not take the whole copy keeps the short one where it is`() = runTest {
        store.replacements[uri] = "short"
        inAlbums("album-1")
        coEvery { cloud.addPhotosToAlbum(userId, "album-1", listOf("whole")) } returns
            DrivePhotoRepository.AddPhotosToAlbumResult(emptyList(), listOf("whole"))

        replacer.replaced(userId, uri, "whole")

        coVerify(exactly = 0) { cloud.deleteFiles(any(), any()) }
        assertEquals("short", store.replacements[uri])
    }

    @Test
    fun `a trash that fails is tried again once the row reads backed up whole`() = runTest {
        store.replacements[uri] = "short"
        inAlbums()
        trashAnswers(trashed = false)
        replacer.replaced(userId, uri, "whole")
        assertEquals("short", store.replacements[uri])

        trashAnswers(trashed = true)
        val row = SyncState(uri, "whole", "h", null, SyncStatus.SYNCED, 0L, 1L, 1L, 10L)
        replacer.retryPending(userId) { row }

        coVerify(exactly = 2) { cloud.deleteFiles(userId, listOf("short")) }
        assertNull(store.replacements[uri])
    }

    @Test
    fun `nothing is trashed without a recorded replacement, or while the row is not backed up whole`() = runTest {
        replacer.replaced(userId, uri, "whole")
        store.replacements[uri] = "short"
        replacer.retryPending(userId) { SyncState(uri, null, "h", null, SyncStatus.LOCAL_ONLY, 0L, null, null, 10L) }

        coVerify(exactly = 0) { cloud.deleteFiles(any(), any()) }
    }

    @Test
    fun `a delete held back for a short copy is handed on once`() = runTest {
        store.deleteAfterWhole += uri

        assertTrue(replacer.deleteAfterWholeBackup(uri))
        replacer.deleteHandedOn(uri)
        assertFalse(replacer.deleteAfterWholeBackup(uri))
    }
}
