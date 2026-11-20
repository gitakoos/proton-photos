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

package eu.akoos.photos.presentation.viewer

import eu.akoos.photos.domain.entity.GalleryItem
import eu.akoos.photos.presentation.editor.SavedEditCopy

/** Every key a photo list can know [this] by: its device uri and its Drive link, whichever it has. */
internal fun GalleryItem.identityKeys(): Set<String> = when (this) {
    is GalleryItem.LocalOnly -> setOf(local.uri)
    is GalleryItem.CloudOnly -> setOf(cloud.linkId)
    is GalleryItem.Synced -> setOf(cloud.linkId, local.uri)
}

private fun GalleryItem.isKnownBy(keys: Set<String>): Boolean = identityKeys().any { it in keys }

private fun GalleryItem.isKnownBy(key: String): Boolean = when (this) {
    is GalleryItem.LocalOnly -> local.uri == key
    is GalleryItem.CloudOnly -> cloud.linkId == key
    is GalleryItem.Synced -> cloud.linkId == key || local.uri == key
}

/**
 * [snapshot] with each editor copy in [copies] put right after the photo it was made from, so the viewer
 * can page to the edit. Only a copy of a photo already in [snapshot] is added, and only once: the list
 * stays what the caller handed over plus the user's own edits of it.
 *
 * The copy is taken from [live] when the timeline already has it. A device copy the timeline has not
 * listed yet (or never lists, as a hidden photo's copy) is shown as its source's device file under the
 * copy's own uri, name and type; a Drive copy waits for [live]. Returns [snapshot] itself when nothing
 * was added.
 */
internal fun insertSavedCopies(
    snapshot: List<GalleryItem>,
    copies: List<SavedEditCopy>,
    live: List<GalleryItem>,
): List<GalleryItem> {
    if (copies.isEmpty() || snapshot.isEmpty()) return snapshot
    var out: MutableList<GalleryItem>? = null
    for (copy in copies.sortedBy { it.seq }) {
        val list = out ?: snapshot
        val at = list.indexOfFirst { it.isKnownBy(copy.sourceKeys) }
        if (at < 0 || list.any { it.isKnownBy(copy.copyKey) }) continue
        val item = live.firstOrNull { it.isKnownBy(copy.copyKey) }
            ?: provisionalDeviceCopy(list[at], copy)
            ?: continue
        val target = out ?: snapshot.toMutableList().also { out = it }
        target.add(at + 1, item)
    }
    return out ?: snapshot
}

private fun provisionalDeviceCopy(source: GalleryItem, copy: SavedEditCopy): GalleryItem? {
    if (!copy.copyIsLocal) return null
    val local = when (source) {
        is GalleryItem.LocalOnly -> source.local
        is GalleryItem.Synced -> source.local
        is GalleryItem.CloudOnly -> return null
    }
    return GalleryItem.LocalOnly(
        local.copy(
            uri = copy.copyKey,
            displayName = copy.copyName ?: local.displayName,
            mimeType = copy.copyMimeType ?: local.mimeType,
        ),
    )
}

/** What the viewer does once copies are in its list: the copies now seen, and the page to go to. */
internal data class SavedCopyLanding(val seen: Set<Long>, val goTo: Int?)

/**
 * Marks every copy in [copies] that [items] now holds as seen, and pages to the newest of them when the
 * page the user is settled on, [settledPage], is still the photo it was made from. A copy that turns up
 * after the user paged elsewhere is only marked, never jumped to. Null when no new copy is in [items].
 */
internal fun savedCopyLanding(
    items: List<GalleryItem>,
    copies: List<SavedEditCopy>,
    seen: Set<Long>,
    settledPage: Int,
): SavedCopyLanding? {
    val fresh = copies.filter { it.seq !in seen }
    if (fresh.isEmpty()) return null
    val present = fresh.mapNotNull { copy ->
        val at = items.indexOfFirst { it.isKnownBy(copy.copyKey) }
        if (at >= 0) copy to at else null
    }
    if (present.isEmpty()) return null
    val settled = items.getOrNull(settledPage)
    val goTo = present
        .filter { (copy, _) -> settled?.isKnownBy(copy.sourceKeys) == true }
        .maxByOrNull { (copy, _) -> copy.seq }
        ?.second
    return SavedCopyLanding(present.map { it.first.seq }.toSet(), goTo)
}
