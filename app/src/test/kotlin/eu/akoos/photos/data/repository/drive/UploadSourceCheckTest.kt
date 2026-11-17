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

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.security.MessageDigest

/** An upload sends exactly the bytes its file holds, on either route, or nothing at all. */
class UploadSourceCheckTest {

    private fun sha1(bytes: ByteArray) = MessageDigest.getInstance("SHA-1").digest(bytes)

    @Test
    fun `the upload sends the length the file has now, not the one recorded for it`() {
        // The editor recorded the size before its EXIF write; the file now holds 484 bytes more.
        assertEquals(10_484L, uploadLength(measuredBytes = 10_484L, claimedBytes = 10_000L))
        assertEquals(9_000L, uploadLength(measuredBytes = 9_000L, claimedBytes = 10_000L))
    }

    @Test
    fun `a length that cannot be read falls back to the recorded one, and with neither the upload is refused`() {
        assertEquals(10_000L, uploadLength(measuredBytes = null, claimedBytes = 10_000L))
        assertEquals(10_000L, uploadLength(measuredBytes = 0L, claimedBytes = 10_000L))
        assertThrows(UploadSourceChangedException::class.java) { uploadLength(measuredBytes = null, claimedBytes = 0L) }
    }

    @Test
    fun `blocks that read fewer or more bytes than the upload set out to send are refused`() {
        requireAllBytesRead(10_484L, 10_484L)
        assertThrows(UploadSourceChangedException::class.java) { requireAllBytesRead(10_000L, 10_484L) }
        assertThrows(UploadSourceChangedException::class.java) { requireAllBytesRead(10_500L, 10_484L) }
    }

    @Test
    fun `bytes that hash apart from the recorded SHA-1 are refused`() {
        requireSameContent("ABCDEF", "abcdef")
        assertThrows(UploadSourceChangedException::class.java) { requireSameContent("abcdef", "abcde0") }
        assertThrows(UploadSourceChangedException::class.java) { requireSameContent("abcdef", null) }
    }

    @Test
    fun `a changed source is never retried as it stands`() {
        assertFalse(isRetryableUploadFailure(UploadSourceChangedException("changed")))
    }

    @Test
    fun `the counting stream counts and hashes every byte read, skipped ones included`() {
        val bytes = ByteArray(20_000) { (it * 31).toByte() }
        val stream = Sha1CountingInputStream(ByteArrayInputStream(bytes))

        assertEquals(bytes[0].toInt() and 0xFF, stream.read())
        assertEquals(999L, stream.skip(999L))
        val buffer = ByteArray(4096)
        while (stream.read(buffer, 0, buffer.size) > 0) Unit

        assertEquals(20_000L, stream.count)
        assertArrayEquals(sha1(bytes), stream.sha1())
        assertArrayEquals("the hash is taken once", sha1(bytes), stream.sha1())
    }

    @Test
    fun `an SDK upload that sent other bytes than the file holds is caught`() {
        val whole = ByteArray(10_484) { 3 }
        val short = whole.copyOf(10_000)

        assertFalse(sdkSentBytesDiffer(10_484L, sha1(whole), 10_484L, sha1(whole)))
        assertTrue(sdkSentBytesDiffer(10_000L, sha1(short), 10_484L, sha1(whole)))
        assertTrue("same count, other bytes", sdkSentBytesDiffer(10_484L, sha1(ByteArray(10_484)), 10_484L, sha1(whole)))
    }
}
