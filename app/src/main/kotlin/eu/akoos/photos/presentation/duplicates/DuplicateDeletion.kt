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

package eu.akoos.photos.presentation.duplicates

/**
 * Which copies of one duplicate group may be deleted, given what the user chose to keep and what
 * this session has already deleted.
 *
 * Pure so the rule that stands between the user and a photo they cannot get back is pinned by tests
 * rather than read off a screen.
 */
object DuplicateDeletion {

    /**
     * The ids safe to delete, or empty when the deletion must not go ahead.
     *
     * A keeper that has already been deleted is not a keeper. The same two photos can be presented
     * on two cards at once, because a byte-identical pair shares a content hash AND fingerprints
     * alike, so it forms an exact group and a similar cluster. Keeping A on one card while keeping B
     * on the other names a survivor each time, yet the second deletion takes the last copy that was
     * still there. Counting only keepers that are still present is what makes the promise on the
     * card, that the rest stay, true across both of them.
     */
    fun deletableExtras(
        groupIds: List<String>,
        keepIds: Set<String>,
        alreadyDeleted: Set<String>,
    ): List<String> {
        val survivingKeepers = groupIds.filter { it in keepIds && it !in alreadyDeleted }
        if (survivingKeepers.isEmpty()) return emptyList()
        return groupIds.filter { it !in keepIds && it !in alreadyDeleted }
    }
}
