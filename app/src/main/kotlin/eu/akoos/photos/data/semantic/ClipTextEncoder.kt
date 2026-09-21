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

import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OnnxValue
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import eu.akoos.photos.domain.usecase.normalize
import java.io.File
import java.nio.IntBuffer
import java.nio.LongBuffer
import kotlin.math.min

/**
 * Turns one tokenised search phrase into a [DIM]-d CLIP text embedding by running the text
 * encoder once.
 *
 * The session opens lazily on the first [encode] from the model file and is released by [close]. [encode]
 * is not safe for concurrent runs and its owner serialises them.
 *
 * The caller passes the token ids the tokenizer already padded or truncated to the context length; the
 * ids are still fitted to the sequence length the session declares (padded with zeros or truncated) as a
 * guard. The tensor is built at the input's declared integer type, int32 for the int32 export and int64
 * otherwise, shaped (1, sequence length). The output vector is L2-normalised into the same space as
 * [ClipImageEncoder], so a text and an image embedding compare by a plain cosine (dot product).
 */
class ClipTextEncoder(private val modelFile: File) : AutoCloseable {

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

    private val inputInfo: TensorInfo? by lazy { session.inputInfo[inputName]?.info as? TensorInfo }

    /** Context length the network declares (index 1), or [CONTEXT_LENGTH] when it is left dynamic. */
    private val sequenceLength: Int by lazy {
        inputInfo?.shape?.getOrNull(1)?.toInt()?.takeIf { it > 0 } ?: CONTEXT_LENGTH
    }

    /** Integer type the input declares; int32 unless the model asks for int64. */
    private val inputType: OnnxJavaType by lazy { inputInfo?.type ?: OnnxJavaType.INT32 }

    /**
     * The L2-normalised text embedding of [tokenIds], the token ids the tokenizer produced. The ids are
     * fitted to the declared sequence length before the run: a longer sequence keeps its first ids and a
     * shorter one is padded with zeros.
     */
    fun encode(tokenIds: IntArray): FloatArray {
        val tensor = toInputTensor(fitToLength(tokenIds, sequenceLength))
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

    /** [tokens] as a (1, size) tensor at the input's declared integer type. */
    private fun toInputTensor(tokens: IntArray): OnnxTensor {
        val shape = longArrayOf(1, tokens.size.toLong())
        return if (inputType == OnnxJavaType.INT64) {
            val widened = LongArray(tokens.size) { tokens[it].toLong() }
            OnnxTensor.createTensor(environment, LongBuffer.wrap(widened), shape)
        } else {
            OnnxTensor.createTensor(environment, IntBuffer.wrap(tokens), shape)
        }
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

        /** Token count the text encoder reads when the model leaves the dimension dynamic. */
        const val CONTEXT_LENGTH = 77

        /** Length of the embedding the encoder emits. */
        const val DIM = 512
    }
}

/**
 * Fits [tokenIds] to exactly [length] ids: a longer sequence keeps its first [length], a shorter one is
 * padded to [length] with zeros. Pure, so the length guard is testable without a model.
 */
internal fun fitToLength(tokenIds: IntArray, length: Int): IntArray {
    if (tokenIds.size == length) return tokenIds
    val out = IntArray(length)
    System.arraycopy(tokenIds, 0, out, 0, min(tokenIds.size, length))
    return out
}
