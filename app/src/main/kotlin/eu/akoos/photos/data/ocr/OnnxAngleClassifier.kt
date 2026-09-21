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
 * The classifier input shape, the two-class output and the session handling below are derived from
 * mobile_ocr, which is MIT licensed:
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
 * Says whether a cropped line is upside down.
 *
 * Text detection has no idea which way up a line runs, so a photo of a page held the wrong way round
 * produces perfectly good outlines with unreadable contents. This model answers that in two classes
 * and costs a fraction of what the reader costs, which is why every crop goes through it rather than
 * only the ones the reader later struggles with.
 *
 * One session per instance, released by [close]; the instance is not safe for concurrent runs and
 * its owner serialises them.
 */
internal class OnnxAngleClassifier(modelPath: String) : AutoCloseable {

    private val environment: OrtEnvironment = OrtEnvironment.getEnvironment()

    private val sessionOptions = OrtSession.SessionOptions().apply {
        setOptimizationLevel(OrtSession.SessionOptions.OptLevel.BASIC_OPT)
    }

    private val session: OrtSession = environment.createSession(modelPath, sessionOptions)

    private val inputName: String = session.inputNames.firstOrNull() ?: DEFAULT_INPUT

    /** For each crop in [crops], whether it has to be read backwards. */
    fun classify(crops: List<Bitmap>, cancellation: OnnxCancellation): BooleanArray {
        if (crops.isEmpty()) return BooleanArray(0)
        cancellation.ensureActive()

        val values = FloatArray(crops.size * 3 * READ_HEIGHT * INPUT_WIDTH)
        crops.forEachIndexed { slot, crop ->
            writeCropInto(crop, upsideDown = false, tensor = values, slot = slot, batchWidth = INPUT_WIDTH)
        }
        val shape = longArrayOf(crops.size.toLong(), 3, READ_HEIGHT.toLong(), INPUT_WIDTH.toLong())
        val tensor = OnnxTensor.createTensor(environment, FloatBuffer.wrap(values), shape)
        return try {
            cancellation.run(session, mapOf(inputName to tensor)).use { outputs ->
                decide(outputs[0] as OnnxTensor, crops.size)
            }
        } finally {
            tensor.close()
        }
    }

    /** The output is one upright score and one turned score per crop, in that order. */
    private fun decide(output: OnnxTensor, count: Int): BooleanArray {
        val buffer = output.floatBuffer.duplicate()
        buffer.rewind()
        val scores = FloatArray(minOf(buffer.remaining(), count * CLASSES))
        buffer.get(scores, 0, scores.size)
        return BooleanArray(count) { index ->
            val offset = index * CLASSES
            if (offset + 1 >= scores.size) false
            else classifierTurnsCrop(upright = scores[offset], upsideDown = scores[offset + 1])
        }
    }

    override fun close() {
        runCatching { session.close() }
        runCatching { sessionOptions.close() }
    }

    private companion object {
        const val DEFAULT_INPUT = "x"
        const val INPUT_WIDTH = 192
        const val CLASSES = 2
    }
}
