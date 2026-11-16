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
import java.security.KeyStoreException

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

        override fun restoreTables(
            live: File,
            liveKeySpec: String,
            old: File,
            oldKeySpec: String,
            tables: List<String>,
            accountHash: String?,
            hashOf: (String) -> String,
        ): RestoreResult {
            restores += Triple(old, accountHash, tables)
            restoreFailure?.let { throw it }
            return restoreResult
        }
    }

    @Before
    fun setUp() {
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
    private fun launch(): OpenTarget = gate().let { g -> g.prepare().also { g.opened(it) } }

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
        wrapper.failure = KeyStoreException("busy")

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
    fun `a keystore that does not answer moves nothing and fails the open`() {
        installEncrypted()
        wrapper.failure = KeyStoreException("busy")

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
        wrapper.failure = KeyStoreException("busy")
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
        wrapper.failure = KeyStoreException("busy")
        runCatching { gate().prepare() }
        runCatching { gate().prepare() }
        wrapper.failure = null
        assertTrue(launch() is OpenTarget.Encrypted)
        wrapper.failure = KeyStoreException("busy")
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
    fun `only the newest set-aside copy is kept, and none past two weeks`() {
        aside.mkdirs()
        File(aside, "${now - 1000}").mkdirs()
        File(aside, "${now - 2000}").mkdirs()
        launch()
        assertEquals(listOf("${now - 1000}"), aside.listFiles()!!.map { it.name })

        now += 15L * 24 * 60 * 60 * 1000
        launch()
        assertTrue(aside.listFiles()!!.isEmpty())
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
