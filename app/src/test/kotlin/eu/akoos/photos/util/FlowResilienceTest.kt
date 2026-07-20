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

package eu.akoos.photos.util

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * Pins [retryOnDbTear], the guard that keeps the duplicate finder (and any Room-backed stream) from
 * force-closing when a concurrent write faults a cursor window mid-read. The crash only reproduces on
 * a very large library under a delete burst, so the resilience is verified here without the app: a
 * source that throws once must re-subscribe and still deliver its data, not propagate the throw.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FlowResilienceTest {

    @Test
    fun a_transient_torn_read_re_subscribes_and_the_stream_survives() = runTest {
        val subscribes = AtomicInteger(0)
        var retries = 0
        // Fails the first subscription (the torn cursor window), succeeds on the re-subscribe.
        val source = flow {
            if (subscribes.getAndIncrement() == 0) throw IllegalStateException("torn cursor window")
            emit(42)
        }

        val value = source.retryOnDbTear("test") { retries++ }.first()

        assertEquals("the stream must survive the tear and deliver its data", 42, value)
        assertEquals("it should have retried exactly once", 1, retries)
        assertEquals("the upstream should have been subscribed twice", 2, subscribes.get())
    }

    @Test
    fun a_source_that_never_fails_passes_straight_through_without_retrying() = runTest {
        var retries = 0
        val value = flow { emit(7) }.retryOnDbTear("test") { retries++ }.first()
        assertEquals(7, value)
        assertEquals("a healthy stream must not retry", 0, retries)
    }

    @Test
    fun the_retry_cap_stops_re_subscribing_and_lets_the_failure_through() = runTest {
        val subscribes = AtomicInteger(0)
        // Deterministic failure, the shape a cap exists for: no number of retries will fix it.
        val source = flow<Int> {
            subscribes.getAndIncrement()
            throw IllegalStateException("too many SQL variables")
        }

        val thrown = runCatching { source.retryOnDbTear("test", maxAttempts = 3).first() }.exceptionOrNull()

        assertTrue("the failure must surface, not be retried away", thrown is IllegalStateException)
        assertEquals("the original cause must reach the collector", "too many SQL variables", thrown?.message)
        assertEquals("one initial subscribe plus three retries", 4, subscribes.get())
    }

    @Test
    fun the_default_keeps_retrying_a_source_that_recovers_late() = runTest {
        val subscribes = AtomicInteger(0)
        val source = flow {
            if (subscribes.getAndIncrement() < 20) throw IllegalStateException("torn cursor window")
            emit(11)
        }

        val value = source.retryOnDbTear("test").first()

        assertEquals("an uncapped stream must still survive a long run of tears", 11, value)
        assertEquals(21, subscribes.get())
    }

    @Test
    fun backoff_grows_linearly_then_caps_at_five_seconds() {
        assertEquals(500L, retryBackoffMs(0))
        assertEquals(1_000L, retryBackoffMs(1))
        assertEquals(4_500L, retryBackoffMs(8))
        assertEquals(5_000L, retryBackoffMs(9))
        assertEquals("a persistently failing source stays capped, never spinning the CPU", 5_000L, retryBackoffMs(100))
    }
}
