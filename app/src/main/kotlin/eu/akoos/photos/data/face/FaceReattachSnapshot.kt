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
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File

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
)

/** What a model-version migration should do with the reattach snapshot. */
enum class ReattachMigrationStep { Capture, Resume, Skip }

/**
 * The snapshot step for a migration entered because the stored model marker no longer matches. A snapshot
 * still on disk is un-consumed curation from an earlier wipe whose reattach has not run yet (the reattach
 * clears it as its last act), so it must never be overwritten: capturing over it from a table that does
 * not carry those names yet would lose every one of them. That covers both a wipe interrupted before its
 * marker and a second model bump landing before the first migration's reattach drained. Pure so it is
 * unit tested directly.
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
 * model rebuilds the vectors. A corrupt or absent file reads back as null, so a failed write can never
 * wedge the walk, only skip a reattach.
 */
object FaceReattachSnapshot {

    private const val MAGIC = "PPFACERE"
    // v2 adds the removed-face box list; a v1 file has none and reads its rejected list back as empty.
    private const val VERSION = 2
    private const val MIN_READABLE_VERSION = 1
    const val FILE_NAME = "pending_reattach.bin"

    fun file(dir: File): File = File(dir, FILE_NAME)

    fun write(file: File, data: FaceReattachData) {
        file.parentFile?.mkdirs()
        DataOutputStream(file.outputStream().buffered()).use { out ->
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
    }

    fun read(file: File): FaceReattachData? {
        if (!file.isFile) return null
        return runCatching {
            DataInputStream(file.inputStream().buffered()).use { inp ->
                if (inp.readUTF() != MAGIC) return null
                val version = inp.readInt()
                if (version < MIN_READABLE_VERSION || version > VERSION) return null
                val members = readLabels(inp)
                val manual = List(inp.readInt()) { ReattachManual(inp.readUTF(), inp.readUTF()) }
                val nots = readLabels(inp)
                val rejected = if (version >= 2) readBoxes(inp) else emptyList()
                FaceReattachData(members, manual, nots, rejected)
            }
        }.getOrNull()
    }

    fun clear(file: File) {
        runCatching { file.delete() }
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

    private fun readLabels(inp: DataInputStream): List<ReattachLabel> = List(inp.readInt()) {
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

    private fun readBoxes(inp: DataInputStream): List<ReattachBox> = List(inp.readInt()) {
        ReattachBox(
            photoKey = inp.readUTF(),
            left = inp.readFloat(), top = inp.readFloat(),
            right = inp.readFloat(), bottom = inp.readFloat(),
        )
    }
}
