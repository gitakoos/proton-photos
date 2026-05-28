package eu.akoos.photos.data.db.dao

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import eu.akoos.photos.data.db.entity.PhotoListingEntity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PhotoListingDaoTest {

    private lateinit var db: TestDatabase
    private lateinit var dao: PhotoListingDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, TestDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.photoListingDao()
    }

    @After
    fun tearDown() = db.close()

    private fun entity(
        linkId: String,
        userId: String = "user1",
        captureTime: Long = 1000L,
    ) = PhotoListingEntity(
        linkId = linkId,
        shareId = "share1",
        volumeId = "vol1",
        userId = userId,
        captureTime = captureTime,
        displayName = "$linkId.jpg",
        mimeType = "image/jpeg",
        sizeBytes = 1024L,
        revisionId = "rev1",
        thumbnailUrl = null,
    )

    @Test
    fun `upsertAll and observeAll returns inserted entities for user`() = runTest {
        dao.upsertAll(listOf(entity("link1"), entity("link2")))

        val result = dao.observeAll("user1").first()

        assertEquals(2, result.size)
        assertTrue(result.any { it.linkId == "link1" })
        assertTrue(result.any { it.linkId == "link2" })
    }

    @Test
    fun `observeAll filters by userId`() = runTest {
        dao.upsertAll(listOf(entity("link1", "user1"), entity("link2", "user2")))

        val forUser1 = dao.observeAll("user1").first()
        val forUser2 = dao.observeAll("user2").first()

        assertEquals(1, forUser1.size)
        assertEquals("link1", forUser1.first().linkId)
        assertEquals(1, forUser2.size)
        assertEquals("link2", forUser2.first().linkId)
    }

    @Test
    fun `observeAll returns entities sorted by captureTime descending`() = runTest {
        dao.upsertAll(listOf(
            entity("old", captureTime = 100L),
            entity("new", captureTime = 900L),
            entity("mid", captureTime = 500L),
        ))

        val result = dao.observeAll("user1").first()

        assertEquals("new", result[0].linkId)
        assertEquals("mid", result[1].linkId)
        assertEquals("old", result[2].linkId)
    }

    @Test
    fun `upsert replaces existing entity with same linkId`() = runTest {
        dao.upsertAll(listOf(entity("link1").copy(displayName = "old.jpg")))
        dao.upsertAll(listOf(entity("link1").copy(displayName = "new.jpg")))

        val result = dao.observeAll("user1").first()

        assertEquals(1, result.size)
        assertEquals("new.jpg", result.first().displayName)
    }

    @Test
    fun `getByLinkId returns entity when present`() = runTest {
        dao.upsertAll(listOf(entity("link1")))

        val result = dao.getByLinkId("link1")

        assertEquals("link1", result?.linkId)
    }

    @Test
    fun `getByLinkId returns null when absent`() = runTest {
        val result = dao.getByLinkId("nonexistent")
        assertNull(result)
    }

    @Test
    fun `deleteByLinkIds removes specified entries`() = runTest {
        dao.upsertAll(listOf(entity("link1"), entity("link2"), entity("link3")))

        dao.deleteByLinkIds(listOf("link1", "link3"))

        val result = dao.observeAll("user1").first()
        assertEquals(1, result.size)
        assertEquals("link2", result.first().linkId)
    }

    @Test
    fun `deleteAll removes only entries for that user`() = runTest {
        dao.upsertAll(listOf(entity("link1", "user1"), entity("link2", "user2")))

        dao.deleteAll("user1")

        assertTrue(dao.observeAll("user1").first().isEmpty())
        assertEquals(1, dao.observeAll("user2").first().size)
    }

    @Test
    fun `getAllLinkIds returns all linkIds for user`() = runTest {
        dao.upsertAll(listOf(entity("link1"), entity("link2"), entity("link3")))

        val ids = dao.getAllLinkIds("user1")

        assertEquals(setOf("link1", "link2", "link3"), ids.toSet())
    }
}
