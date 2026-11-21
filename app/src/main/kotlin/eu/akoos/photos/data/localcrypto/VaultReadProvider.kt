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
import android.content.Context
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
 * Serves the files of the encrypted folders ([LocalFiles.Area]) decrypted, read-only, as
 * `content://<app>.vaultread/<folder code>/<file name>`. Not exported; another app
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
                else -> LocalFiles.plainLength(file)
            }
        }
        return MatrixCursor(columns, 1).apply { addRow(row) }
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw SecurityException("read only")
        val context = context ?: throw FileNotFoundException()
        val file = fileFor(uri) ?: throw FileNotFoundException()
        return openDecrypted(context, file)
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    private fun fileFor(uri: Uri): File? {
        val context = context ?: return null
        val segments = uri.pathSegments
        if (segments.size != 2) return null
        val area = LocalFiles.Area.of(segments[0]) ?: return null
        val name = segments[1]
        if (name.isBlank() || name.contains('/') || name == "." || name == "..") return null
        val file = File(LocalFiles.dir(context, area), name)
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
        val AUTHORITY: String get() = "${BuildConfig.APPLICATION_ID}.vaultread"

        private val handler: Handler by lazy {
            Handler(HandlerThread("vault-read").apply { start() }.looper)
        }

        /** A name to report for a file handed to another app, set by the share path. Bounded, newest kept. */
        private val displayNames = object : LinkedHashMap<String, String>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean = size > 200
        }

        /** The uri of the vault file named [fileName]. */
        fun uriFor(fileName: String): Uri = uriFor(LocalFiles.Area.VAULT, fileName)

        fun uriFor(area: LocalFiles.Area, fileName: String): Uri = Uri.Builder()
            .scheme("content")
            .authority(AUTHORITY)
            .appendPath(area.code)
            .appendPath(fileName)
            .build()

        /**
         * A read-only descriptor of [file]'s plaintext: the file itself when it is not encrypted, else a seekable
         * descriptor that decrypts one segment at a time. A regenerable cache file that no longer opens is
         * deleted, so it is fetched again.
         */
        fun openDecrypted(context: Context, file: File): ParcelFileDescriptor {
            // The descriptor is opened first and the form read from it, so a plaintext file encrypted in place right
            // then is handed out as the plaintext it opened, never as its encrypted bytes. Files only ever go from
            // plaintext to encrypted, so one seen encrypted is opened again by its name.
            val descriptor = try {
                ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            } catch (e: Exception) {
                throw FileNotFoundException("file cannot be read now")
            }
            val plain = try {
                plainAt(descriptor, file.length())
            } catch (e: Exception) {
                descriptor.close()
                throw FileNotFoundException("file cannot be read now")
            }
            if (plain) return descriptor
            descriptor.close()
            val reader = try {
                VaultFiles.openReader(context, file)
            } catch (e: Exception) {
                if (LocalFiles.areaOf(context, file)?.regenerable == true) CacheFiles.dropIfUnreadable(file, e)
                throw FileNotFoundException("file does not open: ${e.javaClass.simpleName}")
            }
            val storage = context.getSystemService(StorageManager::class.java)
            return storage.openProxyFileDescriptor(ParcelFileDescriptor.MODE_READ_ONLY, ReaderCallback(reader), handler)
        }

        /** Whether the open [descriptor] holds a plaintext file, read from its first bytes without moving it. */
        private fun plainAt(descriptor: ParcelFileDescriptor, length: Long): Boolean {
            if (length < LocalCryptoFormat.META_OFFSET) return true
            // A positioned read straight on the descriptor: nothing else is opened on it, so nothing is left to
            // close while the descriptor stays open for the reader.
            val head = ByteArray(5)
            var read = 0
            while (read < head.size) {
                val n = android.system.Os.pread(descriptor.fileDescriptor, head, read, head.size - read, read.toLong())
                if (n <= 0) throw java.io.EOFException()
                read += n
            }
            return !LocalCryptoFormat.looksEncrypted(head)
        }

        fun setDisplayName(fileName: String, name: String) {
            synchronized(displayNames) { displayNames[fileName] = name }
        }
    }
}
