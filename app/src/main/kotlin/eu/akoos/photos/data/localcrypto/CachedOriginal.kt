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
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream

/**
 * A full-size photo in the download cache. Its bytes on disk are encrypted when the phone has a local key, so
 * nothing may read [storedFile] directly: the plaintext comes from [open], [copyPlainTo], [readableUri] or
 * [withPlainFile]. Coil and every reader that opens uris through [LocalFiles.readableUri] take [fileUri] as it is.
 */
class CachedOriginal(val storedFile: File) {

    fun exists(): Boolean = storedFile.isFile && storedFile.length() > 0L

    /** The size of the photo itself, not of the file holding it. */
    val plainLength: Long get() = LocalFiles.plainLength(storedFile)

    /** The `file://` uri of the stored file, for Coil and for readers that open it through [LocalFiles.readableUri]. */
    val fileUri: Uri get() = Uri.fromFile(storedFile)

    /** The photo's bytes. Throws when the file does not open. */
    fun open(context: Context? = null): InputStream = LocalFiles.openPlain(context, storedFile)

    /** A uri any reader can open for the photo's bytes: a decrypting, seekable provider uri when it is encrypted. */
    fun readableUri(context: Context): Uri = LocalFiles.readableUri(context, fileUri)

    /** Writes the photo's bytes into [target], replacing it, and answers how many there were. Throws on a short copy. */
    fun copyPlainTo(target: File, context: Context? = null): Long {
        val copied = open(context).use { input -> FileOutputStream(target).use { input.copyTo(it) } }
        if (copied != target.length() || copied != plainLength) throw IOException("copy of the original incomplete")
        return copied
    }

    /** Whether the stored file holds the photo's bytes as they are (written before the cache was encrypted). */
    val isPlain: Boolean get() = LocalCryptoFormat.formOf(storedFile) == LocalFileForm.PLAIN

    /**
     * Runs [block] with a plaintext file of the photo, for tools that need a path: the stored file itself when it is
     * plain, else a scratch copy proved whole, deleted afterwards (and by the scratch sweep at the next start). A
     * block that writes changes the stored file in the first case, so only one whose caller deletes it after may.
     */
    inline fun <T> withPlainFile(context: Context? = null, block: (File) -> T): T {
        if (isPlain) return block(storedFile)
        val ctx = CacheFiles.contextOf(context) ?: throw LocalKeyUnavailableException(null)
        return VaultFiles.withPlainFile(ctx, storedFile, block)
    }

    fun delete(): Boolean = storedFile.delete()

    override fun equals(other: Any?): Boolean = other is CachedOriginal && other.storedFile == storedFile

    override fun hashCode(): Int = storedFile.hashCode()

    override fun toString(): String = "CachedOriginal(${storedFile.name})"
}
