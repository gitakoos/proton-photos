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
    private lateinit var membershipDao: AlbumPhotoMembershipDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, TestDatabase::class.java)
            .allowMainThreadQueries()
            .setQueryExecutor { it.run() }
            .build()
        dao = db.photoListingDao()
        membershipDao = db.albumPhotoMembershipDao()
    }

    @After
    fun tearDown() = db.close()

    private fun entity(
        linkId: String,
        userId: String = "user1",
        captureTime: Long = 1000L,
        volumeId: String = OWN_VOLUME,
        parentLinkId: String? = null,
        isChildOfAlbum: Boolean = false,
    ) = PhotoListingEntity(
        linkId = linkId,
        shareId = "share1",
        volumeId = volumeId,
        userId = userId,
        captureTime = captureTime,
        displayName = "$linkId.jpg",
        mimeType = "image/jpeg",
        sizeBytes = 1024L,
        revisionId = "rev1",
        thumbnailUrl = null,
        parentLinkId = parentLinkId,
        isChildOfAlbum = isChildOfAlbum,
    )

    /**
     * A photo in an album another user shared: stored under this user's own userId, but on the
     * OWNER's volume and parented to the album rather than to this user's photos root.
     */
    private fun sharedAlbumEntity(linkId: String, userId: String = "user1") =
        entity(linkId, userId = userId, volumeId = OTHER_VOLUME, parentLinkId = ALBUM, isChildOfAlbum = true)

    /**
     * A photo someone contributed to an album this user shared out. The copy is on this user's OWN
     * volume, parented to the album, and the volume's photo listing never returns it.
     */
    private fun contributedEntity(linkId: String, captureTime: Long = 1000L) =
        entity(linkId, captureTime = captureTime, parentLinkId = ALBUM, isChildOfAlbum = true)

    /**
     * A photo the user backed up and then added to an album of their own. Adding rewraps the
     * passphrase but leaves the photo parented to the photos root, so it is still a stream photo.
     */
    private fun ownAlbumEntity(linkId: String, captureTime: Long = 1000L) =
        entity(linkId, captureTime = captureTime, parentLinkId = PHOTOS_ROOT)

    /** An own-volume video with no duration recovered yet, the duration walk's input shape. */
    private fun video(linkId: String) = entity(linkId).copy(mimeType = "video/mp4")

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

        dao.deleteByLinkIds("user1", listOf("link1", "link3"))

        val result = dao.observeAll("user1").first()
        assertEquals(1, result.size)
        assertEquals("link2", result.first().linkId)
    }

    @Test
    fun `deleteByLinkIds never reaches a row belonging to another account`() = runTest {
        // An account switch leaves the previous account's rows in place, so a delete driven by one
        // account's server response can name a linkId that now belongs to the other. Unscoped, it
        // would evict that row from a library its own account never asked to change.
        dao.upsertAll(listOf(entity("link1", "user1"), entity("link2", "user2")))

        dao.deleteByLinkIds("user1", listOf("link1", "link2"))

        assertTrue(dao.observeAll("user1").first().isEmpty())
        assertEquals(listOf("link2"), dao.observeAll("user2").first().map { it.linkId })
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

    @Test
    fun `observeOwnStream returns stream photos and skips a direct album child`() = runTest {
        // The three shapes that share this table. Only the last one lives inside the album; the
        // own-album photo is still parented to the photos root and belongs on the timeline.
        dao.upsertAll(listOf(
            entity("ordinary", captureTime = 300L),
            ownAlbumEntity("in-my-album", captureTime = 200L),
            contributedEntity("contributed", captureTime = 100L),
        ))
        membershipDao.replaceAllForAlbum(ALBUM, listOf("in-my-album", "contributed"))

        val stream = dao.observeOwnStream("user1").first()

        assertEquals(listOf("ordinary", "in-my-album"), stream.map { it.linkId })
    }

    @Test
    fun `observeOwnStreamLite skips a direct album child too`() = runTest {
        // The projection the timeline actually binds, held to the same rule as the full read.
        dao.upsertAll(listOf(
            entity("ordinary", captureTime = 300L),
            ownAlbumEntity("in-my-album", captureTime = 200L),
            contributedEntity("contributed", captureTime = 100L),
        ))

        val stream = dao.observeOwnStreamLite("user1").first()

        assertEquals(listOf("ordinary", "in-my-album"), stream.map { it.linkId })
    }

    @Test
    fun `observeOwnStreamPickerRows returns stream photos newest first and skips a direct album child`() = runTest {
        // The widget picker's pool, held to the same own-stream rule: a photo that lives only inside
        // an album someone shared is never offered as widget content, and the order is the query's.
        dao.upsertAll(listOf(
            entity("ordinary", captureTime = 300L),
            ownAlbumEntity("in-my-album", captureTime = 200L),
            contributedEntity("contributed", captureTime = 100L),
            sharedAlbumEntity("shared"),
        ))

        val rows = dao.observeOwnStreamPickerRows("user1").first()

        assertEquals(listOf("ordinary", "in-my-album"), rows.map { it.linkId })
    }

    @Test
    fun `observeOwnStreamPickerRows carries the decrypted thumbnail url`() = runTest {
        // The one column the timeline projection drops and this one must not: the picker has no primed
        // in-memory store to read an already-decrypted thumbnail from.
        dao.upsertAll(listOf(
            entity("warm").copy(thumbnailUrl = "file:///cache/thumb_warm.jpg"),
            entity("cold", captureTime = 100L),
        ))

        val rows = dao.observeOwnStreamPickerRows("user1").first()

        assertEquals("file:///cache/thumb_warm.jpg", rows.first { it.linkId == "warm" }.thumbnailUrl)
        assertNull(rows.first { it.linkId == "cold" }.thumbnailUrl)
    }

    @Test
    fun `observeOwnStreamPickerRows filters by userId`() = runTest {
        dao.upsertAll(listOf(entity("link1", "user1"), entity("link2", "user2")))

        assertEquals(listOf("link1"), dao.observeOwnStreamPickerRows("user1").first().map { it.linkId })
        assertEquals(listOf("link2"), dao.observeOwnStreamPickerRows("user2").first().map { it.linkId })
    }

    @Test
    fun `observeOwnStream is unchanged when the album membership edges are gone`() = runTest {
        // The regression this column exists for. While the rule was "parent is an album that has at
        // least one membership edge", an album with no cached edges named no album at all, and every
        // photo inside it surfaced on the owner's timeline.
        dao.upsertAll(listOf(
            entity("ordinary", captureTime = 300L),
            ownAlbumEntity("in-my-album", captureTime = 200L),
            contributedEntity("contributed", captureTime = 100L),
        ))
        membershipDao.replaceAllForAlbum(ALBUM, listOf("in-my-album", "contributed"))
        val withEdges = dao.observeOwnStream("user1").first().map { it.linkId }

        membershipDao.clearAll()

        assertEquals(withEdges, dao.observeOwnStream("user1").first().map { it.linkId })
        assertEquals(listOf("ordinary", "in-my-album"), withEdges)
    }

    @Test
    fun `observeOwnStream filters by userId`() = runTest {
        dao.upsertAll(listOf(entity("link1", "user1"), entity("link2", "user2")))

        assertEquals(listOf("link1"), dao.observeOwnStream("user1").first().map { it.linkId })
        assertEquals(listOf("link2"), dao.observeOwnStream("user2").first().map { it.linkId })
    }

    @Test
    fun `getSweepCandidateLinkIds returns the user's stream rows on that volume`() = runTest {
        dao.upsertAll(listOf(entity("link1"), entity("link2")))

        val ids = dao.getSweepCandidateLinkIds("user1", OWN_VOLUME)

        assertEquals(setOf("link1", "link2"), ids.toSet())
    }

    @Test
    fun `getSweepCandidateLinkIds excludes a shared-album row on another volume`() = runTest {
        // A shared-album row carries this user's userId, so a userId-only scope would offer it up;
        // the keep-set is built from the own-volume stream walk and can never contain it, so it
        // would be deleted on every clean pass.
        dao.upsertAll(listOf(entity("own1"), sharedAlbumEntity("shared1")))

        val ids = dao.getSweepCandidateLinkIds("user1", OWN_VOLUME)

        assertEquals(listOf("own1"), ids)
    }

    @Test
    fun `getSweepCandidateLinkIds excludes a direct album child on the user's own volume`() = runTest {
        // The volume scope alone cannot save this one: a photo contributed to an album this user
        // shared out is copied onto their OWN volume. The volume listing that builds the keep-set
        // returns stream photos only, so as a candidate it would be swept on every full refresh.
        dao.upsertAll(listOf(entity("own1"), ownAlbumEntity("in-my-album"), contributedEntity("contributed")))

        val ids = dao.getSweepCandidateLinkIds("user1", OWN_VOLUME)

        assertEquals(setOf("own1", "in-my-album"), ids.toSet())
    }

    @Test
    fun `getSweepCandidateLinkIds filters by userId as well as volume`() = runTest {
        dao.upsertAll(listOf(entity("link1", "user1"), entity("link2", "user2")))

        assertEquals(listOf("link1"), dao.getSweepCandidateLinkIds("user1", OWN_VOLUME))
        assertEquals(listOf("link2"), dao.getSweepCandidateLinkIds("user2", OWN_VOLUME))
    }

    @Test
    fun `getUngeocoded returns the user's un-checked rows on their own volume`() = runTest {
        dao.upsertAll(listOf(entity("link1"), entity("link2").copy(gpsChecked = true)))

        val rows = dao.getUngeocoded("user1", OWN_VOLUME, 10)

        assertEquals(listOf("link1"), rows.map { it.linkId })
    }

    @Test
    fun `getUngeocoded skips a shared-album row on another volume`() = runTest {
        // A shared-album row's revision lives on the owner's volume and never resolves for this
        // user, so it can never be marked checked: unscoped, the walk would re-offer it forever.
        dao.upsertAll(listOf(entity("own1"), sharedAlbumEntity("shared1")))

        val rows = dao.getUngeocoded("user1", OWN_VOLUME, 10)

        assertEquals(listOf("own1"), rows.map { it.linkId })
    }

    @Test
    fun `getVideosMissingDuration returns the user's duration-less videos on their own volume`() = runTest {
        dao.upsertAll(listOf(
            video("video1"),
            video("video2").copy(durationMs = 5_000L),
            entity("photo1"),
        ))

        val rows = dao.getVideosMissingDuration("user1", OWN_VOLUME, 10)

        assertEquals(listOf("video1"), rows.map { it.linkId })
    }

    @Test
    fun `getVideosMissingDuration skips a shared-album video on another volume`() = runTest {
        // Same trap as the geocode walk: durationMs can never be written for a row whose revision
        // this user cannot fetch, and durationMs IS NULL is the query's own bound.
        dao.upsertAll(listOf(
            video("own-video"),
            sharedAlbumEntity("shared-video").copy(mimeType = "video/mp4"),
        ))

        val rows = dao.getVideosMissingDuration("user1", OWN_VOLUME, 10)

        assertEquals(listOf("own-video"), rows.map { it.linkId })
    }

    private companion object {
        const val OWN_VOLUME = "vol1"
        const val OTHER_VOLUME = "vol-owner"
        const val ALBUM = "album1"
        const val PHOTOS_ROOT = "photos-root"
    }
}
