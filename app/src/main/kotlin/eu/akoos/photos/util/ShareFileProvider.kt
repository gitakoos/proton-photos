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

package eu.akoos.photos.util

import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import eu.akoos.photos.data.localcrypto.LocalFiles
import eu.akoos.photos.data.localcrypto.VaultReadProvider
import java.io.File
import java.io.FileNotFoundException
import java.util.concurrent.ConcurrentHashMap

/**
 * Distinct FileProvider subclass for the share sheet. Android registers a content provider by
 * its concrete class, so two `<provider>` entries that both name `androidx.core.content.FileProvider`
 * collide and only the first authority is published. Giving the share provider its own class lets
 * its `share.fileprovider` authority coexist with the updater's `updater.fileprovider`, each
 * advertising only the paths it needs.
 *
 * It also overrides [query] to substitute the file's display name. Decrypted cloud originals are
 * cached on disk under their Drive linkId (e.g. `aBc123.jpg`), so without this the receiving app
 * would show that opaque id as the filename. The share flow registers the real name via
 * [putDisplayName] before launching the chooser; [query] then returns it for OpenableColumns
 * DISPLAY_NAME while [openFile] (inherited) still serves the actual bytes.
 */
class ShareFileProvider : FileProvider() {

    /**
     * A full-size copy in the cache, like a stripped share copy, is kept encrypted, so the receiver is handed a
     * descriptor that decrypts as it reads (seekable, nothing written in plaintext). A file written by an earlier
     * version, or while no key answered, opens as it is.
     */
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        val file = cacheFileOf(uri) ?: return super.openFile(uri, mode)
        if (!LocalFiles.isEncrypted(file)) return super.openFile(uri, mode)
        if (mode != "r") throw SecurityException("read only")
        val context = context ?: throw FileNotFoundException()
        return VaultReadProvider.openDecrypted(context, file)
    }

    /** The full-size cache file (`content://<authority>/shared/<name>`) or stripped share copy
     *  (`.../stripped/<name>`) a share uri names, or null. */
    private fun cacheFileOf(uri: Uri): File? {
        val context = context ?: return null
        val segments = uri.pathSegments
        if (segments.size != 2) return null
        val dir = when (segments[0]) {
            SHARED_ROOT -> LocalFiles.dir(context, LocalFiles.Area.FULLRES)
            STRIPPED_ROOT -> strippedShareDir(context)
            else -> return null
        }
        val name = segments[1]
        if (name.isBlank() || name == "." || name == ".." || name.contains('/')) return null
        return File(dir, name).takeIf { it.isFile }
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        val base = super.query(uri, projection, selection, selectionArgs, sortOrder)
        val override = displayNames[uri.toString()]
        val plainSize = cacheFileOf(uri)?.takeIf { LocalFiles.isEncrypted(it) }?.let { LocalFiles.plainLength(it) }
        if (override == null && plainSize == null) return base
        // Rebuild the row, swapping DISPLAY_NAME and, for an encrypted file, SIZE for the plaintext's,
        // so the receiver sees the byte count it will read. Every other column is copied through.
        return base.use { c ->
            val cols = c.columnNames
            val out = MatrixCursor(cols)
            if (c.moveToFirst()) {
                val row = arrayOfNulls<Any?>(cols.size)
                for (i in cols.indices) {
                    row[i] = if (cols[i] == OpenableColumns.DISPLAY_NAME && override != null) {
                        override
                    } else if (cols[i] == OpenableColumns.SIZE && plainSize != null) {
                        plainSize
                    } else when (c.getType(i)) {
                        Cursor.FIELD_TYPE_INTEGER -> c.getLong(i)
                        Cursor.FIELD_TYPE_FLOAT -> c.getDouble(i)
                        Cursor.FIELD_TYPE_STRING -> c.getString(i)
                        else -> null
                    }
                }
                out.addRow(row)
            }
            out
        }
    }

    companion object {
        // Keyed by the FileProvider content URI string. Bounded so a long session of cloud
        // shares can't grow it without limit — sharing is infrequent and the receiver reads
        // the name immediately, so old entries are safe to drop.
        private const val MAX_ENTRIES = 256
        /** The `name` of the `cache-path` entry in share_paths.xml. */
        private const val SHARED_ROOT = "shared"
        private const val STRIPPED_ROOT = "stripped"
        private val displayNames = ConcurrentHashMap<String, String>()

        /** Register the real filename to report for a just-created share URI. A blank name is
         *  ignored so the receiver falls back to the on-disk name rather than an empty one. */
        fun putDisplayName(uri: Uri, name: String) {
            if (name.isBlank()) return
            if (displayNames.size >= MAX_ENTRIES) displayNames.clear()
            displayNames[uri.toString()] = name
        }
    }
}
