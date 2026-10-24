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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The change times and deletions every label edit records on the phone, and the shared backup's load and write
 * back, so a merge on the phone ends where the same merge ends on the computer.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PhotoLabelSyncRepositoryTest {

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

    @Test
    fun `an edit records when the photo changed, and setting the same list records nothing new`() = runTest {
        repo.setLabels(LINK, listOf("Beach"))
        val first = dao.stateOf(ACCOUNT.id, LINK)
        assertNotNull(first)
        dao.upsertStates(listOf(first!!.copy(changedAt = 1L)))
        repo.setLabels(LINK, listOf("Beach"))
        assertEquals(1L, dao.stateOf(ACCOUNT.id, LINK)?.changedAt)
    }

    @Test
    fun `a photo whose labels were all taken off stays as an empty list`() = runTest {
        repo.setLabels(LINK, listOf("Beach"))
        repo.removeLabel(LINK, "beach")
        val state = repo.loadSyncState(ACCOUNT)
        assertEquals(emptyList<String>(), state.photos[LINK]?.labels)
        assertTrue((state.photos[LINK]?.changedAt ?: 0L) > 0L)
    }

    @Test
    fun `a delete is recorded, and putting the label back clears it`() = runTest {
        repo.setLabels(LINK, listOf("Beach", "Sun"))
        repo.deleteLabel("beach")
        assertEquals("Beach", dao.deletedForUser(ACCOUNT.id).single().label)
        repo.setLabels(LINK_2, listOf("Beach"))
        assertTrue(dao.deletedForUser(ACCOUNT.id).isEmpty())
    }

    @Test
    fun `a rename records the old label as deleted`() = runTest {
        repo.setLabels(LINK, listOf("Old"))
        repo.renameLabel("old", "New")
        assertEquals(listOf("New"), repo.loadSyncState(ACCOUNT).photos[LINK]?.labels)
        assertEquals("Old", dao.deletedForUser(ACCOUNT.id).single().label)
    }

    @Test
    fun `a photo backed up since keeps its change time under its new key`() = runTest {
        repo.setLabels(DEVICE_URI, listOf("Beach"))
        dao.upsertStates(listOf(dao.stateOf(ACCOUNT.id, DEVICE_URI)!!.copy(changedAt = 77L)))
        repo.rekey(DEVICE_URI, LINK)
        assertNull(dao.stateOf(ACCOUNT.id, DEVICE_URI))
        assertEquals(77L, dao.stateOf(ACCOUNT.id, LINK)?.changedAt)
    }

    @Test
    fun `a photo going into the vault leaves no change time behind`() = runTest {
        repo.setLabels(LINK, listOf("Beach"))
        repo.deleteForPhotos(listOf(LINK))
        assertNull(dao.stateOf(ACCOUNT.id, LINK))
    }

    @Test
    fun `a merge written back reads back the same`() = runTest {
        repo.setLabels(LINK, listOf("Here"))
        repo.createLabel("Later")
        val before = repo.loadSyncState(ACCOUNT)
        val after = before.deepCopy()
        LabelCopyMerge.takeIn(
            after,
            LabelSnapshot(
                deviceId = PC, deviceLabel = "Desk", createdAtMillis = Long.MAX_VALUE / 2,
                photos = listOf(
                    LabelSnapshotPhoto(LINK, listOf("There"), Long.MAX_VALUE / 4),
                    LabelSnapshotPhoto(LINK_2, listOf("New"), 5L),
                ),
                names = listOf("Idea"),
                nameTimes = mapOf("Idea" to 6L),
                deleted = listOf(LabelSnapshotDeleted("Later", Long.MAX_VALUE / 4)),
            ),
            OWN,
        )
        repo.saveSyncState(ACCOUNT, before, after)
        val reread = repo.loadSyncState(ACCOUNT)
        assertEquals(after.photos, reread.photos)
        assertEquals(after.names, reread.names)
        assertEquals(after.deleted, reread.deleted)
        assertEquals(listOf("There"), dao.labelsOfPhoto(ACCOUNT.id, LINK).map { it.label })
    }

    @Test
    fun `the phone and the computer end with the same labels through Drive`() = runTest {
        val onPc = LabelSyncState()
        LabelCopyMerge.setLabels(onPc, "k0", listOf("Family"), 10)
        LabelCopyMerge.setLabels(onPc, "k2", listOf("Old"), 5)
        repo.setLabels("k2", listOf("Old"))
        repo.setLabels("k1", listOf("Trip"))
        repo.deleteLabel("Old")

        val fromPhone = LabelSnapshotCodec.decode(
            LabelSnapshotCodec.encode(LabelCopyMerge.forDrive(repo.loadSyncState(ACCOUNT), OWN, "Phone", 30)),
        )!!
        val fromPc = LabelSnapshotCodec.decode(LabelSnapshotCodec.encode(LabelCopyMerge.forDrive(onPc, PC, "Desk", 20)))!!
        val before = repo.loadSyncState(ACCOUNT)
        val after = before.deepCopy()
        LabelCopyMerge.takeIn(after, fromPc, OWN)
        repo.saveSyncState(ACCOUNT, before, after)
        LabelCopyMerge.takeIn(onPc, fromPhone, PC)

        val phoneView = LabelCopyMerge.view(repo.loadSyncState(ACCOUNT))
        assertEquals(mapOf("k0" to listOf("Family"), "k1" to listOf("Trip"), "k2" to emptyList()), phoneView)
        assertEquals(LabelCopyMerge.view(onPc), phoneView)
    }

    private companion object {
        val ACCOUNT = UserId("account-1")
        const val OWN = "22222222-2222-4222-8222-222222222222"
        const val PC = "11111111-1111-4111-8111-111111111111"
        const val LINK = "linkAAA=="
        const val LINK_2 = "linkBBB=="
        const val DEVICE_URI = "content://media/external/images/media/7"
    }
}
