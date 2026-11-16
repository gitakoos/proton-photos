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

import eu.akoos.photos.data.db.encryption.DatabaseKeyState
import eu.akoos.photos.data.db.encryption.DatabaseKeyStoreTest.FakeWrapper
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.ProviderException
import javax.crypto.KeyGenerator

/** The keyring's rows of the failure table: reinstall over, Keystore lost or silent, two processes. */
class LocalKeyringTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val wrapper = FakeWrapper(KeyGenerator.getInstance("AES").apply { init(256) }.generateKey())

    private fun ring(clock: () -> Long = { 0L }) = LocalKeyring(tmp.root, wrapper, clock = clock)

    @Test
    fun `the current key survives a new process and a reinstall over the app`() {
        val first = ring().current()
        val again = ring().current()
        assertEquals(first.keyId, again.keyId)
        assertArrayEquals(first.key, again.key)
        assertEquals(LocalKeyOwner.UNASSIGNED, again.owner)
    }

    @Test
    fun `a rotation keeps every earlier key readable`() {
        val ring = ring()
        val old = ring.current()
        val sealed = LocalCryptoFormat.encryptBytes(byteArrayOf(1, 2, 3), LocalCryptoFormat.Sealing(old.keyId, old.key, LocalFileKind.VAULT))
        val next = ring.rotate(LocalKeyOwner.GUEST)
        assertNotEquals(old.keyId, next.keyId)
        assertArrayEquals(byteArrayOf(1, 2, 3), LocalCryptoFormat.decryptBytes(sealed, ring()))
        assertEquals(listOf(old.keyId, next.keyId).sorted(), ring().keyIds())
    }

    @Test
    fun `a lost Keystore locks the old keys, keeps them, and starts a new current key for the same owner`() {
        val old = ring().also { it.current(); it.setOwner(LocalKeyOwner.GUEST) }.current()
        wrapper.key = null // the Keystore forgot its key
        val ring = ring()
        assertEquals(listOf(old.keyId), ring.lockedKeyIds())
        assertTrue(runCatching { ring.keyFor(old.keyId) }.exceptionOrNull() is LocalKeyLockedException)
        val fresh = ring.current()
        assertNotEquals(old.keyId, fresh.keyId)
        assertEquals(LocalKeyOwner.GUEST, fresh.owner)
        assertTrue(File(tmp.root, "keyring/${old.keyId}").isDirectory)
    }

    @Test
    fun `a recovered key is sealed again and opens its files`() {
        val old = ring().current()
        val sealed = LocalCryptoFormat.encryptBytes(byteArrayOf(4), LocalCryptoFormat.Sealing(old.keyId, old.key, LocalFileKind.STORE))
        wrapper.key = null
        ring().current()
        ring().restore(old.keyId, old.key)
        val after = ring()
        assertTrue(after.state(old.keyId) is DatabaseKeyState.Ready)
        assertTrue(after.lockedKeyIds().isEmpty())
        assertArrayEquals(byteArrayOf(4), LocalCryptoFormat.decryptBytes(sealed, after))
    }

    @Test
    fun `a silent Keystore is reported as unavailable, never as a lost key`() {
        val old = ring().current()
        wrapper.failure = ProviderException("keystore busy")
        val ring = ring()
        assertTrue(runCatching { ring.keyFor(old.keyId) }.exceptionOrNull() is LocalKeyUnavailableException)
        assertTrue(ring.lockedKeyIds().isEmpty())
        // Nothing new is made while the Keystore is silent either.
        assertTrue(runCatching { ring.current() }.exceptionOrNull() is LocalKeyUnavailableException)
        assertEquals(listOf(old.keyId), ring.keyIds())
    }

    @Test
    fun `waiting for the Keystore stops only after three launches over an hour`() {
        var now = 1_000_000L
        assertFalse(ring { now }.unavailableTooLong())
        now += 10 * 60_000L
        assertFalse(ring { now }.unavailableTooLong())
        now += 10 * 60_000L
        assertFalse("three launches, but only 20 minutes", ring { now }.unavailableTooLong())
        now += 60 * 60_000L
        assertTrue(ring { now }.unavailableTooLong())
    }

    @Test
    fun `a damaged current record falls back to its copy`() {
        val first = ring().current()
        File(tmp.root, "keyring/current").writeText("garbage")
        assertEquals(first.keyId, ring().current().keyId)
    }

    @Test
    fun `two keyrings on the same files make one key`() {
        val a = ring()
        val b = ring()
        val threads = listOf(a, b).map { r -> Thread { r.current() } }
        threads.forEach { it.start() }
        threads.forEach { it.join() }
        assertEquals(1, ring().keyIds().size)
        assertEquals(a.current().keyId, b.current().keyId)
    }

    @Test
    fun `owners are stable hashes that do not show the account id`() {
        val owner = LocalKeyOwner.account("user-123")
        assertTrue(LocalKeyOwner.isAccount(owner))
        assertFalse(owner.contains("user-123"))
        assertEquals(owner, LocalKeyOwner.account("user-123"))
        assertNotEquals(owner, LocalKeyOwner.account("user-124"))
        assertFalse(LocalKeyOwner.isAccount(LocalKeyOwner.GUEST))
    }

    @Test
    fun `a Keystore key that throws for good locks its keys and a new key is made for new files`() {
        val broken = FakeWrapper(KeyGenerator.getInstance("AES").apply { init(256) }.generateKey())
        val ring = LocalKeyring(File(tmp.root, "permanent"), broken)
        val old = ring.current()
        broken.failure = java.security.UnrecoverableKeyException("broken entry")

        val reopened = LocalKeyring(File(tmp.root, "permanent"), broken)
        assertEquals(listOf(old.keyId), reopened.lockedKeyIds())
        val fresh = reopened.current()
        assertNotEquals(old.keyId, fresh.keyId)
        assertTrue(File(tmp.root, "permanent/keyring/${old.keyId}").isDirectory)
    }
}
