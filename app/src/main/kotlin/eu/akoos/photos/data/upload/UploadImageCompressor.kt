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

package eu.akoos.photos.data.upload

import android.content.Context
import android.content.res.AssetFileDescriptor
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import eu.akoos.photos.domain.entity.UploadCompressionTier
import eu.akoos.photos.util.ExifHelper
import eu.akoos.photos.util.MetadataStripConfig
import java.io.File
import java.io.FileOutputStream
import java.util.Locale

private const val TAG = "UploadImageCompressor"

/**
 * Opt-in upload compression for IMAGES ONLY. Re-encodes the source still to a lighter JPEG (and,
 * for the resizing tiers, downscales it) into a cache temp file so a smaller copy reaches Drive
 * while the on-device original is never touched. The caller decides image-vs-video and only calls
 * this for an image; video goes through VideoUploadCompressor instead.
 *
 * Every failure mode returns null so the caller falls back to the untouched original: a
 * compression failure must never fail (or block) the upload. The bytes actually sent are what the
 * upload path hashes, sizes, and records, so the SHA-1 / ContentHash / xAttr all describe the
 * recompressed copy. This mirrors the metadata-strip temp-file path in the upload use-case.
 */
object UploadImageCompressor {

    /**
     * Recompress the image at [sourceUri] according to [tier] and return the temp [File], or null
     * when it can't (or shouldn't) be recompressed:
     *
     *  - Decode failure, OOM, or an unreadable source → null (upload the original).
     *  - A format that a single-frame JPEG would silently damage → null (upload the original):
     *    a GIF or animated WebP (would drop every frame but one) and any image with a real alpha
     *    channel (flattening to JPEG would lose the transparency). JPEG/HEIC and opaque, non-animated
     *    PNG/WebP still recompress. These guards run before the expensive decode+encode where they can.
     *  - The produced file is not strictly smaller than the source → null, so a compression that
     *    would inflate the file (already-optimised JPEG, tiny image) never ships a larger copy.
     *
     * Sizing: the bitmap is decoded with an [BitmapFactory.Options.inSampleSize] chosen from a
     * bounds-only pass so a very large image is never fully materialised at native resolution
     * (memory safety). When [UploadCompressionTier.maxLongEdgePx] is greater than 0 and the source's
     * longest edge exceeds it, the decoded bitmap is scaled proportionally to that cap; otherwise
     * full resolution is kept (subject to the sample-size decode).
     *
     * EXIF: [Bitmap.compress] drops all EXIF, so the source orientation is baked into the pixels
     * (the encoded JPEG is upright with orientation NORMAL) and the original EXIF (capture time, GPS,
     * camera make/model, software, ISO, and the rest) is copied back onto the temp via
     * [ExifHelper.copyExifPreservingOrientation] so the compressed upload keeps the photo's metadata.
     * That copy honours [stripConfig]: this path always re-encodes to a fresh JPEG and rebuilds the
     * EXIF from scratch, so gating it here removes the requested groups for every source format,
     * including one whose container ExifInterface cannot rewrite in place (for example HEIC). The
     * default config strips nothing, keeping the full-metadata behaviour when strip-on-upload is off.
     */
    fun compressToTemp(
        context: Context,
        sourceUri: String,
        tier: UploadCompressionTier,
        stripConfig: MetadataStripConfig = MetadataStripConfig(),
    ): File? {
        val parsed = runCatching { Uri.parse(sourceUri) }.getOrNull() ?: return null

        // Size of the source bytes, to enforce the never-inflate rule below. If it can't be read we
        // treat compression as not worthwhile rather than risk shipping a bigger file.
        val sourceSize = readSourceSize(context, parsed)
        if (sourceSize <= 0L) return null

        var decoded: Bitmap? = null
        var oriented: Bitmap? = null
        var scaled: Bitmap? = null
        var out: File? = null
        try {
            // Bounds-only pass: read the native dimensions without allocating the pixels so the
            // sample-size decode below never materialises a 50 MP image at full ARGB_8888 size.
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            // In bounds-only mode decodeStream returns null by design (it only fills outWidth and
            // outHeight), so the null-guard must be on the stream opening, NOT on the decode result,
            // otherwise the dimensions are never read and every image falls back to the original.
            val boundsStream = context.contentResolver.openInputStream(parsed) ?: return null
            boundsStream.use { BitmapFactory.decodeStream(it, null, bounds) }
            val srcW = bounds.outWidth
            val srcH = bounds.outHeight
            if (srcW <= 0 || srcH <= 0) return null

            // Type-safety guard, cheap checks first. The bounds pass already resolved the source mime;
            // fall back to the content resolver when it's null. GIFs may be animated (a JPEG would keep
            // only one frame), so skip every GIF. An animated WebP is caught by a tiny header read.
            val mime = bounds.outMimeType?.takeIf { it.isNotBlank() }
                ?: runCatching { context.contentResolver.getType(parsed) }.getOrNull()
            val mimeLower = mime?.lowercase(Locale.ROOT)
            if (mimeLower == "image/gif") {
                Log.d(TAG, "compress skipped, GIF may be animated: $sourceUri")
                return null
            }
            if (isAnimatedWebp(context, parsed)) {
                Log.d(TAG, "compress skipped, animated WebP: $sourceUri")
                return null
            }

            val cap = tier.maxLongEdgePx
            val opts = BitmapFactory.Options().apply {
                inSampleSize = sampleSizeFor(srcW, srcH, cap)
            }
            decoded = context.contentResolver.openInputStream(parsed)?.use {
                BitmapFactory.decodeStream(it, null, opts)
            } ?: return null

            // Transparency guard, reusing the decode just done (no second decode). BitmapFactory sets
            // hasAlpha from the source's alpha channel, so an opaque RGB PNG/WebP decodes hasAlpha=false
            // and still compresses, while a transparent one decodes hasAlpha=true; flattening it to
            // JPEG would drop the transparency, so skip and let the caller upload the original.
            if (decoded.hasAlpha()) {
                Log.d(TAG, "compress skipped, transparent image: $sourceUri")
                return null
            }

            // BitmapFactory ignores the EXIF orientation tag; bake it into the pixels so the encoded
            // JPEG (which carries a NORMAL orientation) still shows upright, matching the editor save.
            val orientation = ExifHelper.readOrientation(context, sourceUri)
            oriented = ExifHelper.applyOrientation(decoded, orientation)

            // Proportional downscale to the tier's longest-edge cap. The sample-size decode already
            // got the bitmap close (to within a factor of two); this final scale lands it exactly on
            // the cap. A cap of 0 (or an already-smaller image) keeps the decoded dimensions.
            scaled = if (cap > 0) scaleToLongEdge(oriented, cap) else oriented

            out = File.createTempFile("compressed_", ".jpg", context.cacheDir)
            val encoded = FileOutputStream(out).use { fos ->
                scaled.compress(Bitmap.CompressFormat.JPEG, tier.quality, fos)
            }
            if (!encoded) {
                out.delete()
                return null
            }

            // Re-inject the original EXIF (capture time, GPS, camera make/model, software, ISO, lens,
            // exposure, and the rest) so a compressed upload keeps the photo's metadata, not just its
            // date. compress() dropped everything; copy it back with the orientation forced NORMAL (the
            // pixels are already upright) and the dimension tags following the recompressed size. The
            // copy honours [stripConfig], so a group the user chose to strip is not re-injected here
            // even when the source is a format the upstream strip step could not rewrite.
            // Best-effort: a failed EXIF write still leaves a valid recompressed JPEG.
            ExifHelper.readExifSnapshot(context, sourceUri)?.let { srcExif ->
                ExifHelper.copyExifPreservingOrientation(srcExif, out, scaled.width, scaled.height, stripConfig)
            }

            // Never inflate: if the recompressed copy is not strictly smaller than the source, skip
            // it and let the caller upload the original untouched.
            if (out.length() in 1 until sourceSize) {
                Log.d(TAG, "compressed $sourceSize -> ${out.length()} bytes (q${tier.quality}, cap ${tier.maxLongEdgePx}, ${srcW}x${srcH})")
                return out
            }
            Log.d(TAG, "compress skipped, not smaller: src=$sourceSize out=${out.length()} (q${tier.quality}, cap ${tier.maxLongEdgePx}, ${srcW}x${srcH})")
            out.delete()
            return null
        } catch (e: Throwable) {
            // Catch Throwable so an OutOfMemoryError on a huge bitmap also falls back to the original
            // instead of propagating and failing the upload.
            Log.w(TAG, "Compression failed for $sourceUri; uploading original: ${e.message}")
            out?.delete()
            return null
        } finally {
            // Free every intermediate bitmap. scaled may alias oriented (cap == 0), and oriented may
            // alias decoded (NORMAL orientation), so recycle each distinct instance once.
            if (scaled != null && scaled !== oriented) scaled.recycle()
            if (oriented != null && oriented !== decoded) oriented.recycle()
            decoded?.recycle()
        }
    }

    /**
     * Pure decision: does a strip-on-upload need a JPEG transcode to actually erase this source's
     * metadata? True ONLY when every condition holds:
     *
     *  - [stripOnUpload] is on, AND
     *  - [mimeType] is an image whose container CAN embed EXIF location but that ExifInterface cannot
     *    rewrite in place (HEIC / HEIF / AVIF): the in-place strip silently no-ops, so the original
     *    bytes would ship with their GPS / camera EXIF intact, AND
     *  - [compressWillRun] is false: a running compressor re-encodes a fresh JPEG and rebuilds a gated
     *    EXIF block itself, so the strip is already enforced and a separate transcode is moot.
     *
     * Everything else is false: a writable container (JPEG / PNG / WebP) strips in place; a format
     * carrying no EXIF GPS or that a single JPEG frame would damage (GIF / BMP, animated) is excluded
     * so its animation / bitmap is never destroyed; a video mime is never an image strip. Case- and
     * parameter-insensitive.
     *
     * [isMotionPhoto] is queried LAST, and only for a container that would otherwise be transcoded, so
     * the file probe it performs is skipped for every ordinary upload. A motion photo carries an MP4
     * trailer after the still: re-encoding its primary frame to JPEG would drop the motion, so one is
     * never transcoded and its container metadata is left as-is. Side-effect-free apart from that
     * caller-supplied probe, so a plain JVM test pins it with no Android, Context, or ExifInterface.
     */
    /**
     * Whether the on-device original may be REPLACED with a copy this compressor produced. The
     * compressor always encodes JPEG, so overwriting a PNG, a WebP or a HEIC with its output would
     * leave a file whose bytes contradict both its name and its MediaStore mime. Only a JPEG original
     * is safe to replace in place; for every other container the local mirror is skipped and the file
     * on the device is left exactly as it was, while the uploaded copy is still compressed.
     *
     * Pure and side-effect-free (string membership only), so a plain JVM test pins it.
     */
    fun canOverwriteLocalWithCompressedJpeg(mimeType: String): Boolean {
        val normalized = mimeType.substringBefore(';').trim().lowercase(Locale.ROOT)
        return normalized in JPEG_MIMES
    }

    private val JPEG_MIMES = setOf("image/jpeg", "image/jpg")

    fun needsStripTranscode(
        mimeType: String,
        stripOnUpload: Boolean,
        compressWillRun: Boolean,
        isMotionPhoto: () -> Boolean = { false },
    ): Boolean {
        if (!stripOnUpload || compressWillRun) return false
        val normalized = mimeType.substringBefore(';').trim().lowercase(Locale.ROOT)
        if (normalized !in EXIF_UNWRITABLE_IMAGE_MIMES) return false
        return !isMotionPhoto()
    }

    /**
     * Pure decision: must the image compressor be SKIPPED for this upload because the bytes carry an
     * Ultra HDR gain map? An Ultra HDR still is an SDR JPEG with the gain map appended as a second
     * image; this compressor decodes the primary frame and re-encodes a plain JPEG, which drops that
     * second image and with it the HDR rendition. So one is never compressed and its bytes upload as
     * they are, exactly like the motion-photo skip.
     *
     * [hasGainMap] is queried LAST, after [compressOnUpload] and the image [mimeType], so the header
     * probe it performs never runs for an upload that would not be compressed anyway. Side-effect-free
     * apart from that caller-supplied probe, so a plain JVM test pins it with no Android and no file.
     */
    fun skipsCompressionForGainMap(
        mimeType: String,
        compressOnUpload: Boolean,
        hasGainMap: () -> Boolean = { false },
    ): Boolean {
        if (!compressOnUpload || !isImageMime(mimeType)) return false
        return hasGainMap()
    }

    /**
     * Pure decision: may a strip-on-upload try the gain-map-preserving route for these bytes? True only
     * when [stripOnUpload] is on, [sdkInt] is at or above the API that exposes `Bitmap.hasGainmap`,
     * [mimeType] is an image, and [hasGainMap] confirms a gain map. Cheap conditions first, so the probe
     * never runs for an upload that could not take the route.
     *
     * The API floor is the whole point: that route rebuilds the file from a split, and rebuilt bytes may
     * only ship once the platform decoder has confirmed they still decode WITH their gain map. Below that
     * API no such confirmation exists, so the ordinary strip runs and the HDR rendition is lost. Privacy
     * outranks the gain map throughout: this gate only decides whether the attempt is worth making, and
     * the attempt itself falls back to the ordinary strip on any doubt.
     */
    fun attemptsGainMapPreservingStrip(
        mimeType: String,
        stripOnUpload: Boolean,
        sdkInt: Int,
        hasGainMap: () -> Boolean = { false },
    ): Boolean {
        if (!stripOnUpload || sdkInt < GAIN_MAP_VERIFY_MIN_SDK) return false
        if (!isImageMime(mimeType)) return false
        return hasGainMap()
    }

    /** True when [mimeType] names an image, tolerating case and a `;` parameter suffix. */
    private fun isImageMime(mimeType: String): Boolean =
        mimeType.substringBefore(';').trim().lowercase(Locale.ROOT).startsWith("image/")

    /** A gain map can only be VERIFIED where `Bitmap.hasGainmap` exists (API 34). Held as a plain int so
     *  the gate stays a pure value decision the caller drives with its own SDK level. */
    private const val GAIN_MAP_VERIFY_MIN_SDK = 34

    /**
     * Transcodes the image at [sourceUri] to a full-resolution, high-quality JPEG temp holding only
     * the EXIF groups [stripConfig] permits, for a source whose container ExifInterface cannot rewrite
     * in place (see [needsStripTranscode]). Decodes the source, bakes its EXIF orientation into the
     * pixels, re-encodes as a JPEG with NO downscale (a privacy transcode, not compression), then
     * re-injects the permitted EXIF via [ExifHelper.copyExifPreservingOrientation] so a stripped group
     * is never restored. Returns null on any failure so the caller falls back to the original bytes.
     * The temp reuses the `stripped_` prefix so the upload use-case's existing cleanup and stale sweep
     * cover it; the caller owns it and must delete it.
     */
    fun transcodeStrippedJpeg(
        context: Context,
        sourceUri: String,
        stripConfig: MetadataStripConfig,
    ): File? {
        val parsed = runCatching { Uri.parse(sourceUri) }.getOrNull() ?: return null
        var decoded: Bitmap? = null
        var oriented: Bitmap? = null
        var out: File? = null
        try {
            // Bounds-only pass so an enormous source is never fully materialised before the safety
            // sample size is known (memory), mirroring compressToTemp.
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            val boundsStream = context.contentResolver.openInputStream(parsed) ?: return null
            boundsStream.use { BitmapFactory.decodeStream(it, null, bounds) }
            val srcW = bounds.outWidth
            val srcH = bounds.outHeight
            if (srcW <= 0 || srcH <= 0) return null

            // Cap 0: only the pathological-size OOM guard applies, never a compression downscale, so
            // the file's resolution is preserved.
            val opts = BitmapFactory.Options().apply { inSampleSize = sampleSizeFor(srcW, srcH, 0) }
            decoded = context.contentResolver.openInputStream(parsed)?.use {
                BitmapFactory.decodeStream(it, null, opts)
            } ?: return null

            // BitmapFactory ignores the EXIF orientation tag, so bake it into the pixels; the JPEG is
            // then upright with a NORMAL orientation (forced by copyExifPreservingOrientation below).
            val orientation = ExifHelper.readOrientation(context, sourceUri)
            oriented = ExifHelper.applyOrientation(decoded, orientation)

            out = File.createTempFile("stripped_", ".jpg", context.cacheDir)
            val encoded = FileOutputStream(out).use { fos ->
                oriented.compress(Bitmap.CompressFormat.JPEG, STRIP_TRANSCODE_JPEG_QUALITY, fos)
            }
            if (!encoded) {
                out.delete()
                return null
            }

            // Rebuild only the permitted EXIF from the source; a stripped group's tags are dropped so
            // the erased metadata cannot reappear on the transcoded copy.
            ExifHelper.readExifSnapshot(context, sourceUri)?.let { srcExif ->
                ExifHelper.copyExifPreservingOrientation(
                    srcExif, out, oriented.width, oriented.height, stripConfig,
                )
            }
            return out
        } catch (e: Throwable) {
            // Throwable so an OutOfMemoryError on a huge decode also falls back to the original bytes.
            Log.w(TAG, "Strip transcode failed for $sourceUri: ${e.message}")
            out?.delete()
            return null
        } finally {
            if (oriented != null && oriented !== decoded) oriented.recycle()
            decoded?.recycle()
        }
    }

    /** True when [uri] is an animated WebP, read from a tiny header sniff. A WebP file is a RIFF
     *  container: bytes 0..3 are "RIFF", bytes 8..11 are "WEBP". Animation is signalled either by a
     *  VP8X extended-format chunk (fourcc "VP8X" at offset 12) whose flags byte at offset 20 has the
     *  animation bit (0x02) set, or by an explicit "ANIM" chunk further in the header. Only ~64 bytes
     *  are inspected. Any read error, or a non-WebP / still WebP, returns false so a normal WebP is not
     *  blocked; the alpha and mime guards handle the rest. */
    private fun isAnimatedWebp(context: Context, uri: Uri): Boolean = runCatching {
        val header = context.contentResolver.openInputStream(uri)?.use { input ->
            val buf = ByteArray(64)
            var read = 0
            while (read < buf.size) {
                val n = input.read(buf, read, buf.size - read)
                if (n < 0) break
                read += n
            }
            buf.copyOf(read)
        } ?: return@runCatching false

        if (header.size < 21) return@runCatching false
        val isRiff = header.matchesAscii(0, "RIFF")
        val isWebp = header.matchesAscii(8, "WEBP")
        if (!isRiff || !isWebp) return@runCatching false

        // VP8X extended format with the animation flag (bit 1, mask 0x02) in the flags byte.
        val isVp8x = header.matchesAscii(12, "VP8X")
        if (isVp8x && (header[20].toInt() and 0x02) != 0) return@runCatching true

        // Or an explicit ANIM chunk within the sniffed window.
        for (i in 12..header.size - 4) {
            if (header.matchesAscii(i, "ANIM")) return@runCatching true
        }
        false
    }.getOrDefault(false)

    /** True when the ASCII bytes of [text] appear at [offset] in this array (bounds-safe). */
    private fun ByteArray.matchesAscii(offset: Int, text: String): Boolean {
        if (offset < 0 || offset + text.length > size) return false
        for (i in text.indices) {
            if (this[offset + i].toInt() != text[i].code) return false
        }
        return true
    }

    /** Source byte count in O(1) from the asset file descriptor, falling back to a full stream read
     *  only when the descriptor length is unavailable. [AssetFileDescriptor.UNKNOWN_LENGTH] is -1, so
     *  the > 0L filter drops it (and a zero-length descriptor) into the stream count. Mirrors the same
     *  helper in [VideoUploadCompressor]. Any read error yields 0 (compression treated as not
     *  worthwhile). */
    private fun readSourceSize(context: Context, uri: Uri): Long = runCatching {
        context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length }
            ?.takeIf { it > 0L }
            ?: context.contentResolver.openInputStream(uri)?.use { input ->
                var total = 0L
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    total += read
                }
                total
            }
            ?: 0L
    }.getOrDefault(0L)

    /** Largest power-of-two sample size that keeps the sampled longest edge at or above [cap] (so the
     *  precise scale afterwards downsizes rather than upsizes). With no cap (0) it caps the decode at
     *  a generous safety bound so an enormous source still can't blow the heap on full-res tiers. */
    private fun sampleSizeFor(width: Int, height: Int, cap: Int): Int {
        val target = if (cap > 0) cap else FULL_RES_DECODE_SAFETY_CAP
        var sample = 1
        // Halve until the next halving would drop the longest edge below the target.
        while (maxOf(width, height) / (sample * 2) >= target) sample *= 2
        return sample
    }

    /** Scales [src] down so its longest edge equals [maxLongEdge], preserving aspect ratio. Returns
     *  [src] unchanged when it already fits (never upscales). */
    private fun scaleToLongEdge(src: Bitmap, maxLongEdge: Int): Bitmap {
        val longest = maxOf(src.width, src.height)
        if (longest <= maxLongEdge) return src
        val ratio = maxLongEdge.toFloat() / longest.toFloat()
        val newW = (src.width * ratio).toInt().coerceAtLeast(1)
        val newH = (src.height * ratio).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(src, newW, newH, true)
    }

    /** Full-resolution tiers still cap the raw decode at this longest edge so a pathologically large
     *  source (e.g. a 100 MP panorama) can't OOM the decode. Well above any ordinary phone photo. */
    private const val FULL_RES_DECODE_SAFETY_CAP = 8192

    /** Quality for the strip transcode. High (near-visually-lossless): this exists to erase metadata a
     *  format could not strip in place, not to shrink the file, so the pixels stay faithful. */
    private const val STRIP_TRANSCODE_JPEG_QUALITY = 95

    /** Image containers that CAN embed EXIF GPS / camera metadata but that ExifInterface cannot rewrite
     *  in place, so an in-place strip silently leaves the bytes untouched. A strip-on-upload of one of
     *  these must transcode a stripped JPEG (see [needsStripTranscode] / [transcodeStrippedJpeg]) rather
     *  than ship the original. Deliberately EXCLUDES writable containers (JPEG / PNG / WebP strip in
     *  place) and formats with no EXIF GPS or that a single JPEG frame would damage (GIF / BMP). */
    private val EXIF_UNWRITABLE_IMAGE_MIMES = setOf(
        "image/heic",
        "image/heif",
        "image/heic-sequence",
        "image/heif-sequence",
        "image/avif",
    )
}
