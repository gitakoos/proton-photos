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

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** A folder on disk read the way a picked document tree is: [FolderTree] over plain directory listings. */
internal object FolderTestTrees {

    fun children(handle: String): List<FolderChild> =
        (File(handle).listFiles() ?: throw IOException("not a folder")).map { f ->
            FolderChild(
                name = f.name,
                isDirectory = f.isDirectory,
                sizeBytes = if (f.isFile) f.length() else 0L,
                modifiedMs = f.lastModified().takeIf { it > 0L },
                handle = f.path,
            )
        }

    fun reader(root: File, listing: () -> List<FolderEntry> = { FolderTree.list(root.name, root.path, ::children) }) =
        FolderImportReader(
            list = listing,
            open = { File(it.handle).inputStream() },
            rootReachable = { root.isDirectory },
        )

    fun source(root: File): ImportSource {
        val reader = reader(root)
        return ImportSource(reader, root.name) { reader.totalBytes() }
    }

    /** Writes [files] (relative path to bytes) under [root], each modified at [modifiedMs] when given. */
    fun write(root: File, files: Map<String, ByteArray>, modifiedMs: Long? = null) {
        files.forEach { (path, bytes) ->
            val f = File(root, path)
            f.parentFile?.mkdirs()
            f.writeBytes(bytes)
            if (modifiedMs != null) f.setLastModified(modifiedMs)
        }
    }

    /** A zip of [root] as a desktop zipper makes it: the folder itself is the first segment of every entry. */
    fun zipOf(root: File, modifiedMs: Long? = null): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            root.walkTopDown().filter { it.isFile }.forEach { f ->
                val name = root.name + "/" + f.relativeTo(root).invariantSeparatorsPath
                zip.putNextEntry(ZipEntry(name).apply { if (modifiedMs != null) time = modifiedMs })
                zip.write(f.readBytes())
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }
}
