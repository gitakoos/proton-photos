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

package eu.akoos.photos.data.repository

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import eu.akoos.photos.domain.entity.DeviceFileStamp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * A device file's modified time is when its bytes last changed. The media library's column lags a write
 * until the next scan and a download sets it to the capture time, so the file's own time counts too.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DeviceFileStampsTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        Media.file = File(context.cacheDir, "stamp-media").also { it.writeBytes(ByteArray(300) { 1 }) }
        Media.columnSize = 300L
        Robolectric.setupContentProvider(Media::class.java, MediaStore.AUTHORITY)
    }

    private val uri = "content://media/external/images/media/7"

    /** The file's own size and time, as the platform's stat of the open file reads them on a device. */
    private fun readStamp(uri: String) = DeviceFileStamps.read(context, uri) { _ ->
        Media.file.length() to Media.file.lastModified() / 1000L
    }

    @Test
    fun `a file written after its column was set reads with the file's own time`() {
        Media.columnModifiedSec = 1_000L
        Media.file.setLastModified(1_700_000_000_000L)

        assertEquals(DeviceFileStamp(300L, 1_700_000_000L), readStamp(uri))
    }

    @Test
    fun `a column later than the file reads with the column`() {
        Media.columnModifiedSec = 1_800_000_000L
        Media.file.setLastModified(1_700_000_000_000L)

        assertEquals(1_800_000_000L, readStamp(uri)?.modifiedSec)
    }

    @Test
    fun `a file grown before the next scan reads with its own size`() {
        Media.columnModifiedSec = 1_000L
        Media.file.writeBytes(ByteArray(420) { 2 })

        assertEquals(420L, readStamp(uri)?.sizeBytes)
    }

    @Test
    fun `a plain file reads with its own size and time`() {
        val file = File(context.cacheDir, "plain-stamp").also { it.writeBytes(ByteArray(12)) }
        file.setLastModified(1_650_000_000_000L)

        assertEquals(DeviceFileStamp(12L, 1_650_000_000L), DeviceFileStamps.read(context, Uri.fromFile(file).toString()))
        assertNull(DeviceFileStamps.read(context, Uri.fromFile(File(context.cacheDir, "missing")).toString()))
    }

    @Test
    fun `a file that cannot be opened keeps the column's values`() {
        val column = DeviceFileStamp(300L, 1_000L)
        assertEquals(column, DeviceFileStamps.combine(column, null, null))
        assertEquals(DeviceFileStamp(300L, 2_000L), DeviceFileStamps.combine(column, 0L, 2_000L))
    }

    /** One gallery row whose columns and file the test sets. */
    class Media : ContentProvider() {
        override fun onCreate() = true
        override fun query(
            uri: Uri,
            projection: Array<out String>?,
            selection: String?,
            args: Array<out String>?,
            sortOrder: String?,
        ): Cursor {
            val cols = projection ?: arrayOf(MediaStore.MediaColumns._ID)
            return MatrixCursor(cols).apply {
                addRow(
                    cols.map<String, Any> { col ->
                        when (col) {
                            MediaStore.MediaColumns.SIZE -> columnSize
                            MediaStore.MediaColumns.DATE_MODIFIED -> columnModifiedSec
                            else -> 0L
                        }
                    }.toTypedArray(),
                )
            }
        }

        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor =
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)

        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?) = 0
        override fun delete(uri: Uri, selection: String?, args: Array<out String>?) = 0
        override fun getType(uri: Uri): String? = null

        companion object {
            lateinit var file: File
            var columnSize = 0L
            var columnModifiedSec = 0L
        }
    }
}
