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

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.KeyStoreException
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/** The wrapped database key on disk and how its state is read. */
class DatabaseKeyStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun aesKey(): SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    /** A Keystore stand-in: holds one key, can lose it, or can fail to answer. */
    class FakeWrapper(var key: SecretKey?, var failure: Exception? = null) : KeyWrapper {
        var generate: () -> SecretKey = { KeyGenerator.getInstance("AES").apply { init(256) }.generateKey() }
        val successors = mutableMapOf<Int, FakeWrapper>()
        override fun wrappingKey(create: Boolean): SecretKey? {
            failure?.let { throw it }
            if (key == null && create) key = generate()
            return key
        }

        override fun successor(generation: Int): KeyWrapper = successors.getOrPut(generation) { FakeWrapper(null) }
    }

    @Test
    fun `the codec opens what it sealed and refuses another key`() {
        val wrapping = aesKey()
        val raw = ByteArray(32) { it.toByte() }
        val blob = DatabaseKeyCodec.wrap(raw, wrapping)

        assertArrayEquals(raw, DatabaseKeyCodec.unwrap(blob, wrapping))
        val refused = runCatching { DatabaseKeyCodec.unwrap(blob, aesKey()) }.exceptionOrNull()
        assertTrue(refused is javax.crypto.AEADBadTagException)
    }

    @Test
    fun `the codec rejects a file that is not its format`() {
        val failure = runCatching { DatabaseKeyCodec.unwrap(byteArrayOf(1, 2, 3, 4, 5, 6), aesKey()) }.exceptionOrNull()
        assertTrue(failure is MalformedKeyFileException)
    }

    @Test
    fun `no wrapped key on disk is absent`() {
        val store = DatabaseKeyStore(tmp.root, FakeWrapper(aesKey()))
        assertEquals(DatabaseKeyState.Absent, store.load())
        assertFalse(store.hasWrappedKey())
    }

    @Test
    fun `a created key writes two copies and reads back the same bytes`() {
        val store = DatabaseKeyStore(tmp.root, FakeWrapper(null))
        val key = store.create()

        assertEquals(32, key.size)
        assertTrue(File(tmp.root, "key.bin").exists())
        assertTrue(File(tmp.root, "key.bak").exists())
        val loaded = store.load() as DatabaseKeyState.Ready
        assertArrayEquals(key, loaded.key)
    }

    @Test
    fun `a damaged primary copy falls back to the backup and is repaired`() {
        val store = DatabaseKeyStore(tmp.root, FakeWrapper(null))
        val key = store.create()
        File(tmp.root, "key.bin").writeBytes(byteArrayOf(9, 9, 9))

        val loaded = store.load() as DatabaseKeyState.Ready
        assertArrayEquals(key, loaded.key)
        assertArrayEquals(File(tmp.root, "key.bak").readBytes(), File(tmp.root, "key.bin").readBytes())
    }

    @Test
    fun `a missing Keystore key means the key is lost`() {
        val wrapper = FakeWrapper(null)
        val store = DatabaseKeyStore(tmp.root, wrapper)
        store.create()
        wrapper.key = null

        assertEquals(DatabaseKeyState.Lost, store.load())
    }

    @Test
    fun `a Keystore key that no longer matches either copy means the key is lost`() {
        val wrapper = FakeWrapper(null)
        val store = DatabaseKeyStore(tmp.root, wrapper)
        store.create()
        wrapper.key = aesKey()

        assertEquals(DatabaseKeyState.Lost, store.load())
    }

    @Test
    fun `a Keystore that does not answer is unavailable, not lost`() {
        val wrapper = FakeWrapper(null)
        val store = DatabaseKeyStore(tmp.root, wrapper)
        store.create()
        wrapper.failure = java.security.ProviderException("Keystore busy")

        assertTrue(store.load() is DatabaseKeyState.Unavailable)
    }

    /** The errors a broken key throws, and the shapes Android wraps a busy Keystore in. */
    private val suspectFailures = listOf(
        java.security.UnrecoverableKeyException("broken entry"),
        java.security.InvalidKeyException("Keystore operation failed"),
        KeyStoreException("entry unreadable"),
        java.security.InvalidKeyException("Failed to obtain information about key", java.security.ProviderException("busy")),
        java.security.ProviderException("wrapped", java.security.UnrecoverableKeyException("broken entry")),
        javax.crypto.AEADBadTagException("tag"),
    )

    private var start = 0
    private var clockMs = 1_800_000_000_000L

    @org.junit.Before
    fun startsAndClock() {
        KeystoreStrikes.currentStart = { "start-$start" }
        KeystoreStrikes.clock = { clockMs }
    }

    @org.junit.After
    fun resetStrikes() {
        KeystoreStrikes.currentStart = { "test" }
        KeystoreStrikes.clock = System::currentTimeMillis
    }

    /** A new process start, [minutes] after the last one. */
    private fun nextStart(minutes: Long) {
        start++
        clockMs += minutes * 60_000
    }

    @Test
    fun `an error that only looks permanent is unavailable on the first start, wrapped or not`() {
        for (failure in suspectFailures) {
            val dir = File(tmp.root, "k${failure.hashCode()}")
            val wrapper = FakeWrapper(null)
            val store = DatabaseKeyStore(dir, wrapper)
            store.create()
            wrapper.failure = failure
            repeat(5) { assertTrue(failure.toString(), store.load() is DatabaseKeyState.Unavailable) }
            assertTrue(store.hasWrappedKey())
        }
    }

    @Test
    fun `the same error on three starts over ten minutes is lost, so recovery runs`() {
        for (failure in suspectFailures) {
            val dir = File(tmp.root, "s${failure.hashCode()}")
            val wrapper = FakeWrapper(null)
            DatabaseKeyStore(dir, wrapper).create()
            wrapper.failure = failure
            assertTrue(DatabaseKeyStore(dir, wrapper).load() is DatabaseKeyState.Unavailable)
            nextStart(5)
            assertTrue(DatabaseKeyStore(dir, wrapper).load() is DatabaseKeyState.Unavailable)
            nextStart(5)
            assertEquals(failure.toString(), DatabaseKeyState.Lost, DatabaseKeyStore(dir, wrapper).load())
            assertTrue(DatabaseKeyStore(dir, wrapper).hasWrappedKey())
        }
    }

    @Test
    fun `three quick starts in a row are not enough`() {
        val wrapper = FakeWrapper(null)
        DatabaseKeyStore(tmp.root, wrapper).create()
        wrapper.failure = java.security.UnrecoverableKeyException("broken entry")
        repeat(6) {
            assertTrue(DatabaseKeyStore(tmp.root, wrapper).load() is DatabaseKeyState.Unavailable)
            nextStart(1)
        }
    }

    @Test
    fun `a key that opens again clears the count`() {
        val wrapper = FakeWrapper(null)
        DatabaseKeyStore(tmp.root, wrapper).create()
        val failure = java.security.UnrecoverableKeyException("broken entry")
        wrapper.failure = failure
        DatabaseKeyStore(tmp.root, wrapper).load()
        nextStart(6)
        DatabaseKeyStore(tmp.root, wrapper).load()
        nextStart(6)
        wrapper.failure = null
        assertTrue(DatabaseKeyStore(tmp.root, wrapper).load() is DatabaseKeyState.Ready)
        wrapper.failure = failure
        nextStart(6)
        assertTrue(DatabaseKeyStore(tmp.root, wrapper).load() is DatabaseKeyState.Unavailable)
    }

    @Test
    fun `an invalidated key is lost at once, however deep in the chain`() {
        for (failure in listOf(
            android.security.keystore.KeyPermanentlyInvalidatedException("invalidated"),
            java.security.ProviderException("wrapped", android.security.keystore.KeyPermanentlyInvalidatedException("invalidated")),
        )) {
            val dir = File(tmp.root, "i${failure.hashCode()}")
            val wrapper = FakeWrapper(null)
            DatabaseKeyStore(dir, wrapper).create()
            wrapper.failure = failure
            assertEquals(DatabaseKeyState.Lost, DatabaseKeyStore(dir, wrapper).load())
        }
    }

    @Test
    fun `a busy Keystore never moves to a new key while it only looks broken`() {
        val wrapper = FakeWrapper(null)
        DatabaseKeyStore(tmp.root, wrapper).create()
        wrapper.failure = java.security.InvalidKeyException("Keystore operation failed", java.security.ProviderException("busy"))

        assertTrue(runCatching { DatabaseKeyStore(tmp.root, wrapper).store(ByteArray(32)) }.isFailure)
        assertTrue(wrapper.successors.isEmpty())
    }

    @Test
    fun `a key stored after the Keystore key broke for good is sealed by a new one and reads back`() {
        val wrapper = FakeWrapper(null)
        DatabaseKeyStore(tmp.root, wrapper).create()
        wrapper.failure = android.security.keystore.KeyPermanentlyInvalidatedException("invalidated")
        val raw = ByteArray(32) { 9 }

        DatabaseKeyStore(tmp.root, wrapper).store(raw)

        val again = DatabaseKeyStore(tmp.root, wrapper).load()
        assertTrue(again is DatabaseKeyState.Ready)
        assertArrayEquals(raw, (again as DatabaseKeyState.Ready).key)
        assertEquals(setOf(1), wrapper.successors.keys)
    }

    @Test
    fun `a Keystore that only did not answer never moves to a new key`() {
        val wrapper = FakeWrapper(null)
        val store = DatabaseKeyStore(tmp.root, wrapper)
        store.create()
        wrapper.failure = java.security.ProviderException("Keystore busy")

        assertTrue(runCatching { store.store(ByteArray(32)) }.isFailure)
        assertTrue(wrapper.successors.isEmpty())
    }

    @Test
    fun `a Keystore key the cipher refuses is lost only once that held over several starts`() {
        val wrapper = FakeWrapper(null)
        DatabaseKeyStore(tmp.root, wrapper).create()
        wrapper.key = javax.crypto.spec.SecretKeySpec(ByteArray(7), "AES")

        assertTrue(DatabaseKeyStore(tmp.root, wrapper).load() is DatabaseKeyState.Unavailable)
        nextStart(6)
        DatabaseKeyStore(tmp.root, wrapper).load()
        nextStart(6)
        assertEquals(DatabaseKeyState.Lost, DatabaseKeyStore(tmp.root, wrapper).load())
    }

    @Test
    fun `moving the key files away leaves no key behind`() {
        val store = DatabaseKeyStore(tmp.root, FakeWrapper(null))
        store.create()
        val target = File(tmp.root, "aside")
        store.moveTo(target)

        assertEquals(DatabaseKeyState.Absent, store.load())
        assertTrue(File(target, "key.bin").exists())
    }

    @Test
    fun `the raw key form is hex in SQLCipher's x quote syntax`() {
        assertEquals("x'00FF10'", rawKeySpec(byteArrayOf(0, -1, 16)))
        assertEquals(67, rawKeySpec(ByteArray(32)).length)
    }
}
