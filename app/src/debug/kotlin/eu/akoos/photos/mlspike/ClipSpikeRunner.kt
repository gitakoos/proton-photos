package eu.akoos.photos.mlspike

import android.content.Context
import android.graphics.BitmapFactory
import android.util.Log
import java.io.File

/**
 * SPIKE-ONLY (debug source set). Runs the on-device CLIP proof on the 4 labeled test images and
 * the precomputed query tokens, then logs the cosine matrix so it can be compared against the
 * desktop Python reference (cats 0.256, football 0.160, city 0.190, tiger 0.289).
 *
 * Files are read from getExternalFilesDir("clip"): vision_f32.onnx, text_f32.onnx, and
 * imgs/{cats,football,city,tiger}.jpg. Push them there with adb (no network).
 */
object ClipSpikeRunner {

    private const val TAG = "ClipSpike"

    // Precomputed CLIP token ids (padded to 77, pad id 0), generated offline from tokenizer.json.
    private val QUERIES: List<Pair<String, LongArray>> = listOf(
        "a photo of cats" to pad(49406, 320, 1125, 539, 3989, 49407),
        "a football match" to pad(49406, 320, 1882, 2439, 49407),
        "a city street" to pad(49406, 320, 1305, 2012, 49407),
        "a tiger" to pad(49406, 320, 6531, 49407),
        "a plate of food" to pad(49406, 320, 5135, 539, 1559, 49407),
    )

    private val IMAGES = listOf("cats", "football", "city", "tiger")

    fun run(context: Context) {
        val dir = File(context.getExternalFilesDir(null), "clip")
        Log.i(TAG, "=== CLIP spike start; dir=${dir.absolutePath} ===")
        val vision = File(dir, "vision_f32.onnx")
        val text = File(dir, "text_f32.onnx")
        if (!vision.exists() || !text.exists()) {
            Log.e(TAG, "models missing: vision=${vision.exists()} text=${text.exists()}; adb push them to $dir")
            return
        }

        val t0 = System.currentTimeMillis()
        val encoder = try {
            ClipEncoder(vision.absolutePath, text.absolutePath)
        } catch (e: Throwable) {
            Log.e(TAG, "encoder init failed: ${e.message}", e)
            return
        }
        Log.i(TAG, "models loaded in ${System.currentTimeMillis() - t0} ms")

        encoder.use { enc ->
            val imgEmb = IMAGES.mapNotNull { name ->
                val f = File(dir, "imgs/$name.jpg")
                val bmp = BitmapFactory.decodeFile(f.absolutePath)
                if (bmp == null) {
                    Log.e(TAG, "image missing/undecodable: $f"); null
                } else {
                    val ti = System.currentTimeMillis()
                    val e = enc.encodeImage(bmp)
                    bmp.recycle()
                    Log.i(TAG, "encoded image '$name' in ${System.currentTimeMillis() - ti} ms")
                    name to e
                }
            }
            val txtEmb = QUERIES.map { (q, ids) -> q to enc.encodeText(ids) }

            Log.i(TAG, "--- cosine matrix (rows=images, cols=queries) ---")
            for ((name, ie) in imgEmb) {
                val row = txtEmb.joinToString("  ") { (q, te) -> "%.3f".format(enc.cosine(ie, te)) }
                Log.i(TAG, "$name: $row")
            }
            Log.i(TAG, "--- best query per image (should match) ---")
            var correct = 0
            for ((name, ie) in imgEmb) {
                val best = txtEmb.maxByOrNull { (_, te) -> enc.cosine(ie, te) }!!
                val hit = best.first.contains(name) || (name == "cats" && best.first.contains("cats"))
                if (hit) correct++
                Log.i(TAG, "  $name -> \"${best.first}\"  (${"%.3f".format(enc.cosine(ie, best.second))})  ${if (hit) "OK" else "x"}")
            }
            Log.i(TAG, "=== CLIP spike done: $correct/${imgEmb.size} correct ===")
        }
    }

    private fun pad(vararg ids: Int): LongArray =
        LongArray(77) { i -> if (i < ids.size) ids[i].toLong() else 0L }
}
