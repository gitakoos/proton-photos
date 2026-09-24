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

package eu.akoos.photos.data.mlsync

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.FilterInputStream
import java.io.InputStream
import java.security.MessageDigest
import java.util.Random

private const val ALICE = "alice"
private const val BOB = "bob"
private const val CAROL = "carol"
private const val MALLORY = "mallory"

/** The clear-text fields ahead of the key packet length: magic, version and chunk size. */
private const val FIXED_HEADER_BYTES = 8 + 4 + 4

/**
 * Coverage for the ML snapshot envelope: round-trips across the chunk boundaries (empty, under one chunk,
 * exactly one chunk, several chunks, the real default chunk size, sources that return short reads), the
 * clear-text header, the refusal of every truncation, of a dropped, altered or reordered chunk, of trailing
 * data and of a trailer the account did not sign, and the key search that tries every offered key.
 *
 * The real OpenPGP (gopenpgp) is native and absent from a JVM unit test, so [FakeEnvelopeCrypto] stands in
 * with a reversible transform. Its chunks carry no integrity of their own, so an altered byte is caught by
 * the envelope's hashes and signature rather than by the fake.
 */
class MlSnapshotEnvelopeTest {

    private val chunk = 16

    private fun payload(size: Int): ByteArray = ByteArray(size) { (it * 31 + 7).toByte() }

    private fun sealKeys(owner: String = ALICE) = EnvelopeSealKeys(publicKey(owner), privateKey(owner))

    private fun openKeys(decrypting: List<String> = listOf(ALICE), verifying: List<String> = decrypting) =
        EnvelopeOpenKeys(decrypting.map(::privateKey), verifying.map(::publicKey))

    private fun seal(
        plain: ByteArray,
        keys: EnvelopeSealKeys = sealKeys(),
        crypto: EnvelopeCrypto = FakeEnvelopeCrypto(),
        chunkSize: Int = chunk,
        source: InputStream = ByteArrayInputStream(plain),
    ): ByteArray {
        val out = ByteArrayOutputStream()
        assertEquals(plain.size.toLong(), MlSnapshotEnvelope.seal(source, out, crypto, keys, chunkSize))
        return out.toByteArray()
    }

    private class Opening(val result: EnvelopeOpenResult, val plain: ByteArray)

    private fun open(
        envelope: ByteArray,
        keys: EnvelopeOpenKeys = openKeys(),
        crypto: EnvelopeCrypto = FakeEnvelopeCrypto(),
        source: InputStream = ByteArrayInputStream(envelope),
    ): Opening {
        val out = ByteArrayOutputStream()
        val result = MlSnapshotEnvelope.open(source, out, crypto, keys)
        return Opening(result, out.toByteArray())
    }

    private fun result(envelope: ByteArray, keys: EnvelopeOpenKeys = openKeys()): EnvelopeOpenResult =
        open(envelope, keys).result

    private fun assertRoundTrip(size: Int, expectedChunks: Int, chunkSize: Int = chunk) {
        val plain = payload(size)
        val envelope = seal(plain, chunkSize = chunkSize)
        val opened = open(envelope)
        assertEquals(EnvelopeOpenResult.Opened(size.toLong()), opened.result)
        assertArrayEquals(plain, opened.plain)
        assertEquals(expectedChunks, layout(envelope).chunks.size)
    }

    /** An envelope split along its documented layout, so a test can drop, swap or alter one part. */
    private class Layout(
        val header: ByteArray,
        val chunks: List<ByteArray>,
        val hashes: List<ByteArray>,
        val signature: ByteArray,
    ) {
        fun bytes(
            chunks: List<ByteArray> = this.chunks,
            hashes: List<ByteArray> = this.hashes,
            signature: ByteArray = this.signature,
            trailer: Boolean = true,
        ): ByteArray {
            val buffer = ByteArrayOutputStream()
            DataOutputStream(buffer).run {
                write(header)
                for (record in chunks) {
                    writeByte(1)
                    writeInt(record.size)
                    write(record)
                }
                if (trailer) {
                    writeByte(2)
                    writeInt(hashes.size)
                    hashes.forEach { write(it) }
                    writeInt(signature.size)
                    write(signature)
                }
            }
            return buffer.toByteArray()
        }
    }

    private fun layout(envelope: ByteArray): Layout {
        val inp = DataInputStream(ByteArrayInputStream(envelope))
        inp.skipBytes(FIXED_HEADER_BYTES)
        val keyPacketLength = inp.readInt()
        inp.skipBytes(keyPacketLength)
        val header = envelope.copyOf(FIXED_HEADER_BYTES + 4 + keyPacketLength)
        val chunks = ArrayList<ByteArray>()
        var tag = inp.readUnsignedByte()
        while (tag == 1) {
            chunks += ByteArray(inp.readInt()).also { inp.readFully(it) }
            tag = inp.readUnsignedByte()
        }
        assertEquals(2, tag)
        val hashes = List(inp.readInt()) { _ -> ByteArray(32).also { inp.readFully(it) } }
        val signature = ByteArray(inp.readInt()).also { inp.readFully(it) }
        assertEquals(-1, inp.read())
        return Layout(header, chunks, hashes, signature)
    }

    private fun headerBytes(version: Int, chunkSize: Int, keyPacketLength: Int): ByteArray {
        val buffer = ByteArrayOutputStream()
        DataOutputStream(buffer).run {
            write(MlSnapshotEnvelope.MAGIC.toByteArray(Charsets.US_ASCII))
            writeInt(version)
            writeInt(chunkSize)
            writeInt(keyPacketLength)
        }
        return buffer.toByteArray()
    }

    private fun flipLastByte(bytes: ByteArray): ByteArray =
        bytes.copyOf().also { it[it.lastIndex] = (it[it.lastIndex].toInt() xor 1).toByte() }

    @Test
    fun `an empty payload round-trips with no chunks`() {
        assertRoundTrip(size = 0, expectedChunks = 0)
    }

    @Test
    fun `a payload under one chunk round-trips as a single chunk`() {
        assertRoundTrip(size = 5, expectedChunks = 1)
    }

    @Test
    fun `a payload of exactly one chunk size round-trips as a single chunk`() {
        assertRoundTrip(size = chunk, expectedChunks = 1)
    }

    @Test
    fun `a multi-chunk payload round-trips and only its last chunk is short`() {
        assertRoundTrip(size = 3 * chunk + 5, expectedChunks = 4)
        // The fake's ciphertext is a 4-byte key check plus the plaintext, so this reads as 16, 16, 16 and 5.
        val sizes = layout(seal(payload(3 * chunk + 5))).chunks.map { it.size }
        assertEquals(listOf(20, 20, 20, 9), sizes)
    }

    @Test
    fun `a payload just over the default chunk size round-trips as two chunks`() {
        assertRoundTrip(
            size = MlSnapshotEnvelope.DEFAULT_CHUNK_SIZE + 1,
            expectedChunks = 2,
            chunkSize = MlSnapshotEnvelope.DEFAULT_CHUNK_SIZE,
        )
    }

    @Test
    fun `short reads on either side still fill every chunk and open`() {
        val plain = payload(3 * chunk + 5)
        val trickled = seal(plain, source = TricklingInputStream(plain, step = 3))
        assertArrayEquals(seal(plain), trickled)
        val opened = open(trickled, source = TricklingInputStream(trickled, step = 5))
        assertEquals(EnvelopeOpenResult.Opened(plain.size.toLong()), opened.result)
        assertArrayEquals(plain, opened.plain)
    }

    @Test
    fun `the header names the file type and version and chunk size in the clear`() {
        val inp = DataInputStream(ByteArrayInputStream(seal(payload(40))))
        val magic = ByteArray(8).also { inp.readFully(it) }
        assertEquals("PPMLSEAL", String(magic, Charsets.US_ASCII))
        assertEquals(MlSnapshotEnvelope.VERSION, inp.readInt())
        assertEquals(chunk, inp.readInt())
        assertTrue(inp.readInt() > 0)
    }

    @Test
    fun `each envelope gets its own session key`() {
        val crypto = FakeEnvelopeCrypto()
        val first = layout(seal(payload(40), crypto = crypto))
        val second = layout(seal(payload(40), crypto = crypto))
        assertEquals(2, crypto.sessionKeysGenerated)
        assertFalse(first.header.contentEquals(second.header))
        assertFalse(first.chunks[0].contentEquals(second.chunks[0]))
    }

    @Test
    fun `dropping the trailer is rejected`() {
        val l = layout(seal(payload(3 * chunk + 5)))
        assertEquals(EnvelopeOpenResult.Corrupt, result(l.bytes(trailer = false)))
    }

    @Test
    fun `every truncation of an envelope is rejected`() {
        val envelope = seal(payload(2 * chunk + 3))
        for (length in 0 until envelope.size) {
            val expected = if (length < 8) EnvelopeOpenResult.NotAnEnvelope else EnvelopeOpenResult.Corrupt
            assertEquals("cut to $length bytes", expected, result(envelope.copyOf(length)))
        }
    }

    @Test
    fun `a dropped chunk is rejected with or without its hash`() {
        val l = layout(seal(payload(3 * chunk + 5)))
        val withoutSecond = l.chunks.filterIndexed { i, _ -> i != 1 }
        assertEquals(EnvelopeOpenResult.Corrupt, result(l.bytes(chunks = withoutSecond)))
        val hashesWithoutSecond = l.hashes.filterIndexed { i, _ -> i != 1 }
        assertEquals(EnvelopeOpenResult.Corrupt, result(l.bytes(chunks = withoutSecond, hashes = hashesWithoutSecond)))
        // Dropping the short last chunk leaves a well-formed run of full chunks; only the signature catches it.
        assertEquals(
            EnvelopeOpenResult.Corrupt,
            result(l.bytes(chunks = l.chunks.dropLast(1), hashes = l.hashes.dropLast(1))),
        )
    }

    @Test
    fun `an altered byte in a chunk is rejected even with a recomputed hash list`() {
        val l = layout(seal(payload(3 * chunk + 5)))
        val altered = l.chunks.toMutableList().also { it[1] = flipLastByte(it[1]) }
        assertEquals(EnvelopeOpenResult.Corrupt, result(l.bytes(chunks = altered)))
        assertEquals(EnvelopeOpenResult.Corrupt, result(l.bytes(chunks = altered, hashes = altered.map(::sha256))))
    }

    @Test
    fun `an altered hash in the trailer list is rejected`() {
        val l = layout(seal(payload(3 * chunk + 5)))
        val altered = l.hashes.toMutableList().also { it[0] = flipLastByte(it[0]) }
        assertEquals(EnvelopeOpenResult.Corrupt, result(l.bytes(hashes = altered)))
    }

    @Test
    fun `reordered chunks are rejected even with the hash list reordered to match`() {
        val l = layout(seal(payload(3 * chunk + 5)))
        val swapped = listOf(l.chunks[1], l.chunks[0]) + l.chunks.drop(2)
        assertEquals(EnvelopeOpenResult.Corrupt, result(l.bytes(chunks = swapped)))
        val swappedHashes = listOf(l.hashes[1], l.hashes[0]) + l.hashes.drop(2)
        assertEquals(EnvelopeOpenResult.Corrupt, result(l.bytes(chunks = swapped, hashes = swappedHashes)))
    }

    @Test
    fun `a trailer not signed by the account is rejected`() {
        val plain = payload(2 * chunk + 3)
        val forged = seal(plain, keys = EnvelopeSealKeys(publicKey(ALICE), privateKey(MALLORY)))
        assertEquals(EnvelopeOpenResult.Corrupt, result(forged, openKeys(listOf(ALICE))))
        // The same file opens once its signer is trusted, so the refusal above is the signature alone.
        val trusted = open(forged, openKeys(decrypting = listOf(ALICE), verifying = listOf(ALICE, MALLORY)))
        assertEquals(EnvelopeOpenResult.Opened(plain.size.toLong()), trusted.result)

        val l = layout(seal(plain))
        assertEquals(EnvelopeOpenResult.Corrupt, result(l.bytes(signature = flipLastByte(l.signature))))
    }

    @Test
    fun `trailing bytes after the trailer are rejected`() {
        val envelope = seal(payload(2 * chunk + 3))
        assertEquals(EnvelopeOpenResult.Corrupt, result(envelope + byteArrayOf(0)))
    }

    @Test
    fun `the session key opens with the matching key among several`() {
        val plain = payload(2 * chunk + 3)
        val crypto = FakeEnvelopeCrypto()
        val opened = open(seal(plain, keys = sealKeys(BOB)), openKeys(listOf(ALICE, BOB, CAROL)), crypto)
        assertEquals(EnvelopeOpenResult.Opened(plain.size.toLong()), opened.result)
        assertArrayEquals(plain, opened.plain)
        assertEquals(listOf(ALICE, BOB), crypto.unwrapAttempts)
    }

    @Test
    fun `no matching key is the wrong key and writes nothing`() {
        val crypto = FakeEnvelopeCrypto()
        val opened = open(seal(payload(2 * chunk + 3), keys = sealKeys(BOB)), openKeys(listOf(ALICE, CAROL)), crypto)
        assertEquals(EnvelopeOpenResult.WrongKey, opened.result)
        assertEquals(0, opened.plain.size)
        assertEquals(listOf(ALICE, CAROL), crypto.unwrapAttempts)
    }

    @Test
    fun `a key that unwraps to a wrong session key does not hide the matching key`() {
        val plain = payload(2 * chunk + 3)
        val crypto = FakeEnvelopeCrypto(garbageUnwrapKeys = setOf(ALICE))
        val opened = open(seal(plain, keys = sealKeys(BOB)), openKeys(listOf(ALICE, BOB)), crypto)
        assertEquals(EnvelopeOpenResult.Opened(plain.size.toLong()), opened.result)
        assertArrayEquals(plain, opened.plain)
    }

    @Test
    fun `a wrong session key alone is refused rather than trusted`() {
        val crypto = FakeEnvelopeCrypto(garbageUnwrapKeys = setOf(ALICE))
        val keys = openKeys(decrypting = listOf(ALICE), verifying = listOf(ALICE, BOB))
        val opened = open(seal(payload(2 * chunk + 3), keys = sealKeys(BOB)), keys, crypto)
        assertEquals(EnvelopeOpenResult.Corrupt, opened.result)
    }

    @Test
    fun `a file with the wrong magic is not an envelope`() {
        val wrongMagic = "NOTASEAL".toByteArray(Charsets.US_ASCII) + ByteArray(32)
        assertEquals(EnvelopeOpenResult.NotAnEnvelope, result(wrongMagic))
        // A plaintext snapshot's own magic is not mistaken for an envelope.
        val plaintextSnapshot = "PPMLSNAP".toByteArray(Charsets.US_ASCII) + ByteArray(32)
        assertEquals(EnvelopeOpenResult.NotAnEnvelope, result(plaintextSnapshot))
        assertEquals(EnvelopeOpenResult.NotAnEnvelope, result(payload(64)))
        assertEquals(EnvelopeOpenResult.NotAnEnvelope, result(ByteArray(0)))
    }

    @Test
    fun `a newer envelope version is reported as unsupported`() {
        val bytes = headerBytes(MlSnapshotEnvelope.VERSION + 1, chunk, keyPacketLength = 8) + ByteArray(8)
        assertEquals(EnvelopeOpenResult.UnsupportedVersion(MlSnapshotEnvelope.VERSION + 1), result(bytes))
    }

    @Test
    fun `crafted lengths are refused before anything is allocated`() {
        val version = MlSnapshotEnvelope.VERSION
        val hugeChunkSize = headerBytes(version, MlSnapshotEnvelope.MAX_CHUNK_SIZE + 1, keyPacketLength = 8)
        assertEquals(EnvelopeOpenResult.Corrupt, result(hugeChunkSize + ByteArray(8)))
        assertEquals(EnvelopeOpenResult.Corrupt, result(headerBytes(version, chunk, keyPacketLength = Int.MAX_VALUE)))

        val header = layout(seal(ByteArray(0))).header
        val hugeChunk = ByteArrayOutputStream().also { buffer ->
            DataOutputStream(buffer).run { write(header); writeByte(1); writeInt(Int.MAX_VALUE) }
        }.toByteArray()
        assertEquals(EnvelopeOpenResult.Corrupt, result(hugeChunk))
        val hugeSignature = ByteArrayOutputStream().also { buffer ->
            DataOutputStream(buffer).run { write(header); writeByte(2); writeInt(0); writeInt(Int.MAX_VALUE) }
        }.toByteArray()
        assertEquals(EnvelopeOpenResult.Corrupt, result(hugeSignature))
    }

    @Test
    fun `closing the keys wipes the private key bytes`() {
        val seal = sealKeys()
        val open = openKeys(listOf(ALICE, BOB))
        seal.close()
        open.close()
        assertTrue(seal.signingKey.all { it == 0.toByte() })
        assertTrue(open.decryptionKeys.all { key -> key.all { it == 0.toByte() } })
    }
}

private fun publicKey(name: String): String = "pub:$name"

private fun privateKey(name: String): ByteArray = name.toByteArray(Charsets.UTF_8)

private fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)

/** A source that hands out at most [step] bytes per read, as a network stream may. */
private class TricklingInputStream(bytes: ByteArray, private val step: Int) :
    FilterInputStream(ByteArrayInputStream(bytes)) {
    override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, minOf(len, step))
}

/**
 * A reversible stand-in for OpenPGP. A key is a name: [publicKey] is its public half and [privateKey] its
 * private half. A key packet and a signature carry their key's name in a length-prefixed field; a chunk is
 * a 4-byte check of the session key followed by the plaintext XOR the session key; a signature's tag is
 * SHA-256 over the signer's name and the data. A name in [garbageUnwrapKeys] opens a packet meant for
 * another key to a wrong session key instead of refusing it.
 */
private class FakeEnvelopeCrypto(private val garbageUnwrapKeys: Set<String> = emptySet()) : EnvelopeCrypto {
    private val random = Random(7)
    var sessionKeysGenerated = 0
        private set
    val unwrapAttempts = ArrayList<String>()

    override fun generateSessionKey(): ByteArray {
        sessionKeysGenerated++
        return ByteArray(16).also { random.nextBytes(it) }
    }

    override fun wrapSessionKey(sessionKey: ByteArray, recipientPublicKey: String): ByteArray =
        framed(recipientPublicKey.removePrefix("pub:").toByteArray(Charsets.UTF_8), sessionKey)

    override fun unwrapSessionKey(wrappedKey: ByteArray, decryptionKey: ByteArray): ByteArray? {
        val name = String(decryptionKey, Charsets.UTF_8)
        unwrapAttempts += name
        val (recipient, sessionKey) = unframed(wrappedKey) ?: return null
        return when {
            recipient.contentEquals(decryptionKey) -> sessionKey
            name in garbageUnwrapKeys -> ByteArray(sessionKey.size) { 0x5A }
            else -> null
        }
    }

    override fun encryptChunk(plain: ByteArray, sessionKey: ByteArray): ByteArray =
        keyCheck(sessionKey) + xor(plain, sessionKey)

    override fun decryptChunk(cipher: ByteArray, sessionKey: ByteArray): ByteArray? {
        if (cipher.size < CHECK_BYTES || !cipher.copyOf(CHECK_BYTES).contentEquals(keyCheck(sessionKey))) return null
        return xor(cipher.copyOfRange(CHECK_BYTES, cipher.size), sessionKey)
    }

    override fun sign(data: ByteArray, signingKey: ByteArray): ByteArray = framed(signingKey, mac(signingKey, data))

    override fun verify(data: ByteArray, signature: ByteArray, verificationKeys: List<String>): Boolean {
        val (signer, tag) = unframed(signature) ?: return false
        return publicKey(String(signer, Charsets.UTF_8)) in verificationKeys && tag.contentEquals(mac(signer, data))
    }

    private fun keyCheck(sessionKey: ByteArray): ByteArray = sha256(sessionKey).copyOf(CHECK_BYTES)

    private fun mac(signer: ByteArray, data: ByteArray): ByteArray = sha256(signer + data)

    private fun xor(data: ByteArray, key: ByteArray): ByteArray =
        ByteArray(data.size) { i -> (data[i].toInt() xor key[i % key.size].toInt()).toByte() }

    private fun framed(field: ByteArray, rest: ByteArray): ByteArray = byteArrayOf(field.size.toByte()) + field + rest

    private fun unframed(bytes: ByteArray): Pair<ByteArray, ByteArray>? {
        if (bytes.isEmpty()) return null
        val length = bytes[0].toInt() and 0xFF
        if (1 + length > bytes.size) return null
        return bytes.copyOfRange(1, 1 + length) to bytes.copyOfRange(1 + length, bytes.size)
    }

    private companion object {
        const val CHECK_BYTES = 4
    }
}
