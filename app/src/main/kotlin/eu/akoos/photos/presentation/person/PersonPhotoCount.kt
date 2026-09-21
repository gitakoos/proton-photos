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

package eu.akoos.photos.presentation.person

/**
 * A person's photo tally: the photos their faces appear in unioned with any manually attached photos,
 * counted once each. The two key lists overlap when a manual add lands on a photo a face already
 * covers, so the union is de-duplicated. Shared by the detail screen's curation paths and the
 * find-more sweep, so every path that refreshes the cached count reports the same figure.
 */
internal fun personPhotoCount(faceKeys: List<String>, manualKeys: List<String>): Int =
    (faceKeys + manualKeys).toHashSet().size
