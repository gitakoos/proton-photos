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

import eu.akoos.photos.data.hidden.HiddenFolderRecords

/**
 * Which sentences a hide confirmation is entitled to say about one selection.
 *
 * Three facts decide the whole message, and each is claimed only when the selection actually holds
 * it — a sentence about photos that are not there is worse than saying nothing.
 *
 * [vaultCount] is every photo with a file on the phone. All of them move into the app's own hidden
 * area and leave the phone's gallery, which is the point of hiding and the thing to say first.
 * [cloudCopyCount] is how many of those keep a Proton Drive copy, which the hide never touches and a
 * reveal re-links the photo to. [cloudOnlyCount] is the photos with nothing on the phone to move, so
 * hiding them is a matter of this app's own listings.
 */
data class HideConfirmWording(
    val vaultCount: Int,
    val cloudCopyCount: Int,
    val cloudOnlyCount: Int,
) {
    /** True when there is something to say, which is also when the confirmation is worth raising. */
    val hasClause: Boolean get() = vaultCount > 0 || cloudOnlyCount > 0
}

/**
 * Read the wording straight off the split the hide will actually run on, so the confirmation
 * describes THESE photos rather than what a hide does in general.
 *
 * Pure: no Context, no resources, no coroutines. The halves come from
 * [HiddenFolderRecords.hideSplit], which is the same decision the hide itself follows, so a
 * selection can never be told one thing and have another done to it. The Drive-copy count is read off
 * the pairing each target carries into the vault, which is the very thing a reveal transplants back,
 * so the promise made here is the one the reveal keeps.
 */
fun hideConfirmWording(split: HiddenFolderRecords.HideSplit): HideConfirmWording =
    HideConfirmWording(
        vaultCount = split.vaultable.size,
        cloudCopyCount = split.vaultable.count { it.cloudLinkId != null },
        cloudOnlyCount = split.cloudLinkIds.size,
    )
