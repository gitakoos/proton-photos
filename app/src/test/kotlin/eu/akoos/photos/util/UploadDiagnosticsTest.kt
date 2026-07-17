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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two pure helpers behind the upload diagnostics a tester pastes into an issue. Release builds
 * minify away every android.util.Log call, so these lines are the only trace an upload failure leaves,
 * and they must both identify the photo distinctly and reveal nothing about it.
 */
class UploadDiagnosticsTest {

    private fun mediaUri(id: Long) = "content://media/external/images/media/$id"

    @Test
    fun `consecutive photos in one batch get distinct references`() {
        val refs = (1000012433L..1000012437L).map { uploadLogRef(mediaUri(it)) }
        assertEquals("every photo of a batch must be distinguishable", refs.size, refs.toSet().size)
    }

    @Test
    fun `a reference is eight hex digits and reveals nothing of the uri`() {
        val ref = uploadLogRef(mediaUri(1000012433L))
        assertEquals(8, ref.length)
        assertTrue("only hex digits", ref.all { it in "0123456789abcdef" })
        assertFalse("must not carry the id", ref.contains("1000012433"))
        assertFalse("must not carry the path", ref.contains("media"))
    }

    @Test
    fun `the same photo always gets the same reference`() {
        assertEquals(uploadLogRef(mediaUri(42L)), uploadLogRef(mediaUri(42L)))
        assertNotEquals(uploadLogRef(mediaUri(42L)), uploadLogRef(mediaUri(43L)))
    }

    @Test
    fun `a failure names the exception type`() {
        val described = describeUploadFailure(IllegalStateException("boom"))
        assertTrue(described.startsWith("IllegalStateException:"))
    }

    @Test
    fun `a wrapped failure names the root cause too`() {
        val described = describeUploadFailure(RuntimeException("outer", java.io.IOException("inner")))
        assertTrue(described.contains("RuntimeException caused by IOException"))
    }

    @Test
    fun `a failure message never leaks a file name or a url`() {
        val described = describeUploadFailure(
            java.io.IOException("failed to upload IMG_20260710_113355.jpg to https://drive-api.proton.me/blocks"),
        )
        assertFalse("no file name", described.contains("IMG_20260710_113355.jpg"))
        assertFalse("no url", described.contains("drive-api.proton.me"))
        assertTrue("the type still identifies the failure", described.contains("IOException"))
    }

    @Test
    fun `a self referencing cause chain does not spin`() {
        val looping = object : RuntimeException("looping") {
            override val cause: Throwable get() = this
        }
        val described = describeUploadFailure(looping)
        assertTrue(described.isNotBlank())
    }

    @Test
    fun `a failure with no message still describes the type`() {
        val described = describeUploadFailure(java.util.concurrent.TimeoutException())
        assertTrue(described.startsWith("TimeoutException:"))
    }
}
