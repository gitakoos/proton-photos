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

package eu.akoos.photos.domain.usecase

import android.content.Context
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.akoos.photos.util.ExifHelper
import eu.akoos.photos.util.MetadataStripConfig
import eu.akoos.photos.util.MotionPhotoUtil
import eu.akoos.photos.util.UltraHdrUtil
import java.io.File
import java.io.RandomAccessFile
import javax.inject.Inject
import javax.inject.Singleton

private const val UPLOAD_TAG = "UploadUseCase"

/**
 * Byte-level structural strip helpers for the upload path. Splits a Motion Photo or an Ultra HDR
 * still around its appended trailer, strips the primary image's EXIF, and re-attaches the trailer
 * byte-for-byte so the motion or the gain map survives a metadata strip. Also answers whether an
 * upload URI is a Motion Photo or advertises a gain map, so the compressor can skip a pass that
 * would drop either. Reads only the application [context]'s contentResolver and cacheDir.
 */
@Singleton
class UploadStructuralStripper @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    /**
     * Strip path for Motion Photos. Returns a temp upload file when [localUri] is a motion photo,
     * or null when it is not (so the caller runs the ordinary EXIF strip instead).
     *
     * For a motion photo the bytes split into primary = `[0, videoOffset)` and trailer =
     * `[videoOffset, EOF)`. The primary is written to a temp, GPS/EXIF-stripped via [ExifHelper],
     * then the original trailer is appended byte-for-byte. The trailer length is unchanged, so a
     * recipient's `fileSize - videoLength` math still resolves and the motion (plus the motion XMP
     * the primary still carries) survives.
     *
     * Safety: if the file is a confirmed motion photo but the split or primary strip can't complete
     * cleanly, the byte-exact materialized copy is returned so the upload preserves the motion
     * rather than risk a corrupt primary. Returns null only when detection finds no motion photo.
     */
    fun stripImagePreservingMotion(localUri: String, stripConfig: MetadataStripConfig): File? {
        // Materialize the source so the tail scan and the split read real bytes, not a stream.
        val source = try {
            val tmp = File.createTempFile("motion_src_", ".bin", context.cacheDir)
            context.contentResolver.openInputStream(Uri.parse(localUri))?.use { input ->
                tmp.outputStream().use { input.copyTo(it) }
            } ?: run { tmp.delete(); return null }
            tmp
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.w(UPLOAD_TAG, "Motion-photo materialize failed for $localUri: ${e.message}")
            return null
        }

        val info = MotionPhotoUtil.detect(source)
        if (info == null) {
            // Not a motion photo — let the caller take the ordinary strip path.
            source.delete()
            return null
        }

        // From here the file IS a motion photo: never return null (that would invite the
        // destructive plain strip). On any failure fall back to the byte-exact source copy.
        var primary: File? = null
        try {
            primary = File.createTempFile("motion_primary_", ".jpg", context.cacheDir)
            RandomAccessFile(source, "r").use { raf ->
                primary!!.outputStream().use { out ->
                    val buffer = ByteArray(64 * 1024)
                    var remaining = info.videoOffset
                    while (remaining > 0) {
                        val toRead = minOf(buffer.size.toLong(), remaining).toInt()
                        val read = raf.read(buffer, 0, toRead)
                        if (read < 0) break
                        out.write(buffer, 0, read)
                        remaining -= read
                    }
                }
            }

            // Strip GPS/EXIF from the primary only. Feed it through the existing temp-file strip
            // via a file:// URI so the same tag set + behaviour applies.
            val strippedPrimary = ExifHelper.stripToTempFile(
                context, Uri.fromFile(primary).toString(), stripConfig,
            )
            // stripToTempFile returns null on no-op or error; in either case keep the primary bytes
            // we already split so the concatenation still yields an intact motion photo.
            val primaryForJoin = strippedPrimary ?: primary!!

            val joined = File.createTempFile("motion_out_", ".jpg", context.cacheDir)
            joined.outputStream().use { out ->
                primaryForJoin.inputStream().use { it.copyTo(out) }
                RandomAccessFile(source, "r").use { raf ->
                    raf.seek(info.videoOffset)
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = raf.read(buffer)
                        if (read < 0) break
                        out.write(buffer, 0, read)
                    }
                }
            }
            strippedPrimary?.delete()
            primary?.delete()
            source.delete()
            return joined
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.w(UPLOAD_TAG, "Motion-photo split/strip failed for $localUri; uploading byte-exact: ${e.message}")
            primary?.delete()
            // Byte-exact fallback: the untouched materialized copy keeps the motion intact.
            return source
        }
    }

    /**
     * Strip path for Ultra HDR stills. Returns a temp upload file ONLY when the rebuilt bytes are proven
     * to still decode with their gain map, else null so the caller runs the ordinary strip untouched.
     *
     * An Ultra HDR still is an SDR JPEG with the gain map appended as a second image after the primary,
     * so the split is primary = `[0, end of the first EOI after the scan)` and tail = the rest. The
     * primary's EXIF goes through the same helper the motion path uses and the tail is re-attached
     * byte-for-byte, so the appended image and the header that points at it both survive intact.
     *
     * Safety runs the OPPOSITE way to the motion path: there, a corrupt primary is worse than a lost
     * strip; here, a lost gain map only costs the HDR rendition while an unstripped upload leaks GPS. So
     * every uncertainty (a split that does not resolve, a strip that no-ops, a result the decoder refuses
     * or that decodes without a gain map) deletes the temps and returns null, handing the file back to
     * the ordinary strip with no behaviour change at all.
     */
    fun stripImagePreservingGainMap(localUri: String, stripConfig: MetadataStripConfig): File? {
        // Materialize the source so the marker walk and the split read real bytes, not a stream.
        val source = try {
            val tmp = File.createTempFile("gainmap_src_", ".bin", context.cacheDir)
            context.contentResolver.openInputStream(Uri.parse(localUri))?.use { input ->
                tmp.outputStream().use { input.copyTo(it) }
            } ?: run { tmp.delete(); return null }
            tmp
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.w(UPLOAD_TAG, "Ultra HDR materialize failed for $localUri: ${e.message}")
            return null
        }

        var primary: File? = null
        var strippedPrimary: File? = null
        var joined: File? = null
        try {
            // A split that lands at or past EOF means there is no appended image to protect, so the
            // ordinary strip loses nothing and is the safer route.
            val splitAt = primaryImageEndOffset(source)
            if (splitAt <= 0L || splitAt >= source.length()) return null

            primary = File.createTempFile("gainmap_primary_", ".jpg", context.cacheDir)
            RandomAccessFile(source, "r").use { raf ->
                primary!!.outputStream().use { out ->
                    val buffer = ByteArray(64 * 1024)
                    var remaining = splitAt
                    while (remaining > 0) {
                        val toRead = minOf(buffer.size.toLong(), remaining).toInt()
                        val read = raf.read(buffer, 0, toRead)
                        if (read < 0) break
                        out.write(buffer, 0, read)
                        remaining -= read
                    }
                }
            }

            // Strip the primary through the existing temp-file strip via a file:// URI so the same tag
            // set and behaviour apply. A null is a no-op or an error: either way nothing here is proven
            // stripped, so the ordinary path takes over rather than this one shipping unstripped bytes.
            strippedPrimary = ExifHelper.stripToTempFile(
                context, Uri.fromFile(primary).toString(), stripConfig,
            ) ?: return null

            joined = File.createTempFile("gainmap_out_", ".jpg", context.cacheDir)
            joined!!.outputStream().use { out ->
                strippedPrimary!!.inputStream().use { it.copyTo(out) }
                RandomAccessFile(source, "r").use { raf ->
                    raf.seek(splitAt)
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = raf.read(buffer)
                        if (read < 0) break
                        out.write(buffer, 0, read)
                    }
                }
            }

            if (!verifyGainMapPreserved(joined!!)) {
                Log.d(UPLOAD_TAG, "Ultra HDR rebuild unverified for $localUri; ordinary strip applies")
                return null
            }
            // Verified, so hand it to the caller and keep the finally below from deleting it.
            val verified = joined!!
            joined = null
            return verified
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.w(UPLOAD_TAG, "Ultra HDR split/strip failed for $localUri: ${e.message}")
            return null
        } finally {
            // Every intermediate goes, on success and on failure alike; only the verified result escapes.
            source.delete()
            primary?.delete()
            strippedPrimary?.delete()
            joined?.delete()
        }
    }

    /**
     * Offset one byte past the primary image's EOI in [source], or -1 when it cannot be resolved.
     *
     * The marker chain is walked by its length fields up to SOS, so a thumbnail's own SOI/SOS/EOI inside
     * an APPn payload is stepped over instead of taken for the primary's. From the scan onwards the bytes
     * are entropy-coded, where every literal 0xFF is stuffed as `0xFF 0x00`, so the first `0xFF 0xD9`
     * pair there is genuinely the end of the primary and whatever follows it is the appended image.
     */
    internal fun primaryImageEndOffset(source: File): Long {
        RandomAccessFile(source, "r").use { raf ->
            val length = raf.length()
            if (length < 4L) return -1L
            if (raf.read() != 0xFF || raf.read() != 0xD8) return -1L
            while (raf.filePointer < length) {
                // Anything other than 0xFF here means the walk has lost sync with the segment chain.
                if (raf.read() != 0xFF) return -1L
                // A marker may be padded with any number of extra 0xFF fill bytes.
                var marker = raf.read()
                while (marker == 0xFF) marker = raf.read()
                when {
                    marker < 0 -> return -1L
                    // Standalone markers carry no length field.
                    marker == 0xD8 || marker == 0x01 -> continue
                    marker in 0xD0..0xD7 -> continue
                    // EOI before any scan: there is no primary image to split off.
                    marker == 0xD9 -> return -1L
                }
                val high = raf.read()
                val low = raf.read()
                if (high < 0 || low < 0) return -1L
                // The big-endian length counts its own two bytes, so anything below 2 is corrupt.
                val segment = (high shl 8) or low
                if (segment < 2) return -1L
                val payloadEnd = raf.filePointer + (segment - 2)
                if (payloadEnd > length) return -1L
                if (marker == 0xDA) return firstEoiFrom(raf, payloadEnd, length)
                raf.seek(payloadEnd)
            }
        }
        return -1L
    }

    /** Offset one byte past the first `0xFF 0xD9` at or after [from] in [raf], or -1 when there is none
     *  before [length]. */
    internal fun firstEoiFrom(raf: RandomAccessFile, from: Long, length: Long): Long {
        raf.seek(from)
        val buffer = ByteArray(64 * 1024)
        var position = from
        var markerPrefixPending = false
        while (position < length) {
            val read = raf.read(buffer)
            if (read <= 0) break
            for (i in 0 until read) {
                val value = buffer[i].toInt() and 0xFF
                if (markerPrefixPending && value == 0xD9) return position + i + 1
                markerPrefixPending = value == 0xFF
            }
            position += read
        }
        return -1L
    }

    /**
     * True only when the platform decoder can actually decode [candidate] AND reports a gain map on the
     * result. This is what makes a rebuilt file safe to ship: bytes that fail to decode, or that decode
     * without the gain map, are not a preserved Ultra HDR and must never replace the ordinary strip.
     * Decoded at a small target size so the check costs a fraction of the full bitmap. Below API 34 there
     * is no gain-map query at all, so the answer is false and the caller falls back.
     */
    private fun verifyGainMapPreserved(candidate: File): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return false
        // Enough pixels for the decoder to do real work, small enough that the verification costs about
        // a megabyte of heap rather than the full-resolution bitmap.
        val targetPx = 512
        var bitmap: android.graphics.Bitmap? = null
        return try {
            bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(candidate)) { decoder, info, _ ->
                var sample = 1
                val longest = maxOf(info.size.width, info.size.height)
                while (longest / (sample * 2) >= targetPx) sample *= 2
                decoder.setTargetSampleSize(sample)
            }
            bitmap.hasGainmap()
        } catch (e: Throwable) {
            // Throwable so a decoder OutOfMemoryError also reads as "not preserved" instead of failing
            // the upload.
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.w(UPLOAD_TAG, "Gain-map verification failed for ${candidate.name}: ${e.message}")
            false
        } finally {
            bitmap?.recycle()
        }
    }

    /**
     * True when [uploadUri] points at an Android Motion Photo (a still with an appended MP4 trailer),
     * so the caller can skip image compression that would re-encode only the primary and lose the
     * motion. Reuses [MotionPhotoUtil.detect], which needs a [File]: a file:// URI (a strip temp that
     * already preserved the motion) is read in place; a content:// original is materialized to a temp
     * first, then deleted. Defensive: any failure returns false so an unreadable file just compresses
     * as an ordinary still.
     */
    fun isMotionPhotoUpload(uploadUri: String): Boolean {
        val uri = runCatching { Uri.parse(uploadUri) }.getOrNull() ?: return false
        if (uri.scheme == "file") {
            val path = uri.path ?: return false
            return runCatching { MotionPhotoUtil.detect(File(path)) != null }.getOrDefault(false)
        }
        var temp: File? = null
        return try {
            temp = File.createTempFile("motion_check_", ".bin", context.cacheDir)
            val copied = context.contentResolver.openInputStream(uri)?.use { input ->
                temp!!.outputStream().use { input.copyTo(it) }
                true
            } ?: false
            copied && MotionPhotoUtil.detect(temp!!) != null
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.w(UPLOAD_TAG, "Motion-photo check failed for $uploadUri: ${e.message}")
            false
        } finally {
            temp?.delete()
        }
    }

    /**
     * True when [uploadUri] points at a JPEG advertising an Ultra HDR gain map, so the caller can skip a
     * pass that would drop it. [UltraHdrUtil] reads only the marker segments ahead of the scan, so no
     * temp copy and no bitmap is needed whatever the scheme, unlike the motion probe. Defensive: any
     * failure returns false and the file is treated as an ordinary still.
     */
    fun hasGainMapUpload(uploadUri: String): Boolean {
        val uri = runCatching { Uri.parse(uploadUri) }.getOrNull() ?: return false
        return try {
            context.contentResolver.openInputStream(uri)?.buffered()?.use {
                UltraHdrUtil.hasGainMap(it)
            } ?: false
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.w(UPLOAD_TAG, "Gain-map check failed for $uploadUri: ${e.message}")
            false
        }
    }
}
