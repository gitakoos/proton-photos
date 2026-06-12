/*
 * Photos for Proton
 * Copyright (C) 2026 Akoos <https://akoos.eu>
 *
 * Source:  https://github.com/gitakoos/proton-photos
 * Website: https://photos.akoos.eu
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
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
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

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        val base = super.query(uri, projection, selection, selectionArgs, sortOrder)
        val override = displayNames[uri.toString()] ?: return base
        // Rebuild the row, swapping only DISPLAY_NAME. Every other column (notably SIZE) is
        // copied through verbatim so the receiver still sees the correct byte count.
        return base.use { c ->
            val cols = c.columnNames
            val out = MatrixCursor(cols)
            if (c.moveToFirst()) {
                val row = arrayOfNulls<Any?>(cols.size)
                for (i in cols.indices) {
                    row[i] = if (cols[i] == OpenableColumns.DISPLAY_NAME) {
                        override
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
