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

package eu.akoos.photos.domain.entity

/**
 * A device MediaStore bucket (DCIM/Camera, Screenshots, …) grouped for selection. Used by the
 * photo-widget config screen so the user can pick a source folder for the widget to cycle
 * through. The cover and count drive the picker row; [items] carries the bucket contents.
 */
data class LocalAlbum(
    val name: String,
    val coverUri: String?,
    val itemCount: Int,
    val items: List<LocalMediaItem>,
)
