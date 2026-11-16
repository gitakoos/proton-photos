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

package eu.akoos.photos.data.localcrypto

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.os.ProxyFileDescriptorCallback
import android.os.storage.StorageManager
import android.provider.OpenableColumns
import android.system.ErrnoException
import android.system.OsConstants
import eu.akoos.photos.BuildConfig
import eu.akoos.photos.util.HiddenCaptureTime
import eu.akoos.photos.util.mimeFromPath
import java.io.File
import java.io.FileNotFoundException

/**
 * Serves vault files decrypted, read-only, as `content://<app>.vaultread/v/<file name>`. Not exported; another app
 * reads a file only through a uri permission the app grants it (share, set as). The descriptor it hands out is
 * seekable ([StorageManager.openProxyFileDescriptor]), so video players, frame grabs and EXIF readers work on it
 * as on a plain file, decrypting one segment at a time and never writing plaintext to disk.
 */
class VaultReadProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun getType(uri: Uri): String? = fileFor(uri)?.let { mimeFromPath(it.name) }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? {
        val file = fileFor(uri) ?: return null
        val columns = projection?.filter { it == OpenableColumns.DISPLAY_NAME || it == OpenableColumns.SIZE }
            ?.toTypedArray() ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        val row = columns.map { column ->
            when (column) {
                OpenableColumns.DISPLAY_NAME -> synchronized(displayNames) { displayNames[file.name] } ?: HiddenCaptureTime.strip(file.name)
                else -> VaultFiles.plainLength(file)
            }
        }
        return MatrixCursor(columns, 1).apply { addRow(row) }
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw SecurityException("read only")
        val context = context ?: throw FileNotFoundException()
        val file = fileFor(uri) ?: throw FileNotFoundException()
        if (!VaultFiles.isEncrypted(file)) return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        val reader = try {
            VaultFiles.openReader(context, file)
        } catch (e: Exception) {
            throw FileNotFoundException("vault file does not open: ${e.javaClass.simpleName}")
        }
        val storage = context.getSystemService(StorageManager::class.java)
        return storage.openProxyFileDescriptor(ParcelFileDescriptor.MODE_READ_ONLY, ReaderCallback(reader), handler)
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    private fun fileFor(uri: Uri): File? {
        val context = context ?: return null
        val segments = uri.pathSegments
        if (segments.size != 2 || segments[0] != PATH) return null
        val name = segments[1]
        if (name.isBlank() || name.contains('/') || name == "." || name == "..") return null
        val file = File(VaultFiles.vaultDir(context), name)
        return file.takeIf { it.isFile }
    }

    internal class ReaderCallback(private val reader: LocalFileReader) : ProxyFileDescriptorCallback() {
        override fun onGetSize(): Long = reader.plainSize

        override fun onRead(offset: Long, size: Int, data: ByteArray): Int {
            try {
                var done = 0
                while (done < size) {
                    val n = reader.read(offset + done, data, done, size - done)
                    if (n <= 0) break
                    done += n
                }
                return done
            } catch (e: Exception) {
                throw ErrnoException("read", OsConstants.EIO)
            }
        }

        override fun onRelease() {
            runCatching { reader.close() }
        }
    }

    companion object {
        private const val PATH = "v"
        val AUTHORITY: String get() = "${BuildConfig.APPLICATION_ID}.vaultread"

        private val handler: Handler by lazy {
            Handler(HandlerThread("vault-read").apply { start() }.looper)
        }

        /** A name to report for a file handed to another app, set by the share path. Bounded, newest kept. */
        private val displayNames = object : LinkedHashMap<String, String>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean = size > 200
        }

        fun uriFor(fileName: String): Uri = Uri.Builder()
            .scheme("content")
            .authority(AUTHORITY)
            .appendPath(PATH)
            .appendPath(fileName)
            .build()

        fun setDisplayName(fileName: String, name: String) {
            synchronized(displayNames) { displayNames[fileName] = name }
        }
    }
}
