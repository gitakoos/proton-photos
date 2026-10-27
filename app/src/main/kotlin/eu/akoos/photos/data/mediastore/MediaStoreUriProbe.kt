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

package eu.akoos.photos.data.mediastore

import android.content.ContentResolver
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import eu.akoos.photos.util.isMediaStoreUri

/** Match [eu.akoos.photos.util.SQL_CHUNK_SIZE]: SQLite host-variable floor is 999 on older Android. */
private const val MEDIA_STORE_ID_CHUNK = 500

/**
 * Trailing MediaStore row id of a content URI, or null when the last path segment is not numeric.
 * Pure so reclaim / queue pruning can classify URIs without touching the provider.
 */
fun mediaStoreRowId(uri: String): Long? =
    uri.substringAfterLast('/').substringBefore('?').toLongOrNull()

/**
 * Which of [uriStrings] still name a MediaStore row whose id appears in [existingIds]. Pure.
 *
 * A URI with no numeric id is treated as missing: createDeleteRequest / createTrashRequest cannot
 * act on it, and leaving it in a consent batch is what makes the whole request throw.
 */
fun urisPresentInMediaStore(uriStrings: Collection<String>, existingIds: Set<Long>): List<String> =
    uriStrings.filter { uri ->
        val id = mediaStoreRowId(uri) ?: return@filter false
        id in existingIds
    }

/**
 * Presence of a MediaStore row keyed by [_ID], with the trashed flag when the column is available
 * (API 30+). Used to prune stale URIs before building a delete/trash consent request.
 */
data class MediaStoreRowPresence(
    val id: Long,
    val isTrashed: Boolean,
)

/**
 * Outcome of a bulk MediaStore id presence probe.
 *
 * A completed empty [Complete.byId] means every probed id is genuinely absent. [Inconclusive]
 * means the provider gave no trustworthy answer (null cursor or thrown exception) — callers must
 * not invent "missing" / CLOUD_ONLY / queue pruning from it.
 */
sealed class MediaStoreRowsProbe {
    data class Complete(val byId: Map<Long, MediaStoreRowPresence>) : MediaStoreRowsProbe()
    data object Inconclusive : MediaStoreRowsProbe()
}

/**
 * Classification of unresolved delete targets after a conclusive presence probe. Pure.
 */
data class UnresolvedDeleteClassification(
    val missing: List<String>,
    val needsPermission: List<String>,
)

/**
 * Classify unresolved delete URIs from a presence [probe].
 *
 * Returns null when [probe] is [MediaStoreRowsProbe.Inconclusive] so free-up can retry instead of
 * marking unresolved rows CLOUD_ONLY. A completed empty map still classifies every numeric-id URI
 * as missing.
 */
fun classifyUnresolvedAfterProbe(
    unresolved: Collection<String>,
    probe: MediaStoreRowsProbe,
): UnresolvedDeleteClassification? = when (probe) {
    MediaStoreRowsProbe.Inconclusive -> null
    is MediaStoreRowsProbe.Complete -> {
        val alive = urisPresentInMediaStore(unresolved, probe.byId.keys).toHashSet()
        UnresolvedDeleteClassification(
            missing = unresolved.filter { it !in alive },
            needsPermission = unresolved.filter { it in alive },
        )
    }
}

/**
 * Whether pending-delete reconciliation may prune its queue from [probe]. Pure.
 * An inconclusive probe must leave the queue and SyncState statuses untouched.
 */
fun canPrunePendingDeleteFromProbe(probe: MediaStoreRowsProbe): Boolean =
    probe is MediaStoreRowsProbe.Complete

/**
 * Bulk-probe which MediaStore row ids among [uriStrings] still exist, via chunked
 * `MediaStore.Files` `_ID IN (…)` queries (at [MEDIA_STORE_ID_CHUNK]). Prefer this over one query
 * per URI when pruning large free-up or pending-delete batches.
 *
 * A null cursor or provider exception yields [MediaStoreRowsProbe.Inconclusive]. An empty input
 * yields [MediaStoreRowsProbe.Complete] with an empty map without touching the resolver. A
 * completed empty map means the probed ids are genuinely absent.
 */
fun queryExistingMediaStoreRows(
    resolver: ContentResolver,
    uriStrings: Collection<String>,
): MediaStoreRowsProbe {
    val ids = uriStrings.mapNotNull { mediaStoreRowId(it) }.distinct()
    if (ids.isEmpty()) return MediaStoreRowsProbe.Complete(emptyMap())
    return try {
        val collection = MediaStore.Files.getContentUri("external")
        val projection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            arrayOf(MediaStore.Files.FileColumns._ID, MediaStore.MediaColumns.IS_TRASHED)
        } else {
            arrayOf(MediaStore.Files.FileColumns._ID)
        }
        val found = LinkedHashMap<Long, MediaStoreRowPresence>(ids.size)
        for (chunk in ids.chunked(MEDIA_STORE_ID_CHUNK)) {
            val placeholders = chunk.joinToString(",") { "?" }
            val selection = "${MediaStore.Files.FileColumns._ID} IN ($placeholders)"
            val args = Array(chunk.size) { i -> chunk[i].toString() }
            val cursor = resolver.query(collection, projection, selection, args, null)
                ?: return MediaStoreRowsProbe.Inconclusive
            cursor.use {
                val idCol = it.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
                val trashCol = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    it.getColumnIndex(MediaStore.MediaColumns.IS_TRASHED)
                } else {
                    -1
                }
                while (it.moveToNext()) {
                    val id = it.getLong(idCol)
                    val trashed = trashCol >= 0 && it.getInt(trashCol) != 0
                    found[id] = MediaStoreRowPresence(id = id, isTrashed = trashed)
                }
            }
        }
        MediaStoreRowsProbe.Complete(found)
    } catch (e: Exception) {
        if (e is kotlinx.coroutines.CancellationException) throw e
        MediaStoreRowsProbe.Inconclusive
    }
}

/**
 * Parse [uriString] to a MediaStore content [Uri], or null when it is not a well-formed
 * `content://media/…` URI. Non-MediaStore schemes/authorities are rejected before [Uri.parse].
 */
fun parseContentUriOrNull(uriString: String): Uri? {
    if (!isMediaStoreUri(uriString)) return null
    return runCatching { Uri.parse(uriString) }.getOrNull()
}
