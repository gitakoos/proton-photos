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
import java.io.IOException
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
     * How many rows of the user's own work the live database [live] holds, counted by [checks] (table name to a
     * `SELECT count(*)` over it); a table the database does not have counts nothing.
     */
    fun liveWorkRows(live: File, liveKeySpec: String, checks: Map<String, String>): Long

    /**
     * Copies [tables] from the set-aside database [old] into the live database [live] in one transaction: every
     * row of each table is replaced, columns matched by name. Skips (changing nothing) when the two schema
     * versions differ, when [accountHash] is given and the live database does not hold that account, or when
     * [sameAccounts] is set and the two databases do not hold exactly the same accounts.
     */
    fun restoreTables(
        live: File,
        liveKeySpec: String,
        old: File,
        oldKeySpec: String,
        tables: List<String>,
        accountHash: String?,
        hashOf: (String) -> String,
        sameAccounts: Boolean,
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
    private val userWork: Map<String, String> = AppOwnedTables.USER_WORK,
    /** The time since boot, which the wait for a silent Keystore is measured on. */
    private val elapsed: () -> Long = RealTime::elapsed,
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

    /**
     * Moves the encrypted database files into a set-aside folder of their own. Never deletes one: when the folder
     * cannot be made or a file cannot be moved, the files already moved go back and the open fails like an I/O
     * error, leaving every file where it was to be tried again at the next start.
     */
    private fun setAside(reason: String, moveKey: Boolean) {
        val target = File(setAsideDir, "${clock()}")
        if (!target.mkdirs() && !target.isDirectory) throw IOException("set-aside folder not made")
        val moved = mutableListOf<Pair<File, File>>()
        for (file in databaseFiles(encrypted)) {
            if (!file.exists()) continue
            val dest = File(target, file.name)
            if (file.renameTo(dest)) {
                moved += file to dest
                continue
            }
            // A file that went back stays in the set-aside folder otherwise, which is kept too.
            for ((from, to) in moved.asReversed()) to.renameTo(from)
            log("db: encrypted file not set aside ($reason), kept in place")
            throw IOException("database file not set aside")
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
     * Copies the app's own tables back, into the live database, from the newest set-aside database whose key opens
     * now: one a recovery copy gave back, or one whose own wrapped copies the Keystore key opens again (it did not
     * answer for a while). Removes that one set-aside folder once done, and no other. At most one is restored per
     * start: the restored rows are the user's work from then on, so an older set-aside database is never copied
     * over them and stays where it is, kept. Runs on every start, so a set-aside database comes back as soon as its
     * key does. Anything that does not fit (another account, another schema version, an error) leaves both
     * databases as they were, to be tried again.
     *
     * The copy replaces whole tables, so it runs only into a live database that holds none of the user's own work
     * yet ([userWork]: edits waiting, uploads queued or done, imports, face and label decisions, album order),
     * checked again before every attempt. Once it does, no set-aside database is copied over it.
     */
    private fun restoreRecovered() {
        val dirs = setAsideDir.listFiles()?.filter { File(it, ENCRYPTED_NAME).exists() }
            ?.sortedByDescending { it.name.toLongOrNull() ?: 0L } ?: return
        if (dirs.isEmpty()) return
        val live = keys.load() as? DatabaseKeyState.Ready ?: return
        for (dir in dirs) {
            val work = try {
                engine.liveWorkRows(encrypted, rawKeySpec(live.key), userWork)
            } catch (e: Exception) {
                log("db: live database not checked (${e.javaClass.simpleName}), set-aside copies kept")
                return
            }
            if (work > 0) {
                log("db: live database holds newer work, ${dirs.size} set-aside copies kept, not restored")
                return
            }
            val old = File(dir, ENCRYPTED_NAME)
            val recovered = File(dir, RECOVERED_DIR).isDirectory
            val oldKey = if (recovered) recoveredKey(File(dir, RECOVERED_DIR)) else runCatching { keys.keyIn(dir) }.getOrNull()
            if (oldKey == null) {
                if (recovered) log("db: recovered key missing, kept")
                continue
            }
            // A recovery copy names its account; a key the Keystore gave back names none, so the two databases must
            // hold the same accounts.
            val owner = if (!recovered) {
                null
            } else {
                runCatching { File(File(dir, RECOVERED_DIR), RECOVERED_OWNER).readText().trim() }.getOrNull()?.takeIf { it.isNotEmpty() }
            }
            try {
                when (val result = engine.restoreTables(
                    encrypted, rawKeySpec(live.key), old, rawKeySpec(oldKey), appTables, owner, hashOf, sameAccounts = !recovered,
                )) {
                    is RestoreResult.Restored -> {
                        dir.deleteRecursively()
                        log("db: restored ${result.tables} tables, ${result.rows} rows from the set-aside copy")
                        return
                    }
                    is RestoreResult.Skipped -> log("db: set-aside copy not restored (${result.reason}), kept")
                }
            } catch (e: Exception) {
                log("db: restore failed (${e.javaClass.simpleName}), both copies kept")
            }
        }
    }

    /**
     * Keeps every set-aside database until it is restored (the restore removes it). The only folders removed here
     * are empty ones and byte-for-byte copies of a newer set-aside database, which is kept.
     */
    private fun pruneSetAside() {
        val dirs = setAsideDir.listFiles()?.filter { it.isDirectory }?.sortedByDescending { it.name.toLongOrNull() ?: 0L } ?: return
        val kept = mutableListOf<File>()
        for (dir in dirs) {
            val db = File(dir, ENCRYPTED_NAME)
            if (!db.exists()) {
                if (dir.listFiles().isNullOrEmpty()) dir.delete()
                continue
            }
            if (kept.any { sameDatabase(File(it, ENCRYPTED_NAME), db) }) {
                dir.deleteRecursively()
                log("db: set-aside copy identical to a newer one removed")
                continue
            }
            kept += dir
        }
    }

    /** Whether two set-aside databases and their journals hold the same bytes. */
    private fun sameDatabase(a: File, b: File): Boolean {
        val pairs = databaseFiles(a).zip(databaseFiles(b))
        if (pairs.any { (x, y) -> x.exists() != y.exists() || (x.exists() && x.length() != y.length()) }) return false
        return pairs.all { (x, y) -> !x.exists() || runCatching { sameBytes(x, y) }.getOrDefault(false) }
    }

    private fun sameBytes(a: File, b: File): Boolean {
        a.inputStream().buffered().use { x ->
            b.inputStream().buffered().use { y ->
                val bx = ByteArray(COMPARE_BUFFER)
                val by = ByteArray(COMPARE_BUFFER)
                while (true) {
                    val n = readFully(x, bx)
                    val m = readFully(y, by)
                    if (n != m) return false
                    if (n == 0) return true
                    for (i in 0 until n) if (bx[i] != by[i]) return false
                }
            }
        }
    }

    private fun readFully(input: java.io.InputStream, buffer: ByteArray): Int {
        var total = 0
        while (total < buffer.size) {
            val n = input.read(buffer, total, buffer.size - total)
            if (n < 0) break
            total += n
        }
        return total
    }

    private fun attemptsLeft(): Boolean {
        val (version, count) = readPair(attemptsFile)
        return version != versionCode.toLong() || count < MAX_ATTEMPTS
    }

    private fun spendAttempt() {
        val (version, count) = readPair(attemptsFile)
        val next = if (version == versionCode.toLong()) count + 1 else 1
        writePair(attemptsFile, versionCode.toLong(), next)
    }

    /**
     * Three separate process starts over at least an hour of real time ([RealTime]); a wall-clock change does not
     * count, and further opens in the same process count once.
     */
    private fun unavailableLongEnough(): Boolean {
        val now = elapsed()
        val start = KeystoreStrikes.currentStart()
        val parts = runCatching { unavailableFile.readText().trim().split(' ') }.getOrNull()
            ?.takeIf { it.size == 4 }
        val span0 = parts?.get(0)?.toLongOrNull()
        val last = parts?.get(1)?.toLongOrNull()
        val count0 = parts?.get(2)?.toLongOrNull()
        val known = span0 != null && last != null && count0 != null
        if (known && parts!![3] == start) return count0!! >= UNAVAILABLE_LAUNCHES && span0!! >= UNAVAILABLE_SPAN_MS
        val count = (if (known) count0!! else 0L) + 1
        val span = if (known) RealTime.advance(span0!!, last!!, now) else 0L
        stateDir.mkdirs()
        runCatching { writeWhole(unavailableFile, "$span $now $count $start".toByteArray()) }
        return count >= UNAVAILABLE_LAUNCHES && span >= UNAVAILABLE_SPAN_MS
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
        private const val COMPARE_BUFFER = 64 * 1024
        private const val CORRUPT_MARKER = "corrupt"
        private const val ATTEMPTS_FILE = "copy_attempts"
        private const val UNAVAILABLE_FILE = "key_unavailable"
        private const val LOCK_FILE = "open.lock"
        const val MAX_ATTEMPTS = 3L
        private const val UNAVAILABLE_LAUNCHES = 3L
        private const val UNAVAILABLE_SPAN_MS = 60L * 60 * 1000
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
