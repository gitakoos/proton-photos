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

import androidx.datastore.core.CorruptionException
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferencesSerializer
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import eu.akoos.photos.data.db.encryption.DatabaseKeyStoreTest.FakeWrapper
import kotlinx.coroutines.runBlocking
import okio.Buffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.security.ProviderException
import javax.crypto.KeyGenerator

/**
 * The settings file's rows of the failure table: killed during its migration, Keystore lost or silent, a damaged
 * file, settings lost and merged back, a downgrade, and a write while the Keystore is silent.
 */
class EncryptedPreferencesTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var wrapper: FakeWrapper
    private lateinit var keyring: LocalKeyring
    private lateinit var locked: LockedStoreFiles
    private val log = mutableListOf<String>()

    private val name = stringPreferencesKey("name")
    private val hidden = stringSetPreferencesKey("hidden")
    private val flag = booleanPreferencesKey("flag")
    private val sample = preferencesOf(name to "Ada", hidden to setOf("a", "b"), flag to true)

    @Before
    fun setUp() {
        wrapper = FakeWrapper(KeyGenerator.getInstance("AES").apply { init(256) }.generateKey())
        keyring = LocalKeyring(File(tmp.root, "keys"), wrapper, clock = { 0L })
        locked = LockedStoreFiles(File(tmp.root, "keys/locked"), clock = { 42L })
    }

    private fun serializer(ring: LocalKeyring? = keyring) =
        EncryptedPreferencesSerializer("settings", ring, locked, { log += it }, pause = {})

    private fun plainBytes(p: Preferences) = Buffer().also { runBlocking { PreferencesSerializer.writeTo(p, it) } }.readByteArray()

    private fun write(p: Preferences, ring: LocalKeyring? = keyring): ByteArray =
        Buffer().also { runBlocking { serializer(ring).writeTo(p, it) } }.readByteArray()

    private fun read(bytes: ByteArray, ring: LocalKeyring? = keyring): Preferences =
        runBlocking { serializer(ring).readFrom(Buffer().write(bytes)) }

    @Test
    fun `a written file is encrypted and reads back the same`() {
        val bytes = write(sample)
        assertTrue(LocalCryptoFormat.looksEncrypted(bytes))
        assertEquals(sample, read(bytes))
    }

    @Test
    fun `a plaintext file of an earlier version still reads`() {
        assertEquals(sample, read(plainBytes(sample)))
    }

    @Test
    fun `without a Keystore the file stays plaintext both ways`() {
        val bytes = write(sample, ring = null)
        assertFalse(LocalCryptoFormat.looksEncrypted(bytes))
        assertEquals(sample, read(bytes, ring = null))
    }

    @Test
    fun `a locked file is kept aside before the settings start empty`() {
        val bytes = write(sample)
        wrapper.key = null
        val fresh = LocalKeyring(File(tmp.root, "keys"), wrapper)
        val result = read(bytes, fresh)
        assertTrue(result.asMap().isEmpty())
        val kept = locked.pending("settings")
        assertEquals(1, kept.size)
        assertArrayEquals(bytes, kept.single().readBytes())
    }

    @Test
    fun `a damaged file is kept aside too, never dropped`() {
        val bytes = write(sample).also { it[it.size - 3] = (it[it.size - 3].toInt() xor 0x40).toByte() }
        assertTrue(read(bytes).asMap().isEmpty())
        assertArrayEquals(bytes, locked.pending("settings").single().readBytes())
    }

    @Test
    fun `a silent Keystore is an error, not an empty file`() {
        val bytes = write(sample)
        wrapper.failure = ProviderException("busy")
        val fresh = LocalKeyring(File(tmp.root, "keys"), wrapper, clock = { 0L })
        val failure = runCatching { read(bytes, fresh) }.exceptionOrNull()
        assertTrue(failure is IOException)
        assertTrue(locked.pending("settings").isEmpty())
    }

    @Test
    fun `a write while the Keystore is silent stays plaintext and reads later`() {
        keyring.current()
        wrapper.failure = ProviderException("busy")
        val fresh = LocalKeyring(File(tmp.root, "keys"), wrapper)
        val bytes = write(sample, fresh)
        assertFalse(LocalCryptoFormat.looksEncrypted(bytes))
        wrapper.failure = null
        assertEquals(sample, read(bytes, LocalKeyring(File(tmp.root, "keys"), wrapper)))
    }

    @Test
    fun `settings lost to a locked key merge back once the key opens, newer values kept and sets joined`() {
        val old = keyring.current()
        val bytes = write(sample)
        wrapper.key = null
        val fresh = LocalKeyring(File(tmp.root, "keys"), wrapper)
        assertTrue(read(bytes, fresh).asMap().isEmpty())
        val live = preferencesOf(hidden to setOf("c"), name to "new")
        fresh.restore(old.keyId, old.key)
        val store = InMemoryStore(live)
        val merged = runBlocking { LocalStores.restoreLocked("settings", store, fresh, locked) { log += it } }
        assertEquals(1, merged)
        assertEquals("a value set since wins", "new", store.value[name])
        assertEquals(setOf("a", "b", "c"), store.value[hidden])
        assertTrue(locked.pending("settings").isEmpty())
    }

    @Test
    fun `settings set aside while the Keystore only looked broken merge back on the start its key answers`() {
        val bytes = write(sample)
        var start = 0
        var now = 1_800_000_000_000L
        eu.akoos.photos.data.db.encryption.KeystoreStrikes.currentStart = { "start-$start" }
        eu.akoos.photos.data.db.encryption.KeystoreStrikes.clock = { now }
        try {
            wrapper.failure = java.security.InvalidKeyException("Keystore operation failed", ProviderException("busy"))
            // Two starts it is waited for and the read fails; nothing is set aside.
            repeat(2) {
                assertTrue(runCatching { read(bytes, LocalKeyring(File(tmp.root, "keys"), wrapper, clock = { 0L })) }.isFailure)
                assertTrue(locked.pending("settings").isEmpty())
                start++
                now += 6 * 60_000
            }
            // The third start, over ten minutes on, takes the key as lost: kept aside, settings start empty.
            assertTrue(read(bytes, LocalKeyring(File(tmp.root, "keys"), wrapper, clock = { 0L })).asMap().isEmpty())
            assertEquals(1, locked.pending("settings").size)

            // The next start the key answers again, with no account or password involved.
            start++
            wrapper.failure = null
            val store = InMemoryStore(preferencesOf(flag to false))
            val merged = runBlocking {
                LocalStores.restoreLocked("settings", store, LocalKeyring(File(tmp.root, "keys"), wrapper), locked) {}
            }
            assertEquals(1, merged)
            assertEquals("Ada", store.value[name])
            assertTrue(locked.pending("settings").isEmpty())
        } finally {
            eu.akoos.photos.data.db.encryption.KeystoreStrikes.currentStart = { "test" }
            eu.akoos.photos.data.db.encryption.KeystoreStrikes.clock = System::currentTimeMillis
        }
    }

    @Test
    fun `a merge only fills what the live settings lack, and never brings back a key kept out`() {
        val password = androidx.datastore.preferences.core.stringPreferencesKey("vault_password_blob")
        val old = preferencesOf(name to "Ada", flag to true, hidden to setOf("a"), password to "old blob")

        val reset = LocalStores.mergeRecovered(old, preferencesOf(password to "new blob", hidden to setOf("b")))
        assertEquals("a Hidden password set again stays", "new blob", reset[password])
        assertEquals("Ada", reset[name])
        assertEquals(true, reset[flag])
        assertEquals(setOf("a", "b"), reset[hidden])

        val removed = LocalStores.mergeRecovered(old, preferencesOf(), keepOut = setOf("vault_password_blob"))
        assertEquals("a Hidden password removed since is not brought back", null, removed[password])
        assertEquals("Ada", removed[name])

        val folders = androidx.datastore.preferences.core.stringSetPreferencesKey("sync_folder_names")
        val notJoined = LocalStores.mergeRecovered(
            preferencesOf(folders to setOf("Camera", "WhatsApp"), hidden to setOf("a")),
            preferencesOf(folders to setOf("Camera"), hidden to setOf("b")),
            noJoin = setOf("sync_folder_names"),
        )
        assertEquals("a folder the user stopped backing up stays stopped", setOf("Camera"), notJoined[folders])
        assertEquals(setOf("a", "b"), notJoined[hidden])
    }

    @Test
    fun `of two locked copies the newer one wins, and the Hidden password comes from the newest alone`() {
        val password = stringPreferencesKey("vault_password_blob")
        val dir = File(tmp.root, "keys/locked")
        // Set aside at 9 and at 10: by time, not by how the names sort as text.
        LockedStoreFiles(dir, clock = { 9L }).setAside("settings", write(preferencesOf(name to "Ada", flag to true, password to "old blob", hidden to setOf("a"))))
        LockedStoreFiles(dir, clock = { 10L }).setAside("settings", write(preferencesOf(name to "Bea", hidden to setOf("b"))))
        val store = InMemoryStore(preferencesOf())

        val merged = runBlocking {
            LocalStores.restoreLocked("settings", store, keyring, locked, newestOnly = setOf("vault_password_blob")) { log += it }
        }

        assertEquals(2, merged)
        assertEquals("the newer copy wins", "Bea", store.value[name])
        assertEquals("an older copy still fills what the newer one lacks", true, store.value[flag])
        assertEquals(setOf("a", "b"), store.value[hidden])
        assertEquals("the newest copy held no password, so none comes back", null, store.value[password])
        assertTrue(locked.pending("settings").isEmpty())
    }

    @Test
    fun `while the newest copy cannot be read, an older one never brings the Hidden password back`() {
        val password = stringPreferencesKey("vault_password_blob")
        val dir = File(tmp.root, "keys/locked")
        LockedStoreFiles(dir, clock = { 9L }).setAside("settings", write(preferencesOf(name to "Ada", password to "old blob")))
        // The newest copy was sealed with a key this start cannot open.
        val other = LocalKeyring(File(tmp.root, "other_keys"), FakeWrapper(KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()))
        LockedStoreFiles(dir, clock = { 10L }).setAside("settings", write(preferencesOf(password to "new blob"), other))
        val store = InMemoryStore(preferencesOf())

        val merged = runBlocking {
            LocalStores.restoreLocked("settings", store, keyring, locked, newestOnly = setOf("vault_password_blob")) { log += it }
        }

        assertEquals(1, merged)
        assertEquals("Ada", store.value[name])
        assertEquals("the older copy's password stays out", null, store.value[password])
        assertEquals("the newest copy waits for its key", 1, locked.pending("settings").size)
    }

    @Test
    fun `a locked copy stays while its key does not open`() {
        val bytes = write(sample)
        wrapper.key = null
        val fresh = LocalKeyring(File(tmp.root, "keys"), wrapper)
        read(bytes, fresh)
        val store = InMemoryStore(preferencesOf())
        assertEquals(0, runBlocking { LocalStores.restoreLocked("settings", store, fresh, locked) {} })
        assertEquals(1, locked.pending("settings").size)
    }

    @Test
    fun `an older version cannot read the encrypted file and stops instead of overwriting it`() {
        val bytes = write(sample)
        val failure = runCatching { runBlocking { PreferencesSerializer.readFrom(Buffer().write(bytes)) } }.exceptionOrNull()
        assertTrue(failure is CorruptionException)
    }

    @Test
    fun `the migration encrypts a plaintext file in place and verifies it`() {
        val file = File(tmp.root, "settings.preferences_pb").apply { writeBytes(plainBytes(sample)) }
        assertTrue(LocalStoreMigration.migrate(file, "settings", keyring) { log += it })
        assertTrue(LocalCryptoFormat.looksEncrypted(file.readBytes()))
        assertEquals(sample, read(file.readBytes()))
        assertTrue("already encrypted", LocalStoreMigration.migrate(file, "settings", keyring) {})
    }

    @Test
    fun `a migration killed before its rename leaves the plaintext and a temp file the next start removes`() {
        val file = File(tmp.root, "settings.preferences_pb").apply { writeBytes(plainBytes(sample)) }
        val temp = File(tmp.root, "settings.preferences_pb.enc-tmp").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        assertEquals(sample, read(file.readBytes()))
        assertTrue(LocalStoreMigration.migrate(file, "settings", keyring) {})
        assertFalse(temp.exists())
        assertEquals(sample, read(file.readBytes()))
    }

    @Test
    fun `a migration waits for one another process is running and never removes its temp file`() {
        val file = File(tmp.root, "settings.preferences_pb").apply { writeBytes(plainBytes(sample)) }
        val temp = File(tmp.root, "settings.preferences_pb.enc-tmp")
        val holding = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val other = Thread {
            LocalStoreMigration.withMigrationLock(file) {
                temp.writeBytes(byteArrayOf(9, 9, 9))
                holding.countDown()
                release.await()
            }
        }.apply { start() }
        holding.await()
        var migrated: Boolean? = null
        val second = Thread { migrated = LocalStoreMigration.migrate(file, "settings", keyring) {} }.apply { start() }
        second.join(300)
        assertTrue("the second migration waits for the lock", second.isAlive)
        assertTrue("the running migration's temp file is still there", temp.exists())
        release.countDown()
        other.join()
        second.join(5_000)
        assertEquals(true, migrated)
        assertEquals(sample, read(file.readBytes()))
    }

    @Test
    fun `a file that is not settings is left exactly as it is`() {
        val junk = byteArrayOf(1, 2, 3, 4, 5)
        val file = File(tmp.root, "settings.preferences_pb").apply { writeBytes(junk) }
        assertFalse(LocalStoreMigration.migrate(file, "settings", keyring) {})
        assertArrayEquals(junk, file.readBytes())
    }

    @Test
    fun `the migration does nothing while the Keystore is silent`() {
        val plain = plainBytes(sample)
        val file = File(tmp.root, "settings.preferences_pb").apply { writeBytes(plain) }
        wrapper.failure = ProviderException("busy")
        val fresh = LocalKeyring(File(tmp.root, "keys"), wrapper)
        assertFalse(LocalStoreMigration.migrate(file, "settings", fresh) {})
        assertArrayEquals(plain, file.readBytes())
    }

    /** A DataStore holding its value in memory. */
    private class InMemoryStore(initial: Preferences) : androidx.datastore.core.DataStore<Preferences> {
        var value: Preferences = initial
        override val data = kotlinx.coroutines.flow.flow { emit(value) }
        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
            transform(value).also { value = it }
    }
}
