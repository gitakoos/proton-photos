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

import java.io.OutputStream

/**
 * A self-contained animated GIF89a writer, built from the format spec so it carries no dependency on
 * any platform imaging type and runs on a plain JVM. Frames arrive as ARGB_8888 pixel rows, the same
 * order a decoded video frame is handed back in, and each is written as its own image block with a
 * local colour table so a clip whose palette drifts frame to frame still renders true.
 *
 * The two hard parts are done here from first principles: a median-cut quantiser reduces a frame to at
 * most 256 colours, and a variable-width LZW coder packs the resulting indices into the byte stream a
 * GIF decoder expects. Colours are treated as opaque; a frame's alpha is ignored, which is what a video
 * source wants.
 *
 * Usage is streaming: construct with the canvas size and per-frame delay, call [addFrame] for each
 * frame in order, then [finish]. The caller's [out] is flushed but never closed.
 */
class GifEncoder(
    private val out: OutputStream,
    private val width: Int,
    private val height: Int,
    private val frameDelayMs: Int,
    private val loop: Boolean = true,
) {
    private val delayCentis: Int = Math.round(frameDelayMs / 10.0).toInt().coerceIn(0, 0xFFFF)
    private var started = false

    init {
        require(width in 1..0xFFFF) { "width out of range: $width" }
        require(height in 1..0xFFFF) { "height out of range: $height" }
        require(frameDelayMs >= 0) { "frameDelayMs must be >= 0: $frameDelayMs" }
    }

    /**
     * Writes one frame from [argb], which holds [width] * [height] pixels in 0xAARRGGBB order, top row
     * first. The frame is quantised to its own local palette and appended to the stream. The first call
     * also emits the file header, the logical screen descriptor, and, when [loop] is set, the loop
     * extension, since those precede the first image.
     */
    fun addFrame(argb: IntArray) {
        require(argb.size == width * height) {
            "frame must be width*height ($width*$height) pixels, was ${argb.size}"
        }
        if (!started) {
            writeHeader()
            started = true
        }
        val quantized = quantize(argb)
        val bits = tableBits(quantized.palette.size)
        val minCodeSize = maxOf(2, bits)
        writeGraphicControlExtension()
        writeImageDescriptor(bits)
        writeColorTable(quantized.palette, 1 shl bits)
        out.write(minCodeSize)
        val blocks = SubBlockWriter(out)
        LzwEncoder(minCodeSize, blocks).encode(quantized.indices)
        blocks.finish()
    }

    /** Writes the trailer and flushes. The caller's stream is left open. */
    fun finish() {
        if (!started) {
            writeHeader()
            started = true
        }
        out.write(TRAILER)
        out.flush()
    }

    // ---- fixed structure --------------------------------------------------

    /** Signature, logical screen descriptor (no global colour table), then the loop extension. */
    private fun writeHeader() {
        out.write("GIF89a".toByteArray(Charsets.US_ASCII))
        writeShortLE(width)
        writeShortLE(height)
        out.write(0x00) // packed: no global colour table
        out.write(0x00) // background colour index
        out.write(0x00) // pixel aspect ratio
        if (loop) writeLoopExtension()
    }

    /** The NETSCAPE2.0 application extension carrying a loop count of 0, which means loop forever. */
    private fun writeLoopExtension() {
        out.write(EXTENSION_INTRODUCER)
        out.write(0xFF) // application extension label
        out.write(0x0B) // block size: 11 bytes of identifier follow
        out.write("NETSCAPE2.0".toByteArray(Charsets.US_ASCII))
        out.write(0x03) // sub-block size
        out.write(0x01) // sub-block id: looping
        writeShortLE(0) // loop count, 0 = forever
        out.write(0x00) // block terminator
    }

    /**
     * The graphic control extension preceding each image. Disposal method 1 (do not dispose) leaves the
     * frame in place, which renders a normal opaque clip correctly because every frame covers the whole
     * canvas. No transparency is declared.
     */
    private fun writeGraphicControlExtension() {
        out.write(EXTENSION_INTRODUCER)
        out.write(0xF9) // graphic control label
        out.write(0x04) // block size
        out.write(0x01 shl 2) // disposal method 1, no user input, no transparency
        writeShortLE(delayCentis)
        out.write(0x00) // transparent colour index (unused)
        out.write(0x00) // block terminator
    }

    /** The image descriptor: full-canvas placement plus a local colour table flag and its size. */
    private fun writeImageDescriptor(tableBits: Int) {
        out.write(IMAGE_SEPARATOR)
        writeShortLE(0) // left
        writeShortLE(0) // top
        writeShortLE(width)
        writeShortLE(height)
        // Bit 7 sets the local colour table flag; bits 0..2 hold its size as 2^(value+1).
        out.write(0x80 or (tableBits - 1))
    }

    /** The local colour table, padded to [entryCount] entries of R, G, B; unused slots are black. */
    private fun writeColorTable(palette: IntArray, entryCount: Int) {
        for (i in 0 until entryCount) {
            val color = if (i < palette.size) palette[i] else 0
            out.write((color ushr 16) and 0xFF)
            out.write((color ushr 8) and 0xFF)
            out.write(color and 0xFF)
        }
    }

    private fun writeShortLE(value: Int) {
        out.write(value and 0xFF)
        out.write((value ushr 8) and 0xFF)
    }

    // ---- quantization -----------------------------------------------------

    private class Quantized(val palette: IntArray, val indices: IntArray)

    /**
     * Reduces [argb] to a palette of at most [MAX_COLORS] colours and the per-pixel indices into it.
     * A frame that already fits takes the fast path and keeps its exact colours; a busier frame is run
     * through median cut. Alpha is discarded so a colour is its 24-bit RGB.
     */
    private fun quantize(argb: IntArray): Quantized {
        val counts = LinkedHashMap<Int, Int>()
        for (pixel in argb) {
            val rgb = pixel and 0xFFFFFF
            counts[rgb] = (counts[rgb] ?: 0) + 1
        }

        val palette: IntArray
        val indexOf: HashMap<Int, Int>
        if (counts.size <= MAX_COLORS) {
            palette = IntArray(counts.size)
            indexOf = HashMap(counts.size * 2)
            var i = 0
            for (color in counts.keys) {
                palette[i] = color
                indexOf[color] = i
                i++
            }
        } else {
            val colors = IntArray(counts.size)
            val pops = IntArray(counts.size)
            var i = 0
            for ((color, pop) in counts) {
                colors[i] = color
                pops[i] = pop
                i++
            }
            val result = medianCut(colors, pops, MAX_COLORS)
            palette = result.palette
            indexOf = result.indexOf
        }

        val indices = IntArray(argb.size) { indexOf.getValue(argb[it] and 0xFFFFFF) }
        return Quantized(palette, indices)
    }

    private class CutResult(val palette: IntArray, val indexOf: HashMap<Int, Int>)

    /**
     * Median-cut quantization over the distinct colours [colorsIn] weighted by their populations
     * [popsIn]. Colours start in one box; the box with the widest single-channel spread is repeatedly
     * sorted along that channel and split at the population-weighted median until [maxColors] boxes
     * exist or none can be split further. Each box yields one palette entry, the average of its colours,
     * and every colour maps to the index of the box that holds it.
     */
    private fun medianCut(colorsIn: IntArray, popsIn: IntArray, maxColors: Int): CutResult {
        val n = colorsIn.size
        val col = colorsIn.copyOf()
        val pop = popsIn.copyOf()
        val boxes = ArrayList<IntArray>() // each box is [lo, hi) over col/pop
        boxes.add(intArrayOf(0, n))

        while (boxes.size < maxColors) {
            val target = widestBox(boxes, col) ?: break
            val box = boxes[target]
            val lo = box[0]
            val hi = box[1]
            val channel = widestChannel(col, lo, hi)
            sortRange(col, pop, lo, hi, channel)
            var mid = weightedMedian(pop, lo, hi)
            if (mid <= lo || mid >= hi) mid = lo + (hi - lo) / 2
            box[1] = mid
            boxes.add(intArrayOf(mid, hi))
        }

        val palette = IntArray(boxes.size)
        val indexOf = HashMap<Int, Int>(n * 2)
        for (i in boxes.indices) {
            val lo = boxes[i][0]
            val hi = boxes[i][1]
            palette[i] = averageColor(col, pop, lo, hi)
            for (j in lo until hi) indexOf[col[j]] = i
        }
        return CutResult(palette, indexOf)
    }

    /** The index of the splittable box (more than one colour) with the widest channel spread, or null. */
    private fun widestBox(boxes: List<IntArray>, col: IntArray): Int? {
        var best = -1
        var bestSpread = -1
        for (i in boxes.indices) {
            val lo = boxes[i][0]
            val hi = boxes[i][1]
            if (hi - lo <= 1) continue
            val spread = channelRange(col, lo, hi, widestChannel(col, lo, hi))
            if (spread > bestSpread) {
                bestSpread = spread
                best = i
            }
        }
        return if (best >= 0) best else null
    }

    private fun channelValue(color: Int, channel: Int): Int = when (channel) {
        0 -> (color ushr 16) and 0xFF
        1 -> (color ushr 8) and 0xFF
        else -> color and 0xFF
    }

    private fun channelRange(col: IntArray, lo: Int, hi: Int, channel: Int): Int {
        var min = 255
        var max = 0
        for (i in lo until hi) {
            val v = channelValue(col[i], channel)
            if (v < min) min = v
            if (v > max) max = v
        }
        return max - min
    }

    private fun widestChannel(col: IntArray, lo: Int, hi: Int): Int {
        val r = channelRange(col, lo, hi, 0)
        val g = channelRange(col, lo, hi, 1)
        val b = channelRange(col, lo, hi, 2)
        return when {
            r >= g && r >= b -> 0
            g >= b -> 1
            else -> 2
        }
    }

    /** Sorts col[lo, hi) and its parallel pop entries in ascending order of the given channel. */
    private fun sortRange(col: IntArray, pop: IntArray, lo: Int, hi: Int, channel: Int) {
        val len = hi - lo
        val order = (0 until len).sortedBy { channelValue(col[lo + it], channel) }
        val sortedCol = IntArray(len) { col[lo + order[it]] }
        val sortedPop = IntArray(len) { pop[lo + order[it]] }
        for (i in 0 until len) {
            col[lo + i] = sortedCol[i]
            pop[lo + i] = sortedPop[i]
        }
    }

    /** The split point in [lo, hi) where the cumulative population first reaches half of the box total. */
    private fun weightedMedian(pop: IntArray, lo: Int, hi: Int): Int {
        var total = 0
        for (i in lo until hi) total += pop[i]
        val half = total / 2
        var acc = 0
        for (i in lo until hi) {
            acc += pop[i]
            if (acc >= half && i + 1 < hi) return i + 1
        }
        return lo + (hi - lo) / 2
    }

    private fun averageColor(col: IntArray, pop: IntArray, lo: Int, hi: Int): Int {
        var rSum = 0L
        var gSum = 0L
        var bSum = 0L
        var total = 0L
        for (i in lo until hi) {
            val weight = pop[i].toLong()
            rSum += ((col[i] ushr 16) and 0xFF) * weight
            gSum += ((col[i] ushr 8) and 0xFF) * weight
            bSum += (col[i] and 0xFF) * weight
            total += weight
        }
        if (total == 0L) return 0
        val r = ((rSum + total / 2) / total).toInt().coerceIn(0, 255)
        val g = ((gSum + total / 2) / total).toInt().coerceIn(0, 255)
        val b = ((bSum + total / 2) / total).toInt().coerceIn(0, 255)
        return (r shl 16) or (g shl 8) or b
    }

    /** The colour-table size exponent for [colorCount] entries: the smallest b in 1..8 with 2^b >= it. */
    private fun tableBits(colorCount: Int): Int {
        var bits = 1
        while ((1 shl bits) < colorCount) bits++
        return bits.coerceIn(1, 8)
    }

    // ---- LZW --------------------------------------------------------------

    /**
     * The GIF variant of variable-width LZW. Codes start one bit wider than the minimum code size and
     * grow as the dictionary fills, up to 12 bits; a clear code resets the dictionary and code width,
     * and an end-of-information code closes the stream. A string is keyed by (prefix code, next index),
     * so the dictionary is a growing tree of codes rather than of byte sequences.
     */
    private class LzwEncoder(private val minCodeSize: Int, private val out: SubBlockWriter) {
        private val clearCode = 1 shl minCodeSize
        private val endCode = clearCode + 1
        private var codeSize = minCodeSize + 1
        private var maxCode = (1 shl codeSize) - 1
        private var nextCode = clearCode + 2
        private val dict = HashMap<Int, Int>()
        private var bitBuffer = 0
        private var bitCount = 0

        fun encode(indices: IntArray) {
            output(clearCode)
            if (indices.isEmpty()) {
                output(endCode)
                flushBits()
                return
            }
            var prefix = indices[0]
            for (i in 1 until indices.size) {
                val k = indices[i]
                val key = (prefix shl 8) or k
                val existing = dict[key]
                if (existing != null) {
                    prefix = existing
                } else {
                    output(prefix)
                    if (nextCode <= MAX_CODE) {
                        dict[key] = nextCode
                        nextCode++
                    } else {
                        // The dictionary is full: reset it and the code width and start over.
                        output(clearCode)
                        dict.clear()
                        nextCode = clearCode + 2
                        codeSize = minCodeSize + 1
                        maxCode = (1 shl codeSize) - 1
                    }
                    prefix = k
                }
            }
            output(prefix)
            output(endCode)
            flushBits()
        }

        /**
         * Appends [code] to the bit stream at the current width, least significant bit first, and drains
         * whole bytes to the sub-block writer. The width grows here, after the code is written, once the
         * next free code no longer fits: a newly added code is never emitted before at least one further
         * code, so the widening always reaches the decoder before a code that needs it does.
         */
        private fun output(code: Int) {
            bitBuffer = bitBuffer or (code shl bitCount)
            bitCount += codeSize
            while (bitCount >= 8) {
                out.writeByte(bitBuffer and 0xFF)
                bitBuffer = bitBuffer ushr 8
                bitCount -= 8
            }
            if (nextCode > maxCode && codeSize < 12) {
                codeSize++
                maxCode = (1 shl codeSize) - 1
            }
        }

        private fun flushBits() {
            if (bitCount > 0) {
                out.writeByte(bitBuffer and 0xFF)
                bitBuffer = 0
                bitCount = 0
            }
        }

        private companion object {
            const val MAX_CODE = 4095 // 2^12 - 1, the largest code a 12-bit width can hold
        }
    }

    /**
     * Frames a byte stream into the GIF sub-block form: runs of up to 255 data bytes, each prefixed by
     * its length, ended by a zero-length block. Image data is written this way.
     */
    private class SubBlockWriter(private val out: OutputStream) {
        private val buffer = ByteArray(255)
        private var length = 0

        fun writeByte(value: Int) {
            buffer[length++] = value.toByte()
            if (length == 255) flushBlock()
        }

        fun finish() {
            flushBlock()
            out.write(0x00) // block terminator
        }

        private fun flushBlock() {
            if (length > 0) {
                out.write(length)
                out.write(buffer, 0, length)
                length = 0
            }
        }
    }

    private companion object {
        const val MAX_COLORS = 256
        const val EXTENSION_INTRODUCER = 0x21
        const val IMAGE_SEPARATOR = 0x2C
        const val TRAILER = 0x3B
    }
}
