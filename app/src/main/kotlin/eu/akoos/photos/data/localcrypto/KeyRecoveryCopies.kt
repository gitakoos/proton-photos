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

import eu.akoos.photos.data.db.encryption.writeWhole
import java.io.File
import java.nio.ByteBuffer
import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** Seals a small payload to one account's Proton address key and opens it again. */
interface AccountSealer {
    fun seal(payload: ByteArray): ByteArray

    /** The payload, or null when this account's keys do not open [blob] or it is damaged. */
    fun open(blob: ByteArray): ByteArray?
}

/**
 * Copies of a key that do not depend on the Android Keystore, kept in [dir] beside nothing else: one sealed to the
 * Proton address key of an account (`<name>.acct.<userHash>`), and one sealed with a key derived from the Hidden
 * password (`<name>.pwd`). [name] is the key's id, or `db` for the database key. Each copy carries the name it
 * belongs to, so a copy can never be taken for another key's.
 */
class KeyRecoveryCopies(
    private val dir: File,
    private val random: SecureRandom = SecureRandom(),
    private val iterations: Int = PBKDF2_ITERATIONS,
) {
    fun accountCopy(name: String, userHash: String) = File(dir, "$name$ACCOUNT_INFIX$userHash")

    fun passwordCopy(name: String) = File(dir, "$name$PASSWORD_SUFFIX")

    fun hasAccountCopy(name: String, userHash: String): Boolean = accountCopy(name, userHash).isFile

    fun hasPasswordCopy(name: String): Boolean = passwordCopy(name).isFile

    /** Every key name with an account copy for [userHash]. */
    fun namesWithAccountCopy(userHash: String): List<String> =
        dir.listFiles()?.map { it.name }?.filter { it.endsWith("$ACCOUNT_INFIX$userHash") }
            ?.map { it.substringBefore(ACCOUNT_INFIX) } ?: emptyList()

    fun hasAnyAccountCopy(name: String): Boolean =
        dir.listFiles()?.any { it.name.startsWith("$name$ACCOUNT_INFIX") } ?: false

    /** Writes the account copy of [key] and proves it opens before it replaces any earlier one. */
    fun writeAccountCopy(name: String, key: ByteArray, userHash: String, sealer: AccountSealer) {
        val blob = sealer.seal(payload(name, key))
        check(readPayload(sealer.open(blob), name)?.contentEquals(key) == true) { "account copy did not open" }
        dir.mkdirs()
        writeWhole(accountCopy(name, userHash), blob)
    }

    /** The key in the account copy of [name] for [userHash], or null when there is none or it does not open. */
    fun openAccountCopy(name: String, userHash: String, sealer: AccountSealer): ByteArray? {
        val blob = runCatching { accountCopy(name, userHash).readBytes() }.getOrNull() ?: return null
        return readPayload(runCatching { sealer.open(blob) }.getOrNull(), name)
    }

    /** Writes the password copy of [key] and proves it opens with [password] before it replaces any earlier one. */
    fun writePasswordCopy(name: String, key: ByteArray, password: CharArray) {
        val salt = ByteArray(SALT_BYTES).also { random.nextBytes(it) }
        val iv = ByteArray(IV_BYTES).also { random.nextBytes(it) }
        val head = ByteBuffer.allocate(PASSWORD_HEAD).put(PASSWORD_MAGIC).put(VERSION).putInt(iterations).put(salt).array()
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, derive(password, salt, iterations), GCMParameterSpec(TAG_BITS, iv))
        cipher.updateAAD(head)
        cipher.updateAAD(name.toByteArray(Charsets.UTF_8))
        val blob = head + iv + cipher.doFinal(payload(name, key))
        check(openPassword(blob, name, password)?.contentEquals(key) == true) { "password copy did not open" }
        dir.mkdirs()
        writeWhole(passwordCopy(name), blob)
    }

    /** The key in the password copy of [name], or null when there is none or [password] does not open it. */
    fun openPasswordCopy(name: String, password: CharArray): ByteArray? {
        val blob = runCatching { passwordCopy(name).readBytes() }.getOrNull() ?: return null
        return openPassword(blob, name, password)
    }

    fun deletePasswordCopy(name: String) {
        passwordCopy(name).delete()
    }

    private fun openPassword(blob: ByteArray, name: String, password: CharArray): ByteArray? {
        if (blob.size < PASSWORD_HEAD + IV_BYTES + TAG_BITS / 8) return null
        if (!blob.copyOfRange(0, PASSWORD_MAGIC.size).contentEquals(PASSWORD_MAGIC)) return null
        if (blob[PASSWORD_MAGIC.size] != VERSION) return null
        val buffer = ByteBuffer.wrap(blob, PASSWORD_MAGIC.size + 1, 4 + SALT_BYTES)
        val rounds = buffer.int
        if (rounds < MIN_ITERATIONS_READ || rounds > MAX_ITERATIONS_READ) return null
        val salt = ByteArray(SALT_BYTES).also { buffer.get(it) }
        val iv = blob.copyOfRange(PASSWORD_HEAD, PASSWORD_HEAD + IV_BYTES)
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, derive(password, salt, rounds), GCMParameterSpec(TAG_BITS, iv))
            cipher.updateAAD(blob, 0, PASSWORD_HEAD)
            cipher.updateAAD(name.toByteArray(Charsets.UTF_8))
            readPayload(cipher.doFinal(blob, PASSWORD_HEAD + IV_BYTES, blob.size - PASSWORD_HEAD - IV_BYTES), name)
        } catch (_: GeneralSecurityException) {
            null
        }
    }

    private fun derive(password: CharArray, salt: ByteArray, rounds: Int): SecretKeySpec {
        val spec = PBEKeySpec(password, salt, rounds, 256)
        try {
            val bytes = SecretKeyFactory.getInstance(KDF).generateSecret(spec).encoded
            return SecretKeySpec(bytes, "AES")
        } finally {
            spec.clearPassword()
        }
    }

    companion object {
        /** OWASP's 2023 figure for PBKDF2-HMAC-SHA256; about a second on a mid-range phone, once per unlock. */
        const val PBKDF2_ITERATIONS = 600_000
        private const val MIN_ITERATIONS_READ = 1_000
        private const val MAX_ITERATIONS_READ = 50_000_000
        private const val KDF = "PBKDF2WithHmacSHA256"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val TAG_BITS = 128
        private const val IV_BYTES = 12
        private const val SALT_BYTES = 16
        private const val VERSION: Byte = 1
        private const val ACCOUNT_INFIX = ".acct."
        private const val PASSWORD_SUFFIX = ".pwd"
        private val PAYLOAD_MAGIC = "PFRK".toByteArray(Charsets.US_ASCII)
        private val PASSWORD_MAGIC = "PFRP".toByteArray(Charsets.US_ASCII)
        private val PASSWORD_HEAD = PASSWORD_MAGIC.size + 1 + 4 + SALT_BYTES

        internal fun payload(name: String, key: ByteArray): ByteArray {
            val nameBytes = name.toByteArray(Charsets.UTF_8)
            require(nameBytes.size <= 255) { "name" }
            return ByteBuffer.allocate(PAYLOAD_MAGIC.size + 2 + nameBytes.size + key.size)
                .put(PAYLOAD_MAGIC).put(VERSION).put(nameBytes.size.toByte()).put(nameBytes).put(key).array()
        }

        internal fun readPayload(bytes: ByteArray?, name: String): ByteArray? {
            if (bytes == null || bytes.size < PAYLOAD_MAGIC.size + 2) return null
            if (!bytes.copyOfRange(0, PAYLOAD_MAGIC.size).contentEquals(PAYLOAD_MAGIC)) return null
            if (bytes[PAYLOAD_MAGIC.size] != VERSION) return null
            val nameLength = bytes[PAYLOAD_MAGIC.size + 1].toInt() and 0xFF
            val keyStart = PAYLOAD_MAGIC.size + 2 + nameLength
            if (bytes.size != keyStart + LocalCryptoFormat.KEY_BYTES) return null
            val stored = String(bytes, PAYLOAD_MAGIC.size + 2, nameLength, Charsets.UTF_8)
            if (stored != name) return null
            return bytes.copyOfRange(keyStart, bytes.size)
        }
    }
}
