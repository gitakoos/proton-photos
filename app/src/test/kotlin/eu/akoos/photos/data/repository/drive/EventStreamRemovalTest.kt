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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [isStreamRemovalEvent] decides, for one volume event, whether the photo has left the user's active
 * stream and must be removed locally rather than re-added.
 *
 * The case that makes it necessary: a "delete" on any Proton client trashes first (only emptying the
 * trash is the hard delete), so a deletion reaches this feed as an ordinary update carrying the
 * trashed state, not as an eventType-0 delete. Read that as an upsert and the feed re-adds a photo
 * the full listing already dropped, which is what made a deleted photo flash back on refresh.
 */
class EventStreamRemovalTest {

    @Test
    fun `a hard delete event is a removal`() {
        // eventType 0 is the permanent delete that empties the trash, with or without a state.
        assertTrue(isStreamRemovalEvent(eventType = 0, linkState = null))
        assertTrue(isStreamRemovalEvent(eventType = 0, linkState = LINK_STATE_TRASHED))
    }

    @Test
    fun `a trashed link is a removal whatever the event type carrying it`() {
        // A trash arrives as an update (or metadata update), not a delete; the trashed state is what
        // makes it a removal.
        assertTrue(isStreamRemovalEvent(eventType = 2, linkState = LINK_STATE_TRASHED))
        assertTrue(isStreamRemovalEvent(eventType = 3, linkState = 2))
    }

    @Test
    fun `an active link update is not a removal`() {
        // A rename, a favourite, or a restore from trash leaves the photo in the stream.
        assertFalse(isStreamRemovalEvent(eventType = 2, linkState = 1))
        assertFalse(isStreamRemovalEvent(eventType = 3, linkState = 1))
    }

    @Test
    fun `an update whose payload omits the state is not treated as a removal here`() {
        // The event alone cannot prove a trash, so this path must not guess: the link-detail pass
        // reads the authoritative state and removes it there instead.
        assertFalse(isStreamRemovalEvent(eventType = 2, linkState = null))
    }

    @Test
    fun `the trashed constant matches the official client value`() {
        assertEquals(2, LINK_STATE_TRASHED)
    }
}
