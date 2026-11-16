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

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.SecureRandom
import kotlin.random.Random

class LocalCryptoFormatTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val keyId = "00112233445566778899aabbccddeeff"
    private val key = ByteArray(32).also { SecureRandom().nextBytes(it) }
    private val keys = TestKeys(mapOf(keyId to key))

    private fun sealing(meta: LocalFileMeta = LocalFileMeta(), log2: Int = LocalCryptoFormat.SEGMENT_LOG2) =
        LocalCryptoFormat.Sealing(keyId, key, LocalFileKind.VAULT, meta, segmentLog2 = log2)

    @Test
    fun `every size round trips byte for byte`() {
        val segment = 1 shl LocalCryptoFormat.SEGMENT_LOG2
        for (size in listOf(0, 1, 17, segment - 1, segment, segment + 1, 3 * segment + 5)) {
            val plain = Random(size).nextBytes(size)
            val sealed = LocalCryptoFormat.encryptBytes(plain, sealing())
            assertTrue(LocalCryptoFormat.looksEncrypted(sealed))
            assertArrayEquals("size $size", plain, LocalCryptoFormat.decryptBytes(sealed, keys))
            assertEquals(LocalCryptoFormat.overheadFor(size.toLong()), (sealed.size - size).toLong())
        }
    }

    @Test
    fun `a file reads at any position and reports its plaintext size from the header`() {
        val plain = Random(7).nextBytes(200_000)
        val file = tmp.newFile("v.jpg").apply { writeBytes(LocalCryptoFormat.encryptBytes(plain, sealing())) }
        assertEquals(200_000L, LocalCryptoFormat.plainSize(file))
        LocalCryptoFormat.openFile(file, keys).use { reader ->
            for (pos in listOf(0L, 65_535L, 65_536L, 131_000L, 199_990L)) {
                val buf = ByteArray(10)
                val n = reader.read(pos, buf, 0, 10)
                assertTrue(n > 0)
                assertArrayEquals(plain.copyOfRange(pos.toInt(), pos.toInt() + n), buf.copyOf(n))
            }
            assertEquals(-1, reader.read(200_000L, ByteArray(4), 0, 4))
            val streamed = reader.inputStream().readBytes()
            assertArrayEquals(plain, streamed)
        }
    }

    @Test
    fun `the header carries the vault record of the photo`() {
        val meta = LocalFileMeta("IMG_1.jpg", "image/jpeg", "Camera", "link-id", 1_700_000_000_000L)
        val file = tmp.newFile("m.jpg").apply { writeBytes(LocalCryptoFormat.encryptBytes(byteArrayOf(1, 2, 3), sealing(meta))) }
        LocalCryptoFormat.openFile(file, keys).use { assertEquals(meta, it.meta) }
        assertEquals(keyId, LocalCryptoFormat.keyIdOf(file))
    }

    @Test
    fun `a truncated file fails instead of reading short`() {
        val plain = Random(3).nextBytes(150_000)
        val sealed = LocalCryptoFormat.encryptBytes(plain, sealing())
        val segment = (1 shl LocalCryptoFormat.SEGMENT_LOG2) + LocalCryptoFormat.TAG_BYTES
        val header = sealed.size - plain.size - 3 * LocalCryptoFormat.TAG_BYTES
        // Cut exactly at a segment boundary: without the last-segment flag this would read as a shorter file.
        for (cut in listOf(sealed.size - 1, sealed.size - 100, header + 2 * segment, header + segment, header)) {
            val failure = runCatching { LocalCryptoFormat.decryptBytes(sealed.copyOf(cut), keys) }.exceptionOrNull()
            assertTrue("cut $cut", failure is LocalFileDamagedException || failure is java.io.IOException)
        }
    }

    @Test
    fun `a flipped bit anywhere is damage, never different bytes`() {
        val plain = Random(5).nextBytes(70_000)
        val sealed = LocalCryptoFormat.encryptBytes(plain, sealing(LocalFileMeta(originalName = "a.jpg")))
        for (at in listOf(10, 40, 70, 100, sealed.size / 2, sealed.size - 1)) {
            val bad = sealed.copyOf().also { it[at] = (it[at].toInt() xor 1).toByte() }
            val result = runCatching { LocalCryptoFormat.decryptBytes(bad, keys) }
            assertTrue("byte $at", result.isFailure)
        }
    }

    @Test
    fun `a file of a key this phone does not hold is locked, not damaged`() {
        val sealed = LocalCryptoFormat.encryptBytes(byteArrayOf(9, 9), sealing())
        val failure = runCatching { LocalCryptoFormat.decryptBytes(sealed, TestKeys(emptyMap())) }.exceptionOrNull()
        assertTrue(failure is LocalKeyLockedException)
        assertEquals(keyId, (failure as LocalKeyLockedException).keyId)
    }

    @Test
    fun `a wrong master key under the right id is damage`() {
        val sealed = LocalCryptoFormat.encryptBytes(byteArrayOf(9, 9), sealing())
        val other = TestKeys(mapOf(keyId to ByteArray(32)))
        assertTrue(runCatching { LocalCryptoFormat.decryptBytes(sealed, other) }.exceptionOrNull() is LocalFileDamagedException)
    }

    @Test
    fun `plaintext is never taken for an encrypted file`() {
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0, 0x10)
        assertFalse(LocalCryptoFormat.looksEncrypted(jpeg))
        val file = tmp.newFile("p.jpg").apply { writeBytes(jpeg) }
        assertFalse(LocalCryptoFormat.isEncrypted(file))
        assertNull(LocalCryptoFormat.plainSize(file))
        assertNull(LocalCryptoFormat.keyIdOf(file))
    }

    @Test
    fun `the streaming writer and the whole-file reader agree`() {
        val plain = Random(11).nextBytes(300_001)
        val out = ByteArrayOutputStream()
        val digest = LocalCryptoFormat.encrypt(plain.inputStream(), out, sealing())
        val file = File(tmp.root, "s.bin").apply { writeBytes(out.toByteArray()) }
        val copy = ByteArrayOutputStream()
        val read = LocalCryptoFormat.decryptFile(file, keys, copy)
        assertTrue(read.sameAs(digest))
        assertTrue(read.sameAs(PlainDigest.of(plain.inputStream())))
        assertArrayEquals(plain, copy.toByteArray())
    }

    @Test
    fun `a file encrypted twice differs, so nothing repeats between copies`() {
        val plain = ByteArray(1000)
        val a = LocalCryptoFormat.encryptBytes(plain, sealing())
        val b = LocalCryptoFormat.encryptBytes(plain, sealing())
        assertFalse(a.contentEquals(b))
    }
}

/** Master keys held in memory, for tests. */
class TestKeys(private val keys: Map<String, ByteArray>) : MasterKeys {
    override fun keyFor(keyId: String): ByteArray = keys[keyId] ?: throw LocalKeyLockedException(keyId)
}
