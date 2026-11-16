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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.security.ProviderException

/** Which file Room opens, and every way the move to the encrypted file can stop without losing the data. */
class DatabaseOpenGateTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var databases: File
    private lateinit var state: File
    private lateinit var aside: File
    private lateinit var vault: File
    private lateinit var wrapper: DatabaseKeyStoreTest.FakeWrapper
    private lateinit var keys: DatabaseKeyStore
    private val engine = FakeEngine()
    private var now = 1_800_000_000_000L
    private var free: Long? = Long.MAX_VALUE
    private var version = 300
    private val lines = mutableListOf<String>()

    /** Encrypted files are `ENC|<key>|<payload>`; a probe opens one only with the key it was made with. */
    class FakeEngine : CipherEngine {
        var failExport: Exception? = null
        var probeFailure: Exception? = null
        var quickCheckPasses = true
        var exports = 0

        override fun probe(file: File, keySpec: String): ProbeResult {
            probeFailure?.let { return ProbeResult.Failed(it) }
            return if (file.readText().startsWith("ENC|$keySpec|")) ProbeResult.Ok else ProbeResult.Unreadable
        }

        override fun quickCheck(file: File, keySpec: String): Boolean = quickCheckPasses

        override fun export(plain: File, partial: File, keySpec: String): ExportReport {
            exports++
            partial.writeText("ENC|$keySpec|" + plain.readText().substringAfter("PLAIN|", ""))
            failExport?.let { throw it }
            return ExportReport(tables = 1, rows = 12_345)
        }

        var restoreResult: RestoreResult = RestoreResult.Restored(tables = 3, rows = 10)
        var restoreFailure: Exception? = null
        val restores = mutableListOf<Triple<File, String?, List<String>>>()
        val restoreKeys = mutableListOf<String>()
        val sameAccountChecks = mutableListOf<Boolean>()

        var liveWork = 0L

        override fun liveWorkRows(live: File, liveKeySpec: String, checks: Map<String, String>): Long = liveWork

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
            restores += Triple(old, accountHash, tables)
            restoreKeys += oldKeySpec
            sameAccountChecks += sameAccounts
            restoreFailure?.let { throw it }
            // Restored rows are the user's work from then on.
            if (restoreResult is RestoreResult.Restored) liveWork += 10
            return restoreResult
        }
    }

    private var start = 0

    @org.junit.After
    fun resetStrikes() {
        KeystoreStrikes.currentStart = { "test" }
        KeystoreStrikes.clock = System::currentTimeMillis
    }

    @Before
    fun setUp() {
        KeystoreStrikes.currentStart = { "start-$start" }
        KeystoreStrikes.clock = { now }
        databases = tmp.newFolder("databases")
        state = File(tmp.root, "no_backup/database_key")
        aside = File(tmp.root, "no_backup/database_set_aside")
        vault = tmp.newFolder("files", "hidden")
        File(vault, "photo.jpg").writeText("hidden photo bytes")
        wrapper = DatabaseKeyStoreTest.FakeWrapper(null)
        keys = DatabaseKeyStore(state, wrapper)
    }

    private val recoveredKeys = mutableMapOf<File, ByteArray>()

    private fun gate() = DatabaseOpenGate(
        databasesDir = databases,
        stateDir = state,
        setAsideDir = aside,
        keys = keys,
        engine = engine,
        versionCode = version,
        freeBytes = { free },
        clock = { now },
        log = { lines += it },
        elapsed = { now },
        recoveredKey = { dir -> recoveredKeys[dir] },
        appTables = listOf("labels", "face"),
        hashOf = { "h-$it" },
    )

    /** A set-aside copy a recovery copy opened: its key kept under `recovered/`, with the account it belongs to. */
    private fun recoveredSetAside(name: String, owner: String = "abc"): File {
        val dir = File(aside, name).apply { mkdirs() }
        File(dir, DatabaseOpenGate.ENCRYPTED_NAME).writeText("ENC|old|rows")
        val recovered = File(dir, DatabaseOpenGate.RECOVERED_DIR).apply { mkdirs() }
        File(recovered, DatabaseOpenGate.RECOVERED_OWNER).writeText(owner)
        recoveredKeys[recovered] = ByteArray(32) { 1 }
        return dir
    }

    private val plain get() = File(databases, DatabaseOpenGate.PLAIN_NAME)
    private val encrypted get() = File(databases, DatabaseOpenGate.ENCRYPTED_NAME)
    private val partial get() = File(databases, DatabaseOpenGate.PARTIAL_NAME)
    private val rebuilt get() = File(state, DatabaseOpenGate.REBUILT_MARKER)

    private fun writePlain(payload: String = "rows") {
        plain.writeText("PLAIN|$payload")
        File(plain.path + "-wal").writeText("wal")
    }

    /** A first launch, after which Room has written its encrypted file. */
    private fun installEncrypted(payload: String = "data") {
        launch()
        val key = (keys.load() as DatabaseKeyState.Ready).key
        encrypted.writeText("ENC|${rawKeySpec(key)}|$payload")
    }

    /** Opens once like a process start: prepare, then Room's successful open. */
    private fun launch(): OpenTarget {
        start++
        return gate().let { g -> g.prepare().also { g.opened(it) } }
    }

    private fun assertVaultUntouched() {
        assertEquals("hidden photo bytes", File(vault, "photo.jpg").readText())
    }

    @Test
    fun `a fresh install opens a new encrypted database`() {
        val target = launch()

        assertTrue(target is OpenTarget.Encrypted)
        assertEquals(encrypted.path, target.name)
        assertTrue(keys.hasWrappedKey())
        assertFalse(rebuilt.exists())
    }

    @Test
    fun `an existing plaintext database is copied, verified and only then removed`() {
        writePlain("12k rows")
        val g = gate()
        val target = g.prepare()

        assertTrue(target is OpenTarget.Encrypted)
        assertTrue(encrypted.readText().endsWith("12k rows"))
        assertTrue("plaintext kept until Room opened the copy", plain.exists())
        assertFalse(partial.exists())

        g.opened(target)
        assertFalse(plain.exists())
        assertFalse(File(plain.path + "-wal").exists())
        assertFalse(rebuilt.exists())
        assertVaultUntouched()
    }

    @Test
    fun `a failed copy keeps the plaintext database and opens it`() {
        writePlain()
        engine.failExport = IllegalStateException("row counts differ")

        val target = launch()

        assertTrue(target is OpenTarget.Plain)
        assertEquals(plain.path, target.name)
        assertTrue(plain.exists())
        assertFalse(partial.exists())
        assertFalse(encrypted.exists())
    }

    @Test
    fun `copy attempts stop after three failures and resume on a new version`() {
        writePlain()
        engine.failExport = IllegalStateException("boom")
        repeat(5) { launch() }
        assertEquals(3, engine.exports)

        version++
        engine.failExport = null
        val target = launch()
        assertTrue(target is OpenTarget.Encrypted)
        assertFalse(plain.exists())
    }

    @Test
    fun `a copy killed before the rename is discarded and redone`() {
        writePlain("data")
        keys.create()
        partial.writeText("half written")
        File(partial.path + "-journal").writeText("j")

        val target = launch()

        assertTrue(target is OpenTarget.Encrypted)
        assertFalse(partial.exists())
        assertFalse(File(partial.path + "-journal").exists())
        assertTrue(encrypted.readText().endsWith("data"))
    }

    @Test
    fun `a copy killed after the rename opens the encrypted file and removes the plaintext then`() {
        writePlain("data")
        gate().prepare()
        assertTrue(encrypted.exists() && plain.exists())

        val target = launch()

        assertTrue(target is OpenTarget.Encrypted)
        assertFalse(plain.exists())
        assertEquals(1, engine.exports)
    }

    @Test
    fun `too little free space keeps the plaintext database without spending an attempt`() {
        writePlain()
        free = 1L
        repeat(4) { assertTrue(launch() is OpenTarget.Plain) }
        assertEquals(0, engine.exports)

        free = Long.MAX_VALUE
        assertTrue(launch() is OpenTarget.Encrypted)
    }

    @Test
    fun `a keystore that does not answer during the copy keeps the plaintext database`() {
        writePlain()
        wrapper.failure = ProviderException("busy")

        assertTrue(launch() is OpenTarget.Plain)
        assertTrue(plain.exists())
        assertEquals(0, engine.exports)
    }

    @Test
    fun `a plaintext database left by an older build is removed once the encrypted one opens`() {
        writePlain("data")
        launch()
        writePlain("made by an older build")

        val target = launch()

        assertTrue(target is OpenTarget.Encrypted)
        assertTrue(encrypted.readText().endsWith("data"))
        assertFalse(plain.exists())
    }

    @Test
    fun `a lost key sets the encrypted file aside, never deletes it, and starts fresh`() {
        writePlain("data")
        launch()
        wrapper.key = null

        val target = launch()

        assertTrue(target is OpenTarget.Encrypted)
        val kept = aside.listFiles()!!.single()
        assertTrue(File(kept, DatabaseOpenGate.ENCRYPTED_NAME).readText().endsWith("data"))
        assertTrue(File(kept, "key.bin").exists())
        assertTrue(rebuilt.exists())
        assertFalse("Room creates a fresh file", encrypted.exists())
        assertVaultUntouched()
    }

    @Test
    fun `an unreadable encrypted file with the plaintext still there is redone from the plaintext`() {
        writePlain("data")
        gate().prepare()
        encrypted.writeText("garbage")

        val target = launch()

        assertTrue(target is OpenTarget.Encrypted)
        assertTrue(encrypted.readText().endsWith("data"))
        assertFalse(rebuilt.exists())
        assertEquals(1, aside.listFiles()!!.size)
    }

    @Test
    fun `an unreadable encrypted file alone is set aside and marked for a rebuild`() {
        installEncrypted()
        encrypted.writeText("garbage")

        assertTrue(launch() is OpenTarget.Encrypted)
        assertTrue(rebuilt.exists())
        assertEquals("garbage", File(aside.listFiles()!!.single(), DatabaseOpenGate.ENCRYPTED_NAME).readText())
    }

    @Test
    fun `a database file that cannot be set aside is never deleted, the open fails instead`() {
        installEncrypted()
        encrypted.writeText("garbage")
        File(encrypted.path + "-wal").writeText("wal")
        // The main file moves, its journal does not (something already sits where it would go), so it moves back.
        File(File(aside, "$now/${DatabaseOpenGate.ENCRYPTED_NAME}-wal"), "in-the-way").apply { parentFile!!.mkdirs(); writeText("x") }

        try {
            gate().prepare()
            fail("expected the open to fail")
        } catch (e: java.io.IOException) {
            assertEquals("garbage", encrypted.readText())
            assertEquals("wal", File(encrypted.path + "-wal").readText())
            assertFalse(rebuilt.exists())
        }
    }

    @Test
    fun `a set-aside folder that cannot be made deletes nothing and fails the open`() {
        installEncrypted()
        encrypted.writeText("garbage")
        aside.parentFile!!.mkdirs()
        aside.writeText("a file where the folder goes")

        try {
            gate().prepare()
            fail("expected the open to fail")
        } catch (e: java.io.IOException) {
            assertEquals("garbage", encrypted.readText())
        }
    }

    @Test
    fun `a keystore that does not answer moves nothing and fails the open`() {
        installEncrypted()
        wrapper.failure = ProviderException("busy")

        try {
            gate().prepare()
            fail("expected the open to fail")
        } catch (e: DatabaseKeyUnavailableException) {
            assertTrue(encrypted.exists())
            assertFalse(aside.exists() && aside.listFiles()!!.isNotEmpty())
        }
    }

    @Test
    fun `a keystore that stays silent over three launches and an hour is treated as lost`() {
        installEncrypted()
        wrapper.failure = ProviderException("busy")
        repeat(2) {
            assertTrue(runCatching { gate().prepare() }.exceptionOrNull() is DatabaseKeyUnavailableException)
            assertTrue(encrypted.exists())
            now += 40 * 60 * 1000L
        }

        val third = launch()

        assertTrue("no key can be made yet, so a plaintext file carries on", third is OpenTarget.Plain)
        assertEquals(1, aside.listFiles()!!.size)
        assertTrue(rebuilt.exists())

        wrapper.failure = null
        assertTrue(launch() is OpenTarget.Encrypted)
        assertFalse(plain.exists())
    }

    @Test
    fun `an answer from the keystore resets the silent count`() {
        installEncrypted()
        wrapper.failure = ProviderException("busy")
        runCatching { gate().prepare() }
        runCatching { gate().prepare() }
        wrapper.failure = null
        assertTrue(launch() is OpenTarget.Encrypted)
        wrapper.failure = ProviderException("busy")
        now += 2 * 60 * 60 * 1000L

        assertTrue(runCatching { gate().prepare() }.exceptionOrNull() is DatabaseKeyUnavailableException)
        assertTrue(encrypted.exists())
    }

    @Test
    fun `an I O failure on the probe moves nothing`() {
        installEncrypted()
        engine.probeFailure = IOException("EIO")

        assertTrue(runCatching { gate().prepare() }.isFailure)
        assertTrue(encrypted.exists())
        assertFalse(rebuilt.exists())
    }

    @Test
    fun `a corruption report is cleared when the check passes`() {
        installEncrypted()
        val g = gate()
        g.corruptMarker.writeText("")

        assertTrue(launch() is OpenTarget.Encrypted)
        assertFalse(g.corruptMarker.exists())
        assertFalse(aside.exists() && aside.listFiles()!!.isNotEmpty())
    }

    @Test
    fun `a corruption report the check confirms sets the file aside`() {
        installEncrypted()
        val g = gate()
        g.corruptMarker.writeText("")
        engine.quickCheckPasses = false

        assertTrue(launch() is OpenTarget.Encrypted)
        assertTrue(rebuilt.exists())
        assertEquals(1, aside.listFiles()!!.size)
    }

    @Test
    fun `a stray journal of an absent encrypted file is cleared before a new one is made`() {
        File(encrypted.path + "-wal").writeText("old frames")

        launch()

        assertFalse(File(encrypted.path + "-wal").exists())
    }

    @Test
    fun `a set-aside copy not restored is never pruned, only empty folders and identical copies`() {
        aside.mkdirs()
        File(aside, "${now - 1000}").mkdirs()
        val newer = File(aside, "${now - 2000}").apply { mkdirs() }
        File(newer, DatabaseOpenGate.ENCRYPTED_NAME).writeText("ENC|k1|rows")
        val older = File(aside, "${now - 3000}").apply { mkdirs() }
        File(older, DatabaseOpenGate.ENCRYPTED_NAME).writeText("ENC|k0|other rows")
        val duplicate = File(aside, "${now - 4000}").apply { mkdirs() }
        File(duplicate, DatabaseOpenGate.ENCRYPTED_NAME).writeText("ENC|k1|rows")

        now += 365L * 24 * 60 * 60 * 1000
        launch()

        assertEquals(setOf(newer.name, older.name), aside.listFiles()!!.map { it.name }.toSet())
    }

    @Test
    fun `a database set aside while the Keystore only looked broken comes back once its key answers`() {
        installEncrypted("library")
        val oldKey = rawKeySpec((keys.load() as DatabaseKeyState.Ready).key)
        wrapper.failure = java.security.InvalidKeyException("Keystore operation failed", ProviderException("busy"))
        // Unavailable on the first two starts; the third, over ten minutes after the first, takes it as lost.
        repeat(2) {
            assertTrue(runCatching { launch() }.isFailure)
            now += 6 * 60_000
        }
        assertTrue(launch() is OpenTarget.Encrypted)
        val kept = aside.listFiles()!!.single()
        assertTrue(File(kept, DatabaseOpenGate.ENCRYPTED_NAME).readText().endsWith("library"))
        encrypted.writeText("ENC|${rawKeySpec((keys.load() as DatabaseKeyState.Ready).key)}|fresh")

        wrapper.failure = null
        now += 60_000
        launch()

        assertEquals(listOf(oldKey), engine.restoreKeys)
        assertEquals("a key the Keystore gave back names no account", listOf(true), engine.sameAccountChecks)
        assertFalse("restored, so it goes", kept.exists())
    }

    @Test
    fun `a set-aside database is never copied over a live one that holds newer work, both are kept`() {
        installEncrypted("library")
        wrapper.failure = java.security.InvalidKeyException("Keystore operation failed", ProviderException("busy"))
        repeat(2) {
            runCatching { launch() }
            now += 6 * 60_000
        }
        launch()
        val kept = aside.listFiles()!!.single()
        val newer = "ENC|${rawKeySpec((keys.load() as DatabaseKeyState.Ready).key)}|days of new faces and edits"
        encrypted.writeText(newer)
        engine.liveWork = 7

        wrapper.failure = null
        now += 3L * 24 * 60 * 60 * 1000
        launch()
        launch()

        assertTrue("never copied over the newer work", engine.restores.isEmpty())
        assertEquals(newer, encrypted.readText())
        assertTrue(File(kept, DatabaseOpenGate.ENCRYPTED_NAME).readText().endsWith("library"))
        assertEquals(listOf(kept), aside.listFiles()!!.toList())
    }

    @Test
    fun `the user's own work is what blocks a restore, rebuilt listings do not`() {
        val work = AppOwnedTables.USER_WORK
        for (table in listOf("pending_metadata_edit", "import_history", "person", "photo_label", "sync_state")) {
            assertTrue(table, table in work)
        }
        for (table in listOf("photo_listing", "face", "image_embedding", "perceptual_hash")) {
            assertFalse(table, table in work)
        }
        assertTrue(work.keys.all { it in AppOwnedTables.NAMES })
    }

    @Test
    fun `a set-aside database whose key does not answer yet is kept and tried again`() {
        installEncrypted("library")
        wrapper.failure = java.security.InvalidKeyException("Keystore operation failed", ProviderException("busy"))
        repeat(2) {
            runCatching { launch() }
            now += 6 * 60_000
        }
        launch()
        encrypted.writeText("ENC|${rawKeySpec((keys.load() as DatabaseKeyState.Ready).key)}|fresh")
        now += 30L * 24 * 60 * 60 * 1000
        launch()
        launch()

        assertTrue(engine.restores.isEmpty())
        assertEquals(1, aside.listFiles()!!.size)
    }

    @Test
    fun `a lost key carries the recovery copies along with the set-aside file`() {
        installEncrypted()
        File(state, "${DatabaseKeyStore.RECOVERY_DIR}/db.acct.h1").apply { parentFile!!.mkdirs(); writeText("copy") }
        wrapper.key = null
        launch()
        val kept = aside.listFiles()!!.single()
        assertEquals("copy", File(kept, "${DatabaseKeyStore.RECOVERY_DIR}/db.acct.h1").readText())
    }

    @Test
    fun `a recovered set-aside copy is restored into the live database on the next start`() {
        installEncrypted()
        val dir = recoveredSetAside("${now - 5000}")
        launch()
        val (old, owner, tables) = engine.restores.single()
        assertEquals(File(dir, DatabaseOpenGate.ENCRYPTED_NAME), old)
        assertEquals("abc", owner)
        assertEquals(listOf("labels", "face"), tables)
        assertFalse("restored, so it goes", dir.exists())
    }

    @Test
    fun `of two restorable set-aside copies only the newest comes back, the older one is kept`() {
        installEncrypted()
        val older = recoveredSetAside("${now - 9000}")
        val newer = recoveredSetAside("${now - 5000}")

        launch()
        launch()

        assertEquals("one restore, never a second over it", listOf(File(newer, DatabaseOpenGate.ENCRYPTED_NAME)), engine.restores.map { it.first })
        assertFalse(newer.exists())
        assertTrue("the older copy is never deleted", File(older, DatabaseOpenGate.ENCRYPTED_NAME).exists())
    }

    @Test
    fun `a newest copy that does not restore leaves the next one to try, and only the restored folder goes`() {
        installEncrypted()
        val older = recoveredSetAside("${now - 9000}")
        val newer = recoveredSetAside("${now - 5000}")
        recoveredKeys.remove(File(newer, DatabaseOpenGate.RECOVERED_DIR))

        launch()

        assertEquals(listOf(File(older, DatabaseOpenGate.ENCRYPTED_NAME)), engine.restores.map { it.first })
        assertFalse(older.exists())
        assertTrue(File(newer, DatabaseOpenGate.ENCRYPTED_NAME).exists())
    }

    @Test
    fun `a restore the engine skips or fails keeps both copies`() {
        installEncrypted()
        engine.restoreResult = RestoreResult.Skipped("schema 57 vs 58")
        val dir = recoveredSetAside("${now - 5000}")
        launch()
        assertTrue(dir.exists())
        engine.restoreFailure = IOException("disk")
        launch()
        assertTrue(dir.exists())
        assertTrue(encrypted.exists())
    }

    @Test
    fun `set-aside copies a recovery copy may still open outlive the two weeks`() {
        aside.mkdirs()
        val waiting = File(aside, "${now - 1000}").apply { mkdirs() }
        File(waiting, "${DatabaseKeyStore.RECOVERY_DIR}/db.pwd").apply { parentFile!!.mkdirs(); writeText("copy") }
        now += 60L * 24 * 60 * 60 * 1000
        launch()
        assertTrue(waiting.exists())
    }

    @Test
    fun `the space asked for covers the copy, its overhead and a margin`() {
        val gb = 1024L * 1024 * 1024
        assertEquals(gb + gb / 10 + 50L * 1024 * 1024, DatabaseOpenGate.migrationSpaceNeeded(gb))
    }

    @Test
    fun `log lines never carry the key`() {
        writePlain()
        launch()
        val key = (keys.load() as DatabaseKeyState.Ready).key
        val spec = rawKeySpec(key)
        assertTrue(lines.isNotEmpty())
        assertTrue(lines.none { it.contains(spec.substring(2, 20)) })
    }
}
