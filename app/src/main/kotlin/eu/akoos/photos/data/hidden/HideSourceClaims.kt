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

package eu.akoos.photos.data.hidden

/**
 * Which hide run is moving each device photo into the vault, process-wide.
 *
 * Every live timeline screen runs its own hide, and each of them takes in the hides another device
 * asked for. Without one owner per photo, two of them copy the same photo into the vault and each asks
 * the system to delete the original, so a declined prompt undoes only one of the copies. A run claims
 * the source uris of its photos before it copies anything and works only on the ones it won; the others
 * belong to a run already moving them.
 *
 * A claim is keyed by its [owner], so a run that claims a photo it already holds keeps it, and a screen
 * that goes away drops every claim it held in one call.
 */
class HideSourceClaims {

    private val lock = Any()

    /** The owner of each claimed source uri. Lost on process death, which is right: no run survives it. */
    private val owners = HashMap<String, Any>()

    /** Claims [sources] for [owner] and returns the ones it now holds: free ones and its own. */
    fun claim(owner: Any, sources: Collection<String>): Set<String> = synchronized(lock) {
        sources.filterTo(LinkedHashSet()) { source ->
            val held = owners[source]
            when {
                held == null -> { owners[source] = owner; true }
                else -> held === owner
            }
        }
    }

    /** Ends [owner]'s claim on [sources]; a source another owner holds stays with it. */
    fun release(owner: Any, sources: Collection<String>) {
        synchronized(lock) { for (source in sources) if (owners[source] === owner) owners.remove(source) }
    }

    /** Ends every claim [owner] holds. */
    fun releaseAll(owner: Any) {
        synchronized(lock) { owners.entries.removeAll { it.value === owner } }
    }

    /** The source uris some run holds now. */
    fun held(): Set<String> = synchronized(lock) { owners.keys.toSet() }
}
