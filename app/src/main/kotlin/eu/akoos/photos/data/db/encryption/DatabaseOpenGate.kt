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

import eu.akoos.photos.data.localcrypto.LocalKeyOwner
import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

/** What a probe of an encrypted file found. */
sealed interface ProbeResult {
    data object Ok : ProbeResult

    /** SQLite reads the file as not a database or corrupt: the key does not open it, or its pages are damaged. */
    data object Unreadable : ProbeResult

    /** Anything else, such as an I/O error. Says nothing about the file itself. */
    class Failed(val cause: Throwable) : ProbeResult
}

/** The SQLCipher work the gate needs, behind an interface so the decisions run in a unit test. */
interface CipherEngine {
    fun probe(file: File, keySpec: String): ProbeResult

    /** Whether `PRAGMA quick_check` passes. */
    fun quickCheck(file: File, keySpec: String): Boolean

    /**
     * Copies the plaintext [plain] into a new encrypted [partial] and verifies the copy: integrity check, schema,
     * user version and the row count of every table. Throws when any of it fails; [plain] is only read.
     */
    fun export(plain: File, partial: File, keySpec: String): ExportReport

    /**
     * Copies [tables] from the set-aside database [old] into the live database [live] in one transaction: every
     * row of each table is replaced, columns matched by name. Skips (changing nothing) when the two schema
     * versions differ, or when [accountHash] is given and the live database does not hold that account.
     */
    fun restoreTables(
        live: File,
        liveKeySpec: String,
        old: File,
        oldKeySpec: String,
        tables: List<String>,
        accountHash: String?,
        hashOf: (String) -> String,
    ): RestoreResult
}

/** What restoring a set-aside database's own tables did. */
sealed interface RestoreResult {
    data class Restored(val tables: Int, val rows: Long) : RestoreResult

    data class Skipped(val reason: String) : RestoreResult
}

data class ExportReport(val tables: Int, val rows: Long)

/** The file Room opens, by absolute path, and how. */
sealed interface OpenTarget {
    val name: String

    class Encrypted(override val name: String, val key: ByteArray) : OpenTarget

    class Plain(override val name: String) : OpenTarget
}

class DatabaseKeyUnavailableException(cause: Throwable) : IllegalStateException("database key unavailable", cause)

/**
 * Decides, on every first open of the database in a process, which file Room opens: the encrypted one, or the
 * plaintext one it replaces, and moves the plaintext data into an encrypted copy when it safely can.
 *
 * The rules that keep data safe:
 * - The plaintext file is deleted only after the encrypted copy was verified, renamed into place, AND opened by
 *   Room ([opened]). Until then every failure opens the plaintext file, exactly as before.
 * - An encrypted file that cannot be read is never deleted: it is moved to [setAsideDir].
 * - A Keystore that does not answer moves nothing; the open fails like an I/O error would.
 */
class DatabaseOpenGate(
    private val databasesDir: File,
    private val stateDir: File,
    private val setAsideDir: File,
    private val keys: DatabaseKeyStore,
    private val engine: CipherEngine,
    private val versionCode: Int,
    private val freeBytes: (File) -> Long?,
    private val clock: () -> Long,
    private val log: (String) -> Unit,
    private val recoveredKey: (File) -> ByteArray? = { null },
    private val appTables: List<String> = AppOwnedTables.NAMES,
    private val hashOf: (String) -> String = LocalKeyOwner::userHash,
) {
    val plain = File(databasesDir, PLAIN_NAME)
    val encrypted = File(databasesDir, ENCRYPTED_NAME)
    val partial = File(databasesDir, PARTIAL_NAME)
    val corruptMarker = File(stateDir, CORRUPT_MARKER)
    private val rebuiltMarker = File(stateDir, REBUILT_MARKER)
    private val attemptsFile = File(stateDir, ATTEMPTS_FILE)
    private val unavailableFile = File(stateDir, UNAVAILABLE_FILE)

    fun prepare(): OpenTarget = withLocks {
        if (partial.exists() || sidecarsExist(partial)) {
            deleteDatabase(partial)
            log("db: unfinished copy removed")
        }
        if (encrypted.exists()) restoreRecovered()
        pruneSetAside()
        if (encrypted.exists()) openExisting()?.let { return@withLocks it }
        withoutEncrypted()
    }

    /** Called once Room has opened [target]: only now is the plaintext file it replaced removed. */
    fun opened(target: OpenTarget) {
        if (target !is OpenTarget.Encrypted) return
        withLocks {
            if (plain.exists() || sidecarsExist(plain)) {
                deleteDatabase(plain)
                log("db: plaintext copy removed")
            }
            attemptsFile.delete()
        }
    }

    private fun openExisting(): OpenTarget? {
        when (val state = keys.load()) {
            is DatabaseKeyState.Ready -> {
                unavailableFile.delete()
                val spec = rawKeySpec(state.key)
                when (val probe = engine.probe(encrypted, spec)) {
                    ProbeResult.Ok -> {
                        if (corruptMarker.exists()) {
                            if (engine.quickCheck(encrypted, spec)) {
                                corruptMarker.delete()
                                log("db: check after a corruption report passed")
                            } else {
                                setAside("corrupt", moveKey = false)
                                return null
                            }
                        }
                        return OpenTarget.Encrypted(encrypted.path, state.key)
                    }
                    ProbeResult.Unreadable -> {
                        setAside("unreadable", moveKey = false)
                        return null
                    }
                    is ProbeResult.Failed -> throw probe.cause
                }
            }
            DatabaseKeyState.Absent, DatabaseKeyState.Lost -> {
                setAside("key lost", moveKey = true)
                return null
            }
            is DatabaseKeyState.Unavailable -> {
                if (!unavailableLongEnough()) throw DatabaseKeyUnavailableException(state.cause)
                setAside("key unavailable", moveKey = true)
                return null
            }
        }
    }

    private fun withoutEncrypted(): OpenTarget {
        deleteDatabase(encrypted)
        corruptMarker.delete()
        if (!plain.exists()) {
            val key = obtainKey() ?: return OpenTarget.Plain(plain.path)
            return OpenTarget.Encrypted(encrypted.path, key)
        }
        if (!attemptsLeft()) {
            log("db: stays plaintext, copy attempts used up for this version")
            return OpenTarget.Plain(plain.path)
        }
        val plainBytes = databaseBytes(plain)
        val needed = migrationSpaceNeeded(plainBytes)
        val free = freeBytes(databasesDir)
        if (free != null && free < needed) {
            log("db: stays plaintext, needs ${mb(needed)} free, has ${mb(free)}")
            return OpenTarget.Plain(plain.path)
        }
        val key = obtainKey() ?: return OpenTarget.Plain(plain.path)
        spendAttempt()
        val startedAt = clock()
        return try {
            val report = engine.export(plain, partial, rawKeySpec(key))
            syncFile(partial)
            moveIntoPlace(partial, encrypted)
            log("db: encrypted ${report.tables} tables, ${report.rows} rows, ${mb(plainBytes)} in ${clock() - startedAt} ms")
            OpenTarget.Encrypted(encrypted.path, key)
        } catch (e: Exception) {
            deleteDatabase(partial)
            deleteDatabase(encrypted)
            log("db: copy failed (${e.javaClass.simpleName}), stays plaintext")
            OpenTarget.Plain(plain.path)
        }
    }

    private fun obtainKey(): ByteArray? = when (val state = keys.load()) {
        is DatabaseKeyState.Ready -> state.key
        DatabaseKeyState.Absent, DatabaseKeyState.Lost -> try {
            keys.create()
        } catch (e: Exception) {
            log("db: key not created (${e.javaClass.simpleName}), stays plaintext")
            null
        }
        is DatabaseKeyState.Unavailable -> {
            log("db: key store not answering, stays plaintext")
            null
        }
    }

    private fun setAside(reason: String, moveKey: Boolean) {
        val target = File(setAsideDir, "${clock()}")
        target.mkdirs()
        for (file in databaseFiles(encrypted)) {
            if (file.exists() && !file.renameTo(File(target, file.name))) file.delete()
        }
        if (moveKey) keys.moveTo(target)
        corruptMarker.delete()
        if (!plain.exists()) {
            stateDir.mkdirs()
            rebuiltMarker.createNewFile()
        }
        log("db: encrypted file set aside ($reason)")
    }

    /**
     * Copies the app's own tables back from every set-aside database whose key a recovery copy gave back, into
     * the live database, and removes that set-aside folder once done. Anything that does not fit (another
     * account, another schema version, an error) leaves both databases as they were.
     */
    private fun restoreRecovered() {
        val dirs = setAsideDir.listFiles()?.filter { File(it, RECOVERED_DIR).isDirectory } ?: return
        if (dirs.isEmpty()) return
        val live = keys.load() as? DatabaseKeyState.Ready ?: return
        for (dir in dirs) {
            val old = File(dir, ENCRYPTED_NAME)
            val oldKey = if (old.exists()) recoveredKey(File(dir, RECOVERED_DIR)) else null
            if (oldKey == null) {
                log("db: recovered key or database missing, kept")
                continue
            }
            val owner = runCatching { File(File(dir, RECOVERED_DIR), RECOVERED_OWNER).readText().trim() }
                .getOrNull()?.takeIf { it.isNotEmpty() }
            try {
                when (val result = engine.restoreTables(encrypted, rawKeySpec(live.key), old, rawKeySpec(oldKey), appTables, owner, hashOf)) {
                    is RestoreResult.Restored -> {
                        dir.deleteRecursively()
                        log("db: restored ${result.tables} tables, ${result.rows} rows from the set-aside copy")
                    }
                    is RestoreResult.Skipped -> log("db: set-aside copy not restored (${result.reason}), kept")
                }
            } catch (e: Exception) {
                log("db: restore failed (${e.javaClass.simpleName}), both copies kept")
            }
        }
    }

    /**
     * Keeps the newest set-aside database for [SET_ASIDE_KEEP_MS], and every one that a recovery copy may still open
     * (up to [MAX_WAITING_SET_ASIDE]) until it is restored.
     */
    private fun pruneSetAside() {
        val kept = setAsideDir.listFiles()?.sortedByDescending { it.name.toLongOrNull() ?: 0L } ?: return
        val now = clock()
        var plain = 0
        var waiting = 0
        for (dir in kept) {
            if (isWaiting(dir)) {
                waiting++
                if (waiting > MAX_WAITING_SET_ASIDE) dir.deleteRecursively()
                continue
            }
            val age = now - (dir.name.toLongOrNull() ?: 0L)
            if (plain++ > 0 || age > SET_ASIDE_KEEP_MS || age < 0) dir.deleteRecursively()
        }
    }

    private fun isWaiting(dir: File): Boolean =
        File(dir, RECOVERED_DIR).isDirectory ||
            (File(dir, DatabaseKeyStore.RECOVERY_DIR).listFiles()?.isNotEmpty() ?: false)

    private fun attemptsLeft(): Boolean {
        val (version, count) = readPair(attemptsFile)
        return version != versionCode.toLong() || count < MAX_ATTEMPTS
    }

    private fun spendAttempt() {
        val (version, count) = readPair(attemptsFile)
        val next = if (version == versionCode.toLong()) count + 1 else 1
        writePair(attemptsFile, versionCode.toLong(), next)
    }

    private fun unavailableLongEnough(): Boolean {
        val now = clock()
        val (first, count) = readPair(unavailableFile)
        val since = if (count == 0L || first <= 0L) now else first
        writePair(unavailableFile, since, count + 1)
        return count + 1 >= UNAVAILABLE_LAUNCHES && now - since >= UNAVAILABLE_SPAN_MS
    }

    private fun readPair(file: File): Pair<Long, Long> {
        val parts = runCatching { file.readText().trim().split(' ') }.getOrNull() ?: return 0L to 0L
        return (parts.getOrNull(0)?.toLongOrNull() ?: 0L) to (parts.getOrNull(1)?.toLongOrNull() ?: 0L)
    }

    private fun writePair(file: File, first: Long, second: Long) {
        stateDir.mkdirs()
        runCatching { writeWhole(file, "$first $second".toByteArray()) }
    }

    private inline fun <T> withLocks(block: () -> T): T = synchronized(LOCK) {
        stateDir.mkdirs()
        RandomAccessFile(File(stateDir, LOCK_FILE), "rw").use { raf ->
            raf.channel.lock().use { block() }
        }
    }

    companion object {
        const val PLAIN_NAME = "proton_photos.db"
        const val ENCRYPTED_NAME = "proton_photos_encrypted.db"
        const val PARTIAL_NAME = "proton_photos_encrypting.db"
        const val REBUILT_MARKER = "rebuilt"
        const val RECOVERED_DIR = "recovered"
        const val RECOVERED_OWNER = "owner"
        private const val MAX_WAITING_SET_ASIDE = 3
        private const val CORRUPT_MARKER = "corrupt"
        private const val ATTEMPTS_FILE = "copy_attempts"
        private const val UNAVAILABLE_FILE = "key_unavailable"
        private const val LOCK_FILE = "open.lock"
        const val MAX_ATTEMPTS = 3L
        private const val UNAVAILABLE_LAUNCHES = 3L
        private const val UNAVAILABLE_SPAN_MS = 60L * 60 * 1000
        private const val SET_ASIDE_KEEP_MS = 14L * 24 * 60 * 60 * 1000
        private const val SPACE_MARGIN_BYTES = 50L * 1024 * 1024
        private val LOCK = Any()

        /** Free space a copy of [plainBytes] asks for: the copy itself, its page overhead and a margin. */
        fun migrationSpaceNeeded(plainBytes: Long): Long = plainBytes + plainBytes / 10 + SPACE_MARGIN_BYTES

        private fun mb(bytes: Long): String = "${bytes / (1024 * 1024)} MB"
    }
}

private val SIDECARS = listOf("-wal", "-shm", "-journal")

internal fun databaseFiles(db: File): List<File> = listOf(db) + SIDECARS.map { File(db.path + it) }

internal fun sidecarsExist(db: File): Boolean = SIDECARS.any { File(db.path + it).exists() }

internal fun deleteDatabase(db: File) {
    for (file in databaseFiles(db)) if (file.exists()) file.delete()
}

internal fun databaseBytes(db: File): Long = databaseFiles(db).sumOf { if (it.exists()) it.length() else 0L }

private fun syncFile(file: File) {
    RandomAccessFile(file, "rw").use { it.fd.sync() }
}

/** Renames [from] over [to] in one step, after clearing any sidecar of an older [to], then syncs the directory. */
private fun moveIntoPlace(from: File, to: File) {
    deleteDatabase(to)
    Files.move(from.toPath(), to.toPath(), StandardCopyOption.ATOMIC_MOVE)
    runCatching { FileChannel.open(to.parentFile!!.toPath(), StandardOpenOption.READ).use { it.force(true) } }
}
