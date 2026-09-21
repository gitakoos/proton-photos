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
