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

package eu.akoos.photos.service

import eu.akoos.photos.data.face.FaceIndexingProgress
import eu.akoos.photos.data.face.FaceIndexingState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Coverage for when the face-indexing foreground host, and so its notification, stays up. */
class FaceIndexingServiceRulesTest {

    @Test
    fun `a running walk keeps its host`() {
        assertTrue(faceServiceHostsWalk(FaceIndexingState.Running))
    }

    @Test
    fun `a paused walk ends the host instead of waiting to continue`() {
        assertFalse(faceServiceHostsWalk(FaceIndexingState.Paused))
    }

    @Test
    fun `a walk that is done, idle or without its model ends the host`() {
        listOf(FaceIndexingState.Done, FaceIndexingState.Idle, FaceIndexingState.WaitingModel).forEach { state ->
            assertFalse("$state", faceServiceHostsWalk(state))
        }
    }

    @Test
    fun `a climbing count is progress`() {
        assertTrue(faceHostSawProgress(FaceIndexingProgress(FaceIndexingState.Running, 11, 50), lastIndexed = 10, lastHeartbeat = 0L))
    }

    @Test
    fun `a heartbeat with the count standing still is progress`() {
        // The pass-end grouping of a big library: the scan count is frozen while the batches run.
        val p = FaceIndexingProgress(FaceIndexingState.Running, 10, 50, heartbeat = 8L)
        assertTrue(faceHostSawProgress(p, lastIndexed = 10, lastHeartbeat = 7L))
    }

    @Test
    fun `neither moving is a stall`() {
        val p = FaceIndexingProgress(FaceIndexingState.Running, 10, 50, heartbeat = 7L)
        assertFalse(faceHostSawProgress(p, lastIndexed = 10, lastHeartbeat = 7L))
        // A progress written without a beat reads 0 and never moves the heartbeat back.
        assertFalse(faceHostSawProgress(FaceIndexingProgress(FaceIndexingState.Running, 10, 50), lastIndexed = 10, lastHeartbeat = 7L))
    }
}
