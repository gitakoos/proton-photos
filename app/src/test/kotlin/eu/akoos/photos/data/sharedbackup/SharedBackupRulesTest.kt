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

import eu.akoos.photos.data.db.dao.DriveCopyAppliedDao
import eu.akoos.photos.data.db.dao.FaceScanDao
import eu.akoos.photos.data.db.dao.ImageEmbeddingDao
import eu.akoos.photos.data.db.entity.DriveCopyAppliedEntity
import eu.akoos.photos.data.mlsync.HiddenDriveNaming
import eu.akoos.photos.data.mlsync.MlDriveFileKind
import eu.akoos.photos.data.mlsync.LabelDriveNaming
import eu.akoos.photos.data.mlsync.MlDriveFile
import eu.akoos.photos.data.mlsync.MlDriveNaming
import eu.akoos.photos.data.mlsync.MlSyncOperation
import eu.akoos.photos.data.mlsync.MlSyncRequest
import eu.akoos.photos.data.mlsync.MlSyncWorkData
import eu.akoos.photos.data.mlsync.RoundMode
import eu.akoos.photos.data.mlsync.SearchImportMode
import eu.akoos.photos.data.mlsync.shouldImportSearchEmbedding
import eu.akoos.photos.presentation.settings.SharedBackupLine
import eu.akoos.photos.presentation.settings.sharedBackupLine
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import me.proton.core.domain.entity.UserId
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
    fun `after a reinstall or a sign-out, the copy named after this install is read like another device's`() {
        val files = listOf(
            file(LabelDriveNaming.fileName(pc), claimed = 100),
            file(LabelDriveNaming.fileName(own), claimed = 300),
        )
        // The round reads as an id no copy carries until it has taken this install's own copy in.
        val readsAs = ""
        assertEquals(setOf(pc, own), newerCopies(files, LabelDriveNaming::parse, readsAs, emptyMap()).map { it.deviceId }.toSet())
    }

    @Test
    fun `the backups on Drive list each device once, with its newest copy's time and all its files`() {
        val listing = SharedBackupListing(
            ml = listOf(file(MlDriveNaming.fileName(MlDriveFileKind.FACES, pc), claimed = 100), file("notes.txt", claimed = 900)),
            labels = listOf(file(LabelDriveNaming.fileName(pc), claimed = 400), file(LabelDriveNaming.fileName(tablet), claimed = 50)),
            hidden = listOf(file(HiddenDriveNaming.fileName(tablet), claimed = 70)),
        )
        val devices = copiesByDevice(listing, mapOf(pc to "Galaxy S23"))
        assertEquals(listOf(pc, tablet), devices.map { it.deviceId })
        assertEquals(listOf(400L, 70L), devices.map { it.lastSavedMs })
        assertEquals(listOf("Galaxy S23", null), devices.map { it.name })
        assertEquals(2, devices.first().nodeUids.size)
    }

    @Test
    fun `a reinstall on the same phone keeps the install id, so it writes on in its earlier files`() {
        val first = MlDriveNaming.stableDeviceId("a1b2c3d4e5f60718")
        assertEquals(first, MlDriveNaming.stableDeviceId("A1B2C3D4E5F60718"))
        assertTrue(MlDriveNaming.isDeviceId(first))
        assertFalse(first == MlDriveNaming.stableDeviceId("0000000000000001"))
    }

    @Test
    fun `without a usable Android id every install gets a fresh random id`() {
        val a = MlDriveNaming.stableDeviceId(null)
        val b = MlDriveNaming.stableDeviceId("9774d56d682e549c")
        assertTrue(MlDriveNaming.isDeviceId(a) && MlDriveNaming.isDeviceId(b))
        assertFalse(a == b)
        assertFalse(a == MlDriveNaming.stableDeviceId(""))
    }

    @Test
    fun `a copy Drive gives no claimed time for is judged by when the server stored it`() {
        val copies = newerCopies(listOf(file(HiddenDriveNaming.fileName(pc), claimed = null, server = 70)), HiddenDriveNaming::parse, own, mapOf(pc to 60L))
        assertEquals(70L, copies.single().madeAtMs)
    }

    private fun runner(facesHere: Boolean, embeddingsHere: Int, appliedFaces: Long): SharedBackupRunner {
        val appliedDao = mockk<DriveCopyAppliedDao>()
        coEvery { appliedDao.forKind("u", SharedBackupKind.FACES.key) } returns
            listOf(DriveCopyAppliedEntity("u", SharedBackupKind.FACES.key, pc, appliedFaces))
        coEvery { appliedDao.forKind("u", SharedBackupKind.SEARCH.key) } returns
            listOf(DriveCopyAppliedEntity("u", SharedBackupKind.SEARCH.key, pc, appliedFaces))
        val faceScanDao = mockk<FaceScanDao>()
        coEvery { faceScanDao.hasAnyForUser("u") } returns facesHere
        val embeddings = mockk<ImageEmbeddingDao>()
        coEvery { embeddings.countForUser("u") } returns embeddingsHere
        return SharedBackupRunner(
            context = mockk(relaxed = true),
            driveStore = mockk(relaxed = true),
            envelopeCrypto = mockk(relaxed = true),
            labels = mockk(relaxed = true),
            hidden = mockk(relaxed = true),
            appliedDao = appliedDao,
            store = mockk(relaxed = true),
            faceScanDao = faceScanDao,
            imageEmbeddingDao = embeddings,
        )
    }

    private val mlListing = SharedBackupListing(
        ml = listOf(
            file("faces-$pc.pfpml", claimed = 100),
            file("search-$pc.pfpml", claimed = 100),
        ),
        labels = emptyList(),
        hidden = emptyList(),
    )

    @Test
    fun `face and search copies taken in once are not read again while this device holds data of that kind`() =
        runTest {
            val files = runner(facesHere = true, embeddingsHere = 5, appliedFaces = 100).mlFilesToTake(UserId("u"), mlListing, own)
            assertTrue(files.isEmpty())
        }

    @Test
    fun `a kind this device holds nothing of takes the other installs' copies again`() = runTest {
        val files = runner(facesHere = false, embeddingsHere = 0, appliedFaces = 100).mlFilesToTake(UserId("u"), mlListing, own)
        assertEquals(setOf(MlDriveFileKind.FACES, MlDriveFileKind.SEARCH), files.map { it.kind }.toSet())
    }

    @Test
    fun `a scan's changes go out once an hour, a change the user made at the next round`() {
        val hour = ML_SAVE_GAP_MS
        assertTrue(mlSaveDue(changedAt = 10, userChangedAt = 0, savedAt = 0, nowMs = 10))
        assertFalse(mlSaveDue(changedAt = 10, userChangedAt = 0, savedAt = 10, nowMs = 20))
        assertFalse(mlSaveDue(changedAt = 1_000, userChangedAt = 0, savedAt = 500, nowMs = 500 + hour - 1))
        assertTrue(mlSaveDue(changedAt = 1_000, userChangedAt = 0, savedAt = 500, nowMs = 500 + hour))
        assertTrue(mlSaveDue(changedAt = 1_000, userChangedAt = 900, savedAt = 500, nowMs = 1_000))
        assertFalse(mlSaveDue(changedAt = 1_000, userChangedAt = 400, savedAt = 500, nowMs = 1_000))
    }

    @Test
    fun `a round after the user's changes waits three minutes, and never past ten after the first`() {
        val minute = 60_000L
        assertEquals(3 * minute, soonRoundDelay(nowMs = 0, firstWaitingMs = 0))
        assertEquals(3 * minute, soonRoundDelay(nowMs = 5 * minute, firstWaitingMs = 0))
        assertEquals(2 * minute, soonRoundDelay(nowMs = 8 * minute, firstWaitingMs = 0))
        assertEquals(0L, soonRoundDelay(nowMs = 12 * minute, firstWaitingMs = 0))
    }

    @Test
    fun `the hidden list has one file per install`() {
        assertEquals("hidden-$pc.pfphd", HiddenDriveNaming.fileName(pc))
        assertEquals(pc, HiddenDriveNaming.parse("hidden-$pc.pfphd"))
        assertNull(HiddenDriveNaming.parse("labels-$pc.pfplb"))
        assertNull(HiddenDriveNaming.parse("hidden-nope.pfphd"))
    }

    @Test
    fun `load from Drive takes every other device's copies in again, even ones taken in before`() = runTest {
        val files = runner(facesHere = true, embeddingsHere = 5, appliedFaces = 100)
            .mlFilesToTake(UserId("u"), mlListing, own, retake = true)
        assertEquals(setOf(MlDriveFileKind.FACES, MlDriveFileKind.SEARCH), files.map { it.kind }.toSet())
    }

    @Test
    fun `a manual round keeps its mode through the worker's data`() {
        for (mode in RoundMode.entries) {
            val request = MlSyncWorkData.request(MlSyncWorkData.input(MlSyncRequest.Round("user-1", mode)))
            assertEquals(MlSyncRequest.Round("user-1", mode), request)
        }
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
