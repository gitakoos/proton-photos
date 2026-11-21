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

package eu.akoos.photos.data.semantic

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The download size the semantic drawer and bar show: only the models not yet on the device. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SemanticMissingBytesTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val dir = File(context.filesDir, SemanticModelAssets.DIRECTORY)

    @After
    fun cleanUp() {
        dir.deleteRecursively()
    }

    @Test
    fun `nothing on the device counts both models`() {
        assertEquals(SemanticModelAssets.TOTAL_DOWNLOAD_BYTES, SemanticModelManager(context).missingDownloadBytes())
    }

    @Test
    fun `a text model already here counts only the image model`() {
        dir.mkdirs()
        File(dir, SemanticModelAssets.TEXT_MODEL.fileName).writeBytes(byteArrayOf(1))
        assertEquals(SemanticModelAssets.IMAGE_MODEL.sizeBytes, SemanticModelManager(context).missingDownloadBytes())
    }

    @Test
    fun `both models here counts nothing`() {
        dir.mkdirs()
        File(dir, SemanticModelAssets.TEXT_MODEL.fileName).writeBytes(byteArrayOf(1))
        File(dir, SemanticModelAssets.IMAGE_MODEL.fileName).writeBytes(byteArrayOf(1))
        assertEquals(0L, SemanticModelManager(context).missingDownloadBytes())
    }
}
