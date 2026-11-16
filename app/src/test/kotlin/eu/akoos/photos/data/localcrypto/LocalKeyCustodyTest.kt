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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The key hierarchy's rows of the failure table: signed out then in, account switch, guest with and without a
 * password, Keystore lost with each way back, and the recovery copies themselves.
 */
class LocalKeyCustodyTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var wrapper: FakeWrapper
    private lateinit var keyring: LocalKeyring
    private lateinit var copies: KeyRecoveryCopies
    private lateinit var database: FakeDatabase
    private lateinit var custody: LocalKeyCustody

    /** An account's address key stand-in: AES-GCM under a key only that account's sealer holds. */
    class FakeSealer(
        private val key: SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey(),
        /** Older keys of the same account, which still open what they sealed (a key rotation). */
        private val older: List<SecretKey> = emptyList(),
        override val sealId: String? = null,
    ) : AccountSealer {
        override fun seal(payload: ByteArray): ByteArray {
            val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
            val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv)) }
            return iv + c.doFinal(payload)
        }

        override fun open(blob: ByteArray): ByteArray? = (listOf(key) + older).firstNotNullOfOrNull { candidate ->
            runCatching {
                val c = Cipher.getInstance("AES/GCM/NoPadding")
                    .apply { init(Cipher.DECRYPT_MODE, candidate, GCMParameterSpec(128, blob.copyOfRange(0, 12))) }
                c.doFinal(blob, 12, blob.size - 12)
            }.getOrNull()
        }
    }

    class FakeDatabase(root: File) : DatabaseKeyAccess {
        var key: ByteArray? = ByteArray(32) { 7 }
        val setAsides = mutableListOf<SetAsideDatabase>()
        val recovered = mutableMapOf<File, Pair<ByteArray, String?>>()
        override fun currentKey(): ByteArray? = key
        override val copies = KeyRecoveryCopies(File(root, "db_recovery"), iterations = 1_000)
        override fun waitingSetAsides(): List<SetAsideDatabase> = setAsides.filter { it.dir !in recovered }
        override fun markRecovered(setAside: SetAsideDatabase, key: ByteArray, ownerHash: String?) {
            recovered[setAside.dir] = key to ownerHash
        }
    }

    @Before
    fun setUp() {
        wrapper = FakeWrapper(KeyGenerator.getInstance("AES").apply { init(256) }.generateKey())
        keyring = LocalKeyring(File(tmp.root, "keys"), wrapper)
        copies = KeyRecoveryCopies(File(tmp.root, "keys/recovery"), iterations = 1_000)
        database = FakeDatabase(tmp.root)
        custody = LocalKeyCustody(keyring, copies, database) {}
    }

    private fun reopen() {
        keyring = LocalKeyring(File(tmp.root, "keys"), wrapper)
        custody = LocalKeyCustody(keyring, copies, database) {}
    }

    @Test
    fun `a guest owns the first key and the account it signs in to adopts it`() {
        custody.onOccupant(null)
        val guestKey = keyring.currentInfo()!!
        assertEquals(LocalKeyOwner.GUEST, guestKey.second)
        custody.onOccupant("alice")
        val after = keyring.currentInfo()!!
        assertEquals(guestKey.first, after.first)
        assertEquals(LocalKeyOwner.account("alice"), after.second)
    }

    @Test
    fun `signed out then in again writes new files with a new key and keeps the old one readable`() {
        custody.onOccupant("alice")
        val aliceKey = keyring.current()
        custody.onSignedOut("alice")
        val between = keyring.currentInfo()!!
        assertNotEquals(aliceKey.keyId, between.first)
        assertEquals(LocalKeyOwner.UNASSIGNED, between.second)
        custody.onOccupant("alice")
        assertEquals(between.first, keyring.currentInfo()!!.first)
        assertTrue(keyring.state(aliceKey.keyId) is DatabaseKeyState.Ready)
    }

    @Test
    fun `an account switch gives the new account its own key`() {
        custody.onOccupant("alice")
        val alice = keyring.current().keyId
        custody.onOccupant("bob")
        val bob = keyring.currentInfo()!!
        assertNotEquals(alice, bob.first)
        assertEquals(LocalKeyOwner.account("bob"), bob.second)
        assertEquals(2, keyring.keyIds().size)
    }

    @Test
    fun `the same account again changes nothing`() {
        custody.onOccupant("alice")
        val before = keyring.currentInfo()
        custody.onOccupant("alice")
        reopen()
        custody.onOccupant("alice")
        assertEquals(before, keyring.currentInfo())
    }

    @Test
    fun `a lost Keystore is recovered by signing in to the account`() {
        val sealer = FakeSealer()
        custody.onOccupant("alice")
        val old = keyring.current()
        assertEquals(2, custody.ensureAccountCopies("alice", sealer))
        assertEquals(0, custody.ensureAccountCopies("alice", sealer))
        val dbAside = SetAsideDatabase(tmp.newFolder("aside1"), database.copies)

        wrapper.key = null
        reopen()
        database.setAsides += dbAside
        assertEquals(listOf(old.keyId), keyring.lockedKeyIds())
        assertTrue(custody.status().locked)

        val outcome = custody.recoverWithAccount("alice", sealer)
        assertEquals(1, outcome.keys)
        assertEquals(1, outcome.databases)
        assertArrayEquals(old.key, keyring.keyFor(old.keyId))
        assertArrayEquals(ByteArray(32) { 7 }, database.recovered[dbAside.dir]!!.first)
        assertEquals(LocalKeyOwner.userHash("alice"), database.recovered[dbAside.dir]!!.second)
        assertFalse(custody.status().locked)
    }

    @Test
    fun `after the account's primary key changes, every account copy is sealed again to the new one`() {
        val oldKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val newKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        custody.onOccupant("alice")
        val first = keyring.current()
        custody.ensureAccountCopies("alice", FakeSealer(oldKey, sealId = "old"))
        custody.onSignedOut("alice")
        custody.onOccupant("alice")
        val second = keyring.current()
        custody.ensureAccountCopies("alice", FakeSealer(oldKey, sealId = "old"))

        // The account rotated its key: the new one seals, the old one still opens.
        custody.ensureAccountCopies("alice", FakeSealer(newKey, older = listOf(oldKey), sealId = "new"))

        // Once the old key cannot be opened any more (a password reset), the copies still open with the new one.
        val newOnly = FakeSealer(newKey, sealId = "new")
        val hash = LocalKeyOwner.userHash("alice")
        assertArrayEquals(first.key, copies.openAccountCopy(first.keyId, hash, newOnly))
        assertArrayEquals(second.key, copies.openAccountCopy(second.keyId, hash, newOnly))
        assertArrayEquals(database.key, database.copies.openAccountCopy(LocalKeyCustody.DATABASE_NAME, hash, newOnly))
    }

    @Test
    fun `another account's copy opens nothing`() {
        custody.onOccupant("alice")
        val old = keyring.current()
        custody.ensureAccountCopies("alice", FakeSealer())
        wrapper.key = null
        reopen()
        assertEquals(0, custody.recoverWithAccount("bob", FakeSealer()).keys)
        assertEquals(0, custody.recoverWithAccount("alice", FakeSealer()).keys) // same id, other address key
        assertEquals(listOf(old.keyId), keyring.lockedKeyIds())
    }

    @Test
    fun `a guest with a password gets it back with the password`() {
        custody.onOccupant(null)
        val old = keyring.current()
        custody.syncPasswordCopies("correct horse battery".toCharArray(), rewrite = false)
        wrapper.key = null
        reopen()
        assertTrue(custody.status().passwordCanUnlock)
        val wrong = custody.recoverWithPassword("nope".toCharArray())
        assertTrue(wrong.wrongPassword)
        assertFalse(wrong.any)
        val right = custody.recoverWithPassword("correct horse battery".toCharArray())
        assertEquals(1, right.keys)
        assertArrayEquals(old.key, keyring.keyFor(old.keyId))
    }

    @Test
    fun `a guest without a password stays locked and the status says no password can help`() {
        custody.onOccupant(null)
        keyring.current()
        wrapper.key = null
        reopen()
        val status = custody.status()
        assertTrue(status.locked)
        assertFalse(status.passwordCanUnlock)
        assertFalse(custody.recoverWithPassword("anything".toCharArray()).any)
    }

    @Test
    fun `changing or removing the password never drops the copy of a key that is locked`() {
        custody.onOccupant(null)
        val old = keyring.current()
        custody.syncPasswordCopies("first password".toCharArray(), rewrite = false)
        wrapper.key = null
        reopen()
        val fresh = keyring.current()
        custody.syncPasswordCopies("second password".toCharArray(), rewrite = true)
        custody.syncPasswordCopies(null, rewrite = true)
        assertTrue(copies.hasPasswordCopy(old.keyId))
        assertFalse(copies.hasPasswordCopy(fresh.keyId))
        assertEquals(1, custody.recoverWithPassword("first password".toCharArray()).keys)
    }

    @Test
    fun `a start that finds no readable password keeps every password copy`() {
        custody.onOccupant(null)
        val key = keyring.current()
        custody.syncPasswordCopies("first password".toCharArray(), rewrite = false)
        // The stored password could not be read (its Keystore key went with the others), or none is set:
        // the start passes no password and must not take that for the user removing it.
        custody.syncPasswordCopies(null, rewrite = false)
        assertTrue(copies.hasPasswordCopy(key.keyId))
        assertTrue(database.copies.hasPasswordCopy(LocalKeyCustody.DATABASE_NAME))
        assertArrayEquals(key.key, copies.openPasswordCopy(key.keyId, "first password".toCharArray()))
    }

    @Test
    fun `a password copy proves the password when the stored one cannot be read`() {
        custody.onOccupant(null)
        keyring.current()
        custody.syncPasswordCopies("first password".toCharArray(), rewrite = false)
        assertTrue(custody.passwordOpensCopies("first password".toCharArray()))
        assertFalse(custody.passwordOpensCopies("a guess".toCharArray()))
    }

    @Test
    fun `only the user removing the password removes the copies`() {
        custody.onOccupant(null)
        val key = keyring.current()
        custody.syncPasswordCopies("first password".toCharArray(), rewrite = false)
        custody.syncPasswordCopies(null, rewrite = true)
        assertFalse(copies.hasPasswordCopy(key.keyId))
        assertFalse(database.copies.hasPasswordCopy(LocalKeyCustody.DATABASE_NAME))
    }

    @Test
    fun `a changed password rewrites the copies of keys that open`() {
        custody.onOccupant(null)
        val key = keyring.current()
        custody.syncPasswordCopies("first password".toCharArray(), rewrite = false)
        custody.syncPasswordCopies("second password".toCharArray(), rewrite = true)
        assertNull(copies.openPasswordCopy(key.keyId, "first password".toCharArray()))
        assertArrayEquals(key.key, copies.openPasswordCopy(key.keyId, "second password".toCharArray()))
        assertArrayEquals(database.key, database.copies.openPasswordCopy(LocalKeyCustody.DATABASE_NAME, "second password".toCharArray()))
    }

    @Test
    fun `a copy names its key, so it is never taken for another one`() {
        val key = ByteArray(32) { 3 }
        copies.writePasswordCopy("aaaa", key, "pw".toCharArray())
        File(tmp.root, "keys/recovery/aaaa.pwd").copyTo(File(tmp.root, "keys/recovery/bbbb.pwd"))
        assertArrayEquals(key, copies.openPasswordCopy("aaaa", "pw".toCharArray()))
        assertNull(copies.openPasswordCopy("bbbb", "pw".toCharArray()))
        val sealer = FakeSealer()
        copies.writeAccountCopy("aaaa", key, "h", sealer)
        File(tmp.root, "keys/recovery/aaaa.acct.h").copyTo(File(tmp.root, "keys/recovery/bbbb.acct.h"))
        assertNull(copies.openAccountCopy("bbbb", "h", sealer))
        assertArrayEquals(key, copies.openAccountCopy("aaaa", "h", sealer))
    }

    @Test
    fun `an account copy that does not open is never written`() {
        val broken = object : AccountSealer {
            override fun seal(payload: ByteArray) = payload.reversedArray()
            override fun open(blob: ByteArray): ByteArray? = null
        }
        assertTrue(runCatching { copies.writeAccountCopy("aaaa", ByteArray(32), "h", broken) }.isFailure)
        assertFalse(copies.hasAccountCopy("aaaa", "h"))
    }
}
