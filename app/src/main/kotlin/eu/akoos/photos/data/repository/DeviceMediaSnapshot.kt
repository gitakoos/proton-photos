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

import eu.akoos.photos.domain.entity.LocalMediaItem
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * The device list as the last complete scan found it, kept in one app-private file so a cold start can show
 * the whole library at once instead of only the newest photos while the scan of a big library runs.
 *
 * It is only ever a first picture: the scan that follows replaces it, so a file removed while the app was
 * closed shows for those few seconds and is then gone. [environment] is the grants and volumes the scan ran
 * under; a snapshot written under others (a permission taken back, a card removed) is not read.
 *
 * Format: a magic, a version, the environment, then the strings that repeat (folder names, MIME types and the
 * content roots of the uris) once each, and per item its fields with those strings as indexes into that table.
 */
internal object DeviceMediaSnapshot {

    private const val MAGIC = 0x50464D53 // "PFMS"
    private const val VERSION = 1

    /** An item count beyond this reads as a damaged file rather than a library. */
    private const val MAX_ITEMS = 5_000_000
    private const val MAX_TAGS = 64
    private const val CANCEL_CHECK_EVERY = 4096

    fun write(out: OutputStream, environment: String, items: List<LocalMediaItem>) {
        val table = LinkedHashMap<String, Int>()
        fun index(s: String): Int = table.getOrPut(s) { table.size }
        // Uris are "<root>/<id>" almost always; the root goes into the table and the id is a number.
        val roots = IntArray(items.size)
        val ids = LongArray(items.size)
        for ((i, item) in items.withIndex()) {
            val slash = item.uri.lastIndexOf('/')
            val id = if (slash > 0) item.uri.substring(slash + 1).toLongOrNull() else null
            if (id != null && id >= 0 && item.uri.substring(slash + 1) == id.toString()) {
                roots[i] = index(item.uri.substring(0, slash))
                ids[i] = id
            } else {
                roots[i] = index(item.uri)
                ids[i] = -1L
            }
            index(item.mimeType)
            item.bucketName?.let { index(it) }
        }
        val data = DataOutputStream(BufferedOutputStream(out, 1 shl 16))
        data.writeInt(MAGIC)
        data.writeInt(VERSION)
        data.writeUTF(environment)
        data.writeInt(table.size)
        for (s in table.keys) data.writeUTF(s)
        data.writeInt(items.size)
        for ((i, item) in items.withIndex()) {
            data.writeInt(roots[i])
            data.writeLong(ids[i])
            data.writeLong(item.dateTaken)
            data.writeUTF(item.displayName)
            data.writeInt(table.getValue(item.mimeType))
            data.writeLong(item.sizeBytes)
            data.writeInt(item.bucketName?.let { table.getValue(it) } ?: -1)
            data.writeInt(item.width)
            data.writeInt(item.height)
            data.writeLong(item.duration)
            data.writeLong(item.dateModified)
            data.writeBoolean(item.dateTakenIsExplicit)
            writeTags(data, item.tags)
            writeTags(data, item.userTags)
        }
        data.flush()
    }

    /** The items [input] holds, or null when it is no snapshot of this format or was written under another
     *  [environment]. Never throws for a damaged file. */
    fun read(
        input: InputStream,
        environment: String,
        maxItems: Int = MAX_ITEMS,
        isActive: () -> Boolean = { true },
    ): List<LocalMediaItem>? = try {
        val data = DataInputStream(BufferedInputStream(input, 1 shl 16))
        if (data.readInt() != MAGIC || data.readInt() != VERSION || data.readUTF() != environment) {
            null
        } else {
            val tableSize = data.readInt()
            if (tableSize < 0 || tableSize > MAX_ITEMS) throw IOException("bad table")
            val table = Array(tableSize) { data.readUTF() }
            val count = data.readInt()
            if (count < 0 || count > MAX_ITEMS) throw IOException("bad count")
            if (count > maxItems) return null
            val items = ArrayList<LocalMediaItem>(count)
            for (n in 0 until count) {
                // A read no one waits for any more stops instead of building the rest of a large list.
                if (n % CANCEL_CHECK_EVERY == 0 && !isActive()) return null
                val root = table[data.readInt()]
                val id = data.readLong()
                val dateTaken = data.readLong()
                val name = data.readUTF()
                val mime = table[data.readInt()]
                val size = data.readLong()
                val bucket = data.readInt().let { if (it < 0) null else table[it] }
                val width = data.readInt()
                val height = data.readInt()
                val duration = data.readLong()
                val modified = data.readLong()
                val explicit = data.readBoolean()
                val tags = readTags(data)
                val userTags = readTags(data)
                items += LocalMediaItem(
                    uri = if (id >= 0) "$root/$id" else root,
                    dateTaken = dateTaken,
                    displayName = name,
                    mimeType = mime,
                    sizeBytes = size,
                    bucketName = bucket,
                    width = width,
                    height = height,
                    duration = duration,
                    dateModified = modified,
                    tags = tags,
                    userTags = userTags,
                    dateTakenIsExplicit = explicit,
                )
            }
            items
        }
    } catch (e: IOException) {
        null
    } catch (e: IndexOutOfBoundsException) {
        null
    } catch (e: IllegalArgumentException) {
        null
    }

    /** Writes [items] to [file] whole or not at all: a temp file beside it, renamed over it once complete. */
    fun writeFile(file: File, environment: String, items: List<LocalMediaItem>) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        try {
            tmp.outputStream().use { write(it, environment, items) }
            if (!tmp.renameTo(file)) {
                file.delete()
                if (!tmp.renameTo(file)) throw IOException("rename failed")
            }
        } finally {
            tmp.delete()
        }
    }

    fun readFile(
        file: File,
        environment: String,
        maxItems: Int = MAX_ITEMS,
        isActive: () -> Boolean = { true },
    ): List<LocalMediaItem>? =
        if (!file.isFile) null else runCatching { file.inputStream().use { read(it, environment, maxItems, isActive) } }.getOrNull()

    private fun writeTags(data: DataOutputStream, tags: Set<Int>) {
        val kept = if (tags.size > MAX_TAGS) tags.take(MAX_TAGS) else tags
        data.writeByte(kept.size)
        for (t in kept) data.writeInt(t)
    }

    private fun readTags(data: DataInputStream): Set<Int> {
        val n = data.readUnsignedByte()
        if (n == 0) return emptySet()
        if (n > MAX_TAGS) throw EOFException("bad tags")
        return HashSet<Int>(n * 2).apply { repeat(n) { add(data.readInt()) } }
    }
}
