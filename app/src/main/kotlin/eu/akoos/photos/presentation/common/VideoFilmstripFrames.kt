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

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.util.LruCache
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger

/**
 * Shared filmstrip frame extraction for the video surfaces (editor trim strip + viewer scrubber).
 * Both used to run their own sequential, all-or-nothing [MediaMetadataRetriever] pass; this pulls
 * that into one progressive, bounded-parallel, cached path so a strip appears immediately and fills
 * in, and re-entry on the same clip is instant.
 *
 * The frames are always keyed sync-point (OPTION_CLOSEST_SYNC) and scaled at decode time, so a
 * strip of small square posters costs a couple of MB even at the editor's higher target.
 */

/** How many retrievers run at once. A single instance is NOT thread-safe, so each worker owns its
 *  own decoder handle; two keeps the strip filling quickly without piling up native decoders. */
private const val EXTRACT_CONCURRENCY = 2

/**
 * URI-keyed cache of completed filmstrips, keyed "uri@count@px". Small on purpose: a strip is a
 * handful of square bitmaps, and only a few videos are ever revisited in one sitting (editor tab
 * swaps, a viewer pager settling back onto the same clip). Eviction recycles the frames it drops so
 * nothing lingers on the heap once a clip ages out.
 */
private object FilmstripCache {
    private const val MAX_ENTRIES = 3

    private val cache = object : LruCache<String, List<Bitmap>>(MAX_ENTRIES) {
        override fun entryRemoved(
            evicted: Boolean,
            key: String,
            oldValue: List<Bitmap>,
            newValue: List<Bitmap>?,
        ) {
            // Only recycle frames actually being dropped. A re-put of the same key (newValue set)
            // hands the live bitmaps back to the map, so those must survive.
            if (newValue != null) return
            for (bmp in oldValue) {
                if (!bmp.isRecycled) bmp.recycle()
            }
        }
    }

    fun get(key: String): List<Bitmap>? = synchronized(this) { cache.get(key) }

    fun put(key: String, frames: List<Bitmap>) = synchronized(this) { cache.put(key, frames) }

    /** Drop a single clip and recycle its frames now (the editor frees eagerly on exit). */
    fun evict(key: String) = synchronized(this) { cache.remove(key) }
}

private fun cacheKey(uri: Uri, frameCount: Int, targetPx: Int): String =
    "$uri@$frameCount@$targetPx"

/**
 * Result of the shared hook: the [frames] to render (fixed size [SnapshotStateList] with a slot per
 * requested frame, filling in as each decodes, so a consumer renders slot-by-slot and the layout
 * never reflows) plus [release] to drop this clip from the cache and recycle its frames right away.
 */
internal class VideoFilmstripFrames internal constructor(
    val frames: SnapshotStateList<Bitmap?>,
    private val cacheKey: String,
) {
    /** Eagerly recycle this clip's frames and evict it. Use where a surface wants the strip gone
     *  the moment it leaves the screen (the editor, alongside its own exit collection). */
    fun release() {
        FilmstripCache.evict(cacheKey)
    }
}

/**
 * Extract [frameCount] evenly-spaced sync-frames of the video at [uri], each scaled to [targetPx]
 * square, off the main thread. Frames land in their own slot as soon as they decode (progressive),
 * a small pool of retrievers runs the decode in parallel (bounded), and a completed strip is cached
 * so re-entry on the same clip is instant. A source the retrievers cannot open yields empty slots
 * and the caller falls back to a plain track.
 *
 * The clip length comes from the retriever itself (METADATA_KEY_DURATION), read once per pass, so
 * extraction starts the instant the URI is known and runs in parallel with any player still
 * preparing rather than waiting on it. [fallbackDurationMs] is used only when the container omits a
 * usable duration; keying is on the URI (plus frame geometry), never on a caller-supplied length.
 *
 * Coroutine cancellation propagates untouched; only real per-frame decode failures are swallowed.
 */
@Composable
internal fun rememberVideoFilmstripFrames(
    uri: Uri?,
    frameCount: Int,
    targetPx: Int,
    fallbackDurationMs: Long = 0L,
): VideoFilmstripFrames {
    val context = LocalContext.current
    val key = if (uri != null) cacheKey(uri, frameCount, targetPx) else ""

    // Fixed-size slot list so out-of-order parallel arrivals still land in time order and the strip
    // never reflows (a consumer draws each slot, null = not-yet-decoded placeholder).
    val frames = remember(uri, frameCount, targetPx) {
        mutableStateListOf<Bitmap?>().apply { repeat(frameCount) { add(null) } }
    }

    LaunchedEffect(uri, frameCount, targetPx) {
        if (uri == null) return@LaunchedEffect

        FilmstripCache.get(key)?.let { cached ->
            for (i in 0 until frameCount) frames[i] = cached.getOrNull(i)
            return@LaunchedEffect
        }

        extractInto(context, uri, fallbackDurationMs, frameCount, targetPx, frames)

        // Commit the completed (dense-prefix) strip so a tab swap / pager re-settle is instant.
        val done = frames.filterNotNull()
        if (done.isNotEmpty()) FilmstripCache.put(key, done)
    }

    return remember(uri, frameCount, targetPx) {
        VideoFilmstripFrames(frames, key)
    }
}

/**
 * Runs the bounded-parallel decode: [EXTRACT_CONCURRENCY] workers, each holding its own retriever,
 * pull the next un-taken frame index off a shared counter until all are done, publishing each frame
 * into its slot the instant it decodes. Every retriever is released in a finally so a cancelled or
 * failed pass never leaks a native decoder.
 *
 * Each worker reads the clip's own reported duration once from its retriever (before touching the
 * frame counter) and evenly spaces its timestamps across that length. [fallbackDurationMs] is used
 * only when the container omits a usable value; a zero span leaves the strip empty and the caller
 * falls back to a plain track.
 */
private suspend fun extractInto(
    context: Context,
    uri: Uri,
    fallbackDurationMs: Long,
    frameCount: Int,
    targetPx: Int,
    frames: SnapshotStateList<Bitmap?>,
) = withContext(Dispatchers.IO) {
    val nextIndex = AtomicInteger(0)
    // Snapshot writes off the main thread need guarding; a short mutex around the single set() is
    // cheaper than moving each publish onto the main dispatcher and keeps arrivals immediate.
    val publishLock = Mutex()

    coroutineScope {
        repeat(EXTRACT_CONCURRENCY) {
            launch {
                val retriever = MediaMetadataRetriever()
                try {
                    val opened = runCatching { retriever.setDataSource(context, uri) }.isSuccess
                    if (!opened) return@launch
                    // The retriever reports the clip length itself, so the strip no longer waits on
                    // a player preparing. Read it once here; fall back to the caller value only when
                    // the container omits a usable duration.
                    val durationMs = retriever
                        .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                        ?.toLongOrNull()
                        ?.takeIf { it > 0L }
                        ?: fallbackDurationMs
                    if (durationMs <= 0L) return@launch
                    while (true) {
                        val i = nextIndex.getAndIncrement()
                        if (i >= frameCount) break
                        val ratio = (i.toFloat() + 0.5f) / frameCount
                        val tUs = (ratio * durationMs * 1000L).toLong()
                        val bmp = try {
                            decodeFrame(retriever, tUs, targetPx)
                        } catch (ce: CancellationException) {
                            throw ce
                        } catch (t: Throwable) {
                            null
                        }
                        if (bmp != null) publishLock.withLock { frames[i] = bmp }
                    }
                } finally {
                    runCatching { retriever.release() }
                }
            }
        }
    }
}

/** One scaled sync-frame at [tUs]. getScaledFrameAtTime decodes straight to the target on O_MR1+;
 *  the older path decodes full then scales. Aspect is preserved either way. */
private fun decodeFrame(
    retriever: MediaMetadataRetriever,
    tUs: Long,
    targetPx: Int,
): Bitmap? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
    retriever.getScaledFrameAtTime(
        tUs,
        MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
        targetPx, targetPx,
    )
} else {
    retriever.getFrameAtTime(tUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
        ?.let { Bitmap.createScaledBitmap(it, targetPx, targetPx, true) }
}
