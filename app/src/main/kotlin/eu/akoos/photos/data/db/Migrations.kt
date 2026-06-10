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

package eu.akoos.photos.data.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Schema migrations for [AppDatabase]. Each step matches one version bump in
 * `@Database(version = N)` and corresponds to a single SQL change against our
 * own tables (Proton Core tables migrate themselves through ProtonCore's
 * bundled migrators).
 *
 * Each migration is hand-written so SyncState rows (contentHash, recent upload IDs)
 * survive schema bumps.
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

    /**
     * v5 → v6: new `day_meta` table for the Calendar view's per-day notes
     * (location text, free-form description, user-picked cover photo). Keyed on an
     * ISO-8601 date string so callers can do `getByDate("2026-05-28")` directly.
     * All optional columns default to NULL — no data is generated for legacy installs;
     * the row only appears the first time the user edits a day in the Calendar.
     */
    val MIGRATION_5_6 = object : Migration(5, 6) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS day_meta (
                    date TEXT NOT NULL PRIMARY KEY,
                    userId TEXT NOT NULL,
                    coverPhotoUri TEXT DEFAULT NULL,
                    locationText TEXT DEFAULT NULL,
                    description TEXT DEFAULT NULL,
                    updatedAt INTEGER NOT NULL DEFAULT 0
                )
                """.trimIndent()
            )
        }
    }

    /**
     * v6 → v7: new `cloud_albums` table — persisted snapshot of the cloud album list so
     * AlbumsScreen can paint instantly from disk on cold launch (and survive airplane-mode
     * starts). Sharing-related scalars are stored too so the badges render without a
     * network round-trip; `coverThumbnailUrl` is deliberately NOT persisted — that's a
     * decrypted CDN URL whose signature expires.
     */
    val MIGRATION_6_7 = object : Migration(6, 7) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `cloud_albums` (
                    `linkId` TEXT NOT NULL,
                    `name` TEXT NOT NULL,
                    `photoCount` INTEGER NOT NULL,
                    `coverLinkId` TEXT,
                    `lastActivityTimeMs` INTEGER,
                    `sharingShareId` TEXT,
                    `sharingShareUrlId` TEXT,
                    `sharedByEmail` TEXT,
                    `volumeId` TEXT,
                    `lastFetchedMs` INTEGER NOT NULL DEFAULT 0,
                    PRIMARY KEY(`linkId`)
                )
                """.trimIndent()
            )
        }
    }

    /**
     * v7 → v8: new `album_photo_membership` table. Many-to-many edge between cloud
     * albums and the photos they reference, so opening an album offline can enumerate
     * its photos via a JOIN against `photo_listing` — previously impossible because
     * `photo_listing.parentLinkId` points at the photos root, not the album.
     */
    val MIGRATION_7_8 = object : Migration(7, 8) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `album_photo_membership` (
                    `albumLinkId` TEXT NOT NULL,
                    `photoLinkId` TEXT NOT NULL,
                    PRIMARY KEY(`albumLinkId`, `photoLinkId`)
                )
                """.trimIndent()
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_album_photo_membership_photoLinkId` " +
                    "ON `album_photo_membership` (`photoLinkId`)"
            )
        }
    }

    /** All migrations in version-ascending order — pass to [androidx.room.RoomDatabase.Builder.addMigrations]. */
    val ALL: Array<Migration> = arrayOf(MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8)
}
