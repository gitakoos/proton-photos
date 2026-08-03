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

/** Where a vault photo now lives, and what it is now called. A null [displayName] is a move that left
 *  the name alone, so whatever the photo was already shown as still stands. */
data class VaultMove(val uri: String, val displayName: String? = null)

/**
 * Where each vault photo this session has moved ended up, keyed by the uri a screen may still be
 * holding it under.
 *
 * A vault photo's uri is its file path, so a rename and a capture-date edit both MOVE it: every screen
 * that took its list before the move keeps a uri that names nothing, and the page it is showing dies
 * with it. Those lists cannot refresh themselves — the viewer works from a snapshot taken when the
 * grid was tapped — so the move has to reach them, and this is the ledger they read it from.
 *
 * The key is the uri as it was, NOT as it is: a screen looks its own photos up by what it holds. So a
 * second move re-points every entry that ended at the file being moved rather than adding a hop, which
 * keeps a lookup one step for any snapshot however old, and leaves nothing to chase at read time.
 *
 * Pure, so what a chain of moves leaves each screen able to find is verifiable without a datastore.
 */
object HiddenVaultMoves {

    /**
     * [moves] after the vault file at [oldUri] became [newUri], shown as [displayName] where the move
     * changed the name and unchanged where [displayName] is null.
     *
     * Every entry that ended at [oldUri] is re-pointed to [newUri], so a snapshot taken before the
     * first move still resolves in one lookup, and [oldUri] gains an entry of its own for a snapshot
     * taken between the two. A move that goes nowhere writes nothing.
     */
    fun folded(
        moves: Map<String, VaultMove>,
        oldUri: String,
        newUri: String,
        displayName: String? = null,
    ): Map<String, VaultMove> {
        if (oldUri == newUri) return moves
        val out = HashMap<String, VaultMove>(moves.size + 1)
        for ((from, to) in moves) {
            // The name carried forward is the newer of the two: a rename that follows a date edit
            // renames the photo the date edit moved, and only one of them touched the name at all.
            out[from] = if (to.uri == oldUri) VaultMove(newUri, displayName ?: to.displayName) else to
        }
        out[oldUri] = VaultMove(newUri, displayName)
        return out
    }
}
