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

package eu.akoos.photos.data.db.encryption

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Where the database key stands, read from the wrapped copies on disk and the Keystore key that seals them. */
sealed interface DatabaseKeyState {
    /** The key, unwrapped and checked. */
    class Ready(val key: ByteArray) : DatabaseKeyState

    /** No wrapped key on disk: none was ever made, or it was cleared with the data. */
    data object Absent : DatabaseKeyState

    /** A wrapped key exists but can never be opened again: the Keystore key is gone or no longer matches. */
    data object Lost : DatabaseKeyState

    /** The Keystore did not answer this time. Nothing is known to be wrong with the key. */
    class Unavailable(val cause: Throwable) : DatabaseKeyState
}

/** The Keystore key that seals the database key. Behind an interface so the logic runs in a unit test. */
interface KeyWrapper {
    /** The sealing key, or null when it does not exist and [create] is false. */
    fun wrappingKey(create: Boolean): SecretKey?
}

/** The sealing key held by the Android Keystore: bound to this app and device, never exported, no user prompt. */
class AndroidKeystoreWrapper(private val alias: String = KEY_ALIAS) : KeyWrapper {

    override fun wrappingKey(create: Boolean): SecretKey? {
        val store = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        if (!create) return null
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "pfp_database_key"
    }
}

/** A wrapped key file that is not in the expected format. Treated like a failed tag: that copy is unusable. */
class MalformedKeyFileException(message: String) : Exception(message)

/** AES-GCM sealing of the raw database key, apart from the Keystore so it runs in a unit test. */
internal object DatabaseKeyCodec {
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val TAG_BITS = 128
    private const val FORMAT_VERSION: Byte = 1
    private val MAGIC = byteArrayOf('P'.code.toByte(), 'F'.code.toByte(), 'K'.code.toByte())

    fun wrap(key: ByteArray, wrappingKey: SecretKey): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, wrappingKey) }
        val sealed = cipher.doFinal(key)
        val iv = cipher.iv
        return ByteBuffer.allocate(MAGIC.size + 2 + iv.size + sealed.size)
            .put(MAGIC).put(FORMAT_VERSION).put(iv.size.toByte()).put(iv).put(sealed)
            .array()
    }

    fun unwrap(blob: ByteArray, wrappingKey: SecretKey): ByteArray {
        val header = MAGIC.size + 2
        if (blob.size < header) throw MalformedKeyFileException("short")
        if (!blob.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) throw MalformedKeyFileException("magic")
        if (blob[MAGIC.size] != FORMAT_VERSION) throw MalformedKeyFileException("version")
        val ivLength = blob[MAGIC.size + 1].toInt() and 0xFF
        if (ivLength == 0 || blob.size <= header + ivLength) throw MalformedKeyFileException("iv")
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, wrappingKey, GCMParameterSpec(TAG_BITS, blob, header, ivLength))
        }
        return cipher.doFinal(blob, header + ivLength, blob.size - header - ivLength)
    }
}

/**
 * The random key the database is encrypted with, kept on disk only in wrapped form.
 *
 * Two identical copies live in [dir], each written whole before it replaces the old one, so a torn write or a
 * damaged block costs one copy and never the key. Nothing here is derived from anything the user types, so a
 * background job opens the database while the phone is locked.
 */
class DatabaseKeyStore(
    private val dir: File,
    private val wrapper: KeyWrapper,
    private val random: SecureRandom = SecureRandom(),
) {
    private val primary get() = File(dir, PRIMARY)
    private val backup get() = File(dir, BACKUP)

    fun hasWrappedKey(): Boolean = primary.exists() || backup.exists()

    fun load(): DatabaseKeyState {
        val copies = listOf(primary, backup).filter { it.exists() }
        if (copies.isEmpty()) return DatabaseKeyState.Absent
        val wrappingKey = try {
            wrapper.wrappingKey(create = false) ?: return DatabaseKeyState.Lost
        } catch (e: Exception) {
            return DatabaseKeyState.Unavailable(e)
        }
        for (copy in copies) {
            val blob = runCatching { copy.readBytes() }.getOrNull() ?: continue
            try {
                val key = DatabaseKeyCodec.unwrap(blob, wrappingKey)
                if (key.size != KEY_BYTES) continue
                repairOtherCopy(copy, blob)
                return DatabaseKeyState.Ready(key)
            } catch (_: AEADBadTagException) {
                continue
            } catch (_: MalformedKeyFileException) {
                continue
            } catch (e: Exception) {
                return DatabaseKeyState.Unavailable(e)
            }
        }
        return DatabaseKeyState.Lost
    }

    /**
     * Makes a new key, writes both wrapped copies and reads them back. Throws when any step fails, in which case
     * no database may be encrypted with it.
     */
    fun create(): ByteArray {
        val wrappingKey = wrapper.wrappingKey(create = true) ?: error("no wrapping key")
        val key = ByteArray(KEY_BYTES).also { random.nextBytes(it) }
        val blob = DatabaseKeyCodec.wrap(key, wrappingKey)
        dir.mkdirs()
        writeWhole(primary, blob)
        writeWhole(backup, blob)
        val check = load()
        check(check is DatabaseKeyState.Ready && check.key.contentEquals(key)) { "key did not read back" }
        return key
    }

    /** Moves the wrapped copies into [target], used when the database they opened is set aside with them. */
    fun moveTo(target: File) {
        target.mkdirs()
        for (copy in listOf(primary, backup)) {
            if (copy.exists() && !copy.renameTo(File(target, copy.name))) copy.delete()
        }
    }

    private fun repairOtherCopy(good: File, blob: ByteArray) {
        val other = if (good == primary) backup else primary
        val intact = runCatching { other.exists() && other.readBytes().contentEquals(blob) }.getOrDefault(false)
        if (!intact) runCatching { writeWhole(other, blob) }
    }

    private companion object {
        const val PRIMARY = "key.bin"
        const val BACKUP = "key.bak"
        const val KEY_BYTES = 32
    }
}

/** Writes [bytes] to a temporary file, forces it to storage and renames it over [target]. */
internal fun writeWhole(target: File, bytes: ByteArray) {
    val temp = File(target.parentFile, target.name + ".tmp")
    FileOutputStream(temp).use { out ->
        out.write(bytes)
        out.fd.sync()
    }
    if (!temp.renameTo(target)) {
        temp.delete()
        error("rename failed")
    }
}

/** The key in SQLCipher's raw form, which it uses as is instead of deriving one from a passphrase. */
internal fun rawKeySpec(key: ByteArray): String =
    buildString(key.size * 2 + 3) {
        append("x'")
        for (b in key) {
            val v = b.toInt() and 0xFF
            append(HEX[v ushr 4])
            append(HEX[v and 0x0F])
        }
        append('\'')
    }

private val HEX = "0123456789ABCDEF".toCharArray()
