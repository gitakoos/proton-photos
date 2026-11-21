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
import androidx.test.core.app.ApplicationProvider
import eu.akoos.photos.data.db.encryption.DatabaseKeyStoreTest.FakeWrapper
import eu.akoos.photos.data.db.encryption.DatabaseOpenGate
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import javax.crypto.KeyGenerator

/** The one-time notice goes only to an install that held data from a version without local encryption. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LegacyDataNoticeTest {

    @get:Rule val tmp = TemporaryFolder()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val keysDir get() = File(context.noBackupFilesDir, "test_keys")
    private lateinit var files: File
    private lateinit var databases: File
    private lateinit var vault: File
    private val now = System.currentTimeMillis()

    @Before
    fun setUp() {
        files = tmp.newFolder("files")
        databases = tmp.newFolder("databases")
        vault = tmp.newFolder("vault")
        LocalCrypto.root(context).deleteRecursively()
    }

    @After
    fun tearDown() {
        LocalCrypto.useKeyringForTest(null)
        keysDir.deleteRecursively()
        LocalCrypto.root(context).deleteRecursively()
        context.getDatabasePath(DatabaseOpenGate.PLAIN_NAME).delete()
        VaultFiles.vaultDir(context).deleteRecursively()
    }

    private fun legacy() = legacyDataIn(files, databases, vault, processStartedAt = now)

    @Test
    fun `an empty install held nothing`() {
        assertFalse(legacy())
    }

    @Test
    fun `a plaintext database is legacy data`() {
        File(databases, DatabaseOpenGate.PLAIN_NAME).writeBytes(ByteArray(4096))
        assertTrue(legacy())
    }

    @Test
    fun `an encrypted database counts only when it predates this process`() {
        val db = File(databases, DatabaseOpenGate.ENCRYPTED_NAME).apply { writeBytes(ByteArray(4096)) }
        db.setLastModified(now + 5_000)
        assertFalse(legacy())
        db.setLastModified(now - 60_000)
        assertTrue(legacy())
    }

    @Test
    fun `a plaintext settings file is legacy data and an encrypted one is not`() {
        val settings = File(files, "datastore/settings.preferences_pb").apply { parentFile!!.mkdirs() }
        LocalCrypto.useKeyringForTest(
            LocalKeyring(keysDir, FakeWrapper(KeyGenerator.getInstance("AES").apply { init(256) }.generateKey())),
        )
        CacheFiles.write(settings, ByteArray(300) { 7 }, context = context)
        assertTrue(LocalCryptoFormat.isEncrypted(settings))
        assertFalse(legacy())

        settings.writeBytes(ByteArray(300) { 7 })
        assertTrue(legacy())
    }

    @Test
    fun `a file in the vault is legacy data`() {
        File(vault, "photo_1.jpg").writeBytes(ByteArray(10))
        assertTrue(legacy())
    }

    @Test
    fun `a fresh install is not given the notice`() {
        LocalCrypto.recordLegacyData(context)
        LocalCrypto.markMigrated(context)

        assertTrue(LocalCrypto.isMigrated(context))
        assertFalse(LocalCrypto.isNoticeDue(context))
    }

    @Test
    fun `an install with data from before is given the notice once`() {
        context.getDatabasePath(DatabaseOpenGate.PLAIN_NAME).apply { parentFile!!.mkdirs() }.writeBytes(ByteArray(4096))
        LocalCrypto.recordLegacyData(context)
        // The first answer stands after the database was moved.
        context.getDatabasePath(DatabaseOpenGate.PLAIN_NAME).delete()
        LocalCrypto.recordLegacyData(context)
        LocalCrypto.markMigrated(context)

        assertTrue(LocalCrypto.isNoticeDue(context))
        LocalCrypto.clearNotice(context)
        LocalCrypto.markMigrated(context)
        assertFalse(LocalCrypto.isNoticeDue(context))
    }
}
