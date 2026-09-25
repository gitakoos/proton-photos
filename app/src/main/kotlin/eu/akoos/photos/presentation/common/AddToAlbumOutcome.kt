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

import android.content.res.Resources
import eu.akoos.photos.R

/**
 * How an "add to album" landed, counted over the cloud-backed photos the action asked for. The server
 * caps an add at ten links per request, so a partial reject is a normal outcome the user must be told
 * about rather than a rare error. Every surface that adds to an album folds its result through
 * [addToAlbumOutcome] so none of them can silently drop the photos that did not make it.
 */
data class AddToAlbumOutcome(val added: Int, val failed: Int) {
    /** True when at least one requested photo did not join, whether some joined (partial) or none did. */
    val isPartialOrFullFailure: Boolean get() = failed > 0
}

/**
 * Folds an add-to-album result (or a thrown, non-cancellation error) into the counts the UI shows.
 * A thrown error is treated as "every requested photo failed" so the action can never look silently
 * successful.
 */
fun addToAlbumOutcome(
    requested: Int,
    succeededLinkIds: Int,
    failedLinkIds: Int,
    threw: Boolean,
): AddToAlbumOutcome =
    if (threw) AddToAlbumOutcome(added = 0, failed = requested)
    else AddToAlbumOutcome(added = succeededLinkIds, failed = failedLinkIds)

/**
 * What an "add to album" can truthfully say once it returns. A device-only photo is only queued: it
 * uploads first and joins the album after, so it is never reported as added.
 */
sealed interface AddToAlbumMessage {
    /** Every photo joined the album now. */
    data object Added : AddToAlbumMessage

    /** Some photos joined now and [uploading] more join once they upload. */
    data class AddedAndUploading(val uploading: Int) : AddToAlbumMessage

    /** Nothing joined yet: all [uploading] photos join once they upload. */
    data class Uploading(val uploading: Int) : AddToAlbumMessage

    /** Nothing joined and nothing was queued. */
    data object NothingAdded : AddToAlbumMessage
}

/** Picks the [AddToAlbumMessage] for [joined] photos added now and [queued] photos uploading first. */
fun addToAlbumMessage(joined: Int, queued: Int): AddToAlbumMessage = when {
    queued > 0 && joined > 0 -> AddToAlbumMessage.AddedAndUploading(queued)
    queued > 0 -> AddToAlbumMessage.Uploading(queued)
    joined > 0 -> AddToAlbumMessage.Added
    else -> AddToAlbumMessage.NothingAdded
}

/**
 * The snackbar line for a selection added to [albumName], read through [addToAlbumMessage]: the
 * [joined] photos added now, the [queued] ones that join once they upload, or that nothing was added.
 */
fun addToAlbumResultText(resources: Resources, joined: Int, queued: Int, albumName: String): String =
    when (val outcome = addToAlbumMessage(joined, queued)) {
        AddToAlbumMessage.Added -> resources.getString(R.string.gallery_added_to_album, joined, albumName)
        is AddToAlbumMessage.AddedAndUploading -> resources.getQuantityString(
            R.plurals.gallery_add_to_album_some_queued, outcome.uploading, outcome.uploading,
        )
        is AddToAlbumMessage.Uploading -> resources.getString(R.string.gallery_add_to_album_queued)
        AddToAlbumMessage.NothingAdded -> resources.getString(R.string.album_picker_empty)
    }
