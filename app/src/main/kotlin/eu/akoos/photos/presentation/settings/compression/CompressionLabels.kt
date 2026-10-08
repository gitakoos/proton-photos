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

package eu.akoos.photos.presentation.settings.compression

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import eu.akoos.photos.R
import eu.akoos.photos.domain.entity.compression.VideoCodec
import java.text.DecimalFormat
import kotlin.math.roundToInt

@Composable
fun codecDisplayName(codec: VideoCodec): String = stringResource(
    when (codec) {
        VideoCodec.AVC -> R.string.codec_name_avc
        VideoCodec.HEVC -> R.string.codec_name_hevc
        VideoCodec.AV1 -> R.string.codec_name_av1
    }
)

/** "1.8×": one decimal under ten, none above. */
@Composable
private fun speedLabel(speedX: Float): String {
    val formatted = if (speedX < 10f) DecimalFormat("0.0").format(speedX.toDouble()) else speedX.roundToInt().toString()
    return stringResource(R.string.compression_speed_x, formatted)
}

/** "Compressing 42% · H.265 · 1.8×": the codec and the speed once known. */
@Composable
fun compressingLabel(fraction: Float, codecMime: String?, speedX: Float?): String = buildList {
    add(stringResource(R.string.upload_status_compressing_percent, (fraction.coerceIn(0f, 1f) * 100).roundToInt()))
    VideoCodec.fromMime(codecMime)?.let { add(codecDisplayName(it)) }
    speedX?.takeIf { it > 0f }?.let { add(speedLabel(it)) }
}.joinToString(" · ")
