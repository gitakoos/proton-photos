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

import android.database.sqlite.SQLiteDatabaseCorruptException
import android.database.sqlite.SQLiteException
import net.zetetic.database.DatabaseErrorHandler
import net.zetetic.database.sqlcipher.SQLiteDatabase
import net.zetetic.database.sqlcipher.SQLiteNotADatabaseException
import java.io.File

/** Loads SQLCipher's native library once; the library does not load it itself. */
internal object SqlCipherLibrary {
    @Volatile private var loaded = false

    val isLoaded: Boolean get() = loaded

    fun ensureLoaded() {
        if (loaded) return
        synchronized(this) {
            if (!loaded) {
                System.loadLibrary("sqlcipher")
                loaded = true
            }
        }
    }
}

/** Records a corruption report instead of deleting anything; the next open checks the file. */
internal class MarkingErrorHandler(private val marker: File) : DatabaseErrorHandler {
    override fun onCorruption(dbObj: SQLiteDatabase, exception: SQLiteException) {
        runCatching {
            marker.parentFile?.mkdirs()
            marker.createNewFile()
        }
    }
}

/**
 * The real [CipherEngine], on SQLCipher. With [cacheBytes], the copy and its check keep that much of the new file
 * decrypted in memory, so the index pages the copy builds and the integrity check walks are not written,
 * encrypted, read back and decrypted again many times over; null keeps SQLCipher's defaults.
 */
internal class SqlCipherEngine(
    private val corruptMarker: File,
    private val cacheBytes: Long? = null,
) : CipherEngine {

    private val handler get() = MarkingErrorHandler(corruptMarker)

    override fun probe(file: File, keySpec: String): ProbeResult {
        SqlCipherLibrary.ensureLoaded()
        return try {
            open(file, keySpec, SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.ENABLE_WRITE_AHEAD_LOGGING).close()
            ProbeResult.Ok
        } catch (e: SQLiteNotADatabaseException) {
            ProbeResult.Unreadable
        } catch (e: SQLiteDatabaseCorruptException) {
            ProbeResult.Unreadable
        } catch (e: Exception) {
            ProbeResult.Failed(e)
        }
    }

    override fun quickCheck(file: File, keySpec: String): Boolean {
        SqlCipherLibrary.ensureLoaded()
        return try {
            open(file, keySpec, SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.ENABLE_WRITE_AHEAD_LOGGING).use { db ->
                firstColumn(db, "PRAGMA quick_check") == listOf("ok")
            }
        } catch (e: SQLiteException) {
            false
        }
    }

    override fun export(plain: File, partial: File, keySpec: String): ExportReport {
        SqlCipherLibrary.ensureLoaded()
        val before: Snapshot
        // ATTACH opens its file with the main connection's flags, which do not include create: an empty file is
        // the new database SQLCipher writes into.
        partial.parentFile?.mkdirs()
        partial.createNewFile()
        SQLiteDatabase.openDatabase(plain.path, ByteArray(0), null, SQLiteDatabase.OPEN_READWRITE, handler, null).use { db ->
            before = snapshot(db)
            cacheBytes?.let { db.rawExecSQL("PRAGMA soft_heap_limit = ${it + SqlCipherBudget.HEADROOM_BYTES}") }
            db.rawExecSQL("ATTACH DATABASE ? AS encrypted KEY ?", partial.path, keySpec)
            try {
                if (cacheBytes != null) {
                    // The copy is a scratch file: it is checked, synced and renamed only once complete, and thrown
                    // away on any failure, so it needs no journal and no sync of its own while it is written.
                    db.rawExecSQL("PRAGMA encrypted.journal_mode = OFF")
                    db.rawExecSQL("PRAGMA encrypted.synchronous = OFF")
                    db.rawExecSQL("PRAGMA encrypted.cache_size = ${SqlCipherBudget.cacheSizePragma(cacheBytes)}")
                }
                db.rawExecSQL("SELECT sqlcipher_export('encrypted')")
                db.execSQL("PRAGMA encrypted.user_version = ${before.userVersion}")
            } finally {
                db.rawExecSQL("DETACH DATABASE encrypted")
            }
        }
        open(partial, keySpec, SQLiteDatabase.OPEN_READONLY).use { db ->
            cacheBytes?.let { db.rawExecSQL("PRAGMA cache_size = ${SqlCipherBudget.cacheSizePragma(it)}") }
            val integrity = firstColumn(db, "PRAGMA integrity_check")
            check(integrity == listOf("ok")) { "integrity check failed" }
            val after = snapshot(db)
            check(after.userVersion == before.userVersion) { "user version differs" }
            check(after.objects == before.objects) { "schema differs" }
            check(after.rowCounts == before.rowCounts) { "row counts differ" }
        }
        return ExportReport(tables = before.rowCounts.size, rows = before.rowCounts.values.sum())
    }

    override fun restoreTables(
        live: File,
        liveKeySpec: String,
        old: File,
        oldKeySpec: String,
        tables: List<String>,
        accountHash: String?,
        hashOf: (String) -> String,
        sameAccounts: Boolean,
    ): RestoreResult {
        SqlCipherLibrary.ensureLoaded()
        open(live, liveKeySpec, SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.ENABLE_WRITE_AHEAD_LOGGING).use { db ->
            db.rawExecSQL("ATTACH DATABASE ? AS old KEY ?", old.path, oldKeySpec)
            try {
                val oldVersion = firstColumn(db, "PRAGMA old.user_version").firstOrNull()?.toIntOrNull()
                if (oldVersion != db.version) return RestoreResult.Skipped("version $oldVersion, live ${db.version}")
                if (accountHash != null) {
                    val accounts = firstColumn(db, "SELECT userId FROM main.AccountEntity").map(hashOf)
                    if (accountHash !in accounts) return RestoreResult.Skipped("another account")
                }
                if (sameAccounts) {
                    val liveAccounts = firstColumn(db, "SELECT userId FROM main.AccountEntity").toSet()
                    val oldAccounts = firstColumn(db, "SELECT userId FROM old.AccountEntity").toSet()
                    if (liveAccounts != oldAccounts) return RestoreResult.Skipped("other accounts")
                }
                val oldTables = firstColumn(db, "SELECT name FROM old.sqlite_master WHERE type = 'table'").toSet()
                val liveTables = firstColumn(db, "SELECT name FROM main.sqlite_master WHERE type = 'table'").toSet()
                var rows = 0L
                var copied = 0
                db.rawExecSQL("PRAGMA foreign_keys = OFF")
                db.beginTransaction()
                try {
                    for (table in tables) {
                        if (table !in oldTables || table !in liveTables) continue
                        val quoted = quote(table)
                        val oldColumns = columnsOf(db, "old", table).toSet()
                        val columns = columnsOf(db, "main", table).filter { it in oldColumns }
                        if (columns.isEmpty()) continue
                        val list = columns.joinToString(",") { quote(it) }
                        db.execSQL("DELETE FROM main.$quoted")
                        db.execSQL("INSERT INTO main.$quoted ($list) SELECT $list FROM old.$quoted")
                        rows += db.rawQuery("SELECT count(*) FROM main.$quoted").use { c -> c.moveToFirst(); c.getLong(0) }
                        copied++
                    }
                    db.setTransactionSuccessful()
                } finally {
                    db.endTransaction()
                }
                return RestoreResult.Restored(copied, rows)
            } finally {
                runCatching { db.rawExecSQL("DETACH DATABASE old") }
            }
        }
    }

    private fun columnsOf(db: SQLiteDatabase, schema: String, table: String): List<String> =
        db.rawQuery("PRAGMA $schema.table_info(${quote(table)})").use { c ->
            val out = ArrayList<String>()
            val nameIndex = c.getColumnIndexOrThrow("name")
            while (c.moveToNext()) out += c.getString(nameIndex)
            out
        }

    private fun quote(name: String): String = "\"" + name.replace("\"", "\"\"") + "\""

    private fun open(file: File, keySpec: String, flags: Int): SQLiteDatabase =
        SQLiteDatabase.openDatabase(file.path, keySpec.toByteArray(Charsets.US_ASCII), null, flags, handler, null)

    private data class Snapshot(
        val userVersion: Int,
        val objects: Set<String>,
        val rowCounts: Map<String, Long>,
    )

    private fun snapshot(db: SQLiteDatabase): Snapshot {
        val objects = HashSet<String>()
        val tables = ArrayList<String>()
        db.rawQuery("SELECT type, name FROM sqlite_master WHERE name NOT LIKE 'sqlite_%' OR name = 'sqlite_sequence'").use { c ->
            while (c.moveToNext()) {
                val type = c.getString(0)
                val name = c.getString(1)
                objects += "$type:$name"
                if (type == "table") tables += name
            }
        }
        val counts = tables.associateWith { name ->
            db.rawQuery("SELECT count(*) FROM \"${name.replace("\"", "\"\"")}\"").use { c ->
                c.moveToFirst()
                c.getLong(0)
            }
        }
        return Snapshot(db.version, objects, counts)
    }

    private fun firstColumn(db: SQLiteDatabase, sql: String): List<String> =
        db.rawQuery(sql).use { c ->
            val out = ArrayList<String>()
            while (c.moveToNext()) out += c.getString(0)
            out
        }
}
