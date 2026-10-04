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

/** What the Drive copy reads from and writes into the label tables: a saved copy comes back as it was. */
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

    private fun saved(deviceId: String, vararg photos: LabelSnapshotPhoto, names: List<String> = emptyList()) =
        LabelSnapshot(deviceId = deviceId, deviceLabel = "Phone", createdAtMillis = 1L, photos = photos.toList(), names = names)

    private fun photo(key: String, vararg labels: String) = LabelSnapshotPhoto(key, labels.toList())

    private suspend fun restore(snapshot: LabelSnapshot, from: String = OTHER_DEVICE) =
        repo.applySnapshot(ACCOUNT, snapshot.copy(deviceId = from), ownDeviceId = OWN, now = 5_000L)

    @Test
    fun `the snapshot lists every labelled photo with its labels in order`() = runTest {
        repo.setLabels(LINK, listOf("zebra", "apple"))
        repo.setLabels(LINK_2, listOf("mango"))

        val photos = repo.snapshotPhotos(ACCOUNT).associate { it.key to it.labels }

        assertEquals(mapOf(LINK to listOf("zebra", "apple"), LINK_2 to listOf("mango")), photos)
    }

    @Test
    fun `a photo that lost its last label is not in the snapshot`() = runTest {
        repo.setLabels(LINK, listOf("a"))
        repo.removeLabel(LINK, "a")

        assertTrue(repo.snapshotPhotos(ACCOUNT).isEmpty())
    }

    @Test
    fun `another account's labels are not in the snapshot`() = runTest {
        dao.insertIgnore(
            listOf(eu.akoos.photos.data.db.entity.PhotoLabelEntity("someone-else", LINK, "x", "x", 1L)),
        )

        assertTrue(repo.snapshotPhotos(ACCOUNT).isEmpty())
    }

    @Test
    fun `a restore brings back a label that was removed from a photo since`() = runTest {
        repo.setLabels(LINK, listOf("keep", "lost"))
        val copy = saved(OTHER_DEVICE, photo(LINK, "keep", "lost"))
        repo.removeLabel(LINK, "lost")

        val counts = restore(copy)

        assertEquals(1, counts.photos)
        assertEquals(listOf("keep", "lost"), labels(LINK))
    }

    @Test
    fun `a restore keeps a label added since while there is room`() = runTest {
        repo.setLabels(LINK, listOf("saved", "added since"))

        restore(saved(OTHER_DEVICE, photo(LINK, "saved")))

        assertEquals(listOf("saved", "added since"), labels(LINK))
    }

    @Test
    fun `a restore leaves photos the copy does not list alone`() = runTest {
        repo.setLabels(LINK_2, listOf("mine"))

        restore(saved(OTHER_DEVICE, photo(LINK, "a")))

        assertEquals(listOf("mine"), labels(LINK_2))
        assertEquals(listOf("a"), labels(LINK))
    }

    @Test
    fun `restoring the same copy twice changes nothing the second time`() = runTest {
        val copy = saved(OTHER_DEVICE, photo(LINK, "a"), photo(LINK_2, "b"), names = listOf("Empty"))

        val first = restore(copy)
        val second = restore(copy)

        assertEquals(LabelRestoreCounts(photos = 2, names = 1), first)
        assertEquals(LabelRestoreCounts(photos = 0, names = 0), second)
    }

    @Test
    fun `device keys come back only from this install's own copy`() = runTest {
        val photos = arrayOf(photo(URI, "a"), photo(LINK, "b"))

        restore(saved(OTHER_DEVICE, *photos), from = OTHER_DEVICE)
        assertTrue(labels(URI).isEmpty())
        assertEquals(listOf("b"), labels(LINK))

        restore(saved(OWN, *photos), from = OWN)
        assertEquals(listOf("a"), labels(URI))
    }

    @Test
    fun `a restored label takes the spelling the account already uses`() = runTest {
        repo.setLabels(LINK, listOf("Beach"))

        restore(saved(OTHER_DEVICE, photo(LINK_2, "BEACH")))

        assertEquals(listOf("Beach"), labels(LINK_2))
    }

    @Test
    fun `labels written before signing in are adopted before a restore`() = runTest {
        primary.value = null
        repo.setLabels(LINK, listOf("guest label"))
        primary.value = ACCOUNT

        restore(saved(OTHER_DEVICE, photo(LINK_2, "x")))

        assertEquals(listOf("guest label"), labels(LINK))
    }

    @Test
    fun `a restore brings back a label created without a photo that was deleted since`() = runTest {
        repo.createLabel("Empty")
        val copy = saved(OTHER_DEVICE, names = repo.snapshotNames(ACCOUNT))
        repo.deleteLabel("Empty")

        val counts = restore(copy)

        assertEquals(1, counts.names)
        assertEquals(listOf("Empty"), dao.namesForUser(ACCOUNT.id).map { it.label })
    }

    @Test
    fun `a restore does not duplicate a label the account already has`() = runTest {
        repo.setLabels(LINK, listOf("Beach"))
        repo.createLabel("Sun")

        val counts = restore(saved(OTHER_DEVICE, names = listOf("beach", "SUN")))

        assertEquals(0, counts.names)
    }

    private companion object {
        val ACCOUNT = UserId("account-1")
        const val OWN = "0b5f3c2e-1111-4a4a-9c9c-123456789abc"
        const val OTHER_DEVICE = "7d1e9a40-2222-4b4b-8d8d-abcdefabcdef"
        const val LINK = "link-1"
        const val LINK_2 = "link-2"
        const val URI = "content://media/external/images/media/1"
    }
}
