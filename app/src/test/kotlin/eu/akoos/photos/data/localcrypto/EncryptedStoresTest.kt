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

import android.content.Context
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.test.core.app.ApplicationProvider
import eu.akoos.photos.data.db.encryption.DatabaseKeyStoreTest.FakeWrapper
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import javax.crypto.KeyGenerator

private val Context.startOpenTestStore by encryptedPreferencesDataStore("start_open_test")

/** The start-up restore reaches every encrypted settings file, opened or not by the rest of the app. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EncryptedStoresTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `every encrypted settings file is opened and registered for the restore, the transfer history too`() {
        val stores = encryptedStores(context)

        assertEquals(LocalEncryptionCoordinator.STORE_NAMES.toSet(), stores.keys)
        for ((name, store) in stores) assertSame(name, store, LocalStores.named(name))
    }

    @Test
    fun `a settings file an earlier version left in plaintext is encrypted when the start opens it`() {
        val keys = File(context.noBackupFilesDir, "stores_test_keys")
        LocalCrypto.useKeyringForTest(LocalKeyring(keys, FakeWrapper(KeyGenerator.getInstance("AES").apply { init(256) }.generateKey())))
        try {
            val name = "start_open_test"
            val file = context.preferencesDataStoreFile(name)
            val plain = androidx.datastore.preferences.core.stringPreferencesKey("entry")
            val bytes = okio.Buffer().also {
                runBlocking { androidx.datastore.preferences.core.PreferencesSerializer.writeTo(androidx.datastore.preferences.core.preferencesOf(plain to "kept"), it) }
            }.readByteArray()
            file.parentFile!!.mkdirs()
            file.writeBytes(bytes)
            val store = context.startOpenTestStore

            runBlocking { openStores(mapOf(name to store)) }

            assertTrue("encrypted on disk", LocalCryptoFormat.looksEncrypted(file.readBytes()))
            assertEquals("kept", runBlocking { store.data.first()[plain] })
        } finally {
            LocalCrypto.useKeyringForTest(null)
            keys.deleteRecursively()
        }
    }

    @Test
    fun `a Hidden password removed after a copy was set aside is known to be newer than it, whatever the clock says`() {
        val locked = LocalCrypto.lockedStores(context)
        locked.setAside("settings", byteArrayOf(1))
        val older = locked.pending("settings").single()
        LocalCrypto.markPasswordRemoved(context)
        // Set aside after the removal, under a clock set back a day.
        LockedStoreFiles(older.parentFile!!, clock = { System.currentTimeMillis() - 86_400_000L }).setAside("settings", byteArrayOf(2))
        val newer = locked.pending("settings").single { it != older }

        assertTrue(LocalCrypto.passwordRemovedAfter(context, older))
        assertFalse(LocalCrypto.passwordRemovedAfter(context, newer))
    }
}
