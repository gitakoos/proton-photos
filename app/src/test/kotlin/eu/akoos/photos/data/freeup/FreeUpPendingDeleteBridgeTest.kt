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

package eu.akoos.photos.data.freeup

import eu.akoos.photos.domain.repository.FREE_UP_PENDING_OFFER_STALE_MS
import eu.akoos.photos.domain.repository.FreeUpConsentSettlement
import eu.akoos.photos.domain.repository.isFreeUpPendingOfferStale
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FreeUpPendingDeleteBridgeTest {

    @Test
    fun settle_granted_removes_uris_and_emits_matching_settlement() = runTest {
        val bridge = FreeUpPendingDeleteBridge()
        bridge.offer(listOf("content://a", "content://b"))
        assertNotNull(bridge.offeredAtMs)

        // UNDISPATCHED so collect is subscribed before settle's tryEmit.
        val settlement = async(start = CoroutineStart.UNDISPATCHED) { bridge.settled.first() }
        bridge.settle(setOf("content://a"), granted = true)

        assertEquals(
            FreeUpConsentSettlement(uris = setOf("content://a"), granted = true),
            settlement.await(),
        )
        assertEquals(setOf("content://b"), bridge.uris.value)
        assertNotNull(bridge.offeredAtMs)
    }

    @Test
    fun settle_denied_removes_uris_and_emits_granted_false() = runTest {
        val bridge = FreeUpPendingDeleteBridge()
        bridge.offer(listOf("content://a", "content://b"))

        val settlement = async(start = CoroutineStart.UNDISPATCHED) { bridge.settled.first() }
        bridge.settle(setOf("content://a", "content://b"), granted = false)

        assertEquals(
            FreeUpConsentSettlement(uris = setOf("content://a", "content://b"), granted = false),
            settlement.await(),
        )
        assertTrue(bridge.uris.value.isEmpty())
        assertNull(bridge.offeredAtMs)
    }

    @Test
    fun settle_empty_uris_is_noop_with_no_emit() = runTest {
        val bridge = FreeUpPendingDeleteBridge()
        bridge.offer(listOf("content://a"))

        val emissions = mutableListOf<FreeUpConsentSettlement>()
        backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
            bridge.settled.collect { emissions.add(it) }
        }

        bridge.settle(emptySet(), granted = true)

        assertTrue(emissions.isEmpty())
        assertEquals(setOf("content://a"), bridge.uris.value)
    }

    @Test
    fun settleAllPending_denied_drains_queue_so_no_uris_remain_to_reoffer() = runTest {
        val bridge = FreeUpPendingDeleteBridge()
        bridge.offer(listOf("content://chunk1-a", "content://chunk1-b", "content://chunk2-a"))

        val settlement = async(start = CoroutineStart.UNDISPATCHED) { bridge.settled.first() }
        // Deny path: settle the entire queue, not only the dialog batch.
        bridge.settleAllPending(granted = false)

        assertEquals(
            FreeUpConsentSettlement(
                uris = setOf("content://chunk1-a", "content://chunk1-b", "content://chunk2-a"),
                granted = false,
            ),
            settlement.await(),
        )
        assertTrue(bridge.uris.value.isEmpty())
        assertNull(bridge.offeredAtMs)
    }

    @Test
    fun grant_chunk_then_remaining_stay_pending_for_next_offer() = runTest {
        val bridge = FreeUpPendingDeleteBridge()
        bridge.offer(listOf("content://a", "content://b", "content://c"))

        val settlement = async(start = CoroutineStart.UNDISPATCHED) { bridge.settled.first() }
        bridge.settle(setOf("content://a", "content://b"), granted = true)

        assertEquals(
            FreeUpConsentSettlement(uris = setOf("content://a", "content://b"), granted = true),
            settlement.await(),
        )
        assertEquals(setOf("content://c"), bridge.uris.value)
    }

    @Test
    fun offer_sets_offeredAtMs_once_until_queue_empties() {
        val bridge = FreeUpPendingDeleteBridge()
        assertNull(bridge.offeredAtMs)

        bridge.offer(listOf("content://a"))
        val first = bridge.offeredAtMs
        assertNotNull(first)

        bridge.offer(listOf("content://b"))
        assertEquals(first, bridge.offeredAtMs)

        bridge.settle(setOf("content://a", "content://b"), granted = true)
        assertNull(bridge.offeredAtMs)
    }

    @Test
    fun isFreeUpPendingOfferStale_null_offer_is_never_stale() {
        assertFalse(isFreeUpPendingOfferStale(offeredAtMs = null, nowMs = 1_000L))
    }

    @Test
    fun isFreeUpPendingOfferStale_fresh_offer_within_15_min() {
        val offered = 1_000L
        assertFalse(
            isFreeUpPendingOfferStale(
                offeredAtMs = offered,
                nowMs = offered + FREE_UP_PENDING_OFFER_STALE_MS - 1L,
            ),
        )
    }

    @Test
    fun isFreeUpPendingOfferStale_at_or_past_15_min() {
        val offered = 1_000L
        assertTrue(
            isFreeUpPendingOfferStale(
                offeredAtMs = offered,
                nowMs = offered + FREE_UP_PENDING_OFFER_STALE_MS,
            ),
        )
        assertTrue(
            isFreeUpPendingOfferStale(
                offeredAtMs = offered,
                nowMs = offered + FREE_UP_PENDING_OFFER_STALE_MS + 60_000L,
            ),
        )
    }

    @Test
    fun remove_drops_uris_without_emitting_settlement() = runTest {
        val bridge = FreeUpPendingDeleteBridge()
        bridge.offer(listOf("content://a", "content://b", "content://c"))

        val emissions = mutableListOf<FreeUpConsentSettlement>()
        backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
            bridge.settled.collect { emissions.add(it) }
        }

        bridge.remove(setOf("content://a", "content://c"))

        assertTrue(emissions.isEmpty())
        assertEquals(setOf("content://b"), bridge.uris.value)
        assertNotNull(bridge.offeredAtMs)

        bridge.remove(setOf("content://b"))
        assertTrue(emissions.isEmpty())
        assertTrue(bridge.uris.value.isEmpty())
        assertNull(bridge.offeredAtMs)
    }

    @Test
    fun settleAllPending_clears_offeredAtMs_so_offer_is_no_longer_stale() {
        val bridge = FreeUpPendingDeleteBridge()
        bridge.offer(listOf("content://old"))
        val offered = bridge.offeredAtMs!!
        assertTrue(
            isFreeUpPendingOfferStale(
                offeredAtMs = offered,
                nowMs = offered + FREE_UP_PENDING_OFFER_STALE_MS,
            ),
        )

        bridge.settleAllPending(granted = false)
        assertTrue(bridge.uris.value.isEmpty())
        assertNull(bridge.offeredAtMs)
        assertFalse(
            isFreeUpPendingOfferStale(
                offeredAtMs = bridge.offeredAtMs,
                nowMs = offered + FREE_UP_PENDING_OFFER_STALE_MS,
            ),
        )
    }
}
