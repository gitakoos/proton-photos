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
 * The session handling and the NCHW input layout follow the recognition rail in this project. The RGB,
 * 0-1 normalised input and the single (1, 25200, 16) output row (a decoded box, an objectness, five
 * landmark pairs and a face-class score) follow the published YOLOv5-face detection format, which is
 * GPL-3.0 licensed:
 *
 *   Copyright (c) 2021 the YOLOv5-face contributors
 */

package eu.akoos.photos.data.face

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OnnxValue
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.PointF
import android.graphics.RectF
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.FloatBuffer
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Finds where the faces are in a still bitmap by running the YOLOv5-face detector network once.
 *
 * One session per instance, opened from [modelFile] when the instance is built and released by
 * [close]; opening it costs far more than a run, so a caller holds the instance for as long as it
 * needs detections and closes it after. [detect] is a one-shot call meant for a user action, not a
 * per-frame loop. The instance is not safe for concurrent runs and its owner serialises them.
 *
 * Coordinates come back in the original bitmap's own pixel space. The input is letterboxed into a
 * fixed square with the picture centred and grey padding filling the margins, so mapping a result back
 * to the source undoes the centre offset and then the letterbox scale.
 *
 * End-to-end numbers can only be trusted once the real detector asset is side-loaded or published;
 * until then this codes against YOLOv5-face's documented input and output format.
 */
class FaceDetector(
    modelFile: File,
    /** Minimum confidence to keep a face. The library walk holds this at [SCORE_THRESHOLD] for
     *  precision; an on-demand pass on one photo the user asked about can drop it to catch a face the
     *  walk missed, since the few extra false positives are on that one photo and are reviewed. */
    private val scoreThreshold: Float = SCORE_THRESHOLD,
    /** The square the frame is letterboxed into before detection. The static YOLOv5-face export reads a
     *  fixed [INPUT] square, so a run always uses [INPUT]; the parameter stays only so a caller can name
     *  the size it feeds. */
    private val inputSize: Int = INPUT,
) : AutoCloseable {

    private val environment: OrtEnvironment = OrtEnvironment.getEnvironment()

    private val sessionOptions = OrtSession.SessionOptions().apply {
        setOptimizationLevel(OrtSession.SessionOptions.OptLevel.BASIC_OPT)
    }

    private val session: OrtSession = environment.createSession(modelFile.absolutePath, sessionOptions)

    private val inputName: String = session.inputNames.firstOrNull() ?: DEFAULT_INPUT

    private val outputName: String = session.outputNames.firstOrNull() ?: DEFAULT_OUTPUT

    /** Running count of boxes the min-size gate has dropped since this instance was built. The library
     *  walk opens a fresh detector per pass and reads this at the pass end to log the junk-size split;
     *  it never changes what [detect] returns. */
    val droppedTooSmall = AtomicInteger(0)

    /** The faces in [bitmap], in its own pixel coordinates. Empty when there is nothing to find. */
    suspend fun detect(bitmap: Bitmap): List<DetectedFace> = withContext(Dispatchers.Default) {
        if (bitmap.isRecycled) return@withContext emptyList()
        val sourceWidth = bitmap.width
        val sourceHeight = bitmap.height
        if (sourceWidth < MIN_SIDE || sourceHeight < MIN_SIDE) return@withContext emptyList()

        val scale = min(inputSize.toFloat() / sourceWidth, inputSize.toFloat() / sourceHeight)
        val fittedWidth = (sourceWidth * scale).roundToInt().coerceIn(1, inputSize)
        val fittedHeight = (sourceHeight * scale).roundToInt().coerceIn(1, inputSize)
        val padX = (inputSize - fittedWidth) / 2
        val padY = (inputSize - fittedHeight) / 2

        val tensor = toInputTensor(bitmap, fittedWidth, fittedHeight, padX, padY)
        val output = try {
            session.run(mapOf(inputName to tensor)).use { result ->
                val named = result.get(outputName)
                floats(if (named.isPresent) named.get() else result.get(0))
            }
        } finally {
            tensor.close()
        }

        val candidates = decode(output)
        if (candidates.isEmpty()) return@withContext emptyList()
        val mapped = mapBack(
            nms(candidates), scale, padX.toFloat(), padY.toFloat(),
            sourceWidth.toFloat(), sourceHeight.toFloat(),
        )
        // Drop a box whose shorter edge falls under the min-size floor (an absolute pixel floor and a
        // fraction of the frame), so a tiny distant face is rejected rather than embedded as noise.
        val minEdge = max(MIN_FACE_PX.toFloat(), MIN_FACE_FRACTION * min(sourceWidth, sourceHeight))
        val kept = mapped.filter { min(it.box.width(), it.box.height()) >= minEdge }
        droppedTooSmall.addAndGet(mapped.size - kept.size)
        kept
    }

    /**
     * [bitmap] letterboxed into a fixed [INPUT] square: scaled to fit with the aspect ratio kept, the
     * picture centred at ([padX], [padY]), and the margins padded grey. Pixels are laid out
     * channel-planes-first as a single (1, 3, INPUT, INPUT) batch in red, green, blue order, each
     * divided by 255, which is what YOLOv5-face reads.
     */
    private fun toInputTensor(
        bitmap: Bitmap,
        fittedWidth: Int,
        fittedHeight: Int,
        padX: Int,
        padY: Int,
    ): OnnxTensor {
        val scaled = Bitmap.createScaledBitmap(bitmap, fittedWidth, fittedHeight, true)
        val letterboxed = Bitmap.createBitmap(inputSize, inputSize, Bitmap.Config.ARGB_8888)
        val values = try {
            Canvas(letterboxed).apply {
                drawColor(Color.rgb(PAD_GREY, PAD_GREY, PAD_GREY))
                drawBitmap(scaled, padX.toFloat(), padY.toFloat(), null)
            }
            val pixels = IntArray(inputSize * inputSize)
            letterboxed.getPixels(pixels, 0, inputSize, 0, 0, inputSize, inputSize)
            val plane = inputSize * inputSize
            val out = FloatArray(3 * plane)
            for (i in 0 until plane) {
                val pixel = pixels[i]
                out[i] = ((pixel shr 16) and 0xFF) / 255f
                out[plane + i] = ((pixel shr 8) and 0xFF) / 255f
                out[2 * plane + i] = (pixel and 0xFF) / 255f
            }
            out
        } finally {
            // createScaledBitmap hands back the same instance when nothing needs scaling.
            if (scaled !== bitmap && !scaled.isRecycled) scaled.recycle()
            if (!letterboxed.isRecycled) letterboxed.recycle()
        }
        val shape = longArrayOf(1, 3, inputSize.toLong(), inputSize.toLong())
        return OnnxTensor.createTensor(environment, FloatBuffer.wrap(values), shape)
    }

    /**
     * Turns the flat (1, 25200, 16) output into candidate faces above [scoreThreshold], in the
     * letterboxed square's coordinates. Each 16-value row is a decoded box (centre x, centre y, width,
     * height), an objectness, five landmark (x, y) pairs in the ArcFace order (left eye, right eye,
     * nose, left mouth corner, right mouth corner) and a single face-class score; the kept confidence is
     * the objectness times the class score.
     */
    private fun decode(output: FloatArray): List<Candidate> {
        val candidates = ArrayList<Candidate>()
        val rows = output.size / ROW
        for (r in 0 until rows) {
            val base = r * ROW
            val score = output[base + 4] * output[base + 15]
            if (score < scoreThreshold) continue

            val centreX = output[base]
            val centreY = output[base + 1]
            val width = output[base + 2]
            val height = output[base + 3]
            val face = RectF(
                centreX - width / 2f,
                centreY - height / 2f,
                centreX + width / 2f,
                centreY + height / 2f,
            )

            val landmarks = ArrayList<PointF>(LANDMARKS)
            for (k in 0 until LANDMARKS) {
                landmarks.add(PointF(output[base + 5 + 2 * k], output[base + 6 + 2 * k]))
            }
            candidates.add(Candidate(face, landmarks, score))
        }
        return candidates
    }

    /** Greedy non-max suppression: keep the surest, drop anything overlapping it by [NMS_IOU] or more. */
    private fun nms(candidates: List<Candidate>): List<Candidate> {
        val order = candidates.sortedByDescending { it.score }
        val suppressed = BooleanArray(order.size)
        val kept = ArrayList<Candidate>()
        for (i in order.indices) {
            if (suppressed[i]) continue
            val current = order[i]
            kept.add(current)
            for (j in i + 1 until order.size) {
                if (!suppressed[j] && iou(current.box, order[j].box) >= NMS_IOU) suppressed[j] = true
            }
        }
        return kept
    }

    /**
     * Undoes the letterbox: subtract the centre padding, then divide every coordinate by the fit
     * [scale], and clamp boxes to the picture. Landmarks are left unclamped so an eye near an edge keeps
     * its true position.
     */
    private fun mapBack(
        kept: List<Candidate>,
        scale: Float,
        padX: Float,
        padY: Float,
        width: Float,
        height: Float,
    ): List<DetectedFace> {
        val inverse = 1f / scale
        return kept.map { candidate ->
            val box = RectF(
                ((candidate.box.left - padX) * inverse).coerceIn(0f, width),
                ((candidate.box.top - padY) * inverse).coerceIn(0f, height),
                ((candidate.box.right - padX) * inverse).coerceIn(0f, width),
                ((candidate.box.bottom - padY) * inverse).coerceIn(0f, height),
            )
            val landmarks = candidate.landmarks.map { PointF((it.x - padX) * inverse, (it.y - padY) * inverse) }
            DetectedFace(box, landmarks, candidate.score)
        }
    }

    private fun iou(a: RectF, b: RectF): Float {
        val interWidth = (min(a.right, b.right) - max(a.left, b.left)).coerceAtLeast(0f)
        val interHeight = (min(a.bottom, b.bottom) - max(a.top, b.top)).coerceAtLeast(0f)
        val intersection = interWidth * interHeight
        val union = area(a) + area(b) - intersection
        return if (union <= 0f) 0f else intersection / union
    }

    private fun area(r: RectF): Float =
        (r.right - r.left).coerceAtLeast(0f) * (r.bottom - r.top).coerceAtLeast(0f)

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

    /** A survivor of thresholding, in the letterboxed square's coordinates, awaiting suppression. */
    private class Candidate(val box: RectF, val landmarks: List<PointF>, val score: Float)

    private companion object {
        const val DEFAULT_INPUT = "input"

        const val DEFAULT_OUTPUT = "output"

        /** Square side the network reads; the input is letterboxed into it. The static YOLOv5-face export
         *  is fixed at this size, so it is the only size a run uses. */
        const val INPUT = 640

        /** Values per output row: box (4) + objectness (1) + five landmark pairs (10) + face class (1). */
        const val ROW = 16

        /** The count of landmark points the detector emits per face. */
        const val LANDMARKS = 5

        /** Grey the letterbox margins are padded with, the value YOLOv5 letterboxing uses. */
        const val PAD_GREY = 114

        /** Below this on either side there is nothing worth detecting; skip the run. */
        const val MIN_SIDE = 24

        /** Minimum detector confidence to keep a face, the objectness times the face-class score. */
        const val SCORE_THRESHOLD = 0.6f
        const val NMS_IOU = 0.4f

        /** Absolute floor, in source pixels, on a kept face box's shorter edge. */
        const val MIN_FACE_PX = 40

        /** Relative floor on a kept face box's shorter edge, as a fraction of the source's shorter edge.
         *  The gate keeps the larger of this and [MIN_FACE_PX], so a tiny distant face is dropped. */
        const val MIN_FACE_FRACTION = 0.03f
    }
}
