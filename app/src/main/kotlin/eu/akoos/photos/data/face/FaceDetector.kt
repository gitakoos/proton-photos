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
 * The session handling and the NCHW input layout follow the OCR rail in this project. The YuNet prior
 * decode (grid-cell centres, the centre-offset-and-exponent box form, the per-cell keypoint offsets,
 * and the class-times-objectness score) and the raw BGR 0-255 input follow the published OpenCV Zoo
 * YuNet face-detection format, which is MIT licensed:
 *
 *   Copyright (c) 2023 OpenCV Zoo, Shiqi Yu and contributors
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
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Finds where the faces are in a still bitmap by running the YuNet detector network once.
 *
 * One session per instance, opened from [modelFile] when the instance is built and released by
 * [close]; opening it costs far more than a run, so a caller holds the instance for as long as it
 * needs detections and closes it after. [detect] is a one-shot call meant for a user action, not a
 * per-frame loop. The instance is not safe for concurrent runs and its owner serialises them.
 *
 * Coordinates come back in the original bitmap's own pixel space. Because the input is letterboxed
 * into a fixed square with the picture pinned to the top-left and black padding filling the bottom
 * and right, mapping a result back to the source is a single divide by the letterbox scale with no
 * offset to undo.
 *
 * End-to-end numbers can only be trusted once the real YuNet asset is side-loaded or published; until
 * then this codes against YuNet's documented input and output format.
 */
class FaceDetector(
    modelFile: File,
    /** Minimum confidence to keep a face. The library walk holds this at [SCORE_THRESHOLD] for
     *  precision; an on-demand pass on one photo the user asked about can drop it to catch a face the
     *  walk missed, since the few extra false positives are on that one photo and are reviewed. */
    private val scoreThreshold: Float = SCORE_THRESHOLD,
    /** The square the frame is letterboxed into before detection. YuNet's input is fixed at [INPUT],
     *  so a run always uses [INPUT]; the parameter stays only so a caller can name the size it feeds. */
    private val inputSize: Int = INPUT,
) : AutoCloseable {

    private val environment: OrtEnvironment = OrtEnvironment.getEnvironment()

    private val sessionOptions = OrtSession.SessionOptions().apply {
        setOptimizationLevel(OrtSession.SessionOptions.OptLevel.BASIC_OPT)
    }

    private val session: OrtSession = environment.createSession(modelFile.absolutePath, sessionOptions)

    private val inputName: String = session.inputNames.firstOrNull() ?: DEFAULT_INPUT

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
        val tensor = toInputTensor(bitmap, scale)
        val heads = try {
            session.run(mapOf(inputName to tensor)).use { readHeads(it) }
        } finally {
            tensor.close()
        }
        if (heads.size < STRIDES.size) return@withContext emptyList()

        val candidates = decode(heads)
        if (candidates.isEmpty()) return@withContext emptyList()
        val mapped = mapBack(nms(candidates), scale, sourceWidth.toFloat(), sourceHeight.toFloat())
        // Drop a box whose shorter edge falls under the min-size floor (an absolute pixel floor and a
        // fraction of the frame), so a tiny distant face is rejected rather than embedded as noise.
        val minEdge = max(MIN_FACE_PX.toFloat(), MIN_FACE_FRACTION * min(sourceWidth, sourceHeight))
        val kept = mapped.filter { min(it.box.width(), it.box.height()) >= minEdge }
        droppedTooSmall.addAndGet(mapped.size - kept.size)
        kept
    }

    /**
     * [bitmap] letterboxed into a fixed [INPUT] square: scaled to fit with the aspect ratio kept, the
     * picture at the top-left origin, and the remainder padded black. Pixels are laid out
     * channel-planes-first as a single (1, 3, INPUT, INPUT) batch in blue, green, red order at their
     * raw 0-255 values with no normalisation, which is what YuNet reads.
     */
    private fun toInputTensor(bitmap: Bitmap, scale: Float): OnnxTensor {
        val fittedWidth = (bitmap.width * scale).roundToInt().coerceIn(1, inputSize)
        val fittedHeight = (bitmap.height * scale).roundToInt().coerceIn(1, inputSize)
        val scaled = Bitmap.createScaledBitmap(bitmap, fittedWidth, fittedHeight, true)
        val letterboxed = Bitmap.createBitmap(inputSize, inputSize, Bitmap.Config.ARGB_8888)
        val values = try {
            Canvas(letterboxed).apply {
                drawColor(Color.BLACK)
                drawBitmap(scaled, 0f, 0f, null)
            }
            val pixels = IntArray(inputSize * inputSize)
            letterboxed.getPixels(pixels, 0, inputSize, 0, 0, inputSize, inputSize)
            val plane = inputSize * inputSize
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
            if (scaled !== bitmap && !scaled.isRecycled) scaled.recycle()
            if (!letterboxed.isRecycled) letterboxed.recycle()
        }
        val shape = longArrayOf(1, 3, inputSize.toLong(), inputSize.toLong())
        return OnnxTensor.createTensor(environment, FloatBuffer.wrap(values), shape)
    }

    /**
     * The twelve output tensors as one quadruplet (cls, obj, bbox, kps) per stride.
     *
     * Bound by name when the session exposes YuNet's `cls_s` / `obj_s` / `bbox_s` / `kps_s` outputs;
     * otherwise by order, taken as every cls, then every obj, then every bbox, then every kps, each
     * group ascending by stride. An export missing any head yields nothing.
     */
    private fun readHeads(result: OrtSession.Result): List<RawHead> {
        val groups = STRIDES.size
        val byName = STRIDES.all { stride ->
            result.get(clsName(stride)).isPresent &&
                result.get(objName(stride)).isPresent &&
                result.get(bboxName(stride)).isPresent &&
                result.get(kpsName(stride)).isPresent
        }
        if (!byName && result.size() < groups * 4) return emptyList()
        return STRIDES.mapIndexed { index, stride ->
            if (byName) {
                RawHead(
                    cls = floats(result.get(clsName(stride)).get()),
                    obj = floats(result.get(objName(stride)).get()),
                    bbox = floats(result.get(bboxName(stride)).get()),
                    kps = floats(result.get(kpsName(stride)).get()),
                )
            } else {
                RawHead(
                    cls = floats(result.get(index)),
                    obj = floats(result.get(index + groups)),
                    bbox = floats(result.get(index + groups * 2)),
                    kps = floats(result.get(index + groups * 3)),
                )
            }
        }
    }

    /**
     * Turns each stride's heads into candidate faces above [scoreThreshold], in the letterboxed
     * square's coordinates. YuNet places one prior at each grid cell, row-major, so prior `i` lands in
     * cell (col, row) with col = i modulo the grid side and row = i over it, and its centre is at
     * (col*s, row*s). The score is the geometric mean of the clamped class and objectness outputs. A
     * box is that cell pushed by its predicted centre offset and sized by the exponential of its width
     * and height; a landmark is that cell plus its predicted offset. Every distance is in grid units
     * and so multiplied by the stride.
     */
    private fun decode(heads: List<RawHead>): List<Candidate> {
        val candidates = mutableListOf<Candidate>()
        for ((index, stride) in STRIDES.withIndex()) {
            val head = heads[index]
            val gridSide = inputSize / stride
            val count = minOf(
                gridSide * gridSide,
                head.cls.size,
                head.obj.size,
                head.bbox.size / 4,
                head.kps.size / (LANDMARKS * 2),
            )
            for (i in 0 until count) {
                val score = sqrt(head.cls[i].coerceIn(0f, 1f) * head.obj[i].coerceIn(0f, 1f))
                if (score < scoreThreshold) continue

                val col = (i % gridSide).toFloat()
                val row = (i / gridSide).toFloat()

                val box = i * 4
                val centreX = (col + head.bbox[box]) * stride
                val centreY = (row + head.bbox[box + 1]) * stride
                val width = exp(head.bbox[box + 2].toDouble()).toFloat() * stride
                val height = exp(head.bbox[box + 3].toDouble()).toFloat() * stride
                val face = RectF(
                    centreX - width / 2f,
                    centreY - height / 2f,
                    centreX + width / 2f,
                    centreY + height / 2f,
                )

                val point = i * LANDMARKS * 2
                val landmarks = ArrayList<PointF>(LANDMARKS)
                for (k in 0 until LANDMARKS) {
                    landmarks.add(
                        PointF(
                            (col + head.kps[point + 2 * k]) * stride,
                            (row + head.kps[point + 2 * k + 1]) * stride,
                        ),
                    )
                }
                candidates.add(Candidate(face, landmarks, score))
            }
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
     * Undoes the letterbox: divide every coordinate by the fit [scale] (no offset, the padding was
     * bottom and right) and clamp boxes to the picture. Landmarks are left unclamped so an eye near an
     * edge keeps its true position.
     */
    private fun mapBack(
        kept: List<Candidate>,
        scale: Float,
        width: Float,
        height: Float,
    ): List<DetectedFace> {
        val inverse = 1f / scale
        return kept.map { candidate ->
            val box = RectF(
                (candidate.box.left * inverse).coerceIn(0f, width),
                (candidate.box.top * inverse).coerceIn(0f, height),
                (candidate.box.right * inverse).coerceIn(0f, width),
                (candidate.box.bottom * inverse).coerceIn(0f, height),
            )
            val landmarks = candidate.landmarks.map { PointF(it.x * inverse, it.y * inverse) }
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

    private fun clsName(stride: Int): String = "cls_$stride"

    private fun objName(stride: Int): String = "obj_$stride"

    private fun bboxName(stride: Int): String = "bbox_$stride"

    private fun kpsName(stride: Int): String = "kps_$stride"

    override fun close() {
        runCatching { session.close() }
        runCatching { sessionOptions.close() }
    }

    /** One stride's raw output floats, still in the network's grid units. */
    private class RawHead(
        val cls: FloatArray,
        val obj: FloatArray,
        val bbox: FloatArray,
        val kps: FloatArray,
    )

    /** A survivor of thresholding, in the letterboxed square's coordinates, awaiting suppression. */
    private class Candidate(val box: RectF, val landmarks: List<PointF>, val score: Float)

    private companion object {
        const val DEFAULT_INPUT = "input"

        /** Square side the network reads; the input is letterboxed into it. YuNet's input is fixed at
         *  this size, so it is the only size a run uses. */
        const val INPUT = 640

        /** The count of keypoints YuNet emits per face. */
        const val LANDMARKS = 5

        /** Below this on either side there is nothing worth detecting; skip the run. */
        const val MIN_SIDE = 24

        /** Minimum detector confidence to keep a face. Held at 0.6: dropping lower does raise
         *  self-photo recall but floods a full library with false-positive detections on buildings,
         *  landscapes and textures, which then form junk clusters and pollute people. A clean, precise
         *  set of faces is worth more than raw recall; a person's genuinely distant faces are added
         *  through manual add and the suggestion review instead. */
        const val SCORE_THRESHOLD = 0.6f
        const val NMS_IOU = 0.4f

        /** Absolute floor, in source pixels, on a kept face box's shorter edge. */
        const val MIN_FACE_PX = 40

        /** Relative floor on a kept face box's shorter edge, as a fraction of the source's shorter edge.
         *  The gate keeps the larger of this and [MIN_FACE_PX], so a tiny distant face is dropped. */
        const val MIN_FACE_FRACTION = 0.03f

        /** Feature-map strides, ascending; each contributes one cls/obj/bbox/kps quadruplet. */
        val STRIDES = intArrayOf(8, 16, 32)
    }
}
