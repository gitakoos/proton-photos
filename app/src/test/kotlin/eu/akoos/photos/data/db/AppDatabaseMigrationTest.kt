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
