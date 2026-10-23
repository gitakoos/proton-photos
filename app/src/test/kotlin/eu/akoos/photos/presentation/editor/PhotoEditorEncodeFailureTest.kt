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

package eu.akoos.photos.presentation.editor

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import eu.akoos.photos.data.upload.LocalOverwriteGuard
import eu.akoos.photos.data.upload.MirrorOverwriteJournal
import java.io.File
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PhotoEditorEncodeFailureTest {

    private lateinit var context: Context
    private lateinit var photo: File
    private val original = ByteArray(200_000) { (it % 241).toByte() }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        photo = File(context.cacheDir, "edited.jpg").also { it.writeBytes(original) }
        File(context.filesDir, MirrorOverwriteJournal.DIR_NAME).deleteRecursively()
    }

    @Test
    fun `an overwrite whose encoder gave up part way puts the original back`() {
        val uri = Uri.fromFile(photo).toString()
        val thrown = runCatching {
            LocalOverwriteGuard(context).run(uri) {
                context.contentResolver.openOutputStream(Uri.parse(uri), "wt")!!.use { out ->
                    out.write(ByteArray(64))
                    requireEncoded(false)
                }
            }
        }.exceptionOrNull()

        assertTrue(thrown is IOException)
        assertTrue(photo.readBytes().contentEquals(original))
        assertEquals(0, File(context.filesDir, MirrorOverwriteJournal.DIR_NAME).listFiles()?.size ?: 0)
    }

    @Test
    fun `a finished encode lets the save go on`() {
        requireEncoded(true)
    }
}
