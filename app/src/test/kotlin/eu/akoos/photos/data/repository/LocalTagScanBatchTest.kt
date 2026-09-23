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

package eu.akoos.photos.data.repository

import eu.akoos.photos.data.db.entity.LocalTagEntity
import eu.akoos.photos.domain.entity.LocalMediaItem
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.lang.reflect.Modifier

/**
 * How the device-photo tagger plans a scan's backlog and works through it: a plan holds only the
 * photos with no fresh detection, carried as the few fields tagging reads; a fixed pool works
 * through it a chunk per claim; and the next scan's plan replaces whatever of it is still queued.
 *
 * The tagger is a fake that records what it is handed, on the test's virtual clock, so "in flight"
 * and "dropped" are decided by the scheduler rather than by timing.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LocalTagScanBatchTest {

    private val scopes = mutableListOf<CoroutineScope>()

    @After
    fun cancelScopes() = scopes.forEach { it.cancel() }

    /** Stands in for the scheduler's own scope, on the test's virtual clock. */
    private fun TestScope.workerScope(): CoroutineScope =
        CoroutineScope(StandardTestDispatcher(testScheduler) + SupervisorJob()).also { scopes += it }

    private fun uri(id: Int, kind: String = IMAGES) = "content://media/external/$kind/media/$id"

    private fun photo(id: Int, kind: String = IMAGES) = LocalMediaItem(
        uri = uri(id, kind),
        dateTaken = 1_000_000L - id,
        displayName = "IMG_$id.jpg",
        mimeType = "image/jpeg",
        sizeBytes = 2_000L + id,
        bucketName = "Camera",
        dateModified = 1_700L + id,
    )

    private fun photos(count: Int, kind: String = IMAGES) = (0 until count).map { photo(it, kind) }

    /** The row a detection of [item] stores, its freshness key drifted where a test says so. */
    private fun row(
        item: LocalMediaItem,
        dateModified: Long = item.dateModified,
        sizeBytes: Long = item.sizeBytes,
    ) = LocalTagEntity(
        uri = item.uri,
        dateModified = dateModified,
        sizeBytes = sizeBytes,
        tagsCsv = "",
        scannedAt = 1L,
    )

    private fun TagBatch.uris(): List<String> = (0 until size).map { take(it)!!.uri }

    @Test
    fun `only photos without a fresh detection are planned, newest first`() {
        val items = photos(5)
        val cache = mapOf(
            uri(1) to row(items[1]),
            uri(2) to row(items[2], dateModified = 1L),
            uri(3) to row(items[3], sizeBytes = 1L),
            // A category picked before any scan: the detection columns hold the unscanned zeros.
            uri(4) to LocalTagEntity(uri(4), 0L, 0L, "", 0L, userTagsCsv = "4"),
        )

        assertEquals(
            "no row for 0, 2 and 3 replaced in place, 4 never scanned; 1 is fresh",
            listOf(uri(0), uri(2), uri(3), uri(4)),
            planTagBatch(items, cache).uris(),
        )
    }

    @Test
    fun `a row stored after the cache was read counts ahead of it`() {
        val items = photos(3)
        // What the scan read as it began: nothing for 0, and a row for 1 the file has since outgrown.
        val cache = mapOf(uri(1) to row(items[1], sizeBytes = 1L))
        // Stored while the scan ran: fresh rows for 0 and 1, and one for 2 that a later edit outdated.
        val written = mapOf(
            uri(0) to row(items[0]),
            uri(1) to row(items[1]),
            uri(2) to row(items[2], dateModified = 1L),
        )

        assertEquals(listOf(uri(2)), planTagBatch(items, cache, written).uris())
    }

    @Test
    fun `a batch keeps only the fields tagging reads`() {
        // Strings and numbers only: a pending batch that pinned the scan's items, or their list,
        // would keep the whole library in memory after the gallery lets it go.
        val fields = TagBatch::class.java.declaredFields.filterNot { Modifier.isStatic(it.modifiers) }
        val allowed: Set<Class<*>> = setOf(Array<String>::class.java, LongArray::class.java)
        assertTrue(
            fields.joinToString { "${it.name}: ${it.type.simpleName}" },
            fields.isNotEmpty() && fields.all { it.type in allowed },
        )

        val jpeg = String("image/jpeg".toCharArray())
        val sameJpeg = String("image/jpeg".toCharArray())
        assertNotSame(jpeg, sameJpeg)
        val batch = planTagBatch(
            listOf(photo(0).copy(mimeType = jpeg), photo(1).copy(mimeType = sameJpeg)),
            emptyMap(),
        )
        val first = batch.take(0)!!
        val second = batch.take(1)!!

        assertEquals(uri(0), first.uri)
        assertEquals("IMG_0.jpg", first.displayName)
        assertEquals("image/jpeg", first.mimeType)
        assertEquals(2_000L, first.sizeBytes)
        assertEquals(1_700L, first.dateModified)
        assertSame("one copy of a type string serves every photo of that type", first.mimeType, second.mimeType)
        assertNull("a photo is handed out once", batch.take(0))
    }

    @Test
    fun `workers claim a chunk at a time and tag every photo once`() = runTest {
        val started = mutableListOf<String>()
        var running = 0
        var mostAtOnce = 0
        val starts = mutableListOf<Int>()
        val ends = mutableListOf<Int>()
        val runner = TagBatchRunner(
            scope = workerScope(),
            workerCount = 3,
            chunkSize = 4,
            onStart = { starts += it },
            onEnd = { ends += it },
        ) { target ->
            started += target.uri
            running++
            mostAtOnce = maxOf(mostAtOnce, running)
            delay(10)
            running--
            true
        }

        runner.submit(planTagBatch(photos(30), emptyMap()))
        advanceUntilIdle()

        assertEquals("each worker opens on a chunk of its own", listOf(uri(0), uri(4), uri(8)), started.take(3))
        assertEquals((0 until 30).map { uri(it) }.toSet(), started.toSet())
        assertEquals("no photo is tagged twice", 30, started.size)
        assertEquals("the pool never grows past its size", 3, mostAtOnce)
        assertEquals(listOf(30), starts)
        assertEquals(listOf(30), ends)
    }

    @Test
    fun `a newer plan replaces the batch still queued`() = runTest {
        val release = CompletableDeferred<Unit>()
        val tagged = mutableListOf<String>()
        val starts = mutableListOf<Int>()
        val ends = mutableListOf<Int>()
        val runner = TagBatchRunner(
            scope = workerScope(),
            workerCount = 2,
            chunkSize = 4,
            onStart = { starts += it },
            onEnd = { ends += it },
        ) { target ->
            release.await()
            tagged += target.uri
            true
        }

        runner.submit(planTagBatch(photos(20), emptyMap()))
        runCurrent()
        // Both workers now hold the first photo of their chunk: 0 and 4 of the older plan.
        runner.submit(planTagBatch(photos(6, VIDEOS), emptyMap()))
        release.complete(Unit)
        advanceUntilIdle()

        val newer = (0 until 6).map { uri(it, VIDEOS) }
        assertEquals(
            "the older plan finishes only what was in flight",
            setOf(uri(0), uri(4)) + newer,
            tagged.toSet(),
        )
        assertEquals(8, tagged.size)
        assertEquals(listOf(20, 6), starts)
        assertEquals("each batch ends once, the older one as it is replaced", listOf(0, 6), ends)
    }

    @Test
    fun `clear drops the queued photos and lets the ones in flight finish`() = runTest {
        val release = CompletableDeferred<Unit>()
        val tagged = mutableListOf<String>()
        val ends = mutableListOf<Int>()
        val runner = TagBatchRunner(
            scope = workerScope(),
            workerCount = 2,
            chunkSize = 4,
            onStart = {},
            onEnd = { ends += it },
        ) { target ->
            release.await()
            tagged += target.uri
            true
        }

        runner.submit(planTagBatch(photos(20), emptyMap()))
        runCurrent()
        runner.clear()
        release.complete(Unit)
        advanceUntilIdle()

        assertEquals(listOf(uri(0), uri(4)), tagged.sorted())
        assertEquals(listOf(0), ends)
    }

    @Test
    fun `a plan with nothing to tag queues nothing`() = runTest {
        val items = photos(3)
        var tagCalls = 0
        val starts = mutableListOf<Int>()
        val runner = TagBatchRunner(
            scope = workerScope(),
            workerCount = 2,
            chunkSize = 4,
            onStart = { starts += it },
            onEnd = {},
        ) {
            tagCalls++
            true
        }

        runner.submit(planTagBatch(items, items.associate { it.uri to row(it) }))
        advanceUntilIdle()

        assertEquals(0, tagCalls)
        assertEquals(emptyList<Int>(), starts)
    }

    @Test
    fun `a photo that fails costs only that photo`() = runTest {
        val tagged = mutableListOf<String>()
        val ends = mutableListOf<Int>()
        val runner = TagBatchRunner(
            scope = workerScope(),
            workerCount = 2,
            chunkSize = 4,
            onStart = {},
            onEnd = { ends += it },
        ) { target ->
            when (target.uri) {
                uri(2) -> throw IOException("unreadable")
                uri(5) -> false
                else -> {
                    tagged += target.uri
                    true
                }
            }
        }

        runner.submit(planTagBatch(photos(8), emptyMap()))
        advanceUntilIdle()

        assertEquals((0 until 8).filter { it != 2 && it != 5 }.map { uri(it) }.toSet(), tagged.toSet())
        assertEquals("a photo not stored is not counted as tagged", listOf(6), ends)
    }

    private companion object {
        const val IMAGES = "images"
        const val VIDEOS = "video"
    }
}
