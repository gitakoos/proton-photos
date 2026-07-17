package eu.akoos.photos.mlspike

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.graphics.Bitmap
import java.nio.FloatBuffer
import java.nio.LongBuffer
import kotlin.math.sqrt

/**
 * SPIKE-ONLY (debug source set): on-device MobileCLIP-S2 encoder via ONNX Runtime.
 * Fully local inference, no network. Proves the magic-search pipeline runs on the device;
 * the productionised version will live in the main source set behind the opt-in toggle.
 *
 * Interface verified against the Xenova/mobileclip_s2 fp32 ONNX:
 *  vision: pixel_values[1,3,256,256] float -> image_embeds[1,512]
 *  text:   input_ids[1,seq] int64        -> text_embeds[1,512]
 * Preprocess: resize shortest-edge 256 -> center-crop 256 -> /255, no mean/std normalize.
 */
class ClipEncoder(visionModelPath: String, textModelPath: String) : AutoCloseable {

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val vision: OrtSession = env.createSession(visionModelPath, OrtSession.SessionOptions())
    private val text: OrtSession = env.createSession(textModelPath, OrtSession.SessionOptions())

    /** Preprocess -> vision encoder -> L2-normalized 512-d image embedding. */
    fun encodeImage(bitmap: Bitmap): FloatArray {
        val input = preprocess(bitmap)
        val shape = longArrayOf(1, 3, SIZE.toLong(), SIZE.toLong())
        OnnxTensor.createTensor(env, FloatBuffer.wrap(input), shape).use { t ->
            vision.run(mapOf("pixel_values" to t)).use { r ->
                @Suppress("UNCHECKED_CAST")
                val out = (r.get("image_embeds").get().value as Array<FloatArray>)[0]
                return l2(out)
            }
        }
    }

    /** input_ids (padded to 77, pad id 0) -> text encoder -> L2-normalized 512-d text embedding. */
    fun encodeText(tokenIds: LongArray): FloatArray {
        val shape = longArrayOf(1, tokenIds.size.toLong())
        OnnxTensor.createTensor(env, LongBuffer.wrap(tokenIds), shape).use { t ->
            text.run(mapOf("input_ids" to t)).use { r ->
                @Suppress("UNCHECKED_CAST")
                val out = (r.get("text_embeds").get().value as Array<FloatArray>)[0]
                return l2(out)
            }
        }
    }

    /** Cosine similarity of two already-L2-normalized vectors (plain dot product). */
    fun cosine(a: FloatArray, b: FloatArray): Float {
        var s = 0f
        for (i in a.indices) s += a[i] * b[i]
        return s
    }

    private fun preprocess(bmp: Bitmap): FloatArray {
        val w = bmp.width
        val h = bmp.height
        val scale = SIZE.toFloat() / minOf(w, h)
        val sw = Math.round(w * scale)
        val sh = Math.round(h * scale)
        val scaled = Bitmap.createScaledBitmap(bmp, sw, sh, true)
        val left = (sw - SIZE) / 2
        val top = (sh - SIZE) / 2
        val crop = Bitmap.createBitmap(scaled, left, top, SIZE, SIZE)

        val px = IntArray(SIZE * SIZE)
        crop.getPixels(px, 0, SIZE, 0, 0, SIZE, SIZE)
        val plane = SIZE * SIZE
        val out = FloatArray(3 * plane)
        for (i in 0 until plane) {
            val p = px[i]
            out[i] = ((p shr 16) and 0xFF) / 255f
            out[plane + i] = ((p shr 8) and 0xFF) / 255f
            out[2 * plane + i] = (p and 0xFF) / 255f
        }
        if (scaled !== bmp) scaled.recycle()
        crop.recycle()
        return out
    }

    private fun l2(v: FloatArray): FloatArray {
        var s = 0f
        for (x in v) s += x * x
        val n = sqrt(s)
        if (n > 0f) for (i in v.indices) v[i] /= n
        return v
    }

    override fun close() {
        vision.close()
        text.close()
    }

    companion object {
        private const val SIZE = 256
    }
}
