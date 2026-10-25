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

package eu.akoos.photos.data.db.entity

import androidx.room.Entity

/**
 * When the user last changed one piece of a named person's curation by hand, and whether that change took it away:
 * a photo attached to the person ([KIND_MANUAL_PHOTO], [ref] its photo key), a face marked as not being them
 * ([KIND_NOT_PERSON], [ref] the face id), or their cover ([KIND_COVER], [ref] empty). Keyed by the person's NAME like
 * the curation itself. The shared face copy carries these, so the later change wins on every install, a removal
 * included; a removal is kept as a row with [removed] set, as nothing else would remember it.
 */
@Entity(
    tableName = "face_curation_time",
    primaryKeys = ["userId", "kind", "personName", "ref"],
)
data class FaceCurationTimeEntity(
    val userId: String,
    val kind: Int,
    val personName: String,
    val ref: String,
    val removed: Boolean,
    val changedAt: Long,
) {
    companion object {
        /** The kinds, the same numbers the face copy's removal records carry. */
        const val KIND_MANUAL_PHOTO = 1
        const val KIND_NOT_PERSON = 2
        const val KIND_COVER = 3
    }
}
