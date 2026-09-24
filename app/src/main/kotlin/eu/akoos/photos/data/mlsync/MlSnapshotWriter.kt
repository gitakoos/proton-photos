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

package eu.akoos.photos.data.mlsync

import eu.akoos.photos.data.mlsync.MlSnapshotFormat.FACE_BLUR_KNOWN
import eu.akoos.photos.data.mlsync.MlSnapshotFormat.FACE_REJECTED
import eu.akoos.photos.data.mlsync.MlSnapshotFormat.NO_REFERENCE
import eu.akoos.photos.data.mlsync.MlSnapshotFormat.SCAN_HI_RES
import eu.akoos.photos.data.mlsync.MlSnapshotFormat.SEARCH_TOMBSTONE
import eu.akoos.photos.data.mlsync.MlSnapshotFormat.TAG_COVER
import eu.akoos.photos.data.mlsync.MlSnapshotFormat.TAG_FACE
import eu.akoos.photos.data.mlsync.MlSnapshotFormat.TAG_KEY
import eu.akoos.photos.data.mlsync.MlSnapshotFormat.TAG_MANUAL_PHOTO
import eu.akoos.photos.data.mlsync.MlSnapshotFormat.TAG_NOT_PERSON
import eu.akoos.photos.data.mlsync.MlSnapshotFormat.TAG_PERSON
import eu.akoos.photos.data.mlsync.MlSnapshotFormat.TAG_SCAN_MARKER
import eu.akoos.photos.data.mlsync.MlSnapshotFormat.TAG_SEARCH_EMBEDDING
import eu.akoos.photos.data.mlsync.MlSnapshotFormat.tagName
import java.io.BufferedOutputStream
import java.io.OutputStream

private const val WRITE_BUFFER_BYTES = 64 * 1024

/**
 * Streams one snapshot into [output] in the layout [MlSnapshotFormat] describes: the header goes out as
 * the writer is built, each [write] appends one record, and [finish] ends the stream with the END tag.
 * Only the key and name tables are kept (to number later references), never the records, so a library of
 * any size passes through.
 *
 * [header] fixes every section's count up front and the writer holds the caller to it: records come
 * section by section in the format's order, no section overflows, and [finish] refuses while one is
 * short ([IllegalStateException]). A record naming a key or person the tables do not list, or carrying a
 * wrong-size array, is refused ([IllegalArgumentException]) before any of its bytes are written, so the
 * stream stays intact. [finish] flushes [output] but never closes it; a stream abandoned before [finish]
 * has no END tag and reads back as truncated, never as a smaller snapshot.
 */
class MlSnapshotWriter(output: OutputStream, val header: MlSnapshotHeader) {

    private val dim: Int = header.embeddingDim.also {
        require(it in 1..MlSnapshotFormat.MAX_EMBEDDING_DIM) {
            "embeddingDim $it is outside 1..${MlSnapshotFormat.MAX_EMBEDDING_DIM}"
        }
    }
    private val embeddingBytes = dim * Float.SIZE_BYTES
    private val halfScratch = ByteArray(dim * 2)
    private val sections = MlSnapshotFormat.sections(header)
    private val keyIndex = HashMap<String, Int>()
    private val personIndex = HashMap<String, Int>()
    private val out = SnapshotOutput(
        if (output is BufferedOutputStream) output else BufferedOutputStream(output, WRITE_BUFFER_BYTES),
    )
    private var sectionIndex = 0
    private var writtenInSection = 0
    private var finished = false

    init {
        for (section in sections) {
            require(section.count >= 0) { "the ${tagName(section.tag)} count is negative" }
        }
        val deviceId = utf8(header.deviceId)
        val deviceLabel = utf8(header.deviceLabel)
        val modelText = utf8(
            when (header) {
                is MlSnapshotHeader.Faces -> header.faceModelSha256
                is MlSnapshotHeader.Search -> header.searchModelId
            },
        )
        if (header is MlSnapshotHeader.Faces) {
            require(header.faceModelVersion >= 0) { "faceModelVersion is negative" }
            require(header.clusterParamsVersion >= 0) { "clusterParamsVersion is negative" }
        }
        out.bytes(MlSnapshotFormat.MAGIC)
        out.i32(MlSnapshotFormat.VERSION)
        out.u8(header.kind.code)
        out.str(deviceId)
        out.str(deviceLabel)
        out.i64(header.createdAtMillis)
        when (header) {
            is MlSnapshotHeader.Faces -> {
                out.i32(header.faceModelVersion)
                out.str(modelText)
                out.i32(header.clusterParamsVersion)
            }
            is MlSnapshotHeader.Search -> out.str(modelText)
        }
        out.i32(dim)
        for (section in sections) out.i32(section.count)
    }

    /** Appends [record], which must belong to the section currently being filled. */
    fun write(record: MlSnapshotRecord) {
        check(!finished) { "the snapshot is already finished" }
        enterSection(tagOf(record))
        when (record) {
            is MlSnapshotRecord.PhotoKey -> writeKey(record)
            is MlSnapshotRecord.Person -> writePerson(record)
            is MlSnapshotRecord.Face -> writeFace(record)
            is MlSnapshotRecord.ManualPhoto -> {
                val person = personOf(record.personName)
                val key = keyOf(record.photoKey)
                out.u8(TAG_MANUAL_PHOTO)
                out.i32(person)
                out.i32(key)
            }
            is MlSnapshotRecord.NotPerson -> {
                val person = personOf(record.personName)
                val key = keyOf(record.photoKey)
                require(record.faceIndex >= 0) { "faceIndex is negative" }
                out.u8(TAG_NOT_PERSON)
                out.i32(person)
                out.i32(key)
                out.i32(record.faceIndex)
            }
            is MlSnapshotRecord.Cover -> {
                val person = personOf(record.personName)
                val key = keyOf(record.photoKey)
                out.u8(TAG_COVER)
                out.i32(person)
                out.i32(key)
            }
            is MlSnapshotRecord.ScanMarker -> {
                val key = keyOf(record.photoKey)
                out.u8(TAG_SCAN_MARKER)
                out.i32(key)
                out.u8(if (record.hiResScanned) SCAN_HI_RES else 0)
            }
            is MlSnapshotRecord.SearchEmbedding -> writeSearchEmbedding(record)
        }
        writtenInSection++
    }

    /** Ends the stream with the END tag and flushes [output]. Refuses while a section is short. */
    fun finish() {
        check(!finished) { "the snapshot is already finished" }
        skipFullSections()
        check(sectionIndex == sections.size) {
            val section = sections[sectionIndex]
            "the ${tagName(section.tag)} section holds $writtenInSection of ${section.count} records"
        }
        out.u8(MlSnapshotFormat.TAG_END)
        out.flush()
        finished = true
    }

    private fun enterSection(tag: Int) {
        skipFullSections()
        check(sectionIndex < sections.size) { "every section already holds its declared count" }
        val section = sections[sectionIndex]
        check(section.tag == tag) {
            "a ${tagName(tag)} record cannot come next: the ${tagName(section.tag)} section holds " +
                "$writtenInSection of ${section.count} records"
        }
    }

    private fun skipFullSections() {
        while (sectionIndex < sections.size && writtenInSection == sections[sectionIndex].count) {
            sectionIndex++
            writtenInSection = 0
        }
    }

    private fun writeKey(record: MlSnapshotRecord.PhotoKey) {
        val bytes = utf8(record.photoKey)
        require(record.photoKey !in keyIndex) { "a photo key is listed twice" }
        keyIndex[record.photoKey] = keyIndex.size
        out.u8(TAG_KEY)
        out.str(bytes)
    }

    private fun writePerson(record: MlSnapshotRecord.Person) {
        val bytes = utf8(record.name)
        require(record.name !in personIndex) { "a person name is listed twice" }
        personIndex[record.name] = personIndex.size
        out.u8(TAG_PERSON)
        out.str(bytes)
    }

    private fun writeFace(face: MlSnapshotRecord.Face) {
        val key = keyOf(face.photoKey)
        val person = face.personName?.let { personOf(it) } ?: NO_REFERENCE
        val confirmed = face.confirmedName?.let { personOf(it) } ?: NO_REFERENCE
        require(face.faceIndex >= 0) { "faceIndex is negative" }
        require(face.landmarks.size % 2 == 0) { "landmarks hold an odd number of coordinates" }
        val points = face.landmarks.size / 2
        require(points <= MlSnapshotFormat.MAX_LANDMARK_POINTS) {
            "a face carries more than ${MlSnapshotFormat.MAX_LANDMARK_POINTS} landmark points"
        }
        require(face.embedding.size == embeddingBytes) {
            "a face embedding is ${face.embedding.size} bytes, not $embeddingBytes"
        }
        var flags = 0
        if (face.rejected) flags = flags or FACE_REJECTED
        if (face.blur != null) flags = flags or FACE_BLUR_KNOWN
        out.u8(TAG_FACE)
        out.i32(key)
        out.i32(face.faceIndex)
        out.f32(face.left)
        out.f32(face.top)
        out.f32(face.right)
        out.f32(face.bottom)
        out.f32(face.score)
        out.f32(face.blur ?: 0f)
        out.u8(flags)
        out.i32(person)
        out.i32(confirmed)
        out.u8(points)
        for (value in face.landmarks) out.f32(value)
        out.bytes(face.embedding)
    }

    private fun writeSearchEmbedding(record: MlSnapshotRecord.SearchEmbedding) {
        val key = keyOf(record.photoKey)
        val embedding = record.embedding
        require(embedding.size == embeddingBytes) {
            "a search embedding is ${embedding.size} bytes, not $embeddingBytes"
        }
        out.u8(TAG_SEARCH_EMBEDDING)
        out.i32(key)
        if (record.isTombstone) {
            out.u8(SEARCH_TOMBSTONE)
            return
        }
        for (i in 0 until dim) {
            val at = i * 4
            val bits = (embedding[at].toInt() and 0xFF) or
                ((embedding[at + 1].toInt() and 0xFF) shl 8) or
                ((embedding[at + 2].toInt() and 0xFF) shl 16) or
                (embedding[at + 3].toInt() shl 24)
            val half = MlSnapshotFormat.halfBitsOf(bits)
            halfScratch[i * 2] = half.toByte()
            halfScratch[i * 2 + 1] = (half ushr 8).toByte()
        }
        out.u8(0)
        out.bytes(halfScratch)
    }

    private fun keyOf(photoKey: String): Int =
        requireNotNull(keyIndex[photoKey]) { "a record names a photo key the key table does not list" }

    private fun personOf(name: String): Int =
        requireNotNull(personIndex[name]) { "a record names a person the name table does not list" }

    private fun tagOf(record: MlSnapshotRecord): Int = when (record) {
        is MlSnapshotRecord.PhotoKey -> TAG_KEY
        is MlSnapshotRecord.Person -> TAG_PERSON
        is MlSnapshotRecord.Face -> TAG_FACE
        is MlSnapshotRecord.ManualPhoto -> TAG_MANUAL_PHOTO
        is MlSnapshotRecord.NotPerson -> TAG_NOT_PERSON
        is MlSnapshotRecord.Cover -> TAG_COVER
        is MlSnapshotRecord.ScanMarker -> TAG_SCAN_MARKER
        is MlSnapshotRecord.SearchEmbedding -> TAG_SEARCH_EMBEDDING
    }

    private fun utf8(value: String): ByteArray {
        val bytes = value.encodeToByteArray()
        require(bytes.size <= MlSnapshotFormat.MAX_STRING_BYTES) {
            "a string field exceeds ${MlSnapshotFormat.MAX_STRING_BYTES} UTF-8 bytes"
        }
        return bytes
    }
}

private class SnapshotOutput(private val out: OutputStream) {
    private val scratch = ByteArray(8)

    fun u8(value: Int) = out.write(value)

    fun i32(value: Int) = littleEndian(value.toLong(), 4)

    fun i64(value: Long) = littleEndian(value, 8)

    fun f32(value: Float) = i32(value.toRawBits())

    fun bytes(value: ByteArray) = out.write(value)

    fun str(utf8: ByteArray) {
        littleEndian(utf8.size.toLong(), 2)
        out.write(utf8)
    }

    fun flush() = out.flush()

    private fun littleEndian(value: Long, size: Int) {
        for (i in 0 until size) scratch[i] = (value ushr (8 * i)).toByte()
        out.write(scratch, 0, size)
    }
}
