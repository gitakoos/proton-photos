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

import eu.akoos.photos.data.db.encryption.DatabaseKeyState
import eu.akoos.photos.data.db.encryption.DatabaseKeyStore
import eu.akoos.photos.data.db.encryption.KeyWrapper
import eu.akoos.photos.data.db.encryption.writeWhole
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

/** Who the key new data is written with belongs to. */
object LocalKeyOwner {
    const val UNASSIGNED = "unassigned"
    const val GUEST = "guest"
    private const val ACCOUNT_PREFIX = "acct:"

    fun account(userId: String): String = ACCOUNT_PREFIX + userHash(userId)

    fun isAccount(owner: String): Boolean = owner.startsWith(ACCOUNT_PREFIX)

    fun hashOf(owner: String): String? = owner.removePrefix(ACCOUNT_PREFIX).takeIf { isAccount(owner) }

    /** A stable name for an account in file names, from which the account id cannot be read back. */
    fun userHash(userId: String): String =
        MessageDigest.getInstance("SHA-256").digest("pfp-owner:$userId".toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }.take(32)
}

/**
 * The master keys local files are encrypted with, each kept only sealed by one Android Keystore key, in two
 * copies ([DatabaseKeyStore]). One of them is current: new files are written with it. Every file names the key it
 * was written with, so a new current key needs no re-encryption of anything, and no key is ever deleted while the
 * app is installed, so every file stays readable for as long as its key is.
 *
 * Nothing here asks the user for anything, so a background job reads and writes while the phone is locked.
 */
class LocalKeyring(
    private val dir: File,
    private val wrapper: KeyWrapper,
    private val random: SecureRandom = SecureRandom(),
    /** The time since boot, which the wait for a silent Keystore is measured on. */
    private val clock: () -> Long = eu.akoos.photos.data.db.encryption.RealTime::elapsed,
) : MasterKeys {

    class Current(val keyId: String, val key: ByteArray, val owner: String)

    private val keyringDir get() = File(dir, KEYRING_DIR)
    private val currentFile get() = File(keyringDir, CURRENT)
    private val currentBackup get() = File(keyringDir, CURRENT_BACKUP)
    private val unavailableFile get() = File(dir, UNAVAILABLE)
    private val cache = ConcurrentHashMap<String, ByteArray>()

    /** Told about every key made, right after it is written, so its recovery copies follow at once. It must
     *  return quickly and never use this keyring's lock: it runs while that lock is held. */
    @Volatile var onMinted: ((Current) -> Unit)? = null
    @Volatile private var unavailableNotedThisProcess = false

    private fun storeOf(keyId: String) = DatabaseKeyStore(File(keyringDir, keyId), wrapper, random)

    override fun keyFor(keyId: String): ByteArray {
        cache[keyId]?.let { return it }
        if (!isKeyId(keyId) || !File(keyringDir, keyId).isDirectory) throw LocalKeyLockedException(keyId)
        return when (val state = storeOf(keyId).load()) {
            is DatabaseKeyState.Ready -> {
                clearUnavailable()
                cache[keyId] = state.key
                state.key
            }
            DatabaseKeyState.Absent, DatabaseKeyState.Lost -> throw LocalKeyLockedException(keyId)
            is DatabaseKeyState.Unavailable -> throw LocalKeyUnavailableException(state.cause)
        }
    }

    /** Whether [keyId] opens now, without throwing. */
    fun state(keyId: String): DatabaseKeyState {
        cache[keyId]?.let { return DatabaseKeyState.Ready(it) }
        if (!isKeyId(keyId) || !File(keyringDir, keyId).isDirectory) return DatabaseKeyState.Absent
        return storeOf(keyId).load().also { if (it is DatabaseKeyState.Ready) cache[keyId] = it.key }
    }

    fun keyIds(): List<String> =
        keyringDir.listFiles()?.filter { it.isDirectory && isKeyId(it.name) }?.map { it.name }?.sorted() ?: emptyList()

    /** The keys this phone holds but cannot open now (lost with the Keystore), which only a recovery copy opens. */
    fun lockedKeyIds(): List<String> = keyIds().filter {
        val state = state(it)
        state is DatabaseKeyState.Lost || state is DatabaseKeyState.Absent
    }

    /** The current key's id and owner as recorded, without unsealing anything. */
    fun currentInfo(): Pair<String, String>? = readCurrent()

    /**
     * The key new data is written with. Made on first use; replaced by a new one (same owner) when the recorded
     * one can never open again, the lost one staying for its recovery copies. Throws
     * [LocalKeyUnavailableException] when the Keystore does not answer.
     */
    fun current(): Current {
        readCurrent()?.let { (id, owner) ->
            cache[id]?.let { return Current(id, it, owner) }
        }
        return mutate {
            val recorded = readCurrent() ?: return@mutate mint(LocalKeyOwner.UNASSIGNED)
            val (id, owner) = recorded
            try {
                Current(id, keyFor(id), owner)
            } catch (_: LocalKeyLockedException) {
                mint(owner)
            }
        }
    }

    /** Makes a new current key for [owner]. The previous one stays, so what it encrypted stays readable. */
    fun rotate(owner: String): Current = mutate { mint(owner) }

    /** Records that the current key now belongs to [owner] (a guest's data moving into the account it signs in to). */
    fun setOwner(owner: String) {
        mutate {
            val (id, _) = readCurrent() ?: return@mutate
            writeCurrent(id, owner)
        }
    }

    /** Seals [key] again under the Keystore key, for a key a recovery copy gave back. */
    fun restore(keyId: String, key: ByteArray) {
        require(isKeyId(keyId)) { "key id" }
        mutate {
            try {
                storeOf(keyId).store(key)
            } catch (e: Exception) {
                throw LocalKeyUnavailableException(e)
            }
            cache[keyId] = key
        }
    }

    /**
     * Counts a launch on which the Keystore did not answer and answers whether it has stayed that way long
     * enough (three launches over at least an hour of real time, measured on the time since boot) to stop waiting
     * for it. Bounds a crash loop on a phone whose Keystore is broken for good; whatever is then set aside is kept,
     * never deleted.
     */
    fun unavailableTooLong(): Boolean {
        val now = clock()
        val parts = runCatching { unavailableFile.readText().trim().split(' ').map { it.toLong() } }.getOrNull()
            ?.takeIf { it.size == 3 }
        var span = parts?.get(0) ?: 0L
        val last = parts?.get(1) ?: now
        var count = parts?.get(2) ?: 0L
        if (!unavailableNotedThisProcess) {
            unavailableNotedThisProcess = true
            if (count > 0) span = eu.akoos.photos.data.db.encryption.RealTime.advance(span, last, now)
            count++
            dir.mkdirs()
            runCatching { writeWhole(unavailableFile, "$span $now $count".toByteArray()) }
        }
        return count >= UNAVAILABLE_LAUNCHES && span >= UNAVAILABLE_SPAN_MS
    }

    private fun clearUnavailable() {
        if (unavailableFile.exists()) unavailableFile.delete()
    }

    private fun mint(owner: String): Current {
        val id = LocalCryptoFormat.keyIdHex(ByteArray(LocalCryptoFormat.KEY_ID_BYTES).also { random.nextBytes(it) })
        val key = try {
            storeOf(id).create()
        } catch (e: Exception) {
            File(keyringDir, id).deleteRecursively()
            throw LocalKeyUnavailableException(e)
        }
        cache[id] = key
        writeCurrent(id, owner)
        return Current(id, key, owner).also { minted -> runCatching { onMinted?.invoke(minted) } }
    }

    private fun readCurrent(): Pair<String, String>? {
        for (file in listOf(currentFile, currentBackup)) {
            val parts = runCatching { file.readText().trim().split(' ') }.getOrNull() ?: continue
            val id = parts.getOrNull(0) ?: continue
            if (!isKeyId(id)) continue
            return id to (parts.getOrNull(1) ?: LocalKeyOwner.UNASSIGNED)
        }
        return null
    }

    private fun writeCurrent(id: String, owner: String) {
        keyringDir.mkdirs()
        val bytes = "$id $owner".toByteArray()
        writeWhole(currentFile, bytes)
        runCatching { writeWhole(currentBackup, bytes) }
    }

    /** Runs [block] under this process's lock and a file lock, so two processes never make a key at once. */
    private fun <T> mutate(block: () -> T): T = synchronized(LOCK) {
        keyringDir.mkdirs()
        RandomAccessFile(File(keyringDir, LOCK_FILE), "rw").use { raf ->
            raf.channel.lock().use { block() }
        }
    }

    companion object {
        private const val KEYRING_DIR = "keyring"
        private const val CURRENT = "current"
        private const val CURRENT_BACKUP = "current.bak"
        private const val LOCK_FILE = ".lock"
        private const val UNAVAILABLE = "key_unavailable"
        private const val UNAVAILABLE_LAUNCHES = 3L
        private const val UNAVAILABLE_SPAN_MS = 60L * 60 * 1000
        private val LOCK = Any()

        fun isKeyId(value: String): Boolean =
            value.length == LocalCryptoFormat.KEY_ID_BYTES * 2 && value.all { it in '0'..'9' || it in 'a'..'f' }
    }
}
