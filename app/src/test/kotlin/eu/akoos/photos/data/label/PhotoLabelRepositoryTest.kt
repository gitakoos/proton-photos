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
import eu.akoos.photos.data.db.dao.LabelBulkResult
import eu.akoos.photos.data.db.dao.LabelUsage
import eu.akoos.photos.data.db.dao.PhotoLabelDao
import eu.akoos.photos.data.db.dao.TestDatabase
import eu.akoos.photos.data.db.entity.PhotoLocationEntity
import eu.akoos.photos.domain.entity.CloudPhoto
import eu.akoos.photos.domain.entity.GalleryItem
import eu.akoos.photos.domain.entity.LocalMediaItem
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import me.proton.core.accountmanager.domain.AccountManager
import me.proton.core.domain.entity.UserId
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PhotoLabelRepositoryTest {

    private lateinit var db: TestDatabase
    private lateinit var dao: PhotoLabelDao
    private lateinit var repo: PhotoLabelRepository
    private val primary = MutableStateFlow<UserId?>(null)

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

    private fun local(uri: String) = LocalMediaItem(uri, 1_000L, "a.jpg", "image/jpeg", 10L, "Camera")

    private fun cloud(linkId: String) =
        CloudPhoto(linkId, "share", "vol", 1L, "a.jpg", "image/jpeg", 10L, null, "rev")

    private suspend fun labels(photoKey: String, user: String) = dao.labelsOfPhoto(user, photoKey).map { it.label }

    @Test
    fun `a guest's labels are stored under the local owner`() = runTest {
        repo.setLabels(URI, listOf("beach"))

        assertEquals(GUEST, repo.currentUserKey())
        assertEquals(listOf("beach"), labels(URI, GUEST))
    }

    @Test
    fun `set labels normalises, dedupes and caps the list`() = runTest {
        primary.value = ACCOUNT
        repo.setLabels(URI, listOf(" summer   trip ", "Summer Trip", "a", "b", "c", "d", "e"))

        assertEquals(listOf("summer trip", "a", "b", "c", "d"), labels(URI, ACCOUNT.id))
    }

    @Test
    fun `a label the account already uses keeps its first spelling`() = runTest {
        primary.value = ACCOUNT
        repo.setLabels(URI, listOf("Beach"))
        repo.setLabels(LINK, listOf("BEACH", "sun"))
        repo.addLabels(listOf(URI_2), listOf("beach"))

        assertEquals(listOf("Beach", "sun"), labels(LINK, ACCOUNT.id))
        assertEquals(listOf("Beach"), labels(URI_2, ACCOUNT.id))
    }

    @Test
    fun `setting labels on a synced item folds its device-keyed labels onto the linkId`() = runTest {
        primary.value = ACCOUNT
        repo.setLabels(URI, listOf("old"))
        val synced = GalleryItem.Synced(cloud(LINK), local(URI))

        repo.setLabels(synced, listOf("new"))

        assertEquals(listOf("new"), labels(LINK, ACCOUNT.id))
        assertTrue("nothing stays under the device uri", labels(URI, ACCOUNT.id).isEmpty())
    }

    @Test
    fun `labels for a synced item read both keys and move the uri rows`() = runTest {
        primary.value = ACCOUNT
        repo.setLabels(LINK, listOf("cloud"))
        repo.setLabels(URI, listOf("device"))
        val synced = GalleryItem.Synced(cloud(LINK), local(URI))

        assertEquals(listOf("cloud", "device"), repo.labelsFor(synced))
        assertTrue(labels(URI, ACCOUNT.id).isEmpty())
        assertEquals(listOf("cloud", "device"), repo.labelsFor(synced))
    }

    @Test
    fun `reconcile moves every pending synced item and is a no-op afterwards`() = runTest {
        repo.setLabels(URI, listOf("a"))
        repo.setLabels(URI_2, listOf("b"))
        val items = listOf(
            GalleryItem.Synced(cloud(LINK), local(URI)),
            GalleryItem.LocalOnly(local(URI_2)),
        )

        assertEquals(1, repo.reconcileKeys(items))
        assertEquals(listOf("a"), labels(LINK, GUEST))
        assertEquals(listOf("b"), labels(URI_2, GUEST))
        assertEquals(0, repo.reconcileKeys(items))
    }

    @Test
    fun `bulk add reports photos the cap turned away`() = runTest {
        repo.setLabels(URI, listOf("a", "b", "c", "d", "e"))

        val result = repo.addLabels(listOf(URI, URI_2), listOf("trip", "Trip", " "))

        assertEquals(LabelBulkResult(changedPhotos = 1, cappedPhotos = 1), result)
        assertEquals(listOf("trip"), labels(URI_2, GUEST))
        assertEquals(LabelBulkResult(0, 0), repo.addLabels(listOf(URI), listOf(",")))
    }

    @Test
    fun `remove, rename and delete act on folded labels`() = runTest {
        repo.setLabels(URI, listOf("Café", "sun"))
        repo.setLabels(URI_2, listOf("cafe", "sea"))

        assertTrue(repo.removeLabel(URI, "SUN"))
        assertFalse(repo.removeLabel(URI, "missing"))
        assertTrue(repo.renameLabel("CAFE", "Coffee"))
        assertFalse("a comma is not a valid label", repo.renameLabel("sea", "a,b"))
        assertEquals(listOf("Coffee"), labels(URI, GUEST))

        assertEquals(1, repo.deleteLabel("SEA"))
        assertEquals(listOf("Coffee"), labels(URI_2, GUEST))
        assertEquals(listOf(LabelUsage("coffee", "Coffee", 2)), repo.observeLabelUsage().first())
    }

    @Test
    fun `the first write after sign-in adopts the guest's labels`() = runTest {
        repo.setLabels(URI, listOf("guest"))
        primary.value = ACCOUNT

        repo.addLabels(listOf(URI), listOf("mine"))

        assertEquals(listOf("guest", "mine"), labels(URI, ACCOUNT.id))
        assertFalse(dao.hasAnyForUser(GUEST))
    }

    @Test
    fun `observing upgrades in place on sign-in and carries the guest labels`() = runTest {
        repo.setLabels(URI, listOf("guest"))
        assertEquals(mapOf(URI to listOf("guest")), repo.observeLabelsByPhoto().first())

        primary.value = ACCOUNT

        assertEquals(mapOf(URI to listOf("guest")), repo.observeLabelsByPhoto().first())
        assertEquals(listOf("guest"), labels(URI, ACCOUNT.id))
        assertEquals(listOf("guest"), repo.observeLabelsOf(URI).first())
    }

    @Test
    fun `rekey for an account moves only that account's rows`() = runTest {
        primary.value = ACCOUNT
        repo.setLabels(URI, listOf("a"))

        assertEquals(1, repo.rekeyFor(ACCOUNT, URI, LINK))
        assertEquals(0, repo.rekey(LINK, LINK))
        assertEquals(listOf("a"), labels(LINK, ACCOUNT.id))
    }

    @Test
    fun `signing out wipes the account's labels and created labels and nobody else's`() = runTest {
        primary.value = ACCOUNT
        repo.setLabels(URI, listOf("mine"))
        repo.createLabel("Empty")
        dao.insertIgnore(listOf(eu.akoos.photos.data.db.entity.PhotoLabelEntity("other", URI, "theirs", "theirs", 1L)))
        dao.insertName(eu.akoos.photos.data.db.entity.PhotoLabelNameEntity("other", "kept", "Kept", 1L))

        repo.clearForUser(ACCOUNT)

        assertTrue(dao.allLabelsForUser(ACCOUNT.id).isEmpty())
        assertTrue(dao.namesForUser(ACCOUNT.id).isEmpty())
        assertEquals(listOf("theirs"), dao.labelsOfPhoto("other", URI).map { it.label })
        assertEquals(listOf("Kept"), dao.namesForUser("other").map { it.label })
    }

    @Test
    fun `after a sign-out the guest's labels are adopted again by the next account`() = runTest {
        primary.value = ACCOUNT
        repo.setLabels(LINK, listOf("first"))
        repo.clearForUser(ACCOUNT)
        primary.value = null
        repo.setLabels(URI_2, listOf("guest"))
        primary.value = ACCOUNT

        repo.setLabels(LINK, listOf("second"))

        assertEquals(listOf("guest"), labels(URI_2, ACCOUNT.id))
        assertEquals(listOf("second"), labels(LINK, ACCOUNT.id))
    }

    @Test
    fun `a photo hidden into the vault loses its labels under both keys whoever wrote them`() = runTest {
        primary.value = ACCOUNT
        repo.setLabels(URI, listOf("a"))
        repo.setLabels(LINK, listOf("b"))
        repo.setLabels(LINK_2, listOf("kept"))
        dao.insertIgnore(listOf(eu.akoos.photos.data.db.entity.PhotoLabelEntity("local", URI, "guest", "guest", 1L)))

        repo.deleteForPhotos(listOf(URI, LINK))

        assertTrue(labels(URI, ACCOUNT.id).isEmpty())
        assertTrue(labels(LINK, ACCOUNT.id).isEmpty())
        assertTrue(labels(URI, GUEST).isEmpty())
        assertEquals(listOf("kept"), labels(LINK_2, ACCOUNT.id))
    }

    @Test
    fun `the labels put on a device photo follow it to its Drive identity when it is uploaded`() = runTest {
        primary.value = ACCOUNT
        repo.setLabels(URI, listOf("trip", "sun"))

        repo.rekeyFor(ACCOUNT, URI, LINK)

        assertTrue(labels(URI, ACCOUNT.id).isEmpty())
        assertEquals(listOf("trip", "sun"), labels(LINK, ACCOUNT.id))
        assertEquals(listOf("trip", "sun"), repo.labelsFor(GalleryItem.Synced(cloud(LINK), local(URI))))
    }

    @Test
    fun `a Synced photo whose upload never moved its labels still reads and keeps them`() = runTest {
        primary.value = ACCOUNT
        repo.setLabels(URI, listOf("trip"))
        val synced = GalleryItem.Synced(cloud(LINK), local(URI))

        assertEquals(listOf("trip"), repo.labelsFor(synced))
        assertEquals(listOf("trip"), labels(LINK, ACCOUNT.id))
        assertTrue(labels(URI, ACCOUNT.id).isEmpty())
    }

    @Test
    fun `deleting for photos and clearing an account remove the rows`() = runTest {
        primary.value = ACCOUNT
        repo.setLabels(URI, listOf("a"))
        repo.setLabels(LINK, listOf("b"))
        repo.setLabels(URI_2, listOf("c"))

        assertEquals(2, repo.deleteForPhotos(listOf(URI, LINK)))
        assertEquals(0, repo.deleteForPhotos(emptyList()))
        assertEquals(listOf("c"), labels(URI_2, ACCOUNT.id))

        assertEquals(1, repo.clearForUser(ACCOUNT))
        assertFalse(dao.hasAnyForUser(ACCOUNT.id))
    }

    private companion object {
        val ACCOUNT = UserId("account-1")
        const val GUEST = PhotoLocationEntity.LOCAL_USER
        const val URI = "content://media/external/images/media/1"
        const val URI_2 = "content://media/external/images/media/2"
        const val LINK = "link-1"
        const val LINK_2 = "link-2"
    }
}
