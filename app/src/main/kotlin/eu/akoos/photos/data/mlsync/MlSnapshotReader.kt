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
import java.io.BufferedInputStream
import java.io.InputStream
import java.nio.charset.CharacterCodingException

private const val READ_BUFFER_BYTES = 64 * 1024

/** The most key-table slots reserved up front, so a damaged count cannot force a huge allocation. */
private const val PRESIZE_LIMIT = 1 shl 16

/**
 * Reads a snapshot from [input] one record at a time, in the layout [MlSnapshotFormat] describes. The
 * header is read and checked as the reader is built, so a wrong magic or an unsupported version fails
 * right there, before any record, and a caller can judge [header] (its kind, its models) before reading
 * on. Iterating then yields the records in stream order with every key and name reference resolved.
 * Only the key and name tables are kept, never the records, so a library of any size streams through.
 *
 * Malformed input throws [MlSnapshotFormatException]: [MlSnapshotTruncatedException] when the stream
 * ends early, [UnsupportedMlSnapshotVersionException] for another version. The END tag, and the end of
 * [input] right behind it, are checked by the [hasNext] call that returns false, so a caller treats the
 * snapshot as complete only once iteration has run out normally. [input] is never closed here.
 */
class MlSnapshotReader(input: InputStream) : Iterator<MlSnapshotRecord> {

    private val source = SnapshotInput(
        if (input is BufferedInputStream) input else BufferedInputStream(input, READ_BUFFER_BYTES),
    )

    /** The snapshot's header, read and checked when the reader was built. */
    val header: MlSnapshotHeader = readHeader()

    private val sections = MlSnapshotFormat.sections(header)
    private val embeddingBytes = header.embeddingDim * Float.SIZE_BYTES
    private val halfScratch = ByteArray(header.embeddingDim * 2)
    private val keys = ArrayList<String>(minOf(header.keyCount, PRESIZE_LIMIT))
    private val people = ArrayList<String>()
    private var sectionIndex = 0
    private var readInSection = 0
    private var ended = false

    override fun hasNext(): Boolean {
        if (ended) return false
        while (sectionIndex < sections.size && readInSection == sections[sectionIndex].count) {
            sectionIndex++
            readInSection = 0
        }
        if (sectionIndex < sections.size) return true
        val tag = source.u8()
        if (tag != MlSnapshotFormat.TAG_END) {
            throw MlSnapshotFormatException("expected the end marker, found ${tagName(tag)}")
        }
        if (!source.atEnd()) throw MlSnapshotFormatException("data follows the end marker")
        ended = true
        return false
    }

    override fun next(): MlSnapshotRecord {
        if (!hasNext()) throw NoSuchElementException()
        val expected = sections[sectionIndex].tag
        val tag = source.u8()
        if (tag != expected) {
            throw MlSnapshotFormatException("expected a ${tagName(expected)} record, found ${tagName(tag)}")
        }
        val record = when (tag) {
            TAG_KEY -> MlSnapshotRecord.PhotoKey(source.str()).also { keys.add(it.photoKey) }
            TAG_PERSON -> MlSnapshotRecord.Person(source.str()).also { people.add(it.name) }
            TAG_FACE -> readFace()
            TAG_MANUAL_PHOTO -> MlSnapshotRecord.ManualPhoto(personAt(source.i32()), keyAt(source.i32()))
            TAG_NOT_PERSON -> MlSnapshotRecord.NotPerson(
                personName = personAt(source.i32()),
                photoKey = keyAt(source.i32()),
                faceIndex = nonNegative(source.i32(), "face index"),
            )
            TAG_COVER -> MlSnapshotRecord.Cover(personAt(source.i32()), keyAt(source.i32()))
            TAG_SCAN_MARKER -> {
                val photoKey = keyAt(source.i32())
                val flags = checkedFlags(source.u8(), SCAN_HI_RES)
                MlSnapshotRecord.ScanMarker(photoKey, (flags and SCAN_HI_RES) != 0)
            }
            TAG_SEARCH_EMBEDDING -> readSearchEmbedding()
            else -> throw MlSnapshotFormatException("unexpected ${tagName(tag)}")
        }
        readInSection++
        return record
    }

    private fun readHeader(): MlSnapshotHeader {
        val magic = ByteArray(MlSnapshotFormat.MAGIC.size)
        source.fully(magic)
        if (!magic.contentEquals(MlSnapshotFormat.MAGIC)) throw MlSnapshotFormatException("not an ML snapshot")
        val version = source.i32()
        if (version != MlSnapshotFormat.VERSION) {
            throw UnsupportedMlSnapshotVersionException(version.toLong() and 0xFFFFFFFFL)
        }
        val kindCode = source.u8()
        val kind = MlSnapshotKind.entries.firstOrNull { it.code == kindCode }
            ?: throw MlSnapshotFormatException("unknown snapshot kind $kindCode")
        val deviceId = source.str()
        val deviceLabel = source.str()
        val createdAtMillis = source.i64()
        return when (kind) {
            MlSnapshotKind.FACES -> MlSnapshotHeader.Faces(
                deviceId = deviceId,
                deviceLabel = deviceLabel,
                createdAtMillis = createdAtMillis,
                faceModelVersion = nonNegative(source.i32(), "face model version"),
                faceModelSha256 = source.str(),
                clusterParamsVersion = nonNegative(source.i32(), "clustering parameters version"),
                embeddingDim = embeddingDim(source.i32()),
                keyCount = nonNegative(source.i32(), "key count"),
                personCount = nonNegative(source.i32(), "person count"),
                faceCount = nonNegative(source.i32(), "face count"),
                manualPhotoCount = nonNegative(source.i32(), "manual photo count"),
                notPersonCount = nonNegative(source.i32(), "not-person count"),
                coverCount = nonNegative(source.i32(), "cover count"),
                scanMarkerCount = nonNegative(source.i32(), "scan marker count"),
            )
            MlSnapshotKind.SEARCH -> MlSnapshotHeader.Search(
                deviceId = deviceId,
                deviceLabel = deviceLabel,
                createdAtMillis = createdAtMillis,
                searchModelId = source.str(),
                embeddingDim = embeddingDim(source.i32()),
                keyCount = nonNegative(source.i32(), "key count"),
                embeddingCount = nonNegative(source.i32(), "embedding count"),
            )
        }
    }

    private fun readFace(): MlSnapshotRecord.Face {
        val photoKey = keyAt(source.i32())
        val faceIndex = nonNegative(source.i32(), "face index")
        val left = source.f32()
        val top = source.f32()
        val right = source.f32()
        val bottom = source.f32()
        val score = source.f32()
        val blur = source.f32()
        val flags = checkedFlags(source.u8(), FACE_REJECTED or FACE_BLUR_KNOWN)
        val personName = optionalPersonAt(source.i32())
        val confirmedName = optionalPersonAt(source.i32())
        val landmarks = FloatArray(source.u8() * 2) { source.f32() }
        val embedding = ByteArray(embeddingBytes).also { source.fully(it) }
        return MlSnapshotRecord.Face(
            photoKey = photoKey,
            faceIndex = faceIndex,
            left = left,
            top = top,
            right = right,
            bottom = bottom,
            landmarks = landmarks,
            score = score,
            blur = if ((flags and FACE_BLUR_KNOWN) != 0) blur else null,
            rejected = (flags and FACE_REJECTED) != 0,
            personName = personName,
            confirmedName = confirmedName,
            embedding = embedding,
        )
    }

    private fun readSearchEmbedding(): MlSnapshotRecord.SearchEmbedding {
        val photoKey = keyAt(source.i32())
        val flags = checkedFlags(source.u8(), SEARCH_TOMBSTONE)
        val embedding = ByteArray(embeddingBytes)
        if ((flags and SEARCH_TOMBSTONE) == 0) {
            source.fully(halfScratch)
            for (i in 0 until header.embeddingDim) {
                val half = (halfScratch[i * 2].toInt() and 0xFF) or ((halfScratch[i * 2 + 1].toInt() and 0xFF) shl 8)
                val bits = MlSnapshotFormat.floatBitsOf(half)
                val at = i * 4
                embedding[at] = bits.toByte()
                embedding[at + 1] = (bits ushr 8).toByte()
                embedding[at + 2] = (bits ushr 16).toByte()
                embedding[at + 3] = (bits ushr 24).toByte()
            }
        }
        return MlSnapshotRecord.SearchEmbedding(photoKey, embedding)
    }

    private fun keyAt(index: Int): String =
        keys.getOrNull(index) ?: throw MlSnapshotFormatException("a photo key reference is out of range")

    private fun personAt(index: Int): String =
        people.getOrNull(index) ?: throw MlSnapshotFormatException("a person reference is out of range")

    private fun optionalPersonAt(index: Int): String? = if (index == NO_REFERENCE) null else personAt(index)

    private fun checkedFlags(flags: Int, known: Int): Int {
        if ((flags and known.inv()) != 0) throw MlSnapshotFormatException("a record sets an unknown flag bit")
        return flags
    }

    private fun nonNegative(value: Int, what: String): Int {
        if (value < 0) throw MlSnapshotFormatException("the $what is out of range")
        return value
    }

    private fun embeddingDim(value: Int): Int {
        if (value !in 1..MlSnapshotFormat.MAX_EMBEDDING_DIM) {
            throw MlSnapshotFormatException("embedding width $value is out of range")
        }
        return value
    }
}

private class SnapshotInput(private val input: InputStream) {
    private val scratch = ByteArray(8)

    fun fully(target: ByteArray) {
        fully(target, target.size)
    }

    fun u8(): Int {
        val value = input.read()
        if (value < 0) throw MlSnapshotTruncatedException()
        return value
    }

    fun i32(): Int = littleEndian(4).toInt()

    fun i64(): Long = littleEndian(8)

    fun f32(): Float = Float.fromBits(i32())

    fun str(): String {
        val bytes = ByteArray(littleEndian(2).toInt())
        fully(bytes)
        return try {
            bytes.decodeToString(throwOnInvalidSequence = true)
        } catch (e: CharacterCodingException) {
            throw MlSnapshotFormatException("a string is not valid UTF-8", e)
        }
    }

    fun atEnd(): Boolean = input.read() < 0

    private fun fully(target: ByteArray, length: Int) {
        var done = 0
        while (done < length) {
            val read = input.read(target, done, length - done)
            if (read < 0) throw MlSnapshotTruncatedException()
            done += read
        }
    }

    private fun littleEndian(size: Int): Long {
        fully(scratch, size)
        var value = 0L
        for (i in size - 1 downTo 0) value = (value shl 8) or (scratch[i].toLong() and 0xFF)
        return value
    }
}
