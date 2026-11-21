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

package eu.akoos.photos.util

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import eu.akoos.photos.data.db.encryption.DatabaseKeyStoreTest.FakeWrapper
import eu.akoos.photos.data.localcrypto.LocalCrypto
import eu.akoos.photos.data.localcrypto.LocalFiles
import eu.akoos.photos.data.localcrypto.LocalKeyring
import eu.akoos.photos.data.localcrypto.VaultFiles
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import javax.crypto.KeyGenerator
import kotlin.random.Random

/** Stripped share copies: encrypted, in the scratch folder emptied at every start, never in the full-size cache. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ShareCopyTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        val keys = File(context.noBackupFilesDir, "share_copy_keys")
        LocalCrypto.useKeyringForTest(
            LocalKeyring(keys, FakeWrapper(KeyGenerator.getInstance("AES").apply { init(256) }.generateKey())),
        )
    }

    @After
    fun tearDown() {
        LocalCrypto.useKeyringForTest(null)
        strippedShareDir(context).deleteRecursively()
    }

    @Test
    fun `a stripped copy is kept encrypted in the share folder`() {
        val dir = strippedShareDir(context)
        val work = File(dir, "work").apply { mkdirs() }
        val bytes = Random(1).nextBytes(150_000)
        val plain = File(work, "stripped_1.jpg").apply { writeBytes(bytes) }

        val copy = sealShareCopy(context, plain, dir)

        assertEquals(File(context.cacheDir, VaultFiles.SHARE_DIR), copy.parentFile)
        assertFalse("the plain strip is gone", plain.exists())
        assertTrue(LocalFiles.isEncrypted(copy))
        assertArrayEquals(bytes, LocalFiles.readBytes(context, copy))
    }

    @Test
    fun `a share copy outlives a restart for a day, an unfinished one does not`() {
        val dir = strippedShareDir(context).apply { mkdirs() }
        val now = 1_800_000_000_000L
        val fresh = File(dir, "annotated_1x1.jpg").apply { writeBytes(byteArrayOf(1)); setLastModified(now - 60 * 60_000L) }
        val old = File(dir, "stripped_old.jpg").apply { writeBytes(byteArrayOf(1)); setLastModified(now - SHARE_COPY_KEEP_MS - 1) }
        val unfinished = File(File(dir, "work").apply { mkdirs() }, "stripped_cut.mp4").apply { writeBytes(byteArrayOf(1)) }

        VaultFiles.clearScratch(context, now)

        assertTrue("a receiver may still read it after the restart", fresh.exists())
        assertFalse(old.exists())
        assertFalse(unfinished.exists())
    }

    @Test
    fun `a stripped copy goes once it is older than the keep time, a fresh one stays`() {
        val dir = strippedShareDir(context).apply { mkdirs() }
        val now = 1_800_000_000_000L
        val old = File(dir, "stripped_old.jpg").apply { writeBytes(byteArrayOf(1)); setLastModified(now - SHARE_COPY_KEEP_MS - 1) }
        val unfinished = File(File(dir, "work").apply { mkdirs() }, "stripped_cut.mp4")
            .apply { writeBytes(byteArrayOf(1)); setLastModified(now - SHARE_COPY_KEEP_MS - 1) }
        val fresh = File(dir, "stripped_new.jpg").apply { writeBytes(byteArrayOf(1)); setLastModified(now - 60_000) }

        VaultFiles.sweepShareCopies(dir, now - SHARE_COPY_KEEP_MS)

        assertFalse(old.exists())
        assertFalse(unfinished.exists())
        assertTrue(fresh.exists())
    }

    @Test
    fun `share copies an earlier version left in the full-size cache are removed, cached photos are kept`() {
        val fullres = File(context.cacheDir, "fullres").apply { mkdirs() }
        val started = 1_800_000_000_000L
        val leftover = File(fullres, "stripped_123.jpg").apply { writeBytes(byteArrayOf(1)); setLastModified(started - 60_000) }
        val cached = File(fullres, "aBc123.jpg").apply { writeBytes(byteArrayOf(1)); setLastModified(started - 60_000) }

        sweepLegacyShareCopies(context, before = started)

        assertFalse(leftover.exists())
        assertTrue(cached.exists())
        cached.delete()
    }

    @Test
    fun `a copy made to be shared is written encrypted into the scratch folder`() {
        val bytes = Random(2).nextBytes(70_000)

        val copy = makeShareCopy(context, "annotated_1x1.jpg") { it.write(bytes) }

        assertEquals(strippedShareDir(context), copy.parentFile)
        assertTrue(copy.name, copy.name.startsWith("annotated_1x1_") && copy.name.endsWith(".jpg"))
        assertTrue(LocalFiles.isEncrypted(copy))
        assertArrayEquals(bytes, LocalFiles.readBytes(context, copy))
        assertTrue(File(strippedShareDir(context), "work").listFiles().orEmpty().isEmpty())
    }

    @Test
    fun `two shares of the same size never write over each other`() {
        val first = Random(3).nextBytes(20_000)
        val second = Random(4).nextBytes(20_000)

        val a = makeShareCopy(context, "annotated_1x1.jpg") { it.write(first) }
        val b = makeShareCopy(context, "annotated_1x1.jpg") { it.write(second) }

        assertTrue(a != b)
        assertArrayEquals(first, LocalFiles.readBytes(context, a))
        assertArrayEquals(second, LocalFiles.readBytes(context, b))
    }

    @Test
    fun `a stripped copy sealed under a name already in the share folder gets a name of its own`() {
        val dir = strippedShareDir(context)
        val work = File(dir, "work").apply { mkdirs() }
        val earlier = File(dir, "stripped_1.jpg").apply { writeBytes(byteArrayOf(9)) }

        val copy = sealShareCopy(context, File(work, "stripped_1.jpg").apply { writeBytes(byteArrayOf(1, 2)) }, dir)

        assertTrue(copy != earlier)
        assertArrayEquals(byteArrayOf(9), earlier.readBytes())
    }

    @Test
    fun `the share provider serves the scratch folder`() {
        val paths = context.resources.getXml(eu.akoos.photos.R.xml.share_paths)
        val served = mutableListOf<String>()
        while (paths.next() != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
            if (paths.eventType == org.xmlpull.v1.XmlPullParser.START_TAG && paths.name == "cache-path") {
                served += paths.getAttributeValue(null, "path")
            }
        }
        assertTrue(served.toString(), "${VaultFiles.SHARE_DIR}/" in served)
    }
}
