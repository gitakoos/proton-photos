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

import eu.akoos.photos.data.repository.drive.PhotoUnavailableException
import me.proton.core.network.domain.ApiException
import me.proton.core.network.domain.ApiResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.IOException

/**
 * Pins the source-retry policy both background model walks share: never-tried photos go first, due
 * retries follow up to a per-pass cap, a retry not yet due sits the pass out, the backoff ladder, the
 * pass tally that files each outcome, the delay a settled walk waits before it kicks itself again, and
 * how a failed download or an undecodable original is classified.
 */
class MlSourceRetryTest {

    private val keyOf: (String) -> String = { it }

    @Test
    fun never_tried_photos_come_before_due_retries_each_in_library_order() {
        val book = SourceRetryBook()
        book.recordFailure("b", nowMs = 0L)
        book.recordFailure("d", nowMs = 0L)
        val plan = book.planPass(listOf("a", "b", "c", "d", "e"), keyOf, nowMs = MIN_5, maxRetries = 16)
        assertEquals(listOf("a", "c", "e", "b", "d"), plan.queue)
        assertEquals(2, plan.retries)
        assertEquals(0, plan.waiting)
        assertEquals(0, plan.heldBack)
    }

    @Test
    fun a_retry_not_yet_due_sits_the_pass_out() {
        val book = SourceRetryBook()
        book.recordFailure("b", nowMs = 0L)
        val early = book.planPass(listOf("a", "b", "c"), keyOf, nowMs = MIN_5 - 1L, maxRetries = 16)
        assertEquals(listOf("a", "c"), early.queue)
        assertEquals(1, early.waiting)
        val due = book.planPass(listOf("a", "b", "c"), keyOf, nowMs = MIN_5, maxRetries = 16)
        assertEquals(listOf("a", "c", "b"), due.queue)
        assertEquals(0, due.waiting)
    }

    @Test
    fun due_retries_are_capped_per_pass_and_the_rest_held_back() {
        val book = SourceRetryBook()
        val retries = (1..20).map { "r$it" }
        retries.forEach { book.recordFailure(it, nowMs = 0L) }
        val plan = book.planPass(listOf("fresh") + retries, keyOf, nowMs = MIN_5, maxRetries = SOURCE_RETRIES_PER_PASS)
        assertEquals(listOf("fresh") + retries.take(16), plan.queue)
        assertEquals(16, plan.retries)
        assertEquals(4, plan.heldBack)
        // Held back, not dropped: still on record for a later pass.
        assertEquals(20, book.size())
    }

    @Test
    fun the_cap_is_a_quarter_of_the_walks_per_pass_download_budget() {
        assertEquals(16, SOURCE_RETRIES_PER_PASS)
    }

    @Test
    fun the_backoff_climbs_five_minutes_thirty_minutes_two_hours_six_hours_then_stays() {
        val book = SourceRetryBook()
        val expected = listOf(MIN_5, 30L * MINUTE, 2L * HOUR, 6L * HOUR, 6L * HOUR, 6L * HOUR)
        for ((attempt, wait) in expected.withIndex()) {
            val now = 1_000L * attempt
            book.recordFailure("x", now)
            assertEquals(attempt + 1, book.attempts("x"))
            assertEquals(now + wait, book.dueMs("x"))
        }
    }

    @Test
    fun a_photo_with_no_record_has_no_attempts_and_no_due_time() {
        val book = SourceRetryBook()
        assertEquals(0, book.attempts("x"))
        assertNull(book.dueMs("x"))
    }

    @Test
    fun records_for_photos_no_longer_pending_are_dropped_by_the_next_plan() {
        val book = SourceRetryBook()
        book.recordFailure("gone", nowMs = 0L)
        book.recordFailure("kept", nowMs = 0L)
        book.planPass(listOf("kept", "other"), keyOf, nowMs = 0L, maxRetries = 16)
        assertEquals(1, book.size())
        assertEquals(1, book.attempts("kept"))
        assertEquals(0, book.attempts("gone"))
    }

    @Test
    fun failures_at_the_head_of_the_order_no_longer_crowd_out_later_photos() {
        // The first pass spends its whole budget on photos that fail. The next pass, a minute later,
        // starts with the photos behind them rather than the same failures again.
        val book = SourceRetryBook()
        val failing = (1..64).map { "f$it" }
        val later = (1..10).map { "p$it" }
        val first = book.planPass(failing + later, keyOf, nowMs = 0L, maxRetries = 16)
        assertEquals(failing, first.queue.take(64))
        val tally = SourcePassTally(book)
        failing.forEach { tally.stayed(it, SourceMiss.TRANSIENT, nowMs = 0L) }
        val second = book.planPass(failing + later, keyOf, nowMs = MINUTE, maxRetries = 16)
        assertEquals(later, second.queue)
        assertEquals(64, second.waiting)
    }

    @Test
    fun the_tally_files_a_transient_miss_and_only_counts_the_others() {
        val book = SourceRetryBook()
        val tally = SourcePassTally(book)
        tally.stayed("t", SourceMiss.TRANSIENT, nowMs = 0L)
        tally.stayed("o", SourceMiss.OVER_BUDGET, nowMs = 0L)
        tally.stayed("d", SourceMiss.DEFERRED, nowMs = 0L)
        assertEquals(1, tally.transient)
        assertEquals(1, tally.overBudget)
        assertEquals(1, tally.deferred)
        assertEquals(0, tally.moved)
        assertEquals(1, book.attempts("t"))
        assertEquals(0, book.attempts("o"))
        assertEquals(0, book.attempts("d"))
    }

    @Test
    fun a_photo_that_moved_on_leaves_the_book_and_counts_as_headway() {
        val book = SourceRetryBook()
        book.recordFailure("loaded", nowMs = 0L)
        book.recordFailure("settled", nowMs = 0L)
        val tally = SourcePassTally(book)
        tally.movedOn("loaded")
        tally.movedOn("settled", permanent = true)
        assertEquals(2, tally.moved)
        assertEquals(1, tally.permanent)
        assertEquals(0, book.size())
    }

    @Test
    fun the_next_due_time_is_the_earliest_still_in_the_future() {
        val book = SourceRetryBook()
        assertNull(book.nextDueAfter(0L))
        book.recordFailure("a", nowMs = 0L)
        book.recordFailure("b", nowMs = 2L * MINUTE)
        assertEquals(MIN_5, book.nextDueAfter(0L))
        assertEquals(2L * MINUTE + MIN_5, book.nextDueAfter(MIN_5))
        assertNull(book.nextDueAfter(2L * MINUTE + MIN_5))
    }

    @Test
    fun a_settled_walk_waits_for_the_earliest_due_retry_but_never_less_than_the_first_backoff() {
        assertNull(retryKickDelayMs(nextDueMs = null, nowMs = 0L))
        assertEquals(2L * HOUR, retryKickDelayMs(nextDueMs = 2L * HOUR, nowMs = 0L))
        assertEquals(MIN_5, retryKickDelayMs(nextDueMs = 1_000L, nowMs = 0L))
    }

    @Test
    fun clear_drops_every_record() {
        val book = SourceRetryBook()
        book.recordFailure("a", nowMs = 0L)
        book.recordFailure("b", nowMs = 0L)
        book.clear()
        assertEquals(0, book.size())
    }

    @Test
    fun only_a_download_the_service_reports_unavailable_is_permanent() {
        assertEquals(SourceMiss.PERMANENT, sourceMissForDownloadFailure(PhotoUnavailableException("Link not found: x")))
        // A plain state failure (the root key would not decrypt, a block would not decrypt, the manifest
        // did not verify) says nothing certain about the photo, so it is retried.
        assertEquals(SourceMiss.TRANSIENT, sourceMissForDownloadFailure(IllegalStateException("Cannot decrypt root link key")))
        assertEquals(SourceMiss.TRANSIENT, sourceMissForDownloadFailure(IOException("reset")))
        assertEquals(SourceMiss.TRANSIENT, sourceMissForDownloadFailure(ApiException(ApiResult.Error.NoInternet())))
        assertEquals(SourceMiss.TRANSIENT, sourceMissForDownloadFailure(ApiException(ApiResult.Error.Http(503, "unavailable"))))
        assertEquals(SourceMiss.TRANSIENT, sourceMissForDownloadFailure(null))
    }

    @Test
    fun an_original_the_decoder_cannot_read_is_permanent_and_a_failed_decode_is_not() {
        assertEquals(SourceMiss.PERMANENT, sourceMissForUndecodedDownload(unreadableFormat = true))
        assertEquals(SourceMiss.TRANSIENT, sourceMissForUndecodedDownload(unreadableFormat = false))
    }

    @Test
    fun `a device file that keeps failing to open settles on the third attempt`() {
        assertEquals(SourceMiss.TRANSIENT, settledLocalMiss(SourceMiss.TRANSIENT, priorAttempts = 0))
        assertEquals(SourceMiss.TRANSIENT, settledLocalMiss(SourceMiss.TRANSIENT, priorAttempts = 1))
        assertEquals(SourceMiss.PERMANENT, settledLocalMiss(SourceMiss.TRANSIENT, priorAttempts = 2))
        assertEquals(SourceMiss.DEFERRED, settledLocalMiss(SourceMiss.DEFERRED, priorAttempts = 5))
    }

    @Test
    fun `a device file counts as permanent only when it opened and the decode threw nothing`() {
        assertEquals(SourceMiss.PERMANENT, sourceMissForUndecodedLocal(readable = true, decodeThrew = false))
        assertEquals(SourceMiss.TRANSIENT, sourceMissForUndecodedLocal(readable = false, decodeThrew = false))
        assertEquals(SourceMiss.TRANSIENT, sourceMissForUndecodedLocal(readable = true, decodeThrew = true))
    }

    @Test
    fun a_note_reads_transient_until_a_step_gives_up_with_a_reason() {
        val note = SourceMissNote()
        assertEquals(SourceMiss.TRANSIENT, note.reason)
        assertNull(note.giveUp(SourceMiss.OVER_BUDGET))
        assertEquals(SourceMiss.OVER_BUDGET, note.reason)
    }

    private companion object {
        const val MINUTE = 60_000L
        const val HOUR = 60L * MINUTE
        const val MIN_5 = 5L * MINUTE
    }
}
