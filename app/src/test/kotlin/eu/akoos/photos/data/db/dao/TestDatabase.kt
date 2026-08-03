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

package eu.akoos.photos.data.db.dao

import androidx.room.Database
import androidx.room.RoomDatabase
import eu.akoos.photos.data.db.entity.AlbumPhotoMembershipEntity
import eu.akoos.photos.data.db.entity.CloudAlbumEntity
import eu.akoos.photos.data.db.entity.ListingSweepSnapshotEntity
import eu.akoos.photos.data.db.entity.LocalTagEntity
import eu.akoos.photos.data.db.entity.PhotoListingEntity
import eu.akoos.photos.data.db.entity.PhotoLocationEntity
import eu.akoos.photos.data.db.entity.SyncStateEntity

@Database(
    entities = [
        PhotoListingEntity::class,
        SyncStateEntity::class,
        AlbumPhotoMembershipEntity::class,
        CloudAlbumEntity::class,
        ListingSweepSnapshotEntity::class,
        LocalTagEntity::class,
        PhotoLocationEntity::class,
    ],
    version = 1,
    exportSchema = false,
)
abstract class TestDatabase : RoomDatabase() {
    abstract fun photoListingDao(): PhotoListingDao
    abstract fun syncStateDao(): SyncStateDao
    abstract fun albumPhotoMembershipDao(): AlbumPhotoMembershipDao
    abstract fun cloudAlbumDao(): CloudAlbumDao
    abstract fun listingSweepSnapshotDao(): ListingSweepSnapshotDao
    abstract fun localTagDao(): LocalTagDao
    abstract fun photoLocationDao(): PhotoLocationDao
}
