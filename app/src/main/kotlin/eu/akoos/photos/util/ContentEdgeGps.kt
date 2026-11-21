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

package eu.akoos.photos.util

import android.util.Log
import androidx.exifinterface.media.ExifInterface
import eu.akoos.photos.data.crypto.fileMayFillPlace
import eu.akoos.photos.data.db.entity.isUsableGpsFix
import java.io.ByteArrayInputStream

/** Which content blocks of a cloud photo the map's GPS walk reads for a place its XAttr does not give. */
enum class ContentGpsRead { NONE, FIRST, FIRST_AND_LAST }

/**
 * Reads the GPS fix a cloud photo's own file carries from its first content block (an image's EXIF sits at
 * the start) and, for an MP4 or MOV, its last one too (a camera writes the movie box after the media data).
 */
object ContentEdgeGps {

    /** The ISO base media containers whose movie box [Mp4LocationAtoms] can read. */
    private val isoMediaVideos = setOf("video/mp4", "video/quicktime", "video/3gpp", "video/3gpp2", "video/x-m4v")

    /**
     * The blocks worth reading for a photo of [mimeType]. [xAttrJson] is its decrypted XAttr, null when it has
     * none ([hasXAttr] false) or it could not be decrypted. A video's XAttr never names a place its file holds,
     * so an MP4 or MOV is always read. An image is read only when [fileMayFillPlace] allows it.
     */
    fun plan(mimeType: String, hasXAttr: Boolean, xAttrJson: String?): ContentGpsRead = when {
        mimeType.lowercase() in isoMediaVideos -> ContentGpsRead.FIRST_AND_LAST
        mimeType.startsWith("image/") && fileMayFillPlace(mimeType, hasXAttr, xAttrJson) -> ContentGpsRead.FIRST
        else -> ContentGpsRead.NONE
    }

    /** The fix in [first] (and [last], for a video) of a file of [mimeType], or null when they hold none. Never throws. */
    fun read(mimeType: String, first: ByteArray, last: ByteArray?): Pair<Double, Double>? = when {
        mimeType.startsWith("video/") -> mp4Iso6709(first, last)?.let { PhotoGpsResolver.parseIso6709(it) }
        mimeType.startsWith("image/") -> imageGps(first)
        else -> null
    }

    private fun imageGps(bytes: ByteArray): Pair<Double, Double>? {
        val latLong = runCatching { ExifInterface(ByteArrayInputStream(bytes)).latLong }.getOrNull() ?: return null
        return (latLong[0] to latLong[1]).takeIf { isUsableGpsFix(it.first, it.second) }
    }

    /**
     * The ISO 6709 location of an MP4 known only by its [first] bytes and, when fetched, its [last] ones: a
     * movie box lying wholly inside [first] is read there, else one lying wholly inside [last] (where a camera
     * puts it after the media data). Null when neither holds a whole movie box with a location. Never throws.
     */
    fun mp4Iso6709(first: ByteArray, last: ByteArray?): String? = runCatching {
        leadingMoovIso6709(first) ?: last?.let { trailingMoovIso6709(it) }
    }.onFailure { Log.w(TAG, "edge location read failed: ${it.message}") }.getOrNull()

    /** The location of the movie box among the top-level boxes from the start of [buf], when it ends inside it. */
    private fun leadingMoovIso6709(buf: ByteArray): String? {
        var pos = 0L
        while (pos + HEADER <= buf.size) {
            val at = pos.toInt()
            val size32 = u32(buf, at)
            val type = String(buf, at + 4, 4, Charsets.ISO_8859_1)
            var header = HEADER
            val size = when (size32) {
                0L -> return null
                1L -> {
                    if (at + 16 > buf.size) return null
                    header = 16
                    u64(buf, at + HEADER)
                }
                else -> size32
            }
            if (size < header) return null
            if (type == "moov") {
                return if (pos + size <= buf.size) Mp4LocationAtoms.movieIso6709(buf, at + header, (pos + size).toInt()) else null
            }
            pos += size
        }
        return null
    }

    /** The location of a movie box ending inside [buf], found by its type from the end, since where [buf]
     *  starts within the file is not known. A match must parse as a box tree holding a location. */
    private fun trailingMoovIso6709(buf: ByteArray): String? {
        var i = buf.size - 4
        while (i >= 4) {
            if (buf[i] == 'm'.code.toByte() && buf[i + 1] == 'o'.code.toByte() &&
                buf[i + 2] == 'o'.code.toByte() && buf[i + 3] == 'v'.code.toByte()
            ) {
                val start = i - 4
                val size32 = u32(buf, start)
                val header = if (size32 == 1L) 16 else HEADER
                val size = if (size32 == 1L) (if (start + 16 <= buf.size) u64(buf, i + 4) else 0L) else size32
                if (size >= header && start + size <= buf.size) {
                    Mp4LocationAtoms.movieIso6709(buf, start + header, (start + size).toInt())?.let { return it }
                }
            }
            i--
        }
        return null
    }

    private fun u32(b: ByteArray, at: Int): Long =
        ((b[at].toLong() and 0xFF) shl 24) or ((b[at + 1].toLong() and 0xFF) shl 16) or
            ((b[at + 2].toLong() and 0xFF) shl 8) or (b[at + 3].toLong() and 0xFF)

    private fun u64(b: ByteArray, at: Int): Long = (u32(b, at) shl 32) or u32(b, at + 4)

    private const val HEADER = 8
    private const val TAG = "ContentEdgeGps"
}
