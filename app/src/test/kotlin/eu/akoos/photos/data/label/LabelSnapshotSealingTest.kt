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

package eu.akoos.photos.data.label

import eu.akoos.photos.data.mlsync.EnvelopeCrypto
import eu.akoos.photos.data.mlsync.EnvelopeOpenKeys
import eu.akoos.photos.data.mlsync.EnvelopeSealKeys
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import kotlin.random.Random

class LabelSnapshotSealingTest {

    private val crypto = FakeCrypto()

    private val snapshot = LabelSnapshot(
        deviceId = "0b5f3c2e-1111-4a4a-9c9c-123456789abc",
        deviceLabel = "Phone",
        createdAtMillis = 12L,
        photos = listOf(
            LabelSnapshotPhoto("link-1", listOf("Café", "beach")),
            LabelSnapshotPhoto("link-2", emptyList()),
        ),
    )

    private fun sealed(sealKeys: EnvelopeSealKeys = aliceSeal()): ByteArray {
        val out = ByteArrayOutputStream()
        LabelSnapshotSealing.seal(snapshot, crypto, sealKeys, out)
        return out.toByteArray()
    }

    private fun aliceSeal() = EnvelopeSealKeys(recipientPublicKey = "pub:alice", signingKey = "alice".toByteArray())

    private fun open(bytes: ByteArray, who: String = "alice", verifyAs: String = "alice") = LabelSnapshotSealing.open(
        ByteArrayInputStream(bytes),
        crypto,
        EnvelopeOpenKeys(decryptionKeys = listOf(who.toByteArray()), verificationKeys = listOf("pub:$verifyAs")),
    )

    @Test
    fun `a sealed snapshot opens back to the same labels`() {
        val opened = open(sealed())

        assertEquals(LabelSnapshotOpened.Snapshot(snapshot), opened)
    }

    @Test
    fun `the sealed bytes do not hold the labels in the clear`() {
        val text = String(sealed(), Charsets.ISO_8859_1)

        assertTrue("beach" !in text)
    }

    @Test
    fun `another account's key does not open it`() {
        assertEquals(LabelSnapshotOpened.WrongKey, open(sealed(), who = "mallory"))
    }

    @Test
    fun `an altered file is damaged`() {
        val bytes = sealed()
        bytes[bytes.size / 2] = (bytes[bytes.size / 2].toInt() xor 0x55).toByte()

        assertEquals(LabelSnapshotOpened.Damaged, open(bytes))
    }

    @Test
    fun `a file cut short is damaged`() {
        val bytes = sealed()

        assertEquals(LabelSnapshotOpened.Damaged, open(bytes.copyOf(bytes.size - 10)))
    }

    @Test
    fun `a file that is not an envelope is damaged`() {
        assertEquals(LabelSnapshotOpened.Damaged, open("plain text".toByteArray()))
    }

    @Test
    fun `a signature by another key is damaged`() {
        val forged = sealed(EnvelopeSealKeys(recipientPublicKey = "pub:alice", signingKey = "mallory".toByteArray()))

        assertEquals(LabelSnapshotOpened.Damaged, open(forged))
    }

    @Test
    fun `an envelope holding a newer snapshot format reads as too new`() {
        val newer = """{"v":99,"d":"x","n":"x","c":1,"p":[]}""".toByteArray()
        val out = ByteArrayOutputStream()
        eu.akoos.photos.data.mlsync.MlSnapshotEnvelope.seal(ByteArrayInputStream(newer), out, crypto, aliceSeal())

        assertEquals(LabelSnapshotOpened.TooNew, open(out.toByteArray()))
    }

    @Test
    fun `an envelope holding something that is not a snapshot is damaged`() {
        val out = ByteArrayOutputStream()
        eu.akoos.photos.data.mlsync.MlSnapshotEnvelope.seal(ByteArrayInputStream("{}".toByteArray()), out, crypto, aliceSeal())

        assertEquals(LabelSnapshotOpened.Damaged, open(out.toByteArray()))
    }

    @Test
    fun `sealing the same snapshot twice gives different bytes`() {
        assertTrue(!sealed().contentEquals(sealed()))
        assertArrayEquals(LabelSnapshotCodec.encode(snapshot), LabelSnapshotCodec.encode(snapshot))
    }

    /**
     * A reversible stand-in for OpenPGP, as the other snapshot tests use: a key is a name, a key packet names
     * its recipient, a chunk is the plaintext XOR the session key behind a 4-byte check of that key, and a
     * signature is SHA-256 over the signer's name and the data.
     */
    private class FakeCrypto : EnvelopeCrypto {
        private val random = Random(13)

        override fun generateSessionKey(): ByteArray = ByteArray(16).also { random.nextBytes(it) }

        override fun wrapSessionKey(sessionKey: ByteArray, recipientPublicKey: String): ByteArray =
            framed(recipientPublicKey.removePrefix("pub:").toByteArray(Charsets.UTF_8), sessionKey)

        override fun unwrapSessionKey(wrappedKey: ByteArray, decryptionKey: ByteArray): ByteArray? {
            val (recipient, sessionKey) = unframed(wrappedKey) ?: return null
            return if (recipient.contentEquals(decryptionKey)) sessionKey else null
        }

        override fun encryptChunk(plain: ByteArray, sessionKey: ByteArray): ByteArray =
            keyCheck(sessionKey) + xor(plain, sessionKey)

        override fun decryptChunk(cipher: ByteArray, sessionKey: ByteArray): ByteArray? {
            if (cipher.size < CHECK_BYTES || !cipher.copyOf(CHECK_BYTES).contentEquals(keyCheck(sessionKey))) return null
            return xor(cipher.copyOfRange(CHECK_BYTES, cipher.size), sessionKey)
        }

        override fun sign(data: ByteArray, signingKey: ByteArray): ByteArray = framed(signingKey, sha256(signingKey + data))

        override fun verify(data: ByteArray, signature: ByteArray, verificationKeys: List<String>): Boolean {
            val (signer, tag) = unframed(signature) ?: return false
            return "pub:${String(signer, Charsets.UTF_8)}" in verificationKeys && tag.contentEquals(sha256(signer + data))
        }

        private fun keyCheck(sessionKey: ByteArray): ByteArray = sha256(sessionKey).copyOf(CHECK_BYTES)

        private fun xor(data: ByteArray, key: ByteArray): ByteArray =
            ByteArray(data.size) { i -> (data[i].toInt() xor key[i % key.size].toInt()).toByte() }

        private fun framed(field: ByteArray, rest: ByteArray): ByteArray = byteArrayOf(field.size.toByte()) + field + rest

        private fun unframed(bytes: ByteArray): Pair<ByteArray, ByteArray>? {
            if (bytes.isEmpty()) return null
            val length = bytes[0].toInt() and 0xFF
            if (1 + length > bytes.size) return null
            return bytes.copyOfRange(1, 1 + length) to bytes.copyOfRange(1 + length, bytes.size)
        }

        private fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)

        private companion object {
            const val CHECK_BYTES = 4
        }
    }
}
