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

@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package eu.akoos.photos.presentation.gallery

import eu.akoos.photos.domain.entity.GalleryItem

/** What the library looked like at one moment, enough to tell that photos arrived: how many and the newest. */
internal data class LibraryMark(val count: Int, val newestId: String?)

internal fun libraryMarkOf(items: List<GalleryItem>): LibraryMark =
    LibraryMark(items.size, items.firstOrNull()?.stableId)

/**
 * Whether the library gained photos between [before] and [now]: more of them, or a different newest one while
 * not fewer. The first look and a library that was still empty are not gains, since the start-up scan covers
 * what is there when the app opens.
 */
internal fun libraryGained(before: LibraryMark?, now: LibraryMark): Boolean {
    if (before == null || before.count == 0) return false
    return now.count > before.count || (now.count == before.count && now.newestId != before.newestId)
}

/** How long the library has to stay as it is before new photos are scanned, so a burst is scanned once. */
internal const val NEW_PHOTOS_SCAN_DELAY_MS = 30_000L
