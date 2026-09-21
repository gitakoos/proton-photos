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

package eu.akoos.photos.data.hidden

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import eu.akoos.photos.util.HiddenCaptureTime
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
 * What moving a vault file on disk may and may not destroy.
 *
 * The vault is a flat directory of app-private files, and BOTH edits a vaulted photo can take —
 * renaming it, and rewriting the capture time its name carries — are the same move of one file to a
 * new name beside itself. That makes a name collision the one way an edit could overwrite a second
 * photo, and nothing could get that one back: a vault file is the only copy there is, the hide having
 * deleted the device original.
 *
 * So the move refuses a name another file holds, and answers with the file's own uri for a name that
 * resolves to the file itself, which is a photo already in the state the user asked for rather than a
 * collision. Everything here is plain file IO over `filesDir/hidden`, so a Robolectric temp directory
 * answers for all of it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HiddenStorageManagerTest {

    private lateinit var context: Context
    private lateinit var storage: HiddenStorageManager

    private val captureMs = 1_783_507_135_000L
    private val earlier = 1_700_000_000_000L

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        storage = HiddenStorageManager(context)
        // Robolectric can reuse filesDir across tests in a run; start every case from an empty vault.
        storage.clearVault()
    }

    private fun hiddenDir() = File(context.filesDir, "hidden")

    /** A vault file holding [bytes] under [captureTimeMs], written the one way content with no source
     *  file reaches the vault: a private code for a stem and the capture time in the name. */
    private suspend fun vaultFile(
        extension: String = "jpg",
        captureTimeMs: Long? = captureMs,
        bytes: ByteArray = byteArrayOf(1, 2, 3, 4),
    ): String = storage.create(extension, captureTimeMs) { out -> out.write(bytes) }!!

    private fun fileOf(uri: String): File = File(Uri.parse(uri).path!!)

    private fun vaultNames(): List<String> = hiddenDir().listFiles()?.map { it.name }?.sorted() ?: emptyList()

    // ── a rename that would land on another photo ────────────────────────────────────────────────

    @Test
    fun `a rename onto a free name moves the file and nothing else`() = runBlocking {
        val uri = vaultFile()

        val renamed = storage.rename(uri, "Beach trip")

        assertNotNull(renamed)
        assertEquals("Beach trip__$captureMs.jpg", fileOf(renamed!!).name)
        assertFalse("the file it moved from must be gone", fileOf(uri).exists())
        assertEquals(listOf("Beach trip__$captureMs.jpg"), vaultNames())
    }

    @Test
    fun `a rename onto the name a second photo holds is refused and destroys neither`() = runBlocking {
        val taken = storage.rename(vaultFile(bytes = byteArrayOf(9, 9)), "Beach trip")!!
        val mine = storage.rename(vaultFile(bytes = byteArrayOf(1, 2, 3, 4)), "Sunset")!!

        assertNull("the second photo is the only copy there is", storage.rename(mine, "Beach trip"))

        assertTrue(fileOf(mine).exists())
        assertArrayEquals(byteArrayOf(9, 9), fileOf(taken).readBytes())
        assertEquals(2, vaultNames().size)
    }

    @Test
    fun `a rename onto the name the photo already carries answers with the file it is`() = runBlocking {
        // The stem, the capture time and the extension all land back where they were, so the move is
        // of no distance. The user asked for the state the photo is already in.
        val uri = storage.rename(vaultFile(), "Beach trip")!!

        val again = storage.rename(uri, "Beach trip")

        assertEquals(uri, again)
        assertTrue(fileOf(uri).exists())
        assertEquals(1, vaultNames().size)
    }

    @Test
    fun `a rename carries the capture time and the container the file already had`() = runBlocking {
        val uri = vaultFile(extension = "mp4")

        val renamed = storage.rename(uri, "Holiday clip.mov")!!

        // The extension the user typed is dropped: the file keeps its own container, which is what
        // every mime lookup reads back.
        assertEquals("Holiday clip__$captureMs.mp4", fileOf(renamed).name)
        assertEquals(captureMs, HiddenCaptureTime.parse(fileOf(renamed).name))
    }

    @Test
    fun `a rename of a uri naming no vault file answers with nothing`() = runBlocking {
        val gone = Uri.fromFile(File(hiddenDir(), "never_here.jpg")).toString()

        assertNull(storage.rename(gone, "Beach trip"))
    }

    // ── the same move, made by a capture-date edit ───────────────────────────────────────────────

    @Test
    fun `a date edit onto the date already recorded answers with the uri it was given`() = runBlocking {
        // The name would come out identical, so the move never reaches the disk at all.
        val uri = vaultFile()

        assertEquals(uri, storage.restampCaptureTime(uri, captureMs))
        assertTrue(fileOf(uri).exists())
    }

    @Test
    fun `a date edit to another date moves the file and rewrites only the time`() = runBlocking {
        val uri = storage.rename(vaultFile(), "Beach trip")!!

        val restamped = storage.restampCaptureTime(uri, earlier)!!

        assertEquals("Beach trip__$earlier.jpg", fileOf(restamped).name)
        assertFalse(fileOf(uri).exists())
        assertEquals(earlier, HiddenCaptureTime.parse(fileOf(restamped).name))
    }

    @Test
    fun `a date edit that would land on a second photo's name is refused`() = runBlocking {
        // Two photos sharing a stem is ordinary — the same name from two folders, or a copy — and
        // then only the capture time tells the files apart. Moving one onto the other's date is the
        // collision, and the second photo's bytes are what is at stake.
        val other = storage.rename(vaultFile(bytes = byteArrayOf(9, 9)), "Beach trip")!!
        val restampedOther = storage.restampCaptureTime(other, earlier)!!
        val mine = storage.rename(vaultFile(bytes = byteArrayOf(1, 2, 3, 4)), "Beach trip")!!

        assertNull(storage.restampCaptureTime(mine, earlier))

        assertTrue(fileOf(mine).exists())
        assertArrayEquals(byteArrayOf(9, 9), fileOf(restampedOther).readBytes())
        assertEquals(2, vaultNames().size)
    }

    @Test
    fun `a date edit gives a name recording no date one to carry`() = runBlocking {
        val uri = vaultFile(captureTimeMs = null)
        assertNull(HiddenCaptureTime.parse(fileOf(uri).name))

        val restamped = storage.restampCaptureTime(uri, captureMs)!!

        assertEquals(captureMs, HiddenCaptureTime.parse(fileOf(restamped).name))
        assertEquals(1, vaultNames().size)
    }
}
