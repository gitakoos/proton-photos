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

import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** What a file on disk is: encrypted, plaintext, or not readable now ([UNKNOWN]). */
enum class LocalFileForm { ENCRYPTED, PLAIN, UNKNOWN }

/** A file whose first bytes could not be read, so whether it is encrypted is not known. Try again later. */
class LocalFileUnreadableException(file: File) : IOException("cannot read the start of ${file.name}")

/** What an encrypted file holds, written into its header so the kind can never be mistaken for another. */
enum class LocalFileKind(val code: Int) {
    VAULT(1),
    STORE(2),
    CACHE(3),
    ;

    companion object {
        fun of(code: Int): LocalFileKind? = entries.firstOrNull { it.code == code }
    }
}

/**
 * Facts an encrypted file carries about itself, encrypted with it, so an index of the files can be rebuilt from
 * the files alone. Every field is optional; a tag this build does not know is skipped on reading.
 */
data class LocalFileMeta(
    val originalName: String? = null,
    val mimeType: String? = null,
    val sourceFolder: String? = null,
    val cloudLinkId: String? = null,
    val captureTimeMs: Long? = null,
    val storeName: String? = null,
) {
    fun encode(): ByteArray {
        val out = ByteArrayOutputStream()
        fun put(tag: Int, value: String?) {
            if (value == null) return
            val bytes = value.toByteArray(Charsets.UTF_8)
            if (bytes.size > MAX_FIELD_BYTES) return
            out.write(tag)
            out.write(bytes.size ushr 8)
            out.write(bytes.size and 0xFF)
            out.write(bytes)
        }
        put(TAG_NAME, originalName)
        put(TAG_MIME, mimeType)
        put(TAG_FOLDER, sourceFolder)
        put(TAG_LINK, cloudLinkId)
        put(TAG_CAPTURE, captureTimeMs?.toString())
        put(TAG_STORE, storeName)
        return out.toByteArray()
    }

    companion object {
        private const val TAG_NAME = 1
        private const val TAG_MIME = 2
        private const val TAG_FOLDER = 3
        private const val TAG_LINK = 4
        private const val TAG_CAPTURE = 5
        private const val TAG_STORE = 6
        private const val MAX_FIELD_BYTES = 0xFFFF

        fun decode(bytes: ByteArray): LocalFileMeta {
            val fields = HashMap<Int, String>()
            var i = 0
            while (i + 3 <= bytes.size) {
                val tag = bytes[i].toInt() and 0xFF
                val length = ((bytes[i + 1].toInt() and 0xFF) shl 8) or (bytes[i + 2].toInt() and 0xFF)
                i += 3
                if (i + length > bytes.size) break
                fields[tag] = String(bytes, i, length, Charsets.UTF_8)
                i += length
            }
            return LocalFileMeta(
                originalName = fields[TAG_NAME],
                mimeType = fields[TAG_MIME],
                sourceFolder = fields[TAG_FOLDER],
                cloudLinkId = fields[TAG_LINK],
                captureTimeMs = fields[TAG_CAPTURE]?.toLongOrNull(),
                storeName = fields[TAG_STORE],
            )
        }
    }
}

/** The master keys files are wrapped with, looked up by the key id each file names. */
fun interface MasterKeys {
    /** The 32-byte key for [keyId]. Throws [LocalKeyLockedException] or [LocalKeyUnavailableException]. */
    fun keyFor(keyId: String): ByteArray
}

/** The key a file names cannot be opened on this phone now: lost with the Keystore, or never known here. */
class LocalKeyLockedException(val keyId: String) : IOException("local key locked")

/** The Keystore did not answer. Nothing is known to be wrong with the key or the file. */
class LocalKeyUnavailableException(cause: Throwable?) : IOException("local key unavailable", cause)

/** The key opened but the file does not decrypt: cut short, altered, or not one of these files. */
class LocalFileDamagedException(message: String, cause: Throwable? = null) : IOException(message, cause)

/** What writing or reading an encrypted file measured about its plaintext, to verify one copy against another. */
class PlainDigest(val bytes: Long, val sha256: ByteArray) {
    fun sameAs(other: PlainDigest): Boolean = bytes == other.bytes && sha256.contentEquals(other.sha256)

    override fun equals(other: Any?): Boolean = other is PlainDigest && sameAs(other)

    override fun hashCode(): Int = bytes.hashCode() * 31 + sha256.contentHashCode()

    companion object {
        /** The digest of everything [input] yields, read to its end. The stream is not closed. */
        fun of(input: InputStream): PlainDigest {
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(64 * 1024)
            var total = 0L
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
                total += n
            }
            return PlainDigest(total, digest.digest())
        }
    }
}

/**
 * The on-disk format of every file this app encrypts locally ("PFLE" version 1).
 *
 * ```
 * "PFLE" | version | kind | segment size log2 | 0 | key id (16) | nonce prefix (7)
 *        | wrap iv (12) | content key sealed by the master key (32 + 16), aad = the 31 bytes before the iv
 *        | meta length (u32) | meta sealed by the content key, aad = the header up to here
 * segment i = AES-GCM(content key, nonce = prefix | i (u32) | last (u8), aad = SHA-256 of the whole header)
 * ```
 * Every segment but the last holds exactly the segment size of plaintext; an empty file is one empty last
 * segment. Each segment authenticates the whole header and whether it is the last, so a file cut at any point,
 * with segments reordered, a header swapped or a bit flipped, fails to decrypt instead of reading wrong. The
 * format is not bound to the file's name or folder, so a rename keeps it readable. The plaintext size follows
 * from the file size, so nothing is decrypted to answer it.
 */
object LocalCryptoFormat {
    private val MAGIC = byteArrayOf('P'.code.toByte(), 'F'.code.toByte(), 'L'.code.toByte(), 'E'.code.toByte())
    const val VERSION = 1
    const val SEGMENT_LOG2 = 16
    const val TAG_BYTES = 16
    const val KEY_BYTES = 32
    const val KEY_ID_BYTES = 16
    private const val PREFIX_BYTES = 7
    private const val IV_BYTES = 12
    private const val FIXED_HEADER = 4 + 4 + KEY_ID_BYTES + PREFIX_BYTES
    private const val WRAPPED_KEY_BYTES = IV_BYTES + KEY_BYTES + TAG_BYTES
    private const val META_LENGTH_OFFSET = FIXED_HEADER + WRAPPED_KEY_BYTES
    internal const val META_OFFSET = META_LENGTH_OFFSET + 4
    private const val MAX_META_BYTES = 64 * 1024
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val META_INDEX = -1

    /** The size an encrypted copy of [plainBytes] takes beyond its plaintext, without its meta. */
    fun overheadFor(plainBytes: Long): Long {
        val segments = maxOf(1L, (plainBytes + (1L shl SEGMENT_LOG2) - 1) shr SEGMENT_LOG2)
        return META_OFFSET + TAG_BYTES + segments * TAG_BYTES
    }

    /** Whether [head] (the first bytes of a file) starts like one of these files. */
    fun looksEncrypted(head: ByteArray, length: Int = head.size): Boolean =
        length >= 5 && (0 until 4).all { head[it] == MAGIC[it] } && head[4].toInt() == VERSION

    /** Whether [file] starts like one of these files. A file that cannot be read answers false. */
    fun isEncrypted(file: File): Boolean = formOf(file) == LocalFileForm.ENCRYPTED

    /**
     * What [file] is, read from its first bytes. A file that exists but cannot be read now is [LocalFileForm.UNKNOWN],
     * never plaintext: such a file is not encrypted again, copied out or deleted, and is looked at again later.
     */
    fun formOf(file: File, open: (File) -> RandomAccessFile = { RandomAccessFile(it, "r") }): LocalFileForm {
        if (!file.isFile || file.length() < META_OFFSET) return LocalFileForm.PLAIN
        return runCatching {
            open(file).use { raf ->
                val head = ByteArray(5)
                raf.readFully(head)
                if (looksEncrypted(head)) LocalFileForm.ENCRYPTED else LocalFileForm.PLAIN
            }
        }.getOrDefault(LocalFileForm.UNKNOWN)
    }

    /**
     * [file] opened once, with its form read from that same open file: a file encrypted in place by a rename over it
     * right after can never be read as the plaintext it was, or the other way round. Null when it cannot be opened
     * or its start cannot be read. The caller closes it.
     */
    fun openOnce(file: File): OpenedFile? {
        val channel = runCatching { java.nio.channels.FileChannel.open(file.toPath(), java.nio.file.StandardOpenOption.READ) }
            .getOrNull() ?: return null
        return try {
            val size = channel.size()
            val form = if (size < META_OFFSET) {
                LocalFileForm.PLAIN
            } else {
                val head = ByteBuffer.allocate(5)
                while (head.hasRemaining()) if (channel.read(head, head.position().toLong()) < 0) throw EOFException()
                if (looksEncrypted(head.array())) LocalFileForm.ENCRYPTED else LocalFileForm.PLAIN
            }
            OpenedFile(form, channel)
        } catch (e: Exception) {
            runCatching { channel.close() }
            null
        }
    }

    /**
     * Throws [EncryptedBytesException] when [file] starts like one of these files. The last check before bytes
     * leave the app (into the gallery, an upload, a content hash): whatever went wrong before, they never go out
     * encrypted, and the caller keeps its source.
     */
    fun requirePlain(file: File) {
        val head = ByteArray(5)
        val read = java.io.FileInputStream(file).use { readUpTo(it, head) }
        if (looksEncrypted(head, read)) throw EncryptedBytesException()
    }

    /** [input] as it is, checked as [requirePlain] checks a file before its first byte is handed on. */
    fun guardPlain(input: InputStream): InputStream {
        val pushback = java.io.PushbackInputStream(input, 5)
        val head = ByteArray(5)
        val read = readUpTo(pushback, head)
        if (looksEncrypted(head, read)) {
            runCatching { input.close() }
            throw EncryptedBytesException()
        }
        if (read > 0) pushback.unread(head, 0, read)
        return pushback
    }

    private fun readUpTo(input: InputStream, into: ByteArray): Int {
        var total = 0
        while (total < into.size) {
            val n = input.read(into, total, into.size - total)
            if (n < 0) break
            total += n
        }
        return total
    }

    fun keyIdHex(id: ByteArray): String = id.joinToString("") { "%02x".format(it) }

    fun keyIdBytes(hex: String): ByteArray =
        ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    /** What encrypting a file needs: the master key and its id, what the file is, and what it says about itself. */
    class Sealing(
        val keyId: String,
        val masterKey: ByteArray,
        val kind: LocalFileKind,
        val meta: LocalFileMeta = LocalFileMeta(),
        val random: SecureRandom = SecureRandom(),
        val segmentLog2: Int = SEGMENT_LOG2,
    )

    /** An output stream that encrypts everything written to it into [out], which [EncryptingOutputStream.close]
     *  also closes. */
    fun encryptingStream(out: OutputStream, sealing: Sealing): EncryptingOutputStream = EncryptingOutputStream(out, sealing)

    /** Encrypts [input] into [out] whole and reports what the plaintext was. Neither stream is closed. */
    fun encrypt(input: InputStream, out: OutputStream, sealing: Sealing): PlainDigest {
        val stream = EncryptingOutputStream(NonClosing(out), sealing)
        input.copyTo(stream, 64 * 1024)
        stream.close()
        return stream.digest()
    }

    fun encryptBytes(plain: ByteArray, sealing: Sealing): ByteArray {
        val out = ByteArrayOutputStream(plain.size + 256)
        encrypt(plain.inputStream(), out, sealing)
        return out.toByteArray()
    }

    fun decryptBytes(bytes: ByteArray, keys: MasterKeys): ByteArray =
        LocalFileReader(ByteArraySource(bytes), keys).use { reader ->
            if (reader.plainSize > Int.MAX_VALUE - 64) throw IOException("too large for memory")
            val out = ByteArray(reader.plainSize.toInt())
            reader.readFully(0, out)
            out
        }

    /** The key id an encrypted file's header names, without opening it; null when it is not one. */
    fun keyIdOf(bytes: ByteArray): String? =
        runCatching { readHeader(ByteArraySource(bytes)).keyId }.getOrNull()

    fun keyIdOf(file: File): String? =
        runCatching { FileSource(file).use { readHeader(it).keyId } }.getOrNull()

    /** Decrypts the whole of [file] into [out] (not closed; null = verify only) and reports what it held. Throws
     *  on any damage. */
    fun decryptFile(file: File, keys: MasterKeys, out: OutputStream?): PlainDigest =
        openFile(file, keys).use { reader -> reader.copyTo(out) }

    fun openFile(file: File, keys: MasterKeys): LocalFileReader {
        val source = FileSource(file)
        return try {
            LocalFileReader(source, keys)
        } catch (e: Throwable) {
            source.close()
            throw e
        }
    }

    /** The plaintext size of an encrypted [file], read from its header alone, or null when it is not one. */
    fun plainSize(file: File): Long? = runCatching {
        FileSource(file).use { source ->
            val header = readHeader(source)
            segmentLayout(source.size, header.length, header.segmentSize).plainSize
        }
    }.getOrNull()

    // ---- internals shared by the writer and the reader ----

    internal class Header(
        val bytes: ByteArray,
        val kind: LocalFileKind,
        val keyId: String,
        val prefix: ByteArray,
        val segmentSize: Int,
        val wrapIv: ByteArray,
        val wrappedKey: ByteArray,
        val metaCipher: ByteArray,
    ) {
        val length: Int get() = bytes.size
        val aad: ByteArray by lazy { MessageDigest.getInstance("SHA-256").digest(bytes) }
    }

    internal class Layout(val segments: Long, val lastCipher: Int, val plainSize: Long)

    internal fun segmentLayout(fileSize: Long, headerLength: Int, segmentSize: Int): Layout {
        val body = fileSize - headerLength
        if (body < TAG_BYTES) throw LocalFileDamagedException("short")
        val full = segmentSize.toLong() + TAG_BYTES
        val segments = (body + full - 1) / full
        val lastCipher = (body - (segments - 1) * full).toInt()
        if (lastCipher < TAG_BYTES) throw LocalFileDamagedException("tail")
        if (segments > Int.MAX_VALUE) throw LocalFileDamagedException("size")
        return Layout(segments, lastCipher, body - segments * TAG_BYTES)
    }

    internal fun nonce(prefix: ByteArray, index: Int, last: Boolean): ByteArray {
        val nonce = ByteArray(IV_BYTES)
        System.arraycopy(prefix, 0, nonce, 0, PREFIX_BYTES)
        ByteBuffer.wrap(nonce, PREFIX_BYTES, 4).putInt(index)
        nonce[IV_BYTES - 1] = when {
            index == META_INDEX -> 2
            last -> 1
            else -> 0
        }
        return nonce
    }

    internal fun buildHeader(sealing: Sealing, contentKey: ByteArray): Header {
        val keyId = keyIdBytes(sealing.keyId)
        require(keyId.size == KEY_ID_BYTES) { "key id" }
        require(sealing.masterKey.size == KEY_BYTES) { "master key" }
        val prefix = ByteArray(PREFIX_BYTES).also { sealing.random.nextBytes(it) }
        val fixed = ByteBuffer.allocate(FIXED_HEADER)
            .put(MAGIC).put(VERSION.toByte()).put(sealing.kind.code.toByte()).put(sealing.segmentLog2.toByte())
            .put(0).put(keyId).put(prefix).array()
        val wrapIv = ByteArray(IV_BYTES).also { sealing.random.nextBytes(it) }
        val wrapCipher = Cipher.getInstance(TRANSFORMATION)
        wrapCipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(sealing.masterKey, "AES"),
            GCMParameterSpec(TAG_BYTES * 8, wrapIv),
        )
        wrapCipher.updateAAD(fixed)
        val wrapped = wrapCipher.doFinal(contentKey)
        val metaPlain = sealing.meta.encode()
        val beforeMeta = ByteBuffer.allocate(META_OFFSET)
            .put(fixed).put(wrapIv).put(wrapped).putInt(metaPlain.size + TAG_BYTES).array()
        val metaCipher = Cipher.getInstance(TRANSFORMATION)
        metaCipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(contentKey, "AES"),
            GCMParameterSpec(TAG_BYTES * 8, nonce(prefix, META_INDEX, false)),
        )
        metaCipher.updateAAD(beforeMeta)
        val sealedMeta = metaCipher.doFinal(metaPlain)
        val bytes = beforeMeta + sealedMeta
        return Header(bytes, sealing.kind, sealing.keyId, prefix, 1 shl sealing.segmentLog2, wrapIv, wrapped, sealedMeta)
    }

    internal fun readHeader(source: RandomSource): Header {
        val start = ByteArray(META_OFFSET)
        if (source.size < META_OFFSET) throw LocalFileDamagedException("header")
        source.readFully(0, start, 0, start.size)
        if (!looksEncrypted(start)) throw LocalFileDamagedException("not encrypted")
        val kind = LocalFileKind.of(start[5].toInt() and 0xFF) ?: throw LocalFileDamagedException("kind")
        val log2 = start[6].toInt() and 0xFF
        if (log2 < 10 || log2 > 22) throw LocalFileDamagedException("segment")
        val keyId = start.copyOfRange(8, 8 + KEY_ID_BYTES)
        val prefix = start.copyOfRange(8 + KEY_ID_BYTES, FIXED_HEADER)
        val wrapIv = start.copyOfRange(FIXED_HEADER, FIXED_HEADER + IV_BYTES)
        val wrapped = start.copyOfRange(FIXED_HEADER + IV_BYTES, META_LENGTH_OFFSET)
        val metaLength = ByteBuffer.wrap(start, META_LENGTH_OFFSET, 4).int
        if (metaLength < TAG_BYTES || metaLength > MAX_META_BYTES) throw LocalFileDamagedException("meta")
        if (source.size < META_OFFSET.toLong() + metaLength) throw LocalFileDamagedException("meta")
        val metaCipher = ByteArray(metaLength)
        source.readFully(META_OFFSET.toLong(), metaCipher, 0, metaLength)
        return Header(start + metaCipher, kind, keyIdHex(keyId), prefix, 1 shl log2, wrapIv, wrapped, metaCipher)
    }

    internal fun unwrapContentKey(header: Header, masterKey: ByteArray): ByteArray {
        try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(masterKey, "AES"),
                GCMParameterSpec(TAG_BYTES * 8, header.wrapIv),
            )
            cipher.updateAAD(header.bytes, 0, FIXED_HEADER)
            return cipher.doFinal(header.wrappedKey)
        } catch (e: GeneralSecurityException) {
            throw LocalFileDamagedException("content key", e)
        }
    }

    internal fun openMeta(header: Header, contentKey: ByteArray): LocalFileMeta {
        try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(contentKey, "AES"),
                GCMParameterSpec(TAG_BYTES * 8, nonce(header.prefix, META_INDEX, false)),
            )
            cipher.updateAAD(header.bytes, 0, META_OFFSET)
            return LocalFileMeta.decode(cipher.doFinal(header.metaCipher))
        } catch (e: GeneralSecurityException) {
            throw LocalFileDamagedException("meta", e)
        }
    }
}

/**
 * Encrypts what is written to it, one segment at a time. A full segment is held back until more data arrives,
 * so [close] knows which segment is the last. Memory is two segments.
 */
class EncryptingOutputStream internal constructor(
    private val out: OutputStream,
    sealing: LocalCryptoFormat.Sealing,
) : OutputStream() {
    private val contentKey = ByteArray(LocalCryptoFormat.KEY_BYTES).also { sealing.random.nextBytes(it) }
    private val header = LocalCryptoFormat.buildHeader(sealing, contentKey)
    private val keySpec = SecretKeySpec(contentKey, "AES")
    private val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    private val buffer = ByteArray(header.segmentSize)
    private val sealed = ByteArray(header.segmentSize + LocalCryptoFormat.TAG_BYTES)
    private val digest = MessageDigest.getInstance("SHA-256")
    private var filled = 0
    private var index = 0
    private var total = 0L
    private var closed = false
    private var result: PlainDigest? = null

    init {
        out.write(header.bytes)
    }

    override fun write(b: Int) {
        write(byteArrayOf(b.toByte()), 0, 1)
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        check(!closed) { "closed" }
        var offset = off
        var left = len
        while (left > 0) {
            if (filled == buffer.size) flushSegment(last = false)
            val n = minOf(left, buffer.size - filled)
            System.arraycopy(b, offset, buffer, filled, n)
            digest.update(b, offset, n)
            filled += n
            total += n
            offset += n
            left -= n
        }
    }

    private fun flushSegment(last: Boolean) {
        cipher.init(
            Cipher.ENCRYPT_MODE,
            keySpec,
            GCMParameterSpec(LocalCryptoFormat.TAG_BYTES * 8, LocalCryptoFormat.nonce(header.prefix, index, last)),
        )
        cipher.updateAAD(header.aad)
        val n = cipher.doFinal(buffer, 0, filled, sealed, 0)
        out.write(sealed, 0, n)
        index++
        filled = 0
    }

    override fun flush() = out.flush()

    override fun close() {
        if (closed) return
        flushSegment(last = true)
        closed = true
        result = PlainDigest(total, digest.digest())
        contentKey.fill(0)
        buffer.fill(0)
        out.close()
    }

    /** What was written, available once the stream is closed. */
    fun digest(): PlainDigest = result ?: error("not closed")
}

/** Random reads from whatever holds an encrypted file. */
interface RandomSource : Closeable {
    val size: Long

    fun readFully(position: Long, into: ByteArray, offset: Int, length: Int)
}

/** Bytes about to leave the app start like a local encrypted file; nothing was written or sent. */
class EncryptedBytesException : IOException("local encrypted bytes refused")

/** A file opened once by [LocalCryptoFormat.openOnce]: its [form], and its bytes read from the same open file. */
class OpenedFile internal constructor(val form: LocalFileForm, private val channel: java.nio.channels.FileChannel) : Closeable {

    /** The size of the file as opened. */
    val size: Long get() = channel.size()

    /** The bytes as they are, from the start (for a plaintext file). Closing the stream closes the file. */
    fun rawStream(): InputStream = java.nio.channels.Channels.newInputStream(channel.position(0))

    /** A reader of the plaintext of an encrypted file; closing it closes the file. */
    fun reader(keys: MasterKeys): LocalFileReader = LocalFileReader(ChannelSource(channel), keys)

    override fun close() = channel.close()
}

internal class ChannelSource(private val channel: java.nio.channels.FileChannel) : RandomSource {
    override val size: Long = channel.size()

    override fun readFully(position: Long, into: ByteArray, offset: Int, length: Int) {
        val buffer = ByteBuffer.wrap(into, offset, length)
        while (buffer.hasRemaining()) {
            if (channel.read(buffer, position + (buffer.position() - offset)) < 0) throw EOFException()
        }
    }

    override fun close() = channel.close()
}

internal class FileSource(file: File) : RandomSource {
    private val raf = RandomAccessFile(file, "r")
    override val size: Long = raf.length()

    override fun readFully(position: Long, into: ByteArray, offset: Int, length: Int) {
        raf.seek(position)
        raf.readFully(into, offset, length)
    }

    override fun close() = raf.close()
}

internal class ByteArraySource(private val bytes: ByteArray) : RandomSource {
    override val size: Long = bytes.size.toLong()

    override fun readFully(position: Long, into: ByteArray, offset: Int, length: Int) {
        if (position < 0 || position + length > bytes.size) throw EOFException()
        System.arraycopy(bytes, position.toInt(), into, offset, length)
    }

    override fun close() = Unit
}

/**
 * Reads the plaintext of an encrypted file at any offset, decrypting one segment at a time and keeping the last
 * one. Not thread-safe; one reader per consumer.
 */
class LocalFileReader(private val source: RandomSource, keys: MasterKeys) : Closeable {
    private val header = LocalCryptoFormat.readHeader(source)
    private val layout = LocalCryptoFormat.segmentLayout(source.size, header.length, header.segmentSize)
    private val contentKey = LocalCryptoFormat.unwrapContentKey(header, keys.keyFor(header.keyId))
    private val keySpec = SecretKeySpec(contentKey, "AES")
    private val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    private val cipherBuffer = ByteArray(header.segmentSize + LocalCryptoFormat.TAG_BYTES)
    private val plainBuffer = ByteArray(header.segmentSize)
    private var cachedIndex = -1L
    private var cachedLength = 0

    val plainSize: Long get() = layout.plainSize
    val keyId: String get() = header.keyId
    val kind: LocalFileKind get() = header.kind
    val meta: LocalFileMeta by lazy { LocalCryptoFormat.openMeta(header, contentKey) }

    /** Reads up to [length] bytes at [position] into [into]; -1 at the end. */
    fun read(position: Long, into: ByteArray, offset: Int, length: Int): Int {
        if (position >= layout.plainSize) return -1
        if (length == 0) return 0
        val segmentSize = header.segmentSize
        val index = position / segmentSize
        loadSegment(index)
        val within = (position - index * segmentSize).toInt()
        val n = minOf(length, cachedLength - within)
        System.arraycopy(plainBuffer, within, into, offset, n)
        return n
    }

    fun readFully(position: Long, into: ByteArray) {
        var done = 0
        while (done < into.size) {
            val n = read(position + done, into, done, into.size - done)
            if (n < 0) throw EOFException()
            done += n
        }
    }

    /** Copies the whole plaintext to [out] (null = only verify) and reports what it was. */
    fun copyTo(out: OutputStream?): PlainDigest {
        val digest = MessageDigest.getInstance("SHA-256")
        for (index in 0 until layout.segments) {
            loadSegment(index)
            digest.update(plainBuffer, 0, cachedLength)
            out?.write(plainBuffer, 0, cachedLength)
        }
        return PlainDigest(layout.plainSize, digest.digest())
    }

    /** A stream over the plaintext from the start; closing it closes this reader. */
    fun inputStream(): InputStream = ReaderInputStream(this)

    private fun loadSegment(index: Long) {
        if (index == cachedIndex) return
        val last = index == layout.segments - 1
        val full = header.segmentSize + LocalCryptoFormat.TAG_BYTES
        val length = if (last) layout.lastCipher else full
        source.readFully(header.length + index * full, cipherBuffer, 0, length)
        try {
            cipher.init(
                Cipher.DECRYPT_MODE,
                keySpec,
                GCMParameterSpec(
                    LocalCryptoFormat.TAG_BYTES * 8,
                    LocalCryptoFormat.nonce(header.prefix, index.toInt(), last),
                ),
            )
            cipher.updateAAD(header.aad)
            cachedLength = cipher.doFinal(cipherBuffer, 0, length, plainBuffer, 0)
            cachedIndex = index
        } catch (e: GeneralSecurityException) {
            cachedIndex = -1
            throw LocalFileDamagedException("segment $index", e)
        }
    }

    override fun close() {
        contentKey.fill(0)
        plainBuffer.fill(0)
        source.close()
    }
}

private class ReaderInputStream(private val reader: LocalFileReader) : InputStream() {
    private var position = 0L
    private var mark = 0L
    private val one = ByteArray(1)

    override fun read(): Int = if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xFF

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        val n = reader.read(position, b, off, len)
        if (n > 0) position += n
        return n
    }

    override fun skip(n: Long): Long {
        val skipped = minOf(n, reader.plainSize - position).coerceAtLeast(0)
        position += skipped
        return skipped
    }

    override fun available(): Int = minOf(Int.MAX_VALUE.toLong(), reader.plainSize - position).toInt().coerceAtLeast(0)

    override fun markSupported(): Boolean = true

    override fun mark(readlimit: Int) {
        mark = position
    }

    override fun reset() {
        position = mark
    }

    override fun close() = reader.close()
}

private class NonClosing(private val out: OutputStream) : OutputStream() {
    override fun write(b: Int) = out.write(b)
    override fun write(b: ByteArray, off: Int, len: Int) = out.write(b, off, len)
    override fun flush() = out.flush()
    override fun close() = out.flush()
}
