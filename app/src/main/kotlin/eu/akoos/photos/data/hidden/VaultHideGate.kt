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
 * Keeps a hide and a sign-out from meeting over one vault file.
 *
 * A hide deletes the original of every vault file it journals, so from the journal on that file is
 * the only copy the photo will have. A sign-out that destroyed it in that stretch would lose the
 * photo outright. The two are ordered here: a hide is admitted only while no sign-out runs, and a
 * sign-out learns, at the moment it starts, every file a hide already admitted. No hide is admitted
 * after that moment, so the set it learns is every file whose original may still be deleted while
 * it runs, and it spares all of them.
 *
 * A hide whose admission is refused deletes no original: every surface drops its copies and leaves
 * the photos where they were.
 */
class VaultHideGate {

    private val lock = Any()

    /** Vault uris journalled and not yet confirmed or discarded. Lost on process death, which is
     *  right: after a restart there is no hide in flight. */
    private val inFlight = mutableSetOf<String>()

    /** Sign-outs running now. A count, so two overlapping ones each hold the gate shut. */
    private var signOuts = 0

    /** Admit a hide of [uris], or refuse it (false) because a sign-out is running. */
    fun admit(uris: Collection<String>): Boolean = synchronized(lock) {
        if (signOuts > 0) return false
        inFlight += uris
        true
    }

    /** The hide of [uris] has ended: published, or dropped with its originals untouched. */
    fun release(uris: Collection<String>) {
        synchronized(lock) { inFlight -= uris.toSet() }
    }

    /** The vault uris a hide is moving right now. */
    fun inFlight(): Set<String> = synchronized(lock) { inFlight.toSet() }

    /** Shut the gate for a sign-out and answer every file a hide had already been admitted for. */
    fun beginSignOut(): Set<String> = synchronized(lock) {
        signOuts++
        inFlight.toSet()
    }

    /** One sign-out has ended; hides are admitted again once none is running. */
    fun endSignOut() {
        synchronized(lock) { if (signOuts > 0) signOuts-- }
    }

    /** Whether a sign-out is running, so a hide can stop copying for nothing. */
    fun signingOut(): Boolean = synchronized(lock) { signOuts > 0 }
}
