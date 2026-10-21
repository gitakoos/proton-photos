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

package eu.akoos.photos.presentation.picker

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.akoos.photos.data.hidden.HiddenStorageManager
import eu.akoos.photos.data.preferences.currentShareStripConfig
import eu.akoos.photos.domain.entity.GalleryItem
import eu.akoos.photos.util.OutgoingPhotoUris
import eu.akoos.photos.util.ShareIntentBuilder
import eu.akoos.photos.widget.HiddenSetsSource
import eu.akoos.photos.widget.WidgetHiddenSets
import eu.akoos.photos.widget.isInHiddenFolder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import javax.inject.Inject

/**
 * Hands the photos picked in the album picker back to the app that asked for them. The picker shows the
 * gallery as it is, so this is where what may leave is decided: only the kinds the request named, and
 * never a photo the user put away, read from the records the widget reads.
 */
class ExternalPickHandover @Inject constructor(
    @ApplicationContext private val context: Context,
    private val outgoingUris: OutgoingPhotoUris,
    private val hiddenSource: HiddenSetsSource,
    private val hiddenStorage: HiddenStorageManager,
) {

    /** What [picks] come to: the answer for the asking app, or why none of them can go to it. */
    suspend fun answer(request: PickRequest, picks: List<GalleryItem>): PickAnswer {
        val hidden = hiddenSource.observe().first()
        val twins = hiddenSource.hiddenTwins(flowOf(hidden)).first()
        val items = handoverItems(request, picks, hidden, twins, hiddenStorage::isHiddenUri)
        if (items.isEmpty()) return nothingToHandOver(request, picks)
        val strip = currentShareStripConfig(context)
        val uris = ArrayList<Uri>()
        val mimes = ArrayList<String>()
        for (item in items) {
            try {
                uris += outgoingUris.uriFor(item, strip)
                mimes += ShareIntentBuilder.shareMimeAndName(item).first
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "a pick could not be prepared: ${e.message}")
            }
        }
        return if (uris.isEmpty()) PickAnswer.Failed else PickAnswer.Ready(pickResultIntent(uris, mimes))
    }

    private companion object {
        const val TAG = "ExternalPick"
    }
}

/** What a pick from another app comes to. */
sealed interface PickAnswer {
    data class Ready(val intent: Intent) : PickAnswer

    /** None of the picks is of a kind the request takes; [videosOnly] when it takes videos and no photos. */
    data class WrongKind(val videosOnly: Boolean) : PickAnswer

    /** Every pick of the right kind is one the user put away. */
    data object OnlyHidden : PickAnswer

    /** None of the picks that may go out could be prepared. */
    data object Failed : PickAnswer
}

/** Why none of [picks] may go out: none is of a kind [request] takes, or the ones that are, are hidden. */
internal fun nothingToHandOver(request: PickRequest, picks: List<GalleryItem>): PickAnswer =
    if (picks.none { request.accepts(ShareIntentBuilder.shareMimeAndName(it).first) }) {
        PickAnswer.WrongKind(videosOnly = !request.accepts("image/jpeg"))
    } else {
        PickAnswer.OnlyHidden
    }

/**
 * The picks that may go out, in picked order: of a kind [request] takes, not hidden (by its Drive copy,
 * its folder, a hidden Drive twin, or a file in the vault), and only the first when the request takes
 * one photo.
 */
internal fun handoverItems(
    request: PickRequest,
    picks: List<GalleryItem>,
    hidden: WidgetHiddenSets,
    hiddenTwinUris: Set<String>,
    isVaulted: (String) -> Boolean,
): List<GalleryItem> {
    val allowed = picks.filter { item ->
        val cloudId = when (item) {
            is GalleryItem.CloudOnly -> item.cloud.linkId
            is GalleryItem.Synced -> item.cloud.linkId
            is GalleryItem.LocalOnly -> null
        }
        val local = when (item) {
            is GalleryItem.LocalOnly -> item.local
            is GalleryItem.Synced -> item.local
            is GalleryItem.CloudOnly -> null
        }
        request.accepts(ShareIntentBuilder.shareMimeAndName(item).first) &&
            (cloudId == null || cloudId !in hidden.cloudIds) &&
            (local == null || (local.uri !in hiddenTwinUris && !isVaulted(local.uri) &&
                !isInHiddenFolder(local.bucketName, hidden.folderNames)))
    }
    return if (request.allowMultiple) allowed else allowed.take(1)
}
