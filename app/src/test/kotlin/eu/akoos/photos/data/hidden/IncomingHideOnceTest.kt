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

package eu.akoos.photos.data.hidden

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import androidx.datastore.preferences.core.edit
import androidx.test.core.app.ApplicationProvider
import eu.akoos.photos.data.db.dao.LocalTagDao
import eu.akoos.photos.data.db.dao.UploadAlbumTargetDao
import eu.akoos.photos.data.preferences.SettingsKeys
import eu.akoos.photos.data.preferences.settingsDataStore
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import me.proton.core.accountmanager.domain.AccountManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * A hide another device asked for, taken in by more than one live screen at once.
 *
 * Each screen runs the timeline's hide in the same order: claim the photo, take over a copy an earlier run left
 * pending or copy it, journal it, ask the system to delete the original. [consume] walks that order against the
 * real journal, vault and preferences, so these pin what one photo ends up as: one vault copy and one delete
 * request however many screens take it in, and no copy left behind by a declined prompt.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class IncomingHideOnceTest {

    /** The device's media index: a source uri resolves while its id is in [present]. */
    class FakeMedia : ContentProvider() {
        override fun onCreate() = true
        override fun getType(uri: Uri): String? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?) = 0
        override fun delete(uri: Uri, selection: String?, args: Array<out String>?) = 0
        override fun query(
            uri: Uri,
            projection: Array<out String>?,
            selection: String?,
            args: Array<out String>?,
            sort: String?,
        ): Cursor {
            val cursor = MatrixCursor(arrayOf("_id"))
            val id = uri.lastPathSegment
            if (id != null && id in present) cursor.addRow(arrayOf<Any>(id.toLong()))
            return cursor
        }

        companion object {
            val present: MutableSet<String> = java.util.Collections.synchronizedSet(mutableSetOf())
        }
    }

    private lateinit var context: Context
    private lateinit var storage: HiddenStorageManager
    private lateinit var journal: HiddenVaultJournal
    private val deleteRequests = AtomicInteger()

    private val source = "content://$AUTHORITY/media/42"
    private val link = "linkP02"

    @Before
    fun setUp(): Unit = runBlocking {
        context = ApplicationProvider.getApplicationContext()
        context.settingsDataStore.edit { it.clear() }
        storage = HiddenStorageManager(context)
        File(context.filesDir, "hidden").deleteRecursively()
        Robolectric.setupContentProvider(FakeMedia::class.java, AUTHORITY)
        FakeMedia.present.clear()
        FakeMedia.present += "42"
        val albumTargets = mockk<UploadAlbumTargetDao>()
        coEvery { albumTargets.getAll() } returns emptyList()
        val tags = mockk<LocalTagDao>()
        coEvery { tags.getUserTagsCsv(any()) } returns null
        val accounts = mockk<AccountManager>()
        every { accounts.getPrimaryUserId() } returns flowOf(null)
        journal = HiddenVaultJournal(
            context = context,
            hiddenStorage = storage,
            localTagDao = tags,
            uploadAlbumTargetDao = albumTargets,
            syncStateRepo = mockk(relaxed = true),
            accountManager = accounts,
            photoLabels = mockk(relaxed = true),
            facePhotoRemoval = mockk(relaxed = true),
        )
        deleteRequests.set(0)
    }

    /**
     * One screen's run of the hide of [source], up to the delete prompt. Returns the vault copy it asked the
     * delete for, or null when another run owns the photo. [afterClaim] runs between the claim and the copy,
     * the stretch where two screens raced.
     */
    private suspend fun consume(owner: Any, afterClaim: () -> Unit = {}): String? {
        val owned = journal.claimSources(owner, listOf(source))
        afterClaim()
        if (source !in owned) return null
        val reusable = journal.pendingCopiesOf(owned)
        val privateUri = reusable[source]
            ?: storage.create("jpg", 1_700_000_000_000L) { it.write(byteArrayOf(1, 2, 3)) }!!
        val entry = HiddenVaultJournal.Entry(privateUri, source, "Camera", "P02.jpg", link)
        assertTrue(journal.journal(listOf(entry)))
        deleteRequests.incrementAndGet()
        return privateUri
    }

    /** The user declined the system delete prompt of [owner]'s run of [privateUri]. */
    private suspend fun decline(owner: Any, privateUri: String) {
        journal.undoHide(listOf(privateUri))
        journal.releaseSources(owner, listOf(source))
    }

    private suspend fun prefs() = context.settingsDataStore.data.first()

    @Test
    fun `two screens taking in one hide make one vault copy and one delete request`() = runBlocking {
        // Both runs reach their claim before either copies, as two screens collecting the same queue do.
        val bothClaimed = CountDownLatch(2)
        val results = List(2) { Any() }.map { owner ->
            async(Dispatchers.Default) {
                consume(owner) {
                    bothClaimed.countDown()
                    bothClaimed.await(5, TimeUnit.SECONDS)
                }
            }
        }.awaitAll()

        assertEquals(1, results.count { it != null })
        assertEquals(1, storage.vaultBlobUris().size)
        assertEquals(1, deleteRequests.get())
        assertEquals(1, prefs()[SettingsKeys.HIDDEN_PENDING_HIDES].orEmpty().size)
    }

    @Test
    fun `a declined prompt leaves no vault copy and the next take-in copies once`() = runBlocking {
        val first = Any()
        val copy = consume(first)!!

        withContext(Dispatchers.IO) { decline(first, copy) }

        assertTrue(storage.vaultBlobUris().isEmpty())
        assertTrue(prefs()[SettingsKeys.HIDDEN_PENDING_HIDES].orEmpty().isEmpty())
        assertTrue(prefs()[SettingsKeys.HIDDEN_URI_CLOUD_ID_MAP].orEmpty().isEmpty())
        assertTrue(journal.vaultedLinks().isEmpty())

        assertTrue(consume(Any()) != null)
        assertEquals(1, storage.vaultBlobUris().size)
    }

    @Test
    fun `a run whose screen went away leaves its copy to the next run, which moves it instead of copying`() = runBlocking {
        val gone = Any()
        val copy = consume(gone)!!
        journal.leaveForNextRun(listOf(copy))
        journal.releaseAllSources(gone)

        val next = consume(Any())

        assertEquals(copy, next)
        assertEquals(setOf(copy), storage.vaultBlobUris())
        assertEquals(1, prefs()[SettingsKeys.HIDDEN_PENDING_HIDES].orEmpty().size)
        assertEquals(1, prefs()[SettingsKeys.HIDDEN_URI_CLOUD_ID_MAP].orEmpty().size)
    }

    @Test
    fun `a copy another run is still moving is never taken over`() = runBlocking {
        // A hide that is still waiting on its own prompt: its copy is journalled and in flight.
        val moving = storage.create("jpg", 1_700_000_000_000L) { it.write(byteArrayOf(9)) }!!
        assertTrue(journal.journal(listOf(HiddenVaultJournal.Entry(moving, source, "Camera", "P02.jpg", link))))

        val next = consume(Any())

        assertTrue(next != null && next != moving)
        assertTrue(moving in storage.vaultBlobUris())
    }

    @Test
    fun `a second screen asks for nothing while the first one holds the photo`() = runBlocking {
        val copy = consume(Any())!!

        assertNull(consume(Any()))
        assertEquals(setOf(copy), storage.vaultBlobUris())
        assertEquals(1, deleteRequests.get())
    }

    @Test
    fun `an undo whose original has already gone keeps the copy as the hidden photo`() = runBlocking {
        val owner = Any()
        val copy = consume(owner)!!
        // An earlier prompt for the same photo was confirmed late: the original is gone.
        FakeMedia.present.clear()

        withContext(Dispatchers.IO) { decline(owner, copy) }

        assertEquals(setOf(copy), storage.vaultBlobUris())
        assertTrue(copy in prefs()[SettingsKeys.HIDDEN_PHOTO_URIS].orEmpty())
        assertEquals(setOf(link), journal.vaultedLinks())
    }

    private companion object {
        const val AUTHORITY = "test.media.hide"
    }
}
