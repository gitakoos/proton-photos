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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import javax.crypto.KeyGenerator
import kotlin.random.Random

/** The thumbnail, full-size and motion caches: written encrypted, read in either form, refetched when they fail. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CacheFilesTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val keysDir get() = File(context.noBackupFilesDir, "test_keys")
    private lateinit var keyring: LocalKeyring

    private fun newKeyring(dir: File = keysDir) =
        LocalKeyring(dir, FakeWrapper(KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()))

    private val thumbs get() = LocalFiles.dir(context, LocalFiles.Area.THUMBNAILS).apply { mkdirs() }

    @Before
    fun setUp() {
        keyring = newKeyring()
        LocalCrypto.useKeyringForTest(keyring)
        CacheFiles.init(context)
    }

    @After
    fun tearDown() {
        LocalCrypto.useKeyringForTest(null)
        keysDir.deleteRecursively()
        File(context.noBackupFilesDir, "other_keys").deleteRecursively()
        LocalFiles.Area.entries.forEach { LocalFiles.dir(context, it).deleteRecursively() }
    }

    @Test
    fun `a cache file is written encrypted and reads back whole`() {
        val bytes = Random(1).nextBytes(45_000)
        val file = File(thumbs, "thumb_a.jpg")

        CacheFiles.write(file, bytes)

        assertTrue(LocalCryptoFormat.isEncrypted(file))
        assertFalse(file.readBytes().contentEquals(bytes))
        assertArrayEquals(bytes, CacheFiles.readBytesOrNull(file))
        assertArrayEquals(bytes, LocalFiles.openForRead(context, file)!!.use { it.readBytes() })
        assertEquals(bytes.size.toLong(), LocalFiles.plainLength(file))
        assertTrue(thumbs.listFiles()!!.all { it.name == "thumb_a.jpg" })
    }

    @Test
    fun `a cache file an earlier version wrote in plaintext still reads`() {
        val bytes = Random(2).nextBytes(10_000)
        val file = File(thumbs, "thumb_old.jpg").apply { writeBytes(bytes) }

        assertArrayEquals(bytes, CacheFiles.readBytesOrNull(file))
        assertArrayEquals(bytes, LocalFiles.openForRead(context, file)!!.use { it.readBytes() })
        assertEquals(bytes.size.toLong(), LocalFiles.plainLength(file))
    }

    @Test
    fun `a cache file whose key is gone is deleted and reads as missing`() {
        val file = File(thumbs, "thumb_lost.jpg")
        CacheFiles.write(file, Random(3).nextBytes(5_000))
        LocalCrypto.useKeyringForTest(newKeyring(File(context.noBackupFilesDir, "other_keys")))

        assertNull(LocalFiles.openForRead(context, file))
        assertFalse(file.exists())
    }

    @Test
    fun `a damaged cache file is deleted and reads as missing`() {
        val file = File(thumbs, "thumb_bad.jpg")
        CacheFiles.write(file, Random(4).nextBytes(5_000))
        val damaged = file.readBytes().also { it[it.size - 20] = (it[it.size - 20] + 1).toByte() }
        file.writeBytes(damaged)

        assertNull(CacheFiles.readBytesOrNull(file))
        assertFalse(file.exists())
    }

    @Test
    fun `a vault file that does not open is never deleted by a reader`() {
        val vault = VaultFiles.vaultDir(context).apply { mkdirs() }
        val file = File(vault, "kept.jpg")
        FileOutputStream(file).use { out ->
            LocalCryptoFormat.encryptingStream(out, sealing()).use { it.write(ByteArray(3_000) { 5 }) }
        }
        LocalCrypto.useKeyringForTest(newKeyring(File(context.noBackupFilesDir, "other_keys")))

        assertNull(LocalFiles.openForRead(context, file))
        assertTrue(file.exists())
    }

    @Test
    fun `with no key a cache file is written as it is`() {
        LocalCrypto.useKeyringForTest(null)
        val bytes = Random(5).nextBytes(2_000)
        val file = File(thumbs, "thumb_plain.jpg")

        CacheFiles.write(file, bytes)

        assertArrayEquals(bytes, file.readBytes())
    }

    @Test
    fun `the encrypting stream counts the photo's bytes, not the file's`() {
        val bytes = Random(6).nextBytes(200_000)
        val file = File(LocalFiles.dir(context, LocalFiles.Area.FULLRES).apply { mkdirs() }, "p.jpg")

        val counted = CacheFiles.encryptingStream(FileOutputStream(file)).use { it.write(bytes); it }.plainBytes

        assertEquals(bytes.size.toLong(), counted)
        assertTrue(file.length() > counted)
        val original = CachedOriginal(file)
        assertEquals(bytes.size.toLong(), original.plainLength)
        assertArrayEquals(bytes, original.open().use { it.readBytes() })
        val copy = File(context.cacheDir, "copy.jpg")
        assertEquals(bytes.size.toLong(), original.copyPlainTo(copy))
        assertArrayEquals(bytes, copy.readBytes())
        copy.delete()
    }

    @Test
    fun `thumbnail write and read cost, plain against encrypted`() {
        val thumbsBytes = List(200) { Random(it).nextBytes(40_000) }
        fun run(encrypted: Boolean): Pair<Long, Long> {
            LocalCrypto.useKeyringForTest(if (encrypted) keyring else null)
            thumbs.deleteRecursively()
            val files = thumbsBytes.indices.map { File(thumbs, "thumb_$it.jpg") }
            val w0 = System.nanoTime()
            files.forEachIndexed { i, f -> CacheFiles.write(f, thumbsBytes[i]) }
            val w1 = System.nanoTime()
            val sink = ByteArrayOutputStream()
            files.forEach { f -> LocalFiles.openForRead(context, f)!!.use { it.copyTo(sink) }; sink.reset() }
            val r1 = System.nanoTime()
            return (w1 - w0) to (r1 - w1)
        }
        run(false); run(true) // warm up
        val plain = run(false)
        val sealed = run(true)
        println(
            "thumbnail cost per 40 KB file: write plain ${plain.first / 200_000} us, encrypted ${sealed.first / 200_000} us; " +
                "read plain ${plain.second / 200_000} us, encrypted ${sealed.second / 200_000} us",
        )
    }

    private fun sealing(): LocalCryptoFormat.Sealing {
        val current = keyring.current()
        return LocalCryptoFormat.Sealing(current.keyId, current.key, LocalFileKind.VAULT)
    }
}
