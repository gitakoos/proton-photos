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
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream

/** The location of an MP4 known only by its first and last blocks, on box trees built byte by byte. */
class Mp4EdgeLocationTest {

    private fun box(type: String, vararg children: ByteArray): ByteArray {
        val payload = children.fold(ByteArray(0)) { acc, b -> acc + b }
        val out = ByteArrayOutputStream()
        DataOutputStream(out).apply {
            writeInt(8 + payload.size)
            write(type.toByteArray(Charsets.ISO_8859_1))
            write(payload)
        }
        return out.toByteArray()
    }

    private fun xyz(iso: String): ByteArray {
        val s = iso.toByteArray(Charsets.US_ASCII)
        val out = ByteArrayOutputStream()
        DataOutputStream(out).apply { writeShort(s.size); writeShort(0x15C7); write(s) }
        return box("©xyz", out.toByteArray())
    }

    private val ftyp = box("ftyp", "isom".toByteArray())
    private val moov = box("moov", box("mvhd", ByteArray(100)), box("udta", xyz("+38.9067+001.4206/")))
    private val mdat = box("mdat", ByteArray(4_000) { 7 })

    @Test
    fun `a movie box after the media data is read from the last block`() {
        val file = ftyp + mdat + moov

        val iso = ContentEdgeGps.mp4Iso6709(file.copyOfRange(0, 1_000), file.copyOfRange(file.size - 1_500, file.size))

        assertEquals("+38.9067+001.4206/", iso)
    }

    @Test
    fun `a movie box ahead of the media data is read from the first block`() {
        val file = ftyp + moov + mdat

        val iso = ContentEdgeGps.mp4Iso6709(file.copyOfRange(0, 1_000), file.copyOfRange(file.size - 1_000, file.size))

        assertEquals("+38.9067+001.4206/", iso)
    }

    @Test
    fun `a movie box split across the two blocks reads as none`() {
        val file = ftyp + mdat + moov

        val iso = ContentEdgeGps.mp4Iso6709(file.copyOfRange(0, 1_000), file.copyOfRange(file.size - 60, file.size))

        assertNull(iso)
    }

    @Test
    fun `a stray movie box type that holds no location does not end the search`() {
        val decoy = box("moov", ByteArray(12) { 7 })
        val file = ftyp + mdat + moov + box("free", decoy)

        val iso = ContentEdgeGps.mp4Iso6709(file.copyOfRange(0, 100), file.copyOfRange(file.size - 1_000, file.size))

        assertEquals("+38.9067+001.4206/", iso)
    }

    @Test
    fun `a single-block file is read from its first block alone`() {
        val file = ftyp + mdat + moov

        assertEquals("+38.9067+001.4206/", ContentEdgeGps.mp4Iso6709(file, null))
    }

    @Test
    fun `a movie box with no location reads as none`() {
        val file = ftyp + mdat + box("moov", box("mvhd", ByteArray(100)))

        assertNull(ContentEdgeGps.mp4Iso6709(file.copyOfRange(0, 100), file.copyOfRange(file.size - 500, file.size)))
    }
}
