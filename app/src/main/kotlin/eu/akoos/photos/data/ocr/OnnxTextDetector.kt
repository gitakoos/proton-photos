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
 * The preprocessing and the session handling below are derived from mobile_ocr, which is MIT
 * licensed:
 *
 *   Copyright (c) 2025 Laurens Priem
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this software and
 * associated documentation files (the "Software"), to deal in the Software without restriction,
 * including without limitation the rights to use, copy, modify, merge, publish, distribute,
 * sublicense, and/or sell copies of the Software, subject to the conditions of the MIT License.
 */

package eu.akoos.photos.data.ocr

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.graphics.Bitmap
import java.nio.FloatBuffer

/**
 * Runs the text-detection network over a frame and reports where the words are.
 *
 * One session per instance, opened when the instance is built and released by [close]; opening it
 * costs far more than a run, so it is held for as long as the surface that reads text is on screen.
 * The instance is not safe for concurrent runs and its owner serialises them.
 */
internal class OnnxTextDetector(modelPath: String) : AutoCloseable {

    private val environment: OrtEnvironment = OrtEnvironment.getEnvironment()

    private val sessionOptions = OrtSession.SessionOptions().apply {
        setOptimizationLevel(OrtSession.SessionOptions.OptLevel.BASIC_OPT)
    }

    private val session: OrtSession = environment.createSession(modelPath, sessionOptions)

    private val inputName: String = session.inputNames.firstOrNull() ?: DEFAULT_INPUT

    /**
     * Where the words are in [bitmap]'s own pixel coordinates. [cancellation] aborts a run that is
     * already inside the runtime, which is the only way a swipe can drop work that has started.
     */
    fun detect(bitmap: Bitmap, cancellation: OnnxCancellation): List<DetectedQuad> {
        cancellation.ensureActive()
        // Asked of the runtime on every run rather than held: what the process may grow to is the
        // one input to this rule that is not the frame, and reading it here keeps the rule itself
        // arithmetic that can be checked without a device.
        val limit = detectionLimitSide(
            bitmap.width,
            bitmap.height,
            Runtime.getRuntime().maxMemory(),
        )
        val (inputWidth, inputHeight) = detectorInputSize(bitmap.width, bitmap.height, limit)

        // The input is three floats per cell of the grid and the runtime keeps a copy of it, which
        // together is by far the largest thing this holds. Reading the answer out and letting both
        // go before the post-processing starts is what keeps the peak to the run itself rather than
        // to the run plus everything that follows it.
        val tensor = toInputTensor(bitmap, inputWidth, inputHeight)
        val map = try {
            cancellation.run(session, mapOf(inputName to tensor)).use { outputs ->
                probabilityMap(outputs[0] as OnnxTensor, inputWidth, inputHeight)
            }
        } finally {
            tensor.close()
        }

        cancellation.ensureActive()
        return detectQuads(map, bitmap.width, bitmap.height)
    }

    /**
     * The frame as the network expects it: scaled to the input grid, scaled to 0..1, standardised
     * per channel, and laid out channel-planes-first in blue, green, red order to match the data the
     * network was trained on.
     */
    private fun toInputTensor(bitmap: Bitmap, width: Int, height: Int): OnnxTensor {
        val scaled = Bitmap.createScaledBitmap(bitmap, width, height, true)
        val values = try {
            val pixels = IntArray(width * height)
            scaled.getPixels(pixels, 0, width, 0, 0, width, height)
            val plane = width * height
            val values = FloatArray(3 * plane)
            for (i in 0 until plane) {
                val pixel = pixels[i]
                val blue = (pixel and 0xFF) * INV_255
                val green = ((pixel shr 8) and 0xFF) * INV_255
                val red = ((pixel shr 16) and 0xFF) * INV_255
                values[i] = (blue - MEAN[0]) / DEVIATION[0]
                values[i + plane] = (green - MEAN[1]) / DEVIATION[1]
                values[i + 2 * plane] = (red - MEAN[2]) / DEVIATION[2]
            }
            values
        } finally {
            // createScaledBitmap hands back the very same instance when nothing needs scaling.
            if (scaled !== bitmap && !scaled.isRecycled) scaled.recycle()
        }
        val shape = longArrayOf(1, 3, height.toLong(), width.toLong())
        return OnnxTensor.createTensor(environment, FloatBuffer.wrap(values), shape)
    }

    /** The first output channel, which is the per-cell probability of being part of a word. */
    private fun probabilityMap(output: OnnxTensor, width: Int, height: Int): ProbabilityMap {
        val buffer = output.floatBuffer.duplicate()
        buffer.rewind()
        val cells = width * height
        require(buffer.remaining() >= cells) { "detector output is smaller than its own input grid" }
        val values = FloatArray(cells)
        buffer.get(values, 0, cells)
        return ProbabilityMap(width, height, values)
    }

    override fun close() {
        runCatching { session.close() }
        runCatching { sessionOptions.close() }
    }

    private companion object {
        const val DEFAULT_INPUT = "x"
        const val INV_255 = 1f / 255f
        val MEAN = floatArrayOf(0.485f, 0.456f, 0.406f)
        val DEVIATION = floatArrayOf(0.229f, 0.224f, 0.225f)
    }
}
