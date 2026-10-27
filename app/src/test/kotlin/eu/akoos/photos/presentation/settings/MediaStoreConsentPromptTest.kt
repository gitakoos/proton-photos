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

package eu.akoos.photos.presentation.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure decision coverage for [shouldPromptMediaStoreConsent]: trash growth re-prompt,
 * permanent-delete deny stop, and in-flight gating; plus
 * [recordMediaStoreConsentLaunchFailure] bounded per-batch PermanentDelete launch retry.
 */
class MediaStoreConsentPromptTest {

    @Test
    fun trash_prompts_when_queue_grows_past_high_water() {
        assertTrue(
            shouldPromptMediaStoreConsent(
                mode = MediaStoreConsentMode.Trash,
                pendingSize = 3,
                lastPromptedSize = 2,
                inFlight = false,
                denyStopped = false,
            ),
        )
    }

    @Test
    fun trash_does_not_prompt_at_or_below_high_water_after_deny() {
        // Deny leaves lastPromptedSize at the denied batch size; same size must not re-fire.
        assertFalse(
            shouldPromptMediaStoreConsent(
                mode = MediaStoreConsentMode.Trash,
                pendingSize = 5,
                lastPromptedSize = 5,
                inFlight = false,
                denyStopped = true, // ignored for Trash
            ),
        )
        assertFalse(
            shouldPromptMediaStoreConsent(
                mode = MediaStoreConsentMode.Trash,
                pendingSize = 4,
                lastPromptedSize = 5,
                inFlight = false,
                denyStopped = false,
            ),
        )
    }

    @Test
    fun trash_denyStopped_does_not_block_growth_reprompt() {
        assertTrue(
            shouldPromptMediaStoreConsent(
                mode = MediaStoreConsentMode.Trash,
                pendingSize = 6,
                lastPromptedSize = 5,
                inFlight = false,
                denyStopped = true,
            ),
        )
    }

    @Test
    fun permanentDelete_stops_after_deny() {
        assertFalse(
            shouldPromptMediaStoreConsent(
                mode = MediaStoreConsentMode.PermanentDelete,
                pendingSize = 10,
                lastPromptedSize = 0,
                inFlight = false,
                denyStopped = true,
            ),
        )
    }

    @Test
    fun permanentDelete_prompts_while_pending_and_not_denied() {
        assertTrue(
            shouldPromptMediaStoreConsent(
                mode = MediaStoreConsentMode.PermanentDelete,
                pendingSize = 10,
                lastPromptedSize = 0,
                inFlight = false,
                denyStopped = false,
            ),
        )
    }

    @Test
    fun inFlight_blocks_both_modes() {
        assertFalse(
            shouldPromptMediaStoreConsent(
                mode = MediaStoreConsentMode.Trash,
                pendingSize = 9,
                lastPromptedSize = 1,
                inFlight = true,
                denyStopped = false,
            ),
        )
        assertFalse(
            shouldPromptMediaStoreConsent(
                mode = MediaStoreConsentMode.PermanentDelete,
                pendingSize = 9,
                lastPromptedSize = 0,
                inFlight = true,
                denyStopped = false,
            ),
        )
    }

    @Test
    fun empty_pending_never_prompts() {
        assertFalse(
            shouldPromptMediaStoreConsent(
                mode = MediaStoreConsentMode.Trash,
                pendingSize = 0,
                lastPromptedSize = 0,
                inFlight = false,
                denyStopped = false,
            ),
        )
        assertFalse(
            shouldPromptMediaStoreConsent(
                mode = MediaStoreConsentMode.PermanentDelete,
                pendingSize = 0,
                lastPromptedSize = 0,
                inFlight = false,
                denyStopped = false,
            ),
        )
    }

    @Test
    fun launchFailure_trash_is_none() {
        val initial = MediaStoreConsentRetryState()

        val transition = recordMediaStoreConsentLaunchFailure(
            mode = MediaStoreConsentMode.Trash,
            batch = setOf("content://1"),
            state = initial,
        )

        assertEquals(MediaStoreConsentLaunchFailureAction.None, transition.action)
        assertEquals(initial, transition.state)
    }

    @Test
    fun samePermanentDeleteBatch_retries_once_then_notifies_failure() {
        val batch = linkedSetOf("content://1", "content://2")
        val first = recordMediaStoreConsentLaunchFailure(
            mode = MediaStoreConsentMode.PermanentDelete,
            batch = batch,
            state = MediaStoreConsentRetryState(),
        )
        // Clearing lastAttemptedBatch is deliberately not represented in retry state.
        val second = recordMediaStoreConsentLaunchFailure(
            mode = MediaStoreConsentMode.PermanentDelete,
            batch = batch,
            state = first.state,
        )
        val alreadyReported = recordMediaStoreConsentLaunchFailure(
            mode = MediaStoreConsentMode.PermanentDelete,
            batch = batch,
            state = second.state,
        )

        assertEquals(MediaStoreConsentLaunchFailureAction.Retry, first.action)
        assertEquals(1, first.state.failureCount)
        assertEquals(batch, first.state.failureBatch)
        assertEquals(MediaStoreConsentLaunchFailureAction.NotifyFailure, second.action)
        assertEquals(2, second.state.failureCount)
        assertEquals(batch, second.state.failureBatch)
        assertEquals(MediaStoreConsentLaunchFailureAction.None, alreadyReported.action)
    }

    @Test
    fun differentPermanentDeleteBatch_receives_a_fresh_retry_budget() {
        val first = recordMediaStoreConsentLaunchFailure(
            mode = MediaStoreConsentMode.PermanentDelete,
            batch = setOf("content://1"),
            state = MediaStoreConsentRetryState(),
        )

        val different = recordMediaStoreConsentLaunchFailure(
            mode = MediaStoreConsentMode.PermanentDelete,
            batch = setOf("content://2"),
            state = first.state,
        )

        assertEquals(MediaStoreConsentLaunchFailureAction.Retry, different.action)
        assertEquals(1, different.state.failureCount)
        assertEquals(setOf("content://2"), different.state.failureBatch)
    }
}
