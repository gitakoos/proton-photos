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
import androidx.annotation.OptIn
import androidx.media3.common.Metadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.container.MdtaMetadataEntry
import androidx.media3.container.Mp4LocationData

/**
 * The location a video written by Media3 Transformer carries. The extractor drops a `©xyz` string
 * with an altitude (`+47.4979+019.0402+120.000/`), so a re-encoded clip would come out without its
 * place; the fix read back from the source is written in explicitly instead.
 */
object VideoLocationMetadata {

    /** The QuickTime metadata keys that hold a place (ISO 6709 position, place name, accuracy). */
    private const val LOCATION_MDTA_PREFIX = "com.apple.quicktime.location"

    /**
     * The source video's location as the platform reads it (`©xyz` or the QuickTime ISO 6709 key, so
     * an MP4 and a MOV both resolve), through the original URI so Android 10+ does not hand back a
     * redacted stream. Null when the source carries none or cannot be read.
     */
    fun read(context: Context, uri: String): Pair<Double, Double>? =
        PhotoGpsResolver.videoGps(context, originalUriForExif(context, uri))

    /**
     * Rewrites the muxer's metadata [entries] for the output: with [stripLocation] every location entry
     * goes, so a source that still carries one cannot leak it; otherwise the extractor's location entries
     * are replaced by [sourceLocation] when that is known, which leaves the fix in the one `©xyz` atom
     * [Mp4LocationAtoms.erase] can remove. Every other entry stays as it is.
     */
    @OptIn(UnstableApi::class)
    fun apply(entries: MutableSet<Metadata.Entry>, sourceLocation: Pair<Double, Double>?, stripLocation: Boolean) {
        if (stripLocation) {
            entries.removeAll { it is Mp4LocationData || isLocationMdta(it) }
            return
        }
        if (sourceLocation == null) return
        entries.removeAll { it is Mp4LocationData || isLocationMdta(it) }
        entries.add(Mp4LocationData(sourceLocation.first.toFloat(), sourceLocation.second.toFloat()))
    }

    @OptIn(UnstableApi::class)
    private fun isLocationMdta(entry: Metadata.Entry): Boolean =
        entry is MdtaMetadataEntry && entry.key.startsWith(LOCATION_MDTA_PREFIX)
}
