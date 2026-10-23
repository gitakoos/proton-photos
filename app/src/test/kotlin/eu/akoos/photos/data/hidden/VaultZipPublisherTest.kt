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
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VaultZipPublisherTest {

    class FakeDownloads : ContentProvider() {
        override fun onCreate() = true
        override fun insert(uri: Uri, values: ContentValues?): Uri {
            pendingValues = values?.getAsInteger(MediaStore.MediaColumns.IS_PENDING)
            return ContentUris.withAppendedId(uri, 1)
        }
        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor = ParcelFileDescriptor.open(
            target!!,
            ParcelFileDescriptor.MODE_WRITE_ONLY or ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE,
        )
        override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?): Int {
            updatedUris += uri
            return updateResult
        }
        override fun delete(uri: Uri, selection: String?, args: Array<out String>?): Int {
            deletedUris += uri
            return 1
        }
        override fun query(
            uri: Uri,
            projection: Array<out String>?,
            selection: String?,
            args: Array<out String>?,
            sortOrder: String?,
        ): Cursor? = null
        override fun getType(uri: Uri): String? = null
    }

    private lateinit var context: Context
    private lateinit var source: File

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        source = File(context.cacheDir, "vault.zip").also { it.writeBytes(ByteArray(4096) { (it % 7).toByte() }) }
        target = File(context.cacheDir, "published.zip").also { it.delete() }
        updateResult = 1
        pendingValues = null
        updatedUris.clear()
        deletedUris.clear()
        Robolectric.setupContentProvider(FakeDownloads::class.java, MediaStore.AUTHORITY)
    }

    @Test
    fun `a zip still pending after the final update is removed and reported as not published`() {
        updateResult = 0

        assertFalse(VaultZipPublisher(context).publish(source, "PFPVault_ForcedLogout_1.zip"))
        assertEquals(1, updatedUris.size)
        assertEquals(updatedUris, deletedUris)
    }

    @Test
    fun `a zip whose pending flag clears is published`() {
        assertTrue(VaultZipPublisher(context).publish(source, "PFPVault_ForcedLogout_1.zip"))
        assertEquals(1, pendingValues)
        assertEquals(source.length(), target!!.length())
        assertEquals(1, updatedUris.size)
        assertTrue(deletedUris.isEmpty())
    }

    companion object {
        var target: File? = null
        var updateResult = 1
        var pendingValues: Int? = null
        val updatedUris = mutableListOf<Uri>()
        val deletedUris = mutableListOf<Uri>()
    }
}
