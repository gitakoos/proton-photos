package me.proton.photos.data.db.dao

import androidx.room.Database
import androidx.room.RoomDatabase
import me.proton.photos.data.db.entity.PhotoListingEntity
import me.proton.photos.data.db.entity.SyncStateEntity

@Database(
    entities = [PhotoListingEntity::class, SyncStateEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class TestDatabase : RoomDatabase() {
    abstract fun photoListingDao(): PhotoListingDao
    abstract fun syncStateDao(): SyncStateDao
}
