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

import android.database.sqlite.SQLiteDatabase
import android.util.Log
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import eu.akoos.photos.data.db.AppDatabase
import eu.akoos.photos.data.db.Migrations
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.file.Files
import java.security.KeyStore

/**
 * The move to the encrypted database from the database versions earlier releases left on phones: 41 (2.5.0),
 * 46 (2.6.0 previews 1 to 5), 48 (preview 6) and 54 (preview 7). Each database is built from that version's
 * exported schema, as the app left it when it was stopped for the update: part of the rows still in the
 * write-ahead log. It then goes through the same gate, copy and open the app uses, Room migrates the encrypted
 * file to the current version, and every seeded row has to be there. Every file lives in a scratch folder; the
 * installed app's own database is never touched.
 */
@RunWith(AndroidJUnit4::class)
class EncryptedDatabaseUpgradeDeviceTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val ctx = instrumentation.targetContext
    private lateinit var root: File
    private lateinit var databases: File
    private lateinit var state: File
    private lateinit var aside: File
    private val alias = "pfp_database_key_upgrade_test"
    private val gateLog = ArrayList<String>()

    @Before
    fun setUp() {
        root = File(ctx.cacheDir, "dbenc-upgrade-test-${System.nanoTime()}")
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

    private val plainFile get() = File(databases, DatabaseOpenGate.PLAIN_NAME)
    private val encryptedFile get() = File(databases, DatabaseOpenGate.ENCRYPTED_NAME)
    private val partialFile get() = File(databases, DatabaseOpenGate.PARTIAL_NAME)
    private val latestVersion = Migrations.ALL.last().endVersion

    /** The gate as the app builds it, with the production engine and copy settings, over the scratch folder. */
    private fun gate() = DatabaseOpenGate(
        databasesDir = databases,
        stateDir = state,
        setAsideDir = aside,
        keys = DatabaseKeyStore(state, AndroidKeystoreWrapper(alias)),
        engine = SqlCipherEngine(File(state, "corrupt"), SqlCipherTuning.forContext(ctx).cacheBytes),
        versionCode = 1,
        freeBytes = { null },
        clock = System::currentTimeMillis,
        log = { line ->
            Log.i(TAG, line)
            synchronized(gateLog) { gateLog += line }
        },
    )

    /** The database the app builds in `DatabaseModule`, with the scratch gate in place of the app's own. */
    private fun openThroughGate(gate: DatabaseOpenGate): AppDatabase =
        Room.databaseBuilder(ctx, AppDatabase::class.java, DatabaseOpenGate.PLAIN_NAME)
            .openHelperFactory(SupportSQLiteOpenHelper.Factory { DeferredOpenHelper(it) { gate } })
            .addMigrations(*Migrations.ALL)
            .build()

    // ---- the tests ----

    @Test
    fun theStable250DatabaseAtVersion41MovesAndMigrates() = movesAndMigrates(41)

    @Test
    fun thePreviewDatabaseAtVersion46MovesAndMigrates() = movesAndMigrates(46)

    @Test
    fun thePreviewDatabaseAtVersion48MovesAndMigrates() = movesAndMigrates(48)

    @Test
    fun thePreview7DatabaseAtVersion54MovesAndMigrates() = movesAndMigrates(54)

    @Test
    fun aCopyCutShortAtVersion41IsRedoneOnTheNextOpen() = copyCutShort(41)

    @Test
    fun aCopyCutShortAtVersion54IsRedoneOnTheNextOpen() = copyCutShort(54)

    @Test
    fun aCopyStoppedAfterTheRenameAtVersion41MigratesTheEncryptedFile() = copyStoppedAfterRename(41)

    @Test
    fun aCopyStoppedAfterTheRenameAtVersion54MigratesTheEncryptedFile() = copyStoppedAfterRename(54)

    private fun movesAndMigrates(version: Int) {
        val seeded = writeOldDatabase(version)
        assertEquals("SQLite format 3", header(plainFile))
        assertTrue("part of the rows waits in the write-ahead log", File(plainFile.path + "-wal").length() > 0)

        openAndCheck(seeded, version)
        assertTrue(gateLog.toString(), gateLog.any { it.startsWith("db: encrypted ") })
        assertMovedAndEncrypted()

        reopenAndWrite(seeded, version)
    }

    private fun copyCutShort(version: Int) {
        val seeded = writeOldDatabase(version)
        // A full copy of the same data cut to half its length, with a journal beside it, is what a copy stopped
        // part way leaves. It is made from a duplicate so the plaintext files keep their write-ahead log.
        val duplicate = File(root, "duplicate").apply { mkdirs() }
        for (file in databaseFiles(plainFile)) {
            if (file.exists()) Files.copy(file.toPath(), File(duplicate, file.name).toPath())
        }
        val full = File(root, "full_copy.db")
        SqlCipherEngine(File(state, "corrupt")).export(File(duplicate, plainFile.name), full, rawKeySpec(ByteArray(32) { 3 }))
        val bytes = full.readBytes()
        partialFile.writeBytes(bytes.copyOf(bytes.size / 2))
        File(partialFile.path + "-journal").writeBytes(ByteArray(4096) { 1 })
        // The stopped copy already spent its attempt for this version.
        state.mkdirs()
        File(state, "copy_attempts").writeText("1 1")

        openAndCheck(seeded, version)
        assertTrue(gateLog.toString(), gateLog.contains("db: unfinished copy removed"))
        assertTrue(gateLog.toString(), gateLog.any { it.startsWith("db: encrypted ") })
        assertMovedAndEncrypted()

        reopenAndWrite(seeded, version)
    }

    private fun copyStoppedAfterRename(version: Int) {
        val seeded = writeOldDatabase(version)
        gate().prepare()
        assertTrue(plainFile.exists() && encryptedFile.exists())
        assertNotEquals("SQLite format 3", header(encryptedFile))

        openAndCheck(seeded, version)
        assertMovedAndEncrypted()

        reopenAndWrite(seeded, version)
    }

    private fun openAndCheck(seeded: Seeded, version: Int) {
        val db = openThroughGate(gate())
        val sql = db.openHelper.writableDatabase
        assertEquals("migrated from $version", latestVersion, sql.version)
        assertEquals(listOf("ok"), column(sql, "PRAGMA integrity_check"))
        assertEquals(emptyList<String>(), column(sql, "PRAGMA foreign_key_check"))
        assertSeeded(sql, seeded)
        db.close()
    }

    private fun reopenAndWrite(seeded: Seeded, version: Int) {
        val again = openThroughGate(gate())
        val sql = again.openHelper.writableDatabase
        assertEquals(latestVersion, sql.version)
        assertSeeded(sql, seeded)
        sql.execSQL(
            "INSERT INTO photo_label (userId, photoKey, label, labelKey, updatedAt) VALUES (?,?,?,?,?)",
            arrayOf<Any?>(USER, "link-p3", "Added later", "added later", T0 + 99),
        )
        assertEquals(seeded.count("photo_label") + 1, count(sql, "photo_label"))
        again.close()
        assertMovedAndEncrypted()
        assertTrue("set aside: ${aside.list()?.toList()}", aside.list().isNullOrEmpty())
        assertFalse("from $version", File(state, DatabaseOpenGate.REBUILT_MARKER).exists())
    }

    private fun assertMovedAndEncrypted() {
        assertTrue(encryptedFile.exists())
        assertNotEquals("SQLite format 3", header(encryptedFile))
        for (file in databaseFiles(plainFile) + databaseFiles(partialFile)) assertFalse("${file.name} left", file.exists())
        val plaintext = databases.listFiles()!!.filter { it.isFile && it.length() >= 15 && header(it) == "SQLite format 3" }
        assertEquals(emptyList<File>(), plaintext)
    }

    // ---- the old database ----

    private class Field(val name: String, val affinity: String, val notNull: Boolean)

    private class Table(val name: String, val createSql: List<String>, val fields: List<Field>)

    private class Schema(val version: Int, val tables: Map<String, Table>, val setupQueries: List<String>)

    private fun schema(version: Int): Schema {
        val text = instrumentation.context.assets.open("eu.akoos.photos.data.db.AppDatabase/$version.json")
            .bufferedReader().use { it.readText() }
        val database = JSONObject(text).getJSONObject("database")
        assertEquals(version, database.getInt("version"))
        val tables = LinkedHashMap<String, Table>()
        val entities = database.getJSONArray("entities")
        for (i in 0 until entities.length()) {
            val entity = entities.getJSONObject(i)
            val name = entity.getString("tableName")
            val sql = mutableListOf(entity.getString("createSql").replace("\${TABLE_NAME}", name))
            entity.optJSONArray("indices")?.let { indices ->
                for (j in 0 until indices.length()) sql += indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", name)
            }
            val fields = entity.getJSONArray("fields").let { array ->
                (0 until array.length()).map { j ->
                    array.getJSONObject(j).let { Field(it.getString("columnName"), it.getString("affinity"), it.optBoolean("notNull")) }
                }
            }
            tables[name] = Table(name, sql, fields)
        }
        database.optJSONArray("views")?.let { views ->
            for (i in 0 until views.length()) {
                val view = views.getJSONObject(i)
                val name = view.getString("viewName")
                tables["view:$name"] = Table(name, listOf(view.getString("createSql").replace("\${VIEW_NAME}", name)), emptyList())
            }
        }
        val setup = database.getJSONArray("setupQueries").let { array -> (0 until array.length()).map { array.getString(it) } }
        return Schema(version, tables, setup)
    }

    /** What went into the old database, by table, and the rows a migration derives from them. */
    private class Seeded(val rows: Map<String, List<Map<String, Any?>>>, val derived: Map<String, List<Map<String, Any?>>>) {
        fun count(table: String): Long = (rows[table]?.size ?: 0).toLong() + (derived[table]?.size ?: 0).toLong()
    }

    /**
     * Writes the database the release at [version] left behind: its schema, Room's identity row, the seeded rows,
     * and its version, in write-ahead log mode. The first half of the tables reaches the database file; the rest
     * stays in the log, copied out while the connection is still open, the way a process stopped for an update
     * leaves it.
     */
    private fun writeOldDatabase(version: Int): Seeded {
        val schema = schema(version)
        val source = File(root, "source/${DatabaseOpenGate.PLAIN_NAME}").apply { parentFile!!.mkdirs() }
        val rows = seedRows().filterKeys { it in schema.tables }.mapValues { (table, list) ->
            val fields = schema.tables.getValue(table).fields
            list.map { row -> completeRow(row, fields) }
        }
        val db = SQLiteDatabase.openDatabase(
            source.path, null,
            SQLiteDatabase.CREATE_IF_NECESSARY or SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.ENABLE_WRITE_AHEAD_LOGGING,
        )
        try {
            assertEquals("wal", db.rawQuery("PRAGMA journal_mode", null).use { it.moveToFirst(); it.getString(0) })
            db.rawQuery("PRAGMA wal_autocheckpoint = 0", null).use { it.moveToFirst() }
            db.beginTransaction()
            try {
                for (table in schema.tables.values) table.createSql.forEach { db.execSQL(it) }
                schema.setupQueries.forEach { db.execSQL(it) }
                db.version = version
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
            val tables = rows.keys.toList()
            val (inFile, inLog) = tables.chunked((tables.size + 1) / 2).let { it[0] to it.getOrElse(1) { emptyList() } }
            insertAll(db, inFile, rows)
            db.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { it.moveToFirst() }
            insertAll(db, inLog, rows)
            Files.copy(source.toPath(), plainFile.toPath())
            Files.copy(File(source.path + "-wal").toPath(), File(plainFile.path + "-wal").toPath())
        } finally {
            db.close()
        }
        File(root, "source").deleteRecursively()
        val derived = HashMap<String, List<Map<String, Any?>>>()
        if (version < 49) {
            // From 48 to 49 each photo's label change time starts at its newest label row.
            derived["photo_label_state"] = rows["photo_label"].orEmpty()
                .groupBy { it["userId"] to it["photoKey"] }
                .map { (key, labels) -> mapOf("userId" to key.first, "photoKey" to key.second, "changedAt" to labels.maxOf { it["updatedAt"] as Long }) }
        }
        return Seeded(rows, derived)
    }

    private fun insertAll(db: SQLiteDatabase, tables: List<String>, rows: Map<String, List<Map<String, Any?>>>) {
        db.beginTransaction()
        try {
            for (table in tables) {
                for (row in rows.getValue(table)) {
                    val columns = row.keys.toList()
                    db.execSQL(
                        "INSERT INTO `$table` (${columns.joinToString { "`$it`" }}) VALUES (${columns.joinToString { "?" }})",
                        columns.map { row[it] }.toTypedArray(),
                    )
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** Keeps the columns [fields] has and fills every required one the row leaves out. */
    private fun completeRow(row: Map<String, Any?>, fields: List<Field>): Map<String, Any?> {
        val known = fields.map { it.name }.toSet()
        val out = LinkedHashMap(row.filterKeys { it in known })
        for (field in fields) {
            if (field.notNull && field.name !in out) {
                out[field.name] = when (field.affinity) {
                    "INTEGER" -> 0L
                    "REAL" -> 0.0
                    "BLOB" -> byteArrayOf(1, 2, 3, 4)
                    else -> "${field.name}-value"
                }
            }
        }
        return out
    }

    private fun assertSeeded(db: SupportSQLiteDatabase, seeded: Seeded) {
        for (table in seeded.rows.keys + seeded.derived.keys) {
            assertEquals("rows in $table", seeded.count(table), count(db, table))
        }
        for ((table, list) in seeded.rows.entries + seeded.derived.entries) {
            for (row in list) {
                val columns = row.keys.toList()
                val where = columns.joinToString(" AND ") { "`$it` IS ?" }
                val found = db.query("SELECT count(*) FROM `$table` WHERE $where", columns.map { row[it] }.toTypedArray())
                    .use { it.moveToFirst(); it.getLong(0) }
                assertEquals("$table row ${row.filterValues { it !is ByteArray }}", 1L, found)
            }
        }
    }

    private fun count(db: SupportSQLiteDatabase, table: String): Long =
        db.query("SELECT count(*) FROM `$table`").use { it.moveToFirst(); it.getLong(0) }

    private fun column(db: SupportSQLiteDatabase, sql: String): List<String> = db.query(sql).use { c ->
        val out = ArrayList<String>()
        while (c.moveToNext()) out += c.getString(0) ?: ""
        out
    }

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

    /**
     * A signed-in account with a small library: backed-up, hidden and queued device photos, Drive photos and a
     * video, an own and a shared album, tags, places, faces with a named person and the curation around it,
     * search and duplicate data, an import run and, where the version has them, labels and the shared backup's
     * bookkeeping. A table the version does not have is left out; a column it does not have is dropped.
     */
    private fun seedRows(): Map<String, List<Map<String, Any?>>> {
        fun media(id: Int) = "content://media/external/images/media/$id"
        val faceVector = ByteArray(2048) { (it * 7 % 251).toByte() }
        val searchVector = ByteArray(1024) { (it * 13 % 241).toByte() }
        return linkedMapOf(
            "AccountEntity" to listOf(
                mapOf("userId" to USER, "username" to "tester", "email" to "tester@proton.me", "state" to "Ready",
                    "sessionId" to "session-1", "sessionState" to "Authenticated"),
            ),
            "SessionEntity" to listOf(
                mapOf("userId" to USER, "sessionId" to "session-1", "accessToken" to "access-test", "refreshToken" to "refresh-test",
                    "scopes" to "full;self;drive", "product" to "Drive"),
            ),
            "AccountMetadataEntity" to listOf(
                mapOf("userId" to USER, "product" to "Drive", "primaryAtUtc" to T0, "migrations" to null),
            ),
            "UserEntity" to listOf(
                mapOf("userId" to USER, "email" to "tester@proton.me", "name" to "tester", "displayName" to "Tester",
                    "currency" to "EUR", "credit" to 0L, "createdAtUtc" to T0, "usedSpace" to 5_368_709_120L,
                    "maxSpace" to 214_748_364_800L, "maxUpload" to 26_214_400L, "private" to 1L, "subscribed" to 4L, "services" to 4L),
            ),
            "sync_state" to listOf(
                mapOf("localUri" to media(1001), "userId" to USER, "cloudFileId" to "link-p1", "localHash" to "sha-1001",
                    "cloudHash" to "sha-1001", "status" to "SYNCED", "lastSyncAttemptMs" to T0, "lastSyncSuccessMs" to T0,
                    "backedUpAtMs" to T0, "sizeBytes" to 3_145_728L, "queued" to 0L),
                mapOf("localUri" to media(1002), "userId" to USER, "cloudFileId" to "link-p2", "localHash" to "sha-1002",
                    "cloudHash" to "sha-1002", "status" to "HIDDEN", "lastSyncAttemptMs" to T0 + 1, "lastSyncSuccessMs" to T0 + 1,
                    "backedUpAtMs" to T0 + 1, "sizeBytes" to 2_621_440L, "queued" to 0L),
                mapOf("localUri" to media(1003), "userId" to USER, "cloudFileId" to null, "localHash" to "sha-1003",
                    "cloudHash" to null, "status" to "LOCAL_ONLY", "lastSyncAttemptMs" to T0 + 2, "lastSyncSuccessMs" to null,
                    "backedUpAtMs" to null, "sizeBytes" to 4_194_304L, "queued" to 1L, "queueSource" to "AUTO", "queuedAt" to T0 + 2),
            ),
            "photo_listing" to listOf(
                listing("link-p1", "IMG_20240612_101500.jpg", "image/jpeg", T0 - 86_400_000L, null),
                listing("link-p2", "IMG_20240613_181200.jpg", "image/jpeg", T0 - 43_200_000L, null),
                listing("link-p3", "VID_20240614_090000.mp4", "video/mp4", T0 - 21_600_000L, 12_000L),
            ),
            "cloud_albums" to listOf(
                mapOf("linkId" to "album-1", "name" to "Balaton 2024", "photoCount" to 2L, "coverLinkId" to "link-p1",
                    "lastActivityTimeMs" to T0, "volumeId" to "vol-1", "lastFetchedMs" to T0),
                mapOf("linkId" to "album-2", "name" to "Shared with me", "photoCount" to 1L, "coverLinkId" to "link-p2",
                    "lastActivityTimeMs" to T0, "sharingShareId" to "share-a2", "sharingShareUrlId" to "url-a2",
                    "sharedByEmail" to "friend@proton.me", "volumeId" to "vol-2", "lastFetchedMs" to T0, "permissions" to 4L),
            ),
            "album_photo_membership" to listOf(
                mapOf("albumLinkId" to "album-1", "photoLinkId" to "link-p1"),
                mapOf("albumLinkId" to "album-1", "photoLinkId" to "link-p3"),
                mapOf("albumLinkId" to "album-2", "photoLinkId" to "link-p2"),
            ),
            "upload_album_target" to listOf(mapOf("localUri" to media(1003), "albumLinkId" to "album-1")),
            "local_tag" to listOf(
                mapOf("uri" to media(1001), "dateModified" to T0, "sizeBytes" to 3_145_728L, "tagsCsv" to "beach,sea,sky",
                    "scannedAt" to T0, "userTagsCsv" to "holiday"),
                mapOf("uri" to media(1003), "dateModified" to T0, "sizeBytes" to 4_194_304L, "tagsCsv" to "dog",
                    "scannedAt" to T0, "userTagsCsv" to ""),
            ),
            "photo_location" to listOf(
                mapOf("id" to "link-p1", "userId" to USER, "latitude" to 46.9136, "longitude" to 17.8919),
                mapOf("id" to media(1003), "userId" to USER, "latitude" to 47.4979, "longitude" to 19.0402),
            ),
            "day_meta" to listOf(
                mapOf("date" to "2024-06-12", "userId" to USER, "coverPhotoUri" to media(1001), "locationText" to "Tihany",
                    "description" to "Lake day", "updatedAt" to T0),
            ),
            "perceptual_hash" to listOf(
                mapOf("key" to "link-p1", "h0" to 0x1234_5678_9abc_def0L, "h1" to -42L, "h2" to 7L, "h3" to 99L, "quality" to 80L,
                    "color" to ByteArray(48) { it.toByte() }, "isCloud" to 1L, "freshness" to "rev-link-p1", "algoVersion" to 2L,
                    "computedAt" to T0),
            ),
            "pending_metadata_edit" to listOf(
                mapOf("linkId" to "link-p3", "userId" to USER, "newCaptureMs" to T0 - 1_000L, "locationMode" to "KEEP",
                    "description" to "Morning walk", "enqueuedAt" to T0),
            ),
            "listing_sweep_snapshot" to listOf(mapOf("userId" to USER, "volumeId" to "vol-1", "linkId" to "link-p1")),
            "import_history" to listOf(
                mapOf("id" to 1L, "zipId" to "takeout-1", "fileName" to "takeout-20240101.zip", "importedAt" to T0,
                    "total" to 10L, "uploaded" to 9L, "skipped" to 1L, "failed" to 0L, "runId" to "run-1"),
            ),
            "import_uploaded" to listOf(
                mapOf("id" to 1L, "runId" to "run-1", "linkId" to "link-p3", "sha1" to "da39a3ee5e6b4b0d3255bfef95601890afd80709",
                    "name" to "VID_20240614_090000.mp4", "dateMs" to T0 - 21_600_000L, "undone" to 0L, "alreadyInDrive" to 0L),
            ),
            "face" to listOf(
                mapOf("id" to "face-1", "userId" to USER, "photoKey" to "link-p1", "left" to 0.12, "top" to 0.2, "right" to 0.31,
                    "bottom" to 0.44, "landmarks" to "[0.15,0.25,0.27,0.25,0.21,0.31,0.17,0.38,0.26,0.38]", "embedding" to faceVector,
                    "personId" to 1L, "score" to 0.93, "blur" to 120.5, "rejected" to 0L, "manualName" to null),
                mapOf("id" to "face-2", "userId" to USER, "photoKey" to "link-p2", "left" to 0.5, "top" to 0.1, "right" to 0.7,
                    "bottom" to 0.4, "landmarks" to "[]", "embedding" to faceVector.reversedArray(), "personId" to 2L,
                    "score" to 0.81, "blur" to null, "rejected" to 0L, "manualName" to "Anna"),
            ),
            "person" to listOf(
                mapOf("id" to 1L, "userId" to USER, "displayName" to "Anna", "coverFaceId" to "face-1", "faceCount" to 1L,
                    "updatedAt" to T0, "isOther" to 0L),
                mapOf("id" to 2L, "userId" to USER, "displayName" to null, "coverFaceId" to "face-2", "faceCount" to 1L,
                    "updatedAt" to T0, "isOther" to 0L),
            ),
            "face_scan" to listOf(
                mapOf("userId" to USER, "photoKey" to "link-p1", "hiResScanned" to 1L),
                mapOf("userId" to USER, "photoKey" to "link-p2", "hiResScanned" to 0L),
            ),
            "person_manual_photo" to listOf(mapOf("userId" to USER, "personName" to "Anna", "photoKey" to "link-p3")),
            "not_person" to listOf(mapOf("userId" to USER, "personName" to "Anna", "faceId" to "face-2")),
            "person_cover" to listOf(mapOf("userId" to USER, "personName" to "Anna", "photoKey" to "link-p1")),
            "cluster_summary" to listOf(
                mapOf("personId" to 1L, "userId" to USER, "centroid" to faceVector, "memberCount" to 1L, "modelVersion" to 3L,
                    "updatedAt" to T0),
            ),
            "image_embedding" to listOf(
                mapOf("userId" to USER, "photoKey" to "link-p1", "embedding" to searchVector, "modelVersion" to 1L, "indexedAt" to T0),
            ),
            "photo_label" to listOf(
                mapOf("userId" to USER, "photoKey" to "link-p1", "label" to "Holiday", "labelKey" to "holiday", "updatedAt" to T0 + 10),
                mapOf("userId" to USER, "photoKey" to "link-p1", "label" to "Family", "labelKey" to "family", "updatedAt" to T0 + 12),
                mapOf("userId" to USER, "photoKey" to "link-p2", "label" to "Family", "labelKey" to "family", "updatedAt" to T0 + 11),
            ),
            "photo_label_name" to listOf(
                mapOf("userId" to USER, "labelKey" to "receipts", "label" to "Receipts", "createdAt" to T0 + 5),
            ),
            "photo_label_state" to listOf(mapOf("userId" to USER, "photoKey" to "link-p3", "changedAt" to T0 + 20)),
            "photo_label_deleted" to listOf(mapOf("userId" to USER, "labelKey" to "old", "label" to "Old", "deletedAt" to T0 + 21)),
            "drive_copy_applied" to listOf(mapOf("userId" to USER, "kind" to "labels", "deviceId" to "device-b", "createdAt" to T0 + 22)),
            "person_name_state" to listOf(mapOf("userId" to USER, "name" to "Anna", "namedAt" to T0 + 23)),
            "person_name_change" to listOf(mapOf("userId" to USER, "fromName" to "Ann", "toName" to "Anna", "changedAt" to T0 + 24)),
            "face_curation_time" to listOf(
                mapOf("userId" to USER, "kind" to 0L, "personName" to "Anna", "ref" to "link-p3", "removed" to 0L, "changedAt" to T0 + 25),
            ),
            "pending_cloud_compress" to listOf(
                mapOf("linkId" to "link-p2", "userId" to USER, "enqueuedAt" to T0 + 26, "newLinkId" to null, "attemptRun" to null,
                    "attempts" to 1L, "carriedOver" to 0L),
            ),
        )
    }

    private fun listing(linkId: String, name: String, mime: String, capture: Long, durationMs: Long?): Map<String, Any?> = mapOf(
        "linkId" to linkId, "shareId" to "share-photos", "volumeId" to "vol-1", "userId" to USER, "captureTime" to capture,
        "displayName" to name, "mimeType" to mime, "sizeBytes" to 3_000_000L, "revisionId" to "rev-$linkId",
        "contentHash" to "hash-$linkId", "tagsCsv" to "", "contentKeyPacket" to "wcBMA-$linkId",
        "encNodeKey" to "armored-node-key-$linkId", "encNodePassphrase" to "armored-node-passphrase-$linkId",
        "parentLinkId" to "photos-root", "isChildOfAlbum" to 0L, "gpsChecked" to 1L, "durationMs" to durationMs,
        "nameFingerprint" to "fp-$linkId",
    )

    private companion object {
        const val TAG = "DbEncUpgradeTest"
        const val USER = "uid-test-0001"
        const val T0 = 1_730_000_000_000L
    }
}
