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

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Exercises [Migrations] against a hand-built v2 `photo_listing` table.
 *
 * We deliberately don't use Room's `MigrationTestHelper` here because the v2/v3 schema JSONs
 * predate the `exportSchema=true` flip — the helper validates against schema JSONs that don't
 * exist in the repo yet. The migrate() lambdas are pure SQL though, so a low-level
 * SupportSQLite test is enough to assert "column appears, existing rows survive, default
 * value applied where expected".
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppDatabaseMigrationTest {

    private lateinit var helper: SupportSQLiteOpenHelper
    private lateinit var db: SupportSQLiteDatabase

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val config = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(null) // in-memory
            .callback(object : SupportSQLiteOpenHelper.Callback(2) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    // Hand-crafted v2 schema for photo_listing — matches the Room-generated
                    // CREATE TABLE statement from the v2 era (before contentHash / tagsCsv).
                    db.execSQL(
                        """
                        CREATE TABLE photo_listing (
                          linkId TEXT NOT NULL PRIMARY KEY,
                          shareId TEXT NOT NULL,
                          volumeId TEXT NOT NULL,
                          userId TEXT NOT NULL,
                          captureTime INTEGER NOT NULL,
                          displayName TEXT NOT NULL,
                          mimeType TEXT NOT NULL,
                          sizeBytes INTEGER NOT NULL,
                          revisionId TEXT NOT NULL,
                          thumbnailUrl TEXT
                        )
                        """.trimIndent()
                    )
                }
                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {}
            })
            .build()
        helper = FrameworkSQLiteOpenHelperFactory().create(config)
        db = helper.writableDatabase
    }

    @After
    fun tearDown() {
        db.close()
        helper.close()
    }

    @Test
    fun migrate_v2_to_v3_addsContentHashColumn_preservesRow() {
        db.execSQL(
            """
            INSERT INTO photo_listing (linkId, shareId, volumeId, userId, captureTime,
                displayName, mimeType, sizeBytes, revisionId, thumbnailUrl)
            VALUES ('l1','s1','v1','u1',1000,'a.jpg','image/jpeg',1024,'r1','thumb://a')
            """.trimIndent()
        )

        Migrations.MIGRATION_2_3.migrate(db)

        db.query("SELECT linkId, displayName, contentHash FROM photo_listing WHERE linkId = 'l1'")
            .use { cur ->
                assertTrue("expected the seeded row to survive the migration", cur.moveToFirst())
                assertEquals("l1", cur.getString(0))
                assertEquals("a.jpg", cur.getString(1))
                assertTrue("contentHash defaults to NULL on legacy rows", cur.isNull(2))
            }
    }

    @Test
    fun migrate_v3_to_v4_addsTagsCsvColumn_withEmptyDefault_preservesRow() {
        // Bring the table to a v3-ish state first.
        db.execSQL("ALTER TABLE photo_listing ADD COLUMN contentHash TEXT")
        db.execSQL(
            """
            INSERT INTO photo_listing (linkId, shareId, volumeId, userId, captureTime,
                displayName, mimeType, sizeBytes, revisionId, thumbnailUrl, contentHash)
            VALUES ('l2','s1','v1','u1',2000,'b.jpg','image/jpeg',2048,'r2','thumb://b','deadbeef')
            """.trimIndent()
        )

        Migrations.MIGRATION_3_4.migrate(db)

        db.query("SELECT contentHash, tagsCsv FROM photo_listing WHERE linkId = 'l2'")
            .use { cur ->
                assertTrue(cur.moveToFirst())
                assertEquals("deadbeef", cur.getString(0))
                assertEquals("tagsCsv must default to empty string", "", cur.getString(1))
            }
    }

    @Test
    fun migrate_v13_to_v14_createsPerceptualHashTable_acceptsRow() {
        Migrations.MIGRATION_13_14.migrate(db)

        // The table exists and accepts a row shaped exactly like PerceptualHashEntity.
        db.execSQL(
            """
            INSERT INTO perceptual_hash (`key`, hash, isCloud, freshness, algoVersion, computedAt)
            VALUES ('link-1', 1234567890, 1, 'link-1', 1, 5000)
            """.trimIndent()
        )

        db.query(
            "SELECT `key`, hash, isCloud, freshness, algoVersion, computedAt FROM perceptual_hash WHERE `key` = 'link-1'"
        ).use { cur ->
            assertTrue("expected the inserted fingerprint row to be readable", cur.moveToFirst())
            assertEquals("link-1", cur.getString(0))
            assertEquals(1234567890L, cur.getLong(1))
            assertEquals("isCloud stored as INTEGER 1", 1, cur.getInt(2))
            assertEquals("link-1", cur.getString(3))
            assertEquals(1, cur.getInt(4))
            assertEquals(5000L, cur.getLong(5))
        }
    }

    @Test
    fun migrate_v13_to_v14_perceptualHashKeyIsPrimaryKey_upsertReplaces() {
        Migrations.MIGRATION_13_14.migrate(db)

        db.execSQL(
            "INSERT INTO perceptual_hash (`key`, hash, isCloud, freshness, algoVersion, computedAt) " +
                "VALUES ('u', 1, 0, 'a_10', 1, 1)"
        )
        // A second write for the same key replaces it (PRIMARY KEY on `key`), mirroring @Upsert.
        db.execSQL(
            "INSERT OR REPLACE INTO perceptual_hash (`key`, hash, isCloud, freshness, algoVersion, computedAt) " +
                "VALUES ('u', 99, 0, 'a_20', 1, 2)"
        )

        db.query("SELECT COUNT(*), MAX(hash), MAX(freshness) FROM perceptual_hash WHERE `key` = 'u'")
            .use { cur ->
                assertTrue(cur.moveToFirst())
                assertEquals("the key is a primary key, so only one row survives", 1, cur.getInt(0))
                assertEquals("the replacement hash won", 99L, cur.getLong(1))
                assertEquals("the replacement freshness won", "a_20", cur.getString(2))
            }
    }

    @Test
    fun migrate_v14_to_v15_addsQueueColumns_backfillsLocalOnly_andCreatesTargetTable() {
        // A pre-v15 sync_state table with the columns MIGRATION_14_15 touches. Only the queued
        // backfill depends on `status`, so the seed carries the minimum the migration reads.
        db.execSQL(
            """
            CREATE TABLE sync_state (
              localUri TEXT NOT NULL PRIMARY KEY,
              userId TEXT NOT NULL,
              cloudFileId TEXT,
              localHash TEXT NOT NULL,
              cloudHash TEXT,
              status TEXT NOT NULL,
              lastSyncAttemptMs INTEGER NOT NULL,
              lastSyncSuccessMs INTEGER,
              backedUpAtMs INTEGER,
              sizeBytes INTEGER NOT NULL
            )
            """.trimIndent()
        )
        // One LOCAL_ONLY row (must be backfilled queued) and one SYNCED row (must NOT be).
        db.execSQL(
            "INSERT INTO sync_state (localUri, userId, cloudFileId, localHash, cloudHash, status, " +
                "lastSyncAttemptMs, lastSyncSuccessMs, backedUpAtMs, sizeBytes) " +
                "VALUES ('uri-local', 'u1', NULL, 'h1', NULL, 'LOCAL_ONLY', 10, NULL, NULL, 100)"
        )
        db.execSQL(
            "INSERT INTO sync_state (localUri, userId, cloudFileId, localHash, cloudHash, status, " +
                "lastSyncAttemptMs, lastSyncSuccessMs, backedUpAtMs, sizeBytes) " +
                "VALUES ('uri-synced', 'u1', 'cloud-1', 'h2', 'h2', 'SYNCED', 20, 21, 22, 200)"
        )

        Migrations.MIGRATION_14_15.migrate(db)

        // LOCAL_ONLY row: backfilled queued=1 / AUTO_FOLDER, queuedAt left NULL (deterministic).
        db.query("SELECT queued, queueSource, queuedAt FROM sync_state WHERE localUri = 'uri-local'")
            .use { cur ->
                assertTrue(cur.moveToFirst())
                assertEquals("LOCAL_ONLY row backfilled queued", 1, cur.getInt(0))
                assertEquals("AUTO_FOLDER", cur.getString(1))
                assertTrue("queuedAt stays NULL for backfilled rows", cur.isNull(2))
            }
        // SYNCED row: left un-queued (default 0 / NULL).
        db.query("SELECT queued, queueSource, queuedAt FROM sync_state WHERE localUri = 'uri-synced'")
            .use { cur ->
                assertTrue(cur.moveToFirst())
                assertEquals("SYNCED row must NOT be backfilled queued", 0, cur.getInt(0))
                assertTrue("queueSource default NULL on non-LOCAL_ONLY row", cur.isNull(1))
                assertTrue("queuedAt default NULL", cur.isNull(2))
            }

        // upload_album_target exists and enforces the composite (localUri, albumLinkId) primary key.
        db.execSQL("INSERT INTO upload_album_target (localUri, albumLinkId) VALUES ('uri-local', 'album-1')")
        db.execSQL(
            "INSERT OR IGNORE INTO upload_album_target (localUri, albumLinkId) VALUES ('uri-local', 'album-1')"
        )
        db.execSQL("INSERT INTO upload_album_target (localUri, albumLinkId) VALUES ('uri-local', 'album-2')")
        db.query("SELECT COUNT(*) FROM upload_album_target WHERE localUri = 'uri-local'").use { cur ->
            assertTrue(cur.moveToFirst())
            assertEquals("the duplicate (uri, album) pair was ignored; the distinct one was kept", 2, cur.getInt(0))
        }
    }

    @Test
    fun migrate_v15_to_v16_addsDurationMsColumn_defaultsNull_preservesRow() {
        db.execSQL(
            """
            INSERT INTO photo_listing (linkId, shareId, volumeId, userId, captureTime,
                displayName, mimeType, sizeBytes, revisionId, thumbnailUrl)
            VALUES ('vid','s1','v1','u1',4000,'clip.mp4','video/mp4',9000,'r9','thumb://vid')
            """.trimIndent()
        )

        Migrations.MIGRATION_15_16.migrate(db)

        db.query("SELECT linkId, mimeType, durationMs FROM photo_listing WHERE linkId = 'vid'")
            .use { cur ->
                assertTrue("expected the seeded row to survive the migration", cur.moveToFirst())
                assertEquals("vid", cur.getString(0))
                assertEquals("video/mp4", cur.getString(1))
                assertTrue("durationMs defaults to NULL on existing rows", cur.isNull(2))
            }

        // The new column is writable (the backfill / upload path fills it in later).
        db.execSQL("UPDATE photo_listing SET durationMs = 7500 WHERE linkId = 'vid'")
        db.query("SELECT durationMs FROM photo_listing WHERE linkId = 'vid'").use { cur ->
            assertTrue(cur.moveToFirst())
            assertEquals(7500L, cur.getLong(0))
        }
    }

    @Test
    fun migrate_v16_to_v17_addsPermissionsColumn_defaultsNull_preservesRows() {
        // A pre-v17 cloud_albums table with the columns that existed before the permission bitmask.
        db.execSQL(
            """
            CREATE TABLE cloud_albums (
              linkId TEXT NOT NULL PRIMARY KEY,
              name TEXT NOT NULL,
              photoCount INTEGER NOT NULL,
              coverLinkId TEXT,
              lastActivityTimeMs INTEGER,
              sharingShareId TEXT,
              sharingShareUrlId TEXT,
              sharedByEmail TEXT,
              volumeId TEXT,
              lastFetchedMs INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            INSERT INTO cloud_albums (linkId, name, photoCount, sharedByEmail, volumeId, lastFetchedMs)
            VALUES ('own','Holiday',12,NULL,'volA',100), ('guest','Wedding',3,'her@example.test','volB',200)
            """.trimIndent()
        )

        Migrations.MIGRATION_16_17.migrate(db)

        // Both rows survive, and neither claims a permission it was never told about. That NULL is
        // load-bearing: it is read as "not an editor", so an album cached before this column
        // existed stays read-only until the next refresh answers the question for real.
        db.query("SELECT linkId, name, sharedByEmail, permissions FROM cloud_albums ORDER BY linkId")
            .use { cur ->
                assertTrue("expected the seeded rows to survive the migration", cur.moveToFirst())
                assertEquals("guest", cur.getString(0))
                assertEquals("Wedding", cur.getString(1))
                assertEquals("her@example.test", cur.getString(2))
                assertTrue("permissions defaults to NULL on existing rows", cur.isNull(3))

                assertTrue(cur.moveToNext())
                assertEquals("own", cur.getString(0))
                assertTrue("an owned album has no permission either", cur.isNull(3))
            }

        // The new column is writable: the shared-with-me refresh fills it in from the bootstrap.
        db.execSQL("UPDATE cloud_albums SET permissions = 6 WHERE linkId = 'guest'")
        db.query("SELECT permissions FROM cloud_albums WHERE linkId = 'guest'").use { cur ->
            assertTrue(cur.moveToFirst())
            assertEquals(6L, cur.getLong(0))
        }
    }

    @Test
    fun migrate_v17_to_v18_addsIsChildOfAlbumColumn_backfillsAlbumChildren_leavesStreamPhotosAlone() {
        // Bring the fixture to the shape MIGRATION_17_18 reads: photo_listing carrying a parent, and
        // the cached album list that answers whether a given parent is an album. Both come from the
        // real migrations rather than hand-written SQL, so the fixture cannot drift from the schema.
        Migrations.MIGRATION_4_5.migrate(db)
        Migrations.MIGRATION_6_7.migrate(db)
        db.execSQL(
            "INSERT INTO cloud_albums (linkId, name, photoCount, lastFetchedMs) VALUES ('album-1','Wedding',2,0)"
        )
        // Three rows: one contributed into the album, one the user added to that same album (still
        // parented to the photos root), and one from before parentLinkId existed at all.
        db.execSQL(
            """
            INSERT INTO photo_listing (linkId, shareId, volumeId, userId, captureTime,
                displayName, mimeType, sizeBytes, revisionId, thumbnailUrl, parentLinkId)
            VALUES ('contributed','s1','v1','u1',1000,'guest.jpg','image/jpeg',1024,'r1',NULL,'album-1'),
                   ('in-my-album','s1','v1','u1',2000,'mine.jpg','image/jpeg',2048,'r2',NULL,'photos-root'),
                   ('legacy','s1','v1','u1',3000,'old.jpg','image/jpeg',512,'r3',NULL,NULL)
            """.trimIndent()
        )

        Migrations.MIGRATION_17_18.migrate(db)

        db.query("SELECT linkId, isChildOfAlbum FROM photo_listing ORDER BY linkId").use { cur ->
            assertTrue("expected the seeded rows to survive the migration", cur.moveToFirst())
            assertEquals("contributed", cur.getString(0))
            assertEquals("a row parented to a cached album is backfilled as an album child", 1, cur.getInt(1))

            assertTrue(cur.moveToNext())
            assertEquals("in-my-album", cur.getString(0))
            assertEquals("a root-parented photo stays a stream photo, album or not", 0, cur.getInt(1))

            assertTrue(cur.moveToNext())
            assertEquals("legacy", cur.getString(0))
            assertEquals("a row with no parent at all stays on the timeline", 0, cur.getInt(1))
        }

        // The new column is writable: every sync path sets it from the parent or the wire field.
        db.execSQL("UPDATE photo_listing SET isChildOfAlbum = 1 WHERE linkId = 'legacy'")
        db.query("SELECT isChildOfAlbum FROM photo_listing WHERE linkId = 'legacy'").use { cur ->
            assertTrue(cur.moveToFirst())
            assertEquals(1, cur.getInt(0))
        }
    }

    @Test
    fun migrate_v18_to_v19_addsNameFingerprintColumn_defaultsNull_preservesRows() {
        db.execSQL(
            """
            INSERT INTO photo_listing (linkId, shareId, volumeId, userId, captureTime,
                displayName, mimeType, sizeBytes, revisionId, thumbnailUrl)
            VALUES ('named','s1','v1','u1',1000,'holiday.jpg','image/jpeg',1024,'r1','thumb://a')
            """.trimIndent()
        )

        Migrations.MIGRATION_18_19.migrate(db)

        db.query("SELECT linkId, displayName, nameFingerprint FROM photo_listing WHERE linkId = 'named'")
            .use { cur ->
                assertTrue("expected the seeded row to survive the migration", cur.moveToFirst())
                assertEquals("named", cur.getString(0))
                assertEquals("the stored name is untouched", "holiday.jpg", cur.getString(1))
                // That NULL is the point of the column rather than an oversight: the digest belongs
                // to ciphertext this migration cannot see, and null is read as "recheck", so the
                // first refresh after the upgrade repairs any name that already drifted.
                assertTrue("nameFingerprint defaults to NULL on existing rows", cur.isNull(2))
            }

        // The new column is writable: every path that decrypts a name records the digest it came from.
        db.execSQL("UPDATE photo_listing SET nameFingerprint = 'a1b2c3d4' WHERE linkId = 'named'")
        db.query("SELECT nameFingerprint FROM photo_listing WHERE linkId = 'named'").use { cur ->
            assertTrue(cur.moveToFirst())
            assertEquals("a1b2c3d4", cur.getString(0))
        }
    }

    @Test
    fun migrate_v19_to_v20_createsSweepSnapshotTable_compositeKeyIsPerUserVolumeLink() {
        Migrations.MIGRATION_19_20.migrate(db)

        // The table exists and accepts a row shaped exactly like ListingSweepSnapshotEntity.
        db.execSQL(
            "INSERT INTO listing_sweep_snapshot (userId, volumeId, linkId) VALUES ('u1','volA','link-1')"
        )
        // Re-offering the same candidate is not news (the DAO inserts with IGNORE), while the same
        // linkId under another volume is a different candidate and must survive alongside it.
        db.execSQL(
            "INSERT OR IGNORE INTO listing_sweep_snapshot (userId, volumeId, linkId) VALUES ('u1','volA','link-1')"
        )
        db.execSQL(
            "INSERT INTO listing_sweep_snapshot (userId, volumeId, linkId) VALUES ('u1','volB','link-1')"
        )
        db.execSQL(
            "INSERT INTO listing_sweep_snapshot (userId, volumeId, linkId) VALUES ('u2','volA','link-1')"
        )

        db.query("SELECT COUNT(*) FROM listing_sweep_snapshot").use { cur ->
            assertTrue(cur.moveToFirst())
            assertEquals("the duplicate candidate was ignored; the distinct scopes were kept", 3, cur.getInt(0))
        }

        // The generation reads and deletes are all scoped to one (userId, volumeId) pair.
        db.execSQL("DELETE FROM listing_sweep_snapshot WHERE userId = 'u1' AND volumeId = 'volA'")
        db.query("SELECT userId, volumeId FROM listing_sweep_snapshot ORDER BY userId, volumeId")
            .use { cur ->
                assertTrue(cur.moveToFirst())
                assertEquals("u1", cur.getString(0))
                assertEquals("clearing one generation leaves the other volume's alone", "volB", cur.getString(1))

                assertTrue(cur.moveToNext())
                assertEquals("another account's generation is untouched too", "u2", cur.getString(0))
            }
    }

    @Test
    fun migrate_v19_to_v20_leavesTheNewTableEmpty_andPhotoRowsUntouched() {
        db.execSQL(
            """
            INSERT INTO photo_listing (linkId, shareId, volumeId, userId, captureTime,
                displayName, mimeType, sizeBytes, revisionId, thumbnailUrl)
            VALUES ('kept','s1','v1','u1',1000,'holiday.jpg','image/jpeg',1024,'r1','thumb://a')
            """.trimIndent()
        )

        Migrations.MIGRATION_19_20.migrate(db)

        // Empty is the correct state to arrive at, not an oversight: a row's whole value is that it
        // was read at a known moment relative to a listing walk, and this migration has no walk to
        // speak for. Seeding it from photo_listing here would hand the next pass a candidate set
        // whose age it cannot know — and what stays in that set is what the sweep deletes.
        db.query("SELECT COUNT(*) FROM listing_sweep_snapshot").use { cur ->
            assertTrue(cur.moveToFirst())
            assertEquals("no backfill: the first fresh pass materialises its own generation", 0, cur.getInt(0))
        }
        db.query("SELECT displayName FROM photo_listing WHERE linkId = 'kept'").use { cur ->
            assertTrue("expected the seeded row to survive the migration", cur.moveToFirst())
            assertEquals("holiday.jpg", cur.getString(0))
        }
    }

    @Test
    fun migrate_v20_to_v21_addsUserTagsCsvColumn_withEmptyDefault_preservesDetections() {
        // The fixture comes from the real migration that created local_tag rather than hand-written
        // SQL, so it cannot drift from the schema MIGRATION_20_21 has to alter.
        Migrations.MIGRATION_8_9.migrate(db)
        db.execSQL(
            """
            INSERT INTO local_tag (uri, dateModified, sizeBytes, tagsCsv, scannedAt)
            VALUES ('content://media/external/images/media/1', 1700, 2048, '1,4', 9000),
                   ('content://media/external/images/media/2', 1800, 4096, '', 9100)
            """.trimIndent()
        )

        Migrations.MIGRATION_20_21.migrate(db)

        // Every detection survives, and neither row claims a choice nobody made. Empty is the only
        // honest state here: the column beside it holds what a detector guessed, so a backfill from
        // it would dress that guess up as a decision the user never took.
        db.query("SELECT uri, dateModified, sizeBytes, tagsCsv, scannedAt, userTagsCsv FROM local_tag ORDER BY uri")
            .use { cur ->
                assertTrue("expected the seeded rows to survive the migration", cur.moveToFirst())
                assertEquals("content://media/external/images/media/1", cur.getString(0))
                assertEquals(1700L, cur.getLong(1))
                assertEquals(2048L, cur.getLong(2))
                assertEquals("the detected tags are untouched", "1,4", cur.getString(3))
                assertEquals(9000L, cur.getLong(4))
                assertEquals("userTagsCsv must default to empty string", "", cur.getString(5))

                assertTrue(cur.moveToNext())
                assertEquals("content://media/external/images/media/2", cur.getString(0))
                assertEquals("a row that detected nothing has no user choice either", "", cur.getString(5))
            }

        // The new column is writable, and writing it leaves the detection columns exactly as they are.
        db.execSQL(
            "UPDATE local_tag SET userTagsCsv = '2,8' WHERE uri = 'content://media/external/images/media/1'"
        )
        db.query(
            "SELECT tagsCsv, scannedAt, userTagsCsv FROM local_tag WHERE uri = 'content://media/external/images/media/1'"
        ).use { cur ->
            assertTrue(cur.moveToFirst())
            assertEquals("1,4", cur.getString(0))
            assertEquals(9000L, cur.getLong(1))
            assertEquals("2,8", cur.getString(2))
        }
    }

    @Test
    fun migrate_v23_to_v24_createsFaceScanTable_andBackfillsFromExistingFaces() {
        // At v23 the face table already exists; the migration seeds face_scan from photos that already
        // carry a face, so an existing library is not re-scanned. A minimal face shape covers the
        // userId + photoKey the backfill reads.
        db.execSQL(
            "CREATE TABLE `face` (`id` TEXT NOT NULL, `userId` TEXT NOT NULL, `photoKey` TEXT NOT NULL, PRIMARY KEY(`id`))"
        )
        db.execSQL("INSERT INTO face (id, userId, photoKey) VALUES ('f1','u1','link-1')")
        db.execSQL("INSERT INTO face (id, userId, photoKey) VALUES ('f2','u1','link-1')")
        db.execSQL("INSERT INTO face (id, userId, photoKey) VALUES ('f3','u2','link-9')")

        Migrations.MIGRATION_23_24.migrate(db)

        // Two faces on one photo collapse to one marker; the other account's photo is its own marker.
        db.query("SELECT COUNT(*) FROM face_scan").use { cur ->
            assertTrue(cur.moveToFirst())
            assertEquals("backfilled distinct (user, photo) markers", 2, cur.getInt(0))
        }

        // New markers are accepted and the composite key dedups per (user, photo).
        db.execSQL("INSERT OR REPLACE INTO face_scan (userId, photoKey) VALUES ('u1','link-1')")
        db.execSQL("INSERT INTO face_scan (userId, photoKey) VALUES ('u2','link-1')")
        db.query("SELECT COUNT(*) FROM face_scan").use { cur ->
            assertTrue(cur.moveToFirst())
            assertEquals("the duplicate marker collapsed; the new one was added", 3, cur.getInt(0))
        }

        // The sign-out wipe and clear-index are scoped to one account, leaving another's markers alone.
        db.execSQL("DELETE FROM face_scan WHERE userId = 'u1'")
        db.query("SELECT COUNT(*) FROM face_scan WHERE userId = 'u1'").use { cur ->
            assertTrue(cur.moveToFirst())
            assertEquals("one account's markers cleared", 0, cur.getInt(0))
        }
        db.query("SELECT COUNT(*) FROM face_scan WHERE userId = 'u2'").use { cur ->
            assertTrue(cur.moveToFirst())
            assertEquals("the other account's markers remain", 2, cur.getInt(0))
        }
    }

    @Test
    fun migrate_v24_to_v25_addsRejectedColumn_andPersonManualPhotoTable() {
        // The migration ALTERs the face table (a rejected flag) and creates the manual-photo table, so
        // a minimal face shape must exist first.
        db.execSQL(
            "CREATE TABLE `face` (`id` TEXT NOT NULL, `userId` TEXT NOT NULL, `photoKey` TEXT NOT NULL, PRIMARY KEY(`id`))"
        )
        db.execSQL("INSERT INTO face (id, userId, photoKey) VALUES ('f1','u1','link-1')")

        Migrations.MIGRATION_24_25.migrate(db)

        // An existing face defaults to not-rejected, so a rescan keeps clustering it until the user
        // says otherwise.
        db.query("SELECT rejected FROM face WHERE id = 'f1'").use { cur ->
            assertTrue(cur.moveToFirst())
            assertEquals("existing face defaults to not rejected", 0, cur.getInt(0))
        }

        // The manual-photo table exists and its composite key dedups per (user, name, photo).
        db.execSQL("INSERT INTO person_manual_photo (userId, personName, photoKey) VALUES ('u1','Akos','link-2')")
        db.execSQL("INSERT OR IGNORE INTO person_manual_photo (userId, personName, photoKey) VALUES ('u1','Akos','link-2')")
        db.query("SELECT COUNT(*) FROM person_manual_photo").use { cur ->
            assertTrue(cur.moveToFirst())
            assertEquals("the duplicate manual membership collapsed", 1, cur.getInt(0))
        }
        // The same photo under a different name is a separate membership, so renaming can re-key it.
        db.execSQL("INSERT INTO person_manual_photo (userId, personName, photoKey) VALUES ('u1','Bela','link-2')")
        db.query("SELECT COUNT(*) FROM person_manual_photo").use { cur ->
            assertTrue(cur.moveToFirst())
            assertEquals("a different name is a separate membership", 2, cur.getInt(0))
        }
    }

    @Test
    fun migrate_v25_to_v26_addsManualNameColumn_nullByDefault() {
        db.execSQL(
            "CREATE TABLE `face` (`id` TEXT NOT NULL, `userId` TEXT NOT NULL, `photoKey` TEXT NOT NULL, PRIMARY KEY(`id`))"
        )
        db.execSQL("INSERT INTO face (id, userId, photoKey) VALUES ('f1','u1','link-1')")

        Migrations.MIGRATION_25_26.migrate(db)

        // An existing face is unconfirmed (null), so it is not treated as anchoring any person.
        db.query("SELECT manualName FROM face WHERE id = 'f1'").use { cur ->
            assertTrue(cur.moveToFirst())
            assertTrue("existing face has no confirmed name", cur.isNull(0))
        }
        // The column accepts a confirmed name.
        db.execSQL("UPDATE face SET manualName = 'Akos' WHERE id = 'f1'")
        db.query("SELECT manualName FROM face WHERE id = 'f1'").use { cur ->
            assertTrue(cur.moveToFirst())
            assertEquals("a confirmed name is stored", "Akos", cur.getString(0))
        }
    }

    @Test
    fun migrate_v26_to_v27_addsNotPersonTable() {
        Migrations.MIGRATION_26_27.migrate(db)
        // The table exists and its composite key dedups a (user, name, face) rejection.
        db.execSQL("INSERT INTO not_person (userId, personName, faceId) VALUES ('u1','Akos','face-1')")
        db.execSQL("INSERT OR IGNORE INTO not_person (userId, personName, faceId) VALUES ('u1','Akos','face-1')")
        db.query("SELECT COUNT(*) FROM not_person").use { cur ->
            assertTrue(cur.moveToFirst())
            assertEquals("the duplicate rejection collapsed", 1, cur.getInt(0))
        }
        // The same face rejected for a different person is its own row.
        db.execSQL("INSERT INTO not_person (userId, personName, faceId) VALUES ('u1','Bela','face-1')")
        db.query("SELECT COUNT(*) FROM not_person").use { cur ->
            assertTrue(cur.moveToFirst())
            assertEquals("a different person is a separate rejection", 2, cur.getInt(0))
        }
    }

    @Test
    fun migrate_v28_to_v29_addsPersonCoverTable_oneCoverPerName() {
        Migrations.MIGRATION_28_29.migrate(db)
        // One cover per (user, name): re-picking replaces rather than piling up rows.
        db.execSQL("INSERT INTO person_cover (userId, personName, photoKey) VALUES ('u1','Akos','link-1')")
        db.execSQL("INSERT OR REPLACE INTO person_cover (userId, personName, photoKey) VALUES ('u1','Akos','link-2')")
        db.query("SELECT COUNT(*), MAX(photoKey) FROM person_cover WHERE userId = 'u1' AND personName = 'Akos'")
            .use { cur ->
                assertTrue(cur.moveToFirst())
                assertEquals("the person keeps a single cover", 1, cur.getInt(0))
                assertEquals("the latest pick won", "link-2", cur.getString(1))
            }
        // A different name is a separate cover, so renaming can re-key it.
        db.execSQL("INSERT INTO person_cover (userId, personName, photoKey) VALUES ('u1','Bela','link-1')")
        db.query("SELECT COUNT(*) FROM person_cover").use { cur ->
            assertTrue(cur.moveToFirst())
            assertEquals("a different name is a separate cover", 2, cur.getInt(0))
        }
    }

    @Test
    fun migrate_v29_to_v30_createsPendingMetadataEditTable_replacesOnLinkIdPk_andKeepsEmptyVersusNull() {
        Migrations.MIGRATION_29_30.migrate(db)

        // The table exists and accepts a row shaped exactly like PendingMetadataEditEntity, including
        // the nullable columns left NULL on a cloud-only, unchanged-place edit.
        db.execSQL(
            "INSERT INTO pending_metadata_edit " +
                "(linkId, userId, deviceUri, newCaptureMs, locationMode, lat, lng, description, artist, copyright, enqueuedAt) " +
                "VALUES ('link-1','u1',NULL,NULL,'UNCHANGED',NULL,NULL,NULL,NULL,NULL,10)"
        )
        // A second edit for the same photo REPLACES the first (linkId is the primary key), so a
        // re-enqueue never piles up two pending edits for one photo.
        db.execSQL(
            "INSERT OR REPLACE INTO pending_metadata_edit " +
                "(linkId, userId, deviceUri, newCaptureMs, locationMode, lat, lng, description, artist, copyright, enqueuedAt) " +
                "VALUES ('link-1','u1','content://media/7',1700,'SET',47.5,19.05,'','a','c',20)"
        )

        db.query(
            "SELECT COUNT(*), MAX(locationMode), MAX(lat), MAX(lng), MAX(deviceUri), MAX(enqueuedAt) " +
                "FROM pending_metadata_edit WHERE linkId = 'link-1'"
        ).use { cur ->
            assertTrue(cur.moveToFirst())
            assertEquals("the linkId is a primary key, so only one pending edit survives", 1, cur.getInt(0))
            assertEquals("the replacement's SET place won", "SET", cur.getString(1))
            assertEquals(47.5, cur.getDouble(2), 0.0)
            assertEquals(19.05, cur.getDouble(3), 0.0)
            assertEquals("content://media/7", cur.getString(4))
            assertEquals(20L, cur.getLong(5))
        }

        // An empty text tag is stored as "", distinct from a NULL "leave unchanged".
        db.query("SELECT description, artist FROM pending_metadata_edit WHERE linkId = 'link-1'").use { cur ->
            assertTrue(cur.moveToFirst())
            assertEquals("an empty tag clears that field and stays an empty string, not NULL", "", cur.getString(0))
            assertEquals("a", cur.getString(1))
        }
    }

    @Test
    fun migrate_v30_to_v31_bringsPendingMetadataEditToTheResumeStateShape() {
        // Simulate the unreleased v30 that created pending_metadata_edit WITHOUT the newLinkId column,
        // the shape a test build already left on device, and seed a row so the drop is observable.
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `pending_metadata_edit` (" +
                "`linkId` TEXT NOT NULL, `userId` TEXT NOT NULL, `deviceUri` TEXT, " +
                "`newCaptureMs` INTEGER, `locationMode` TEXT NOT NULL, `lat` REAL, `lng` REAL, " +
                "`description` TEXT, `artist` TEXT, `copyright` TEXT, `enqueuedAt` INTEGER NOT NULL, " +
                "PRIMARY KEY(`linkId`))"
        )
        db.execSQL(
            "INSERT INTO pending_metadata_edit " +
                "(linkId, userId, deviceUri, newCaptureMs, locationMode, lat, lng, description, artist, copyright, enqueuedAt) " +
                "VALUES ('stale','u1',NULL,NULL,'UNCHANGED',NULL,NULL,NULL,NULL,NULL,10)"
        )

        Migrations.MIGRATION_30_31.migrate(db)

        // The transient queue is recreated, so the pre-migration row is gone (an empty queue is the
        // correct state to arrive at) and the table now carries the resume-state newLinkId column.
        db.query("SELECT COUNT(*) FROM pending_metadata_edit").use { cur ->
            assertTrue(cur.moveToFirst())
            assertEquals("the transient queue is recreated empty", 0, cur.getInt(0))
        }
        db.execSQL(
            "INSERT INTO pending_metadata_edit " +
                "(linkId, userId, deviceUri, newCaptureMs, locationMode, lat, lng, description, artist, copyright, enqueuedAt, newLinkId) " +
                "VALUES ('link-1','u1',NULL,NULL,'UNCHANGED',NULL,NULL,NULL,NULL,NULL,10,'new-link-9')"
        )
        db.query("SELECT newLinkId FROM pending_metadata_edit WHERE linkId = 'link-1'").use { cur ->
            assertTrue(cur.moveToFirst())
            assertEquals("the recorded upload link is stored so a resume skips re-uploading", "new-link-9", cur.getString(0))
        }
    }

    @Test
    fun migrate_v31_to_v32_addsHiResScannedColumn_defaultsZero_preservesMarker() {
        // At v31 face_scan holds one marker per scanned photo (userId, photoKey). The migration adds
        // the hi-res-swept flag the "find more photos" sweep reads, so a minimal v31 shape and a
        // seeded marker cover what the ALTER has to preserve.
        db.execSQL(
            "CREATE TABLE `face_scan` (`userId` TEXT NOT NULL, `photoKey` TEXT NOT NULL, " +
                "PRIMARY KEY(`userId`, `photoKey`))"
        )
        db.execSQL("INSERT INTO face_scan (userId, photoKey) VALUES ('u1','link-1')")

        Migrations.MIGRATION_31_32.migrate(db)

        // The marker survives and defaults to not-yet-hi-res-swept, so the first sweep after the
        // upgrade re-checks the faceless photo once rather than treating it as already done.
        db.query("SELECT userId, photoKey, hiResScanned FROM face_scan WHERE photoKey = 'link-1'")
            .use { cur ->
                assertTrue("expected the seeded marker to survive the migration", cur.moveToFirst())
                assertEquals("u1", cur.getString(0))
                assertEquals("link-1", cur.getString(1))
                assertEquals("hiResScanned defaults to 0 on existing markers", 0, cur.getInt(2))
            }

        // The new column is writable: a sweep flags a photo hi-res swept so it is never re-checked.
        db.execSQL("UPDATE face_scan SET hiResScanned = 1 WHERE photoKey = 'link-1'")
        db.query("SELECT hiResScanned FROM face_scan WHERE photoKey = 'link-1'").use { cur ->
            assertTrue(cur.moveToFirst())
            assertEquals(1, cur.getInt(0))
        }
    }

    @Test
    fun migrate_v32_to_v33_addsIsOtherColumn_defaultsZero_preservesPerson() {
        // At v32 person holds one row per clustered person. The migration adds the isOther flag that
        // marks the single "Unsorted" bucket, so a minimal v32 person and a seeded row cover what the
        // ALTER has to preserve.
        db.execSQL(
            "CREATE TABLE `person` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`userId` TEXT NOT NULL, `displayName` TEXT, `coverFaceId` TEXT, " +
                "`faceCount` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL)"
        )
        db.execSQL(
            "INSERT INTO person (id, userId, displayName, coverFaceId, faceCount, updatedAt) " +
                "VALUES (1, 'u1', 'Ákos', 'face-1', 12, 0)"
        )

        Migrations.MIGRATION_32_33.migrate(db)

        // The named person survives and defaults to not-the-bucket, so an existing person is never
        // mistaken for the Unsorted bucket after the upgrade.
        db.query("SELECT userId, displayName, faceCount, isOther FROM person WHERE id = 1").use { cur ->
            assertTrue("expected the seeded person to survive the migration", cur.moveToFirst())
            assertEquals("u1", cur.getString(0))
            assertEquals("Ákos", cur.getString(1))
            assertEquals(12, cur.getInt(2))
            assertEquals("isOther defaults to 0 on existing people", 0, cur.getInt(3))
        }

        // The new column is writable: the rebuild flags the leftover bucket as the Unsorted person.
        db.execSQL(
            "INSERT INTO person (id, userId, displayName, coverFaceId, faceCount, isOther, updatedAt) " +
                "VALUES (2, 'u1', NULL, 'face-9', 40, 1, 0)"
        )
        db.query("SELECT isOther FROM person WHERE id = 2").use { cur ->
            assertTrue(cur.moveToFirst())
            assertEquals(1, cur.getInt(0))
        }
    }

    @Test
    fun migrate_v2_through_v4_chain_appliesBothMigrations() {
        // Seed a pure v2 row.
        db.execSQL(
            """
            INSERT INTO photo_listing (linkId, shareId, volumeId, userId, captureTime,
                displayName, mimeType, sizeBytes, revisionId, thumbnailUrl)
            VALUES ('l3','s1','v1','u1',3000,'c.jpg','image/jpeg',3072,'r3',NULL)
            """.trimIndent()
        )

        Migrations.MIGRATION_2_3.migrate(db)
        Migrations.MIGRATION_3_4.migrate(db)

        db.query("SELECT contentHash, tagsCsv, thumbnailUrl FROM photo_listing WHERE linkId = 'l3'")
            .use { cur ->
                assertTrue(cur.moveToFirst())
                assertTrue("contentHash NULL on v2-seeded row after both migrations", cur.isNull(0))
                assertEquals("", cur.getString(1))
                assertTrue("thumbnailUrl NULL preserved", cur.isNull(2))
            }
    }
}
