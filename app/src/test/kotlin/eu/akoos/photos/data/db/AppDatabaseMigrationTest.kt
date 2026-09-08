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
    fun migrate_v33_to_v34_createsClusterSummaryTable_personIdIsPrimaryKey_updatedAtDefaultsZero() {
        Migrations.MIGRATION_33_34.migrate(db)

        // The table exists and accepts a row shaped exactly like ClusterSummaryEntity.
        db.execSQL(
            "INSERT INTO cluster_summary (personId, userId, centroid, memberCount, modelVersion, updatedAt) " +
                "VALUES (1, 'u1', X'01020304', 12, 4, 100)"
        )
        db.query(
            "SELECT personId, userId, memberCount, modelVersion, updatedAt FROM cluster_summary WHERE personId = 1"
        ).use { cur ->
            assertTrue("expected the inserted centroid row to be readable", cur.moveToFirst())
            assertEquals(1L, cur.getLong(0))
            assertEquals("u1", cur.getString(1))
            assertEquals(12, cur.getInt(2))
            assertEquals(4, cur.getInt(3))
            assertEquals(100L, cur.getLong(4))
        }

        // updatedAt carries a DEFAULT 0, so a row inserted without it reads back as 0.
        db.execSQL(
            "INSERT INTO cluster_summary (personId, userId, centroid, memberCount, modelVersion) " +
                "VALUES (2, 'u1', X'05', 1, 4)"
        )
        db.query("SELECT updatedAt FROM cluster_summary WHERE personId = 2").use { cur ->
            assertTrue(cur.moveToFirst())
            assertEquals("updatedAt defaults to 0 when omitted", 0L, cur.getLong(0))
        }

        // A second write for the same person REPLACES the first (personId is the primary key), mirroring
        // @Upsert, so a re-summarised cluster never piles up two rows.
        db.execSQL(
            "INSERT OR REPLACE INTO cluster_summary (personId, userId, centroid, memberCount, modelVersion, updatedAt) " +
                "VALUES (1, 'u1', X'0607', 20, 4, 200)"
        )
        db.query("SELECT COUNT(*), MAX(memberCount) FROM cluster_summary WHERE personId = 1").use { cur ->
            assertTrue(cur.moveToFirst())
            assertEquals("the personId is a primary key, so only one summary survives", 1, cur.getInt(0))
            assertEquals("the replacement member count won", 20, cur.getInt(1))
        }

        // The clear-for-user delete is scoped to one account, leaving another's summaries alone.
        db.execSQL(
            "INSERT INTO cluster_summary (personId, userId, centroid, memberCount, modelVersion, updatedAt) " +
                "VALUES (5, 'u2', X'08', 3, 4, 300)"
        )
        db.execSQL("DELETE FROM cluster_summary WHERE userId = 'u1'")
        db.query("SELECT COUNT(*) FROM cluster_summary WHERE userId = 'u1'").use { cur ->
            assertTrue(cur.moveToFirst())
            assertEquals("one account's summaries cleared", 0, cur.getInt(0))
        }
        db.query("SELECT personId FROM cluster_summary WHERE userId = 'u2'").use { cur ->
            assertTrue("the other account's summary remains", cur.moveToFirst())
            assertEquals(5L, cur.getLong(0))
        }
    }

    @Test
    fun migrate_v34_to_v35_createsPendingImportTable_compositeKeyReplacesPerEntry_scopesByZip() {
        Migrations.MIGRATION_34_35.migrate(db)

        // The table exists and accepts a row shaped exactly like PendingImportEntity.
        db.execSQL(
            "INSERT INTO pending_import (zipId, entryName, linkId, importedAt) " +
                "VALUES ('zip-a', 'Takeout/Photos/a.jpg', 'link-1', 100)"
        )
        db.query(
            "SELECT zipId, entryName, linkId, importedAt FROM pending_import WHERE zipId = 'zip-a'"
        ).use { cur ->
            assertTrue("expected the inserted import marker to be readable", cur.moveToFirst())
            assertEquals("zip-a", cur.getString(0))
            assertEquals("Takeout/Photos/a.jpg", cur.getString(1))
            assertEquals("link-1", cur.getString(2))
            assertEquals(100L, cur.getLong(3))
        }

        // A second write for the same (zipId, entryName) REPLACES the first (the composite primary key),
        // mirroring @Insert(REPLACE), so a resume that re-marks an entry never piles up two rows.
        db.execSQL(
            "INSERT OR REPLACE INTO pending_import (zipId, entryName, linkId, importedAt) " +
                "VALUES ('zip-a', 'Takeout/Photos/a.jpg', 'link-2', 200)"
        )
        db.query(
            "SELECT COUNT(*), MAX(linkId), MAX(importedAt) FROM pending_import " +
                "WHERE zipId = 'zip-a' AND entryName = 'Takeout/Photos/a.jpg'"
        ).use { cur ->
            assertTrue(cur.moveToFirst())
            assertEquals("the (zipId, entryName) pair is the primary key, so only one marker survives", 1, cur.getInt(0))
            assertEquals("the replacement link won", "link-2", cur.getString(1))
            assertEquals(200L, cur.getLong(2))
        }

        // A different entry of the same zip, and the same entry name under a different zip, are each their
        // own marker, so the per-zip dedupe never bleeds across zips.
        db.execSQL(
            "INSERT INTO pending_import (zipId, entryName, linkId, importedAt) " +
                "VALUES ('zip-a', 'Takeout/Photos/b.jpg', 'link-3', 300)"
        )
        db.execSQL(
            "INSERT INTO pending_import (zipId, entryName, linkId, importedAt) " +
                "VALUES ('zip-b', 'Takeout/Photos/a.jpg', 'link-4', 400)"
        )

        // The EXISTS-style resume probe the DAO runs: a sent entry is seen, an unsent one is not.
        db.query(
            "SELECT EXISTS(SELECT 1 FROM pending_import WHERE zipId = 'zip-a' AND entryName = 'Takeout/Photos/b.jpg')"
        ).use { cur ->
            assertTrue(cur.moveToFirst())
            assertEquals("an already-imported entry is reported present", 1, cur.getInt(0))
        }
        db.query(
            "SELECT EXISTS(SELECT 1 FROM pending_import WHERE zipId = 'zip-a' AND entryName = 'Takeout/Photos/z.jpg')"
        ).use { cur ->
            assertTrue(cur.moveToFirst())
            assertEquals("an entry not yet imported is reported absent", 0, cur.getInt(0))
        }

        // The per-zip count is scoped, so a resumed run's starting progress reflects only its own zip.
        db.query("SELECT COUNT(*) FROM pending_import WHERE zipId = 'zip-a'").use { cur ->
            assertTrue(cur.moveToFirst())
            assertEquals("two distinct entries of zip-a", 2, cur.getInt(0))
        }

        // clearForZip deletes one zip's markers and leaves another zip's alone.
        db.execSQL("DELETE FROM pending_import WHERE zipId = 'zip-a'")
        db.query("SELECT COUNT(*) FROM pending_import WHERE zipId = 'zip-a'").use { cur ->
            assertTrue(cur.moveToFirst())
            assertEquals("zip-a markers cleared", 0, cur.getInt(0))
        }
        db.query("SELECT entryName FROM pending_import WHERE zipId = 'zip-b'").use { cur ->
            assertTrue("the other zip's markers remain", cur.moveToFirst())
            assertEquals("Takeout/Photos/a.jpg", cur.getString(0))
        }
    }

    @Test
    fun migrate_v35_to_v36_createsImportStagedTable_compositeKeyReplacesPerEntry_filtersReviewChoices() {
        Migrations.MIGRATION_35_36.migrate(db)

        // The table exists and accepts a row shaped exactly like ImportStagedEntity, nullable
        // metadata columns left NULL where the archive resolved nothing.
        db.execSQL(
            "INSERT INTO import_staged " +
                "(zipId, entryName, title, dateMs, lat, lng, description, sizeBytes, thumbPath, excluded, uploaded, stagedAt) " +
                "VALUES ('zip-a', 'Takeout/Photos/a.jpg', 'a.jpg', 1000, 47.5, 19.05, NULL, 2048, '/cache/a.jpg', 0, 0, 10)"
        )
        db.query(
            "SELECT zipId, entryName, title, dateMs, lat, lng, description, sizeBytes, thumbPath, excluded, uploaded, stagedAt " +
                "FROM import_staged WHERE zipId = 'zip-a' AND entryName = 'Takeout/Photos/a.jpg'"
        ).use { cur ->
            assertTrue("expected the inserted staged row to be readable", cur.moveToFirst())
            assertEquals("zip-a", cur.getString(0))
            assertEquals("Takeout/Photos/a.jpg", cur.getString(1))
            assertEquals("a.jpg", cur.getString(2))
            assertEquals(1000L, cur.getLong(3))
            assertEquals(47.5, cur.getDouble(4), 0.0)
            assertEquals(19.05, cur.getDouble(5), 0.0)
            assertTrue("description left NULL where none resolved", cur.isNull(6))
            assertEquals(2048L, cur.getLong(7))
            assertEquals("/cache/a.jpg", cur.getString(8))
            assertEquals(0, cur.getInt(9))
            assertEquals(0, cur.getInt(10))
            assertEquals(10L, cur.getLong(11))
        }

        // A second write for the same (zipId, entryName) REPLACES the first (the composite primary key),
        // mirroring @Insert(REPLACE), so re-staging an entry never piles up two rows.
        db.execSQL(
            "INSERT OR REPLACE INTO import_staged " +
                "(zipId, entryName, title, dateMs, lat, lng, description, sizeBytes, thumbPath, excluded, uploaded, stagedAt) " +
                "VALUES ('zip-a', 'Takeout/Photos/a.jpg', 'renamed.jpg', 2000, NULL, NULL, 'desc', 4096, '/cache/a2.jpg', 0, 0, 20)"
        )
        db.query(
            "SELECT COUNT(*), MAX(title), MAX(stagedAt) FROM import_staged " +
                "WHERE zipId = 'zip-a' AND entryName = 'Takeout/Photos/a.jpg'"
        ).use { cur ->
            assertTrue(cur.moveToFirst())
            assertEquals("the (zipId, entryName) pair is the primary key, so only one staged row survives", 1, cur.getInt(0))
            assertEquals("the replacement metadata won", "renamed.jpg", cur.getString(1))
            assertEquals(20L, cur.getLong(2))
        }

        // Three entries covering the upload filter: one clean, one the user excluded, one already sent.
        db.execSQL(
            "INSERT INTO import_staged " +
                "(zipId, entryName, sizeBytes, excluded, uploaded, stagedAt) " +
                "VALUES ('zip-a', 'Takeout/Photos/b.jpg', 100, 1, 0, 30)"
        )
        db.execSQL(
            "INSERT INTO import_staged " +
                "(zipId, entryName, sizeBytes, excluded, uploaded, stagedAt) " +
                "VALUES ('zip-a', 'Takeout/Photos/c.jpg', 100, 0, 1, 40)"
        )

        // The pendingUpload / observeIncludedCount filter: only the neither-excluded-nor-uploaded row.
        db.query(
            "SELECT COUNT(*) FROM import_staged WHERE zipId = 'zip-a' AND excluded = 0 AND uploaded = 0"
        ).use { cur ->
            assertTrue(cur.moveToFirst())
            assertEquals("only the clean entry is queued to upload", 1, cur.getInt(0))
        }

        // markUploaded flips one entry, and it drops out of the pending set.
        db.execSQL(
            "UPDATE import_staged SET uploaded = 1 WHERE zipId = 'zip-a' AND entryName = 'Takeout/Photos/a.jpg'"
        )
        db.query(
            "SELECT COUNT(*) FROM import_staged WHERE zipId = 'zip-a' AND excluded = 0 AND uploaded = 0"
        ).use { cur ->
            assertTrue(cur.moveToFirst())
            assertEquals("the marked entry left the pending set", 0, cur.getInt(0))
        }

        // setExcluded over a selection toggles the flag back, bringing an entry into the pending set.
        db.execSQL(
            "UPDATE import_staged SET excluded = 0 WHERE zipId = 'zip-a' AND entryName IN ('Takeout/Photos/b.jpg')"
        )
        db.query(
            "SELECT COUNT(*) FROM import_staged WHERE zipId = 'zip-a' AND excluded = 0 AND uploaded = 0"
        ).use { cur ->
            assertTrue(cur.moveToFirst())
            assertEquals("the un-excluded entry is queued again", 1, cur.getInt(0))
        }

        // A different archive keeps its own staged set, so per-zip scoping never bleeds across archives.
        db.execSQL(
            "INSERT INTO import_staged (zipId, entryName, sizeBytes, excluded, uploaded, stagedAt) " +
                "VALUES ('zip-b', 'Takeout/Photos/a.jpg', 100, 0, 0, 50)"
        )
        db.query("SELECT COUNT(*) FROM import_staged WHERE zipId = 'zip-a'").use { cur ->
            assertTrue(cur.moveToFirst())
            assertEquals("three distinct entries of zip-a", 3, cur.getInt(0))
        }

        // clearForZip drops one archive's staged rows and leaves another's alone.
        db.execSQL("DELETE FROM import_staged WHERE zipId = 'zip-a'")
        db.query("SELECT COUNT(*) FROM import_staged WHERE zipId = 'zip-a'").use { cur ->
            assertTrue(cur.moveToFirst())
            assertEquals("zip-a staged rows cleared", 0, cur.getInt(0))
        }
        db.query("SELECT entryName FROM import_staged WHERE zipId = 'zip-b'").use { cur ->
            assertTrue("the other archive's staged rows remain", cur.moveToFirst())
            assertEquals("Takeout/Photos/a.jpg", cur.getString(0))
        }
    }

    @Test
    fun migrate_v35_to_v36_createsImportHistoryTable_autoIdAssigned_ordersNewestFirst() {
        Migrations.MIGRATION_35_36.migrate(db)

        // The table exists and accepts a row shaped exactly like ImportHistoryEntity, id omitted so the
        // autoincrement key assigns one.
        db.execSQL(
            "INSERT INTO import_history (zipId, fileName, importedAt, total, uploaded, skipped, failed) " +
                "VALUES ('zip-a', 'takeout-1.zip', 100, 10, 8, 1, 1)"
        )
        db.execSQL(
            "INSERT INTO import_history (zipId, fileName, importedAt, total, uploaded, skipped, failed) " +
                "VALUES ('zip-b', 'takeout-2.zip', 200, 5, 5, 0, 0)"
        )

        // Two runs get two distinct auto-generated ids.
        db.query("SELECT COUNT(*), COUNT(DISTINCT id) FROM import_history").use { cur ->
            assertTrue(cur.moveToFirst())
            assertEquals(2, cur.getInt(0))
            assertEquals("each run got its own auto-generated id", 2, cur.getInt(1))
        }

        // observeAll / recent order newest first by importedAt.
        db.query("SELECT fileName, total, uploaded, skipped, failed FROM import_history ORDER BY importedAt DESC")
            .use { cur ->
                assertTrue(cur.moveToFirst())
                assertEquals("the newer run sorts first", "takeout-2.zip", cur.getString(0))
                assertEquals(5, cur.getInt(1))
                assertEquals(5, cur.getInt(2))
                assertEquals(0, cur.getInt(3))
                assertEquals(0, cur.getInt(4))

                assertTrue(cur.moveToNext())
                assertEquals("takeout-1.zip", cur.getString(0))
                assertEquals("its skipped and failed counts are preserved", 1, cur.getInt(3))
                assertEquals(1, cur.getInt(4))
            }
    }

    @Test
    fun migrate_v36_to_v37_addsRunIdToHistory_andCreatesImportUploadedLedger() {
        // Build the v36 import tables from the real prior migration so the fixture cannot drift from the
        // schema MIGRATION_36_37 has to alter.
        Migrations.MIGRATION_35_36.migrate(db)
        db.execSQL(
            "INSERT INTO import_history (zipId, fileName, importedAt, total, uploaded, skipped, failed) " +
                "VALUES ('zip-a', 'takeout-1.zip', 100, 10, 8, 1, 1)"
        )

        Migrations.MIGRATION_36_37.migrate(db)

        // The existing summary survives and defaults to a null runId, so a run recorded without a ledger
        // is simply not undoable rather than lost.
        db.query("SELECT fileName, uploaded, runId FROM import_history WHERE zipId = 'zip-a'").use { cur ->
            assertTrue("expected the seeded run to survive the migration", cur.moveToFirst())
            assertEquals("takeout-1.zip", cur.getString(0))
            assertEquals(8, cur.getInt(1))
            assertTrue("runId defaults to NULL on existing runs", cur.isNull(2))
        }
        // The new column is writable: a run stamps its id so its ledger can be found again.
        db.execSQL("UPDATE import_history SET runId = 'run-1' WHERE zipId = 'zip-a'")
        db.query("SELECT runId FROM import_history WHERE zipId = 'zip-a'").use { cur ->
            assertTrue(cur.moveToFirst())
            assertEquals("run-1", cur.getString(0))
        }

        // The ledger table exists and accepts a row shaped exactly like ImportUploadedEntity, id omitted
        // so the autoincrement key assigns one and the nullable name/date left NULL.
        db.execSQL(
            "INSERT INTO import_uploaded (runId, linkId, sha1, name, dateMs, undone) " +
                "VALUES ('run-1', 'link-1', 'sha-1', 'a.jpg', 1000, 0)"
        )
        db.execSQL(
            "INSERT INTO import_uploaded (runId, linkId, sha1, undone) " +
                "VALUES ('run-1', 'link-2', 'sha-2', 0)"
        )
        db.query(
            "SELECT id, runId, linkId, sha1, name, dateMs, undone FROM import_uploaded WHERE linkId = 'link-1'"
        ).use { cur ->
            assertTrue("expected the inserted ledger row to be readable", cur.moveToFirst())
            assertTrue("the autoincrement key assigned an id", cur.getLong(0) > 0)
            assertEquals("run-1", cur.getString(1))
            assertEquals("link-1", cur.getString(2))
            assertEquals("sha-1", cur.getString(3))
            assertEquals("a.jpg", cur.getString(4))
            assertEquals(1000L, cur.getLong(5))
            assertEquals("a fresh upload is not undone", 0, cur.getInt(6))
        }
        // The second row left its nullable name/date NULL, distinct from an empty string.
        db.query("SELECT name, dateMs FROM import_uploaded WHERE linkId = 'link-2'").use { cur ->
            assertTrue(cur.moveToFirst())
            assertTrue("name left NULL where none was carried", cur.isNull(0))
            assertTrue("dateMs left NULL where none was carried", cur.isNull(1))
        }

        // pendingByRun / pendingCount read the rows not yet undone; markUndone flips a selection by linkId.
        db.query("SELECT COUNT(*) FROM import_uploaded WHERE runId = 'run-1' AND undone = 0").use { cur ->
            assertTrue(cur.moveToFirst())
            assertEquals("both fresh uploads are pending", 2, cur.getInt(0))
        }
        db.execSQL("UPDATE import_uploaded SET undone = 1 WHERE runId = 'run-1' AND linkId IN ('link-1')")
        db.query("SELECT COUNT(*) FROM import_uploaded WHERE runId = 'run-1' AND undone = 0").use { cur ->
            assertTrue(cur.moveToFirst())
            assertEquals("the undone upload dropped out of the pending set", 1, cur.getInt(0))
        }

        // The runId index scopes the ledger: another run's rows are their own set, so an undo of one run
        // never reaches into another.
        db.execSQL(
            "INSERT INTO import_uploaded (runId, linkId, sha1, undone) VALUES ('run-2', 'link-9', 'sha-9', 0)"
        )
        db.query("SELECT COUNT(*) FROM import_uploaded WHERE runId = 'run-1'").use { cur ->
            assertTrue(cur.moveToFirst())
            assertEquals("run-1 keeps its own two rows", 2, cur.getInt(0))
        }
        db.query("SELECT linkId FROM import_uploaded WHERE runId = 'run-2'").use { cur ->
            assertTrue("the other run's ledger is untouched", cur.moveToFirst())
            assertEquals("link-9", cur.getString(0))
        }
    }

    @Test
    fun migrate_v37_to_v38_addsAlreadyInDriveToLedger_andDropsPendingImport() {
        // Build the full v37 import state from the real prior migrations so the fixture cannot drift:
        // MIGRATION_34_35 creates pending_import, MIGRATION_35_36 the staged/history tables, and
        // MIGRATION_36_37 the import_uploaded ledger MIGRATION_37_38 has to alter.
        Migrations.MIGRATION_34_35.migrate(db)
        Migrations.MIGRATION_35_36.migrate(db)
        Migrations.MIGRATION_36_37.migrate(db)
        // A v37 ledger row, written before the new column exists, to check its default after the ALTER.
        db.execSQL(
            "INSERT INTO import_uploaded (runId, linkId, sha1, name, dateMs, undone) " +
                "VALUES ('run-1', 'link-1', 'sha-1', 'a.jpg', 1000, 0)"
        )
        // The dead table still carries a marker, so the drop is observed to remove a real table with data.
        db.execSQL(
            "INSERT INTO pending_import (zipId, entryName, linkId, importedAt) " +
                "VALUES ('zip-a', 'Takeout/Photos/a.jpg', 'link-1', 100)"
        )

        Migrations.MIGRATION_37_38.migrate(db)

        // The existing ledger row survives and its new alreadyInDrive flag defaults to 0, so an upload
        // recorded before the column existed stays a real upload an undo will trash.
        db.query(
            "SELECT linkId, undone, alreadyInDrive FROM import_uploaded WHERE linkId = 'link-1'"
        ).use { cur ->
            assertTrue("expected the seeded ledger row to survive the migration", cur.moveToFirst())
            assertEquals("link-1", cur.getString(0))
            assertEquals("a fresh upload is not undone", 0, cur.getInt(1))
            assertEquals("alreadyInDrive defaults to 0 on existing rows", 0, cur.getInt(2))
        }
        // The new column is writable: a deduped photo records the pre-existing link with the flag set, the
        // signal that lets an undo skip it.
        db.execSQL(
            "INSERT INTO import_uploaded (runId, linkId, sha1, undone, alreadyInDrive) " +
                "VALUES ('run-1', 'link-2', 'sha-2', 0, 1)"
        )
        db.query(
            "SELECT alreadyInDrive FROM import_uploaded WHERE runId = 'run-1' AND alreadyInDrive = 1"
        ).use { cur ->
            assertTrue("the deduped row is readable by its flag", cur.moveToFirst())
            assertEquals("a deduped row carries alreadyInDrive = 1", 1, cur.getInt(0))
        }

        // The dead pending_import table is gone, superseded by import_staged / import_uploaded, so a
        // lookup in sqlite_master finds no table of that name.
        db.query(
            "SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name = 'pending_import'"
        ).use { cur ->
            assertTrue(cur.moveToFirst())
            assertEquals("pending_import no longer exists after the migration", 0, cur.getInt(0))
        }
    }

    @Test
    fun migrate_v38_to_v39_addsAlbumColumnsToStaged_andCreatesAlbumMemberTable() {
        // Build the v38 import_staged shape from the real prior migration so the fixture cannot drift from
        // the schema MIGRATION_38_39 has to alter. MIGRATION_35_36 creates import_staged in the shape v38
        // still carries, since 36 through 38 never touch that table.
        Migrations.MIGRATION_35_36.migrate(db)
        // A v38 staged row, written before the new columns exist, to check their defaults after the ALTERs.
        db.execSQL(
            "INSERT INTO import_staged " +
                "(zipId, entryName, title, dateMs, lat, lng, description, sizeBytes, thumbPath, excluded, uploaded, stagedAt) " +
                "VALUES ('zip-a', 'Takeout/Photos/a.jpg', 'a.jpg', 1000, NULL, NULL, NULL, 2048, '/cache/a.jpg', 0, 0, 10)"
        )

        Migrations.MIGRATION_38_39.migrate(db)

        // The existing staged row survives; albumName defaults NULL (a timeline entry no album claims) and
        // alreadyInDrive defaults 0, so a row staged before the columns existed reads back as not yet badged.
        db.query(
            "SELECT entryName, albumName, alreadyInDrive FROM import_staged WHERE zipId = 'zip-a'"
        ).use { cur ->
            assertTrue("expected the seeded staged row to survive the migration", cur.moveToFirst())
            assertEquals("Takeout/Photos/a.jpg", cur.getString(0))
            assertTrue("albumName defaults to NULL on existing rows", cur.isNull(1))
            assertEquals("alreadyInDrive defaults to 0 on existing rows", 0, cur.getInt(2))
        }
        // Both new columns are writable: the stage pass records the source album and the already-in-Drive badge.
        db.execSQL(
            "UPDATE import_staged SET albumName = 'Wedding', alreadyInDrive = 1 " +
                "WHERE zipId = 'zip-a' AND entryName = 'Takeout/Photos/a.jpg'"
        )
        db.query("SELECT albumName, alreadyInDrive FROM import_staged WHERE zipId = 'zip-a'").use { cur ->
            assertTrue(cur.moveToFirst())
            assertEquals("Wedding", cur.getString(0))
            assertEquals(1, cur.getInt(1))
        }

        // The membership table exists and accepts a row shaped exactly like ImportAlbumMemberEntity, id
        // omitted so the autoincrement key assigns one. A photo in two albums is one edge per album.
        db.execSQL("INSERT INTO import_album_member (runId, albumName, linkId) VALUES ('run-1', 'Wedding', 'link-1')")
        db.execSQL("INSERT INTO import_album_member (runId, albumName, linkId) VALUES ('run-1', 'Holiday', 'link-1')")
        db.execSQL("INSERT INTO import_album_member (runId, albumName, linkId) VALUES ('run-1', 'Wedding', 'link-2')")
        db.query("SELECT COUNT(*), COUNT(DISTINCT id) FROM import_album_member WHERE runId = 'run-1'").use { cur ->
            assertTrue("expected the inserted membership edges to be readable", cur.moveToFirst())
            assertEquals(3, cur.getInt(0))
            assertEquals("each edge got its own auto-generated id", 3, cur.getInt(1))
        }

        // albumsForRun reads the run's distinct albums; linkIdsForAlbum reads one album's member links.
        db.query("SELECT COUNT(DISTINCT albumName) FROM import_album_member WHERE runId = 'run-1'").use { cur ->
            assertTrue(cur.moveToFirst())
            assertEquals("the run carried two distinct albums", 2, cur.getInt(0))
        }
        db.query(
            "SELECT DISTINCT linkId FROM import_album_member WHERE runId = 'run-1' AND albumName = 'Wedding' ORDER BY linkId"
        ).use { cur ->
            assertTrue(cur.moveToFirst())
            assertEquals("link-1", cur.getString(0))
            assertTrue(cur.moveToNext())
            assertEquals("Wedding holds both of its member links", "link-2", cur.getString(0))
        }

        // The runId index exists, the scope every album read filters on.
        db.query(
            "SELECT COUNT(*) FROM sqlite_master WHERE type = 'index' AND name = 'index_import_album_member_runId'"
        ).use { cur ->
            assertTrue(cur.moveToFirst())
            assertEquals("the runId index was created", 1, cur.getInt(0))
        }

        // clearForRun drops one run's edges and leaves another run's alone.
        db.execSQL("INSERT INTO import_album_member (runId, albumName, linkId) VALUES ('run-2', 'Trip', 'link-9')")
        db.execSQL("DELETE FROM import_album_member WHERE runId = 'run-1'")
        db.query("SELECT COUNT(*) FROM import_album_member WHERE runId = 'run-1'").use { cur ->
            assertTrue(cur.moveToFirst())
            assertEquals("run-1 edges cleared", 0, cur.getInt(0))
        }
        db.query("SELECT albumName FROM import_album_member WHERE runId = 'run-2'").use { cur ->
            assertTrue("the other run's edges remain", cur.moveToFirst())
            assertEquals("Trip", cur.getString(0))
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
