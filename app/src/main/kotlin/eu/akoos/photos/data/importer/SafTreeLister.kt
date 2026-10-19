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

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream

/**
 * The provider side of a folder import: lists a picked document tree with one child-documents query per
 * directory (the walk itself is [FolderTree]) and opens its files by document id. Kept to the provider
 * calls alone; everything that can be decided without a provider lives in [FolderTree] and
 * [FolderImportReader].
 */
internal class SafTreeLister(private val resolver: ContentResolver, private val tree: Uri) {

    private val rootId: String = DocumentsContract.getTreeDocumentId(tree)

    fun list(): List<FolderEntry> = FolderTree.list(rootName(), rootId, ::children)

    fun open(entry: FolderEntry): InputStream =
        resolver.openInputStream(DocumentsContract.buildDocumentUriUsingTree(tree, entry.handle))
            ?: throw FileNotFoundException("no stream")

    fun rootReachable(): Boolean =
        resolver.query(rootUri(), arrayOf(Document.COLUMN_DOCUMENT_ID), null, null, null)
            ?.use { it.moveToFirst() } == true

    /** The picked folder's own name, the first segment of every entry name. Throws when the provider
     *  does not answer, so a run never names its entries differently from the one before. */
    fun rootName(): String {
        val cursor = resolver.query(rootUri(), arrayOf(Document.COLUMN_DISPLAY_NAME), null, null, null)
            ?: throw IOException("folder not available")
        val name = cursor.use { if (it.moveToFirst()) it.getString(0) else null }
        return name?.takeIf { it.isNotBlank() } ?: fallbackName(rootId)
    }

    private fun rootUri(): Uri = DocumentsContract.buildDocumentUriUsingTree(tree, rootId)

    private fun children(docId: String): List<FolderChild> {
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
        val cursor = resolver.query(uri, PROJECTION, null, null, null) ?: throw IOException("folder not available")
        return cursor.use { c ->
            val out = ArrayList<FolderChild>(c.count.coerceAtLeast(0))
            while (c.moveToNext()) {
                val id = c.getString(0) ?: continue
                out += FolderChild(
                    name = c.getString(1).orEmpty(),
                    isDirectory = c.getString(2) == Document.MIME_TYPE_DIR,
                    sizeBytes = if (c.isNull(3)) 0L else c.getLong(3),
                    modifiedMs = if (c.isNull(4)) null else c.getLong(4).takeIf { it > 0L },
                    handle = id,
                )
            }
            out
        }
    }

    companion object {
        private val PROJECTION = arrayOf(
            Document.COLUMN_DOCUMENT_ID,
            Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_MIME_TYPE,
            Document.COLUMN_SIZE,
            Document.COLUMN_LAST_MODIFIED,
        )

        /** A name for a folder whose provider reports none: the last part of its document id
         *  (`primary:DCIM/Camera` gives `Camera`). */
        internal fun fallbackName(documentId: String): String =
            documentId.substringAfterLast(':').substringAfterLast('/').ifBlank { "Import" }
    }
}
