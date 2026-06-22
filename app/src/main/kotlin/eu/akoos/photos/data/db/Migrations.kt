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
 * Schema migrations for our own tables only — ProtonCore tables self-migrate via their
 * bundled migrators. Hand-written so existing rows survive each bump.
 */
object Migrations {

    /** v2 → v3: photo_listing.contentHash (nullable SHA-256; null = matched by name+size). */
    val MIGRATION_2_3 = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE photo_listing ADD COLUMN contentHash TEXT DEFAULT NULL")
        }
    }

    /** v3 → v4: photo_listing.tagsCsv (comma-separated PhotoTag ids). */
    val MIGRATION_3_4 = object : Migration(3, 4) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE photo_listing ADD COLUMN tagsCsv TEXT NOT NULL DEFAULT ''")
        }
    }

    /**
     * v4 → v5: six nullable columns holding the encrypted inputs for on-demand thumbnail decrypt.
     * Legacy rows stay null here and keep their decrypted thumbnailUrl, so they render unchanged
     * (the on-demand path only fires when thumbnailUrl IS NULL).
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

    /** v5 → v6: new `day_meta` table for the Calendar view's per-day notes, keyed on ISO-8601 date. */
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
     * v6 → v7: new `cloud_albums` table — cached album list for instant cold-launch paint.
     * `coverThumbnailUrl` is deliberately NOT persisted (decrypted CDN URL whose signature expires).
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
     * v7 → v8: new `album_photo_membership` join table. Needed because photo_listing.parentLinkId
     * points at the photos root, not the album, so offline album enumeration has no other source.
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

    /**
     * v8 → v9: new `local_tag` table — per-URI category-tag cache so scans skip re-reading XMP.
     * A row is fresh only while dateModified + sizeBytes still match MediaStore; rebuildable.
     */
    val MIGRATION_8_9 = object : Migration(8, 9) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS local_tag (
                    uri TEXT NOT NULL PRIMARY KEY,
                    dateModified INTEGER NOT NULL,
                    sizeBytes INTEGER NOT NULL,
                    tagsCsv TEXT NOT NULL,
                    scannedAt INTEGER NOT NULL
                )
                """.trimIndent()
            )
        }
    }

    /** v9 → v10: indices for the hottest queries. Names must match Room's convention or schema validation fails. */
    val MIGRATION_9_10 = object : Migration(9, 10) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_photo_listing_userId_captureTime` " +
                    "ON `photo_listing` (`userId`, `captureTime`)"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_photo_listing_parentLinkId` " +
                    "ON `photo_listing` (`parentLinkId`)"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_album_photo_membership_albumLinkId` " +
                    "ON `album_photo_membership` (`albumLinkId`)"
            )
        }
    }

    /** v10 → v11: indices on sync_state (userId, status) — every sync write queries by userId. */
    val MIGRATION_10_11 = object : Migration(10, 11) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_sync_state_userId` " +
                    "ON `sync_state` (`userId`)"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_sync_state_status` " +
                    "ON `sync_state` (`status`)"
            )
        }
    }

    /** v11 → v12: new `photo_location` table — one GPS fix per photo, the map view's source. */
    val MIGRATION_11_12 = object : Migration(11, 12) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("CREATE TABLE IF NOT EXISTS `photo_location` (`id` TEXT NOT NULL, `userId` TEXT NOT NULL, `latitude` REAL NOT NULL, `longitude` REAL NOT NULL, PRIMARY KEY(`id`))")
        }
    }

    /** v12 → v13: photo_listing gains the encrypted XAttr blob + a GPS-backfill-done flag, so cloud
     *  photos' GPS can be recovered locally without a second API call. */
    val MIGRATION_12_13 = object : Migration(12, 13) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE photo_listing ADD COLUMN encXAttr TEXT")
            db.execSQL("ALTER TABLE photo_listing ADD COLUMN gpsChecked INTEGER NOT NULL DEFAULT 0")
        }
    }

    val ALL: Array<Migration> = arrayOf(MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13)
}
