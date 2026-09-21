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

package eu.akoos.photos.data.semantic

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OnnxValue
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import android.graphics.Bitmap
import eu.akoos.photos.domain.usecase.normalize
import java.io.File
import java.nio.FloatBuffer
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Turns one photo into a [DIM]-d CLIP image embedding by running the image encoder once.
 *
 * The session opens lazily on the first [encode] from the model file and is released by [close], so
 * building the encoder is free until the first photo is embedded; opening the session costs far more
 * than a run, so a caller holds the instance across a batch and closes it after. [encode] is not safe
 * for concurrent runs and its owner serialises them.
 *
 * The input follows the encoder's recipe: the bitmap is resized to fill a [SIZE] square (scaled by the
 * longer edge ratio so the square is fully covered), centre-cropped to the square, read in red, green,
 * blue channel order, scaled to 0..1 and normalised per channel by the CLIP mean and standard
 * deviation, and laid out channel-planes-first as a single (1, channels, side, side) NCHW batch. The channel count and the
 * square side are read from the session's declared input, so a three-channel RGB model and a
 * four-channel RGBA variant both feed correctly. The output vector is L2-normalised, so an image and a
 * text embedding compare by a plain cosine (dot product).
 */
class ClipImageEncoder(private val modelFile: File) : AutoCloseable {

    private val environment: OrtEnvironment = OrtEnvironment.getEnvironment()

    private val sessionOptions = OrtSession.SessionOptions().apply {
        // BASIC, not ALL_OPT: the extended transformer fusions rewrite this fp16 model's GELU into a
        // com.microsoft.Gelu contrib op, and the mobile Runtime's CPU provider has no fp16 kernel for it
        // (ORT_NOT_IMPLEMENTED at run time, so every embed fails). BASIC leaves the model's own ops, which
        // do have fp16 CPU kernels. XNNPACK is not built into this Runtime package (requesting it aborts at
        // session creation), so the default CPU kernels run.
        setOptimizationLevel(OrtSession.SessionOptions.OptLevel.BASIC_OPT)
    }

    private val lazySession = lazy { environment.createSession(modelFile.absolutePath, sessionOptions) }

    private val session: OrtSession get() = lazySession.value

    private val inputName: String by lazy { session.inputNames.firstOrNull() ?: DEFAULT_INPUT }

    private val outputName: String by lazy { session.outputNames.firstOrNull() ?: DEFAULT_OUTPUT }

    /** The declared input shape, or empty when the model leaves it unstated. */
    private val inputShape: LongArray by lazy {
        (session.inputInfo[inputName]?.info as? TensorInfo)?.shape ?: LongArray(0)
    }

    /** Channels the network declares (NCHW index 1): 3 for RGB, 4 for an RGBA variant; 3 by default. */
    private val channels: Int by lazy {
        inputShape.getOrNull(1)?.toInt()?.takeIf { it == 3 || it == 4 } ?: DEFAULT_CHANNELS
    }

    /** Square side the network declares (NCHW index 2), or [SIZE] when it leaves the dimension dynamic. */
    private val side: Int by lazy {
        inputShape.getOrNull(2)?.toInt()?.takeIf { it > 0 } ?: SIZE
    }

    /**
     * The L2-normalised image embedding of [bitmap]. The bitmap belongs to the caller and is left
     * untouched; a recycled bitmap yields a zero vector rather than throwing, so a caller iterating a
     * library can skip a dead frame.
     */
    fun encode(bitmap: Bitmap): FloatArray {
        if (bitmap.isRecycled) return FloatArray(DIM)
        val tensor = toInputTensor(bitmap)
        val embedding = try {
            session.run(mapOf(inputName to tensor)).use { result ->
                val named = result.get(outputName)
                floats(if (named.isPresent) named.get() else result.get(0))
            }
        } finally {
            tensor.close()
        }
        return normalize(embedding)
    }

    /**
     * [bitmap] as the encoder's input: resized to fill a [side] square by the longer edge ratio,
     * centre-cropped to [side] x [side], and filled into a (1, [channels], side, side) NCHW tensor.
     */
    private fun toInputTensor(bitmap: Bitmap): OnnxTensor {
        val sourceWidth = bitmap.width
        val sourceHeight = bitmap.height
        val fill = max(side.toFloat() / sourceWidth, side.toFloat() / sourceHeight)
        val scaledWidth = max(side, (sourceWidth * fill).roundToInt())
        val scaledHeight = max(side, (sourceHeight * fill).roundToInt())
        val scaled = Bitmap.createScaledBitmap(bitmap, scaledWidth, scaledHeight, true)
        val pixels = IntArray(side * side)
        try {
            val startX = (scaledWidth - side) / 2
            val startY = (scaledHeight - side) / 2
            scaled.getPixels(pixels, 0, side, startX, startY, side, side)
        } finally {
            // createScaledBitmap hands back the same instance when nothing needs scaling.
            if (scaled !== bitmap && !scaled.isRecycled) scaled.recycle()
        }
        val mean = if (channels >= 4) CLIP_MEAN + 0f else CLIP_MEAN
        val std = if (channels >= 4) CLIP_STD + 1f else CLIP_STD
        val values = fillClipImageTensor(pixels, side, side, channels, mean, std)
        val shape = longArrayOf(1, channels.toLong(), side.toLong(), side.toLong())
        return OnnxTensor.createTensor(environment, FloatBuffer.wrap(values), shape)
    }

    private fun floats(value: OnnxValue): FloatArray {
        val buffer = (value as OnnxTensor).floatBuffer.duplicate()
        buffer.rewind()
        val out = FloatArray(buffer.remaining())
        buffer.get(out)
        return out
    }

    override fun close() {
        if (lazySession.isInitialized()) runCatching { session.close() }
        runCatching { sessionOptions.close() }
    }

    private companion object {
        const val DEFAULT_INPUT = "input"

        const val DEFAULT_OUTPUT = "output"

        /** Square side the image encoder reads when the model leaves the dimension dynamic. */
        const val SIZE = 224

        /** Channels assumed when the model declares no usable count: RGB. */
        const val DEFAULT_CHANNELS = 3

        /** Length of the embedding the encoder emits. */
        const val DIM = 512

        /** Per-channel CLIP normalisation applied after scaling to 0..1: (value - mean) / std, RGB order. */
        val CLIP_MEAN = floatArrayOf(0.48145466f, 0.4578275f, 0.40821073f)

        val CLIP_STD = floatArrayOf(0.26862954f, 0.26130258f, 0.27577711f)
    }
}

/**
 * Fills a channel-planes-first NCHW tensor from [pixels] (ARGB, row-major, [width] x [height]): the red
 * plane first, then green, then blue, each 8-bit value scaled into 0..1 and then normalised by the
 * per-channel [mean] and [std] (in red, green, blue order); a fourth plane carries alpha when [channels]
 * is 4, for an RGBA model. Pure and allocation-only, so the pixel-to-tensor step is testable without a
 * model or a real bitmap.
 */
internal fun fillClipImageTensor(
    pixels: IntArray,
    width: Int,
    height: Int,
    channels: Int,
    mean: FloatArray,
    std: FloatArray,
): FloatArray {
    val plane = width * height
    val out = FloatArray(channels * plane)
    val greenOffset = plane
    val blueOffset = 2 * plane
    val alphaOffset = 3 * plane
    val toUnit = 1f / 255f
    for (i in 0 until plane) {
        val pixel = pixels[i]
        out[i] = (((pixel shr 16) and 0xFF) * toUnit - mean[0]) / std[0]
        out[greenOffset + i] = (((pixel shr 8) and 0xFF) * toUnit - mean[1]) / std[1]
        out[blueOffset + i] = ((pixel and 0xFF) * toUnit - mean[2]) / std[2]
        if (channels >= 4) out[alphaOffset + i] = (((pixel shr 24) and 0xFF) * toUnit - mean[3]) / std[3]
    }
    return out
}
