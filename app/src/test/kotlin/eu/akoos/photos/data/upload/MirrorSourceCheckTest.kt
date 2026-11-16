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
import io.mockk.mockk
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class MirrorSourceCheckTest {

    @get:Rule val tmp = TemporaryFolder()
    private val context = mockk<Context>(relaxed = true)

    @Test
    fun `an empty replacement is refused for every kind`() {
        val empty = tmp.newFile()
        MirrorSourceCheck.Kind.entries.forEach { kind ->
            assertFalse(MirrorSourceCheck.accepts(context, kind, empty, "file:///device/x"))
        }
    }

    @Test
    fun `a missing replacement is refused`() {
        val missing = File(tmp.root, "gone.mp4")
        assertFalse(MirrorSourceCheck.accepts(context, MirrorSourceCheck.Kind.VIDEO, missing, "file:///device/x"))
    }

    @Test
    fun `a video replacement without a moov is refused before any probe`() {
        val noMoov = tmp.newFile().apply { writeBytes(java.nio.ByteBuffer.allocate(24).putInt(24).put("ftyp".toByteArray()).array()) }
        assertFalse(MirrorSourceCheck.accepts(context, MirrorSourceCheck.Kind.VIDEO, noMoov, "file:///device/x"))
    }
}
