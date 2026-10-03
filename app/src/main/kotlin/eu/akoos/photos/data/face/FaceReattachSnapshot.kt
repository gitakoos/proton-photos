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
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.io.UTFDataFormatException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** A photo attached to a person by name rather than by a detected face, carried across a rebuild. */
data class ReattachManual(val name: String, val photoKey: String)

/** A removed ("not a person") face reduced to where it sat, carried across a rebuild so the removal can
 *  be re-applied to whichever re-detected face lands in the same place. No name and no embedding: only
 *  the box a geometry match needs. */
data class ReattachBox(
    val photoKey: String,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
)

/**
 * The names and curation waiting to be put back on a freshly detected library. [members] are the faces a
 * person is made of (rebound to the re-detected faces by [eu.akoos.photos.domain.usecase.matchReattachLabels]),
 * [manual] are photos attached to a person by hand, [nots] are faces marked "not this person", and
 * [rejected] are faces the user removed from People entirely. A model swap fills [members], [nots] and
 * [rejected] from the local library; an import from another device fills [members], [manual] and [nots].
 */
data class FaceReattachData(
    val members: List<ReattachLabel>,
    val manual: List<ReattachManual>,
    val nots: List<ReattachLabel>,
    val rejected: List<ReattachBox> = emptyList(),
) {
    /** Whether it carries nothing to put back. */
    fun isEmpty(): Boolean = members.isEmpty() && manual.isEmpty() && nots.isEmpty() && rejected.isEmpty()
}

/** What [FaceReattachSnapshot.load] found on disk. */
sealed interface ReattachLoad {
    /** No snapshot: no names wait. */
    data object Absent : ReattachLoad

    /** A file whose content does not parse (cut short, damaged, or in a format this build does not read),
     *  so nothing in it can ever come back. */
    data object Unreadable : ReattachLoad

    data class Loaded(val data: FaceReattachData) : ReattachLoad
}

/** Where a snapshot file stands on disk: when it was last written and how long it is. A write moves it. */
data class ReattachStamp(val modifiedMs: Long, val length: Long)

/** What a model-version migration should do with the reattach snapshot. */
enum class ReattachMigrationStep { Capture, Resume, Skip }

/**
 * The snapshot step for a migration entered because the stored model marker no longer matches. A snapshot
 * still on disk is un-consumed curation from an earlier wipe whose reattach has not placed it yet (the
 * reattach removes each entry once it is back), so it must never be overwritten: capturing over it from a
 * table that does not carry those names yet would lose every one of them. That covers both a wipe
 * interrupted before its marker and a second model bump landing before the first migration's reattach
 * drained. Pure so it is unit tested directly.
 *
 * - A snapshot already present: [Resume] it, never re-capture over pending curation.
 * - No snapshot and faces still present: [Capture] a fresh one from the live table before the wipe.
 * - No snapshot and no faces: nothing to preserve, [Skip].
 */
fun reattachMigrationStep(faceTableEmpty: Boolean, snapshotPresent: Boolean): ReattachMigrationStep = when {
    snapshotPresent -> ReattachMigrationStep.Resume
    !faceTableEmpty -> ReattachMigrationStep.Capture
    else -> ReattachMigrationStep.Skip
}

/**
 * A single small file, written just before a face wipe (or by an import) and consumed once the library
 * has been re-detected, holding the model-independent curation the wipe would otherwise lose. It carries
 * no embedding, only where each named or removed face sat, so it stays valid no matter which recognition
 * model rebuilds the vectors.
 *
 * A write lands whole or not at all ([write]), so a reader only ever meets a complete snapshot. A file
 * that still does not parse (cut short, or damaged on disk) reads as [ReattachLoad.Unreadable], which the
 * drained walk discards rather than leave it holding every grouping back. Every change goes through one
 * lock, so a read-merge-write ([update]) never drops names another writer added meanwhile.
 */
object FaceReattachSnapshot {

    private const val MAGIC = "PPFACERE"
    // v2 adds the removed-face box list; a v1 file has none and reads its rejected list back as empty.
    private const val VERSION = 2
    private const val MIN_READABLE_VERSION = 1
    const val FILE_NAME = "pending_reattach.bin"
    private const val TEMP_SUFFIX = ".tmp"

    fun file(dir: File): File = File(dir, FILE_NAME)

    /**
     * [under] and [over] as one snapshot: everything either carries, each entry once. A face is keyed by
     * its photo and its exact box. Where both speak for the same face, [over]'s word stands: its name or
     * its removal replaces [under]'s, a "not this person" mark in [under] for the very name [over] gives
     * the face is dropped, and so is a name in [under] that [over] marks the face as not being. The other
     * marks and the photos attached by hand simply add up. [over]'s entries come first, so where two still
     * compete for one re-detected face at the reattach (boxes another detector drew a little apart), a tie
     * there goes to [over] as well. Pure so it is unit tested directly.
     */
    fun merge(under: FaceReattachData, over: FaceReattachData): FaceReattachData {
        val overFaces = HashSet<FaceKey>()
        over.members.mapTo(overFaces) { it.faceKey() }
        over.rejected.mapTo(overFaces) { it.faceKey() }
        val overNames = over.members.mapTo(HashSet()) { it.faceKey() to it.name }
        val overNots = over.nots.mapTo(HashSet()) { it.faceKey() to it.name }
        return FaceReattachData(
            members = (over.members + under.members.filter {
                it.faceKey() !in overFaces && (it.faceKey() to it.name) !in overNots
            }).distinctBy { it.faceKey() },
            manual = (over.manual + under.manual).distinct(),
            nots = (over.nots + under.nots.filter { (it.faceKey() to it.name) !in overNames })
                .distinctBy { it.faceKey() to it.name },
            rejected = (over.rejected + under.rejected.filter { it.faceKey() !in overFaces })
                .distinctBy { it.faceKey() },
        )
    }

    /**
     * Writes [data] as the snapshot. It lands whole or not at all: the bytes go to a file beside [file],
     * are flushed to storage, and only then renamed over it, so a crash or a failed write leaves the
     * previous snapshot as it was.
     */
    @Synchronized
    fun write(file: File, data: FaceReattachData) {
        val temp = stage(file, data)
        try {
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } catch (e: Throwable) {
            temp.delete()
            throw e
        }
    }

    /** The first half of [write]: [data] written in full to the file beside [file] and flushed to storage,
     *  [file] itself untouched. Returns that file. */
    internal fun stage(file: File, data: FaceReattachData): File {
        val temp = tempFor(file)
        temp.parentFile?.mkdirs()
        try {
            FileOutputStream(temp).use { stream ->
                val out = DataOutputStream(BufferedOutputStream(stream))
                writeData(out, data)
                out.flush()
                stream.fd.sync()
            }
        } catch (e: Throwable) {
            temp.delete()
            throw e
        }
        return temp
    }

    /** The snapshot, or null when there is none or it cannot be read. */
    fun read(file: File): FaceReattachData? =
        (runCatching { load(file) }.getOrNull() as? ReattachLoad.Loaded)?.data

    /**
     * What is on disk: [ReattachLoad.Absent], [ReattachLoad.Unreadable] for a file whose content does not
     * parse, or the data. A file that cannot be opened for any other reason throws, so a snapshot that
     * merely failed to open is never taken for one that is gone or broken.
     */
    @Synchronized
    fun load(file: File): ReattachLoad {
        if (!file.isFile) return ReattachLoad.Absent
        val stream = try {
            FileInputStream(file)
        } catch (e: FileNotFoundException) {
            return ReattachLoad.Absent
        }
        return DataInputStream(BufferedInputStream(stream)).use { inp ->
            try {
                ReattachLoad.Loaded(readData(inp, file.length()))
            } catch (e: EOFException) {
                ReattachLoad.Unreadable
            } catch (e: UTFDataFormatException) {
                ReattachLoad.Unreadable
            } catch (e: MalformedSnapshotException) {
                ReattachLoad.Unreadable
            }
        }
    }

    /**
     * Replaces the snapshot with [transform] of it, the read and the write one step against every other
     * change made here. [transform] gets the waiting data, or null when there is none or it cannot be
     * read; a null or empty result removes the file. Throws, writing nothing, when the file cannot be
     * opened.
     */
    @Synchronized
    fun update(file: File, transform: (FaceReattachData?) -> FaceReattachData?) {
        val next = transform((load(file) as? ReattachLoad.Loaded)?.data)
        if (next == null || next.isEmpty()) clear(file) else write(file, next)
    }

    /** Deletes [file] when it is there but its content cannot be read, and says whether it did. */
    @Synchronized
    fun discardIfUnreadable(file: File): Boolean {
        if (load(file) != ReattachLoad.Unreadable) return false
        clear(file)
        return true
    }

    /**
     * What of [data] still waits once a reattach has put [applied] back: every name, "not this person" mark
     * and removed face it did not place, except the ones on a photo in [settledPhotos] (already re-detected
     * with no face for them, or gone from the library), which no later pass can place. The photos attached
     * by hand need no face, so they go back at once and never wait. Pure so it is unit tested directly.
     */
    fun remainder(data: FaceReattachData, applied: FaceReattachData, settledPhotos: Set<String>): FaceReattachData {
        val members = applied.members.toHashSet()
        val nots = applied.nots.toHashSet()
        val rejected = applied.rejected.toHashSet()
        return FaceReattachData(
            members = data.members.filter { it !in members && it.photoKey !in settledPhotos },
            manual = emptyList(),
            nots = data.nots.filter { it !in nots && it.photoKey !in settledPhotos },
            rejected = data.rejected.filter { it !in rejected && it.photoKey !in settledPhotos },
        )
    }

    /**
     * Leaves [remainder] in [file], what is left of [consumed] once a reattach has put part of it back, and
     * removes the file once nothing is left, but only while it still holds exactly [consumed], so names
     * written in after [consumed] was read wait whole for the next reattach. Says whether it consumed.
     */
    @Synchronized
    fun consumeIfUnchanged(file: File, consumed: FaceReattachData, remainder: FaceReattachData): Boolean {
        val current = runCatching { load(file) }.getOrNull()
        if (current !is ReattachLoad.Loaded || current.data != consumed) return false
        when {
            remainder.isEmpty() -> clear(file)
            remainder != consumed -> write(file, remainder)
        }
        return true
    }

    /** Deletes [file] only while it still holds exactly [consumed], so names written in after [consumed]
     *  was read wait for the next reattach instead of going with it. Says whether it deleted. */
    @Synchronized
    fun clearIfUnchanged(file: File, consumed: FaceReattachData): Boolean =
        consumeIfUnchanged(file, consumed, FaceReattachData(emptyList(), emptyList(), emptyList()))

    /** Where [file] stands on disk, or null when there is none. */
    @Synchronized
    fun stamp(file: File): ReattachStamp? =
        if (file.isFile) ReattachStamp(file.lastModified(), file.length()) else null

    /** [file]'s [stamp] while it holds exactly [data], else null. */
    @Synchronized
    fun stampIfHolds(file: File, data: FaceReattachData): ReattachStamp? {
        val current = runCatching { load(file) }.getOrNull()
        return if (current is ReattachLoad.Loaded && current.data == data) stamp(file) else null
    }

    /** Deletes the snapshot and any temp file a write cut short left beside it. */
    @Synchronized
    fun clear(file: File) {
        runCatching { file.delete() }
        runCatching { tempFor(file).delete() }
    }

    private fun tempFor(file: File): File = File(file.absoluteFile.parentFile, file.name + TEMP_SUFFIX)

    private fun writeData(out: DataOutputStream, data: FaceReattachData) {
        out.writeUTF(MAGIC)
        out.writeInt(VERSION)
        writeLabels(out, data.members)
        out.writeInt(data.manual.size)
        for (m in data.manual) {
            out.writeUTF(m.name)
            out.writeUTF(m.photoKey)
        }
        writeLabels(out, data.nots)
        writeBoxes(out, data.rejected)
    }

    private fun readData(inp: DataInputStream, fileLength: Long): FaceReattachData {
        if (inp.readUTF() != MAGIC) throw MalformedSnapshotException()
        val version = inp.readInt()
        if (version < MIN_READABLE_VERSION || version > VERSION) throw MalformedSnapshotException()
        val members = readLabels(inp, fileLength)
        val manual = List(readCount(inp, fileLength)) { ReattachManual(inp.readUTF(), inp.readUTF()) }
        val nots = readLabels(inp, fileLength)
        val rejected = if (version >= 2) readBoxes(inp, fileLength) else emptyList()
        return FaceReattachData(members, manual, nots, rejected)
    }

    /** A list length as written, refused when negative or beyond what the file could hold, so a damaged
     *  length never turns into a huge allocation. */
    private fun readCount(inp: DataInputStream, fileLength: Long): Int {
        val count = inp.readInt()
        if (count < 0 || count > fileLength) throw MalformedSnapshotException()
        return count
    }

    private fun writeLabels(out: DataOutputStream, labels: List<ReattachLabel>) {
        out.writeInt(labels.size)
        for (l in labels) {
            out.writeUTF(l.faceId)
            out.writeUTF(l.photoKey)
            out.writeFloat(l.left); out.writeFloat(l.top); out.writeFloat(l.right); out.writeFloat(l.bottom)
            out.writeUTF(l.name)
        }
    }

    private fun readLabels(inp: DataInputStream, fileLength: Long): List<ReattachLabel> =
        List(readCount(inp, fileLength)) {
            ReattachLabel(
                faceId = inp.readUTF(),
                photoKey = inp.readUTF(),
                left = inp.readFloat(), top = inp.readFloat(),
                right = inp.readFloat(), bottom = inp.readFloat(),
                name = inp.readUTF(),
            )
        }

    private fun writeBoxes(out: DataOutputStream, boxes: List<ReattachBox>) {
        out.writeInt(boxes.size)
        for (b in boxes) {
            out.writeUTF(b.photoKey)
            out.writeFloat(b.left); out.writeFloat(b.top); out.writeFloat(b.right); out.writeFloat(b.bottom)
        }
    }

    private fun readBoxes(inp: DataInputStream, fileLength: Long): List<ReattachBox> =
        List(readCount(inp, fileLength)) {
            ReattachBox(
                photoKey = inp.readUTF(),
                left = inp.readFloat(), top = inp.readFloat(),
                right = inp.readFloat(), bottom = inp.readFloat(),
            )
        }

    /** A face as [merge] keys it: its photo and its exact box. */
    private data class FaceKey(
        val photoKey: String,
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float,
    )

    private fun ReattachLabel.faceKey() = FaceKey(photoKey, left, top, right, bottom)

    private fun ReattachBox.faceKey() = FaceKey(photoKey, left, top, right, bottom)

    /** The file is there but is not a snapshot this build can read. */
    private class MalformedSnapshotException : IOException("not a readable reattach snapshot")
}
