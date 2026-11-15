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

import android.util.Log
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import eu.akoos.photos.data.db.AppDatabase
import eu.akoos.photos.data.db.Migrations
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.KeyStore

/**
 * The move to the encrypted database on the real SQLCipher build, on a device: the app's own schema with
 * thousands of rows, an interrupted copy, a lost key, and how long scans take before and after. Every file
 * lives in a scratch folder; the installed app's own database is never touched.
 */
@RunWith(AndroidJUnit4::class)
class EncryptedDatabaseDeviceTest {

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var root: File
    private lateinit var databases: File
    private lateinit var state: File
    private lateinit var aside: File
    private val alias = "pfp_database_key_test"

    @Before
    fun setUp() {
        root = File(ctx.cacheDir, "dbenc-test-${System.nanoTime()}")
        databases = File(root, "databases").apply { mkdirs() }
        state = File(root, "state")
        aside = File(root, "aside")
        deleteAlias()
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
        deleteAlias()
    }

    private fun deleteAlias() {
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.let { if (it.containsAlias(alias)) it.deleteEntry(alias) }
    }

    private fun gate() = DatabaseOpenGate(
        databasesDir = databases,
        stateDir = state,
        setAsideDir = aside,
        keys = DatabaseKeyStore(state, AndroidKeystoreWrapper(alias)),
        engine = SqlCipherEngine(File(state, "corrupt")),
        versionCode = 1,
        freeBytes = { null },
        clock = System::currentTimeMillis,
        log = { Log.i(TAG, it) },
    )

    private val plainFile get() = File(databases, DatabaseOpenGate.PLAIN_NAME)
    private val encryptedFile get() = File(databases, DatabaseOpenGate.ENCRYPTED_NAME)

    /** The database as the current release writes it: plaintext, the platform SQLite, the app's schema. */
    private fun writePlaintextDatabase(syncRows: Int, tagRows: Int, embeddingRows: Int) {
        val db = Room.databaseBuilder(ctx, AppDatabase::class.java, plainFile.path)
            .addMigrations(*Migrations.ALL)
            .build()
        val sql = db.openHelper.writableDatabase
        sql.beginTransaction()
        try {
            for (i in 0 until syncRows) {
                sql.execSQL(
                    "INSERT INTO sync_state (localUri, userId, cloudFileId, localHash, cloudHash, status, " +
                        "lastSyncAttemptMs, lastSyncSuccessMs, backedUpAtMs, sizeBytes, queued) VALUES (?,?,?,?,?,?,?,?,?,?,0)",
                    arrayOf<Any?>(
                        "content://media/external/images/media/$i", "user", "link$i", "hash$i", "chash$i",
                        if (i % 7 == 0) "HIDDEN" else "SYNCED", 1_700_000_000_000L + i, null, null, 2_000_000L + i,
                    ),
                )
            }
            for (i in 0 until tagRows) {
                sql.execSQL(
                    "INSERT INTO local_tag (uri, dateModified, sizeBytes, tagsCsv, scannedAt, userTagsCsv) VALUES (?,?,?,?,?,?)",
                    arrayOf<Any?>("content://media/external/images/media/$i", i.toLong(), 1L, "beach,sea", 1L, "holiday"),
                )
            }
            val vector = ByteArray(2048) { (it % 251).toByte() }
            for (i in 0 until embeddingRows) {
                sql.execSQL(
                    "INSERT INTO image_embedding (userId, photoKey, embedding, modelVersion, indexedAt) VALUES (?,?,?,?,?)",
                    arrayOf<Any?>("user", "photo$i", vector, 1, i.toLong()),
                )
            }
            sql.setTransactionSuccessful()
        } finally {
            sql.endTransaction()
        }
        db.close()
    }

    private fun openThroughGate(gate: DatabaseOpenGate): AppDatabase =
        Room.databaseBuilder(ctx, AppDatabase::class.java, DatabaseOpenGate.PLAIN_NAME)
            .openHelperFactory(SupportSQLiteOpenHelper.Factory { DeferredOpenHelper(it) { gate } })
            .addMigrations(*Migrations.ALL)
            .build()

    private fun count(db: AppDatabase, table: String): Long =
        db.openHelper.writableDatabase.query("SELECT count(*) FROM $table").use { it.moveToFirst(); it.getLong(0) }

    private fun header(file: File): String = file.inputStream().use { input ->
        val bytes = ByteArray(15)
        var read = 0
        while (read < bytes.size) {
            val n = input.read(bytes, read, bytes.size - read)
            if (n < 0) break
            read += n
        }
        String(bytes, 0, read, Charsets.US_ASCII)
    }

    @Test
    fun aPlaintextDatabaseWithTwelveThousandRowsMovesToAnEncryptedFileRoomOpens() {
        writePlaintextDatabase(syncRows = 12_000, tagRows = 12_000, embeddingRows = 3_000)
        assertEquals("SQLite format 3", header(plainFile))

        val db = openThroughGate(gate())
        assertEquals(12_000L, count(db, "sync_state"))
        assertEquals(12_000L, count(db, "local_tag"))
        assertEquals(3_000L, count(db, "image_embedding"))
        assertEquals(12_000L / 7 + 1, db.openHelper.writableDatabase.query("SELECT count(*) FROM sync_state WHERE status = 'HIDDEN'").use { it.moveToFirst(); it.getLong(0) })
        db.close()

        assertFalse(plainFile.exists())
        assertTrue(encryptedFile.exists())
        assertNotEquals("SQLite format 3", header(encryptedFile))

        val again = openThroughGate(gate())
        assertEquals(12_000L, count(again, "sync_state"))
        again.openHelper.writableDatabase.execSQL("DELETE FROM local_tag WHERE uri LIKE '%/1'")
        assertEquals(11_999L, count(again, "local_tag"))
        again.close()
    }

    @Test
    fun aCopyInterruptedBeforeTheRenameIsRedoneWithNothingLost() {
        writePlaintextDatabase(syncRows = 10_500, tagRows = 0, embeddingRows = 0)
        File(databases, DatabaseOpenGate.PARTIAL_NAME).writeBytes(ByteArray(8192) { 7 })

        val db = openThroughGate(gate())
        assertEquals(10_500L, count(db, "sync_state"))
        db.close()
        assertFalse(File(databases, DatabaseOpenGate.PARTIAL_NAME).exists())
    }

    @Test
    fun aCopyInterruptedAfterTheRenameOpensTheEncryptedFile() {
        writePlaintextDatabase(syncRows = 10_500, tagRows = 0, embeddingRows = 0)
        gate().prepare()
        assertTrue(plainFile.exists() && encryptedFile.exists())

        val db = openThroughGate(gate())
        assertEquals(10_500L, count(db, "sync_state"))
        db.close()
        assertFalse(plainFile.exists())
    }

    @Test
    fun aLostKeySetsTheDatabaseAsideAndStartsEmptyWhileVaultFilesStay() {
        writePlaintextDatabase(syncRows = 10_500, tagRows = 0, embeddingRows = 0)
        openThroughGate(gate()).close()
        val vaultFile = File(root, "files/hidden/a.jpg").apply { parentFile!!.mkdirs(); writeText("photo") }
        deleteAlias()

        val db = openThroughGate(gate())
        assertEquals(0L, count(db, "sync_state"))
        db.close()

        val kept = aside.listFiles()!!.single()
        assertTrue(File(kept, DatabaseOpenGate.ENCRYPTED_NAME).length() > 0)
        assertTrue(File(state, DatabaseOpenGate.REBUILT_MARKER).exists())
        assertEquals("photo", vaultFile.readText())
    }

    @Test
    fun aFileEncryptedWithAnotherKeyIsReportedUnreadableNotDeleted() {
        writePlaintextDatabase(syncRows = 100, tagRows = 0, embeddingRows = 0)
        openThroughGate(gate()).close()
        val engine = SqlCipherEngine(File(state, "corrupt"))

        assertEquals(ProbeResult.Unreadable, engine.probe(encryptedFile, rawKeySpec(ByteArray(32) { 1 })))
        assertTrue(encryptedFile.exists())
    }

    @Test
    fun scanTimingPlaintextAgainstEncrypted() {
        val rows = 130_000
        val plainMs = timeLibrary(File(databases, "perf_plain.db"), null, rows)
        val encMs = timeLibrary(File(databases, "perf_enc.db"), rawKeySpec(ByteArray(32) { 3 }), rows)
        Log.i(TAG, "perf rows=$rows plain insert=${plainMs[0]} scan=${plainMs[1]} | encrypted insert=${encMs[0]} scan=${encMs[1]} ms")

        val source = File(databases, "perf_plain.db")
        val started = System.nanoTime()
        SqlCipherEngine(File(state, "corrupt")).export(source, File(databases, "perf_copy.db"), rawKeySpec(ByteArray(32) { 4 }))
        Log.i(TAG, "perf copy of ${source.length() / (1024 * 1024)} MB took ${(System.nanoTime() - started) / 1_000_000} ms")
    }

    /** Insert time and the best of three scans of the timeline's display columns, ms. */
    private fun timeLibrary(file: File, keySpec: String?, rows: Int): LongArray {
        val insertStart = System.nanoTime()
        withDatabase(file, keySpec) { exec, tx -> fill(exec, tx, rows) }
        val insertMs = (System.nanoTime() - insertStart) / 1_000_000
        var best = Long.MAX_VALUE
        repeat(3) {
            val start = System.nanoTime()
            withDatabase(file, keySpec, scan = true) { _, _ -> }
            best = minOf(best, (System.nanoTime() - start) / 1_000_000)
        }
        return longArrayOf(insertMs, best)
    }

    /** Opens [file] plaintext on the platform SQLite or encrypted on SQLCipher, in WAL like the app. */
    private fun withDatabase(
        file: File,
        keySpec: String?,
        scan: Boolean = false,
        block: (exec: (String, Array<Any?>) -> Unit, tx: (() -> Unit) -> Unit) -> Unit,
    ) {
        if (keySpec == null) {
            val flags = android.database.sqlite.SQLiteDatabase.CREATE_IF_NECESSARY or
                android.database.sqlite.SQLiteDatabase.ENABLE_WRITE_AHEAD_LOGGING
            android.database.sqlite.SQLiteDatabase.openDatabase(file.path, null, flags).use { db ->
                if (scan) db.rawQuery(SCAN, null).use { c -> while (c.moveToNext()) c.getString(0) }
                block({ sql, args -> db.execSQL(sql, args) }) { body ->
                    db.beginTransaction()
                    try { body(); db.setTransactionSuccessful() } finally { db.endTransaction() }
                }
            }
        } else {
            SqlCipherLibrary.ensureLoaded()
            val flags = net.zetetic.database.sqlcipher.SQLiteDatabase.CREATE_IF_NECESSARY or
                net.zetetic.database.sqlcipher.SQLiteDatabase.ENABLE_WRITE_AHEAD_LOGGING
            net.zetetic.database.sqlcipher.SQLiteDatabase.openDatabase(
                file.path, keySpec.toByteArray(Charsets.US_ASCII), null, flags, null, null,
            ).use { db ->
                if (scan) db.rawQuery(SCAN).use { c -> while (c.moveToNext()) c.getString(0) }
                block({ sql, args -> db.execSQL(sql, args) }) { body ->
                    db.beginTransaction()
                    try { body(); db.setTransactionSuccessful() } finally { db.endTransaction() }
                }
            }
        }
    }

    private fun fill(exec: (String, Array<Any?>) -> Unit, tx: (() -> Unit) -> Unit, rows: Int) {
        exec(
            "CREATE TABLE photo (linkId TEXT PRIMARY KEY, captureTime INTEGER, displayName TEXT, mimeType TEXT, " +
                "sizeBytes INTEGER, contentHash TEXT, crypto TEXT)",
            emptyArray(),
        )
        exec("CREATE INDEX photo_time ON photo(captureTime)", emptyArray())
        val crypto = "x".repeat(400)
        tx {
            for (i in 0 until rows) {
                exec(
                    "INSERT INTO photo VALUES (?,?,?,?,?,?,?)",
                    arrayOf<Any?>("link$i", 1_600_000_000_000L + i * 1000L, "IMG_$i.jpg", "image/jpeg", 3_000_000L + i, "hash$i", crypto),
                )
            }
        }
    }

    private companion object {
        const val TAG = "DbEncTest"
        const val SCAN = "SELECT linkId, captureTime, displayName, mimeType, sizeBytes, contentHash FROM photo ORDER BY captureTime DESC"
    }
}
