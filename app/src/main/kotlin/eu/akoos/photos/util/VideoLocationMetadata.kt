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

import android.content.Context
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.annotation.OptIn
import androidx.core.net.toUri
import androidx.media3.common.Metadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.container.MdtaMetadataEntry
import androidx.media3.container.Mp4LocationData
import java.io.File

/**
 * A video's place: the [latitude] and [longitude] a muxer can write, and the source's own ISO 6709
 * string ([iso6709], altitude and zero padding included) when its bytes could be read, which
 * [VideoLocationMetadata.carryOver] puts back into the output as it was.
 */
data class VideoLocation(val latitude: Double, val longitude: Double, val iso6709: String? = null)

/**
 * The location a video written by Media3 Transformer or the platform muxer carries. Both write only a
 * latitude and longitude in their own format (`+47.4979+19.0402/`), and the Media3 extractor drops a
 * `©xyz` string with an altitude altogether, so the fix read back from the source is written in
 * explicitly and the source's own string then replaces the muxer's.
 */
object VideoLocationMetadata {

    private const val TAG = "VideoLocationMetadata"

    /** The QuickTime metadata keys that hold a place (ISO 6709 position, place name, accuracy). */
    private const val LOCATION_MDTA_PREFIX = "com.apple.quicktime.location"

    /**
     * The source video's location (`©xyz` or the QuickTime ISO 6709 key, so an MP4 and a MOV both
     * resolve), through the original URI so Android 10+ does not hand back a redacted stream. The
     * string itself comes from the file's bytes; when those cannot be read (a redacted or unseekable
     * stream) the platform's reading gives the latitude and longitude alone. Null when the source
     * carries none or cannot be read.
     */
    fun read(context: Context, uri: String): VideoLocation? {
        val original = originalUriForExif(context, uri)
        return locationOf(rawIso6709(context, original)) { PhotoGpsResolver.videoGps(context, original) }
    }

    /**
     * The location for a source whose bytes held [raw]: the raw string when it holds a usable fix, else
     * the latitude and longitude [platformFix] reads, without a string.
     */
    internal fun locationOf(raw: String?, platformFix: () -> Pair<Double, Double>?): VideoLocation? {
        raw?.let { PhotoGpsResolver.parseIso6709(it) }?.let { (lat, lng) -> return VideoLocation(lat, lng, raw) }
        return platformFix()?.let { (lat, lng) -> VideoLocation(lat, lng) }
    }

    private fun rawIso6709(context: Context, uri: String): String? = runCatching {
        val readable = eu.akoos.photos.data.localcrypto.VaultFiles.readableUri(context, uri.toUri())
        context.contentResolver.openFileDescriptor(readable, "r")?.let { pfd ->
            ParcelFileDescriptor.AutoCloseInputStream(pfd).use { Mp4LocationAtoms.readIso6709(it.channel) }
        }
    }.getOrNull()

    /**
     * Rewrites the muxer's metadata [entries] for the output: with [stripLocation] every location entry
     * goes, so a source that still carries one cannot leak it; otherwise the extractor's location entries
     * are replaced by [sourceLocation] when that is known, which leaves the fix in the one `©xyz` atom
     * [carryOver] rewrites and [Mp4LocationAtoms.erase] can remove. Every other entry stays as it is.
     */
    @OptIn(UnstableApi::class)
    fun apply(entries: MutableSet<Metadata.Entry>, sourceLocation: VideoLocation?, stripLocation: Boolean) {
        if (stripLocation) {
            entries.removeAll { it is Mp4LocationData || isLocationMdta(it) }
            return
        }
        if (sourceLocation == null) return
        entries.removeAll { it is Mp4LocationData || isLocationMdta(it) }
        entries.add(Mp4LocationData(sourceLocation.latitude.toFloat(), sourceLocation.longitude.toFloat()))
    }

    /**
     * Puts [location]'s source string into the finished [output] in place of the muxer's two-number
     * `©xyz`, so the altitude and the source's own formatting survive. Runs once the muxer has closed
     * the file. Without a string, or when the rewrite does not succeed, the output keeps the muxer's
     * latitude and longitude. True when the output carries the source string.
     */
    fun carryOver(output: File, location: VideoLocation?): Boolean {
        val iso6709 = location?.iso6709 ?: return false
        val written = Mp4LocationAtoms.writeIso6709(output, iso6709)
        if (!written) Log.w(TAG, "source location string not carried over; output keeps latitude and longitude")
        return written
    }

    @OptIn(UnstableApi::class)
    private fun isLocationMdta(entry: Metadata.Entry): Boolean =
        entry is MdtaMetadataEntry && entry.key.startsWith(LOCATION_MDTA_PREFIX)
}
