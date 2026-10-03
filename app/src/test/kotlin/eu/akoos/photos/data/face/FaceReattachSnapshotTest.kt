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

import eu.akoos.photos.domain.usecase.ReattachLabel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.UTFDataFormatException

/**
 * Coverage for the reattach snapshot: the pure migration-step decision that keeps an interrupted wipe
 * from destroying a pending snapshot, the file round-trip that carries the removed-face boxes across
 * the format bump, the merge that lets new names join the ones still waiting without dropping any, and
 * the write that lands whole or not at all, with a file that cannot be read told apart from no file.
 * Plain JVM, only scratch files.
 */
class FaceReattachSnapshotTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `faces present with no pending snapshot captures a fresh one`() {
        assertEquals(ReattachMigrationStep.Capture, reattachMigrationStep(faceTableEmpty = false, snapshotPresent = false))
    }

    @Test
    fun `a pending snapshot is resumed and never overwritten`() {
        // A snapshot on disk is un-consumed curation. Whether or not faces are present (a wipe cut short
        // before its marker, or a second model bump before the first reattach drained), capturing over it
        // would lose every name it holds.
        assertEquals(ReattachMigrationStep.Resume, reattachMigrationStep(faceTableEmpty = true, snapshotPresent = true))
        assertEquals(ReattachMigrationStep.Resume, reattachMigrationStep(faceTableEmpty = false, snapshotPresent = true))
    }

    @Test
    fun `faces gone and no snapshot has nothing to preserve`() {
        assertEquals(ReattachMigrationStep.Skip, reattachMigrationStep(faceTableEmpty = true, snapshotPresent = false))
    }

    @Test
    fun `the snapshot round-trips members manual nots and removed boxes`() {
        val file = File.createTempFile("reattach", ".bin").apply { deleteOnExit() }
        val data = FaceReattachData(
            members = listOf(ReattachLabel("p1#0", "p1", 0.10f, 0.10f, 0.30f, 0.30f, "Ann")),
            manual = listOf(ReattachManual("Ann", "p9")),
            nots = listOf(ReattachLabel("p2#1", "p2", 0.40f, 0.40f, 0.60f, 0.60f, "Bob")),
            rejected = listOf(ReattachBox("p3", 0.50f, 0.50f, 0.70f, 0.70f)),
        )
        FaceReattachSnapshot.write(file, data)
        assertEquals(data, FaceReattachSnapshot.read(file))
    }

    @Test
    fun `a missing file reads back as null`() {
        val file = File.createTempFile("reattach", ".bin").apply { delete() }
        assertNull(FaceReattachSnapshot.read(file))
        assertEquals(ReattachLoad.Absent, FaceReattachSnapshot.load(file))
    }

    @Test
    fun `a merge keeps every name, mark, removal and manual photo either side carries`() {
        val merged = FaceReattachSnapshot.merge(under = OLD, over = NEW)
        assertEquals(NEW.members + OLD.members, merged.members)
        assertEquals(NEW.manual + OLD.manual, merged.manual)
        assertEquals(NEW.nots + OLD.nots, merged.nots)
        assertEquals(NEW.rejected + OLD.rejected, merged.rejected)
    }

    @Test
    fun `a rescan that captures nothing keeps every name still waiting`() {
        // Waiting names sit in the snapshot, not on the faces, until the drained walk reattaches them, so a
        // capture taken meanwhile can come back empty; merged in, it must not cost a single one.
        assertEquals(OLD, FaceReattachSnapshot.merge(under = OLD, over = data()))
    }

    @Test
    fun `on the same face the newer name wins and the older one goes`() {
        val merged = FaceReattachSnapshot.merge(
            under = data(members = listOf(label("p1", 0.1f, "Ann"))),
            over = data(members = listOf(label("p1", 0.1f, "Bea"))),
        )
        assertEquals(listOf(label("p1", 0.1f, "Bea")), merged.members)
    }

    @Test
    fun `a newer removal replaces a waiting name, and a newer name a waiting removal`() {
        val removed = FaceReattachSnapshot.merge(
            under = data(members = listOf(label("p1", 0.1f, "Ann"))),
            over = data(rejected = listOf(box("p1", 0.1f))),
        )
        assertTrue(removed.members.isEmpty())
        assertEquals(listOf(box("p1", 0.1f)), removed.rejected)

        val named = FaceReattachSnapshot.merge(
            under = data(rejected = listOf(box("p1", 0.1f))),
            over = data(members = listOf(label("p1", 0.1f, "Ann"))),
        )
        assertEquals(listOf(label("p1", 0.1f, "Ann")), named.members)
        assertTrue(named.rejected.isEmpty())
    }

    @Test
    fun `a face at another box is another face, so both names wait with the newer first`() {
        // The key is the exact box: one another detector drew a little apart is left to the reattach's own
        // overlap matching, where the entry listed first wins a tie.
        val merged = FaceReattachSnapshot.merge(
            under = data(members = listOf(label("p1", 0.10f, "Ann"))),
            over = data(members = listOf(label("p1", 0.11f, "Bea"))),
        )
        assertEquals(listOf(label("p1", 0.11f, "Bea"), label("p1", 0.10f, "Ann")), merged.members)
    }

    @Test
    fun `not this person marks add up but never contradict the name that wins`() {
        val merged = FaceReattachSnapshot.merge(
            under = data(
                members = listOf(label("p2", 0.1f, "Cid")),
                nots = listOf(label("p1", 0.1f, "Ann"), label("p1", 0.1f, "Dee"), label("p3", 0.1f, "Eve")),
            ),
            over = data(
                members = listOf(label("p1", 0.1f, "Ann")),
                nots = listOf(label("p2", 0.1f, "Cid"), label("p3", 0.1f, "Eve")),
            ),
        )
        // The face now named Ann drops only its "not Ann" mark; the face now marked "not Cid" drops the
        // name Cid; the repeated "not Eve" is kept once.
        assertEquals(listOf(label("p1", 0.1f, "Ann")), merged.members)
        assertEquals(
            listOf(label("p2", 0.1f, "Cid"), label("p3", 0.1f, "Eve"), label("p1", 0.1f, "Dee")),
            merged.nots,
        )
    }

    @Test
    fun `manual photos add up once each`() {
        val merged = FaceReattachSnapshot.merge(
            under = data(manual = listOf(ReattachManual("Ann", "p1"), ReattachManual("Bob", "p1"))),
            over = data(manual = listOf(ReattachManual("Ann", "p1"), ReattachManual("Ann", "p2"))),
        )
        assertEquals(
            listOf(ReattachManual("Ann", "p1"), ReattachManual("Ann", "p2"), ReattachManual("Bob", "p1")),
            merged.manual,
        )
    }

    @Test
    fun `entries repeated on one side collapse to the first`() {
        val merged = FaceReattachSnapshot.merge(
            under = data(),
            over = data(
                members = listOf(label("p1", 0.1f, "Ann"), label("p1", 0.1f, "Bea")),
                rejected = listOf(box("p2", 0.1f), box("p2", 0.1f)),
            ),
        )
        assertEquals(listOf(label("p1", 0.1f, "Ann")), merged.members)
        assertEquals(listOf(box("p2", 0.1f)), merged.rejected)
    }

    @Test
    fun `a write replaces the snapshot whole and leaves no temp file behind`() {
        val file = FaceReattachSnapshot.file(tmp.root)
        FaceReattachSnapshot.write(file, OLD)
        FaceReattachSnapshot.write(file, NEW)
        assertEquals(ReattachLoad.Loaded(NEW), FaceReattachSnapshot.load(file))
        assertEquals(listOf(file.name), tmp.root.list()!!.toList())
    }

    @Test
    fun `a crash between the temp write and the rename leaves the old snapshot intact`() {
        val file = FaceReattachSnapshot.file(tmp.root)
        FaceReattachSnapshot.write(file, OLD)
        // The new bytes are on disk beside it, and the rename never happens.
        val staged = FaceReattachSnapshot.stage(file, NEW)
        assertTrue(staged.isFile)
        assertEquals(ReattachLoad.Loaded(OLD), FaceReattachSnapshot.load(file))
        // The next write goes through over the leftover.
        FaceReattachSnapshot.write(file, NEW)
        assertEquals(ReattachLoad.Loaded(NEW), FaceReattachSnapshot.load(file))
        assertFalse(staged.exists())
    }

    @Test
    fun `a write that fails part way leaves the old snapshot intact`() {
        val file = FaceReattachSnapshot.file(tmp.root)
        FaceReattachSnapshot.write(file, OLD)
        // A name past the format's string limit throws in the middle of the write.
        val tooLong = data(members = listOf(label("p1", 0.1f, "x".repeat(70_000))))
        assertThrows(UTFDataFormatException::class.java) { FaceReattachSnapshot.write(file, tooLong) }
        assertEquals(ReattachLoad.Loaded(OLD), FaceReattachSnapshot.load(file))
        assertEquals(listOf(file.name), tmp.root.list()!!.toList())
    }

    @Test
    fun `a snapshot cut short reads as unreadable, never as nothing waiting`() {
        val file = FaceReattachSnapshot.file(tmp.root)
        FaceReattachSnapshot.write(file, OLD)
        val bytes = file.readBytes()
        file.writeBytes(bytes.copyOf(bytes.size / 2))
        assertEquals(ReattachLoad.Unreadable, FaceReattachSnapshot.load(file))
        assertNull(FaceReattachSnapshot.read(file))
    }

    @Test
    fun `a file that is no snapshot, or claims an impossible length, reads as unreadable`() {
        val file = FaceReattachSnapshot.file(tmp.root)
        file.writeBytes(byteArrayOf(0x13, 0x37, 0x00, 0x42))
        assertEquals(ReattachLoad.Unreadable, FaceReattachSnapshot.load(file))
        // A valid header whose first list claims more entries than the file could hold.
        val header = ByteArrayOutputStream().also { buffer ->
            DataOutputStream(buffer).use {
                it.writeUTF("PPFACERE")
                it.writeInt(2)
                it.writeInt(Int.MAX_VALUE)
            }
        }
        file.writeBytes(header.toByteArray())
        assertEquals(ReattachLoad.Unreadable, FaceReattachSnapshot.load(file))
    }

    @Test
    fun `an unreadable snapshot is discarded and a readable one never is`() {
        val file = FaceReattachSnapshot.file(tmp.root)
        FaceReattachSnapshot.write(file, OLD)
        assertFalse(FaceReattachSnapshot.discardIfUnreadable(file))
        assertEquals(ReattachLoad.Loaded(OLD), FaceReattachSnapshot.load(file))

        file.writeBytes(file.readBytes().copyOf(10))
        assertTrue(FaceReattachSnapshot.discardIfUnreadable(file))
        assertEquals(ReattachLoad.Absent, FaceReattachSnapshot.load(file))
        assertFalse(FaceReattachSnapshot.discardIfUnreadable(file))
    }

    @Test
    fun `an update merges into the waiting names and removes the file once nothing is left`() {
        val file = FaceReattachSnapshot.file(tmp.root)
        FaceReattachSnapshot.update(file) { waiting ->
            assertNull(waiting)
            OLD
        }
        FaceReattachSnapshot.update(file) { waiting -> FaceReattachSnapshot.merge(under = waiting!!, over = NEW) }
        assertEquals(ReattachLoad.Loaded(FaceReattachSnapshot.merge(OLD, NEW)), FaceReattachSnapshot.load(file))

        FaceReattachSnapshot.update(file) { data() }
        assertFalse(file.exists())
    }

    @Test
    fun `an update over an unreadable file starts from nothing`() {
        val file = FaceReattachSnapshot.file(tmp.root)
        file.writeBytes(byteArrayOf(1, 2, 3))
        FaceReattachSnapshot.update(file) { waiting ->
            assertNull(waiting)
            NEW
        }
        assertEquals(ReattachLoad.Loaded(NEW), FaceReattachSnapshot.load(file))
    }

    @Test
    fun `a consumed snapshot is cleared only while it still holds what was consumed`() {
        val file = FaceReattachSnapshot.file(tmp.root)
        FaceReattachSnapshot.write(file, OLD)
        // Names merged in after the reattach read the file wait for the next one.
        FaceReattachSnapshot.update(file) { waiting -> FaceReattachSnapshot.merge(under = waiting!!, over = NEW) }
        assertFalse(FaceReattachSnapshot.clearIfUnchanged(file, OLD))
        assertTrue(file.isFile)

        assertTrue(FaceReattachSnapshot.clearIfUnchanged(file, FaceReattachSnapshot.merge(OLD, NEW)))
        assertFalse(file.exists())
    }

    @Test
    fun `a partial reattach keeps the labels that found no face and lets the placed ones go`() {
        val waiting = data(
            members = listOf(label("p1", 0.1f, "Ann"), label("p2", 0.1f, "Bob"), label("p3", 0.1f, "Cid")),
            manual = listOf(ReattachManual("Ann", "p9")),
            nots = listOf(label("p1", 0.4f, "Bob"), label("p4", 0.1f, "Ann")),
            rejected = listOf(box("p2", 0.5f), box("p5", 0.1f)),
        )
        val applied = data(
            members = listOf(label("p1", 0.1f, "Ann")),
            manual = waiting.manual,
            nots = listOf(label("p1", 0.4f, "Bob")),
            rejected = listOf(box("p2", 0.5f)),
        )
        val left = FaceReattachSnapshot.remainder(waiting, applied, settledPhotos = emptySet())
        // The photos attached by hand need no face, so they never wait.
        assertEquals(
            data(
                members = listOf(label("p2", 0.1f, "Bob"), label("p3", 0.1f, "Cid")),
                nots = listOf(label("p4", 0.1f, "Ann")),
                rejected = listOf(box("p5", 0.1f)),
            ),
            left,
        )
    }

    @Test
    fun `an unmatched label goes only once its photo can never place it`() {
        // p2 was re-detected with no face for Bob, or is gone from the library; p3 is still to be scanned.
        val waiting = data(
            members = listOf(label("p2", 0.1f, "Bob"), label("p3", 0.1f, "Cid")),
            nots = listOf(label("p2", 0.4f, "Ann")),
            rejected = listOf(box("p2", 0.5f), box("p3", 0.5f)),
        )
        val left = FaceReattachSnapshot.remainder(waiting, data(), settledPhotos = setOf("p2"))
        assertEquals(data(members = listOf(label("p3", 0.1f, "Cid")), rejected = listOf(box("p3", 0.5f))), left)
    }

    @Test
    fun `a reattach over a failed or empty read lets no waiting name go`() {
        // Nothing found a face and no photo could be checked: every name, mark and removal waits.
        val left = FaceReattachSnapshot.remainder(OLD, data(manual = OLD.manual), settledPhotos = emptySet())
        assertEquals(OLD.copy(manual = emptyList()), left)

        val file = FaceReattachSnapshot.file(tmp.root)
        FaceReattachSnapshot.write(file, OLD)
        assertTrue(FaceReattachSnapshot.consumeIfUnchanged(file, OLD, left))
        assertEquals(ReattachLoad.Loaded(left), FaceReattachSnapshot.load(file))
    }

    @Test
    fun `a consume leaves the remainder on disk and removes the file once nothing is left`() {
        val file = FaceReattachSnapshot.file(tmp.root)
        FaceReattachSnapshot.write(file, OLD)
        val left = data(members = OLD.members)
        assertTrue(FaceReattachSnapshot.consumeIfUnchanged(file, OLD, left))
        assertEquals(ReattachLoad.Loaded(left), FaceReattachSnapshot.load(file))

        assertTrue(FaceReattachSnapshot.consumeIfUnchanged(file, left, data()))
        assertFalse(file.exists())
    }

    @Test
    fun `a consume never lands over names merged in after the read`() {
        val file = FaceReattachSnapshot.file(tmp.root)
        FaceReattachSnapshot.write(file, OLD)
        FaceReattachSnapshot.update(file) { waiting -> FaceReattachSnapshot.merge(under = waiting!!, over = NEW) }
        assertFalse(FaceReattachSnapshot.consumeIfUnchanged(file, OLD, data()))
        assertEquals(ReattachLoad.Loaded(FaceReattachSnapshot.merge(OLD, NEW)), FaceReattachSnapshot.load(file))
    }

    @Test
    fun `a consume that changes nothing leaves the file and its stamp as they were`() {
        val file = FaceReattachSnapshot.file(tmp.root)
        FaceReattachSnapshot.write(file, OLD)
        val before = FaceReattachSnapshot.stamp(file)
        assertTrue(FaceReattachSnapshot.consumeIfUnchanged(file, OLD, OLD))
        assertEquals(before, FaceReattachSnapshot.stamp(file))
        assertEquals(before, FaceReattachSnapshot.stampIfHolds(file, OLD))
    }

    @Test
    fun `a stamp is read only while the file holds what the reattach left`() {
        val file = FaceReattachSnapshot.file(tmp.root)
        assertNull(FaceReattachSnapshot.stamp(file))
        FaceReattachSnapshot.write(file, OLD)
        assertEquals(ReattachStamp(file.lastModified(), file.length()), FaceReattachSnapshot.stampIfHolds(file, OLD))
        assertNull(FaceReattachSnapshot.stampIfHolds(file, NEW))
        FaceReattachSnapshot.clear(file)
        assertNull(FaceReattachSnapshot.stampIfHolds(file, OLD))
    }

    private fun label(photoKey: String, at: Float, name: String) =
        ReattachLabel("$photoKey#0", photoKey, at, at, at + 0.2f, at + 0.2f, name)

    private fun box(photoKey: String, at: Float) = ReattachBox(photoKey, at, at, at + 0.2f, at + 0.2f)

    private fun data(
        members: List<ReattachLabel> = emptyList(),
        manual: List<ReattachManual> = emptyList(),
        nots: List<ReattachLabel> = emptyList(),
        rejected: List<ReattachBox> = emptyList(),
    ) = FaceReattachData(members, manual, nots, rejected)

    private companion object {
        val OLD = FaceReattachData(
            members = listOf(ReattachLabel("p1#0", "p1", 0.10f, 0.10f, 0.30f, 0.30f, "Ann")),
            manual = listOf(ReattachManual("Ann", "p9")),
            nots = listOf(ReattachLabel("p2#1", "p2", 0.40f, 0.40f, 0.60f, 0.60f, "Bob")),
            rejected = listOf(ReattachBox("p3", 0.50f, 0.50f, 0.70f, 0.70f)),
        )
        val NEW = FaceReattachData(
            members = listOf(ReattachLabel("p4#0", "p4", 0.20f, 0.20f, 0.40f, 0.40f, "Cid")),
            manual = listOf(ReattachManual("Cid", "p8")),
            nots = listOf(ReattachLabel("p5#0", "p5", 0.10f, 0.10f, 0.30f, 0.30f, "Dee")),
            rejected = listOf(ReattachBox("p6", 0.10f, 0.10f, 0.30f, 0.30f)),
        )
    }
}
