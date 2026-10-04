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
import eu.akoos.photos.data.db.dao.LabelUsage
import eu.akoos.photos.data.db.dao.PhotoLabelDao
import eu.akoos.photos.data.db.dao.TestDatabase
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import me.proton.core.accountmanager.domain.AccountManager
import me.proton.core.domain.entity.UserId
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Labels created without a photo, and the lock that keeps the user's label edits out of a Drive run. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PhotoLabelNamesRepositoryTest {

    private lateinit var db: TestDatabase
    private lateinit var dao: PhotoLabelDao
    private lateinit var repo: PhotoLabelRepository
    private val primary = MutableStateFlow<UserId?>(ACCOUNT)
    private val lock = MutableStateFlow(false)

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
        val gate = object : LabelWriteGate {
            override val locked: Flow<Boolean> = lock
        }
        repo = PhotoLabelRepository(dao, accountManager, gate)
    }

    @After
    fun tearDown() = db.close()

    private suspend fun assertLocked(edit: suspend () -> Unit) {
        try {
            edit()
        } catch (e: LabelsLockedException) {
            return
        }
        fail("the edit went through while the labels were locked")
    }

    private suspend fun usage() = repo.observeLabelUsage().first().associate { it.label to it.count }

    @Test
    fun `a created label is listed with no photos`() = runTest {
        assertEquals(LabelCreation.Created("summer trip"), repo.createLabel("  summer   trip "))

        assertEquals(mapOf("summer trip" to 0), usage())
    }

    @Test
    fun `a label that exists, on a photo or without one, is not created twice`() = runTest {
        repo.setLabels(LINK, listOf("Beach"))
        repo.createLabel("Sun")

        assertEquals(LabelCreation.Exists("Beach"), repo.createLabel("BEACH"))
        assertEquals(LabelCreation.Exists("Sun"), repo.createLabel("sun"))
        assertEquals(mapOf("Beach" to 1, "Sun" to 0), usage())
    }

    @Test
    fun `text that is no label is invalid`() = runTest {
        assertEquals(LabelCreation.Invalid, repo.createLabel("   "))
        assertEquals(LabelCreation.Invalid, repo.createLabel(" , "))
    }

    @Test
    fun `putting a created label on a photo counts the photo and keeps one entry`() = runTest {
        repo.createLabel("Beach")

        repo.addLabels(listOf(LINK), listOf("beach"))

        assertEquals(listOf<LabelUsage>(LabelUsage("beach", "Beach", 1)), repo.observeLabelUsage().first())
    }

    @Test
    fun `a created label gives its spelling to later uses`() = runTest {
        repo.createLabel("Café")

        repo.setLabels(LINK, listOf("CAFE"))

        assertEquals(listOf("Café"), dao.labelsOfPhoto(ACCOUNT.id, LINK).map { it.label })
    }

    @Test
    fun `renaming a label also renames it when it has no photo`() = runTest {
        repo.createLabel("Old")

        repo.renameLabel("Old", "New")

        assertEquals(mapOf("New" to 0), usage())
    }

    @Test
    fun `deleting a label forgets it whether or not it has photos`() = runTest {
        repo.createLabel("Empty")
        repo.setLabels(LINK, listOf("Used"))
        repo.createLabel("Used")

        repo.deleteLabel("Empty")
        repo.deleteLabel("Used")

        assertEquals(emptyMap<String, Int>(), usage())
    }

    @Test
    fun `several labels are deleted together, with and without photos`() = runTest {
        repo.setLabels(LINK, listOf("One", "Two", "Keep"))
        repo.setLabels(LINK_2, listOf("One"))
        repo.createLabel("Empty")

        val removed = repo.deleteLabels(listOf("One", "TWO", "empty"))

        assertEquals(3, removed)
        assertEquals(mapOf("Keep" to 1), usage())
        assertEquals(listOf("Keep"), dao.labelsOfPhoto(ACCOUNT.id, LINK).map { it.label })
    }

    @Test
    fun `deleting several labels is refused while the labels are locked`() = runTest {
        repo.setLabels(LINK, listOf("One"))
        lock.value = true

        assertLocked { repo.deleteLabels(listOf("One")) }
        assertEquals(listOf("One"), dao.labelsOfPhoto(ACCOUNT.id, LINK).map { it.label })
    }

    @Test
    fun `the photo count of one label is the photos that carry it`() = runTest {
        repo.setLabels(LINK, listOf("Beach"))
        repo.setLabels(LINK_2, listOf("Beach", "Sun"))

        assertEquals(setOf(LINK, LINK_2), repo.photoKeysOfLabel("beach"))
        assertEquals(setOf(LINK_2), repo.photoKeysOfLabel("SUN"))
    }

    @Test
    fun `the snapshot lists only the labels that no photo carries`() = runTest {
        repo.createLabel("Empty")
        repo.createLabel("Used")
        repo.setLabels(LINK, listOf("Used"))

        assertEquals(listOf("Empty"), repo.snapshotNames(ACCOUNT))
    }

    @Test
    fun `a restore adds the saved labels the account does not have`() = runTest {
        repo.setLabels(LINK, listOf("Beach"))
        repo.createLabel("Sun")
        val saved = LabelSnapshot(
            deviceId = OTHER_DEVICE, deviceLabel = "Phone", createdAtMillis = 1L, photos = emptyList(),
            names = listOf("BEACH", "sun", "Mountain", "  "),
        )

        val counts = repo.applySnapshot(ACCOUNT, saved, OWN, now = 5_000L)

        assertEquals(LabelRestoreCounts(photos = 0, names = 1), counts)
        assertEquals(mapOf("Beach" to 1, "Sun" to 0, "Mountain" to 0), usage())
    }

    @Test
    fun `a guest's created labels are adopted when an account signs in`() = runTest {
        primary.value = null
        repo.createLabel("Guest label")
        primary.value = ACCOUNT

        assertEquals(mapOf("Guest label" to 0), usage())
    }

    @Test
    fun `wiping an account forgets its created labels`() = runTest {
        repo.createLabel("Empty")

        repo.clearForUser(ACCOUNT)

        assertTrue(dao.namesForUser(ACCOUNT.id).isEmpty())
    }

    @Test
    fun `while the labels are locked the user's edits are refused`() = runTest {
        repo.setLabels(LINK, listOf("Beach"))
        lock.value = true

        assertLocked { repo.createLabel("New") }
        assertLocked { repo.setLabels(LINK, listOf("x")) }
        assertLocked { repo.addLabels(listOf(LINK), listOf("x")) }
        assertLocked { repo.removeLabel(LINK, "Beach") }
        assertLocked { repo.renameLabel("Beach", "Sea") }
        assertLocked { repo.deleteLabel("Beach") }
        assertEquals(listOf("Beach"), dao.labelsOfPhoto(ACCOUNT.id, LINK).map { it.label })
    }

    @Test
    fun `the app's own moves and the restore itself go through while locked`() = runTest {
        repo.setLabels(URI, listOf("Beach"))
        lock.value = true

        repo.rekey(URI, LINK)
        val counts = repo.applySnapshot(
            ACCOUNT,
            LabelSnapshot(
                deviceId = OTHER_DEVICE, deviceLabel = "Phone", createdAtMillis = 1L,
                photos = listOf(LabelSnapshotPhoto(LINK_2, listOf("Sun"))),
            ),
            OWN, now = 5_000L,
        )

        assertEquals(listOf("Beach"), dao.labelsOfPhoto(ACCOUNT.id, LINK).map { it.label })
        assertEquals(1, counts.photos)
        assertEquals(lock.value, repo.observeLocked().first())
    }

    @Test
    fun `an unlocked gate lets edits through again`() = runTest {
        lock.value = true
        lock.value = false

        assertEquals(LabelCreation.Created("Back"), repo.createLabel("Back"))
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
