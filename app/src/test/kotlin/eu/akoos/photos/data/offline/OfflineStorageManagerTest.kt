/*
 * Photos for Proton
 * Copyright (C) 2026 Akoos <https://akoos.eu>
 *
 * Source:  https://github.com/gitakoos/proton-photos
 * Website: https://photos.akoos.eu
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
import kotlinx.coroutines.runBlocking
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
import java.io.File

/**
 * Coverage for [OfflineStorageManager] — the sandbox blob store behind "make available offline".
 * It is pure on-disk file IO over `filesDir/offline`, so it runs under Robolectric with a real
 * temp filesDir. These tests stand in for the part of the pin flow that runs AFTER the download:
 * once the full-res blob is fetched, store→findBlob→isOffline is what makes the photo load from
 * disk, and clearAll/delete are what the sign-out wipe and un-pin rely on.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OfflineStorageManagerTest {

    private lateinit var context: Context
    private lateinit var store: OfflineStorageManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        store = OfflineStorageManager(context)
        // Robolectric can reuse filesDir across tests in a run; start every case from empty.
        store.clearAll()
    }

    /** A cache-side source file (what downloadFullResPhoto hands back) with [ext] and [bytes].
     *  The `src_` prefix keeps createTempFile's 3-char minimum happy for short names. */
    private fun source(name: String, ext: String, bytes: ByteArray): File =
        File.createTempFile("src_$name", ".$ext", context.cacheDir).apply { writeBytes(bytes) }

    private fun offlineDir() = File(context.filesDir, "offline")

    @Test
    fun `store then findBlob returns the blob and isOffline becomes true`() = runBlocking {
        assertFalse(store.isOffline("link1"))
        val dest = store.store("link1", source("s", "jpg", byteArrayOf(1, 2, 3, 4)))

        assertTrue(dest.exists())
        assertEquals("link1.jpg", dest.name)
        assertTrue(store.isOffline("link1"))
        assertEquals(dest, store.findBlob("link1"))
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), store.findBlob("link1")!!.readBytes())
    }

    @Test
    fun `findBlob locates the blob under any extension`() = runBlocking {
        store.store("vid", source("s", "mp4", byteArrayOf(9, 9, 9)))
        val found = store.findBlob("vid")
        assertNotNull(found)
        assertEquals("vid.mp4", found!!.name)
    }

    @Test
    fun `findBlob is null and isOffline false for an unknown linkId`() {
        assertNull(store.findBlob("missing"))
        assertFalse(store.isOffline("missing"))
    }

    @Test
    fun `an interrupted store leaves a tmp that is never treated as a real blob`() {
        // A crash between copy and rename would leave only a `.tmp`; findBlob must ignore it so the
        // viewer falls back to the network rather than serving a truncated file.
        offlineDir().mkdirs()
        File(offlineDir(), "halfpin.jpg.tmp").writeBytes(byteArrayOf(1, 2, 3))
        assertNull(store.findBlob("halfpin"))
        assertFalse(store.isOffline("halfpin"))
    }

    @Test
    fun `a zero-length blob does not count as offline`() = runBlocking {
        store.store("empty", source("e", "jpg", ByteArray(0)))
        assertFalse(store.isOffline("empty"))
        assertNull(store.findBlob("empty"))
    }

    @Test
    fun `delete removes the blob for one linkId only`() = runBlocking {
        store.store("a", source("a", "jpg", byteArrayOf(1)))
        store.store("b", source("b", "jpg", byteArrayOf(2, 2)))

        store.delete("a")

        assertNull(store.findBlob("a"))
        assertNotNull(store.findBlob("b"))
    }

    @Test
    fun `re-storing a linkId overwrites with the latest bytes and keeps a single blob`() = runBlocking {
        store.store("x", source("x", "jpg", byteArrayOf(1, 1)))
        store.store("x", source("x2", "jpg", byteArrayOf(2, 2, 2)))

        assertArrayEquals(byteArrayOf(2, 2, 2), store.findBlob("x")!!.readBytes())
        assertEquals(1, offlineDir().listFiles { f -> f.name.startsWith("x.") }?.size)
    }

    @Test
    fun `re-pinning under a different extension leaves a single current blob`() = runBlocking {
        store.store("m", source("m", "jpg", byteArrayOf(1, 1)))
        store.store("m", source("m2", "png", byteArrayOf(2, 2, 2)))

        // The old .jpg must be gone — exactly one blob remains and it is the new .png.
        val blobs = offlineDir().listFiles { f -> f.name.startsWith("m.") }
        assertEquals(1, blobs?.size)
        assertEquals("m.png", blobs!![0].name)
        assertArrayEquals(byteArrayOf(2, 2, 2), store.findBlob("m")!!.readBytes())
    }

    @Test
    fun `computeSizeBytes is zero when empty and sums every blob`() = runBlocking {
        assertEquals(0L, store.computeSizeBytes())
        store.store("a", source("a", "jpg", ByteArray(10)))
        store.store("b", source("b", "png", ByteArray(25)))
        assertEquals(35L, store.computeSizeBytes())
    }

    @Test
    fun `clearAll wipes every blob (the sign-out and clear-offline path)`() = runBlocking {
        store.store("a", source("a", "jpg", byteArrayOf(1)))
        store.store("b", source("b", "jpg", byteArrayOf(2)))

        store.clearAll()

        assertEquals(0L, store.computeSizeBytes())
        assertNull(store.findBlob("a"))
        assertNull(store.findBlob("b"))
    }
}
