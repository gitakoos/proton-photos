/*
 * Photos for Proton
 * Copyright (C) 2026 Akoos <https://akoos.eu>
 *
 * Source:  https://github.com/gitakoos/proton-photos
 * Website: https://photos.akoos.eu
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
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.RandomAccessFile

private const val TAG = "MotionPhotoUtil"

/**
 * Offset and length of the MP4 trailer embedded in an Android Motion Photo.
 *
 * [videoOffset] is the byte position where the appended MP4 begins (the start of its first
 * `ftyp` box); [videoLength] is `fileSize - videoOffset`, i.e. the trailer byte count other
 * clients reconstruct with `fileSize - videoLength`.
 */
data class MotionPhotoInfo(val videoOffset: Long, val videoLength: Long)

/**
 * Detects Android Motion Photos and locates their embedded MP4 trailer.
 *
 * A Motion Photo is a primary still (JPEG/HEIC) with a complete MP4 appended at the END of the
 * file. Two encodings exist:
 *  - Modern (XMP `Container` directory): `Camera:MotionPhoto="1"` plus a `Container:Item` whose
 *    `Item:Semantic="MotionPhoto"` carries `Item:Length=<videoBytes>`. The video starts at
 *    `fileSize - Item:Length`.
 *  - Legacy Google (`GCamera`): `GCamera:MotionPhoto`/`GCamera:MicroVideo` plus
 *    `GCamera:MicroVideoOffset=<videoBytes>`. The video starts at `fileSize - MicroVideoOffset`.
 *
 * The XMP flag alone is treated only as a hint: the authoritative offset comes from scanning the
 * file tail for the MP4 `ftyp` box signature (`66 74 79 70` at box offset + 4). The XMP length
 * narrows the search window; the ftyp scan pins the exact box start.
 */
object MotionPhotoUtil {

    // ASCII "ftyp" — appears at offset+4 of the first box of every ISO-BMFF (MP4/MOV) stream.
    private val FTYP = byteArrayOf(0x66, 0x74, 0x79, 0x70)

    // Cap how far back from EOF the tail scan reads when no XMP length is available. A Motion
    // Photo trailer is short (a 1-3 s clip); 32 MiB comfortably covers it without slurping a
    // multi-hundred-MB file into memory on a false-positive flag.
    private const val MAX_TAIL_SCAN_BYTES = 32L * 1024 * 1024

    /**
     * Returns the offset + length of the embedded MP4 trailer, or null if [file] is not a Motion
     * Photo (or can't be parsed). Cheap: reads XMP and a bounded tail window only — never decodes
     * the bitmap. Defensive — any IO/parse error yields null.
     */
    fun detect(file: File): MotionPhotoInfo? {
        return try {
            if (!file.isFile || file.length() <= 0L) return null
            val fileSize = file.length()

            // A readable XMP motion flag is required: real Motion Photos always carry one
            // (modern Camera:MotionPhoto / Container Item, or legacy GCamera). Demanding it keeps
            // a plain JPEG whose compressed data happens to contain an "ftyp" byte run from being
            // mistaken for a Motion Photo, which on the upload path would split the still at a
            // bogus offset. The declared length (Item:Length / MicroVideoOffset) is only a hint;
            // the ftyp tail scan pins the authoritative offset.
            val xmpHint = readXmpHint(file)
            if (xmpHint == null || !xmpHint.hasMotionFlag) return null

            val hintedLength = xmpHint?.videoLength?.takeIf { it in 1 until fileSize }
            val searchStart = when {
                hintedLength != null -> (fileSize - hintedLength - SEARCH_SLOP).coerceAtLeast(0L)
                else -> (fileSize - MAX_TAIL_SCAN_BYTES).coerceAtLeast(0L)
            }

            val videoOffset = findFtypOffset(file, searchStart, fileSize, hintedLength)
                // A wrong hinted length (e.g. a GainMap's, on a multi-item Container) can skip the
                // search window past the real ftyp. Retry once with a full bounded tail scan, hint
                // ignored, before trusting the hinted boundary.
                ?: findFtypOffset(file, (fileSize - MAX_TAIL_SCAN_BYTES).coerceAtLeast(0L), fileSize, null)
                ?: run {
                    // No ftyp box found in the tail. If the XMP hint is firm, trust it as a
                    // last resort so a non-standard box layout still yields a usable split.
                    if (hintedLength != null) fileSize - hintedLength else return null
                }

            if (videoOffset <= 0L || videoOffset >= fileSize) return null
            // Bound the clip at the end of the contiguous MP4 boxes, not at EOF. Samsung appends
            // a proprietary SEF trailer (SEFH/SEFT) after the embedded clip; copying through EOF
            // would tack that non-MP4 tail onto the extracted file and leave it unplayable.
            val mp4Len = RandomAccessFile(file, "r").use { mp4ContentLength(it, videoOffset, fileSize) }
            val videoLength = if (mp4Len > 0L) mp4Len else fileSize - videoOffset
            MotionPhotoInfo(videoOffset = videoOffset, videoLength = videoLength)
        } catch (e: Exception) {
            Log.w(TAG, "detect failed for ${file.name}: ${e.message}")
            null
        }
    }

    /**
     * Copies bytes `[videoOffset, EOF)` from [file] into [dest] as a standalone, playable .mp4.
     * Returns false on any failure (not a Motion Photo, IO error).
     */
    fun extractVideo(file: File, dest: File): Boolean {
        val info = detect(file) ?: return false
        return try {
            RandomAccessFile(file, "r").use { raf ->
                raf.seek(info.videoOffset)
                FileOutputStream(dest).use { out ->
                    val buffer = ByteArray(64 * 1024)
                    var remaining = info.videoLength
                    while (remaining > 0) {
                        val toRead = minOf(buffer.size.toLong(), remaining).toInt()
                        val read = raf.read(buffer, 0, toRead)
                        if (read < 0) break
                        out.write(buffer, 0, read)
                        remaining -= read
                    }
                }
            }
            dest.length() > 0L
        } catch (e: Exception) {
            Log.w(TAG, "extractVideo failed for ${file.name}: ${e.message}")
            runCatching { if (dest.exists()) dest.delete() }
            false
        }
    }

    /**
     * Cheap screen for the Motion Photo XMP flag without copying the whole file: reads a bounded
     * prefix (the JPEG/HEIC XMP segment sits near the start of the primary) and scans it for the
     * motion markers. Lets a content URI be filtered before the full file is staged for [detect],
     * and catches vendors whose file names give no hint (e.g. Samsung's plain DCIM/Camera JPEGs).
     * The caller owns and closes [input].
     */
    fun hasMotionXmp(input: InputStream): Boolean = try {
        val prefix = ByteArray(PREFIX_SCAN_BYTES)
        var read = 0
        while (read < prefix.size) {
            val r = input.read(prefix, read, prefix.size - read)
            if (r < 0) break
            read += r
        }
        // Latin-1 maps every byte to a char, so the ASCII XMP markers are found verbatim in the
        // otherwise-binary prefix. "Camera:MotionPhoto" also matches the "GCamera:MotionPhoto" form.
        val text = String(prefix, 0, read, Charsets.ISO_8859_1)
        text.contains("Camera:MotionPhoto") ||
            text.contains("GCamera:MicroVideo") ||
            SEMANTIC_MOTION_REGEX.containsMatchIn(text)
    } catch (e: Exception) {
        false
    }

    // The primary's XMP segment lands within the first JPEG/HEIC APP segments; this window covers
    // it comfortably (the observed Samsung/Google motion flag sits well under 256 KB).
    private const val PREFIX_SCAN_BYTES = 512 * 1024
    private val SEMANTIC_MOTION_REGEX = Regex("""Semantic\s*=\s*["']MotionPhoto["']""")

    /** Slack added before a hinted boundary so the ftyp scan still finds the box if the encoder's
     *  declared length is off by a small amount (padding, box-header rounding). */
    private const val SEARCH_SLOP = 4096L

    /** Parsed XMP signal: whether a motion flag is present and the declared trailer length. */
    private data class XmpHint(val hasMotionFlag: Boolean, val videoLength: Long?)

    /**
     * Reads the primary's XMP and extracts the motion flag + declared trailer length. Uses
     * ExifInterface to surface the raw XMP packet, then a light string scan for the handful of
     * attributes the two Motion Photo encodings use. Returns null when no XMP is present.
     */
    private fun readXmpHint(file: File): XmpHint? {
        val xmp = try {
            val exif = ExifInterface(file.absolutePath)
            exif.getAttribute(ExifInterface.TAG_XMP)
        } catch (e: Exception) {
            null
        } ?: return null

        val hasMotionFlag =
            Regex("""(?:GCamera:)?MotionPhoto\s*=\s*["']?1""").containsMatchIn(xmp) ||
                xmp.contains("GCamera:MicroVideo") ||
                xmp.contains("Camera:MotionPhoto") ||
                // Container directory form: an Item with Semantic="MotionPhoto".
                Regex("""Semantic\s*=\s*["']MotionPhoto["']""").containsMatchIn(xmp)

        // The trailer byte count. A modern Container lists several items (Primary, an optional HDR
        // GainMap, then the MotionPhoto video appended LAST), so the video's Item:Length is the last
        // one. Taking the FIRST Item:Length grabbed the GainMap's length on multi-item files and
        // pointed the extractor past the real ftyp, producing a corrupt clip that crashed playback.
        // The legacy GCamera form carries a single MicroVideoOffset.
        val length = Regex("""GCamera:MicroVideoOffset\s*=\s*["']?(\d+)""")
            .find(xmp)?.groupValues?.getOrNull(1)?.toLongOrNull()
            ?: Regex("""Item:Length\s*=\s*["']?(\d+)""")
                .findAll(xmp).mapNotNull { it.groupValues.getOrNull(1)?.toLongOrNull() }.lastOrNull()

        return XmpHint(hasMotionFlag = hasMotionFlag, videoLength = length)
    }

    /**
     * Scans [file] from [searchStart] toward EOF for the first MP4 `ftyp` box and returns the box
     * start (4 bytes before the signature). When [hintedLength] is set, the offset it implies
     * (`fileSize - hintedLength`) is probed first so a well-formed file resolves in one read.
     */
    private fun findFtypOffset(
        file: File,
        searchStart: Long,
        fileSize: Long,
        hintedLength: Long?,
    ): Long? {
        RandomAccessFile(file, "r").use { raf ->
            // Fast path: verify the exact hinted boundary before a window scan.
            if (hintedLength != null) {
                val candidate = fileSize - hintedLength
                if (candidate in 4 until fileSize && isFtypBoxStart(raf, candidate)) {
                    return candidate
                }
            }

            val windowSize = (fileSize - searchStart).coerceAtMost(MAX_TAIL_SCAN_BYTES).toInt()
            if (windowSize <= 4) return null
            val window = ByteArray(windowSize)
            raf.seek(searchStart)
            raf.readFully(window)

            // "ftyp" sits at box offset + 4, so the box start is the match index - 4. The size
            // field preceding it must be a plausible box length for the match to be a real box.
            var i = 0
            while (i <= window.size - FTYP.size) {
                if (window[i] == FTYP[0] && window[i + 1] == FTYP[1] &&
                    window[i + 2] == FTYP[2] && window[i + 3] == FTYP[3]
                ) {
                    val boxStart = searchStart + i - 4
                    if (boxStart >= 0 && isPlausibleBoxSize(raf, boxStart, fileSize)) {
                        return boxStart
                    }
                }
                i++
            }
        }
        return null
    }

    /** True when [offset] holds a 4-byte box size followed by the "ftyp" tag and the size is
     *  a plausible box length within the file. */
    private fun isFtypBoxStart(raf: RandomAccessFile, offset: Long): Boolean {
        return try {
            raf.seek(offset + 4)
            val tag = ByteArray(4)
            raf.readFully(tag)
            tag.contentEquals(FTYP) && isPlausibleBoxSize(raf, offset, raf.length())
        } catch (e: Exception) {
            false
        }
    }

    /** Reads the 4-byte big-endian box size at [offset] and checks it fits inside the file.
     *  Sizes 0 (box extends to EOF) and 1 (64-bit extended size follows) are both accepted. */
    private fun isPlausibleBoxSize(raf: RandomAccessFile, offset: Long, fileSize: Long): Boolean {
        return try {
            raf.seek(offset)
            val b = ByteArray(4)
            raf.readFully(b)
            val size = ((b[0].toLong() and 0xFF) shl 24) or
                ((b[1].toLong() and 0xFF) shl 16) or
                ((b[2].toLong() and 0xFF) shl 8) or
                (b[3].toLong() and 0xFF)
            size == 0L || size == 1L || (size in 8..(fileSize - offset))
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Walks the ISO-BMFF top-level boxes from [start] and returns the byte length of the
     * contiguous run of well-formed boxes. A box is `[4-byte big-endian size][4-char type]`;
     * size 1 means a 64-bit size follows the 8-byte header, size 0 means the box runs to EOF.
     * The walk stops at the first bytes that aren't a standard box (a type led by a lowercase
     * ASCII letter: ftyp/moov/mdat/free/skip/uuid...), so a vendor trailer appended after the
     * clip (e.g. Samsung's SEFH/SEFT) is excluded. Returns 0 when no valid box starts at [start].
     */
    private fun mp4ContentLength(raf: RandomAccessFile, start: Long, fileSize: Long): Long {
        var pos = start
        val header = ByteArray(8)
        while (pos + 8 <= fileSize) {
            raf.seek(pos)
            raf.readFully(header)
            // Box type must be a standard MP4 four-char code led by a lowercase letter; Samsung's
            // "SEFH" trailer fails this and ends the walk at the true end of the clip.
            val c0 = header[4].toInt() and 0xFF
            if (c0 < 'a'.code || c0 > 'z'.code) break
            var allPrintable = true
            for (j in 4..7) {
                val c = header[j].toInt() and 0xFF
                if (c < 0x20 || c > 0x7E) { allPrintable = false; break }
            }
            if (!allPrintable) break
            var size = ((header[0].toLong() and 0xFF) shl 24) or
                ((header[1].toLong() and 0xFF) shl 16) or
                ((header[2].toLong() and 0xFF) shl 8) or
                (header[3].toLong() and 0xFF)
            when {
                size == 0L -> return fileSize - start // box runs to EOF
                size == 1L -> {
                    if (pos + 16 > fileSize) break
                    val ext = ByteArray(8)
                    raf.seek(pos + 8)
                    raf.readFully(ext)
                    size = 0L
                    for (b in ext) size = (size shl 8) or (b.toLong() and 0xFF)
                }
            }
            if (size < 8 || pos + size > fileSize) break
            pos += size
        }
        return pos - start
    }
}
