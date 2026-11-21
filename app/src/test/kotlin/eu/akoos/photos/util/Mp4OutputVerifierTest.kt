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

import eu.akoos.photos.util.Mp4OutputVerifier.Probe
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

class Mp4OutputVerifierTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun box(type: String, payload: Int): ByteArray =
        ByteBuffer.allocate(8 + payload).putInt(8 + payload).put(type.toByteArray(Charsets.ISO_8859_1)).array()

    private fun largeBox(type: String, payload: Int): ByteArray =
        ByteBuffer.allocate(16 + payload).putInt(1).put(type.toByteArray(Charsets.ISO_8859_1)).putLong(16L + payload).array()

    private fun file(vararg parts: ByteArray) = tmp.newFile().apply {
        writeBytes(ByteArrayOutputStream().also { o -> parts.forEach { o.write(it) } }.toByteArray())
    }

    // ─── box walk ─────────────────────────────────────────────────────────────

    @Test
    fun `boxes tiling the file with a moov pass`() {
        assertTrue(Mp4OutputVerifier.hasCompleteMoov(file(box("ftyp", 16), box("mdat", 100), box("moov", 40))))
    }

    @Test
    fun `a moov placed first passes`() {
        assertTrue(Mp4OutputVerifier.hasCompleteMoov(file(box("ftyp", 16), box("moov", 40), box("mdat", 100))))
    }

    @Test
    fun `a file whose muxer never wrote the moov fails`() {
        assertFalse(Mp4OutputVerifier.hasCompleteMoov(file(box("ftyp", 16), box("mdat", 100))))
    }

    @Test
    fun `a truncated last box fails`() {
        val whole = ByteArrayOutputStream().apply { write(box("ftyp", 16)); write(box("mdat", 100)); write(box("moov", 40)) }.toByteArray()
        assertFalse(Mp4OutputVerifier.hasCompleteMoov(file(whole.copyOf(whole.size - 10))))
    }

    @Test
    fun `trailing bytes shorter than a box header fail`() {
        assertFalse(Mp4OutputVerifier.hasCompleteMoov(file(box("ftyp", 16), box("moov", 40), ByteArray(3))))
    }

    @Test
    fun `a box size smaller than its header fails`() {
        val bad = ByteBuffer.allocate(8).putInt(4).put("free".toByteArray()).array()
        assertFalse(Mp4OutputVerifier.hasCompleteMoov(file(box("moov", 8), bad)))
    }

    @Test
    fun `a 64-bit box size is followed`() {
        assertTrue(Mp4OutputVerifier.hasCompleteMoov(file(box("ftyp", 16), largeBox("mdat", 64), box("moov", 8))))
    }

    @Test
    fun `a size-zero last box runs to the end of the file`() {
        val toEnd = ByteBuffer.allocate(40).putInt(0).put("mdat".toByteArray()).array()
        assertTrue(Mp4OutputVerifier.hasCompleteMoov(file(box("ftyp", 16), box("moov", 8), toEnd)))
    }

    @Test
    fun `an empty file fails`() {
        assertFalse(Mp4OutputVerifier.hasCompleteMoov(file()))
    }

    // ─── accepts ──────────────────────────────────────────────────────────────

    private val source = Probe(trackCount = 3, hasVideo = true, hasAudio = true, durationMs = 60_000L)

    @Test
    fun `an exact stream copy is accepted`() {
        assertTrue(Mp4OutputVerifier.accepts(source, source, sameTracks = true))
    }

    @Test
    fun `a stream copy that lost a track is refused`() {
        assertFalse(Mp4OutputVerifier.accepts(source, source.copy(trackCount = 2), sameTracks = true))
    }

    @Test
    fun `a replacement may drop a data track but never picture or sound`() {
        assertTrue(Mp4OutputVerifier.accepts(source, source.copy(trackCount = 2), sameTracks = false))
        assertFalse(Mp4OutputVerifier.accepts(source, source.copy(hasAudio = false), sameTracks = false))
        assertFalse(Mp4OutputVerifier.accepts(source, source.copy(hasVideo = false), sameTracks = false))
    }

    @Test
    fun `an output with no duration or no tracks is refused`() {
        assertFalse(Mp4OutputVerifier.accepts(source, source.copy(durationMs = 0L), sameTracks = false))
        assertFalse(Mp4OutputVerifier.accepts(source, source.copy(trackCount = 0), sameTracks = false))
    }

    @Test
    fun `a duration within the slack is accepted and a cut-short one refused`() {
        assertTrue(Mp4OutputVerifier.accepts(source, source.copy(durationMs = 62_900L), sameTracks = true))
        assertFalse(Mp4OutputVerifier.accepts(source, source.copy(durationMs = 30_000L), sameTracks = true))
        val short = Probe(1, true, false, 1_000L)
        assertTrue(Mp4OutputVerifier.accepts(short, short.copy(durationMs = 2_900L), sameTracks = true))
        assertFalse(Mp4OutputVerifier.accepts(short, short.copy(durationMs = 3_100L), sameTracks = true))
    }

    @Test
    fun `a source with unknown duration only needs a real output duration`() {
        val unknown = source.copy(durationMs = 0L)
        assertTrue(Mp4OutputVerifier.accepts(unknown, source.copy(durationMs = 5L), sameTracks = true))
    }
}
