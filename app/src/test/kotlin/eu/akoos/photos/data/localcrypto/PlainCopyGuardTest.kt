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
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import javax.crypto.KeyGenerator
import kotlin.random.Random

/**
 * Plaintext copies of local files are made from one open of the file, never from the file by its path, and no
 * bytes that start like a local encrypted file are ever handed on as a photo.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlainCopyGuardTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val keysDir get() = File(context.noBackupFilesDir, "guard_keys")
    private val vault get() = VaultFiles.vaultDir(context).apply { mkdirs() }
    private val fullres get() = LocalFiles.dir(context, LocalFiles.Area.FULLRES).apply { mkdirs() }

    @Before
    fun setUp() {
        LocalCrypto.useKeyringForTest(LocalKeyring(keysDir, FakeWrapper(KeyGenerator.getInstance("AES").apply { init(256) }.generateKey())))
        CacheFiles.init(context)
    }

    @After
    fun tearDown() {
        LocalFiles.afterOpenForTest = null
        LocalCrypto.useKeyringForTest(null)
        keysDir.deleteRecursively()
        vault.deleteRecursively()
        VaultFiles.workDir(context).deleteRecursively()
        fullres.deleteRecursively()
    }

    private fun encryptInPlace(file: File, plain: ByteArray) {
        val temp = File(file.parentFile, ".cache_race.tmp")
        FileOutputStream(temp).use { out -> LocalCryptoFormat.encryptingStream(out, CacheFiles.sealing()!!).use { it.write(plain) } }
        java.nio.file.Files.move(temp.toPath(), file.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
    }

    @Test
    fun `a plain vault file encrypted in place while it is copied out still comes out as its plaintext`() {
        val plain = Random(1).nextBytes(150_000)
        val file = File(vault, "IMG_1.jpg").apply { writeBytes(plain) }
        LocalFiles.afterOpenForTest = { encryptInPlace(file, plain) }

        val work = VaultFiles.materialize(context, file)

        assertArrayEquals(plain, work.readBytes())
        assertTrue("the vault file did go encrypted meanwhile", LocalFiles.isEncrypted(file))
    }

    @Test
    fun `a tool that needs a path always gets a scratch copy, never the plain file itself`() {
        val plain = Random(2).nextBytes(20_000)
        val file = File(vault, "IMG_2.jpg").apply { writeBytes(plain) }
        var seen: File? = null

        VaultFiles.withPlainFile(context, file) { work ->
            seen = work
            assertArrayEquals(plain, work.readBytes())
        }

        assertNotEquals(file, seen)
        assertFalse("deleted after use", seen!!.exists())
        assertArrayEquals(plain, file.readBytes())
    }

    @Test
    fun `a cached original read for an upload or a save is a scratch copy, even when it is plain`() {
        val plain = Random(3).nextBytes(20_000)
        val stored = File(fullres, "full_x.jpg").apply { writeBytes(plain) }
        LocalFiles.afterOpenForTest = { encryptInPlace(stored, plain) }

        val read = CachedOriginal(stored).withPlainFile(context) { work ->
            assertNotEquals(stored, work)
            work.readBytes()
        }

        assertArrayEquals(plain, read)
    }

    @Test
    fun `bytes that start like a local encrypted file are refused, a file and a stream alike`() {
        val encrypted = File(fullres, "full_enc.jpg").also { CacheFiles.write(it, Random(4).nextBytes(10_000)) }
        try {
            LocalCryptoFormat.requirePlain(encrypted)
            fail("an encrypted file must be refused")
        } catch (_: EncryptedBytesException) {
        }
        try {
            LocalCryptoFormat.guardPlain(encrypted.inputStream())
            fail("an encrypted stream must be refused")
        } catch (_: EncryptedBytesException) {
        }
    }

    @Test
    fun `a photo passes the check whole, a tiny one too`() {
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte()) + Random(5).nextBytes(5_000)
        assertArrayEquals(jpeg, LocalCryptoFormat.guardPlain(ByteArrayInputStream(jpeg)).readBytes())
        assertArrayEquals(byteArrayOf(1, 2), LocalCryptoFormat.guardPlain(ByteArrayInputStream(byteArrayOf(1, 2))).readBytes())
        val file = File(fullres, "full_plain.jpg").apply { writeBytes(jpeg) }
        LocalCryptoFormat.requirePlain(file)
        assertEquals(jpeg.size.toLong(), file.length())
    }
}
