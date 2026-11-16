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

package eu.akoos.photos.domain.usecase

import eu.akoos.photos.domain.repository.FreeUpUncheckedStore

/** A [FreeUpUncheckedStore] held in memory, for the free-up tests. */
internal class InMemoryFreeUpUncheckedStore : FreeUpUncheckedStore {
    val entries = LinkedHashMap<String, FreeUpUncheckedStore.Kept>()

    override suspend fun all(): Map<String, FreeUpUncheckedStore.Kept> = entries.toMap()

    override suspend fun mark(uri: String, kept: FreeUpUncheckedStore.Kept) {
        entries[uri] = kept
    }

    override suspend fun unmark(uris: Collection<String>) {
        uris.forEach { entries.remove(it) }
    }
}
