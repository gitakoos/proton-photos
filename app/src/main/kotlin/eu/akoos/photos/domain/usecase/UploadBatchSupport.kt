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

package eu.akoos.photos.domain.usecase

import android.util.Log
import eu.akoos.photos.data.preferences.SettingsKeys
import eu.akoos.photos.domain.entity.QueueSource
import eu.akoos.photos.domain.entity.compression.CompressionOutcome
import eu.akoos.photos.domain.entity.compression.CompressionSkipReason
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

internal const val UPLOAD_TAG = "UploadUseCase"

/**
 * #102: whether an auto-queued photo must wait for Wi-Fi under the Wi-Fi-only setting. An explicitly
 * picked upload (a MANUAL "back up now" of a photo, or an ALBUM_ADD) rides mobile data because the
 * user asked for that one; a folder auto-backup or an edit re-upload waits for Wi-Fi. Enforced per
 * photo, so a single manual pick does not drag the whole auto backlog onto cellular just because it
 * opened the pass.
 */
internal fun uploadDefersForWifiOnly(queueSource: String?, wifiOnly: Boolean, onWifi: Boolean): Boolean =
    wifiOnly && !onWifi &&
        queueSource != QueueSource.MANUAL &&
        queueSource != QueueSource.ALBUM_ADD

/**
 * One name-free diagnostics line per compression decision, so release builds (where `Log` is
 * stripped) still record it, e.g. `compress <ref> video SKIPPED ALREADY_EFFICIENT 24.3MB`. The ref is
 * the same non-reversible URI hash the other upload lines use.
 */
internal fun logCompression(
    localUri: String,
    kind: String,
    outcome: CompressionOutcome,
    reason: CompressionSkipReason?,
    sourceBytes: Long,
    outputBytes: Long? = null,
    codec: String? = null,
    detail: String? = null,
) {
    val mb = { b: Long -> "%.1fMB".format(Locale.US, b / 1_000_000.0) }
    val sizes = if (outputBytes != null && outputBytes > 0L) mb(sourceBytes) + "->" + mb(outputBytes) else mb(sourceBytes)
    val line = listOfNotNull(
        "compress", eu.akoos.photos.util.uploadLogRef(localUri), kind, outcome.name, reason?.name, codec, sizes, detail,
    ).joinToString(" ")
    Log.d(UPLOAD_TAG, line)
    eu.akoos.photos.util.SyncDiagnostics.log(line)
}

/**
 * Runs [block] with one held permit handed back. The re-acquire can't be cancelled, because the
 * enclosing `withPermit` releases one on the way out whatever happens.
 */
internal suspend fun <T> Semaphore.releasedWhile(block: suspend () -> T): T {
    release()
    try {
        return block()
    } finally {
        withContext(NonCancellable) { acquire() }
    }
}

/**
 * #123: runs [process] over [items] on [workers] coroutines, each taking the next item no worker has
 * started. Items start in list order, at most [workers] run at once, and the batch holds [workers]
 * coroutines however long [items] is.
 *
 * Before taking an item a worker checks for cancellation and asks [shouldStop]. A true [shouldStop]
 * ends the batch at that boundary: no further item starts, and an item already running is left to
 * finish. [process] contains its own per-item failures; anything that escapes it, a stray
 * cancellation included, cancels the other workers and is rethrown to the caller.
 */
internal suspend fun <T> runUploadWorkers(
    items: List<T>,
    workers: Int,
    shouldStop: () -> Boolean,
    process: suspend (T) -> Unit,
) {
    require(workers > 0) { "workers must be positive: $workers" }
    val next = AtomicInteger(0)
    coroutineScope {
        List(minOf(workers, items.size)) {
            async {
                while (true) {
                    ensureActive()
                    if (shouldStop()) break
                    val index = next.getAndIncrement()
                    if (index >= items.size) break
                    process(items[index])
                }
            }
        }.awaitAll()
    }
}

/**
 * The miss counts behind [FOREIGN_ALBUM_JOIN_ATTEMPTS], kept as "count|albumLinkId|localUri" entries
 * of [SettingsKeys.FOREIGN_ALBUM_JOIN_MISSES]. Neither a link id nor a content uri holds a '|'.
 */
internal object ForeignAlbumJoinMisses {

    private fun pairKey(localUri: String, albumLinkId: String) = "$albumLinkId|$localUri"

    /** [entries] with the pair's count raised by one, and that count. */
    fun bump(entries: Set<String>, localUri: String, albumLinkId: String): Pair<Set<String>, Int> {
        val key = pairKey(localUri, albumLinkId)
        val previous = entries.firstOrNull { it.substringAfter('|') == key }
        val count = (previous?.substringBefore('|')?.toIntOrNull() ?: 0) + 1
        return (entries - setOfNotNull(previous) + "$count|$key") to count
    }

    /** [entries] without the pair's count. */
    fun clear(entries: Set<String>, localUri: String, albumLinkId: String): Set<String> {
        val key = pairKey(localUri, albumLinkId)
        return entries.filterNotTo(HashSet()) { it.substringAfter('|') == key }
    }
}
