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

import eu.akoos.photos.data.localcrypto.PlainContent
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * An edit's upload sends exactly the size recorded for it, so the size must be read once the last write is
 * done. Read before the EXIF went back in, it left the EXIF block (484 bytes in the reported case) off the
 * Drive copy.
 */
class FinishedEditFileTest {

    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun `the size and hash are the finished file's, taken after the EXIF is written`() {
        val file = folder.newFile("edit.jpg")
        file.writeBytes(ByteArray(10_000) { it.toByte() })

        val finished = finishEditFile(file) {
            // The EXIF put back after the encode grows the file.
            file.appendBytes(ByteArray(484) { 7 })
        }

        assertEquals(10_484L, file.length())
        assertEquals(file.length(), finished.sizeBytes)
        assertEquals(PlainContent.sha1Hex(file), finished.sha1)
    }

    @Test
    fun `a strip that shrinks the file is measured too`() {
        val file = folder.newFile("edit.jpg")
        file.writeBytes(ByteArray(10_000) { 1 })

        val finished = finishEditFile(file) { file.writeBytes(ByteArray(9_000) { 2 }) }

        assertEquals(9_000L, finished.sizeBytes)
        assertEquals(PlainContent.sha1Hex(file), finished.sha1)
    }
}
