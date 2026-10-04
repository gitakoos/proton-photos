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

package eu.akoos.photos.presentation.settings

import eu.akoos.photos.data.db.dao.LabelUsage

/*
 * The selection on the Labels page, kept pure so each rule runs in a plain JVM test. A selection is the folded
 * keys of the labels picked, in the order they were picked.
 */

/** [selected] with [key] added at the end, or taken out when it is there. */
internal fun toggledLabelSelection(selected: List<String>, key: String): List<String> =
    if (key in selected) selected - key else selected + key

/** The selection once labels that are gone from [available] (deleted or renamed meanwhile) are dropped. */
internal fun prunedLabelSelection(selected: List<String>, available: List<LabelUsage>): List<String> {
    val keys = available.mapTo(HashSet()) { it.labelKey }
    return selected.filter { it in keys }
}

/** The selected labels as [available] lists them, in the order they were picked. */
internal fun selectedLabels(selected: List<String>, available: List<LabelUsage>): List<LabelUsage> {
    val byKey = available.associateBy { it.labelKey }
    return selected.mapNotNull { byKey[it] }
}

/**
 * The photo keys the picker leaves out when photos are added to several labels at once: only the photos that
 * already carry every one of them, since a photo with some of them still gains the rest. No labels leave out nothing.
 */
internal fun photosCarryingAll(keysPerLabel: List<Set<String>>): Set<String> =
    keysPerLabel.reduceOrNull { common, next -> common intersect next }.orEmpty()
