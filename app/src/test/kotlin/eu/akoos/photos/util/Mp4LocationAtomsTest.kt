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

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File

/** The in-place location erase a failed strip remux falls back to, on box trees built byte by byte. */
class Mp4LocationAtomsTest {

    private val files = mutableListOf<File>()

    @After
    fun cleanUp() {
        files.forEach { it.delete() }
    }

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

    private fun file(bytes: ByteArray): File =
        File.createTempFile("loc_", ".mp4").also { it.writeBytes(bytes); files += it }

    private fun ByteArray.contains(text: String): Boolean =
        String(this, Charsets.ISO_8859_1).contains(text)

    @Test
    fun `the movie location atom becomes a zeroed free box and nothing else moves`() {
        val mdat = box("mdat", ByteArray(64) { 7 })
        val bytes = box("ftyp", "isom".toByteArray()) +
            box("moov", box("mvhd", ByteArray(100)), box("udta", xyz("+47.4979+019.0402+120.000/"))) + mdat
        val f = file(bytes)

        assertTrue(Mp4LocationAtoms.erase(f))

        val after = f.readBytes()
        assertEquals(bytes.size, after.size)
        assertFalse(after.contains("©xyz"))
        assertFalse(after.contains("+47.4979"))
        assertTrue(after.contains("free"))
        assertArrayEquals(mdat, after.copyOfRange(after.size - mdat.size, after.size))
    }

    @Test
    fun `a track location atom and a loci box are erased too`() {
        val bytes = box(
            "moov",
            box("trak", box("tkhd", ByteArray(84)), box("udta", xyz("-33.8688+151.2093/"))),
            box("udta", box("loci", "Budapest".toByteArray())),
        )
        val f = file(bytes)

        assertTrue(Mp4LocationAtoms.erase(f))

        val after = f.readBytes()
        assertFalse(after.contains("-33.8688"))
        assertFalse(after.contains("loci"))
        assertFalse(after.contains("Budapest"))
    }

    @Test
    fun `a file without a location is left byte for byte as it was`() {
        val bytes = box("ftyp", "isom".toByteArray()) + box("moov", box("mvhd", ByteArray(100)))
        val f = file(bytes)

        assertTrue(Mp4LocationAtoms.erase(f))

        assertArrayEquals(bytes, f.readBytes())
    }

    @Test
    fun `a file that is not an mp4 counts as still carrying its location`() {
        assertFalse(Mp4LocationAtoms.erase(file("not a video at all".toByteArray())))
        val truncated = box("moov", box("udta", xyz("+47.4979+019.0402/"))).copyOf(20)
        assertFalse(Mp4LocationAtoms.erase(file(truncated)))
    }
}
