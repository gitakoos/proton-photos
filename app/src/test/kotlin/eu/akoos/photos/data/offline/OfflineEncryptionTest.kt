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

package eu.akoos.photos.data.offline

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import eu.akoos.photos.data.db.encryption.DatabaseKeyStoreTest.FakeWrapper
import eu.akoos.photos.data.localcrypto.CacheFiles
import eu.akoos.photos.data.localcrypto.LocalCrypto
import eu.akoos.photos.data.localcrypto.LocalFiles
import eu.akoos.photos.data.localcrypto.LocalKeyUnavailableException
import eu.akoos.photos.data.localcrypto.LocalKeyring
import eu.akoos.photos.data.localcrypto.offlineMigration
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import javax.crypto.KeyGenerator
import kotlin.random.Random

/** Offline copies are kept encrypted: the ones an earlier version pinned in plain are encrypted in place. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OfflineEncryptionTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var keyring: LocalKeyring
    private val offline get() = LocalFiles.dir(context, LocalFiles.Area.OFFLINE).apply { mkdirs() }

    @Before
    fun setUp() {
        keyring = LocalKeyring(
            File(context.noBackupFilesDir, "offline_keys"),
            FakeWrapper(KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()),
        )
        LocalCrypto.useKeyringForTest(keyring)
    }

    @After
    fun tearDown() {
        LocalCrypto.useKeyringForTest(null)
        offline.deleteRecursively()
    }

    @Test
    fun `plain offline copies are encrypted in place and read back byte for byte, with nothing downloaded`() {
        val photos = mapOf("link1.jpg" to Random(1).nextBytes(150_000), "link2.mp4" to Random(2).nextBytes(10))
        photos.forEach { (name, bytes) -> File(offline, name).writeBytes(bytes) }
        val unfinished = File(offline, "link3.jpg.tmp").apply { writeBytes(byteArrayOf(1, 2, 3)) }

        val outcome = offlineMigration(context, keyring).run()

        assertEquals(2, outcome.encrypted)
        assertTrue(outcome.complete)
        for ((name, bytes) in photos) {
            val file = File(offline, name)
            assertTrue(name, LocalFiles.isEncrypted(file))
            assertArrayEquals(name, bytes, LocalFiles.readBytes(context, file))
        }
        assertFalse("an unfinished copy is not taken", LocalFiles.isEncrypted(unfinished))
        assertEquals(
            "nothing but the copies in the offline folder",
            setOf("link1.jpg", "link2.mp4", "link3.jpg.tmp"),
            offline.listFiles()!!.map { it.name }.toSet(),
        )
        assertNotNull(OfflineStorageManager(context).findBlob("link1"))
        assertEquals(0, offlineMigration(context, keyring).run().encrypted)
    }

    @Test
    fun `a pin from a plain source is stored encrypted`() = runTest {
        val bytes = Random(3).nextBytes(90_000)
        val source = File(context.cacheDir, "plain_source.jpg").apply { writeBytes(bytes) }

        val stored = OfflineStorageManager(context).store("pinA", source)

        assertTrue(LocalFiles.isEncrypted(stored))
        assertArrayEquals(bytes, LocalFiles.readBytes(context, stored))
        source.delete()
    }

    @Test
    fun `a pin from the encrypted cache is copied as it is`() = runTest {
        val bytes = Random(4).nextBytes(90_000)
        val source = File(context.cacheDir, "fullres/enc_source.jpg")
        CacheFiles.write(source, bytes, context = context)

        val stored = OfflineStorageManager(context).store("pinB", source)

        assertArrayEquals(source.readBytes(), stored.readBytes())
        assertArrayEquals(bytes, LocalFiles.readBytes(context, stored))
        source.delete()
    }

    @Test
    fun `a pin from a plain source writes nothing while no key answers`() = runTest {
        LocalCrypto.useKeyringForTest(
            LocalKeyring(
                File(context.noBackupFilesDir, "broken_keys"),
                FakeWrapper(null, failure = java.security.ProviderException("keystore busy")),
            ),
        )
        val source = File(context.cacheDir, "plain_source2.jpg").apply { writeBytes(Random(5).nextBytes(1_000)) }

        val failure = runCatching { OfflineStorageManager(context).store("pinC", source) }.exceptionOrNull()

        assertTrue(failure.toString(), failure is LocalKeyUnavailableException)
        assertTrue("no plaintext copy left behind", offline.listFiles().orEmpty().isEmpty())
        source.delete()
    }
}
