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

package eu.akoos.photos.presentation.gallery

import eu.akoos.photos.data.db.dao.LabelUsage
import eu.akoos.photos.data.label.LabelKeyReconciler
import eu.akoos.photos.domain.entity.GalleryItem
import eu.akoos.photos.util.LabelNormalizer

/**
 * The label part of a [ContentFilter], shared by the timeline and Search: a photo passes when it carries
 * the picked labels by their exact folded name, read under every key its labels may sit under.
 */
internal object LabelFilter {

    /** Each photo key's labels as their folded keys, built once per label change. */
    fun index(byPhoto: Map<String, List<String>>): Map<String, Set<String>> =
        byPhoto.mapValues { (_, labels) -> labels.mapTo(HashSet()) { LabelNormalizer.fold(it) } }

    /** [item]'s label keys: those under its stableId and, for a backed-up photo, those still under its device uri. */
    fun keysOf(item: GalleryItem, index: Map<String, Set<String>>): Set<String> {
        if (index.isEmpty()) return emptySet()
        val keys = LabelKeyReconciler.keysFor(item)
        val primary = index[keys[0]].orEmpty()
        val secondary = keys.getOrNull(1)?.let { index[it] } ?: return primary
        return if (primary.isEmpty()) secondary else primary + secondary
    }

    /** Whether [item] carries any of [labelKeys], or every one of them when [matchAll] is set. */
    fun matches(item: GalleryItem, labelKeys: Set<String>, matchAll: Boolean, index: Map<String, Set<String>>): Boolean {
        val held = keysOf(item, index)
        if (held.isEmpty()) return false
        return if (matchAll) held.containsAll(labelKeys) else labelKeys.any { it in held }
    }

    /** [items] narrowed to the labels [filter] asks for, in their order; untouched when it asks for none. */
    fun apply(items: List<GalleryItem>, filter: ContentFilter, index: Map<String, Set<String>>): List<GalleryItem> =
        if (filter.labelKeys.isEmpty()) items
        else items.filter { matches(it, filter.labelKeys, filter.labelsMatchAll, index) }

    /** [filter] without the picked labels the account no longer has, as after a delete or a rename. */
    fun prune(filter: ContentFilter, usage: List<LabelUsage>): ContentFilter {
        if (filter.labelKeys.isEmpty()) return filter
        val existing = usage.mapTo(HashSet()) { it.labelKey }
        val kept = filter.labelKeys.filterTo(LinkedHashSet()) { it in existing }
        return if (kept.size == filter.labelKeys.size) filter else filter.withLabels(kept)
    }

    /** A picked label lets through the photos "Hide photos in albums" keeps off the timeline, as a category does. */
    fun bypassesAlbumHide(filter: ContentFilter): Boolean = filter.labelKeys.isNotEmpty()

    /** The labels the filter section shows: the [top] most used, then any picked one beyond them. */
    fun shownInSection(usage: List<LabelUsage>, picked: Set<String>, top: Int = SECTION_TOP): List<LabelUsage> {
        val head = usage.take(top)
        val rest = usage.drop(top).filter { it.labelKey in picked }
        return head + rest
    }

    /** What names the picked labels on a pill: the one label's name, or [many] for several. */
    fun summary(filter: ContentFilter, usage: List<LabelUsage>, many: (Int) -> String): String? = when (filter.labelKeys.size) {
        0 -> null
        1 -> filter.labelKeys.first().let { key -> usage.firstOrNull { it.labelKey == key }?.label ?: key }
        else -> many(filter.labelKeys.size)
    }

    /** How many labels the filter section shows before its More chip. */
    const val SECTION_TOP = 5
}
