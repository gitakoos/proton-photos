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

package eu.akoos.photos.presentation.gallery

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.delay

/**
 * Asks for a cloud photo's thumbnail to be decrypted and kept from cache trimming, for a face tile
 * whose cover has none yet. Provided at the nav root; a tile composed outside it asks nothing.
 */
val LocalCoverThumbnailRequester: ProvidableCompositionLocal<(String) -> Unit> =
    staticCompositionLocalOf { {} }

/** True when [key] names a cloud photo by its linkId rather than a device file by its uri. */
internal fun isCloudPhotoKey(key: String): Boolean = !key.contains("://")

/** The cloud cover a face tile must ask for: a linkId with no decrypted thumbnail yet, else null. */
internal fun coverKeyToRequest(key: String?, decryptedUrl: String?): String? =
    key?.takeIf { isCloudPhotoKey(it) && decryptedUrl == null }

/**
 * The image model for a face tile's [photoKey]: the decrypted thumbnail of a cloud photo, or a device
 * photo's own uri. A cloud photo without one yet gives null, so the tile keeps its background instead
 * of handing the bare linkId to the image loader, and asks for the thumbnail once the tile has stayed
 * on screen briefly, so a fast fling past a row of faces queues nothing.
 */
@Composable
fun rememberFaceCoverModel(photoKey: String?): Any? {
    val allUrls = LocalThumbnailUrls.current
    // Reads only this tile's entry, so the tile recomposes when its own URL lands and not on every
    // other decrypt.
    val ownUrl by remember(photoKey, allUrls) { derivedStateOf { photoKey?.let { allUrls.value[it] } } }
    val pending = coverKeyToRequest(photoKey, ownUrl)
    if (pending != null) {
        val request = LocalCoverThumbnailRequester.current
        LaunchedEffect(pending) {
            delay(COVER_REQUEST_DELAY_MS)
            request(pending)
        }
        return null
    }
    return photoKey?.let { ownUrl ?: it }
}

private const val COVER_REQUEST_DELAY_MS = 120L
