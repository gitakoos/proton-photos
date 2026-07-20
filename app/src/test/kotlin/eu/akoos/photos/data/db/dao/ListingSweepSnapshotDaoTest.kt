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
import eu.akoos.photos.data.db.entity.PhotoListingEntity
import eu.akoos.photos.data.repository.drive.ListingPageVerdict
import eu.akoos.photos.data.repository.drive.listingPageVerdict
import eu.akoos.photos.data.repository.drive.removableListingIds
import eu.akoos.photos.util.forEachSqlChunk
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The refresh sweep's self-consuming candidate set, across the pass shapes a large library actually
 * produces.
 *
 * The three steps below reproduce what `PhotoStreamService.doRefreshCloudPhotos` does, in the same
 * order and against the same DAOs and the same [removableListingIds] rule — the walk itself cannot
 * be driven from a unit test, since it needs the network and the crypto stack. What is under test is
 * the property those steps are arranged to give: the delete set is a subset of a candidate list read
 * before the walk began, so nothing that appeared afterwards can be in it, no matter how the walk is
 * split across passes.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ListingSweepSnapshotDaoTest {

    private lateinit var db: TestDatabase
    private lateinit var snapshotDao: ListingSweepSnapshotDao
    private lateinit var photoListingDao: PhotoListingDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, TestDatabase::class.java)
            .allowMainThreadQueries()
            .setQueryExecutor { it.run() }
            .build()
        snapshotDao = db.listingSweepSnapshotDao()
        photoListingDao = db.photoListingDao()
    }

    @After
    fun tearDown() = db.close()

    /** A stream photo on the user's own volume: what the sweep is entitled to speak for. */
    private fun entity(linkId: String, volumeId: String = OWN_VOLUME, revisionId: String = "rev1") =
        PhotoListingEntity(
            linkId = linkId,
            shareId = "share1",
            volumeId = volumeId,
            userId = USER,
            captureTime = 1000L,
            displayName = "$linkId.jpg",
            mimeType = "image/jpeg",
            sizeBytes = 1024L,
            revisionId = revisionId,
            thumbnailUrl = null,
        )

    /** A row whose detail batch failed: the listing named it, but its detail never landed. */
    private fun stub(linkId: String) = entity(linkId, revisionId = "")

    /** Step one, and the one that only a pass starting FRESH is allowed to take. */
    private suspend fun startFreshPass(volumeId: String = OWN_VOLUME) {
        val candidates = photoListingDao.getSweepCandidateLinkIds(USER, volumeId)
        snapshotDao.replaceGeneration(USER, volumeId, candidates)
    }

    /** Step two, taken by every page of every pass, fresh or resumed. */
    private suspend fun listPage(vararg linkIds: String, volumeId: String = OWN_VOLUME) {
        snapshotDao.deleteListed(USER, volumeId, linkIds.toList())
    }

    /**
     * The page loop's decision chain, as `PhotoStreamService.doRefreshCloudPhotos` runs it: account
     * for each page against the set, then let the real [listingPageVerdict] say where the walk
     * stands. Returns whether the walk reached the server's empty page, which is the ONLY answer
     * allowed to credit the pass — the value the sweep is gated on.
     */
    private suspend fun walk(pages: List<List<String>>, volumeId: String = OWN_VOLUME): Boolean {
        var cursor: String? = null
        for (page in pages) {
            snapshotDao.deleteListed(USER, volumeId, page)
            when (val verdict = listingPageVerdict(page, cursor)) {
                is ListingPageVerdict.Advance -> cursor = verdict.nextCursor
                ListingPageVerdict.Exhausted -> return true
                ListingPageVerdict.CursorStalled -> return false
            }
        }
        // Pages ran out without the server ever answering empty: the shape of a pass cut short by a
        // rate limit, which is likewise not an end.
        return false
    }

    private data class PassResult(val paginationComplete: Boolean, val deleted: List<String>)

    /** One whole pass: walk the scripted pages, then sweep only if pagination actually ended. */
    private suspend fun refreshPass(
        pages: List<List<String>>,
        recentUploads: Set<String> = emptySet(),
    ): PassResult {
        val complete = walk(pages)
        return PassResult(complete, if (complete) sweepAtEndOfPagination(recentUploads) else emptyList())
    }

    /** Step three, taken once pagination reaches the end. Returns what it deleted. */
    private suspend fun sweepAtEndOfPagination(
        recentUploads: Set<String> = emptySet(),
        volumeId: String = OWN_VOLUME,
    ): List<String> {
        val stubIds = photoListingDao.getIncompleteRowLinkIds(USER).toSet()
        val unaccountedFor = snapshotDao.getGeneration(USER, volumeId)
        val toDelete = removableListingIds(unaccountedFor, recentUploads, stubIds)
        toDelete.forEachSqlChunk { photoListingDao.deleteByLinkIds(USER, it) }
        snapshotDao.clearGeneration(USER, volumeId)
        return toDelete
    }

    @Test
    fun `a fresh pass materialises the candidates that exist before its walk starts`() = runTest {
        photoListingDao.upsertAll(listOf(entity("a"), entity("b")))

        startFreshPass()

        assertEquals(listOf("a", "b"), snapshotDao.getGeneration(USER, OWN_VOLUME).sorted())
    }

    @Test
    fun `a resumed pass reuses the fresh pass generation instead of re-reading the candidates`() = runTest {
        // The server still holds a and b; c was deleted on another client.
        photoListingDao.upsertAll(listOf(entity("a"), entity("b"), entity("c")))

        // Pass one starts fresh, accounts for the first page, then is cut short by a rate limit.
        startFreshPass()
        listPage("a")

        // Pass two resumes the saved cursor. It takes no snapshot — that is the invariant — and
        // picks the walk up where it stopped.
        listPage("b")
        val deleted = sweepAtEndOfPagination()

        assertEquals("only the photo no page of the whole walk returned is deleted", listOf("c"), deleted)
        assertNotNull("a was accounted for by the first pass", photoListingDao.getByLinkId("a"))
        assertNotNull("b was accounted for by the second", photoListingDao.getByLinkId("b"))
        assertNull(photoListingDao.getByLinkId("c"))
    }

    @Test
    fun `re-snapshotting on a resumed pass would delete a photo the earlier pages accounted for`() = runTest {
        // Same chain as above, with the invariant broken, to show what it is holding back. The
        // pages a resumed pass does not re-fetch are the ones it can no longer account for, so
        // refilling the set re-offers them and the sweep reads their absence as a deletion.
        photoListingDao.upsertAll(listOf(entity("a"), entity("b"), entity("c")))
        startFreshPass()
        listPage("a")

        startFreshPass() // the mistake: a resumed pass re-reading the candidate list
        listPage("b")
        val deleted = sweepAtEndOfPagination()

        assertEquals(
            "a is still on the server, and re-snapshotting is what puts it in the delete set",
            listOf("a", "c"),
            deleted.sorted(),
        )
    }

    @Test
    fun `a photo uploaded after the snapshot can never be in the delete set`() = runTest {
        photoListingDao.upsertAll(listOf(entity("a"), entity("b")))
        startFreshPass()

        // Another client uploads mid-walk. The row lands, but this walk is already past the top of
        // the timeline and never lists it — the case that makes "diff the candidate list at the
        // end" data loss rather than a sweep.
        photoListingDao.upsertAll(listOf(entity("uploaded-mid-walk")))
        listPage("a", "b")
        val deleted = sweepAtEndOfPagination()

        assertTrue("the walk accounted for everything it was given", deleted.isEmpty())
        assertNotNull(
            "a row that was not a candidate cannot become one",
            photoListingDao.getByLinkId("uploaded-mid-walk"),
        )
    }

    @Test
    fun `a photo deleted on the server is pruned once a fresh-then-resumed chain reaches the end`() = runTest {
        photoListingDao.upsertAll(listOf(entity("p1"), entity("p2"), entity("p3"), entity("gone")))

        // Three passes to walk one library: fresh, resumed, resumed. The first two end mid-listing
        // and prune nothing, which is what a library too large to list in one pass looks like.
        startFreshPass()
        listPage("p1")
        listPage("p2")
        val deleted = run {
            listPage("p3")
            sweepAtEndOfPagination()
        }

        assertEquals(listOf("gone"), deleted)
        assertNull("the deletion finally reaches the device", photoListingDao.getByLinkId("gone"))
        assertEquals(
            "and nothing else went with it",
            listOf("p1", "p2", "p3"),
            listOf("p1", "p2", "p3").filter { photoListingDao.getByLinkId(it) != null },
        )
    }

    @Test
    fun `a torn snapshot under-deletes rather than over-deletes`() = runTest {
        // Every row is gone from the server, but the fill only got two of the three in before it
        // failed. The third is simply never offered, so the worst a torn set can do is leave a
        // stale row on screen until the next pass — never delete a photo it was not asked about.
        photoListingDao.upsertAll(listOf(entity("a"), entity("b"), entity("c")))
        snapshotDao.replaceGeneration(USER, OWN_VOLUME, listOf("a", "b"))

        val deleted = sweepAtEndOfPagination()

        assertEquals(listOf("a", "b"), deleted.sorted())
        assertNotNull("the candidate the torn fill missed is left alone", photoListingDao.getByLinkId("c"))
    }

    @Test
    fun `a row the listing returned is accounted for even when its detail never landed`() = runTest {
        // The set shrinks by what the LISTING returned, so a detail batch that fails afterwards
        // cannot turn a present photo into a missing one. Both rows below were listed; only one
        // of them has a detail row to show for it.
        photoListingDao.upsertAll(listOf(entity("complete"), stub("detail-failed")))
        startFreshPass()

        listPage("complete", "detail-failed")
        val deleted = sweepAtEndOfPagination()

        assertTrue("a failed detail batch is not evidence that a photo is gone", deleted.isEmpty())
        assertNotNull(photoListingDao.getByLinkId("detail-failed"))
    }

    @Test
    fun `a recent upload the listing has not indexed yet is kept`() = runTest {
        // This app's own upload writes its row at once, but the photo-stream index takes seconds to
        // catch up, so a walk crossing that gap lists everything except the newest photo.
        photoListingDao.upsertAll(listOf(entity("a"), entity("just-uploaded")))
        startFreshPass()

        listPage("a")
        val deleted = sweepAtEndOfPagination(recentUploads = setOf("just-uploaded"))

        assertTrue(deleted.isEmpty())
        assertNotNull(photoListingDao.getByLinkId("just-uploaded"))
    }

    @Test
    fun `a repeated cursor leaves the pass incomplete and sweeps nothing`() = runTest {
        photoListingDao.upsertAll(listOf(entity("p1"), entity("p2"), entity("p3")))
        startFreshPass()

        // The server answers the p2 cursor with a page ending on p2 again. The walk has to stop or
        // it would fetch that page forever, but stopping is all it may conclude.
        val pass = refreshPass(listOf(listOf("p1", "p2"), listOf("p2")))

        assertFalse("a stalled cursor is not the server saying the library ended", pass.paginationComplete)
        assertTrue("so the pass has established nothing it could delete on", pass.deleted.isEmpty())
        assertNotNull("the photo the walk never reached is untouched", photoListingDao.getByLinkId("p3"))
        assertEquals(
            "and it is still owed an answer, which a later pass will supply",
            listOf("p3"),
            snapshotDao.getGeneration(USER, OWN_VOLUME),
        )
    }

    @Test
    fun `an empty page credits completion and sweeps normally`() = runTest {
        // The natural end, unchanged: the sweep must still fire, or a deletion made on another
        // client would never reach the device.
        photoListingDao.upsertAll(listOf(entity("p1"), entity("p2"), entity("gone")))
        startFreshPass()

        val pass = refreshPass(listOf(listOf("p1", "p2"), emptyList()))

        assertTrue(pass.paginationComplete)
        assertEquals(listOf("gone"), pass.deleted)
        assertNull(photoListingDao.getByLinkId("gone"))
        assertNotNull(photoListingDao.getByLinkId("p1"))
        assertNotNull(photoListingDao.getByLinkId("p2"))
    }

    @Test
    fun `the photos past a truncation point survive a repeated-cursor pass`() = runTest {
        // The regression this pair of exits was separated for. The walk stalls two pages in, leaving
        // p5..p8 unlisted; because the stall left the loop by the same door as the natural end, the
        // pass used to be credited and the sweep took that whole unlisted tail as its delete set —
        // every photo past the truncation point, gone from a library that still holds them.
        val library = (1..8).map { "p$it" }
        photoListingDao.upsertAll(library.map { entity(it) })
        startFreshPass()

        val pass = refreshPass(listOf(listOf("p1", "p2"), listOf("p3", "p4"), listOf("p4")))

        assertFalse(pass.paginationComplete)
        assertTrue("nothing may be deleted on the strength of a truncated walk", pass.deleted.isEmpty())
        assertEquals("every photo is still on the device", library, library.filter {
            photoListingDao.getByLinkId(it) != null
        })
        assertEquals(
            "the unlisted tail stays unaccounted for rather than being written off",
            listOf("p5", "p6", "p7", "p8"),
            snapshotDao.getGeneration(USER, OWN_VOLUME).sorted(),
        )
    }

    @Test
    fun `a later pass sweeps what the stalled one could not`() = runTest {
        // The stall costs a pass, not the prune: the set survives untouched, so once the backend
        // serves the page properly the same generation finishes the job.
        photoListingDao.upsertAll(listOf(entity("p1"), entity("p2"), entity("gone")))
        startFreshPass()

        val stalled = refreshPass(listOf(listOf("p1"), listOf("p1")))
        assertFalse(stalled.paginationComplete)

        // The retry re-lists p1 (a no-op against the set), gets p2, then the empty page.
        val recovered = refreshPass(listOf(listOf("p1"), listOf("p2"), emptyList()))

        assertTrue(recovered.paginationComplete)
        assertEquals(listOf("gone"), recovered.deleted)
        assertNotNull(photoListingDao.getByLinkId("p2"))
    }

    @Test
    fun `a generation belongs to one volume only`() = runTest {
        // The walk speaks for the user's own volume; a photo from an album another user shared
        // keeps the OWNER's volumeId, so neither generation may reach into the other.
        snapshotDao.replaceGeneration(USER, OWN_VOLUME, listOf("own1", "own2"))
        snapshotDao.replaceGeneration(USER, OTHER_VOLUME, listOf("shared1"))

        listPage("own1", "shared1")

        assertEquals(listOf("own2"), snapshotDao.getGeneration(USER, OWN_VOLUME))
        assertEquals(
            "a page of the own-volume walk cannot account for another volume's row",
            listOf("shared1"),
            snapshotDao.getGeneration(USER, OTHER_VOLUME),
        )

        snapshotDao.clearGeneration(USER, OWN_VOLUME)
        assertEquals(listOf("shared1"), snapshotDao.getGeneration(USER, OTHER_VOLUME))
    }

    @Test
    fun `clearForUser drops every generation the signed-out user holds`() = runTest {
        snapshotDao.replaceGeneration(USER, OWN_VOLUME, listOf("a"))
        snapshotDao.replaceGeneration(USER, OTHER_VOLUME, listOf("b"))
        snapshotDao.replaceGeneration(OTHER_USER, OWN_VOLUME, listOf("c"))

        snapshotDao.clearForUser(USER)

        assertTrue(snapshotDao.getGeneration(USER, OWN_VOLUME).isEmpty())
        assertTrue(snapshotDao.getGeneration(USER, OTHER_VOLUME).isEmpty())
        assertEquals(
            "another signed-in account keeps its own generation",
            listOf("c"),
            snapshotDao.getGeneration(OTHER_USER, OWN_VOLUME),
        )
    }

    @Test
    fun `replacing a generation leaves nothing of the previous one behind`() = runTest {
        snapshotDao.replaceGeneration(USER, OWN_VOLUME, listOf("old1", "old2"))

        snapshotDao.replaceGeneration(USER, OWN_VOLUME, listOf("new1"))

        assertEquals(listOf("new1"), snapshotDao.getGeneration(USER, OWN_VOLUME))
    }

    private companion object {
        const val USER = "user1"
        const val OTHER_USER = "user2"
        const val OWN_VOLUME = "vol1"
        const val OTHER_VOLUME = "vol-owner"
    }
}
