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

import androidx.datastore.core.DataStore
import androidx.datastore.core.okio.OkioStorage
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferencesSerializer
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import eu.akoos.photos.data.db.encryption.DatabaseKeyStoreTest.FakeWrapper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okio.Buffer
import okio.FileSystem
import okio.Path.Companion.toPath
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.ProviderException
import javax.crypto.KeyGenerator

/**
 * What a settings write the Keystore refuses does to its two kinds of caller. A plain caller (a switch, a choice,
 * made from a scope with no handler) gets no exception, the value stays as it was and the user is told once. A
 * caller whose correctness depends on the write (the Hidden records, the sign-out markers, a locked copy merged
 * back) gets [SettingsWriteRefusedException]. In neither case is a byte of plaintext written.
 */
class SettingsWriteRefusalTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var wrapper: FakeWrapper
    private lateinit var locked: LockedStoreFiles
    private lateinit var file: File
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var shown = 0
    private var now = 0L

    private val name = stringPreferencesKey("name")
    private val storeName = "test_store"

    @Before
    fun setUp() {
        wrapper = FakeWrapper(KeyGenerator.getInstance("AES").apply { init(256) }.generateKey())
        locked = LockedStoreFiles(File(tmp.root, "keys/locked"), clock = { 42L })
        file = File(tmp.root, "$storeName.preferences_pb")
        SettingsWriteRefusals.resetForTest()
        SettingsWriteRefusals.log = {}
        SettingsWriteRefusals.clock = { now }
        SettingsWriteRefusals.show = { shown++ }
    }

    @After
    fun tearDown() {
        scope.cancel()
        SettingsWriteRefusals.resetForTest()
    }

    /** A plaintext file holding name=Ada, written before the key existed, and a key minted since. */
    private fun plaintextFileWithAKeyOnDisk() {
        file.writeBytes(Buffer().also { runBlocking { PreferencesSerializer.writeTo(preferencesOf(name to "Ada"), it) } }.readByteArray())
        LocalKeyring(File(tmp.root, "keys"), wrapper, clock = { 0L }).current()
    }

    /** The store as the app builds it, over a keyring that never opened its key this process. */
    private fun guardedStore(pause: suspend (Long) -> Unit = {}): GuardedPreferencesStore {
        val keyring = LocalKeyring(File(tmp.root, "keys"), wrapper, clock = { 0L })
        val raw: DataStore<Preferences> = PreferenceDataStoreFactory.create(
            storage = OkioStorage(
                fileSystem = FileSystem.SYSTEM,
                serializer = EncryptedPreferencesSerializer(storeName, keyring, locked, {}, pause),
                producePath = { file.absolutePath.toPath() },
            ),
            scope = scope,
        )
        return GuardedPreferencesStore(raw, storeName, SettingsWriteRefusals::report)
    }

    @Test
    fun `a plain caller's refused write throws nothing, keeps the value and tells the user`() = runBlocking {
        plaintextFileWithAKeyOnDisk()
        val store = guardedStore()
        wrapper.failure = ProviderException("busy")
        val before = file.readBytes()

        val after = store.edit { it[name] = "Bob" }

        assertEquals("the caller gets the settings as they stand", "Ada", after[name])
        assertEquals("Ada", store.data.first()[name])
        assertEquals(1, shown)
        assertTrue("the file is exactly as it was, never rewritten in plaintext", before.contentEquals(file.readBytes()))
    }

    @Test
    fun `a caller that depends on the write gets the refusal`() = runBlocking {
        plaintextFileWithAKeyOnDisk()
        val store = guardedStore()
        wrapper.failure = ProviderException("busy")
        val before = file.readBytes()

        val failure = runCatching { store.strict.edit { it[name] = "Bob" } }.exceptionOrNull()

        assertTrue(failure is SettingsWriteRefusedException)
        assertEquals("Ada", store.data.first()[name])
        assertEquals("the strict caller handles it; no message from here", 0, shown)
        assertTrue(before.contentEquals(file.readBytes()))
    }

    @Test
    fun `a Keystore that answers again during the wait gets the change written encrypted`() = runBlocking {
        plaintextFileWithAKeyOnDisk()
        var waited = 0L
        val store = guardedStore(pause = { ms ->
            waited += ms
            if (waited >= 3_000) wrapper.failure = null
        })
        wrapper.failure = ProviderException("busy")

        store.strict.edit { it[name] = "Bob" }

        assertEquals("Bob", store.data.first()[name])
        assertTrue(LocalCryptoFormat.looksEncrypted(file.readBytes()))
        assertEquals(0, shown)
    }

    @Test
    fun `a write is refused only after the Keystore stayed silent for the whole wait`() = runBlocking {
        plaintextFileWithAKeyOnDisk()
        val pauses = mutableListOf<Long>()
        val store = guardedStore(pause = { pauses += it })
        wrapper.failure = ProviderException("busy")

        assertTrue(runCatching { store.strict.edit { it[name] = "Bob" } }.exceptionOrNull() is SettingsWriteRefusedException)

        assertEquals(EncryptedPreferencesSerializer.WRITE_BACKOFF_MS.toList(), pauses)
        val total = pauses.sum()
        assertTrue("waited $total ms", total in 20_000L..30_000L)
    }

    @Test
    fun `many refused writes tell the user once, and again only after a quiet spell`() = runBlocking {
        plaintextFileWithAKeyOnDisk()
        val store = guardedStore()
        wrapper.failure = ProviderException("busy")

        repeat(5) { store.edit { it[name] = "Bob $it" } }
        assertEquals(1, shown)
        now += 9_999
        store.edit { it[name] = "Carol" }
        assertEquals(1, shown)
        now += 1
        store.edit { it[name] = "Dan" }
        assertEquals(2, shown)
        assertEquals("Ada", store.data.first()[name])
    }

    @Test
    fun `a locked copy whose merge is refused stays to merge back later`() = runBlocking {
        // A copy set aside under the old key, which the keyring can open again.
        val ring = LocalKeyring(File(tmp.root, "keys"), wrapper, clock = { 0L })
        val sealed = sealStoreBytes(ring, storeName, Buffer().also { PreferencesSerializer.writeTo(preferencesOf(name to "Ada"), it) }.readByteArray())
        locked.setAside(storeName, sealed)
        assertEquals(1, locked.pending(storeName).size)

        // The live file is plaintext and the Keystore stops answering before the merge writes.
        file.writeBytes(Buffer().also { PreferencesSerializer.writeTo(preferencesOf(), it) }.readByteArray())
        val store = guardedStore()
        val opener = ring
        wrapper.failure = ProviderException("busy")

        val result = runCatching { LocalStores.restoreLocked(storeName, store, opener, locked) {} }

        assertTrue("the merge reports failure", result.isFailure || result.getOrNull() == 0)
        assertEquals("the copy is kept", 1, locked.pending(storeName).size)
        assertFalse(store.data.first().contains(name))
    }
}
