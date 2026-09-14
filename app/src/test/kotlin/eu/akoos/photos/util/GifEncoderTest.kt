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
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

/**
 * Round-trips the encoder against an independent decoder written from the GIF89a spec: a handful of
 * synthetic frames are encoded to bytes, then the block chain is walked, each image block's LZW data
 * is inflated back to colour indices and mapped through its local colour table, and the reconstructed
 * pixels are checked against what went in.
 *
 * The decoder is the inverse of the encoder built by the same hand, so a shared blind spot is possible
 * in principle; two guards make that unlikely to pass silently. The variable-width LZW here follows the
 * standard decode (dictionary rebuilt per code, KwKwK handled, width grown on the decoder's own
 * schedule), which is a different code path from the encoder rather than a mirror of it; and the byte
 * stream is separately checked for the exact signature and trailer and parsed structurally, so a framing
 * or header error is caught without any decode at all. The platform's own GIF reader is not reachable
 * from an Android unit test (android.jar carries no javax.imageio), which is why the round trip is
 * self-contained.
 */
class GifEncoderTest {

    private val red = 0xFFFF0000.toInt()
    private val green = 0xFF00FF00.toInt()
    private val black = 0xFF000000.toInt()
    private val white = 0xFFFFFFFF.toInt()

    private fun solid(size: Int, color: Int): IntArray = IntArray(size * size) { color }

    /** A checkerboard where (0,0) is black: a two-colour frame that exercises real index data. */
    private fun checkerboard(size: Int): IntArray = IntArray(size * size) { i ->
        if (((i % size) + (i / size)) % 2 == 0) black else white
    }

    private fun encode(width: Int, height: Int, delayMs: Int, loop: Boolean, frames: List<IntArray>): ByteArray {
        val sink = ByteArrayOutputStream()
        val encoder = GifEncoder(sink, width, height, delayMs, loop)
        frames.forEach { encoder.addFrame(it) }
        encoder.finish()
        return sink.toByteArray()
    }

    // ---- round trip -------------------------------------------------------

    @Test
    fun `a three frame gif round trips back to its pixels`() {
        val size = 16
        val bytes = encode(
            size, size, delayMs = 100, loop = true,
            frames = listOf(solid(size, red), solid(size, green), checkerboard(size)),
        )

        assertSignatureAndTrailer(bytes)

        val gif = decode(bytes)
        assertEquals("canvas width", size, gif.canvasWidth)
        assertEquals("canvas height", size, gif.canvasHeight)
        assertEquals("frame count", 3, gif.frames.size)
        assertTrue("loop extension present", gif.hasNetscapeLoop)

        val redFrame = gif.frames[0]
        assertEquals(size, redFrame.width)
        assertEquals(size, redFrame.height)
        for (p in redFrame.pixels) assertEquals("every pixel of frame 0 is red", 0xFF0000, p)

        assertEquals("frame 1 is green", 0x00FF00, gif.frames[1].pixelAt(5, 5))

        val checker = gif.frames[2]
        assertEquals("checker (0,0) black", 0x000000, checker.pixelAt(0, 0))
        assertEquals("checker (1,0) white", 0xFFFFFF, checker.pixelAt(1, 0))
        assertEquals("checker (0,1) white", 0xFFFFFF, checker.pixelAt(0, 1))
        assertEquals("checker (1,1) black", 0x000000, checker.pixelAt(1, 1))
    }

    @Test
    fun `a frame with more than 256 colours quantises and still round trips`() {
        val size = 24 // 576 pixels, more distinct colours than a palette can hold
        val frame = IntArray(size * size) { i ->
            val x = i % size
            val y = i / size
            // (10x, 10y) is unique per pixel, so all 576 colours are distinct and spread over R and G.
            (0xFF shl 24) or ((x * 10) shl 16) or ((y * 10) shl 8) or ((x * y) % 256)
        }
        assertTrue("fixture must exceed the palette limit", frame.map { it and 0xFFFFFF }.toSet().size > 256)

        val bytes = encode(size, size, delayMs = 40, loop = false, frames = listOf(frame))

        assertSignatureAndTrailer(bytes)
        val gif = decode(bytes)
        assertEquals(1, gif.frames.size)
        assertTrue("loop = false omits the extension", !gif.hasNetscapeLoop)

        val decoded = gif.frames[0]
        assertEquals(size, decoded.width)
        assertEquals(size, decoded.height)
        assertEquals("all pixels reconstructed", size * size, decoded.pixels.size)
        assertTrue("colours were reduced to a palette", decoded.pixels.toSet().size <= 256)
    }

    @Test
    fun `looping off produces a single opaque frame with no loop extension`() {
        val bytes = encode(8, 8, delayMs = 100, loop = false, frames = listOf(solid(8, red)))
        val gif = decode(bytes)
        assertTrue(!gif.hasNetscapeLoop)
        assertEquals(1, gif.frames.size)
        for (p in gif.frames[0].pixels) assertEquals(0xFF0000, p)
    }

    // ---- byte-level checks ------------------------------------------------

    private fun assertSignatureAndTrailer(bytes: ByteArray) {
        assertEquals("GIF89a", String(bytes, 0, 6, Charsets.US_ASCII))
        assertEquals("trailer", 0x3B, bytes[bytes.size - 1].toInt() and 0xFF)
    }

    // ---- self-contained decoder -------------------------------------------

    private class Frame(val width: Int, val height: Int, val pixels: IntArray) {
        fun pixelAt(x: Int, y: Int): Int = pixels[y * width + x]
    }

    private class DecodedGif(
        val canvasWidth: Int,
        val canvasHeight: Int,
        val hasNetscapeLoop: Boolean,
        val frames: List<Frame>,
    )

    /**
     * Walks the block chain: signature, logical screen descriptor, then extensions and image blocks
     * until the trailer. Sub-blocks are skipped or gathered by their length prefixes, so a data byte
     * equal to an image separator is never taken for a real block boundary. Each image block's colour
     * indices are recovered by [lzwDecode] and mapped through its local colour table.
     */
    private fun decode(bytes: ByteArray): DecodedGif {
        fun u8(i: Int) = bytes[i].toInt() and 0xFF
        fun u16(i: Int) = u8(i) or (u8(i + 1) shl 8)

        val canvasWidth = u16(6)
        val canvasHeight = u16(8)
        val screenPacked = u8(10)
        var pos = 13
        if (screenPacked and 0x80 != 0) pos += 3 * (1 shl ((screenPacked and 0x07) + 1))

        var netscape = false
        val frames = ArrayList<Frame>()

        fun skipSubBlocks(start: Int): Int {
            var p = start
            while (u8(p) != 0) p += 1 + u8(p)
            return p + 1
        }

        loop@ while (pos < bytes.size) {
            when (u8(pos)) {
                0x3B -> break@loop
                0x21 -> {
                    if (u8(pos + 1) == 0xFF && u8(pos + 2) == 0x0B &&
                        String(bytes, pos + 3, 11, Charsets.US_ASCII) == "NETSCAPE2.0"
                    ) {
                        netscape = true
                    }
                    pos = skipSubBlocks(pos + 2)
                }
                0x2C -> {
                    val imageWidth = u16(pos + 5)
                    val imageHeight = u16(pos + 7)
                    val localPacked = u8(pos + 9)
                    var p = pos + 10
                    require(localPacked and 0x80 != 0) { "expected a local colour table" }
                    val tableSize = 1 shl ((localPacked and 0x07) + 1)
                    val table = IntArray(tableSize) { k ->
                        (u8(p + 3 * k) shl 16) or (u8(p + 3 * k + 1) shl 8) or u8(p + 3 * k + 2)
                    }
                    p += 3 * tableSize
                    val minCodeSize = u8(p)
                    p += 1
                    val data = ByteArrayOutputStream()
                    while (u8(p) != 0) {
                        val len = u8(p)
                        data.write(bytes, p + 1, len)
                        p += 1 + len
                    }
                    p += 1 // block terminator
                    val indices = lzwDecode(minCodeSize, data.toByteArray())
                    val pixels = IntArray(imageWidth * imageHeight) { table[indices[it]] }
                    frames.add(Frame(imageWidth, imageHeight, pixels))
                    pos = p
                }
                else -> break@loop
            }
        }
        return DecodedGif(canvasWidth, canvasHeight, netscape, frames)
    }

    /** Standard variable-width LZW decode of GIF image data into colour indices. */
    private fun lzwDecode(minCodeSize: Int, data: ByteArray): IntArray {
        val clearCode = 1 shl minCodeSize
        val endCode = clearCode + 1
        var codeSize = minCodeSize + 1
        var bitPos = 0

        fun readCode(): Int {
            var value = 0
            for (i in 0 until codeSize) {
                val byteIndex = bitPos / 8
                val bit = if (byteIndex < data.size) (data[byteIndex].toInt() ushr (bitPos % 8)) and 1 else 0
                value = value or (bit shl i)
                bitPos++
            }
            return value
        }

        val out = ArrayList<Int>()
        var dict = ArrayList<IntArray>()
        fun reset() {
            dict = ArrayList()
            for (i in 0 until clearCode) dict.add(intArrayOf(i))
            dict.add(IntArray(0)) // clear code slot
            dict.add(IntArray(0)) // end code slot
            codeSize = minCodeSize + 1
        }
        reset()

        var previous: IntArray? = null
        while (true) {
            val code = readCode()
            when {
                code == clearCode -> {
                    reset()
                    previous = null
                }
                code == endCode -> break
                else -> {
                    val entry = if (code < dict.size) {
                        dict[code]
                    } else {
                        // The one code not yet in the table: its expansion is the previous string plus
                        // that string's own first index.
                        val p = previous!!
                        p + p[0]
                    }
                    for (v in entry) out.add(v)
                    previous?.let {
                        dict.add(it + entry[0])
                        if (dict.size == (1 shl codeSize) && codeSize < 12) codeSize++
                    }
                    previous = entry
                }
            }
        }
        return out.toIntArray()
    }
}
