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

package eu.akoos.photos.domain.repository

/**
 * What a sign-out does to the hidden vault.
 *
 * A vault file is the only copy of a photo that never reached Drive, so how much of the vault goes
 * depends on how the session ended, which only the caller knows.
 */
sealed interface VaultWipe {

    /** The vault is left as it is: a sign-in that gave up before it made a session owns nothing in it,
     *  and a rescue that could not run cannot say which files are safe to destroy. */
    data object None : VaultWipe

    /**
     * Exactly the vault files [uris] names go, and no other. The caller names only files it has dealt
     * with: taken back to the device, saved in a zip, confirmed in Drive, or named in the sign-out confirmation. A
     * file that appeared after the caller looked, or one a hide was still moving, is never among them.
     */
    data class Only(val uris: Set<String>) : VaultWipe
}
