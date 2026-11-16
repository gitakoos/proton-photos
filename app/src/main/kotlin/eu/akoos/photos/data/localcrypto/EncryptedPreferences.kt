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

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.okio.OkioSerializer
import androidx.datastore.core.okio.OkioStorage
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferencesSerializer
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStoreFile
import eu.akoos.photos.data.db.encryption.writeWhole
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import okio.Buffer
import okio.BufferedSink
import okio.BufferedSource
import okio.FileSystem
import okio.Path.Companion.toPath
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.concurrent.ConcurrentHashMap
import kotlin.properties.ReadOnlyProperty
import kotlin.reflect.KProperty

/**
 * Copies of encrypted settings files whose key could not be opened, kept until a recovery copy opens that key.
 * A copy is written whole and synced before the settings file may be answered as empty, so the original bytes
 * always survive the empty file DataStore then writes over them.
 */
class LockedStoreFiles(private val dir: File, private val clock: () -> Long = System::currentTimeMillis) {

    fun setAside(storeName: String, bytes: ByteArray) {
        dir.mkdirs()
        // A copy already kept under the same time is never written over: the next free time takes this one.
        var at = clock()
        while (File(dir, "$storeName-$at$SUFFIX").exists()) at++
        writeWhole(File(dir, "$storeName-$at$SUFFIX"), bytes)
    }

    /** The copies of [storeName], newest first, by the time each was set aside (in its name). */
    fun pending(storeName: String): List<File> =
        dir.listFiles().orEmpty()
            .mapNotNull { f -> setAsideAt(storeName, f)?.let { f to it } }
            .sortedByDescending { it.second }
            .map { it.first }

    private fun setAsideAt(storeName: String, file: File): Long? {
        if (!file.name.startsWith("$storeName-") || !file.name.endsWith(SUFFIX)) return null
        return file.name.removePrefix("$storeName-").removeSuffix(SUFFIX).toLongOrNull()
    }

    fun all(): List<File> = dir.listFiles()?.filter { it.name.endsWith(SUFFIX) } ?: emptyList()

    /** The copies of [storeName] the user has not chosen to go on without ([acknowledge]), newest first. */
    fun unacknowledged(storeName: String): List<File> {
        val seen = acknowledged()
        return pending(storeName).filter { it.name !in seen }
    }

    /** Notes every copy of [storeName] set aside so far as seen. The copies themselves stay, to merge back later. */
    fun acknowledge(storeName: String) {
        val names = acknowledged() + pending(storeName).map { it.name }
        dir.mkdirs()
        writeWhole(File(dir, ACKNOWLEDGED), names.joinToString("\n").toByteArray())
    }

    private fun acknowledged(): Set<String> =
        runCatching { File(dir, ACKNOWLEDGED).readLines().filter { it.isNotBlank() }.toSet() }.getOrNull().orEmpty()

    private companion object {
        const val SUFFIX = ".pfle"
        const val ACKNOWLEDGED = "acknowledged"
    }
}

/**
 * The preferences serializer with every file encrypted on the way out and decrypted on the way in. A plaintext
 * file (written before this build, or while the Keystore did not answer) still reads, so nothing depends on
 * the migration having run.
 *
 * Never answers empty for a file it could not read unless the file's bytes were first kept in [locked]: a
 * locked or damaged file is copied there and then answered as empty; a Keystore that does not answer is waited
 * for and then reported as an error, unless it has stayed silent for launches on end. Either way the settings
 * file is then held back ([SettingsLock]) until it reads again or the copy merges back.
 *
 * With a keyring a file is only ever written encrypted: a write the key cannot seal is refused with an error,
 * so the file on disk stays as it was. Only a platform without a Keystore writes plaintext.
 */
class EncryptedPreferencesSerializer(
    private val storeName: String,
    private val keyring: LocalKeyring?,
    private val locked: LockedStoreFiles,
    private val log: (String) -> Unit,
    private val pause: suspend (Long) -> Unit = { delay(it) },
) : OkioSerializer<Preferences> {

    override val defaultValue: Preferences get() = PreferencesSerializer.defaultValue

    override suspend fun readFrom(source: BufferedSource): Preferences {
        val bytes = source.readByteArray()
        if (keyring == null || !LocalCryptoFormat.looksEncrypted(bytes)) return parse(bytes).also { readable() }
        var attempt = 0
        while (true) {
            try {
                return parse(LocalCryptoFormat.decryptBytes(bytes, keyring)).also { readable() }
            } catch (e: LocalKeyUnavailableException) {
                if (++attempt < READ_ATTEMPTS) {
                    pause(RETRY_MS)
                    continue
                }
                if (!keyring.unavailableTooLong()) {
                    SettingsLock.readOutcome(storeName, readable = false)
                    throw IOException("$storeName: key not available", e)
                }
                return setAsideAndStartEmpty(bytes, "key unavailable")
            } catch (_: LocalKeyLockedException) {
                return setAsideAndStartEmpty(bytes, "key locked")
            } catch (_: LocalFileDamagedException) {
                return setAsideAndStartEmpty(bytes, "damaged")
            }
        }
    }

    override suspend fun writeTo(t: Preferences, sink: BufferedSink) {
        val plain = Buffer().also { PreferencesSerializer.writeTo(t, it) }.readByteArray()
        sink.write(if (keyring == null) plain else encryptVerified(keyring, plain))
    }

    /** [plain] sealed with the current key; throws rather than hand back bytes that are not encrypted. */
    private suspend fun encryptVerified(keyring: LocalKeyring, plain: ByteArray): ByteArray {
        var attempt = 0
        while (true) {
            try {
                return sealStoreBytes(keyring, storeName, plain)
            } catch (e: LocalKeyUnavailableException) {
                if (++attempt < WRITE_ATTEMPTS) {
                    pause(RETRY_MS)
                    continue
                }
                log("$storeName: key not available, change not written")
                throw IOException("$storeName: key not available, change not written", e)
            } catch (e: Exception) {
                log("$storeName: change not written (${e.javaClass.simpleName})")
                throw IOException("$storeName: could not encrypt, change not written", e)
            }
        }
    }

    private fun readable() = SettingsLock.readOutcome(storeName, readable = true)

    private suspend fun setAsideAndStartEmpty(bytes: ByteArray, reason: String): Preferences {
        try {
            locked.setAside(storeName, bytes)
        } catch (e: Exception) {
            throw IOException("$storeName: could not keep the unreadable file", e)
        }
        log("$storeName: kept aside ($reason), starting empty")
        SettingsLock.readOutcome(storeName, readable = true)
        return emptyPreferences()
    }

    private suspend fun parse(bytes: ByteArray): Preferences = PreferencesSerializer.readFrom(Buffer().write(bytes))

    companion object {
        private const val READ_ATTEMPTS = 6
        private const val WRITE_ATTEMPTS = 6
        private const val RETRY_MS = 500L
    }
}

/** Encrypts [plain] as a settings file with the current key and proves it decrypts back to the same bytes. */
internal fun sealStoreBytes(keyring: LocalKeyring, storeName: String, plain: ByteArray): ByteArray {
    val current = keyring.current()
    val sealed = LocalCryptoFormat.encryptBytes(
        plain,
        LocalCryptoFormat.Sealing(current.keyId, current.key, LocalFileKind.STORE, LocalFileMeta(storeName = storeName)),
    )
    check(LocalCryptoFormat.decryptBytes(sealed, keyring).contentEquals(plain)) { "settings copy did not verify" }
    return sealed
}

/** The one-time move of a plaintext settings file into its encrypted form, done before DataStore first reads it. */
object LocalStoreMigration {

    /**
     * Encrypts [file] in place when it is plaintext: the bytes must parse as preferences, the encrypted copy is
     * written beside it and synced, read back and decrypted to the same bytes, and only then renamed over it in
     * one step. Any failure leaves the plaintext file as it was. Answers whether the file is encrypted now.
     *
     * Runs under a lock on a file beside it, held across processes and threads, so a second process opening
     * the same settings never deletes the temp copy of a migration still running in the first.
     */
    fun migrate(file: File, storeName: String, keyring: LocalKeyring?, log: (String) -> Unit): Boolean =
        withMigrationLock(file) { migrateLocked(file, storeName, keyring, log) }

    /** Runs [block] holding the cross-process lock that [migrate] of [file] holds. */
    internal fun <T> withMigrationLock(file: File, block: () -> T): T = synchronized(this) {
        file.parentFile?.mkdirs()
        RandomAccessFile(File(file.parentFile, file.name + LOCK_SUFFIX), "rw").use { raf ->
            raf.channel.lock().use { block() }
        }
    }

    private fun migrateLocked(file: File, storeName: String, keyring: LocalKeyring?, log: (String) -> Unit): Boolean {
        val temp = File(file.parentFile, file.name + TEMP_SUFFIX)
        if (temp.exists()) temp.delete()
        if (keyring == null || !file.isFile || file.length() == 0L) return false
        val bytes = runCatching { file.readBytes() }.getOrNull() ?: return false
        if (LocalCryptoFormat.looksEncrypted(bytes)) return true
        val parsed = runCatching { runBlocking { PreferencesSerializer.readFrom(Buffer().write(bytes)) } }.isSuccess
        if (!parsed) {
            log("$storeName: not a readable settings file, left as it is")
            return false
        }
        return try {
            val sealed = sealStoreBytes(keyring, storeName, bytes)
            FileOutputStream(temp).use { out ->
                out.write(sealed)
                out.fd.sync()
            }
            check(LocalCryptoFormat.decryptBytes(temp.readBytes(), keyring).contentEquals(bytes)) { "copy differs" }
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            syncDirectory(file.parentFile)
            log("$storeName: encrypted (${bytes.size} bytes)")
            true
        } catch (e: Exception) {
            temp.delete()
            log("$storeName: stays as before this start (${e.javaClass.simpleName})")
            false
        }
    }

    private const val TEMP_SUFFIX = ".enc-tmp"
    private const val LOCK_SUFFIX = ".migrate-lock"
}

internal fun syncDirectory(dir: File?) {
    if (dir == null) return
    runCatching { FileChannel.open(dir.toPath(), StandardOpenOption.READ).use { it.force(true) } }
}

/**
 * Settings files opened through [encryptedPreferencesDataStore], by name, so a recovered key can merge a locked copy
 * back into the live file.
 */
object LocalStores {
    private val stores = ConcurrentHashMap<String, DataStore<Preferences>>()

    internal fun register(name: String, store: DataStore<Preferences>) {
        stores[name] = store
    }

    fun named(name: String): DataStore<Preferences>? = stores[name]

    /**
     * Merges every locked copy of [storeName] whose key opens now into [store], newest first, then deletes that
     * copy. A value set since then wins: a copy only fills the keys the live file (and the newer copies merged before
     * it) do not have, and sets are joined, so a photo hidden in either stays listed. [keepOut] names, per copy,
     * keys it must not bring back at all (a Hidden password the user removed after that copy was set aside).
     * [newestOnly] names keys taken from the newest copy alone (by the time it was set aside), merged or not yet
     * readable: an older copy never fills one while a newer copy exists. [noJoin] names sets that are never joined: the live set, when there is one, stays as it is,
     * so an entry removed since the copy was set aside does not come back. Answers how many copies were merged.
     */
    suspend fun restoreLocked(
        storeName: String,
        store: DataStore<Preferences>,
        keyring: LocalKeyring,
        locked: LockedStoreFiles,
        keepOut: (File) -> Set<String> = { emptySet() },
        newestOnly: Set<String> = emptySet(),
        noJoin: Set<String> = emptySet(),
        log: (String) -> Unit,
    ): Int {
        var merged = 0
        val pending = locked.pending(storeName)
        for (file in pending) {
            val recovered = try {
                PreferencesSerializer.readFrom(Buffer().write(LocalCryptoFormat.decryptBytes(file.readBytes(), keyring)))
            } catch (_: LocalKeyLockedException) {
                continue
            } catch (_: LocalKeyUnavailableException) {
                continue
            } catch (e: Exception) {
                log("$storeName: locked copy unreadable (${e.javaClass.simpleName}), kept")
                continue
            }
            // Only the newest copy there is may bring these back, even on a start its key does not open.
            val excluded = keepOut(file) + if (file != pending.first()) newestOnly else emptySet()
            store.updateData { current -> mergeRecovered(recovered, current, excluded, noJoin) }
            file.delete()
            merged++
        }
        if (merged > 0) log("$storeName: $merged locked copies merged back")
        SettingsLock.refresh()
        return merged
    }

    @Suppress("UNCHECKED_CAST")
    internal fun mergeRecovered(
        recovered: Preferences,
        current: Preferences,
        keepOut: Set<String> = emptySet(),
        noJoin: Set<String> = emptySet(),
    ): Preferences {
        val out: MutablePreferences = current.toMutablePreferences()
        for ((key, value) in recovered.asMap()) {
            if (key.name in keepOut) continue
            val live = current.asMap()[key]
            val mergedValue: Any = when {
                live == null -> value
                key.name in noJoin -> continue
                value is Set<*> && live is Set<*> -> value + live
                else -> continue
            }
            out[key as Preferences.Key<Any>] = mergedValue
        }
        return out.toPreferences()
    }
}

/**
 * A preferences DataStore exactly like `preferencesDataStore(name)` (same file, same scope, no corruption handler,
 * no migrations), with the file encrypted by the local keys.
 */
fun encryptedPreferencesDataStore(name: String): ReadOnlyProperty<Context, DataStore<Preferences>> =
    EncryptedStoreDelegate(name)

private class EncryptedStoreDelegate(private val name: String) : ReadOnlyProperty<Context, DataStore<Preferences>> {
    @Volatile private var instance: DataStore<Preferences>? = null

    override fun getValue(thisRef: Context, property: KProperty<*>): DataStore<Preferences> =
        instance ?: synchronized(this) {
            instance ?: create(thisRef.applicationContext).also {
                instance = it
                LocalStores.register(name, it)
            }
        }

    private fun create(context: Context): DataStore<Preferences> {
        if (name == SettingsLock.STORE_NAME) SettingsLock.attach(context)
        val keyring = LocalCrypto.keyringOrNull(context)
        val serializer = EncryptedPreferencesSerializer(name, keyring, LocalCrypto.lockedStores(context), LocalCrypto::log)
        return PreferenceDataStoreFactory.create(
            storage = OkioStorage(
                fileSystem = FileSystem.SYSTEM,
                serializer = serializer,
                producePath = {
                    val file = context.preferencesDataStoreFile(name)
                    LocalStoreMigration.migrate(file, name, keyring, LocalCrypto::log)
                    file.absolutePath.toPath()
                },
            ),
            scope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
        )
    }
}
