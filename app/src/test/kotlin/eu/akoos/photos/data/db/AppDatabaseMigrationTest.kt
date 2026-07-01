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
