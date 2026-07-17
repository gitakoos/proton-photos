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

package eu.akoos.photos.presentation.common

import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.util.LruCache
import android.util.Size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Outcome of trying to resolve an on-device video's poster through the OS thumbnail service.
 *   [Loading]     the fetch is in flight (or waiting on the API guard); keep the tile background.
 *   [Loaded]      a system thumbnail is ready to draw straight into the cell.
 *   [Unavailable] pre-Q, or the provider refused loadThumbnail, so the caller must fall back to
 *                 decoding a video frame through Coil so the tile is never left blank.
 */
internal sealed interface LocalVideoThumb {
    data object Loading : LocalVideoThumb
    data class Loaded(val bitmap: Bitmap) : LocalVideoThumb
    data object Unavailable : LocalVideoThumb
}

/**
 * Grid-sized OS video thumbnails, keyed on "uri@px". `ContentResolver.loadThumbnail` is
 * system-cached and near-instant, but a fling still re-enters the cell many times, so a small
 * bounded cache keeps a warm bitmap for a re-scroll without re-hitting the resolver. Bitmaps are
 * tile-sized (a few hundred px), so even a full cache is a couple of MB. Bounded by entry count
 * because every value is roughly the same small size.
 */
private object LocalVideoThumbnailCache {
    private const val MAX_ENTRIES = 150
    private val cache = object : LruCache<String, Bitmap>(MAX_ENTRIES) {}

    fun get(key: String): Bitmap? = cache.get(key)

    fun put(key: String, bitmap: Bitmap) {
        cache.put(key, bitmap)
    }
}

/**
 * Resolve a LOCAL (on-device) video [uri] to its OS-provided poster [Bitmap], sized to [targetPx]
 * square, off the main thread. Returns [LocalVideoThumb.Loaded] once the system thumbnail lands,
 * [LocalVideoThumb.Unavailable] on pre-Q devices or when the provider rejects the request (the
 * caller then decodes a frame through Coil instead), and [LocalVideoThumb.Loading] until then.
 *
 * Only the specific loadThumbnail failure is swallowed; coroutine cancellation propagates so a
 * flung-past cell stops its own work cleanly.
 */
// The producer assigns value on every path (the pre-Q bail, the cache hit, and the decode result of
// the suspending withContext). The lint check only recognises a direct assignment in the lambda body,
// so it misses the ones made inside a scope function and through a suspend call.
@Suppress("ProduceStateDoesNotAssignValue")
@Composable
internal fun rememberLocalVideoThumbnail(uri: String, targetPx: Int): State<LocalVideoThumb> {
    val context = LocalContext.current
    return produceState<LocalVideoThumb>(
        initialValue = LocalVideoThumb.Loading,
        key1 = uri,
        key2 = targetPx,
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            value = LocalVideoThumb.Unavailable
            return@produceState
        }
        val cacheKey = "$uri@$targetPx"
        LocalVideoThumbnailCache.get(cacheKey)?.let {
            value = LocalVideoThumb.Loaded(it)
            return@produceState
        }
        value = withContext(Dispatchers.IO) {
            try {
                val bmp = context.contentResolver
                    .loadThumbnail(Uri.parse(uri), Size(targetPx, targetPx), null)
                LocalVideoThumbnailCache.put(cacheKey, bmp)
                LocalVideoThumb.Loaded(bmp)
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                // Some providers reject loadThumbnail (e.g. a file:// path or an unsupported
                // codec). Signal the caller to fall back to Coil's frame decoder rather than
                // leaving a blank tile.
                LocalVideoThumb.Unavailable
            }
        }
    }
}
