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
 * The batching by shape, the input layout and the per-column argmax below are derived from
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
import java.io.File
import java.nio.Buffer
import java.nio.FloatBuffer
import kotlin.math.ceil

/**
 * Turns cropped lines into text.
 *
 * The network reads a squeezed 48 pixel tall strip column by column and answers, for each column, a
 * score over the whole alphabet. Collapsing that into characters is [ctcDecode]'s job; this class
 * owns the session, the input layout and the per-column argmax.
 *
 * Crops are run in shape-alike groups because every crop in one run has to share one width, and
 * mixing a caption with a banner would pad the caption out to the banner's length. Runs are sized by
 * [planReadBatches] rather than by a fixed count, so a run of wide crops carries fewer of them and
 * the output tensor stays roughly the same size whatever the photo holds.
 *
 * One session per instance, released by [close]; the instance is not safe for concurrent runs and
 * its owner serialises them.
 */
internal class OnnxTextReader(modelPath: String, dictionaryFile: File) : AutoCloseable {

    private val environment: OrtEnvironment = OrtEnvironment.getEnvironment()

    private val sessionOptions = OrtSession.SessionOptions().apply {
        setOptimizationLevel(OrtSession.SessionOptions.OptLevel.BASIC_OPT)
    }

    private val session: OrtSession = environment.createSession(modelPath, sessionOptions)

    private val inputName: String = session.inputNames.firstOrNull() ?: DEFAULT_INPUT

    private val dictionary: List<String> =
        buildCharacterDictionary(dictionaryFile.bufferedReader().use { it.readLines() })

    /**
     * Reads [crops], each already turned the right way up by [upsideDown]. The answers come back in
     * the order the crops were given, whatever order they were run in.
     */
    fun read(
        crops: List<Bitmap>,
        upsideDown: BooleanArray,
        cancellation: OnnxCancellation,
    ): List<DecodedText> {
        if (crops.isEmpty()) return emptyList()

        val order = crops.indices.sortedBy { crops[it].width.toFloat() / crops[it].height.coerceAtLeast(1) }
        val ratios = order.map { crops[it].width.toFloat() / crops[it].height.coerceAtLeast(1) }
        val answers = MutableList(crops.size) { EMPTY }

        var start = 0
        for (size in planReadBatches(ratios)) {
            cancellation.ensureActive()
            val slots = order.subList(start, start + size)
            val batchWidth = readBatchWidth(ratios[start + size - 1])
            runBatch(slots.map { crops[it] }, slots.map { upsideDown[it] }, batchWidth, cancellation)
                .forEachIndexed { position, decoded -> answers[slots[position]] = decoded }
            start += size
        }
        return answers
    }

    private fun runBatch(
        crops: List<Bitmap>,
        upsideDown: List<Boolean>,
        batchWidth: Int,
        cancellation: OnnxCancellation,
    ): List<DecodedText> {
        val values = FloatArray(crops.size * 3 * READ_HEIGHT * batchWidth)
        val contentWidths = IntArray(crops.size) { slot ->
            writeCropInto(crops[slot], upsideDown[slot], values, slot, batchWidth)
        }
        val shape = longArrayOf(crops.size.toLong(), 3, READ_HEIGHT.toLong(), batchWidth.toLong())
        val tensor = OnnxTensor.createTensor(environment, FloatBuffer.wrap(values), shape)
        return try {
            cancellation.run(session, mapOf(inputName to tensor)).use { outputs ->
                decode(outputs[0] as OnnxTensor, crops.size, contentWidths, batchWidth)
            }
        } finally {
            tensor.close()
        }
    }

    /**
     * Picks the winning alphabet entry per column and collapses the result.
     *
     * The output is read one column at a time into a single reused row rather than copied whole: an
     * alphabet of eighteen thousand entries across a full run is millions of floats, and this runs
     * while the viewer is already holding a full-resolution photo.
     *
     * Columns past where a crop's own picture ended are padding, and are cut before decoding so a
     * short line does not pick up characters out of the zeroes it was padded with.
     */
    private fun decode(
        output: OnnxTensor,
        count: Int,
        contentWidths: IntArray,
        batchWidth: Int,
    ): List<DecodedText> {
        val shape = output.info.shape
        if (shape.size < 3) return List(count) { EMPTY }
        val columns = shape[1].toInt()
        val alphabet = shape[2].toInt()
        if (columns <= 0 || alphabet <= 0) return List(count) { EMPTY }

        val buffer = output.floatBuffer.duplicate()
        val row = FloatArray(alphabet)
        val indices = IntArray(columns)
        val probabilities = FloatArray(columns)

        return List(count) { slot ->
            val used = usedColumns(columns, contentWidths.getOrElse(slot) { batchWidth }, batchWidth)
            val slotOffset = slot.toLong() * columns * alphabet
            for (column in 0 until used) {
                // Cast so the call binds to the base method, which every API level has.
                (buffer as Buffer).position((slotOffset + column.toLong() * alphabet).toInt())
                buffer.get(row, 0, alphabet)
                var best = 0
                var bestScore = row[0]
                for (entry in 1 until alphabet) {
                    if (row[entry] > bestScore) {
                        bestScore = row[entry]
                        best = entry
                    }
                }
                indices[column] = best
                probabilities[column] = bestScore
            }
            ctcDecode(indices.copyOf(used), probabilities.copyOf(used), dictionary)
        }
    }

    /**
     * How many output columns belong to a crop that covered [contentWidth] of [batchWidth] pixels.
     * One column of slack, because a character sitting against the end of the picture spills into
     * the column after it and losing the last letter of every line would be worse than reading one
     * column of padding.
     */
    private fun usedColumns(columns: Int, contentWidth: Int, batchWidth: Int): Int {
        if (batchWidth <= 0 || contentWidth >= batchWidth) return columns
        val covered = ceil(columns.toDouble() * contentWidth / batchWidth).toInt() + 1
        return covered.coerceIn(1, columns)
    }

    override fun close() {
        runCatching { session.close() }
        runCatching { sessionOptions.close() }
    }

    private companion object {
        const val DEFAULT_INPUT = "x"
        val EMPTY = DecodedText("", 0f)
    }
}
