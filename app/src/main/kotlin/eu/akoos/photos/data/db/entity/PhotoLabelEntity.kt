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
import androidx.room.Index

/**
 * One free-text label on one photo, kept on the device only. [photoKey] is the gallery identity
 * (GalleryItem.stableId: a device content URI, or the Drive linkId once the photo is backed up), so a
 * label joins straight back to the timeline the same way faces and embeddings do. [userId] is the
 * signed-in account, or [PhotoLocationEntity.LOCAL_USER] for a guest.
 *
 * [label] is the spelling shown to the user and [labelKey] its folded form
 * ([eu.akoos.photos.util.LabelNormalizer.fold]), the identity two spellings share. The (userId,
 * labelKey) index serves the account-wide rename, delete and usage counts. [updatedAt] is the
 * wall-clock time the row was written, and with the rowid gives a photo's labels their display order.
 */
@Entity(
    tableName = "photo_label",
    primaryKeys = ["userId", "photoKey", "labelKey"],
    indices = [Index(value = ["userId", "labelKey"])],
)
data class PhotoLabelEntity(
    val userId: String,
    val photoKey: String,
    val label: String,
    val labelKey: String,
    val updatedAt: Long,
)
