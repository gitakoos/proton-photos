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

package eu.akoos.photos.presentation.common

import eu.akoos.photos.domain.entity.SyncState
import eu.akoos.photos.domain.entity.SyncStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Undoing a delete pairs the restored photo with its cloud copy again. Its backup stamp is kept from the
 * row the delete left, or given only when its bytes are Drive's: a fresh stamp on nothing verified would
 * let free-up delete a file never backed up.
 */
class UndoRelinkTest {

    private val relink = UndoAction.Delete.Relink("uri://1", "cloud-1", 1000L, contentHash = "drive")
    private val now = 9_000L

    private fun prior(cloudId: String = "cloud-1", backedUpAtMs: Long? = 5_000L) = SyncState(
        localUri = "uri://1", cloudFileId = cloudId, localHash = "uploaded", cloudHash = "drive",
        status = SyncStatus.LOCAL_ONLY, lastSyncAttemptMs = 1L, lastSyncSuccessMs = 1L,
        backedUpAtMs = backedUpAtMs, sizeBytes = 1200L, originalHash = "orig",
    )

    @Test
    fun `the row the delete left keeps its hashes and backup stamp`() {
        val row = UndoController.relinkedRow(prior(), relink, sha1 = null, onDrive = false, now = now)

        assertEquals(SyncStatus.SYNCED, row.status)
        assertEquals(5_000L, row.backedUpAtMs)
        assertEquals("uploaded", row.localHash)
        assertEquals("orig", row.originalHash)
        assertEquals(1200L, row.sizeBytes)
    }

    @Test
    fun `without that row a restored file is stamped only when its bytes are Drive's`() {
        val unverified = UndoController.relinkedRow(null, relink, sha1 = "other", onDrive = false, now = now)
        assertEquals(SyncStatus.SYNCED, unverified.status)
        assertNull("paired so the backup skips it, never stamped", unverified.backedUpAtMs)
        assertEquals("", unverified.localHash)

        val verified = UndoController.relinkedRow(null, relink, sha1 = "drive-bytes", onDrive = true, now = now)
        assertEquals(now, verified.backedUpAtMs)
        assertEquals("drive-bytes", verified.localHash)
    }

    @Test
    fun `a row paired to another cloud copy or never stamped is not kept`() {
        val other = UndoController.relinkedRow(prior(cloudId = "cloud-2"), relink, null, onDrive = false, now = now)
        assertEquals("cloud-1", other.cloudFileId)
        assertNull(other.backedUpAtMs)

        val unstamped = UndoController.relinkedRow(prior(backedUpAtMs = null), relink, null, onDrive = false, now = now)
        assertNull(unstamped.backedUpAtMs)
    }
}
