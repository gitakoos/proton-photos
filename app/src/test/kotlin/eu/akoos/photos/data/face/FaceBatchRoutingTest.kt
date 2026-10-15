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

package eu.akoos.photos.data.face

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FaceBatchRoutingTest {

    @Test
    fun `a library at or under the limit is regrouped whole`() {
        assertFalse(groupsInBatchesOnly(faceCount = 0, maxFaces = 30_000))
        assertFalse(groupsInBatchesOnly(faceCount = 30_000, maxFaces = 30_000))
    }

    @Test
    fun `a big backlog with no scan running is hosted on the foreground service`() {
        assertTrue(shouldHostGroupingInForeground(ungrouped = 5_000, backgroundEnabled = true, walkRunning = false))
        assertTrue(shouldHostGroupingInForeground(ungrouped = 120_000, backgroundEnabled = true, walkRunning = false))
    }

    @Test
    fun `a small backlog, a running scan or a user opt-out is not hosted`() {
        assertFalse(shouldHostGroupingInForeground(ungrouped = 4_999, backgroundEnabled = true, walkRunning = false))
        assertFalse(shouldHostGroupingInForeground(ungrouped = 120_000, backgroundEnabled = true, walkRunning = true))
        assertFalse(shouldHostGroupingInForeground(ungrouped = 120_000, backgroundEnabled = false, walkRunning = false))
    }

    @Test
    fun `a library past the limit is grouped in batches only`() {
        assertTrue(groupsInBatchesOnly(faceCount = 30_001, maxFaces = 30_000))
        assertTrue(groupsInBatchesOnly(faceCount = 122_461, maxFaces = 30_000))
    }
}
