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

import android.content.Context
import android.net.Uri

/**
 * Cheap detector for Google Photo Sphere / panorama stills. The marker lives in an XMP
 * packet (`http://ns.google.com/photos/1.0/panorama/`) that camera apps embed near the
 * head of the JPEG, so scanning the first slice of the stream is enough to flag one
 * without decoding pixels or pulling in an XMP parser.
 *
 * Detection is intentionally permissive — any of the GPano signals is treated as a hit.
 * The viewer only uses the result to offer an optional pan affordance, so a rare false
 * positive degrades to "user can drag a normal-aspect photo a little", never a crash.
 */
object PanoramaDetector {

    /** Read at most this many bytes from the head of the file looking for the marker.
     *  GPano sits in the APP1/XMP segment right after SOI, comfortably inside 64 KB. */
    private const val SCAN_BYTES = 64 * 1024

    /** ASCII needles that appear in a GPano XMP packet. Any match flags a panorama. */
    private val MARKERS = listOf(
        "GPano:",
        "ns.google.com/photos/1.0/panorama",
        "UsePanoramaViewer",
        "ProjectionType",
    )

    /**
     * Scans the head of [uri] for GPano markers. Works for both `file://` and `content://`
     * sources — both resolve through the [ContentResolver][android.content.ContentResolver]
     * stream API. Caller is responsible for running this off the main thread.
     */
    fun isPanorama(context: Context, uri: Uri): Boolean {
        return runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                val buffer = ByteArray(SCAN_BYTES)
                var read = 0
                // Stream may hand back short reads; keep filling until the buffer is full
                // or the source is exhausted.
                while (read < buffer.size) {
                    val n = input.read(buffer, read, buffer.size - read)
                    if (n < 0) break
                    read += n
                }
                if (read <= 0) return@use false
                // Latin-1 keeps every byte 1:1 so the ASCII markers survive intact even
                // when surrounding binary bytes aren't valid UTF-8.
                val head = String(buffer, 0, read, Charsets.ISO_8859_1)
                MARKERS.any { head.contains(it) }
            } ?: false
        }.getOrDefault(false)
    }
}
