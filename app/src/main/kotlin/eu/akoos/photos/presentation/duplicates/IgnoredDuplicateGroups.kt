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

import eu.akoos.photos.domain.usecase.FindDuplicatesUseCase.DuplicateGroup

/**
 * The key a group is remembered by when the user ignores it: its copies' ids, sorted and joined, so the
 * order the finder lists them in does not matter.
 */
internal fun duplicateIgnoreKey(group: DuplicateGroup): String =
    group.items.map { it.stableId }.sorted().joinToString("|")

/**
 * The groups the user has chosen to leave alone, as the keys [duplicateIgnoreKey] made. A group counts as
 * ignored while every one of its copies belongs to one remembered group, so deleting a copy of an ignored
 * group leaves the rest ignored, while a copy that was not in it (a new look-alike) brings the group back.
 */
internal class IgnoredDuplicateGroups(keys: Set<String>) {
    private val entries: List<Pair<String, Set<String>>> = keys.map { it to it.split('|').toSet() }

    /** Every remembered group a photo is in, by photo id, so a check looks at a few groups, not all. */
    private val byMember: Map<String, List<Int>> = HashMap<String, MutableList<Int>>().also { index ->
        entries.forEachIndexed { i, (_, ids) -> ids.forEach { id -> index.getOrPut(id) { ArrayList() }.add(i) } }
    }

    /** Whether [group] is covered by a remembered group. */
    fun contains(group: DuplicateGroup): Boolean = keysCovering(group).isNotEmpty()

    /** The remembered keys that cover [group]: what stops ignoring it removes. */
    fun keysCovering(group: DuplicateGroup): List<String> {
        val ids = group.items.map { it.stableId }
        val first = ids.firstOrNull() ?: return emptyList()
        val candidates = byMember[first] ?: return emptyList()
        return candidates.filter { entries[it].second.containsAll(ids) }.map { entries[it].first }
    }
}
