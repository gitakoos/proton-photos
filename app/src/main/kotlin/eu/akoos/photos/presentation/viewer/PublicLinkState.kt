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

package eu.akoos.photos.presentation.viewer

/** Per-photo public-link state for [ManagePublicLinkSheet], reset to [None] on every page/selection
 *  change so a link from the previous photo can't bleed onto the next. */
sealed class PublicLinkState {
    /** No link exists (or the photo isn't backed up). The sheet offers "Create link". */
    data object None : PublicLinkState()
    /** A create/lookup round-trip is in flight. */
    data object Loading : PublicLinkState()
    /** A live link; [hasPassword] true when it requires a typed password (bare URL, no
     *  `#fragment`), false for the default anyone-with-the-link share. */
    data class Active(val url: String, val hasPassword: Boolean = false) : PublicLinkState()
    /** The last operation failed; [message] is already localized for display. */
    data class Error(val message: String) : PublicLinkState()
}
