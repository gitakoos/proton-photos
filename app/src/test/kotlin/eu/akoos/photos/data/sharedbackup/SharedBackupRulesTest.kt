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

package eu.akoos.photos.data.sharedbackup

import eu.akoos.photos.data.mlsync.HiddenDriveNaming
import eu.akoos.photos.data.mlsync.LabelDriveNaming
import eu.akoos.photos.data.mlsync.MlDriveFile
import eu.akoos.photos.data.mlsync.MlSyncOperation
import eu.akoos.photos.data.mlsync.MlSyncRequest
import eu.akoos.photos.data.mlsync.MlSyncWorkData
import eu.akoos.photos.data.mlsync.SearchImportMode
import eu.akoos.photos.data.mlsync.shouldImportSearchEmbedding
import eu.akoos.photos.presentation.settings.SharedBackupLine
import eu.akoos.photos.presentation.settings.sharedBackupLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The shared backup's own rules: which copies a round reads, the round request, the files' names and the status line. */
class SharedBackupRulesTest {

    private val own = "22222222-2222-4222-8222-222222222222"
    private val pc = "11111111-1111-4111-8111-111111111111"
    private val tablet = "33333333-3333-4333-8333-333333333333"

    private fun file(name: String, claimed: Long?, server: Long = 1L, uid: String = name) = MlDriveFile(
        nodeUid = uid, name = name, sizeBytes = 10, activeRevisionUid = "rev-$uid",
        serverModifiedAtMs = server, serverCreatedAtMs = 0, claimedModifiedAtMs = claimed,
    )

    @Test
    fun `a round reads each other install's newest copy once, and never its own`() {
        val files = listOf(
            file(LabelDriveNaming.fileName(pc), claimed = 100),
            file(LabelDriveNaming.fileName(tablet), claimed = 50),
            file(LabelDriveNaming.fileName(own), claimed = 300),
            file("notes.txt", claimed = 400),
        )
        val first = newerCopies(files, LabelDriveNaming::parse, own, applied = emptyMap())
        assertEquals(setOf(pc, tablet), first.map { it.deviceId }.toSet())
        val again = newerCopies(files, LabelDriveNaming::parse, own, applied = mapOf(pc to 100L, tablet to 50L))
        assertTrue(again.isEmpty())
        val newer = newerCopies(
            files + file(LabelDriveNaming.fileName(pc), claimed = 150, uid = "second"),
            LabelDriveNaming::parse,
            own,
            applied = mapOf(pc to 100L, tablet to 50L),
        )
        assertEquals(listOf(pc to 150L), newer.map { it.deviceId to it.madeAtMs })
    }

    @Test
    fun `a copy Drive gives no claimed time for is judged by when the server stored it`() {
        val copies = newerCopies(listOf(file(HiddenDriveNaming.fileName(pc), claimed = null, server = 70)), HiddenDriveNaming::parse, own, mapOf(pc to 60L))
        assertEquals(70L, copies.single().madeAtMs)
    }

    @Test
    fun `the hidden list has one file per install`() {
        assertEquals("hidden-$pc.pfphd", HiddenDriveNaming.fileName(pc))
        assertEquals(pc, HiddenDriveNaming.parse("hidden-$pc.pfphd"))
        assertNull(HiddenDriveNaming.parse("labels-$pc.pfplb"))
        assertNull(HiddenDriveNaming.parse("hidden-nope.pfphd"))
    }

    @Test
    fun `a round request survives the worker's data`() {
        val request = MlSyncWorkData.request(MlSyncWorkData.input(MlSyncRequest.Round("user-1")))
        assertEquals(MlSyncRequest.Round("user-1"), request)
        assertEquals(MlSyncOperation.ROUND, request?.operation)
    }

    @Test
    fun `another device's search tombstone is never taken, a photo is indexed here instead`() {
        assertFalse(shouldImportSearchEmbedding(SearchImportMode.Merge, embeddedHere = false, tombstone = true, fromOtherDevice = true))
        assertTrue(shouldImportSearchEmbedding(SearchImportMode.Merge, embeddedHere = false, tombstone = true, fromOtherDevice = false))
        assertTrue(shouldImportSearchEmbedding(SearchImportMode.Merge, embeddedHere = false, tombstone = false, fromOtherDevice = true))
        assertFalse(shouldImportSearchEmbedding(SearchImportMode.Merge, embeddedHere = true, tombstone = false, fromOtherDevice = true))
    }

    @Test
    fun `the status line says syncing, never, failed or when`() {
        val never = SharedBackupStatus(enabled = true, lastAtMs = 0L, lastFailed = false)
        assertEquals(SharedBackupLine.SYNCING, sharedBackupLine(running = true, status = never))
        assertEquals(SharedBackupLine.NEVER, sharedBackupLine(running = false, status = never))
        assertEquals(SharedBackupLine.FAILED, sharedBackupLine(false, never.copy(lastAtMs = 5L, lastFailed = true)))
        assertEquals(SharedBackupLine.LAST, sharedBackupLine(false, never.copy(lastAtMs = 5L)))
    }
}
