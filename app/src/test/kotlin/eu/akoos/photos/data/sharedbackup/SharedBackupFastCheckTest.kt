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

import eu.akoos.photos.data.mlsync.AlbumOrderDriveNaming
import eu.akoos.photos.data.mlsync.DriveDataFolder
import eu.akoos.photos.data.mlsync.HiddenDriveNaming
import eu.akoos.photos.data.mlsync.LabelDriveNaming
import eu.akoos.photos.data.mlsync.MlDriveFile
import eu.akoos.photos.data.mlsync.MlDriveFileKind
import eu.akoos.photos.data.mlsync.MlDriveNaming
import eu.akoos.photos.data.mlsync.SettingsDriveNaming
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The rules of the quicker shared backup: the flush as the app is left, the throttled check and what it compares. */
@OptIn(ExperimentalCoroutinesApi::class)
class SharedBackupFastCheckTest {

    private val own = "22222222-2222-4222-8222-222222222222"
    private val pc = "11111111-1111-4111-8111-111111111111"

    private fun file(name: String, revision: String, uid: String = name) = MlDriveFile(
        nodeUid = uid, name = name, sizeBytes = 10, activeRevisionUid = revision,
        serverModifiedAtMs = 1L, serverCreatedAtMs = 0L, claimedModifiedAtMs = null,
    )

    private fun listing(
        pcLabelsRevision: String = "r1",
        ownLabelsRevision: String = "o1",
        pcAlbumOrderRevision: String = "a1",
    ) = SharedBackupListing(
        ml = listOf(file(MlDriveNaming.fileName(MlDriveFileKind.FACES, pc), "f1")),
        labels = listOf(
            file(LabelDriveNaming.fileName(pc), pcLabelsRevision),
            file(LabelDriveNaming.fileName(own), ownLabelsRevision),
            file("notes.txt", "x1"),
        ),
        hidden = listOf(file(HiddenDriveNaming.fileName(pc), "h1")),
        settings = listOf(file(SettingsDriveNaming.fileName(pc), "s1")),
        albumOrder = listOf(
            file(AlbumOrderDriveNaming.fileName(pc), pcAlbumOrderRevision),
            file(AlbumOrderDriveNaming.fileName(own), "oa1"),
        ),
    )

    @Test
    fun `the round goes out as the app is left only when a change waits for it`() {
        val now = 10_000_000L
        assertFalse(flushOnBackground(firstWaitingChangeMs = 0L, lastFailed = false, lastAtMs = 0L, nowMs = now))
        assertTrue(flushOnBackground(firstWaitingChangeMs = now - 5_000L, lastFailed = false, lastAtMs = 0L, nowMs = now))
    }

    @Test
    fun `a failed round's wait holds the flush back until it ran out`() {
        val now = 10_000_000L
        val waiting = now - 5_000L
        assertFalse(flushOnBackground(waiting, lastFailed = true, lastAtMs = now - 60_000L, nowMs = now))
        assertTrue(flushOnBackground(waiting, lastFailed = true, lastAtMs = now - SharedBackupScheduler.SAVE_AFTER_MS, nowMs = now))
    }

    @Test
    fun `the folders are listed at most once a minute`() {
        val now = 10_000_000L
        assertFalse(checkTooSoon(now, lastCheckMs = 0L))
        assertTrue(checkTooSoon(now, lastCheckMs = now - 30_000L))
        assertTrue(checkTooSoon(now, lastCheckMs = now - 59_999L))
        assertFalse(checkTooSoon(now, lastCheckMs = now - 60_000L))
        // A clock set back is not a reason to stop checking.
        assertFalse(checkTooSoon(now, lastCheckMs = now + 30_000L))
    }

    private val allowed = CheckConditions(
        enabled = true, signedIn = true, signingOut = false, online = true, networkAllowed = true, lastFailed = false,
    )

    @Test
    fun `nothing is listed when off, for a guest or signed out, signing out, offline, on a barred network or after a failure`() {
        assertTrue(checkAllowed(allowed))
        assertFalse(checkAllowed(allowed.copy(enabled = false)))
        assertFalse(checkAllowed(allowed.copy(signedIn = false)))
        assertFalse(checkAllowed(allowed.copy(signingOut = true)))
        assertFalse(checkAllowed(allowed.copy(online = false)))
        assertFalse(checkAllowed(allowed.copy(networkAllowed = false)))
        assertFalse(checkAllowed(allowed.copy(lastFailed = true)))
    }

    @Test
    fun `a round runs only when another device's file is new since the last round saw the folders`() {
        val seen = seenRevisions(listing())
        assertTrue(newerFiles(listing(), own, ownCopyTaken = true, seen = seen).isEmpty())

        val replaced = newerFiles(listing(pcLabelsRevision = "r2"), own, ownCopyTaken = true, seen = seen)
        assertEquals(setOf(DriveDataFolder.LABELS), replaced.keys)
        assertEquals(listOf("r2"), replaced.getValue(DriveDataFolder.LABELS).map { it.activeRevisionUid })
    }

    @Test
    fun `another device's newer album order file starts a round`() {
        val seen = seenRevisions(listing())
        val replaced = newerFiles(listing(pcAlbumOrderRevision = "a2"), own, ownCopyTaken = true, seen = seen)
        assertEquals(setOf(DriveDataFolder.ALBUM_ORDER), replaced.keys)
        assertEquals(
            listOf(AlbumOrderDriveNaming.fileName(pc)),
            replaced.getValue(DriveDataFolder.ALBUM_ORDER).map { it.name },
        )
    }

    @Test
    fun `a device never seen before counts as new, and nothing counts before a first round`() {
        val all = newerFiles(listing(), own, ownCopyTaken = true, seen = emptyMap())
        assertEquals(
            setOf(
                DriveDataFolder.ML, DriveDataFolder.LABELS, DriveDataFolder.HIDDEN, DriveDataFolder.SETTINGS,
                DriveDataFolder.ALBUM_ORDER,
            ),
            all.keys,
        )
        // Only the other device's labels file: never this install's own, never a name no install writes.
        assertEquals(listOf(LabelDriveNaming.fileName(pc)), all.getValue(DriveDataFolder.LABELS).map { it.name })
    }

    @Test
    fun `this install's own file counts only while its copies were not taken in yet`() {
        val seen = seenRevisions(listing())
        val ownRewritten = listing(ownLabelsRevision = "o2")
        assertTrue(newerFiles(ownRewritten, own, ownCopyTaken = true, seen = seen).isEmpty())
        assertEquals(
            listOf(LabelDriveNaming.fileName(own)),
            newerFiles(ownRewritten, own, ownCopyTaken = false, seen = seen).getValue(DriveDataFolder.LABELS).map { it.name },
        )
    }

    @Test
    fun `the check runs at once and every two minutes while in the foreground, and stops in the background`() = runTest {
        var checks = 0
        val loop = ForegroundCheckLoop(backgroundScope) { checks++ }
        runCurrent()
        assertEquals(0, checks)

        loop.foreground()
        runCurrent()
        assertEquals(1, checks)
        advanceTimeBy(CHECK_EVERY_MS - 1)
        runCurrent()
        assertEquals(1, checks)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(2, checks)
        advanceTimeBy(CHECK_EVERY_MS)
        runCurrent()
        assertEquals(3, checks)

        // A second start while already in the foreground does not double the loop.
        loop.foreground()
        runCurrent()
        assertEquals(3, checks)

        loop.background()
        assertFalse(loop.running)
        advanceTimeBy(10 * CHECK_EVERY_MS)
        runCurrent()
        assertEquals(3, checks)

        loop.foreground()
        runCurrent()
        assertEquals(4, checks)
        loop.background()
    }

    @Test
    fun `a check that fails keeps the loop going`() = runTest {
        var checks = 0
        val loop = ForegroundCheckLoop(backgroundScope) {
            checks++
            error("listing failed")
        }
        loop.foreground()
        runCurrent()
        advanceTimeBy(CHECK_EVERY_MS)
        runCurrent()
        assertEquals(2, checks)
        loop.background()
    }
}
