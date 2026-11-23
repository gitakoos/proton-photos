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

package eu.akoos.photos.data.importer

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Opens a picked import source: a document tree (a folder picked with the folder picker) reads through
 * [FolderImportReader], anything else is read as a `.zip` through [TakeoutZipReader]. Nothing is read
 * until a pass runs, so opening never fails; a source that cannot be read fails on its first pass.
 */
@Singleton
class ImportSourceFactory @Inject constructor(@ApplicationContext private val context: Context) {

    fun open(uri: Uri): ImportSource {
        val resolver = context.contentResolver
        if (isFolder(uri)) {
            val lister = SafTreeLister(resolver, uri)
            val reader = FolderImportReader(lister::list, lister::open, lister::rootReachable)
            return ImportSource(reader, displayName(uri)) { reader.totalBytes() }
        }
        val reader = TakeoutZipReader(
            openStream = { resolver.openInputStream(uri) ?: throw IOException("cannot open zip input stream") },
            openRandomAccess = { openSeekable(uri) },
        )
        return ImportSource(reader, displayName(uri)) { zipSize(uri) }
    }

    /** The zip as a file readable at any position, or null when its provider hands out only a stream (a pipe). */
    private fun openSeekable(uri: Uri): ZipRandomSource? {
        val descriptor = context.contentResolver.openFileDescriptor(uri, "r") ?: return null
        val input = ParcelFileDescriptor.AutoCloseInputStream(descriptor)
        return try {
            val source = ChannelZipSource(input.channel, input)
            if (source.size > 0 && source.readAt(0, ByteArray(1), 0, 1) == 1) source else null.also { input.close() }
        } catch (e: IOException) {
            input.close()
            null
        }
    }

    /**
     * The name to record a run under: the provider's own name for the file or folder, else the uri's last
     * path segment, else the uri itself. Best effort and never throws.
     */
    fun displayName(uri: Uri): String {
        val fromProvider = runCatching {
            if (isFolder(uri)) SafTreeLister(context.contentResolver, uri).rootName() else queryName(uri)
        }.getOrNull()
        return fromProvider?.takeIf { it.isNotBlank() }
            ?: uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
            ?: uri.toString()
    }

    private fun isFolder(uri: Uri): Boolean = DocumentsContract.isTreeUri(uri)

    private fun queryName(uri: Uri): String? =
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { row ->
            if (!row.moveToFirst()) return@use null
            val idx = row.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0) row.getString(idx) else null
        }

    /** The archive's size in bytes when the provider reports it, else -1. */
    private fun zipSize(uri: Uri): Long = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { row ->
            if (!row.moveToFirst()) return@use -1L
            val idx = row.getColumnIndex(OpenableColumns.SIZE)
            if (idx >= 0 && !row.isNull(idx)) row.getLong(idx) else -1L
        }
    }.getOrNull() ?: -1L
}
