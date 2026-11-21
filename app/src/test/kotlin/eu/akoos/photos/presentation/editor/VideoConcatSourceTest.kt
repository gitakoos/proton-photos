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
import eu.akoos.photos.data.localcrypto.LocalFiles
import eu.akoos.photos.data.localcrypto.VaultFiles
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** A joined clip that is kept encrypted reaches the Transformer through the decrypting provider. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VideoConcatSourceTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `a cloud original in the full-size cache is read decrypted`() {
        val file = File(LocalFiles.dir(context, LocalFiles.Area.FULLRES), "abc.mp4")

        val uri = concatSourceUri(context, Uri.fromFile(file).toString())

        assertEquals("content", uri.scheme)
        assertEquals(listOf(LocalFiles.Area.FULLRES.code, "abc.mp4"), uri.pathSegments)
    }

    @Test
    fun `a hidden video is read decrypted`() {
        val file = File(VaultFiles.vaultDir(context), "clip__1700000000000.mp4")

        val uri = concatSourceUri(context, Uri.fromFile(file).toString())

        assertEquals("content", uri.scheme)
        assertEquals(LocalFiles.Area.VAULT.code, uri.pathSegments.first())
    }

    @Test
    fun `a gallery video is used as it is`() {
        val gallery = "content://media/external/video/media/42"

        assertEquals(gallery, concatSourceUri(context, gallery).toString())
    }
}
