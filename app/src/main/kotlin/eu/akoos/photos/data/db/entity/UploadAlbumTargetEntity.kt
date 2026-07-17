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
 * Cloud albums a not-yet-backed-up local photo should join once it uploads. One row per
 * (localUri, albumLinkId), so a single photo can be queued into several albums. Replaces the
 * album half of the [eu.akoos.photos.data.preferences.SettingsKeys.PENDING_ALBUM_ADDS] DataStore
 * side-queue: the row's upload intent lives on sync_state.queued now, and the target album(s) live
 * here. The composite primary key makes re-adding the same photo to the same album a no-op.
 */
@Entity(tableName = "upload_album_target", primaryKeys = ["localUri", "albumLinkId"])
data class UploadAlbumTargetEntity(
    val localUri: String,
    val albumLinkId: String,
)
