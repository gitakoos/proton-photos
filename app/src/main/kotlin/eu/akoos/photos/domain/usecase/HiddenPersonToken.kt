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

import java.util.UUID

/**
 * The reserved name a never-named group is hidden under. Hiding labels the group's faces with it, so the
 * ordinary name machinery (a confirmed name anchors a group through every regrouping) keeps the group together
 * and hidden without a second mechanism. It starts with a private-use character no name list offers and carries
 * a random id, so two hidden groups never share one; it holds no letters a case fold or a trim could change.
 * Nothing may show it: [visibleName] turns it into "no name".
 */
object HiddenPersonToken {

    /** What every token starts with. */
    const val PREFIX = "hidden"

    /** A fresh token for a group about to be hidden. */
    fun make(): String = PREFIX + UUID.randomUUID().toString().replace("-", "")

    /** Whether [name] is one of the reserved names rather than a name a person was given. */
    fun isToken(name: String?): Boolean = name != null && name.startsWith(PREFIX)

    /** [name] as a surface may show it: null for blank or a token, else the name. */
    fun visibleName(name: String?): String? = name?.takeIf { it.isNotBlank() && !isToken(it) }
}
