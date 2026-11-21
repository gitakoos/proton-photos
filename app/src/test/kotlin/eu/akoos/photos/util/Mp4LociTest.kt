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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File

/** The 3GPP `loci` location box: read beside `©xyz`, erased with the other location atoms. */
class Mp4LociTest {

    private val files = mutableListOf<File>()

    @After
    fun cleanUp() {
        files.forEach { it.delete() }
    }

    private fun box(type: String, payload: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        DataOutputStream(out).apply {
            writeInt(8 + payload.size)
            write(type.toByteArray(Charsets.ISO_8859_1))
            write(payload)
        }
        return out.toByteArray()
    }

    private fun loci(latitude: Double, longitude: Double, name: ByteArray = "Home\u0000".toByteArray()): ByteArray {
        val out = ByteArrayOutputStream()
        DataOutputStream(out).apply {
            writeInt(0)
            writeShort(0x15C7)
            write(name)
            writeByte(0)
            writeInt((longitude * 65536).toInt())
            writeInt((latitude * 65536).toInt())
            writeInt(0)
            write("Earth\u0000\u0000".toByteArray())
        }
        return box("loci", out.toByteArray())
    }

    private fun xyz(iso: String): ByteArray {
        val s = iso.toByteArray(Charsets.US_ASCII)
        val out = ByteArrayOutputStream()
        DataOutputStream(out).apply { writeShort(s.size); writeShort(0x15C7); write(s) }
        return box("©xyz", out.toByteArray())
    }

    private fun movie(vararg udta: ByteArray): ByteArray =
        box("ftyp", "isom".toByteArray()) + box("moov", box("mvhd", ByteArray(100)) + box("udta", udta.fold(ByteArray(0)) { a, b -> a + b }))

    private fun file(bytes: ByteArray): File =
        File.createTempFile("loci_", ".mp4").also { it.writeBytes(bytes); files += it }

    @Test
    fun `a loci place is read as an ISO 6709 string`() {
        assertEquals("+47.5000+019.2500+0.000/", Mp4LocationAtoms.readIso6709(file(movie(loci(47.5, 19.25)))))
    }

    @Test
    fun `a loci place behind a UTF-16 name is read`() {
        val name = byteArrayOf(0xFE.toByte(), 0xFF.toByte(), 0, 'H'.code.toByte(), 0, 'i'.code.toByte(), 0, 0)

        assertEquals("-33.7500+151.1250+0.000/", Mp4LocationAtoms.readIso6709(file(movie(loci(-33.75, 151.125, name)))))
    }

    @Test
    fun `a movie location atom wins over loci`() {
        assertEquals("+10.0000+020.0000/", Mp4LocationAtoms.readIso6709(file(movie(loci(47.5, 19.25), xyz("+10.0000+020.0000/")))))
    }

    @Test
    fun `a loci position out of range is no place`() {
        assertNull(Mp4LocationAtoms.readIso6709(file(movie(loci(100.0, 19.25)))))
    }

    @Test
    fun `a loci position at 0, 0 is no place`() {
        assertNull(Mp4LocationAtoms.readIso6709(file(movie(loci(0.0, 0.0)))))
    }

    @Test
    fun `a loci box cut short before its coordinates is no place`() {
        val cut = box("loci", byteArrayOf(0, 0, 0, 0, 0x15, 0xC7.toByte()) + "Home\u0000".toByteArray() + byteArrayOf(0, 1, 2))

        assertNull(Mp4LocationAtoms.readIso6709(file(movie(cut))))
    }

    @Test
    fun `a loci name with no end is no place`() {
        val unterminated = box("loci", byteArrayOf(0, 0, 0, 0, 0x15, 0xC7.toByte()) + "Home".toByteArray())

        assertNull(Mp4LocationAtoms.readIso6709(file(movie(unterminated))))
    }

    @Test
    fun `an erased loci box reads as no place and keeps the file size`() {
        val bytes = movie(loci(47.5, 19.25))
        val f = file(bytes)

        assertTrue(Mp4LocationAtoms.erase(f))

        assertNull(Mp4LocationAtoms.readIso6709(f))
        assertEquals(bytes.size.toLong(), f.length())
        assertFalse(String(f.readBytes(), Charsets.ISO_8859_1).contains("loci"))
    }

    @Test
    fun `the cloud file read finds a loci place in a leading movie box`() {
        assertEquals(47.5 to 19.25, ContentEdgeGps.read("video/mp4", movie(loci(47.5, 19.25)), null))
    }

    @Test
    fun `a leftover loci is counted until it is blanked`() {
        val bytes = movie(loci(47.5, 19.25))
        assertEquals(1, MovieLocation.process(bytes, 0, bytes.size, erase = false))

        MovieLocation.process(bytes, 0, bytes.size, erase = true)

        assertEquals(0, MovieLocation.process(bytes, 0, bytes.size, erase = false))
    }
}
