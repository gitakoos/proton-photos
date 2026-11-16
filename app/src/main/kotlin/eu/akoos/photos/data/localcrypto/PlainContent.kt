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

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.InputStream
import java.security.MessageDigest

/**
 * The one place every upload and every content hash reads a photo's bytes through: the bytes are checked for the
 * local encryption header first ([LocalCryptoFormat.guardPlain]), so neither upload route nor any hash ever takes
 * a local encrypted file for the photo. A refusal throws [EncryptedBytesException]; nothing was read on.
 */
object PlainContent {

    /** [uri]'s bytes, checked; null when the resolver gives no stream. */
    fun open(context: Context, uri: String): InputStream? =
        context.contentResolver.openInputStream(Uri.parse(uri))?.let { LocalCryptoFormat.guardPlain(it) }

    /** Throws [EncryptedBytesException] when [uri]'s bytes start like a local encrypted file. */
    fun requirePlain(context: Context, uri: String) {
        open(context, uri)?.close()
    }

    /** Hex SHA-1 of [input]'s bytes, checked first. Closes [input]. */
    fun sha1Hex(input: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-1")
        LocalCryptoFormat.guardPlain(input).use { stream ->
            val buffer = ByteArray(8192)
            var read: Int
            while (stream.read(buffer).also { read = it } != -1) digest.update(buffer, 0, read)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** Hex SHA-1 of [file]'s bytes, checked first. */
    fun sha1Hex(file: File): String = sha1Hex(file.inputStream())

    /**
     * Runs [copy] into a gallery row just made for it; any failure, a refusal of local encrypted bytes included,
     * runs [rollBack] (which removes the half-made row) and is thrown on.
     */
    inline fun <T> intoNewRow(rollBack: () -> Unit, copy: () -> T): T = try {
        copy()
    } catch (e: Exception) {
        runCatching { rollBack() }
        throw e
    }

    /** Hex SHA-1 of [uri]'s bytes, checked first; null when the resolver gives no stream. */
    fun sha1Hex(context: Context, uri: String): String? =
        context.contentResolver.openInputStream(Uri.parse(uri))?.let { sha1Hex(it) }
}
