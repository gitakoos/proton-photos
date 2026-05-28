package eu.akoos.photos.data.db.dao

import androidx.room.Database
import androidx.room.RoomDatabase
import eu.akoos.photos.data.db.entity.PhotoListingEntity
import eu.akoos.photos.data.db.entity.SyncStateEntity

@Database(
    entities = [PhotoListingEntity::class, SyncStateEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class TestDatabase : RoomDatabase() {
    abstract fun photoListingDao(): PhotoListingDao
    abstract fun syncStateDao(): SyncStateDao
}
