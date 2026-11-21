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

import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The duplicate finder's landed hashes reach the screen in batches: one map copy per batch, not one per
 * file, so a library with tens of thousands of same-size files does not run out of memory.
 */
class HashBatchPublisherTest {

    private var now = 0L
    private val target = MutableStateFlow<Map<String, String>>(emptyMap())
    private val publisher = HashBatchPublisher(target, batchSize = 3, intervalMs = 500L, nowMs = { now })

    @Test
    fun `hashes wait until the batch is full`() {
        publisher.add("a", "1")
        publisher.add("b", "2")

        assertEquals(emptyMap<String, String>(), target.value)
    }

    @Test
    fun `a full batch is published in one step`() {
        publisher.add("a", "1")
        publisher.add("b", "2")
        publisher.add("c", "3")

        assertEquals(mapOf("a" to "1", "b" to "2", "c" to "3"), target.value)
    }

    @Test
    fun `a hash landing after the interval publishes what is waiting`() {
        publisher.add("a", "1")
        now = 500L

        publisher.add("b", "2")

        assertEquals(mapOf("a" to "1", "b" to "2"), target.value)
    }

    @Test
    fun `flush publishes a partial batch`() {
        publisher.add("a", "1")

        publisher.flush()

        assertEquals(mapOf("a" to "1"), target.value)
    }

    @Test
    fun `published hashes are added to the ones already there`() {
        target.value = mapOf("old" to "0", "a" to "stale")
        publisher.add("a", "1")

        publisher.flush()

        assertEquals(mapOf("old" to "0", "a" to "1"), target.value)
    }

    @Test
    fun `flush with nothing waiting leaves the map as it is`() {
        val before = mapOf("a" to "1")
        target.value = before

        publisher.flush()

        assertEquals(before, target.value)
    }
}
