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

package eu.akoos.photos.domain.model

/**
 * One clustered person reduced to what a list or rail needs: a cover reference and a face count,
 * never an embedding, so any screen can render people without touching the recognition data. Built by
 * [eu.akoos.photos.domain.usecase.ObservePeopleUseCase] and shared across the people surfaces.
 *
 * [coverPhotoKey] is the cover face's photo (the gallery feed's stableId keyspace), resolved to a
 * thumbnail the same way a timeline cell is; it is null when the person has no resolvable cover.
 * [faceBox] is the cover face region as fractions (0..1) of the cover image so a tile can crop to the
 * face at any resolution, and is null when the cover photo's dimensions are unknown to the caller.
 * [faceCount] is the cached number of faces assigned to the person, the list's ordering key.
 */
data class PersonSummary(
    val personId: Long,
    val displayName: String?,
    val coverPhotoKey: String?,
    val faceBox: FaceBoxNorm?,
    val faceCount: Int,
)

/** A face region as fractions (0..1) of its image, left/top/right/bottom. */
data class FaceBoxNorm(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
)
