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

    /** v13 → v14: new `perceptual_hash` table — one cached dHash per photo, the near-duplicate
     *  finder's source so it need not re-decode the whole library on every open. */
    val MIGRATION_13_14 = object : Migration(13, 14) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("CREATE TABLE IF NOT EXISTS `perceptual_hash` (`key` TEXT NOT NULL, `hash` INTEGER NOT NULL, `isCloud` INTEGER NOT NULL, `freshness` TEXT NOT NULL, `algoVersion` INTEGER NOT NULL, `computedAt` INTEGER NOT NULL, PRIMARY KEY(`key`))")
        }
    }

    /**
     * v14 → v15: explicit upload-intent on sync_state plus a new `upload_album_target` table.
     * These back the queue redesign, and the upload selector now reads `queued` to pick what to
     * back up. The conservative backfill marks every existing LOCAL_ONLY row queued with
     * AUTO_FOLDER so an upgraded install keeps the same pending set the old derived selector had;
     * queuedAt stays NULL for backfilled rows (a deterministic value, no clock is called in SQL).
     */
    val MIGRATION_14_15 = object : Migration(14, 15) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE sync_state ADD COLUMN queued INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE sync_state ADD COLUMN queueSource TEXT DEFAULT NULL")
            db.execSQL("ALTER TABLE sync_state ADD COLUMN queuedAt INTEGER DEFAULT NULL")
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `upload_album_target` (" +
                    "`localUri` TEXT NOT NULL, `albumLinkId` TEXT NOT NULL, " +
                    "PRIMARY KEY(`localUri`, `albumLinkId`))"
            )
            db.execSQL("UPDATE sync_state SET queued = 1, queueSource = 'AUTO_FOLDER' WHERE status = 'LOCAL_ONLY'")
        }
    }

    /** v15 → v16: photo_listing gains a nullable video duration (milliseconds), recovered from the
     *  xAttr Media.Duration block. Additive + nullable: existing rows stay NULL and are filled in by
     *  the bounded duration backfill, so a v15 DB opens at v16 with no data loss. */
    val MIGRATION_15_16 = object : Migration(15, 16) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE photo_listing ADD COLUMN durationMs INTEGER DEFAULT NULL")
        }
    }

    /**
     * Carries this user's own permission bitmask on a shared-with-me album (4 = viewer,
     * 6 = viewer + editor), so the app can tell whether it may offer to add photos.
     *
     * Nullable with no default: an existing row genuinely has no answer yet, and null is read as
     * "not an editor" everywhere, so old rows stay read-only until the next refresh fills them in.
     */
    val MIGRATION_16_17 = object : Migration(16, 17) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE cloud_albums ADD COLUMN permissions INTEGER DEFAULT NULL")
        }
    }

    /**
     * v17 → v18: photo_listing gains the per-row "lives only inside an album" fact, which tells a
     * photo contributed to a shared album apart from one the user backed up themselves. Both sit in
     * the same table under the same userId on the same volume, and only the parent distinguishes them.
     *
     * The backfill is what makes an upgraded install correct: every row already parented to a cached
     * album is an album child, and without marking them they would keep surfacing on the timeline
     * whenever their album's membership edges are absent, and keep being swept away as stale.
     */
    val MIGRATION_17_18 = object : Migration(17, 18) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE photo_listing ADD COLUMN isChildOfAlbum INTEGER NOT NULL DEFAULT 0")
            db.execSQL(
                "UPDATE photo_listing SET isChildOfAlbum = 1 " +
                    "WHERE parentLinkId IN (SELECT linkId FROM cloud_albums)"
            )
        }
    }

    /**
     * v18 → v19: photo_listing records a digest of the encrypted name each row's displayName came
     * from, so a refresh can tell a photo renamed elsewhere from one that never changed without
     * decrypting every name it walks past.
     *
     * No backfill: the digest belongs to ciphertext this migration cannot see. Null is deliberately
     * the state every existing row lands in, because null means "recheck", and that one pass is what
     * repairs the names that already drifted.
     */
    val MIGRATION_18_19 = object : Migration(18, 19) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE photo_listing ADD COLUMN nameFingerprint TEXT")
        }
    }

    /**
     * v19 → v20: new `listing_sweep_snapshot` table, which holds one refresh pass's sweep candidates
     * from before its listing walk starts until pagination reaches the end.
     *
     * No backfill, and the empty table is the correct state to arrive at. A row's whole value is
     * that it was read at a known moment relative to a walk, and this migration has no walk in
     * flight to speak for; the first pass that starts fresh materialises the generation it consumes.
     */
    val MIGRATION_19_20 = object : Migration(19, 20) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `listing_sweep_snapshot` (" +
                    "`userId` TEXT NOT NULL, `volumeId` TEXT NOT NULL, `linkId` TEXT NOT NULL, " +
                    "PRIMARY KEY(`userId`, `volumeId`, `linkId`))"
            )
        }
    }

    /**
     * v20 → v21: local_tag gains the categories a user picked for a device photo, held apart from
     * the scanner's own `tagsCsv` so a re-detection cannot overwrite them.
     *
     * Empty on every existing row is the correct state, and no backfill could improve on it: the
     * column records an answer only a person gives, while the column beside it holds what a detector
     * guessed, so copying one into the other would dress a guess up as a decision.
     */
    val MIGRATION_20_21 = object : Migration(20, 21) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE local_tag ADD COLUMN userTagsCsv TEXT NOT NULL DEFAULT ''")
        }
    }

    /**
     * v21 → v22: two new tables backing People; `face` holds one detected face per row, keyed by a
     * stable id the indexer derives from the photo, and `person` holds the clusters those faces group
     * into. Both are additive and rebuildable: the empty tables are the correct state to arrive at,
     * since a face and its embedding come from re-reading images this migration cannot see, and no
     * backfill could conjure them. Existing tables are untouched.
     */
    val MIGRATION_21_22 = object : Migration(21, 22) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `face` (`id` TEXT NOT NULL, `userId` TEXT NOT NULL, " +
                    "`photoKey` TEXT NOT NULL, `left` REAL NOT NULL, `top` REAL NOT NULL, " +
                    "`right` REAL NOT NULL, `bottom` REAL NOT NULL, `landmarks` TEXT NOT NULL, " +
                    "`embedding` BLOB NOT NULL, `personId` INTEGER, `score` REAL NOT NULL, " +
                    "PRIMARY KEY(`id`))"
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_face_photoKey` ON `face` (`photoKey`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_face_personId` ON `face` (`personId`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_face_userId` ON `face` (`userId`)")
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `person` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`userId` TEXT NOT NULL, `displayName` TEXT, `coverFaceId` TEXT, " +
                    "`faceCount` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL)"
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_person_userId` ON `person` (`userId`)")
        }
    }

    /** v23: a per-face Laplacian sharpness score, so a blurred crop can be held to a stricter cluster
     *  distance. Nullable, so rows indexed before it stay valid until the next re-index fills them. */
    val MIGRATION_22_23 = object : Migration(22, 23) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `face` ADD COLUMN `blur` REAL")
        }
    }

    /**
     * v23 → v24: new `face_scan` table, one marker per photo the face indexer has fully scanned, so a
     * re-run skips a photo it already looked at whether or not it held a face, instead of re-decoding
     * every faceless photo on each pass. Additive and rebuildable: the empty table is the correct state
     * to arrive at, since the marker records a scan this migration cannot redo, and the first pass after
     * the upgrade re-derives it. Existing tables are untouched.
     */
    val MIGRATION_23_24 = object : Migration(23, 24) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `face_scan` (`userId` TEXT NOT NULL, " +
                    "`photoKey` TEXT NOT NULL, PRIMARY KEY(`userId`, `photoKey`))"
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_face_scan_userId` ON `face_scan` (`userId`)")
            // Seed the marker from photos already carrying a face, so an existing library is not
            // re-scanned (and, for a cloud photo, re-downloaded) just to record what it already knows.
            // Faceless photos were never persisted, so they get scanned once and marked from then on.
            db.execSQL(
                "INSERT OR IGNORE INTO `face_scan` (`userId`, `photoKey`) " +
                    "SELECT DISTINCT `userId`, `photoKey` FROM `face`"
            )
        }
    }

    /** People curation: a per-face "removed by the user" flag so a manual removal survives a rescan,
     *  and a table of photos the user manually attached to a named person (keyed by name so the
     *  membership follows the person across a clustering rebuild). Both are additive. */
    val MIGRATION_24_25 = object : Migration(24, 25) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `face` ADD COLUMN `rejected` INTEGER NOT NULL DEFAULT 0")
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `person_manual_photo` (`userId` TEXT NOT NULL, " +
                    "`personName` TEXT NOT NULL, `photoKey` TEXT NOT NULL, " +
                    "PRIMARY KEY(`userId`, `personName`, `photoKey`))"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_person_manual_photo_userId_personName` " +
                    "ON `person_manual_photo` (`userId`, `personName`)"
            )
        }
    }

    /** Teaching: a per-face confirmed person name. A face the user confirms (by adding its photo to a
     *  person) anchors that person and pulls matching faces in on the next clustering pass. Additive
     *  and nullable, so existing faces stay unconfirmed. */
    val MIGRATION_25_26 = object : Migration(25, 26) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `face` ADD COLUMN `manualName` TEXT")
        }
    }

    /** People suggestions: a "not this person" feedback table, so a rejected match ("Is this X? No")
     *  is never re-offered nor pulled into that person again. Keyed by name so it survives a rebuild. */
    val MIGRATION_26_27 = object : Migration(26, 27) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `not_person` (`userId` TEXT NOT NULL, " +
                    "`personName` TEXT NOT NULL, `faceId` TEXT NOT NULL, " +
                    "PRIMARY KEY(`userId`, `personName`, `faceId`))"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_not_person_userId_personName` " +
                    "ON `not_person` (`userId`, `personName`)"
            )
        }
    }

    /** Un-poison people: a manual "add to person" used to auto-label the photo's single detected face,
     *  which mislabels a bystander when the person's own face was too small to detect. Drop every label
     *  that sits on a manually attached photo, so the next rebuild reclusters those faces by likeness
     *  alone. The manual attachments themselves (the display membership) are untouched. */
    val MIGRATION_27_28 = object : Migration(27, 28) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "UPDATE `face` SET `manualName` = NULL WHERE EXISTS (" +
                    "SELECT 1 FROM `person_manual_photo` p " +
                    "WHERE p.`photoKey` = `face`.`photoKey` AND p.`userId` = `face`.`userId`)"
            )
        }
    }

    /** Custom person covers: the photo a user picked as a named person's cover, overriding the
     *  automatic clearest-face pick. Keyed by name so the choice follows the person across a rebuild.
     *  Additive. */
    val MIGRATION_28_29 = object : Migration(28, 29) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `person_cover` (`userId` TEXT NOT NULL, " +
                    "`personName` TEXT NOT NULL, `photoKey` TEXT NOT NULL, " +
                    "PRIMARY KEY(`userId`, `personName`))"
            )
        }
    }

    /** v29 to v30: new `pending_metadata_edit` table, one persisted row per queued cloud/synced
     *  metadata edit, so the durable drain survives a process kill. Additive, and the empty table is
     *  the correct state to arrive at: a pending edit exists only once the editor enqueues one, and
     *  nothing this migration can see stands in for an edit a person has not yet made. */
    val MIGRATION_29_30 = object : Migration(29, 30) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `pending_metadata_edit` (" +
                    "`linkId` TEXT NOT NULL, `userId` TEXT NOT NULL, `deviceUri` TEXT, " +
                    "`newCaptureMs` INTEGER, `locationMode` TEXT NOT NULL, `lat` REAL, `lng` REAL, " +
                    "`description` TEXT, `artist` TEXT, `copyright` TEXT, `enqueuedAt` INTEGER NOT NULL, " +
                    "`newLinkId` TEXT, PRIMARY KEY(`linkId`))"
            )
        }
    }

    /** v30 to v31: bring `pending_metadata_edit` to its final shape (the resume-state `newLinkId`
     *  column). An unreleased v30 created the table without that column on some test builds, so this
     *  drops and recreates it: the table is a transient edit queue, so an empty table is the correct
     *  state to arrive at, and a fresh v29 to v31 path never had a pending edit to preserve. */
    val MIGRATION_30_31 = object : Migration(30, 31) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("DROP TABLE IF EXISTS `pending_metadata_edit`")
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `pending_metadata_edit` (" +
                    "`linkId` TEXT NOT NULL, `userId` TEXT NOT NULL, `deviceUri` TEXT, " +
                    "`newCaptureMs` INTEGER, `locationMode` TEXT NOT NULL, `lat` REAL, `lng` REAL, " +
                    "`description` TEXT, `artist` TEXT, `copyright` TEXT, `enqueuedAt` INTEGER NOT NULL, " +
                    "`newLinkId` TEXT, PRIMARY KEY(`linkId`))"
            )
        }
    }

    val ALL: Array<Migration> = arrayOf(MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13, MIGRATION_13_14, MIGRATION_14_15, MIGRATION_15_16, MIGRATION_16_17, MIGRATION_17_18, MIGRATION_18_19, MIGRATION_19_20, MIGRATION_20_21, MIGRATION_21_22, MIGRATION_22_23, MIGRATION_23_24, MIGRATION_24_25, MIGRATION_25_26, MIGRATION_26_27, MIGRATION_27_28, MIGRATION_28_29, MIGRATION_29_30, MIGRATION_30_31)
}
