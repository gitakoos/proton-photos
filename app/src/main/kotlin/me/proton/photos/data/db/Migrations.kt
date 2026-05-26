package me.proton.photos.data.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Schema migrations for [AppDatabase]. Each step matches one version bump in
 * `@Database(version = N)` and corresponds to a single SQL change against our
 * own tables (Proton Core tables migrate themselves through ProtonCore's
 * bundled migrators).
 *
 * Why this exists at all: the v0.x app shipped with `fallbackToDestructiveMigration`,
 * which wipes the local SyncState on every schema bump. Once the app is in users'
 * hands that wipe would lose upload progress (contentHash-based matching, recent-
 * upload IDs, …) — so before the first public release we need explicit migrations.
 */
object Migrations {

    /**
     * v2 → v3: `photo_listing.contentHash TEXT` (nullable).
     * Stores the SHA-256 of the plaintext file; null on legacy rows means
     * "matched by name+size, not by hash".
     */
    val MIGRATION_2_3 = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE photo_listing ADD COLUMN contentHash TEXT DEFAULT NULL")
        }
    }

    /**
     * v3 → v4: `photo_listing.tagsCsv TEXT NOT NULL DEFAULT ''`.
     * Holds the comma-separated PhotoTag ids (Drive enum 0=Favorite, 1=Screenshot, …).
     */
    val MIGRATION_3_4 = object : Migration(3, 4) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE photo_listing ADD COLUMN tagsCsv TEXT NOT NULL DEFAULT ''")
        }
    }

    /** All migrations in version-ascending order — pass to [androidx.room.RoomDatabase.Builder.addMigrations]. */
    val ALL: Array<Migration> = arrayOf(MIGRATION_2_3, MIGRATION_3_4)
}
