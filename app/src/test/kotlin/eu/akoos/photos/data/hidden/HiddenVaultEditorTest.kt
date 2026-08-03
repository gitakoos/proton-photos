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

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.test.core.app.ApplicationProvider
import eu.akoos.photos.data.db.dao.LocalTagDao
import eu.akoos.photos.data.preferences.SettingsKeys
import eu.akoos.photos.data.preferences.settingsDataStore
import eu.akoos.photos.util.FolderCoverMap
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The order the two halves of a vault edit happen in, and what a move of no distance is allowed to
 * touch.
 *
 * A vaulted photo keeps its name in the vault's own records and its capture date in its file name, so
 * every edit to either RENAMES the file — and the file's uri is the key all five vault records, the
 * heart, the pinned folder cover and the categories are stored under. Doing the two halves in the
 * wrong order is what makes an edit losable: the file has to move first, so an interruption between
 * them leaves a vault file no record names, which the reconciliation adopts back as a hidden photo.
 * Records first would leave every record pointing at a file that is no longer there.
 *
 * The storage is stood in for so the moment the file moves can be observed from inside; the records
 * are the real ones, since what they hold afterwards is half of what is under test.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HiddenVaultEditorTest {

    private lateinit var context: Context
    private lateinit var storage: HiddenStorageManager
    private lateinit var localTagDao: LocalTagDao
    private lateinit var editor: HiddenVaultEditor

    private val oldUri = "file:///data/user/0/eu.akoos.photos/files/hidden/3f1a__1783507135000.jpg"
    private val newUri = "file:///data/user/0/eu.akoos.photos/files/hidden/Beach trip__1783507135000.jpg"
    private val copyUri = "file:///data/user/0/eu.akoos.photos/files/hidden/c1d2__1783507135000.jpg"

    /** What the index held at the moment the storage was asked to move the file, so the ordering can
     *  be read off from inside the edit rather than guessed at from its result. */
    private var indexWhenFileMoved: Set<String>? = null

    /** And what it held when the categories were asked to follow, which is the step after. */
    private var indexWhenTagsMoved: Set<String>? = null

    @Before
    fun setUp() = runBlocking {
        context = ApplicationProvider.getApplicationContext()
        // The `by preferencesDataStore` instance is a process-wide singleton, so every case starts
        // by wiping what the one before it wrote.
        clearRecords()
        storage = mockk()
        localTagDao = mockk(relaxed = true)
        coEvery { localTagDao.getUserTagsCsv(any()) } coAnswers {
            indexWhenTagsMoved = index()
            null
        }
        editor = HiddenVaultEditor(context, storage, localTagDao)
        indexWhenFileMoved = null
        indexWhenTagsMoved = null
    }

    @After
    fun tearDown() = runBlocking { clearRecords() }

    private suspend fun clearRecords() {
        runCatching { context.settingsDataStore.edit { it.clear() } }
        Unit
    }

    private suspend fun prefs(): Preferences = context.settingsDataStore.data.first()

    private suspend fun index(): Set<String> = prefs()[SettingsKeys.HIDDEN_PHOTO_URIS] ?: emptySet()

    private suspend fun names(): Set<String> =
        prefs()[SettingsKeys.HIDDEN_URI_ORIGINAL_NAME_MAP] ?: emptySet()

    /** A vault holding the one photo, with everything a hide and a user can put on it. */
    private suspend fun seedRecordsFor(uri: String) {
        context.settingsDataStore.edit { p ->
            p[SettingsKeys.HIDDEN_PHOTO_URIS] = setOf(uri)
            p[SettingsKeys.HIDDEN_URI_ORIGINAL_NAME_MAP] = setOf("$uri|IMG_0042.jpg")
            p[SettingsKeys.HIDDEN_URI_SOURCE_FOLDER_MAP] = setOf("$uri|DCIM/Camera")
            p[SettingsKeys.HIDDEN_URI_CLOUD_ID_MAP] = setOf("$uri|link-7")
            p[SettingsKeys.FAVORITE_IDS] = setOf(uri)
            p[SettingsKeys.FOLDER_COVER_URI_MAP] = setOf(FolderCoverMap.encode("Camera", uri))
        }
    }

    /** The storage answering [answer] for a rename, and recording what the records held as it did. */
    private fun storageRenamesTo(answer: String?) {
        coEvery { storage.rename(any(), any()) } coAnswers {
            indexWhenFileMoved = index()
            answer
        }
    }

    private fun storageRestampsTo(answer: String?) {
        coEvery { storage.restampCaptureTime(any(), any()) } coAnswers {
            indexWhenFileMoved = index()
            answer
        }
    }

    // ── the file moves first ────────────────────────────────────────────────────────────────────

    @Test
    fun `a rename moves the file before it re-keys a single record`() = runBlocking {
        seedRecordsFor(oldUri)
        storageRenamesTo(newUri)

        assertEquals(newUri, editor.rename(oldUri, "Beach trip"))

        assertEquals("the records may not have moved yet", setOf(oldUri), indexWhenFileMoved)
        assertEquals("and they have to have moved by the end", setOf(newUri), index())
    }

    @Test
    fun `the categories follow only once the records already name the new file`() = runBlocking {
        seedRecordsFor(oldUri)
        storageRenamesTo(newUri)

        editor.rename(oldUri, "Beach trip")

        assertEquals(setOf(newUri), indexWhenTagsMoved)
    }

    @Test
    fun `a rename replaces the recorded name and carries everything else onto the new uri`() = runBlocking {
        seedRecordsFor(oldUri)
        storageRenamesTo(newUri)

        editor.rename(oldUri, "Beach trip")

        val after = prefs()
        // The recorded name is what a reveal writes the file back to the device under, so a rename
        // has to overwrite it rather than carry the old one.
        assertEquals(setOf("$newUri|Beach trip.jpg"), names())
        assertEquals(setOf("$newUri|DCIM/Camera"), after[SettingsKeys.HIDDEN_URI_SOURCE_FOLDER_MAP])
        assertEquals(setOf("$newUri|link-7"), after[SettingsKeys.HIDDEN_URI_CLOUD_ID_MAP])
        assertEquals(setOf(newUri), after[SettingsKeys.FAVORITE_IDS])
        assertEquals(newUri, FolderCoverMap.parse(after[SettingsKeys.FOLDER_COVER_URI_MAP])["Camera"])
    }

    @Test
    fun `a rename publishes where the file went for the screens still holding the old uri`() = runBlocking {
        seedRecordsFor(oldUri)
        storageRenamesTo(newUri)

        editor.rename(oldUri, "Beach trip")

        assertEquals(VaultMove(newUri, "Beach trip.jpg"), editor.moves.value[oldUri])
    }

    @Test
    fun `a category that could not be carried does not fail the edit`() = runBlocking {
        seedRecordsFor(oldUri)
        storageRenamesTo(newUri)
        coEvery { localTagDao.getUserTagsCsv(any()) } throws IllegalStateException("table gone")

        // The photo has already moved and its name, date, heart and cover went with it. Losing a
        // category is not worth reporting the whole edit as failed over.
        assertEquals(newUri, editor.rename(oldUri, "Beach trip"))
        assertEquals(setOf(newUri), index())
        assertEquals(VaultMove(newUri, "Beach trip.jpg"), editor.moves.value[oldUri])
    }

    // ── a file that could not move ──────────────────────────────────────────────────────────────

    @Test
    fun `a rename the storage refused leaves every record where it was`() = runBlocking {
        seedRecordsFor(oldUri)
        storageRenamesTo(null)

        assertNull(editor.rename(oldUri, "Beach trip"))

        assertEquals(setOf(oldUri), index())
        assertEquals(setOf("$oldUri|IMG_0042.jpg"), names())
        assertTrue("no screen may be told the photo moved", editor.moves.value.isEmpty())
        coVerify(exactly = 0) { localTagDao.getUserTagsCsv(any()) }
    }

    // ── a move of no distance ───────────────────────────────────────────────────────────────────

    @Test
    fun `a rename onto the name the photo already has records the name and moves nothing`() = runBlocking {
        // The file stayed where it is, so every store keyed by its uri already answers under the uri
        // the photo keeps. Walking them anyway would take each answer off the photo and put it back
        // on the same photo, which for the categories — whose move ends by dropping the row the old
        // uri named — would be the user's choice deleted.
        seedRecordsFor(oldUri)
        storageRenamesTo(oldUri)

        assertEquals(oldUri, editor.rename(oldUri, "Beach trip"))

        assertEquals(setOf(oldUri), index())
        assertEquals(setOf("$oldUri|Beach trip.jpg"), names())
        assertTrue(editor.moves.value.isEmpty())
        coVerify(exactly = 0) { localTagDao.getUserTagsCsv(any()) }
        coVerify(exactly = 0) { localTagDao.deleteByUris(any()) }
    }

    @Test
    fun `a date edit onto the date already recorded touches no record at all`() = runBlocking {
        seedRecordsFor(oldUri)
        storageRestampsTo(oldUri)

        assertEquals(oldUri, editor.restampCaptureTime(oldUri, 1_783_507_135_000L))

        assertEquals(setOf(oldUri), index())
        assertEquals(setOf("$oldUri|IMG_0042.jpg"), names())
        assertTrue(editor.moves.value.isEmpty())
        coVerify(exactly = 0) { localTagDao.getUserTagsCsv(any()) }
    }

    @Test
    fun `a date edit moves everything the file carried and leaves the name alone`() = runBlocking {
        seedRecordsFor(oldUri)
        storageRestampsTo(newUri)

        assertEquals(newUri, editor.restampCaptureTime(oldUri, 1_700_000_000_000L))

        assertEquals("the records may not have moved yet", setOf(oldUri), indexWhenFileMoved)
        assertEquals(setOf(newUri), index())
        // Only the date moved, so the name the reveal writes back is carried across untouched.
        assertEquals(setOf("$newUri|IMG_0042.jpg"), names())
        assertEquals(VaultMove(newUri, null), editor.moves.value[oldUri])
    }

    @Test
    fun `a date edit the storage refused leaves every record where it was`() = runBlocking {
        seedRecordsFor(oldUri)
        storageRestampsTo(null)

        assertNull(editor.restampCaptureTime(oldUri, 1_700_000_000_000L))

        assertEquals(setOf(oldUri), index())
        assertTrue(editor.moves.value.isEmpty())
    }

    // ── a second photo rather than a move ───────────────────────────────────────────────────────

    @Test
    fun `a copy joins the vault under its own name and takes no cloud twin with it`() = runBlocking {
        seedRecordsFor(oldUri)
        coEvery { storage.duplicate(oldUri) } returns copyUri

        assertEquals(copyUri, editor.copy(oldUri, "Beach trip"))

        val after = prefs()
        assertEquals(setOf(oldUri, copyUri), index())
        assertEquals(setOf("$oldUri|IMG_0042.jpg", "$copyUri|Beach trip.jpg"), names())
        // It returns to the same folder the source came from, and claims no cloud id: that id names
        // one photo on Drive, and two entries claiming it would have both reveals transplant the
        // same sync row.
        assertEquals(
            setOf("$oldUri|DCIM/Camera", "$copyUri|DCIM/Camera"),
            after[SettingsKeys.HIDDEN_URI_SOURCE_FOLDER_MAP],
        )
        assertEquals(setOf("$oldUri|link-7"), after[SettingsKeys.HIDDEN_URI_CLOUD_ID_MAP])
        assertTrue("a copy is not a move", editor.moves.value.isEmpty())
    }

    @Test
    fun `a copy that could not be written records nothing`() = runBlocking {
        seedRecordsFor(oldUri)
        coEvery { storage.duplicate(oldUri) } returns null

        assertNull(editor.copy(oldUri, "Beach trip"))

        assertEquals(setOf(oldUri), index())
        assertEquals(setOf("$oldUri|IMG_0042.jpg"), names())
    }
}
