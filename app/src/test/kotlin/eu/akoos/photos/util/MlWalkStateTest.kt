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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Pins what a background model walk keeps across a process the system killed: the retry book round-trips
 * through its wall-clock form without stretching or losing a backoff, and the state file reads back only
 * for the app version and account that wrote it, never trusting a damaged one.
 */
class MlWalkStateTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun store(version: Int = 273, name: String = "walk.bin") =
        MlWalkStateStore(File(File(temp.root, "ml_state"), name), version)

    @Test
    fun a_book_round_trips_through_its_wall_clock_form_keeping_attempts_and_the_wait_left() {
        val book = SourceRetryBook()
        book.recordFailure("a", nowMs = 1_000L)
        book.recordFailure("a", nowMs = 2_000L)
        book.recordFailure("b", nowMs = 1_000L)
        val records = book.export(nowMs = 10_000L, nowEpochMs = 1_700_000_000_000L)

        val later = SourceRetryBook()
        // A new process: its elapsed clock restarted at 500, and one minute of wall-clock time has passed.
        later.restore(records, nowMs = 500L, nowEpochMs = 1_700_000_060_000L)

        assertEquals(2, later.attempts("a"))
        assertEquals(1, later.attempts("b"))
        // "b" was due 5 minutes after elapsed 1_000, i.e. 301_000, which is 291_000 after the export instant;
        // 60 s of those have gone by, leaving 231_000 from the new process's now.
        assertEquals(500L + 231_000L, later.dueMs("b"))
        // "a" climbed to the 30 minute step after its second failure.
        assertEquals(500L + (2_000L + 30L * 60_000L - 10_000L) - 60_000L, later.dueMs("a"))
    }

    @Test
    fun a_record_already_due_is_due_at_once_and_keeps_its_attempts() {
        val later = SourceRetryBook()
        later.restore(listOf(RetryRecord("a", 3, dueEpochMs = 1_000L)), nowMs = 700L, nowEpochMs = 9_000_000L)
        assertEquals(3, later.attempts("a"))
        assertEquals(700L, later.dueMs("a"))
    }

    @Test
    fun a_wall_clock_set_back_cannot_park_a_photo_beyond_the_longest_step() {
        val later = SourceRetryBook()
        val farAhead = 1_700_000_000_000L + 365L * 24 * 3_600_000L
        later.restore(listOf(RetryRecord("a", 4, dueEpochMs = farAhead)), nowMs = 0L, nowEpochMs = 1_700_000_000_000L)
        assertEquals(SOURCE_RETRY_BACKOFF_MS.last(), later.dueMs("a"))
    }

    @Test
    fun a_photo_that_already_holds_a_record_keeps_it() {
        val book = SourceRetryBook()
        book.recordFailure("a", nowMs = 0L)
        book.restore(listOf(RetryRecord("a", 9, dueEpochMs = 0L)), nowMs = 0L, nowEpochMs = 0L)
        assertEquals(1, book.attempts("a"))
    }

    @Test
    fun a_moment_the_wall_clock_puts_in_the_future_counts_as_now() {
        assertEquals(5_000L, elapsedFromEpoch(epochMs = 2_000_000L, nowElapsedMs = 5_000L, nowEpochMs = 1_000_000L))
        assertEquals(4_000L, elapsedFromEpoch(epochMs = 999_000L, nowElapsedMs = 5_000L, nowEpochMs = 1_000_000L))
        assertEquals(999_000L, epochFromElapsed(elapsedMs = 4_000L, nowElapsedMs = 5_000L, nowEpochMs = 1_000_000L))
    }

    @Test
    fun the_state_file_reads_back_what_was_written() {
        val s = store()
        s.write(
            MlWalkState(
                account = "acct",
                retries = listOf(RetryRecord("k1", 2, 123L), RetryRecord("content://media/1", 1, 456L)),
                lastFailedRegroupEpochMs = 10L,
                lastRegroupEndEpochMs = 20L,
                lastRegroupDurationMs = 30L,
                baselineUngrouped = 7,
            ),
        )
        val read = s.read("acct")
        assertNotNull(read)
        read!!
        assertEquals(listOf("k1", "content://media/1"), read.retries.map { it.key })
        assertEquals(listOf(2, 1), read.retries.map { it.attempts })
        assertEquals(listOf(123L, 456L), read.retries.map { it.dueEpochMs })
        assertEquals(10L, read.lastFailedRegroupEpochMs)
        assertEquals(20L, read.lastRegroupEndEpochMs)
        assertEquals(30L, read.lastRegroupDurationMs)
        assertEquals(7, read.baselineUngrouped)
    }

    @Test
    fun absent_values_stay_absent() {
        val s = store()
        s.write(MlWalkState("acct", retries = listOf(RetryRecord("k", 1, 5L))))
        val read = s.read("acct")!!
        assertNull(read.lastFailedRegroupEpochMs)
        assertNull(read.lastRegroupEndEpochMs)
        assertNull(read.baselineUngrouped)
    }

    @Test
    fun another_account_or_app_version_reads_as_nothing_saved() {
        store(version = 273).write(MlWalkState("acct", retries = listOf(RetryRecord("k", 1, 5L))))
        assertNull(store(version = 273).read("other"))
        assertNull(store(version = 274).read("acct"))
        assertNotNull(store(version = 273).read("acct"))
    }

    @Test
    fun a_damaged_or_cut_short_file_reads_as_nothing_saved() {
        val s = store()
        s.write(MlWalkState("acct", retries = listOf(RetryRecord("k", 1, 5L))))
        val file = File(File(temp.root, "ml_state"), "walk.bin")
        val bytes = file.readBytes()
        file.writeBytes(bytes.copyOf(bytes.size / 2))
        assertNull(s.read("acct"))
        file.writeBytes(byteArrayOf(1, 2, 3, 4, 5))
        assertNull(s.read("acct"))
        file.writeBytes(ByteArray(0))
        assertNull(s.read("acct"))
    }

    @Test
    fun an_empty_state_removes_the_file() {
        val s = store()
        s.write(MlWalkState("acct", retries = listOf(RetryRecord("k", 1, 5L))))
        val file = File(File(temp.root, "ml_state"), "walk.bin")
        assertTrue(file.isFile)
        s.write(MlWalkState("acct"))
        assertFalse(file.exists())
        assertNull(s.read("acct"))
    }

    @Test
    fun an_owed_finalize_round_trips_and_alone_keeps_the_file() {
        val s = store()
        s.write(MlWalkState("acct", finalizeOwed = true))
        val read = s.read("acct")
        assertNotNull(read)
        assertTrue(read!!.finalizeOwed)
        assertNull(read.baselineUngrouped)
        s.write(MlWalkState("acct", baselineUngrouped = 4))
        assertFalse(s.read("acct")!!.finalizeOwed)
    }

    @Test
    fun the_last_row_a_batched_run_looked_at_round_trips_and_alone_keeps_the_file() {
        val s = store()
        s.write(MlWalkState("acct", groupedUpToRow = 120_000L))
        assertEquals(120_000L, s.read("acct")!!.groupedUpToRow)
        s.write(MlWalkState("acct", baselineUngrouped = 4))
        assertEquals(0L, s.read("acct")!!.groupedUpToRow)
    }

    @Test
    fun a_file_in_the_previous_format_reads_as_nothing_saved() {
        val file = File(File(temp.root, "ml_state"), "walk.bin")
        file.parentFile!!.mkdirs()
        java.io.DataOutputStream(file.outputStream()).use { out ->
            out.writeUTF("PPMLWALK")
            out.writeInt(1)
            out.writeInt(273)
            out.writeUTF("acct")
            out.writeInt(0)
            out.writeLong(Long.MIN_VALUE)
            out.writeLong(Long.MIN_VALUE)
            out.writeLong(0L)
            out.writeInt(7)
        }
        assertNull(store().read("acct"))
    }

    @Test
    fun delete_removes_the_saved_state() {
        val s = store()
        s.write(MlWalkState("acct", baselineUngrouped = 3))
        s.delete()
        assertNull(s.read("acct"))
    }
}
