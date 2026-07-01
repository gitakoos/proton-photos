/*
 * Photos for Proton
 * Copyright (C) 2026 Akoos <https://akoos.eu>
 *
 * Source:  https://github.com/gitakoos/proton-photos
 * Website: https://photos.akoos.eu
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

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Reusable multi-select state for a grid screen: the set of selected item keys plus the small
 * mutation vocabulary every selection surface needs (toggle one, replace the swept range, clear).
 * A ViewModel owns one of these and exposes [flow] to the UI; bulk actions read [value] for a
 * stable snapshot. Centralizing it keeps the selection logic in one tested place instead of being
 * re-implemented per screen.
 *
 * @param K the item key type (a link id or a media uri, depending on the screen).
 */
class SelectionState<K> {
    private val _selected = MutableStateFlow<Set<K>>(emptySet())

    /** Observable selection for the UI. Selection mode is active whenever this is non-empty. */
    val flow: StateFlow<Set<K>> = _selected.asStateFlow()

    /** The current selection, for a bulk action that runs against a snapshot. */
    val value: Set<K> get() = _selected.value

    /** Add [key] when absent, remove it when present. */
    fun toggle(key: K) = _selected.update { if (key in it) it - key else it + key }

    /** Replace the whole selection — the drag-to-select sweep paints the swept range through here. */
    fun set(keys: Set<K>) { _selected.value = keys }

    /** Leave selection mode. */
    fun clear() { _selected.value = emptySet() }
}
