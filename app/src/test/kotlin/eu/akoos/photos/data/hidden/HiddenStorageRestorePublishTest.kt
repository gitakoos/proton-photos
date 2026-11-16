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
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * A reveal deletes the vault copy, the only copy, once the photo is back in the media index. These pin
 * the two ways the index can fail it after the bytes were written: a row that stays pending, and an
 * insert the provider refuses outright, which before Android 10 means a SecurityException.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HiddenStorageRestorePublishTest {

    /** A media provider that hands out one row backed by a real file and answers as told. */
    class FakeMedia : ContentProvider() {
        override fun onCreate() = true
        override fun getType(uri: Uri): String? = null

        override fun insert(uri: Uri, values: ContentValues?): Uri? {
            if (refuseInsert) throw SecurityException("no storage permission")
            return Uri.parse("content://media/external/images/media/7")
        }

        override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?): Int {
            if (values?.getAsInteger(MediaStore.MediaColumns.IS_PENDING) == 0 && publishes) pending = 0
            return if (publishes) 1 else 0
        }

        override fun delete(uri: Uri, selection: String?, args: Array<out String>?): Int {
            deleted += uri.toString()
            return 1
        }

        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?): Cursor {
            val columns = projection ?: arrayOf(MediaStore.MediaColumns.IS_PENDING)
            return MatrixCursor(columns).apply {
                addRow(columns.map { if (it == MediaStore.MediaColumns.IS_PENDING) pending else 0L })
            }
        }

        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor =
            ParcelFileDescriptor.open(backing!!, ParcelFileDescriptor.parseMode(mode))

        companion object {
            var refuseInsert = false
            var publishes = true
            var pending = 1
            var backing: File? = null
            val deleted = mutableListOf<String>()
        }
    }

    private lateinit var context: Context
    private lateinit var storage: HiddenStorageManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        storage = HiddenStorageManager(context)
        File(context.filesDir, "hidden").deleteRecursively()
        Robolectric.setupContentProvider(FakeMedia::class.java, MediaStore.AUTHORITY)
        FakeMedia.refuseInsert = false
        FakeMedia.publishes = true
        FakeMedia.pending = 1
        FakeMedia.deleted.clear()
        FakeMedia.backing = File(context.cacheDir, "media_row.png").apply { delete(); createNewFile() }
    }

    private suspend fun vaultFile(): String =
        storage.create("png", 1_700_000_000_000L) { out -> out.write(ByteArray(512) { it.toByte() }) }!!

    private fun exists(uri: String) = File(Uri.parse(uri).path!!).exists()

    @Test
    fun `a row whose pending flag does not clear rolls back and keeps the vault copy`() = runBlocking {
        val uri = vaultFile()
        FakeMedia.publishes = false

        val restored = storage.restore(uri, "photo.png")

        assertNull(restored)
        assertTrue("the only copy stays", exists(uri))
        assertTrue("the half-made row is removed", FakeMedia.deleted.contains("content://media/external/images/media/7"))
    }

    @Test
    fun `a published row lets the vault copy go`() = runBlocking {
        val uri = vaultFile()

        val restored = storage.restore(uri, "photo.png")

        assertNotNull(restored)
        assertFalse(exists(uri))
    }

    @Test
    fun `an insert the provider refuses keeps the vault copy and does not throw`() = runBlocking {
        val uri = vaultFile()
        FakeMedia.refuseInsert = true

        val restored = storage.restore(uri, "photo.png")

        assertNull(restored)
        assertTrue(exists(uri))
    }
}
