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

import eu.akoos.photos.data.db.dao.PhotoListingDao
import eu.akoos.photos.data.db.entity.ThumbnailUrlSeed
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import me.proton.core.domain.entity.UserId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The thumbnail URL store the timeline cells read their decrypted thumbnails from. A decrypt writes
 * one entry in place and is readable at once; readers are told through [ThumbnailUrlStore.urls],
 * which publishes a new view per coalesced change rather than a copy of the map per decrypt. The seed
 * fills only what no live decrypt holds and only from files still on disk, and a sign-out mid-seed
 * leaves nothing behind. The DAO is a mock and the clock is the test scheduler's.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ThumbnailUrlStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val dao = mockk<PhotoListingDao>()
    private val user = UserId("user-1")

    /** The store on the test's background scope, standing in for the injected app scope: the virtual
     *  clock, and cancelled when the test body ends so no publish window outlives the test. */
    private fun TestScope.store() = ThumbnailUrlStore(dao, backgroundScope)

    /** The virtual time of every publish [ThumbnailUrlStore.urls] makes after the value it starts
     *  with, recorded as it lands. Times rather than values: every view reads the same live entries. */
    private fun TestScope.publishesOf(store: ThumbnailUrlStore): List<Long> {
        val publishedAt = mutableListOf<Long>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            store.urls.drop(1).collect { publishedAt += testScheduler.currentTime }
        }
        return publishedAt
    }

    /** Runs out any open publish window. Bounded, so a window that never closes fails an assertion
     *  rather than spinning the scheduler. */
    private fun TestScope.settle() = advanceTimeBy(ThumbnailUrlStore.PUBLISH_WINDOW_MS * 3)

    private fun url(linkId: String) = "file:///data/cache/thumbnails/thumb_$linkId.jpg"

    private fun fileUrl(file: File) = "file://${file.absolutePath}"

    // region put

    @Test
    fun `a put is readable at once and published`() = runTest {
        val store = store()
        val published = publishesOf(store)

        store.put("L1", url("L1"))

        assertEquals(url("L1"), store.urls.value["L1"])
        assertEquals("the first decrypt after a quiet spell goes out at once", listOf(0L), published)
    }

    @Test
    fun `re-putting the same url publishes nothing`() = runTest {
        val store = store()
        val published = publishesOf(store)

        store.put("L1", url("L1"))
        // An equal URL built afresh, as a warm-cache re-point produces it.
        store.put("L1", String(url("L1").toCharArray()))
        settle()

        assertEquals(1, published.size)
    }

    @Test
    fun `a burst of decrypts is published once per window, and the last one lands`() = runTest {
        val store = store()
        val published = publishesOf(store)
        val window = ThumbnailUrlStore.PUBLISH_WINDOW_MS

        repeat(1_000) { i ->
            store.put("L$i", url("L$i"))
            advanceTimeBy(1)
        }
        settle()

        assertTrue("${published.size} publishes for 1000 decrypts", published.size <= 1_000 / window + 2)
        assertTrue(
            "publishes closer than the window: $published",
            published.zipWithNext().all { (a, b) -> b - a >= window },
        )
        assertTrue("the last decrypt (t=999) is followed by a publish", published.last() >= 999L)
        assertEquals(1_000, store.urls.value.size)
        assertEquals(url("L999"), store.urls.value["L999"])
    }

    @Test
    fun `a published view reads the live entries and is a new value each time`() = runTest {
        val store = store()
        publishesOf(store)

        store.put("L1", url("L1"))
        val first = store.urls.value
        // Inside the window: not yet published, but already readable through the earlier view.
        store.put("L2", url("L2"))
        assertEquals(url("L2"), first["L2"])

        settle()
        val second = store.urls.value
        assertNotEquals("a publish must read as a change", first, second)
        assertEquals(url("L2"), second["L2"])
    }

    // endregion

    // region seed

    @Test
    fun `seed fills from files still on disk and never overrides a live decrypt`() = runTest {
        val onDisk = tmp.newFile("thumb_A.jpg").apply { writeBytes(byteArrayOf(1)) }
        val empty = tmp.newFile("thumb_B.jpg")
        val gone = File(tmp.root, "thumb_C.jpg")
        val stale = tmp.newFile("thumb_D.jpg").apply { writeBytes(byteArrayOf(1)) }
        coEvery { dao.getThumbnailUrlSeed(user.id) } returns listOf(
            ThumbnailUrlSeed("A", fileUrl(onDisk)),
            ThumbnailUrlSeed("B", fileUrl(empty)),
            ThumbnailUrlSeed("C", fileUrl(gone)),
            ThumbnailUrlSeed("D", fileUrl(stale)),
            ThumbnailUrlSeed("E", null),
            ThumbnailUrlSeed("F", "https://example.invalid/thumb_F"),
        )
        val store = store()
        store.put("D", url("D"))
        val published = publishesOf(store)

        store.seed(user)

        val urls = store.urls.value
        assertEquals(fileUrl(onDisk), urls["A"])
        assertNull("an empty file is not a thumbnail", urls["B"])
        assertNull("a cleared cache leaves nothing to point at", urls["C"])
        assertEquals("the live decrypt outranks the older seed row", url("D"), urls["D"])
        assertNull(urls["E"])
        assertEquals("a non-file URL has nothing local to check", "https://example.invalid/thumb_F", urls["F"])
        assertEquals("the whole seed is one publish", 1, published.size)
    }

    @Test
    fun `a seed with nothing to add publishes nothing`() = runTest {
        coEvery { dao.getThumbnailUrlSeed(user.id) } returns emptyList()
        val store = store()
        val published = publishesOf(store)

        store.seed(user)

        assertTrue(store.urls.value.isEmpty())
        assertTrue(published.isEmpty())
    }

    @Test
    fun `a sign-out during the seed read leaves nothing behind`() = runTest {
        val onDisk = tmp.newFile("thumb_A.jpg").apply { writeBytes(byteArrayOf(1)) }
        val store = store()
        coEvery { dao.getThumbnailUrlSeed(user.id) } coAnswers {
            store.clear()
            listOf(ThumbnailUrlSeed("A", fileUrl(onDisk)))
        }

        store.seed(user)

        assertTrue(store.urls.value.isEmpty())
    }

    // endregion

    // region remove and clear

    @Test
    fun `remove drops evicted entries, and ids it does not hold publish nothing`() = runTest {
        val store = store()
        val published = publishesOf(store)
        store.put("A", url("A"))
        store.put("B", url("B"))
        settle()
        val settled = published.size

        store.remove(listOf("unknown"))
        settle()
        assertEquals(settled, published.size)

        store.remove(listOf("A", "unknown"))
        settle()
        assertEquals(settled + 1, published.size)
        assertNull(store.urls.value["A"])
        assertEquals(url("B"), store.urls.value["B"])
    }

    @Test
    fun `clear empties the store and publishes at once`() = runTest {
        val store = store()
        val published = publishesOf(store)
        store.put("A", url("A"))
        val beforeClear = published.size

        store.clear()

        assertTrue(store.urls.value.isEmpty())
        assertEquals("no window wait on sign-out", beforeClear + 1, published.size)

        store.clear()
        assertEquals("clearing an empty store says nothing", beforeClear + 1, published.size)
    }

    // endregion
}
