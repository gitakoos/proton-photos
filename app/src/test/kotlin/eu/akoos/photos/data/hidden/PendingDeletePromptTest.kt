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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import me.proton.core.accountmanager.domain.AccountManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * A hide whose system delete prompt is still open when the screen that asked, or the whole process, goes away.
 *
 * The prompt belongs to the system and an Allow tapped on it later still removes the original. These walk the
 * real journal, vault and preferences through that: a forced sign-out's reconciliation and a fresh process
 * (a new journal over the same stored state) both keep the copy while the original is still there, publish it
 * once the original goes, and drop it only after the prompt can no longer act.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PendingDeletePromptTest {

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

    private val source = "content://$AUTHORITY/media/42"

    @Before
    fun setUp(): Unit = runBlocking {
        context = ApplicationProvider.getApplicationContext()
        context.settingsDataStore.edit { it.clear() }
        storage = HiddenStorageManager(context)
        File(context.filesDir, "hidden").deleteRecursively()
        Robolectric.setupContentProvider(FakeMedia::class.java, AUTHORITY)
        FakeMedia.present.clear()
        FakeMedia.present += "42"
    }

    /** A journal as a new process builds it: nothing in flight, everything else read from storage. */
    private fun newProcess(): HiddenVaultJournal {
        val albumTargets = mockk<UploadAlbumTargetDao>()
        coEvery { albumTargets.getAll() } returns emptyList()
        val tags = mockk<LocalTagDao>()
        coEvery { tags.getUserTagsCsv(any()) } returns null
        val accounts = mockk<AccountManager>()
        every { accounts.getPrimaryUserId() } returns flowOf(null)
        return HiddenVaultJournal(
            context = context,
            hiddenStorage = storage,
            localTagDao = tags,
            uploadAlbumTargetDao = albumTargets,
            syncStateRepo = mockk(relaxed = true),
            accountManager = accounts,
            photoLabels = mockk(relaxed = true),
            facePhotoRemoval = mockk(relaxed = true),
        )
    }

    /** Copies the photo into the vault and journals it, up to the point the hide opens the delete prompt. */
    private suspend fun hideUpToPrompt(journal: HiddenVaultJournal): String {
        val copy = storage.create("jpg", 1_700_000_000_000L) { it.write(byteArrayOf(1, 2, 3)) }!!
        assertTrue(journal.journal(listOf(HiddenVaultJournal.Entry(copy, source, "Camera", "P02.jpg", null))))
        return copy
    }

    private suspend fun prefs() = context.settingsDataStore.data.first()

    @Test
    fun `a forced sign-out under an open prompt keeps the copy, and an Allow then publishes it`() = runBlocking {
        val journal = newProcess()
        val copy = hideUpToPrompt(journal)
        // The screen goes away with the prompt still open, then the sign-out ends with its reconciliation.
        journal.leaveForNextRun(listOf(copy))
        val spared = journal.beginSignOut()
        journal.endSignOut()
        journal.reconcileAgain()

        assertTrue(copy in spared)
        assertEquals(setOf(copy), storage.vaultBlobUris())
        assertEquals(1, prefs()[SettingsKeys.HIDDEN_PENDING_HIDES].orEmpty().size)

        // The user taps Allow: the original goes, and the next pass in the same process publishes the copy.
        FakeMedia.present.clear()
        journal.reconcile()

        assertEquals(setOf(copy), storage.vaultBlobUris())
        assertTrue(copy in prefs()[SettingsKeys.HIDDEN_PHOTO_URIS].orEmpty())
        assertTrue(prefs()[SettingsKeys.HIDDEN_PENDING_HIDES].orEmpty().isEmpty())
        assertTrue(prefs()[SettingsKeys.HIDDEN_DELETE_REQUESTS].orEmpty().isEmpty())
    }

    @Test
    fun `a process restart under an open prompt keeps the copy until the original goes`() = runBlocking {
        val copy = hideUpToPrompt(newProcess())

        // The process dies with the prompt still open; the next one reconciles first.
        val restarted = newProcess()
        restarted.reconcile()

        assertEquals(setOf(copy), storage.vaultBlobUris())
        assertFalse(copy in prefs()[SettingsKeys.HIDDEN_PHOTO_URIS].orEmpty())

        FakeMedia.present.clear()
        newProcess().reconcile()

        assertEquals(setOf(copy), storage.vaultBlobUris())
        assertTrue(copy in prefs()[SettingsKeys.HIDDEN_PHOTO_URIS].orEmpty())
    }

    @Test
    fun `a copy whose prompt is long over and whose original stayed is dropped`() = runBlocking {
        val copy = hideUpToPrompt(newProcess())
        context.settingsDataStore.edit {
            it[SettingsKeys.HIDDEN_DELETE_REQUESTS] = setOf(HiddenVaultLeftovers.deleteRequestEntry(copy, 0L))
        }

        newProcess().reconcile()

        assertTrue(storage.vaultBlobUris().isEmpty())
        assertTrue(prefs()[SettingsKeys.HIDDEN_PENDING_HIDES].orEmpty().isEmpty())
        assertTrue(prefs()[SettingsKeys.HIDDEN_DELETE_REQUESTS].orEmpty().isEmpty())
    }

    @Test
    fun `a pending hide an earlier version journalled gets a request time and is kept`() = runBlocking {
        val copy = hideUpToPrompt(newProcess())
        context.settingsDataStore.edit { it.remove(SettingsKeys.HIDDEN_DELETE_REQUESTS) }

        newProcess().reconcile()

        assertEquals(setOf(copy), storage.vaultBlobUris())
        assertTrue(copy in HiddenVaultLeftovers.parseDeleteRequests(prefs()[SettingsKeys.HIDDEN_DELETE_REQUESTS].orEmpty()))
    }

    @Test
    fun `a declined prompt in the same process still drops the copy at once`() = runBlocking {
        val journal = newProcess()
        val copy = hideUpToPrompt(journal)

        journal.undoHide(listOf(copy))

        assertTrue(storage.vaultBlobUris().isEmpty())
        assertTrue(prefs()[SettingsKeys.HIDDEN_DELETE_REQUESTS].orEmpty().isEmpty())
    }

    private companion object {
        const val AUTHORITY = "test.media.pendingdelete"
    }
}
