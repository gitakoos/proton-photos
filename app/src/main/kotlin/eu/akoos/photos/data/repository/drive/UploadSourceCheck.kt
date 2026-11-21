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

package eu.akoos.photos.data.repository.drive

import java.io.FilterInputStream
import java.io.InputStream
import java.security.MessageDigest

/**
 * The file an upload reads is not the one its caller described: its length or its SHA-1 differs from
 * what the caller recorded, or it changed while it was read. Nothing is committed for it, and it is never
 * retried as it stands: the caller measures and hashes the file again on its next pass.
 */
open class UploadSourceChangedException(message: String) : IllegalStateException(message)

/**
 * The file holds no bytes at all. Drive takes no file without a block or a thumbnail, so it can only be
 * backed up once its bytes change; trying again as it stands fails the same way every time.
 */
class EmptySourceFileException : UploadSourceChangedException("the file is empty")

/**
 * The byte count an upload sends: the length the file has now ([measuredBytes]), whatever the caller
 * recorded ([claimedBytes]). A file whose length cannot be read falls back to the recorded one; with
 * neither, the upload is refused, since reading "up to one block" would cut anything larger short. A file
 * measured and recorded at zero bytes is refused as empty.
 */
internal fun uploadLength(measuredBytes: Long?, claimedBytes: Long): Long = when {
    measuredBytes != null && measuredBytes > 0L -> measuredBytes
    claimedBytes > 0L -> claimedBytes
    measuredBytes == 0L -> throw EmptySourceFileException()
    else -> throw UploadSourceChangedException("the file's length cannot be read")
}

/** Refuses an upload whose blocks did not read exactly [expectedBytes], the length it set out to send. */
internal fun requireAllBytesRead(readBytes: Long, expectedBytes: Long) {
    if (readBytes != expectedBytes) {
        throw UploadSourceChangedException("read $readBytes of $expectedBytes bytes; the file changed during the upload")
    }
}

/** Refuses an upload whose bytes do not hash to the SHA-1 its caller recorded and Drive is told. */
internal fun requireSameContent(expectedSha1: String, actualSha1: String?) {
    if (actualSha1 == null || !actualSha1.equals(expectedSha1, ignoreCase = true)) {
        throw UploadSourceChangedException("the file's content is not the one hashed for this upload")
    }
}

/**
 * Passes a stream through while counting its bytes and hashing them with SHA-1, so an upload that hands
 * the stream to another reader still knows exactly which bytes left.
 */
internal class Sha1CountingInputStream(input: InputStream) : FilterInputStream(input) {
    private val digest = MessageDigest.getInstance("SHA-1")

    /** Bytes read through so far. */
    var count: Long = 0L
        private set

    override fun read(): Int {
        val b = super.read()
        if (b >= 0) {
            digest.update(b.toByte())
            count++
        }
        return b
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        val n = super.read(b, off, len)
        if (n > 0) {
            digest.update(b, off, n)
            count += n
        }
        return n
    }

    // Skipped bytes are read through, so they are counted and hashed like the rest.
    override fun skip(n: Long): Long {
        if (n <= 0L) return 0L
        val buffer = ByteArray(minOf(n, 8192L).toInt())
        var skipped = 0L
        while (skipped < n) {
            val r = read(buffer, 0, minOf(buffer.size.toLong(), n - skipped).toInt())
            if (r <= 0) break
            skipped += r
        }
        return skipped
    }

    override fun markSupported(): Boolean = false

    private var finished: ByteArray? = null

    /** SHA-1 of the bytes read, taken once the reader is done; later reads no longer count. */
    @Synchronized
    fun sha1(): ByteArray = finished ?: digest.digest().also { finished = it }
}
