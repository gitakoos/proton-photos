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

package eu.akoos.photos.data.upload

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [LocalOverwriteGuard] against the real content resolver: the backup, the byte comparison that tells
 * a refused write from a truncating one, the restore and the image check. Files stand in for the
 * photo, so the check on the rewritten image is exercised through a size the stand-in cannot satisfy.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LocalOverwriteGuardTest {

    private lateinit var context: Context
    private lateinit var photo: File
    private val original = ByteArray(300_000) { (it % 251).toByte() }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        photo = File(context.cacheDir, "guarded.jpg").also { it.writeBytes(original) }
        journalDir().deleteRecursively()
        LocalRewrites.clear()
    }

    private fun journalDir() = File(context.filesDir, MirrorOverwriteJournal.DIR_NAME)

    private fun journalFiles() = journalDir().listFiles()?.size ?: 0

    private fun guard() = LocalOverwriteGuard(context)

    private val uri get() = Uri.fromFile(photo).toString()

    @Test
    fun `an edit that completes replaces the bytes and leaves no backup behind`() {
        val result = guard().run(uri) {
            context.contentResolver.openOutputStream(Uri.parse(uri), "wt")!!.use { it.write("edited".toByteArray()) }
            7
        }
        assertEquals(7, result)
        assertEquals("edited", photo.readText())
        assertEquals(0, journalFiles())
    }

    @Test
    fun `an edit that empties the file and fails puts the original back`() {
        val thrown = runCatching {
            guard().run(uri) {
                context.contentResolver.openOutputStream(Uri.parse(uri), "wt")!!.use { it.write(1) }
                throw IOException("process out of space")
            }
        }.exceptionOrNull()
        assertTrue(thrown is IOException)
        assertTrue(photo.readBytes().contentEquals(original))
        assertEquals(0, journalFiles())
    }

    @Test
    fun `a refused write leaves the file alone and still reports the refusal`() {
        val thrown = runCatching { guard().run<Unit>(uri) { throw SecurityException("needs consent") } }
            .exceptionOrNull()
        assertTrue(thrown is SecurityException)
        assertTrue(photo.readBytes().contentEquals(original))
        assertEquals(0, journalFiles())
    }

    @Test
    fun `an edit whose result is not an image of the wanted size is rolled back`() {
        val thrown = runCatching {
            guard().run(uri, expectedSize = 10 to 10) {
                context.contentResolver.openOutputStream(Uri.parse(uri), "wt")!!.use { it.write("not an image".toByteArray()) }
            }
        }.exceptionOrNull()
        assertTrue(thrown is IOException)
        assertTrue(photo.readBytes().contentEquals(original))
        assertEquals(0, journalFiles())
    }

    @Test
    fun `a completed rewrite is noted with the size it left`() {
        guard().run(uri) {
            context.contentResolver.openOutputStream(Uri.parse(uri), "wt")!!.use { it.write("edited".toByteArray()) }
        }
        assertFalse(LocalRewrites.consume(uri, original.size.toLong()))

        guard().run(uri) {
            context.contentResolver.openOutputStream(Uri.parse(uri), "wt")!!.use { it.write("edited again".toByteArray()) }
        }
        assertTrue(LocalRewrites.consume(uri, "edited again".length.toLong()))
        assertFalse("a note is spent once", LocalRewrites.consume(uri, "edited again".length.toLong()))
    }

    @Test
    fun `a rolled-back rewrite is not noted`() {
        runCatching {
            guard().run(uri) {
                context.contentResolver.openOutputStream(Uri.parse(uri), "wt")!!.use { it.write(1) }
                throw IOException("process out of space")
            }
        }
        runCatching { guard().run<Unit>(uri) { throw SecurityException("needs consent") } }

        assertFalse(LocalRewrites.consume(uri, 1L))
        assertFalse(LocalRewrites.consume(uri, original.size.toLong()))
    }

    @Test
    fun `a file that cannot be read is never edited`() {
        val missing = Uri.fromFile(File(context.cacheDir, "gone.jpg")).toString()
        var ran = false
        val thrown = runCatching { guard().run(missing) { ran = true } }.exceptionOrNull()
        assertTrue(thrown is MirrorOverwriteJournal.StagingFailedException)
        assertFalse(ran)
        assertEquals(0, journalFiles())
    }
}
