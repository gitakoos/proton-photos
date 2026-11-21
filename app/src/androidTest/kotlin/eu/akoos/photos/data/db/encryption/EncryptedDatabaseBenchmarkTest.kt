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

import android.database.Cursor
import android.os.StatFs
import android.util.Log
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import eu.akoos.photos.data.db.AppDatabase
import eu.akoos.photos.data.db.Migrations
import net.zetetic.database.sqlcipher.SQLiteConnection
import net.zetetic.database.sqlcipher.SQLiteDatabaseHook
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.Closeable
import java.io.File
import net.zetetic.database.sqlcipher.SQLiteDatabase as CipherDatabase
import android.database.sqlite.SQLiteDatabase as PlatformDatabase

/**
 * How the encrypted database compares with the plaintext one in time, for a run by hand on a device with
 * `-e pfpDbBench true`; skipped otherwise. Results go to logcat under `DbEncBench`.
 *
 * - [realLibrary]: the installed app's own encrypted database, read through a read-only attach into a plaintext
 *   scratch copy. Nothing is written to the app's database.
 * - [syntheticLibrary]: made-up rows of the app's schema, for a device or emulator without a library
 *   (`-e pfpDbBenchRows N`, default 60000).
 *
 * Each compares the copy (the first-start move) with SQLCipher's defaults and with [SqlCipherTuning], then runs
 * the app's own timeline and reconcile reads on: the platform SQLite on the plaintext copy (the app before
 * encryption), SQLCipher with its defaults (the build before [SqlCipherTuning]), SQLCipher tuned, and, to put a
 * number on two file formats the app does not use, tuned without the per-page HMAC and tuned with 16 KB pages.
 * Every figure is the best of two rounds.
 */
@RunWith(AndroidJUnit4::class)
class EncryptedDatabaseBenchmarkTest {

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val args = InstrumentationRegistry.getArguments()
    private lateinit var root: File

    @Before
    fun setUp() {
        assumeTrue("pass -e pfpDbBench true to run", args.getString("pfpDbBench") == "true")
        root = File(ctx.cacheDir, "dbbench-${System.nanoTime()}").apply { mkdirs() }
        SqlCipherLibrary.ensureLoaded()
    }

    @After
    fun tearDown() {
        if (::root.isInitialized) root.deleteRecursively()
    }

    @Test
    fun realLibrary() {
        val real = ctx.getDatabasePath(DatabaseOpenGate.ENCRYPTED_NAME)
        assumeTrue("no encrypted library on this device", real.exists())
        val state = DatabaseKeyStore(File(ctx.noBackupFilesDir, "database_key"), AndroidKeystoreWrapper()).load()
        assumeTrue("library key not readable: $state", state is DatabaseKeyState.Ready)
        val spec = rawKeySpec((state as DatabaseKeyState.Ready).key)
        val bytes = databaseBytes(real)
        val free = StatFs(root.path).availableBytes
        assumeTrue("needs ${mb(3 * bytes + 300 * MIB)} free, has ${mb(free)}", free > 3 * bytes + 300 * MIB)
        log("free space ${mb(free)}; the two format variants need about ${mb(2 * bytes)} more")

        val plain = File(root, "plain.db")
        val ms = timed {
            CipherDatabase.openDatabase(
                plain.path, ByteArray(0), null,
                CipherDatabase.CREATE_IF_NECESSARY or CipherDatabase.NO_LOCALIZED_COLLATORS, null, null,
            ).use { db ->
                db.rawExecSQL("ATTACH DATABASE ? AS src KEY ?", "file:${real.path}?mode=ro", spec)
                try {
                    db.rawExecSQL("SELECT sqlcipher_export('main', 'src')")
                } finally {
                    db.rawExecSQL("DETACH DATABASE src")
                }
            }
        }
        log("real library: ${mb(bytes)} encrypted, plaintext snapshot ${mb(plain.length())} in $ms ms")
        val userId = PlatformDb(plain).use {
            it.scalar("SELECT userId FROM photo_listing GROUP BY userId ORDER BY count(*) DESC LIMIT 1")
        }
        assumeTrue("no photo_listing rows", userId != null)
        compare(plain, spec, userId!!)
    }

    @Test
    fun syntheticLibrary() {
        val rows = args.getString("pfpDbBenchRows")?.toIntOrNull() ?: 60_000
        val plain = File(root, "plain.db")
        val ms = timed { fillSynthetic(plain, rows) }
        log("synthetic library: $rows rows, ${mb(databaseBytes(plain))} in $ms ms")
        compare(plain, rawKeySpec(ByteArray(32) { 3 }), SYNTHETIC_USER)
    }

    private fun compare(plain: File, spec: String, userId: String) {
        val tuning = SqlCipherTuning.forContext(ctx)
        log("tuning: cache ${mb(tuning.cacheBytes)}, soft heap limit ${mb(tuning.softHeapLimitBytes)}, pool ${tuning.poolSize}")

        val defaultsCopy = File(root, "enc_defaults.db")
        val tunedCopy = File(root, "enc_tuned.db")
        setSoftHeapLimit(DEFAULT_SOFT_HEAP_LIMIT)
        val defaultsMs = timed { SqlCipherEngine(File(root, "corrupt")).export(plain, defaultsCopy, spec) }
        deleteDatabase(defaultsCopy)
        val tunedMs = timed { SqlCipherEngine(File(root, "corrupt"), tuning.cacheBytes).export(plain, tunedCopy, spec) }
        log("copy with check (first start): defaults $defaultsMs ms, tuned $tunedMs ms")

        CipherDb(tunedCopy, spec, tuning.hook).use { db ->
            for (pragma in SETTINGS) log("setting $pragma = ${runCatching { db.scalar("PRAGMA $pragma") }.getOrElse { "error ${it.message}" }}")
            db.rows(
                "SELECT name, sum(pgsize), sum(pagetype = 'overflow'), count(*) FROM dbstat " +
                    "GROUP BY name ORDER BY sum(pgsize) DESC LIMIT 12",
            ) { c -> log("dbstat ${c.getString(0)}: ${mb(c.getLong(1))}, ${c.getLong(3)} pages, ${c.getLong(2)} overflow") }
        }

        val configs = mutableListOf(
            "plaintext" to { PlatformDb(plain) as BenchDb },
            "encrypted-defaults" to { CipherDb(tunedCopy, spec, DefaultsHook) as BenchDb },
            "encrypted-tuned" to { CipherDb(tunedCopy, spec, tuning.hook) as BenchDb },
        )
        for (variant in VARIANTS) {
            val free = StatFs(root.path).availableBytes
            if (free < databaseBytes(plain) * 12 / 10 + 200 * MIB) {
                log("variant ${variant.name} skipped: ${mb(free)} free")
                continue
            }
            val copy = File(root, "enc_${variant.name}.db")
            val ms = timed { exportVariant(plain, copy, spec, variant, tuning.cacheBytes) }
            log("variant ${variant.name}: copy without check $ms ms, ${mb(databaseBytes(copy))}")
            configs += "encrypted-tuned-${variant.name}" to { CipherDb(copy, spec, VariantHook(variant.open, tuning.hook)) as BenchDb }
        }
        val best = LinkedHashMap<String, LinkedHashMap<String, Long>>()
        repeat(2) {
            for ((name, open) in configs) {
                val result = best.getOrPut(name) { LinkedHashMap() }
                measure(open, userId).forEach { (metric, value) -> result[metric] = minOf(result[metric] ?: Long.MAX_VALUE, value) }
            }
        }
        val dbBytes = databaseBytes(tunedCopy)
        for ((name, result) in best) {
            val check = result["quick_check"] ?: 0L
            val rate = if (check > 0) "${dbBytes / MIB * 1000 / check} MB/s" else "-"
            log("result $name: " + result.entries.joinToString(", ") { "${it.key}=${it.value}" } + " ms, read rate $rate")
        }
    }

    /** One round of every read, in ms; "rows" is a row count. */
    private fun measure(open: () -> BenchDb, userId: String): Map<String, Long> {
        val out = LinkedHashMap<String, Long>()
        var db: BenchDb? = null
        out["open"] = timed { db = open().also { it.scalar("SELECT count(*) FROM sqlite_schema") } }
        db!!.close()
        val cold = ArrayList<Long>()
        repeat(3) { open().use { d -> cold += timed { out["rows"] = d.readAll(STREAM, userId).toLong() } } }
        out["timeline_cold"] = cold.min()
        open().use { d ->
            d.readAll(STREAM, userId)
            out["timeline_warm"] = (1..3).minOf { timed { d.readAll(STREAM, userId) } }
        }
        open().use { d -> out["pairing_cold"] = timed { d.readAll(PAIRING, userId) } }
        open().use { d -> out["sync_state_cold"] = timed { d.readAll(SYNC_STATE, null) } }
        open().use { d -> out["quick_check"] = timed { d.scalar("PRAGMA quick_check") } }
        return out
    }

    private interface BenchDb : Closeable {
        /** Reads every column of every row, inside a read transaction like the app's listing reads. */
        fun readAll(sql: String, arg: String?): Int

        fun scalar(sql: String): String?
    }

    private class PlatformDb(file: File) : BenchDb {
        private val db = PlatformDatabase.openDatabase(
            file.path, null, PlatformDatabase.OPEN_READWRITE or PlatformDatabase.ENABLE_WRITE_AHEAD_LOGGING,
        )

        override fun readAll(sql: String, arg: String?): Int {
            db.beginTransactionNonExclusive()
            try {
                return db.rawQuery(sql, arg?.let { arrayOf(it) }).use(::drain)
            } finally {
                db.endTransaction()
            }
        }

        override fun scalar(sql: String): String? = db.rawQuery(sql, null).use { if (it.moveToFirst()) it.getString(0) else null }

        override fun close() = db.close()
    }

    private class CipherDb(file: File, spec: String, hook: SQLiteDatabaseHook) : BenchDb {
        private val db = CipherDatabase.openDatabase(
            file.path, spec.toByteArray(Charsets.US_ASCII), null,
            CipherDatabase.OPEN_READWRITE or CipherDatabase.ENABLE_WRITE_AHEAD_LOGGING, null, hook,
        )

        override fun readAll(sql: String, arg: String?): Int {
            db.beginTransactionNonExclusive()
            try {
                return db.rawQuery(sql, arg?.let { arrayOf(it) } ?: emptyArray<String>()).use(::drain)
            } finally {
                db.endTransaction()
            }
        }

        override fun scalar(sql: String): String? = db.rawQuery(sql).use { if (it.moveToFirst()) it.getString(0) else null }

        fun rows(sql: String, each: (Cursor) -> Unit) = db.rawQuery(sql).use { while (it.moveToNext()) each(it) }

        override fun close() = db.close()
    }

    /** SQLCipher as the build before [SqlCipherTuning] ran it: its 8 MB process-wide cache limit, default cache. */
    private object DefaultsHook : SQLiteDatabaseHook {
        override fun preKey(connection: SQLiteConnection) = Unit

        override fun postKey(connection: SQLiteConnection) {
            connection.executeForLong("PRAGMA soft_heap_limit = $DEFAULT_SOFT_HEAP_LIMIT", null, null)
        }
    }

    /**
     * A file format the app does not write, only to put a number on it: [Variant.defaultOn] makes SQLCipher key
     * the attached copy with it, [Variant.open] opens the copy again.
     */
    private class Variant(val name: String, val defaultOn: String, val defaultOff: String, val open: String)

    private fun exportVariant(plain: File, copy: File, spec: String, variant: Variant, cacheBytes: Long) {
        copy.createNewFile()
        CipherDatabase.openDatabase(plain.path, ByteArray(0), null, CipherDatabase.OPEN_READWRITE, null, null).use { db ->
            db.rawExecSQL(variant.defaultOn)
            try {
                db.rawExecSQL("ATTACH DATABASE ? AS encrypted KEY ?", copy.path, spec)
            } finally {
                db.rawExecSQL(variant.defaultOff)
            }
            try {
                db.rawExecSQL("PRAGMA encrypted.journal_mode = OFF")
                db.rawExecSQL("PRAGMA encrypted.synchronous = OFF")
                db.rawExecSQL("PRAGMA encrypted.cache_size = ${SqlCipherBudget.cacheSizePragma(cacheBytes)}")
                db.rawExecSQL("SELECT sqlcipher_export('encrypted')")
            } finally {
                db.rawExecSQL("DETACH DATABASE encrypted")
            }
        }
    }

    /** Sets a variant's format right after the key, before the first read, then the app's own settings. */
    private class VariantHook(private val format: String, private val then: SQLiteDatabaseHook) : SQLiteDatabaseHook {
        override fun preKey(connection: SQLiteConnection) = Unit

        override fun postKey(connection: SQLiteConnection) {
            connection.execute(format, null, null)
            then.postKey(connection)
        }
    }

    private fun setSoftHeapLimit(bytes: Long) {
        CipherDatabase.openDatabase(
            File(root, "limit.db").path, ByteArray(0), null, CipherDatabase.CREATE_IF_NECESSARY, null, null,
        ).use { db -> db.rawQuery("PRAGMA soft_heap_limit = $bytes").use { it.moveToFirst() } }
    }

    /** Rows shaped like the app's: 88-character ids, armored key material, a sync row per photo. */
    private fun fillSynthetic(file: File, rows: Int) {
        val db = Room.databaseBuilder(ctx, AppDatabase::class.java, file.path).addMigrations(*Migrations.ALL).build()
        val sql = db.openHelper.writableDatabase
        fun text(seed: Int, length: Int) = buildString(length) {
            var x = seed * 2654435761L
            repeat(length) {
                x = x * 6364136223846793005L + 1442695040888963407L
                append(ALPHABET[((x ushr 33) % ALPHABET.length).toInt()])
            }
        }
        sql.beginTransaction()
        try {
            val random = java.util.Random(7)
            for (i in 0 until rows) {
                // Mostly newest first, as the listing arrives, with some rows moved by later refreshes.
                val time = if (random.nextInt(10) == 0) random.nextInt(rows).toLong() else (rows - i).toLong()
                sql.execSQL(
                    "INSERT INTO photo_listing (linkId, shareId, volumeId, userId, captureTime, displayName, mimeType, " +
                        "sizeBytes, revisionId, contentHash, tagsCsv, serverThumbnailUrl, contentKeyPacket, encNodeKey, " +
                        "encNodePassphrase, parentLinkId, isChildOfAlbum, encXAttr, gpsChecked, durationMs) " +
                        "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    arrayOf<Any?>(
                        text(i, 88), SHARE, VOLUME, SYNTHETIC_USER, 1_600_000_000_000L + time * 60_000L, "IMG_$i.jpg",
                        if (i % 9 == 0) "video/mp4" else "image/jpeg", 3_000_000L + i, text(i + rows, 88), text(i, 64),
                        "", "https://example.invalid/${text(i, 60)}", text(i, 200), text(i, 600), text(i, 300), ROOT,
                        if (i % 50 == 0) 1 else 0, text(i, 300), 1, if (i % 9 == 0) 12_000L else null,
                    ),
                )
                sql.execSQL(
                    "INSERT INTO sync_state (localUri, userId, cloudFileId, localHash, cloudHash, status, " +
                        "lastSyncAttemptMs, sizeBytes, queued) VALUES (?,?,?,?,?,?,?,?,0)",
                    arrayOf<Any?>(
                        "content://media/external/images/media/$i", SYNTHETIC_USER, text(i, 88), text(i, 64), text(i, 64),
                        "SYNCED", 1_700_000_000_000L + i, 3_000_000L + i,
                    ),
                )
            }
            sql.setTransactionSuccessful()
        } finally {
            sql.endTransaction()
        }
        db.close()
    }

    private fun log(line: String) {
        Log.i(TAG, line)
    }

    private companion object {
        const val TAG = "DbEncBench"
        const val MIB = 1024L * 1024
        const val DEFAULT_SOFT_HEAP_LIMIT = 8L * MIB
        const val SYNTHETIC_USER = "bench-user"
        const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
        val SHARE = "S".repeat(88)
        val VOLUME = "V".repeat(88)
        val ROOT = "R".repeat(88)

        /** The app's own reads, character for character: PhotoListingDao and SyncStateDao. */
        const val STREAM = "SELECT linkId, shareId, volumeId, captureTime, displayName, mimeType, sizeBytes, revisionId, " +
            "contentHash, tagsCsv, durationMs FROM photo_listing WHERE userId = ? AND " +
            "isChildOfAlbum = 0 ORDER BY captureTime DESC"
        const val PAIRING = "SELECT linkId, contentHash, captureTime, displayName, sizeBytes FROM photo_listing " +
            "WHERE userId = ? AND isChildOfAlbum = 0 ORDER BY captureTime DESC"
        const val SYNC_STATE = "SELECT * FROM sync_state"

        val VARIANTS = listOf(
            Variant(
                "nohmac", "PRAGMA cipher_default_use_hmac = OFF", "PRAGMA cipher_default_use_hmac = ON",
                "PRAGMA cipher_use_hmac = OFF",
            ),
            Variant(
                "page16k", "PRAGMA cipher_default_page_size = 16384", "PRAGMA cipher_default_page_size = 4096",
                "PRAGMA cipher_page_size = 16384",
            ),
        )

        val SETTINGS = listOf(
            "cipher_version", "cipher_provider", "cipher_provider_version", "cipher_memory_security",
            "cipher_page_size", "cipher_hmac_algorithm", "cipher_kdf_algorithm", "page_size", "auto_vacuum",
            "journal_mode", "cache_size", "soft_heap_limit",
        )

        fun mb(bytes: Long) = "${bytes / MIB} MB"

        inline fun timed(block: () -> Unit): Long {
            val start = System.nanoTime()
            block()
            return (System.nanoTime() - start) / 1_000_000
        }

        fun drain(c: Cursor): Int {
            var rows = 0
            val columns = c.columnCount
            while (c.moveToNext()) {
                for (i in 0 until columns) {
                    when (c.getType(i)) {
                        Cursor.FIELD_TYPE_INTEGER -> c.getLong(i)
                        Cursor.FIELD_TYPE_FLOAT -> c.getDouble(i)
                        Cursor.FIELD_TYPE_STRING -> c.getString(i)
                        Cursor.FIELD_TYPE_BLOB -> c.getBlob(i)
                    }
                }
                rows++
            }
            return rows
        }
    }
}
