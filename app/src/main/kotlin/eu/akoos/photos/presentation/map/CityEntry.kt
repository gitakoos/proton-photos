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

package eu.akoos.photos.presentation.map

/**
 * One place the account has located photos in, backing the map's city-search overlay. [name] is the
 * "City, Country" label the offline geocoder resolves the place's fixes to; [latitude]/[longitude]
 * are a representative coordinate (the mean of that place's fixes) the map animates to when the row
 * is tapped; [count] is how many located photos fall in the place, used to rank and label the row.
 */
data class CityEntry(
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val count: Int,
)
