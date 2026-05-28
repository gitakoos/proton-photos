package eu.akoos.photos.data.db

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

    /**
     * v4 → v5: lazy-thumbnail-decrypt material.
     *
     * Adds six nullable columns that hold the encrypted inputs needed to decrypt a
     * photo's thumbnail on-demand (when its grid cell scrolls into view) instead of
     * up-front during the sync pass.
     *
     * Pre-existing rows keep null in these columns AND keep their already-decrypted
     * `thumbnailUrl` — the on-demand path is only invoked when `thumbnailUrl IS NULL`,
     * so legacy rows render exactly as before. New sync passes write the encrypted
     * material here and leave `thumbnailUrl` null; the cell-driven scheduler fills it
     * in later.
     */
    val MIGRATION_4_5 = object : Migration(4, 5) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE photo_listing ADD COLUMN serverThumbnailUrl TEXT DEFAULT NULL")
            db.execSQL("ALTER TABLE photo_listing ADD COLUMN serverThumbnailToken TEXT DEFAULT NULL")
            db.execSQL("ALTER TABLE photo_listing ADD COLUMN contentKeyPacket TEXT DEFAULT NULL")
            db.execSQL("ALTER TABLE photo_listing ADD COLUMN encNodeKey TEXT DEFAULT NULL")
            db.execSQL("ALTER TABLE photo_listing ADD COLUMN encNodePassphrase TEXT DEFAULT NULL")
            db.execSQL("ALTER TABLE photo_listing ADD COLUMN parentLinkId TEXT DEFAULT NULL")
        }
    }

    /** All migrations in version-ascending order — pass to [androidx.room.RoomDatabase.Builder.addMigrations]. */
    val ALL: Array<Migration> = arrayOf(MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
}
