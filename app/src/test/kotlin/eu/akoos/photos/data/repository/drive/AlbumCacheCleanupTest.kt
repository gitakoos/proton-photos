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

package eu.akoos.photos.data.repository.drive

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import eu.akoos.photos.data.db.dao.TestDatabase
import eu.akoos.photos.data.db.entity.AlbumPhotoMembershipEntity
import eu.akoos.photos.data.db.entity.CloudAlbumEntity
import eu.akoos.photos.data.db.entity.PhotoListingEntity
import eu.akoos.photos.util.SQL_CHUNK_SIZE
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import me.proton.core.domain.entity.UserId
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The ownership rule behind [AlbumCacheCleanup], against a real database rather than mocked DAOs,
 * because the rule is a claim about what survives in three tables.
 *
 * An album shared WITH this user must give up its photo rows: they carry the OWNER's volumeId, are
 * parented to the album, and the timeline keeps them out only by testing that parent against the
 * membership table, so dropping the edges alone would surface another person's photos in this
 * user's own stream. An OWNED album must not: its photos live in the photos root and are the user's
 * own, and deleting them here would take them off their timeline. The second case is the one that
 * costs real photos when it regresses, so it is pinned from both directions.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AlbumCacheCleanupTest {

    private lateinit var db: TestDatabase
    private lateinit var cleanup: AlbumCacheCleanup

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, TestDatabase::class.java)
            .allowMainThreadQueries()
            .setQueryExecutor { it.run() }
            .build()
        cleanup = AlbumCacheCleanup(db.photoListingDao(), db.cloudAlbumDao(), db.albumPhotoMembershipDao())
    }

    @After
    fun tearDown() = db.close()

    private fun album(sharedBy: String?) = CloudAlbumEntity(
        linkId = ALBUM,
        name = "Trip",
        photoCount = 2,
        sharedByEmail = sharedBy,
        volumeId = if (sharedBy == null) OWN_VOLUME else OTHER_VOLUME,
    )

    /** A photo row as the album's member: the guest shape when [volumeId] is the owner's. */
    private fun photo(linkId: String, volumeId: String = OWN_VOLUME, parentLinkId: String? = null) =
        PhotoListingEntity(
            linkId = linkId,
            shareId = "share1",
            volumeId = volumeId,
            userId = "user1",
            captureTime = 1_000L,
            displayName = "$linkId.jpg",
            mimeType = "image/jpeg",
            sizeBytes = 1024L,
            revisionId = "rev1",
            parentLinkId = parentLinkId,
        )

    private suspend fun seed(sharedBy: String?, memberIds: List<String>, extra: List<PhotoListingEntity>) {
        db.cloudAlbumDao().upsertAll(listOf(album(sharedBy)))
        db.albumPhotoMembershipDao().upsertAll(memberIds.map { AlbumPhotoMembershipEntity(ALBUM, it) })
        val members = memberIds.map {
            if (sharedBy == null) photo(it) else photo(it, OTHER_VOLUME, ALBUM)
        }
        db.photoListingDao().upsertAll(members + extra)
    }

    private suspend fun storedLinkIds(): Set<String> =
        db.photoListingDao().observeAll("user1").first().map { it.linkId }.toSet()

    @Test
    fun `leaving an album shared with this user clears the photo rows it backed`() = runTest {
        seed(sharedBy = SHARER, memberIds = listOf("guest1", "guest2"), extra = listOf(photo("own1")))

        cleanup.dropCachedAlbum(USER, ALBUM)

        // The guest's copies go, so the timeline exclusion is not what was holding them back.
        assertEquals(setOf("own1"), storedLinkIds())
        assertTrue(db.albumPhotoMembershipDao().getPhotoLinkIds(ALBUM).isEmpty())
        assertTrue(db.cloudAlbumDao().getSharedWithMe().isEmpty())
    }

    @Test
    fun `deleting an owned album leaves every one of its photo rows in place`() = runTest {
        seed(sharedBy = null, memberIds = listOf("mine1", "mine2"), extra = listOf(photo("own1")))

        cleanup.dropCachedAlbum(USER, ALBUM)

        // The album container is gone, the user's photos are not: they live in the photos root and
        // belong to the timeline whether or not any album references them.
        assertEquals(setOf("mine1", "mine2", "own1"), storedLinkIds())
        assertTrue(db.albumPhotoMembershipDao().getPhotoLinkIds(ALBUM).isEmpty())
        assertTrue(db.cloudAlbumDao().getOwned().isEmpty())
    }

    @Test
    fun `an album with no cached row is treated as owned`() = runTest {
        // Nothing identifies the album, so the safe answer is the one that cannot destroy a photo.
        db.albumPhotoMembershipDao().upsertAll(listOf(AlbumPhotoMembershipEntity(ALBUM, "mine1")))
        db.photoListingDao().upsertAll(listOf(photo("mine1")))

        cleanup.dropCachedAlbum(USER, ALBUM)

        assertEquals(setOf("mine1"), storedLinkIds())
        assertTrue(db.albumPhotoMembershipDao().getPhotoLinkIds(ALBUM).isEmpty())
    }

    @Test
    fun `a shared album larger than one chunk gives up all of its photo rows`() = runTest {
        // Membership is unbounded, and a single IN list would exceed SQLite's host-variable cap.
        val memberIds = (1..SQL_CHUNK_SIZE + 25).map { "guest$it" }
        seed(sharedBy = SHARER, memberIds = memberIds, extra = listOf(photo("own1")))

        cleanup.dropCachedAlbum(USER, ALBUM)

        assertEquals(setOf("own1"), storedLinkIds())
    }

    private companion object {
        val USER = UserId("user1")
        const val ALBUM = "album1"
        const val SHARER = "sharer@example.com"
        const val OWN_VOLUME = "vol1"
        const val OTHER_VOLUME = "vol-owner"
    }
}
