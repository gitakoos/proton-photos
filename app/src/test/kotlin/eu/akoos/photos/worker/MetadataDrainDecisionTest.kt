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

package eu.akoos.photos.worker

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure-value coverage for [metadataDrainDecision], the rule that decides whether a failed
 * metadata-edit row is dropped or held for another WorkManager attempt.
 *
 * The two axes are the failure's transience and the worker's attempt count. A permanent failure is
 * always dropped, regardless of how many attempts are left, so a re-add the server will never accept
 * does not consume the retry budget. A transient failure is held only while attempts remain, then
 * given up at the budget so a genuinely stuck edit cannot loop the worker forever. The budget boundary
 * is the sharp edge: at exactly maxAttempts the decision must flip from keep to give-up.
 *
 * No Android, no Robolectric, no WorkManager runtime: plain JVM assertions on the inputs.
 */
class MetadataDrainDecisionTest {

    @Test
    fun `a permanent failure is dropped at once, whatever the attempt count`() {
        for (attempt in listOf(0, 1, 2, 3, 4, 99)) {
            assertEquals(
                "a permanent failure must never be held for a retry (attempt $attempt)",
                MetadataDrainDecision.DELETE_PERMANENT,
                metadataDrainDecision(transient = false, runAttemptCount = attempt),
            )
        }
    }

    @Test
    fun `a transient failure inside the budget is kept for a retry`() {
        for (attempt in listOf(0, 1, 2)) {
            assertEquals(
                "a transient failure with attempts left must be kept (attempt $attempt)",
                MetadataDrainDecision.KEEP_AND_RETRY,
                metadataDrainDecision(transient = true, runAttemptCount = attempt),
            )
        }
    }

    @Test
    fun `a transient failure at or past the budget is given up`() {
        for (attempt in listOf(3, 4, 10)) {
            assertEquals(
                "a transient failure out of attempts must be given up (attempt $attempt)",
                MetadataDrainDecision.GIVE_UP_AFTER_MAX,
                metadataDrainDecision(transient = true, runAttemptCount = attempt),
            )
        }
    }

    @Test
    fun `the budget boundary flips from keep to give-up at exactly maxAttempts`() {
        assertEquals(
            "one attempt below the budget still keeps",
            MetadataDrainDecision.KEEP_AND_RETRY,
            metadataDrainDecision(transient = true, runAttemptCount = 2),
        )
        assertEquals(
            "exactly at the budget gives up",
            MetadataDrainDecision.GIVE_UP_AFTER_MAX,
            metadataDrainDecision(transient = true, runAttemptCount = 3),
        )
    }

    @Test
    fun `a custom budget moves the boundary with it`() {
        assertEquals(
            MetadataDrainDecision.KEEP_AND_RETRY,
            metadataDrainDecision(transient = true, runAttemptCount = 4, maxAttempts = 5),
        )
        assertEquals(
            MetadataDrainDecision.GIVE_UP_AFTER_MAX,
            metadataDrainDecision(transient = true, runAttemptCount = 5, maxAttempts = 5),
        )
    }
}
