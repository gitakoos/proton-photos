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

/*
 * The session handling and the NCHW input layout follow the detector rail in this project. The BGR,
 * raw 0-255 input and the L2-normalised 128-d output follow the published OpenCV Zoo SFace
 * recognition format (the blobFromImage default), which is Apache-2.0 licensed:
 *
 *   Copyright (c) 2023 OpenCV Zoo and contributors
 */

package eu.akoos.photos.data.face

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OnnxValue
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.graphics.Bitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.FloatBuffer
import kotlin.math.sqrt

/**
 * Turns one aligned face crop into a [DIM]-d embedding by running the SFace recognition network once.
 *
 * One session per instance, opened from [modelFile] when the instance is built and released by
 * [close]; opening it costs far more than a run, so a caller holds the instance for as long as it
 * needs embeddings and closes it after. [embed] is not safe for concurrent runs and its owner
 * serialises them.
 *
 * The input is a 112x112 landmark-aligned crop (see [FaceAlignment]), fed BGR and raw 0-255 as SFace
 * expects; the output vector is L2-normalised so two embeddings compare by a plain cosine (dot
 * product).
 */
class FaceEmbedder(modelFile: File) : AutoCloseable {

    private val environment: OrtEnvironment = OrtEnvironment.getEnvironment()

    private val sessionOptions = OrtSession.SessionOptions().apply {
        setOptimizationLevel(OrtSession.SessionOptions.OptLevel.BASIC_OPT)
    }

    private val session: OrtSession = environment.createSession(modelFile.absolutePath, sessionOptions)

    private val inputName: String = session.inputNames.firstOrNull() ?: DEFAULT_INPUT

    private val outputName: String = session.outputNames.firstOrNull() ?: DEFAULT_OUTPUT

    /**
     * The L2-normalised [DIM]-d embedding of [aligned], a 112x112 face crop. A recycled bitmap yields
     * a zero vector rather than throwing, so a caller iterating detected faces can skip a dead crop.
     */
    suspend fun embed(aligned: Bitmap): FloatArray = withContext(Dispatchers.Default) {
        if (aligned.isRecycled) return@withContext FloatArray(DIM)
        val tensor = toInputTensor(aligned)
        val embedding = try {
            session.run(mapOf(inputName to tensor)).use { result ->
                val named = result.get(outputName)
                floats(if (named.isPresent) named.get() else result.get(0))
            }
        } finally {
            tensor.close()
        }
        normalize(embedding)
    }

    /**
     * [aligned] as the network's input: scaled to [SIZE] square if it is not already, read raw 0-255
     * in blue, green, red channel order and laid out channel-planes-first as a single (1, 3, SIZE,
     * SIZE) batch, which is what SFace was trained on.
     */
    private fun toInputTensor(aligned: Bitmap): OnnxTensor {
        val sized = if (aligned.width == SIZE && aligned.height == SIZE) {
            aligned
        } else {
            Bitmap.createScaledBitmap(aligned, SIZE, SIZE, true)
        }
        val values = try {
            val pixels = IntArray(SIZE * SIZE)
            sized.getPixels(pixels, 0, SIZE, 0, 0, SIZE, SIZE)
            val plane = SIZE * SIZE
            val out = FloatArray(3 * plane)
            for (i in 0 until plane) {
                val pixel = pixels[i]
                out[i] = (pixel and 0xFF).toFloat()
                out[plane + i] = ((pixel shr 8) and 0xFF).toFloat()
                out[2 * plane + i] = ((pixel shr 16) and 0xFF).toFloat()
            }
            out
        } finally {
            // createScaledBitmap hands back the same instance when nothing needs scaling.
            if (sized !== aligned && !sized.isRecycled) sized.recycle()
        }
        val shape = longArrayOf(1, 3, SIZE.toLong(), SIZE.toLong())
        return OnnxTensor.createTensor(environment, FloatBuffer.wrap(values), shape)
    }

    /**
     * [vector] divided by its L2 norm in place, so it lands on the unit sphere and a cosine similarity
     * is a plain dot product. A zero (or non-finite) norm is left untouched, which keeps a degenerate
     * all-zero output finite rather than turning it into NaN.
     */
    private fun normalize(vector: FloatArray): FloatArray {
        var sum = 0.0
        for (value in vector) sum += value.toDouble() * value.toDouble()
        val norm = sqrt(sum).toFloat()
        if (norm <= 0f || !norm.isFinite()) return vector
        for (i in vector.indices) vector[i] = vector[i] / norm
        return vector
    }

    private fun floats(value: OnnxValue): FloatArray {
        val buffer = (value as OnnxTensor).floatBuffer.duplicate()
        buffer.rewind()
        val out = FloatArray(buffer.remaining())
        buffer.get(out)
        return out
    }

    override fun close() {
        runCatching { session.close() }
        runCatching { sessionOptions.close() }
    }

    private companion object {
        const val DEFAULT_INPUT = "data"

        const val DEFAULT_OUTPUT = "fc1"

        /** Square side the network reads. */
        const val SIZE = 112

        /** Length of the embedding the network emits. */
        const val DIM = 128
    }
}
