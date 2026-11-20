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

package eu.akoos.photos.presentation.editor

import android.content.ContentResolver
import android.net.Uri
import android.provider.MediaStore
import eu.akoos.photos.domain.repository.DrivePhotoRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.atomic.AtomicLong

/**
 * A copy an editor save made of a photo.
 *
 * [sourceKeys] are what the edited photo is known by in a photo list: its device uri, its Drive link,
 * or both. [copyKey] is the copy's device uri when [copyIsLocal], else its Drive link. [copyName] and
 * [copyMimeType] describe a device copy, so it can be shown before the gallery has listed it.
 */
data class SavedEditCopy(
    val seq: Long,
    val sourceKeys: Set<String>,
    val copyKey: String,
    val copyIsLocal: Boolean,
    val copyName: String? = null,
    val copyMimeType: String? = null,
)

/**
 * The copies editor saves made in this process, newest last, so a viewer that opened the editor can
 * page to the copy once it exists. A Drive copy is only known when its upload ends, after the editor
 * has closed, which is why this is a shared flow rather than a value handed back on close.
 */
object EditorSavedCopies {
    /** Enough for any one session; the oldest go first. */
    private const val KEEP = 50

    private val nextSeq = AtomicLong()
    private val copies = MutableStateFlow<List<SavedEditCopy>>(emptyList())
    val all: StateFlow<List<SavedEditCopy>> = copies.asStateFlow()

    /** The seq of the newest copy so far; a viewer keeps only copies above the value it opened with. */
    fun latestSeq(): Long = nextSeq.get()

    fun record(
        sourceKeys: Set<String>,
        copyKey: String,
        copyIsLocal: Boolean,
        copyName: String? = null,
        copyMimeType: String? = null,
    ) {
        val keys = sourceKeys.filter { it.isNotBlank() }.toSet()
        if (keys.isEmpty() || copyKey.isBlank() || copyKey in keys) return
        val entry = SavedEditCopy(nextSeq.incrementAndGet(), keys, copyKey, copyIsLocal, copyName, copyMimeType)
        copies.update { (it + entry).takeLast(KEEP) }
    }
}

/** Records a device copy, with the name and type MediaStore gave it when it has them (a hidden copy has none). */
internal fun recordDeviceEditCopy(resolver: ContentResolver, sourceKeys: Set<String>, copyUri: Uri) {
    val (name, mime) = runCatching {
        resolver.query(
            copyUri,
            arrayOf(MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.MIME_TYPE),
            null, null, null,
        )?.use { c -> if (c.moveToFirst()) c.getString(0) to c.getString(1) else null }
    }.getOrNull() ?: (null to null)
    EditorSavedCopies.record(sourceKeys, copyUri.toString(), copyIsLocal = true, copyName = name, copyMimeType = mime)
}

/** Whether [linkId] went into the album: the call returned and did not report that link as failed. */
internal fun albumAddSucceeded(
    outcome: Result<DrivePhotoRepository.AddPhotosToAlbumResult>,
    linkId: String,
): Boolean = outcome.getOrNull()?.let { linkId !in it.failedLinkIds } ?: false
