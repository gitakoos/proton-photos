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

import eu.akoos.photos.data.db.entity.PersonEntity

/**
 * How many people the AI panel reports found, matching the named face tiles shown beside the figure:
 * the named clusters only. The single "Unsorted" bucket is not a person, so it is excluded even though
 * it carries faces, and an unnamed cluster is left out since it has no tile of its own.
 */
internal fun namedPeopleCount(people: List<PersonEntity>): Int =
    people.count { !it.displayName.isNullOrBlank() && !it.isOther }
