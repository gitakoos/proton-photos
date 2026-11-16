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
import org.junit.Assert.assertNotNull
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
    fun `a cache file damaged part way is deleted when the read reaches the damage, so it is fetched again`() {
        val file = File(thumbs, "thumb_mid.jpg")
        CacheFiles.write(file, Random(8).nextBytes(200_000), context = context)
        val damaged = file.readBytes().also { it[100_000] = (it[100_000] + 1).toByte() }
        file.writeBytes(damaged)

        val stream = LocalFiles.openForRead(context, file)
        assertNotNull("the first segment still opens", stream)
        val failure = runCatching { stream!!.use { it.readBytes() } }.exceptionOrNull()

        assertTrue(failure is java.io.IOException)
        assertFalse("a reader that checks for the file sees it gone, not unreadable", file.exists())
    }

    @Test
    fun `a vault file damaged part way is kept when a read reaches the damage`() {
        val vault = VaultFiles.vaultDir(context).apply { mkdirs() }
        val file = File(vault, "kept_mid.jpg")
        FileOutputStream(file).use { out ->
            LocalCryptoFormat.encryptingStream(out, sealing()).use { it.write(Random(9).nextBytes(200_000)) }
        }
        val damaged = file.readBytes().also { it[100_000] = (it[100_000] + 1).toByte() }
        file.writeBytes(damaged)

        runCatching { LocalFiles.openForRead(context, file)!!.use { it.readBytes() } }

        assertTrue(file.exists())
        file.delete()
    }

    /** Reads the whole stream like an image decoder that swallows a failed read and takes it as the end, then
     *  hands back a picture of what it got (a cut-short JPEG comes out grey at the bottom), or [result]. */
    private fun swallowingDecoder(result: () -> android.graphics.Bitmap?) =
        { input: java.io.InputStream, _: android.graphics.BitmapFactory.Options ->
            val cutShort = runCatching { input.readBytes() }.isFailure
            if (cutShort) android.graphics.Bitmap.createBitmap(4, 4, android.graphics.Bitmap.Config.ARGB_8888) else result()
        }

    @Test
    fun `an index decode that hits a damaged segment is a miss for now, never undecodable`() {
        val file = File(thumbs, "thumb_index.jpg")
        CacheFiles.write(file, Random(10).nextBytes(200_000), context = context)
        file.writeBytes(file.readBytes().also { it[100_000] = (it[100_000] + 1).toByte() })

        val decoded = LocalFiles.decodeChecked(
            context, file, android.graphics.BitmapFactory.Options(), swallowingDecoder { null },
        )

        assertNull("a picture of a read cut short is never handed back", decoded.bitmap)
        assertFalse(decoded.undecodable)
        assertFalse("deleted, so it is fetched again", file.exists())
    }

    @Test
    fun `an index decode that read the whole file and made nothing is undecodable`() {
        val file = File(thumbs, "thumb_bad.jpg")
        CacheFiles.write(file, Random(11).nextBytes(200_000), context = context)

        val decoded = LocalFiles.decodeChecked(
            context, file, android.graphics.BitmapFactory.Options(), swallowingDecoder { null },
        )

        assertTrue(decoded.undecodable)
        assertTrue(file.exists())
    }

    @Test
    fun `an index decode of a damaged vault file keeps the file and does not call it undecodable`() {
        val vault = VaultFiles.vaultDir(context).apply { mkdirs() }
        val file = File(vault, "index_mid.jpg")
        FileOutputStream(file).use { out ->
            LocalCryptoFormat.encryptingStream(out, sealing()).use { it.write(Random(12).nextBytes(200_000)) }
        }
        file.writeBytes(file.readBytes().also { it[100_000] = (it[100_000] + 1).toByte() })

        val decoded = LocalFiles.decodeChecked(
            context, file, android.graphics.BitmapFactory.Options(), swallowingDecoder { null },
        )

        assertFalse(decoded.undecodable)
        assertTrue(file.exists())
        file.delete()
    }

    @Test
    fun `temps an earlier process left mid-write are swept, a temp of this one and finished files are kept`() {
        val started = 1_800_000_000_000L
        val fullres = LocalFiles.dir(context, LocalFiles.Area.FULLRES).apply { mkdirs() }
        fun file(dir: File, name: String, modified: Long) = File(dir, name).apply {
            writeBytes(byteArrayOf(1, 2, 3))
            setLastModified(modified)
        }
        val stale = listOf(
            file(fullres, "dec_seal_123.tmp", started - 60_000),
            file(context.cacheDir, ".widget_tmp_9.tmp", started - 60_000),
            file(thumbs, "thumb_dec_abc_77.tmp", started - 60_000),
            file(thumbs, ".cache_5.tmp", started - 60_000),
        )
        val kept = listOf(
            file(fullres, "dec_seal_456.tmp", started + 1_000),
            file(fullres, "abc.jpg", started - 60_000),
            file(fullres, "dec_abc_0", started - 60_000),
            file(context.cacheDir, "other.tmp", started - 60_000),
        )

        CacheFiles.sweepWriteTemps(context, before = started)

        assertTrue(stale.none { it.exists() })
        assertTrue(kept.all { it.exists() })
    }

    @Test
    fun `plain cache files an earlier version left are encrypted in place and read the same`() {
        val started = 1_800_000_000_000L
        val fullres = LocalFiles.dir(context, LocalFiles.Area.FULLRES).apply { mkdirs() }
        val photo = Random(20).nextBytes(150_000)
        val thumb = Random(21).nextBytes(5_000)
        fun file(dir: File, name: String, bytes: ByteArray, modified: Long = started - 60_000) =
            File(dir, name).apply { writeBytes(bytes); setLastModified(modified) }
        val oldPhoto = file(fullres, "link1.jpg", photo)
        val oldThumb = file(thumbs, "thumb_link1.jpg", thumb)
        val hashes = file(fullres, "link1.hashes", byteArrayOf(9, 9))
        val scratch = file(fullres, "enc_link2_0", byteArrayOf(7))
        val writing = file(fullres, "link3.jpg", byteArrayOf(1, 2), modified = started + 1_000)

        assertTrue(CacheFiles.encryptPlainLeftovers(context, before = started))

        for ((f, bytes) in listOf(oldPhoto to photo, oldThumb to thumb)) {
            assertTrue(f.name, LocalFiles.isEncrypted(f))
            assertArrayEquals(bytes, LocalFiles.readBytes(context, f))
            assertEquals("its time is kept for the cache's age sweep", started - 60_000, f.lastModified() / 1000 * 1000)
        }
        assertFalse(LocalFiles.isEncrypted(hashes))
        assertFalse(LocalFiles.isEncrypted(scratch))
        assertFalse("a file of this process is left to its writer", LocalFiles.isEncrypted(writing))
        assertTrue("no temp left", fullres.listFiles()!!.none { it.name.endsWith(".tmp") })
        listOf(oldPhoto, hashes, scratch, writing).forEach { it.delete() }
    }

    @Test
    fun `plain cache files stay as they are while there is no room, and the run is not done`() {
        val started = 1_800_000_000_000L
        val old = File(thumbs, "thumb_room.jpg").apply { writeBytes(ByteArray(1_000)); setLastModified(started - 60_000) }

        assertFalse(CacheFiles.encryptPlainLeftovers(context, before = started, freeBytes = { 1L }))

        assertFalse(LocalFiles.isEncrypted(old))
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
    fun `where the platform has no Keystore a cache file is written as it is`() {
        LocalCrypto.useKeyringForTest(null)
        val bytes = Random(5).nextBytes(2_000)
        val file = File(thumbs, "thumb_plain.jpg")

        CacheFiles.write(file, bytes)

        assertArrayEquals(bytes, file.readBytes())
    }

    @Test
    fun `a Keystore that does not answer leaves nothing cached rather than a plaintext file`() {
        val broken = LocalKeyring(
            File(context.noBackupFilesDir, "other_keys"),
            FakeWrapper(null, failure = java.security.ProviderException("keystore busy")),
        )
        LocalCrypto.useKeyringForTest(broken)
        val file = File(thumbs, "thumb_nokey.jpg")

        val writeFailure = runCatching { CacheFiles.write(file, Random(7).nextBytes(2_000), context = context) }.exceptionOrNull()
        val streamFailure = runCatching {
            CacheFiles.encryptingStream(FileOutputStream(File(thumbs, "stream_nokey.bin")), context)
        }.exceptionOrNull()

        assertTrue(writeFailure is LocalKeyUnavailableException)
        assertTrue(streamFailure is LocalKeyUnavailableException)
        assertFalse(file.exists())
        assertTrue("no temp or partial file is left", thumbs.listFiles()!!.none { it.length() > 0L })
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
