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

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest

/**
 * The cryptographic primitives [MlSnapshotEnvelope] is built from, kept behind an interface so the envelope
 * logic runs against a fake in tests. Private keys are unlocked key bytes and public keys are armored. On
 * the open path a failure is null or false, and a thrown exception counts as the same failure.
 */
interface EnvelopeCrypto {
    fun generateSessionKey(): ByteArray

    /** [sessionKey] encrypted to [recipientPublicKey], as a key packet. */
    fun wrapSessionKey(sessionKey: ByteArray, recipientPublicKey: String): ByteArray

    /** The session key inside [wrappedKey] opened with [decryptionKey], or null when that key does not open it. */
    fun unwrapSessionKey(wrappedKey: ByteArray, decryptionKey: ByteArray): ByteArray?

    fun encryptChunk(plain: ByteArray, sessionKey: ByteArray): ByteArray

    /** The plaintext of [cipher], or null when it does not decrypt under [sessionKey]. */
    fun decryptChunk(cipher: ByteArray, sessionKey: ByteArray): ByteArray?

    /** A detached signature over [data] made with [signingKey]. */
    fun sign(data: ByteArray, signingKey: ByteArray): ByteArray

    /** Whether [signature] is a valid signature over [data] by any of [verificationKeys]. */
    fun verify(data: ByteArray, signature: ByteArray, verificationKeys: List<String>): Boolean
}

/** What [MlSnapshotEnvelope.seal] needs: the public key the session key is wrapped to and the unlocked key
 *  that signs the trailer. [close] wipes the private key bytes. */
class EnvelopeSealKeys(
    val recipientPublicKey: String,
    val signingKey: ByteArray,
) : AutoCloseable {
    override fun close() = signingKey.fill(0)
}

/** What [MlSnapshotEnvelope.open] needs: every unlocked key that may open the session key, tried in order,
 *  and the public keys the trailer signature may verify against. [close] wipes the private key bytes. */
class EnvelopeOpenKeys(
    val decryptionKeys: List<ByteArray>,
    val verificationKeys: List<String>,
) : AutoCloseable {
    override fun close() = decryptionKeys.forEach { it.fill(0) }
}

/** The outcome of [MlSnapshotEnvelope.open]. Only [Opened] means the output holds verified plaintext. */
sealed interface EnvelopeOpenResult {
    /** Every chunk and the signed trailer verified, and [plaintextBytes] bytes were written. */
    data class Opened(val plaintextBytes: Long) : EnvelopeOpenResult

    /** The stream does not start with [MlSnapshotEnvelope.MAGIC]. */
    data object NotAnEnvelope : EnvelopeOpenResult

    /** An envelope version this build cannot read. */
    data class UnsupportedVersion(val version: Int) : EnvelopeOpenResult

    /** No offered key opens the session key: another account's envelope, or a key this device lacks. */
    data object WrongKey : EnvelopeOpenResult

    /** Cut short, a chunk dropped, reordered or altered, trailing data, or a trailer signature that does
     *  not verify against the offered keys. */
    data object Corrupt : EnvelopeOpenResult
}

/**
 * The encrypted, signed container an ML snapshot is stored in, on top of Drive's own encryption. The
 * plaintext stream is cut into chunks of [DEFAULT_CHUNK_SIZE] bytes, all encrypted with one session key
 * minted fresh for the envelope, so memory stays bounded by one chunk however large the snapshot grows.
 *
 * Layout, with the two record tags a single byte and every other integer a big-endian int32:
 * ```
 * header   "PPMLSEAL" | version | chunk size | key packet length | key packet
 * chunk    0x01 | ciphertext length | ciphertext                    one record per plaintext chunk
 * trailer  0x02 | chunk count | SHA-256 of each chunk's ciphertext, in order (32 bytes each)
 *               | signature length | signature
 * ```
 * The header is in the clear so a reader can tell the file type; the key packet is the session key wrapped
 * to the account's address key. Every chunk but the last carries exactly the chunk size of plaintext, and
 * an empty stream has no chunks. The trailer signature, made with the same address key, covers a fixed
 * context string, the header, the chunk count and the ordered hashes, and nothing may follow it, so a file
 * that is cut short, has a chunk dropped, reordered, altered or appended, or was not signed by the account
 * fails [open].
 *
 * [open] streams: each chunk's plaintext is written out as soon as it decrypts, and the hashes and the
 * signature are checked once the trailer arrives. Whatever was written is untrusted unless the result is
 * [EnvelopeOpenResult.Opened], so a caller opens into a scratch file and discards it on any other result
 * or on an exception.
 */
object MlSnapshotEnvelope {
    /** The clear-text marker every envelope starts with. */
    const val MAGIC = "PPMLSEAL"
    const val VERSION = 1
    const val DEFAULT_CHUNK_SIZE = 4 * 1024 * 1024

    /** The largest chunk size a reader accepts, which bounds what one chunk can make it allocate. */
    const val MAX_CHUNK_SIZE = 16 * 1024 * 1024

    private const val TAG_CHUNK = 1
    private const val TAG_TRAILER = 2
    private const val HASH_BYTES = 32
    private const val MAX_KEY_PACKET_BYTES = 64 * 1024
    private const val MAX_SIGNATURE_BYTES = 64 * 1024
    private const val CIPHER_SLACK_BYTES = 64 * 1024
    private const val SIGNATURE_CONTEXT = "eu.akoos.photos.mlsync.envelope"

    private val magicBytes = MAGIC.toByteArray(Charsets.US_ASCII)

    /**
     * Encrypts [input], read to its end, into an envelope written to [output]; neither stream is closed.
     * Returns the number of plaintext bytes sealed. Blocking. On an exception the partial output is not a
     * valid envelope and must be discarded.
     */
    fun seal(
        input: InputStream,
        output: OutputStream,
        crypto: EnvelopeCrypto,
        keys: EnvelopeSealKeys,
        chunkSize: Int = DEFAULT_CHUNK_SIZE,
    ): Long {
        require(chunkSize in 1..MAX_CHUNK_SIZE) { "chunk size $chunkSize out of range" }
        val sessionKey = crypto.generateSessionKey()
        try {
            val wrappedKey = crypto.wrapSessionKey(sessionKey, keys.recipientPublicKey)
            check(wrappedKey.size in 1..MAX_KEY_PACKET_BYTES) { "key packet of ${wrappedKey.size} bytes" }
            val header = header(chunkSize, wrappedKey)
            val out = DataOutputStream(output)
            out.write(header)
            val digest = MessageDigest.getInstance("SHA-256")
            val hashes = ArrayList<ByteArray>()
            val buffer = ByteArray(chunkSize)
            var total = 0L
            while (true) {
                val read = readUpTo(input, buffer)
                if (read == 0) break
                val cipher = crypto.encryptChunk(if (read == chunkSize) buffer else buffer.copyOf(read), sessionKey)
                check(cipher.size in 1..maxCipherLength(chunkSize)) { "chunk ciphertext of ${cipher.size} bytes" }
                out.writeByte(TAG_CHUNK)
                out.writeInt(cipher.size)
                out.write(cipher)
                hashes += digest.digest(cipher)
                total += read
                if (read < chunkSize) break
            }
            val signature = crypto.sign(signedManifest(header, hashes), keys.signingKey)
            check(signature.size in 1..MAX_SIGNATURE_BYTES) { "signature of ${signature.size} bytes" }
            out.writeByte(TAG_TRAILER)
            out.writeInt(hashes.size)
            hashes.forEach { out.write(it) }
            out.writeInt(signature.size)
            out.write(signature)
            out.flush()
            return total
        } finally {
            sessionKey.fill(0)
        }
    }

    /**
     * Decrypts the envelope in [input] into [output], chunk by chunk; neither stream is closed. The key
     * packet is tried against every key in [keys] in order. Blocking. Only [EnvelopeOpenResult.Opened]
     * means [output] holds the complete, verified plaintext; an I/O failure of either stream is thrown.
     */
    fun open(
        input: InputStream,
        output: OutputStream,
        crypto: EnvelopeCrypto,
        keys: EnvelopeOpenKeys,
    ): EnvelopeOpenResult {
        val magic = ByteArray(magicBytes.size)
        if (readUpTo(input, magic) < magic.size || !magic.contentEquals(magicBytes)) {
            return EnvelopeOpenResult.NotAnEnvelope
        }
        val inp = DataInputStream(input)
        return try {
            val version = inp.readInt()
            if (version != VERSION) return EnvelopeOpenResult.UnsupportedVersion(version)
            val chunkSize = inp.readInt()
            val wrappedLength = inp.readInt()
            if (chunkSize !in 1..MAX_CHUNK_SIZE || wrappedLength !in 1..MAX_KEY_PACKET_BYTES) {
                return EnvelopeOpenResult.Corrupt
            }
            val wrappedKey = ByteArray(wrappedLength).also { inp.readFully(it) }
            openBody(inp, output, crypto, keys, chunkSize, wrappedKey)
        } catch (e: EOFException) {
            EnvelopeOpenResult.Corrupt
        }
    }

    private fun openBody(
        inp: DataInputStream,
        output: OutputStream,
        crypto: EnvelopeCrypto,
        keys: EnvelopeOpenKeys,
        chunkSize: Int,
        wrappedKey: ByteArray,
    ): EnvelopeOpenResult {
        val candidates = keys.decryptionKeys.iterator()
        var sessionKey = nextSessionKey(crypto, wrappedKey, candidates) ?: return EnvelopeOpenResult.WrongKey
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            val hashes = ArrayList<ByteArray>()
            var written = 0L
            var lastChunkShort = false
            var tag = inp.readUnsignedByte()
            while (tag == TAG_CHUNK) {
                val length = inp.readInt()
                if (lastChunkShort || length !in 1..maxCipherLength(chunkSize)) return EnvelopeOpenResult.Corrupt
                val cipher = ByteArray(length).also { inp.readFully(it) }
                var plain = decryptOrNull(crypto, cipher, sessionKey)
                // A key that does not own the packet can still unwrap it to a wrong session key, so until a
                // chunk has opened, a failure moves on to the next key instead of rejecting the file.
                while (plain == null && hashes.isEmpty()) {
                    val next = nextSessionKey(crypto, wrappedKey, candidates) ?: break
                    sessionKey.fill(0)
                    sessionKey = next
                    plain = decryptOrNull(crypto, cipher, sessionKey)
                }
                if (plain == null || plain.isEmpty() || plain.size > chunkSize) return EnvelopeOpenResult.Corrupt
                hashes += digest.digest(cipher)
                output.write(plain)
                written += plain.size
                lastChunkShort = plain.size < chunkSize
                tag = inp.readUnsignedByte()
            }
            if (tag != TAG_TRAILER || inp.readInt() != hashes.size) return EnvelopeOpenResult.Corrupt
            val listed = ByteArray(HASH_BYTES)
            for (hash in hashes) {
                inp.readFully(listed)
                if (!listed.contentEquals(hash)) return EnvelopeOpenResult.Corrupt
            }
            val signatureLength = inp.readInt()
            if (signatureLength !in 1..MAX_SIGNATURE_BYTES) return EnvelopeOpenResult.Corrupt
            val signature = ByteArray(signatureLength).also { inp.readFully(it) }
            if (inp.read() != -1) return EnvelopeOpenResult.Corrupt
            val manifest = signedManifest(header(chunkSize, wrappedKey), hashes)
            val verified = try {
                crypto.verify(manifest, signature, keys.verificationKeys)
            } catch (e: Exception) {
                false
            }
            if (!verified) return EnvelopeOpenResult.Corrupt
            output.flush()
            return EnvelopeOpenResult.Opened(written)
        } finally {
            sessionKey.fill(0)
        }
    }

    private fun nextSessionKey(crypto: EnvelopeCrypto, wrappedKey: ByteArray, keys: Iterator<ByteArray>): ByteArray? {
        while (keys.hasNext()) {
            val key = keys.next()
            val opened = try {
                crypto.unwrapSessionKey(wrappedKey, key)
            } catch (e: Exception) {
                null
            }
            if (opened != null && opened.isNotEmpty()) return opened
        }
        return null
    }

    private fun decryptOrNull(crypto: EnvelopeCrypto, cipher: ByteArray, sessionKey: ByteArray): ByteArray? =
        try {
            crypto.decryptChunk(cipher, sessionKey)
        } catch (e: Exception) {
            null
        }

    private fun header(chunkSize: Int, wrappedKey: ByteArray): ByteArray {
        val buffer = ByteArrayOutputStream()
        DataOutputStream(buffer).run {
            write(magicBytes)
            writeInt(VERSION)
            writeInt(chunkSize)
            writeInt(wrappedKey.size)
            write(wrappedKey)
        }
        return buffer.toByteArray()
    }

    private fun signedManifest(header: ByteArray, hashes: List<ByteArray>): ByteArray {
        val buffer = ByteArrayOutputStream()
        DataOutputStream(buffer).run {
            write(SIGNATURE_CONTEXT.toByteArray(Charsets.US_ASCII))
            write(header)
            writeInt(hashes.size)
            hashes.forEach { write(it) }
        }
        return buffer.toByteArray()
    }

    private fun maxCipherLength(chunkSize: Int): Int = chunkSize + chunkSize / 4 + CIPHER_SLACK_BYTES

    /** Fills [buffer] from [input] until it is full or the stream ends; returns the bytes read. */
    private fun readUpTo(input: InputStream, buffer: ByteArray): Int {
        var filled = 0
        while (filled < buffer.size) {
            val read = input.read(buffer, filled, buffer.size - filled)
            if (read < 0) break
            filled += read
        }
        return filled
    }
}
