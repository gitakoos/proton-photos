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

package eu.akoos.photos.data.label

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import eu.akoos.photos.data.db.dao.PhotoLabelDao
import eu.akoos.photos.data.db.dao.TestDatabase
import eu.akoos.photos.data.db.entity.PhotoLabelEntity
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import me.proton.core.accountmanager.domain.AccountManager
import me.proton.core.domain.entity.UserId
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** What the Drive copy reads from and writes into the label tables. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PhotoLabelSnapshotRepositoryTest {

    private lateinit var db: TestDatabase
    private lateinit var dao: PhotoLabelDao
    private lateinit var repo: PhotoLabelRepository
    private val primary = MutableStateFlow<UserId?>(ACCOUNT)

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, TestDatabase::class.java)
            .allowMainThreadQueries()
            .setQueryExecutor { it.run() }
            .build()
        dao = db.photoLabelDao()
        val accountManager = mockk<AccountManager>()
        every { accountManager.getPrimaryUserId() } returns primary
        repo = PhotoLabelRepository(dao, accountManager)
    }

    @After
    fun tearDown() = db.close()

    private suspend fun labels(key: String) = dao.labelsOfPhoto(ACCOUNT.id, key).map { it.label }

    private fun snapshot(deviceId: String, vararg photos: LabelSnapshotPhoto) =
        LabelSnapshot(deviceId = deviceId, deviceLabel = "Phone", createdAtMillis = 1L, photos = photos.toList())

    private fun photo(key: String, at: Long, vararg labels: String) = LabelSnapshotPhoto(key, at, labels.toList())

    @Test
    fun `the snapshot lists every labelled photo with its labels in order`() = runTest {
        repo.setLabels(LINK, listOf("zebra", "apple"))
        repo.setLabels(LINK_2, listOf("mango"))

        val photos = repo.snapshotPhotos(ACCOUNT).associateBy { it.key }

        assertEquals(listOf("zebra", "apple"), photos.getValue(LINK).labels)
        assertEquals(listOf("mango"), photos.getValue(LINK_2).labels)
        assertTrue(photos.getValue(LINK).changedAt > 0L)
    }

    @Test
    fun `a photo whose last label was removed stays in the snapshot as an empty list`() = runTest {
        repo.setLabels(LINK, listOf("a"))
        repo.removeLabel(LINK, "a")

        val photo = repo.snapshotPhotos(ACCOUNT).single()

        assertEquals(LINK, photo.key)
        assertTrue(photo.labels.isEmpty())
    }

    @Test
    fun `a labelled photo without a recorded time reads as its newest label write`() = runTest {
        dao.insertIgnore(
            listOf(
                PhotoLabelEntity(ACCOUNT.id, LINK, "a", "a", 100L),
                PhotoLabelEntity(ACCOUNT.id, LINK, "b", "b", 300L),
            ),
        )

        assertEquals(300L, repo.snapshotPhotos(ACCOUNT).single().changedAt)
    }

    @Test
    fun `another account's labels are not in the snapshot`() = runTest {
        dao.insertIgnore(listOf(PhotoLabelEntity("someone-else", LINK, "x", "x", 1L)))
        dao.touch("someone-else", LINK, 1L)

        assertTrue(repo.snapshotPhotos(ACCOUNT).isEmpty())
    }

    @Test
    fun `a newer saved list replaces the held one and keeps its time`() = runTest {
        repo.setLabels(LINK, listOf("old"))

        val changed = repo.applySnapshot(
            ACCOUNT,
            snapshot(OTHER_DEVICE, photo(LINK, FUTURE, "fresh", "beach")),
            ownDeviceId = OWN,
            now = FUTURE,
        )

        assertEquals(1, changed.photos)
        assertEquals(listOf("fresh", "beach"), labels(LINK))
        assertEquals(FUTURE, dao.statesForUser(ACCOUNT.id).single().changedAt)
    }

    @Test
    fun `an older saved list leaves the held one alone`() = runTest {
        repo.setLabels(LINK, listOf("mine"))

        val changed = repo.applySnapshot(ACCOUNT, snapshot(OTHER_DEVICE, photo(LINK, 1L, "theirs")), OWN, now = 5_000L)

        assertEquals(0, changed.photos)
        assertEquals(listOf("mine"), labels(LINK))
    }

    @Test
    fun `a saved removal that is newer clears the held labels`() = runTest {
        repo.setLabels(LINK, listOf("a"))

        repo.applySnapshot(ACCOUNT, snapshot(OTHER_DEVICE, photo(LINK, FUTURE)), OWN, now = FUTURE)

        assertTrue(labels(LINK).isEmpty())
    }

    @Test
    fun `restoring twice changes nothing the second time`() = runTest {
        val saved = snapshot(OTHER_DEVICE, photo(LINK, 4_000L, "a"), photo(LINK_2, 4_000L))

        assertEquals(2, repo.applySnapshot(ACCOUNT, saved, OWN, now = 5_000L).photos)
        assertEquals(0, repo.applySnapshot(ACCOUNT, saved, OWN, now = 5_000L).photos)
    }

    @Test
    fun `device keys come back only from this install's own file`() = runTest {
        val photos = arrayOf(photo(URI, 4_000L, "a"), photo(LINK, 4_000L, "b"))

        repo.applySnapshot(ACCOUNT, snapshot(OTHER_DEVICE, *photos), OWN, now = 5_000L)
        assertTrue(labels(URI).isEmpty())
        assertEquals(listOf("b"), labels(LINK))

        repo.applySnapshot(ACCOUNT, snapshot(OWN, *photos), OWN, now = 5_000L)
        assertEquals(listOf("a"), labels(URI))
    }

    @Test
    fun `a restored label takes the spelling the account already uses`() = runTest {
        repo.setLabels(LINK, listOf("Beach"))

        repo.applySnapshot(ACCOUNT, snapshot(OTHER_DEVICE, photo(LINK_2, 4_000L, "BEACH")), OWN, now = 5_000L)

        assertEquals(listOf("Beach"), labels(LINK_2))
    }

    @Test
    fun `labels written before signing in are adopted before a restore merges`() = runTest {
        primary.value = null
        repo.setLabels(LINK, listOf("guest label"))
        primary.value = ACCOUNT

        repo.applySnapshot(ACCOUNT, snapshot(OTHER_DEVICE, photo(LINK_2, 4_000L, "x")), OWN, now = 5_000L)

        assertEquals(listOf("guest label"), labels(LINK))
    }

    private companion object {
        val ACCOUNT = UserId("account-1")
        val FUTURE = System.currentTimeMillis() + 60_000L
        const val OWN = "0b5f3c2e-1111-4a4a-9c9c-123456789abc"
        const val OTHER_DEVICE = "7d1e9a40-2222-4b4b-8d8d-abcdefabcdef"
        const val LINK = "link-1"
        const val LINK_2 = "link-2"
        const val URI = "content://media/external/images/media/1"
    }
}
