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
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import eu.akoos.photos.data.db.encryption.DatabaseKeyStoreTest.FakeWrapper
import eu.akoos.photos.data.repository.drive.isRetryableUploadFailure
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import javax.crypto.KeyGenerator
import kotlin.random.Random

/** Every upload and content hash reads through one check, and a refusal is final and leaves nothing behind. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlainContentTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val keysDir get() = File(context.noBackupFilesDir, "plain_content_keys")
    private val dir get() = LocalFiles.dir(context, LocalFiles.Area.FULLRES).apply { mkdirs() }

    @Before
    fun setUp() {
        LocalCrypto.useKeyringForTest(LocalKeyring(keysDir, FakeWrapper(KeyGenerator.getInstance("AES").apply { init(256) }.generateKey())))
        CacheFiles.init(context)
    }

    @After
    fun tearDown() {
        LocalCrypto.useKeyringForTest(null)
        keysDir.deleteRecursively()
        dir.deleteRecursively()
    }

    private fun sha1(bytes: ByteArray) = MessageDigest.getInstance("SHA-1").digest(bytes).joinToString("") { "%02x".format(it) }

    @Test
    fun `a photo hashes as it is, by file and by uri`() {
        val bytes = Random(1).nextBytes(30_000)
        val file = File(dir, "plain.jpg").apply { writeBytes(bytes) }

        assertEquals(sha1(bytes), PlainContent.sha1Hex(file))
        assertEquals(sha1(bytes), PlainContent.sha1Hex(context, Uri.fromFile(file).toString()))
        PlainContent.requirePlain(context, Uri.fromFile(file).toString())
    }

    @Test
    fun `local encrypted bytes are refused by the hash and the upload source check alike`() {
        val file = File(dir, "enc.jpg").also { CacheFiles.write(it, Random(2).nextBytes(30_000)) }
        val uri = Uri.fromFile(file).toString()

        for (attempt in listOf<() -> Unit>(
            { PlainContent.sha1Hex(file) },
            { PlainContent.sha1Hex(context, uri) },
            { PlainContent.requirePlain(context, uri) },
            { PlainContent.open(context, uri) },
        )) {
            try {
                attempt()
                fail("local encrypted bytes must be refused")
            } catch (_: EncryptedBytesException) {
            }
        }
        assertTrue("the source is kept", file.exists())
    }

    @Test
    fun `a refusal is a final failure for that upload, never a retry`() {
        assertFalse(isRetryableUploadFailure(EncryptedBytesException()))
        assertFalse(isRetryableUploadFailure(IllegalStateException("upload", EncryptedBytesException())))
        assertTrue("other read errors still retry", isRetryableUploadFailure(IOException("read error")))
    }

    @Test
    fun `a gallery row made for a copy is rolled back when the copy is refused or fails`() {
        var rolledBack = 0
        try {
            PlainContent.intoNewRow(rollBack = { rolledBack++ }) { throw EncryptedBytesException() }
            fail("the refusal is thrown on")
        } catch (_: EncryptedBytesException) {
        }
        try {
            PlainContent.intoNewRow(rollBack = { rolledBack++ }) { throw IOException("disk full") }
            fail("the failure is thrown on")
        } catch (_: IOException) {
        }
        assertEquals(2, rolledBack)
        assertEquals(7, PlainContent.intoNewRow(rollBack = { rolledBack++ }) { 7 })
        assertEquals("a copy that worked keeps its row", 2, rolledBack)
    }
}
