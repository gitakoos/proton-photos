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

package eu.akoos.photos.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.PipedInputStream
import java.io.PipedOutputStream
import kotlin.random.Random

/** A decrypted block comes back from the crypto process through a pipe, whole, and never through a file. */
class CryptoPipeTest {

    private fun pipe(): Pair<PipedInputStream, PipedOutputStream> {
        val out = PipedOutputStream()
        return PipedInputStream(out, 64 * 1024) to out
    }

    @Test
    fun `a block larger than the pipe buffer comes back whole`() {
        val plain = Random(1).nextBytes(3 * 1024 * 1024 + 17)
        val (input, out) = pipe()

        // The writer fills the pipe many times over on this thread; it would stall if nothing read meanwhile.
        val read = readWhileWriting(input, closeWriteEnd = { out.close() }) {
            plain.asList().chunked(100_000).forEach { out.write(it.toByteArray()) }
            true
        }

        assertArrayEquals(plain, read)
    }

    @Test
    fun `a decrypt that fails gives nothing back`() {
        val (input, out) = pipe()

        val read = readWhileWriting(input, closeWriteEnd = { out.close() }) {
            out.write(byteArrayOf(1, 2, 3))
            false
        }

        assertNull(read)
    }

    @Test
    fun `a writer that throws still ends the read`() {
        val (input, out) = pipe()

        val failure = runCatching {
            readWhileWriting(input, closeWriteEnd = { out.close() }) { throw IllegalStateException("crypto process gone") }
        }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
    }
}
