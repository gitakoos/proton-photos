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

import eu.akoos.photos.domain.importer.SidecarMeta
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.security.MessageDigest

/**
 * The result of pass 1 over a picked zip, kept on disk so an import that was interrupted (the app was
 * closed, the process was killed, the source dropped out) continues from the second pass instead of
 * reading the whole archive again just to rebuild the sidecar index.
 *
 * It is a cache in the strict sense: it lives under the app's cache directory, a missing, unreadable or
 * mismatched file is simply a fresh start, and nothing depends on it being there. A file belongs to one
 * archive: it is keyed by the picked uri and carries the archive's size, so a different file saved under
 * the same uri is not mistaken for it. It is written to a temporary name and renamed, so a kill during
 * the write never leaves a half-written file that looks valid.
 */
class ImportScanCache(private val dir: File) {

    /** The saved scan of [zipId] when one exists for an archive of [zipBytes] bytes (an unknown size, a
     *  non-positive value, matches any), else null. */
    fun load(zipId: String, zipBytes: Long): TakeoutScan? {
        val f = fileFor(zipId)
        if (!f.isFile) return null
        return try {
            DataInputStream(BufferedInputStream(f.inputStream())).use { input ->
                if (input.readInt() != MAGIC || input.readInt() != VERSION) return null
                val savedBytes = input.readLong()
                if (zipBytes > 0 && savedBytes > 0 && zipBytes != savedBytes) return null
                val media = input.readInt()
                val count = input.readInt()
                val index = HashMap<String, SidecarMeta>(count.coerceAtLeast(16))
                repeat(count) {
                    val key = input.readUTF()
                    index[key] = SidecarMeta(
                        takenMs = if (input.readBoolean()) input.readLong() else null,
                        lat = if (input.readBoolean()) input.readDouble() else null,
                        lng = if (input.readBoolean()) input.readDouble() else null,
                        description = readText(input),
                        title = readText(input),
                        favorited = input.readBoolean(),
                    )
                }
                TakeoutScan(index, media)
            }
        } catch (e: Exception) {
            runCatching { f.delete() }
            null
        }
    }

    /** Saves [scan] for [zipId]; best effort, a failure just means the next run starts from the top. */
    fun save(zipId: String, zipBytes: Long, scan: TakeoutScan) {
        runCatching {
            dir.mkdirs()
            val target = fileFor(zipId)
            val tmp = File(dir, target.name + ".tmp")
            DataOutputStream(BufferedOutputStream(tmp.outputStream())).use { out ->
                out.writeInt(MAGIC)
                out.writeInt(VERSION)
                out.writeLong(zipBytes)
                out.writeInt(scan.mediaCount)
                out.writeInt(scan.sidecars.size)
                for ((key, meta) in scan.sidecars) {
                    out.writeUTF(key)
                    out.writeBoolean(meta.takenMs != null)
                    meta.takenMs?.let(out::writeLong)
                    out.writeBoolean(meta.lat != null)
                    meta.lat?.let(out::writeDouble)
                    out.writeBoolean(meta.lng != null)
                    meta.lng?.let(out::writeDouble)
                    writeText(out, meta.description)
                    writeText(out, meta.title)
                    out.writeBoolean(meta.favorited)
                }
            }
            if (!tmp.renameTo(target)) {
                target.delete()
                tmp.renameTo(target)
            }
        }
    }

    /** Whether a saved scan exists for [zipId], without reading it. */
    fun exists(zipId: String): Boolean = fileFor(zipId).isFile

    /** Drops the saved scan of [zipId]. */
    fun delete(zipId: String) {
        runCatching { fileFor(zipId).delete() }
    }

    private fun fileFor(zipId: String): File {
        val digest = MessageDigest.getInstance("SHA-1").digest(zipId.toByteArray(Charsets.UTF_8))
        return File(dir, digest.joinToString("") { "%02x".format(it) } + ".scan")
    }

    private fun writeText(out: DataOutputStream, text: String?) {
        if (text == null) {
            out.writeInt(-1)
            return
        }
        val bytes = text.toByteArray(Charsets.UTF_8)
        out.writeInt(bytes.size)
        out.write(bytes)
    }

    private fun readText(input: DataInputStream): String? {
        val len = input.readInt()
        if (len < 0) return null
        if (len > MAX_TEXT_BYTES) throw java.io.IOException("scan cache text too long")
        val bytes = ByteArray(len)
        input.readFully(bytes)
        return String(bytes, Charsets.UTF_8)
    }

    companion object {
        /** Cache subdirectory holding the saved scans. Mirrored in the app's clear-caches sweep. */
        const val DIR_NAME = "import_scan"

        private const val MAGIC = 0x504D5343
        private const val VERSION = 1
        private const val MAX_TEXT_BYTES = 4 * 1024 * 1024
    }
}
