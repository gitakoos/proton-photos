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

package eu.akoos.photos.presentation.albums

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import eu.akoos.photos.presentation.theme.LocalGifAutoplayCovers

/**
 * The model to feed Coil for a cloud album cover, swapping the static [thumbnailUrl] for an
 * animatable full-resolution GIF once cover autoplay is on and [resolve] finds a playable local GIF
 * for [coverLinkId]. Feeding the returned `file://` path to Coil's default (animating) loader plays
 * the GIF; the still thumbnail stands in until it is ready.
 *
 * Returns [thumbnailUrl] unchanged whenever autoplay is off, [coverLinkId] is null/blank, the cover
 * is not a GIF, or its GIF is not yet resolved. [resolve] returns null for every non-GIF cover, so a
 * non-GIF album never downloads anything and behaves exactly as before.
 */
@Composable
fun rememberCoverGifModel(
    coverLinkId: String?,
    thumbnailUrl: String?,
    resolve: suspend (String) -> String?,
): Any? {
    val autoplay = LocalGifAutoplayCovers.current
    var gifPath by remember(coverLinkId) { mutableStateOf<String?>(null) }
    LaunchedEffect(coverLinkId, autoplay) {
        gifPath = if (autoplay && !coverLinkId.isNullOrBlank()) resolve(coverLinkId) else null
    }
    return if (autoplay) gifPath ?: thumbnailUrl else thumbnailUrl
}
