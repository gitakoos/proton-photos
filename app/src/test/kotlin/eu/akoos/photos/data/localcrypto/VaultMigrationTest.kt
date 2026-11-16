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

import eu.akoos.photos.data.db.encryption.DatabaseKeyStoreTest.FakeWrapper
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.ProviderException
import java.util.concurrent.locks.ReentrantLock
import javax.crypto.KeyGenerator
import kotlin.random.Random

/**
 * The vault migration's rows of the failure table: a kill at every step, low storage, a verify failure, a file
 * changed while it was encrypted, a silent Keystore, and the byte-identical result.
 */
class VaultMigrationTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var vault: File
    private lateinit var staging: File
    private lateinit var wrapper: FakeWrapper
    private lateinit var keyring: LocalKeyring
    private val originals = mutableMapOf<String, ByteArray>()

    /** Stands in for the process dying: not an Exception, so nothing in the migration cleans up after it. */
    private class Killed : Error()

    @Before
    fun setUp() {
        vault = tmp.newFolder("files", "hidden")
        staging = File(tmp.root, "files/.hidden_staging")
        wrapper = FakeWrapper(KeyGenerator.getInstance("AES").apply { init(256) }.generateKey())
        keyring = LocalKeyring(File(tmp.root, "keys"), wrapper)
        for ((i, size) in listOf(0, 1, 70_000, 300_000).withIndex()) {
            val name = "uuid-$i.jpg"
            val bytes = Random(i).nextBytes(size)
            File(vault, name).writeBytes(bytes)
            originals[name] = bytes
        }
    }

    private fun migration(
        free: Long = Long.MAX_VALUE,
        checkpoint: (VaultMigration.Step, File) -> Unit = { _, _ -> },
    ) = VaultMigration(
        vaultDir = vault,
        stagingDir = staging,
        stateDir = File(tmp.root, "keys"),
        keyring = keyring,
        lock = ReentrantLock(),
        metaFor = { LocalFileMeta(originalName = "orig-${it.name}", sourceFolder = "Camera") },
        freeBytes = { free },
        checkpoint = checkpoint,
    )

    private fun plainOf(file: File): ByteArray = if (!LocalCryptoFormat.isEncrypted(file)) file.readBytes()
    else ByteArrayOutputStream().also { LocalCryptoFormat.decryptFile(file, keyring, it) }.toByteArray()

    /** The invariant behind every row: each photo is in the vault exactly once and reads byte for byte. */
    private fun assertVaultWhole() {
        val names = vault.listFiles()!!.filter { it.isFile }.map { it.name }.sorted()
        assertEquals(originals.keys.sorted(), names)
        for ((name, bytes) in originals) assertArrayEquals(name, bytes, plainOf(File(vault, name)))
    }

    @Test
    fun `every file is encrypted, carries its record and decrypts byte for byte`() {
        val outcome = migration().run()
        assertEquals(4, outcome.encrypted)
        assertTrue(outcome.complete)
        assertTrue(vault.listFiles()!!.all { LocalCryptoFormat.isEncrypted(it) })
        assertVaultWhole()
        LocalCryptoFormat.openFile(File(vault, "uuid-2.jpg"), keyring).use {
            assertEquals("orig-uuid-2.jpg", it.meta.originalName)
            assertEquals("Camera", it.meta.sourceFolder)
        }
        assertEquals(0, migration().run().encrypted)
    }

    @Test
    fun `a kill at any step leaves every photo whole and the next run finishes`() {
        for (step in VaultMigration.Step.values()) {
            setUpFresh()
            var killed = false
            val killing = migration { at, _ ->
                if (at == step && !killed) {
                    killed = true
                    throw Killed()
                }
            }
            assertTrue(runCatching { killing.run() }.exceptionOrNull() is Killed)
            assertVaultWhole()
            val outcome = migration().run()
            assertTrue("after a kill at $step", outcome.complete)
            assertVaultWhole()
            assertTrue(staging.listFiles().orEmpty().none { it.name.endsWith(VaultMigration.STAGING_SUFFIX) })
        }
    }

    private fun setUpFresh() {
        vault.listFiles()?.forEach { it.delete() }
        staging.deleteRecursively()
        for ((name, bytes) in originals) File(vault, name).writeBytes(bytes)
    }

    @Test
    fun `low storage stops before a file and leaves it as it is`() {
        val outcome = migration(free = 1_000L).run()
        assertTrue(outcome.stoppedForSpace)
        assertEquals(0, outcome.encrypted)
        assertEquals(4, outcome.remaining)
        assertVaultWhole()
        assertTrue(vault.listFiles()!!.none { LocalCryptoFormat.isEncrypted(it) })
    }

    @Test
    fun `a file that changes while it is encrypted is not replaced`() {
        val outcome = migration { at, temp ->
            if (at == VaultMigration.Step.STAGED && temp.name.startsWith("uuid-3")) {
                File(vault, "uuid-3.jpg").appendBytes(byteArrayOf(1))
                originals["uuid-3.jpg"] = originals["uuid-3.jpg"]!! + byteArrayOf(1)
            }
        }.run()
        assertEquals(1, outcome.failed)
        assertFalse(LocalCryptoFormat.isEncrypted(File(vault, "uuid-3.jpg")))
        assertVaultWhole()
    }

    @Test
    fun `a copy that does not verify is dropped and the plaintext stays`() {
        val outcome = migration { at, temp ->
            if (at == VaultMigration.Step.STAGED && temp.name.startsWith("uuid-2")) {
                val bytes = temp.readBytes()
                bytes[bytes.size - 5] = (bytes[bytes.size - 5].toInt() xor 1).toByte()
                temp.writeBytes(bytes)
            }
        }.run()
        assertEquals(1, outcome.failed)
        assertFalse(LocalCryptoFormat.isEncrypted(File(vault, "uuid-2.jpg")))
        assertVaultWhole()
    }

    @Test
    fun `a file that keeps failing is given up after three tries and stays readable`() {
        val failing = { at: VaultMigration.Step, temp: File ->
            if (at == VaultMigration.Step.STAGED && temp.name.startsWith("uuid-1")) throw java.io.IOException("disk")
        }
        repeat(3) { migration(checkpoint = failing).run() }
        val fourth = migration(checkpoint = failing).run()
        assertEquals(0, fourth.failed)
        assertEquals(1, fourth.remaining)
        assertVaultWhole()
    }

    @Test
    fun `a silent Keystore encrypts nothing and loses nothing`() {
        wrapper.failure = ProviderException("busy")
        keyring = LocalKeyring(File(tmp.root, "keys"), wrapper)
        val outcome = migration().run()
        assertEquals(0, outcome.encrypted)
        assertEquals(4, outcome.remaining)
        assertVaultWhole()
    }

    @Test
    fun `files already encrypted and hidden entries are left alone`() {
        migration().run()
        val before = vault.listFiles()!!.associate { it.name to it.readBytes() }
        File(vault, ".nomedia").writeBytes(ByteArray(0))
        migration().run()
        for ((name, bytes) in before) assertArrayEquals(bytes, File(vault, name).readBytes())
        assertEquals(0, File(vault, ".nomedia").length())
    }
}
