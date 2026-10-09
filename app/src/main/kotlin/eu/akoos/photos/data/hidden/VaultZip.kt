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

package eu.akoos.photos.data.hidden

import net.lingala.zip4j.ZipFile
import net.lingala.zip4j.model.ZipParameters
import net.lingala.zip4j.model.enums.AesKeyStrength
import net.lingala.zip4j.model.enums.CompressionMethod
import net.lingala.zip4j.model.enums.EncryptionMethod
import java.io.File

/** One vault file going into the zip: the file on disk, the name it has inside the zip, and the
 *  capture time stamped on the entry (null leaves the file's own modification time). */
data class VaultZipEntry(val file: File, val name: String, val modifiedMs: Long?)

/**
 * The password-protected zip a forced sign-out leaves instead of plain photos.
 *
 * AES-256 with no compression, since photos and videos are already compressed and storing is quick
 * and cannot grow them. The result opens in any zip tool that supports AES (7-Zip and the like), which
 * is why the standard format is used and not one of the app's own.
 */
object VaultZip {

    /** Writes [entries] into [out] encrypted with [password]. [out] must not exist yet. */
    fun write(entries: List<VaultZipEntry>, password: CharArray, out: File) {
        require(!out.exists()) { "zip already exists" }
        val zip = ZipFile(out, password)
        for (entry in entries) {
            val params = ZipParameters().apply {
                compressionMethod = CompressionMethod.STORE
                isEncryptFiles = true
                encryptionMethod = EncryptionMethod.AES
                aesKeyStrength = AesKeyStrength.KEY_STRENGTH_256
                fileNameInZip = entry.name
                entry.modifiedMs?.takeIf { it > 0L }?.let { lastModifiedFileTime = it }
            }
            zip.addFile(entry.file, params)
        }
    }

    /**
     * Whether [zip] opens with [password] and holds every one of [entries] intact: the same names, each
     * read to its end (which checks the AES authentication code) and the same length as the source.
     * The vault copies are only given up once this is true.
     */
    fun verify(zip: File, password: CharArray, entries: List<VaultZipEntry>): Boolean {
        return try {
            val file = ZipFile(zip, password)
            if (!file.isValidZipFile) return false
            val headers = file.fileHeaders.associateBy { it.fileName }
            entries.all { entry ->
                val header = headers[entry.name] ?: return@all false
                var read = 0L
                file.getInputStream(header).use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        read += n
                    }
                }
                read == entry.file.length()
            }
        } catch (e: Exception) {
            false
        }
    }

    /** [names] made unique inside one zip: a repeat gets " (2)", " (3)" before its extension. Blank
     *  names become [fallback]. */
    fun uniqueNames(names: List<String>, fallback: (index: Int) -> String): List<String> {
        val used = HashSet<String>()
        return names.mapIndexed { index, raw ->
            val base = raw.trim().replace('/', '_').replace('\\', '_').ifBlank { fallback(index) }
            var candidate = base
            var n = 2
            while (!used.add(candidate.lowercase())) {
                val dot = base.lastIndexOf('.')
                candidate = if (dot > 0) "${base.substring(0, dot)} ($n)${base.substring(dot)}" else "$base ($n)"
                n++
            }
            candidate
        }
    }
}
