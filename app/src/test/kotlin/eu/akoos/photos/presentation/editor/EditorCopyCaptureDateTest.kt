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

package eu.akoos.photos.presentation.editor

import android.content.ContentResolver
import android.content.Context
import android.database.MatrixCursor
import android.net.Uri
import android.provider.MediaStore
import eu.akoos.photos.data.preferences.InMemoryPreferencesDataStore
import eu.akoos.photos.data.preferences.SettingsKeys
import eu.akoos.photos.util.CaptureDateOverride
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A saved copy of a WebP or PNG keeps its original's date. MediaStore leaves DATE_TAKEN unset for those
 * formats and dates the file to the day it was added, so where the column did not take the date, the copy
 * records it as a capture-date override with the file's modified time, the way a download is dated.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EditorCopyCaptureDateTest {

    private val originalMs = 1_500_000_000_000L
    private val uri = Uri.parse("content://media/external/images/media/42")
    private val resolver = mockk<ContentResolver>()
    private val context = mockk<Context>()
    private val store = InMemoryPreferencesDataStore()

    @Before
    fun setUp() {
        every { context.contentResolver } returns resolver
        InMemoryPreferencesDataStore.install(context, store)
    }

    @After
    fun tearDown() = InMemoryPreferencesDataStore.uninstall()

    private fun storedRow(dateTaken: Long, modifiedSeconds: Long) {
        val cursor = MatrixCursor(arrayOf(MediaStore.MediaColumns.DATE_TAKEN, MediaStore.MediaColumns.DATE_MODIFIED))
        cursor.addRow(arrayOf<Any>(dateTaken, modifiedSeconds))
        every { resolver.query(uri, any(), null, null, null) } returns cursor
    }

    private fun overrides() = store.current[SettingsKeys.DOWNLOAD_DATE_OVERRIDES]

    @Test
    fun `a copy whose date the media store dropped records the original date`() = runTest {
        storedRow(dateTaken = 0L, modifiedSeconds = 1_700_000_000L)

        recordCaptureDateIfUnset(context, uri, originalMs)

        assertEquals(setOf(CaptureDateOverride.encode(uri.toString(), originalMs, 1_700_000_000L)), overrides())
    }

    @Test
    fun `a copy whose date the media store kept records nothing`() = runTest {
        storedRow(dateTaken = originalMs, modifiedSeconds = 1_700_000_000L)

        recordCaptureDateIfUnset(context, uri, originalMs)

        assertNull(overrides())
    }

    @Test
    fun `an original with no known date records nothing`() = runTest {
        storedRow(dateTaken = 0L, modifiedSeconds = 1_700_000_000L)

        recordCaptureDateIfUnset(context, uri, 0L)

        assertNull(overrides())
    }

    @Test
    fun `the entries of other files are kept`() = runTest {
        val other = CaptureDateOverride.encode("content://media/external/images/media/7", 1_000L, 5L)
        store.updateData { prefs ->
            prefs.toMutablePreferences().apply { this[SettingsKeys.DOWNLOAD_DATE_OVERRIDES] = setOf(other) }
        }
        storedRow(dateTaken = 0L, modifiedSeconds = 9L)

        recordCaptureDateIfUnset(context, uri, originalMs)

        assertEquals(setOf(other, CaptureDateOverride.encode(uri.toString(), originalMs, 9L)), overrides())
    }

    @Test
    fun `a copy the media store cannot read back still keeps its date`() = runTest {
        every { resolver.query(uri, any(), null, null, null) } returns null

        recordCaptureDateIfUnset(context, uri, originalMs)

        assertEquals(setOf(CaptureDateOverride.encode(uri.toString(), originalMs, null)), overrides())
    }
}
