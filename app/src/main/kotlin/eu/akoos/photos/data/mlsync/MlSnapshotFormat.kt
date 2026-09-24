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

import java.io.IOException

/**
 * The plaintext ML snapshot: one device's face index (kind FACES) or semantic search index (kind SEARCH)
 * as a single forward-only byte stream, so a library of any size is written and read one record at a
 * time. It is the payload the snapshot envelope encrypts, and this comment is its reference for any other
 * reader. [MlSnapshotWriter] writes it and [MlSnapshotReader] reads it.
 *
 * Encoding
 *
 * - u8, u16 and u32 are unsigned, i64 is two's complement, all little-endian. A count, index, version or
 *   width never exceeds 2^31 - 1. The one exception is a FACE's person and confirmedPerson, where
 *   0xFFFFFFFF means "none"; every other reference must name a listed entry.
 * - f32 is an IEEE 754 binary32 and f16 an IEEE 754 binary16, both little-endian.
 * - str is a u16 byte length followed by that many bytes of standard UTF-8, with no terminator and no BOM.
 *
 * Header
 *
 *     magic                 8 bytes  ASCII "PPMLSNAP"
 *     version               u32      4
 *     kind                  u8       1 = FACES, 2 = SEARCH
 *     deviceId              str      the writing device's id
 *     deviceLabel           str      the writing device's human-readable name
 *     createdAt             i64      the writer's clock in epoch milliseconds, informational only
 *   FACES continues with
 *     faceModelVersion      u32      face pipeline generation (detector, recognition model, quality gate)
 *     faceModelSha256       str      hex SHA-256 of the recognition model file
 *     clusterParamsVersion  u32      clustering parameters generation
 *     embeddingDim          u32      components per face embedding
 *     7 x u32 counts: keys, people, faces, manualPhotos, notPersons, covers, scanMarkers
 *   SEARCH continues with
 *     searchModelId         str      the embedding space; vectors compare only under an equal id
 *     embeddingDim          u32      components per search embedding
 *     2 x u32 counts: keys, embeddings
 *
 * Records
 *
 * Every record is a u8 tag and a body. Records come in sections, one per count above and in the same
 * order (FACES: KEY, PERSON, FACE, MANUAL_PHOTO, NOT_PERSON, COVER, SCAN_MARKER; SEARCH: KEY,
 * SEARCH_EMBEDDING), each holding exactly its count of records. The END tag follows the last section and
 * is the final byte of the stream.
 *
 *     0x01 KEY               str photoKey
 *     0x02 PERSON            str name
 *     0x03 FACE              u32 key, u32 faceIndex, f32 left, f32 top, f32 right, f32 bottom,
 *                            f32 score, f32 blur, u8 flags, u32 person, u32 confirmedPerson,
 *                            u8 points, points x (f32 x, f32 y), embeddingDim x f32
 *     0x04 MANUAL_PHOTO      u32 person, u32 key
 *     0x05 NOT_PERSON        u32 person, u32 key, u32 faceIndex
 *     0x06 COVER             u32 person, u32 key
 *     0x07 SCAN_MARKER       u32 key, u8 flags
 *     0x08 SEARCH_EMBEDDING  u32 key, u8 flags, then embeddingDim x f16 unless the tombstone flag is set
 *     0xFF END
 *
 * Meaning
 *
 * - KEY: a photo's Proton Drive link id, the identity every device of the account shares. Keys are
 *   numbered from 0 in stream order, and every later record names a photo by that number.
 * - PERSON: a person name, numbered from 0 the same way. Every name a later record uses is listed once.
 * - FACE: one detected face. faceIndex is its position among its photo's detections, so its id on the
 *   writing device is photoKey + "#" + faceIndex. The box is normalised to the photo (0..1), score is
 *   the detection score and blur the Laplacian variance of the aligned crop. Flags: bit 0 set when the
 *   face was removed from People, bit 1 set when blur is known (the field holds 0 otherwise). person is
 *   the named person the face is grouped under and confirmedPerson the name confirmed for it by hand.
 *   The landmark points (five from the current detector: left eye, right eye, nose, left and right mouth
 *   corner) are in the detector's source pixel space. The embedding is the recognition vector exactly as
 *   the face index stores it.
 * - MANUAL_PHOTO: a photo attached to the person by hand.
 * - NOT_PERSON: the face photoKey + "#" + faceIndex is marked as not being the person.
 * - COVER: the photo picked as the person's cover.
 * - SCAN_MARKER: a photo the face indexer finished, whether or not it held a face, so an importing device
 *   skips it. Flags: bit 0 set once the high-resolution "find more" sweep has checked it too.
 * - SEARCH_EMBEDDING: a photo's image embedding at half precision. Flags: bit 0 marks a tombstone, a
 *   photo the indexer could not decode and keeps as the zero vector; a tombstone carries no components.
 *
 * Flag bits not listed are zero. A reader refuses a wrong magic, any version but [VERSION] (so an older
 * build turns a newer file away before reading on), an unknown kind, a record out of order or past its
 * section's count, a reference to a key or person that is not listed, a set unlisted flag bit, malformed
 * UTF-8, a stream that ends before the END tag, and any byte after it.
 */
object MlSnapshotFormat {

    /** The format version this build writes, and the only one it reads. */
    const val VERSION = 4

    /** The widest embedding a header may declare. Far above any model in use, it keeps a damaged header
     *  from asking for a huge allocation per record. */
    const val MAX_EMBEDDING_DIM = 4096

    /** The longest string field in UTF-8 bytes, the reach of its u16 length. */
    const val MAX_STRING_BYTES = 0xFFFF

    /** The most landmark points one face carries, the reach of its u8 count. */
    const val MAX_LANDMARK_POINTS = 0xFF

    internal val MAGIC: ByteArray = "PPMLSNAP".encodeToByteArray()

    internal const val TAG_KEY = 0x01
    internal const val TAG_PERSON = 0x02
    internal const val TAG_FACE = 0x03
    internal const val TAG_MANUAL_PHOTO = 0x04
    internal const val TAG_NOT_PERSON = 0x05
    internal const val TAG_COVER = 0x06
    internal const val TAG_SCAN_MARKER = 0x07
    internal const val TAG_SEARCH_EMBEDDING = 0x08
    internal const val TAG_END = 0xFF

    internal const val FACE_REJECTED = 0x01
    internal const val FACE_BLUR_KNOWN = 0x02
    internal const val SCAN_HI_RES = 0x01
    internal const val SEARCH_TOMBSTONE = 0x01

    /** A person reference naming nobody, 0xFFFFFFFF on the wire. */
    internal const val NO_REFERENCE = -1

    /** [value] rounded to the nearest IEEE 754 half, ties to even, as the half's bit pattern. A magnitude
     *  past the half range becomes infinity and a NaN stays a NaN. */
    fun floatToHalf(value: Float): Short = halfBitsOf(value.toRawBits()).toShort()

    /** The half whose bit pattern is [half], widened to a float, which is always exact. */
    fun halfToFloat(half: Short): Float = Float.fromBits(floatBitsOf(half.toInt() and 0xFFFF))

    internal fun halfBitsOf(floatBits: Int): Int {
        val sign = (floatBits ushr 16) and 0x8000
        val exponent = (floatBits ushr 23) and 0xFF
        val mantissa = floatBits and 0x7FFFFF
        if (exponent == 0xFF) {
            return if (mantissa == 0) sign or 0x7C00 else sign or 0x7E00 or (mantissa ushr 13)
        }
        val halfExponent = exponent - 112
        return when {
            halfExponent >= 0x1F -> sign or 0x7C00
            // A carry out of the rounded mantissa steps into the next exponent, up to infinity.
            halfExponent > 0 -> sign or ((halfExponent shl 10) + roundShift(mantissa, 13))
            // Subnormal half: the implicit leading bit becomes explicit and shifts down with the rest.
            halfExponent >= -10 -> sign or roundShift(mantissa or 0x800000, 14 - halfExponent)
            else -> sign
        }
    }

    internal fun floatBitsOf(halfBits: Int): Int {
        val sign = (halfBits and 0x8000) shl 16
        val exponent = (halfBits ushr 10) and 0x1F
        val mantissa = halfBits and 0x3FF
        return when {
            exponent == 0x1F -> sign or 0x7F800000 or (mantissa shl 13)
            exponent != 0 -> sign or ((exponent + 112) shl 23) or (mantissa shl 13)
            mantissa == 0 -> sign
            else -> {
                val shift = mantissa.countLeadingZeroBits() - 21
                sign or ((113 - shift) shl 23) or (((mantissa shl shift) and 0x3FF) shl 13)
            }
        }
    }

    private fun roundShift(value: Int, shift: Int): Int {
        val kept = value ushr shift
        val dropped = value and ((1 shl shift) - 1)
        val halfway = 1 shl (shift - 1)
        return if (dropped > halfway || (dropped == halfway && (kept and 1) == 1)) kept + 1 else kept
    }

    internal class Section(val tag: Int, val count: Int)

    /** The sections [header] declares, in stream order. */
    internal fun sections(header: MlSnapshotHeader): List<Section> = when (header) {
        is MlSnapshotHeader.Faces -> listOf(
            Section(TAG_KEY, header.keyCount),
            Section(TAG_PERSON, header.personCount),
            Section(TAG_FACE, header.faceCount),
            Section(TAG_MANUAL_PHOTO, header.manualPhotoCount),
            Section(TAG_NOT_PERSON, header.notPersonCount),
            Section(TAG_COVER, header.coverCount),
            Section(TAG_SCAN_MARKER, header.scanMarkerCount),
        )
        is MlSnapshotHeader.Search -> listOf(
            Section(TAG_KEY, header.keyCount),
            Section(TAG_SEARCH_EMBEDDING, header.embeddingCount),
        )
    }

    internal fun tagName(tag: Int): String = when (tag) {
        TAG_KEY -> "KEY"
        TAG_PERSON -> "PERSON"
        TAG_FACE -> "FACE"
        TAG_MANUAL_PHOTO -> "MANUAL_PHOTO"
        TAG_NOT_PERSON -> "NOT_PERSON"
        TAG_COVER -> "COVER"
        TAG_SCAN_MARKER -> "SCAN_MARKER"
        TAG_SEARCH_EMBEDDING -> "SEARCH_EMBEDDING"
        TAG_END -> "END"
        else -> "tag $tag"
    }
}

/** Which index a snapshot carries. [code] is its byte in the header. */
enum class MlSnapshotKind(internal val code: Int) {
    FACES(1),
    SEARCH(2),
}

/**
 * A snapshot's header: who wrote it, which models produced it, and how many records each section holds.
 * The counts are exact, so a writer must know them before the first record and a reader can size its
 * work (and show progress) up front.
 */
sealed interface MlSnapshotHeader {
    val kind: MlSnapshotKind
    val deviceId: String
    val deviceLabel: String
    val createdAtMillis: Long
    val embeddingDim: Int
    val keyCount: Int

    data class Faces(
        override val deviceId: String,
        override val deviceLabel: String,
        override val createdAtMillis: Long,
        val faceModelVersion: Int,
        val faceModelSha256: String,
        val clusterParamsVersion: Int,
        override val embeddingDim: Int,
        override val keyCount: Int,
        val personCount: Int,
        val faceCount: Int,
        val manualPhotoCount: Int,
        val notPersonCount: Int,
        val coverCount: Int,
        val scanMarkerCount: Int,
    ) : MlSnapshotHeader {
        override val kind: MlSnapshotKind get() = MlSnapshotKind.FACES
    }

    data class Search(
        override val deviceId: String,
        override val deviceLabel: String,
        override val createdAtMillis: Long,
        val searchModelId: String,
        override val embeddingDim: Int,
        override val keyCount: Int,
        val embeddingCount: Int,
    ) : MlSnapshotHeader {
        override val kind: MlSnapshotKind get() = MlSnapshotKind.SEARCH
    }
}

/**
 * One record of a snapshot, as [MlSnapshotReader] yields it and [MlSnapshotWriter] takes it. Photo keys
 * and person names are spelled out; numbering them against the key and name tables is the format's
 * business. Array fields are held as given, not copied.
 */
sealed interface MlSnapshotRecord {

    /** One entry of the key table. */
    data class PhotoKey(val photoKey: String) : MlSnapshotRecord

    /** One entry of the name table. */
    data class Person(val name: String) : MlSnapshotRecord

    /**
     * One detected face. [landmarks] interleaves the points' x and y. [embedding] is the face index's own
     * blob, embeddingDim little-endian f32 values, carried byte for byte. [personName] is the named person
     * the face is grouped under and [confirmedName] the name confirmed for it by hand, each null when
     * absent. Not a data class, so the arrays compare by content and the floats bit for bit.
     */
    class Face(
        val photoKey: String,
        val faceIndex: Int,
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float,
        val landmarks: FloatArray,
        val score: Float,
        val blur: Float?,
        val rejected: Boolean,
        val personName: String?,
        val confirmedName: String?,
        val embedding: ByteArray,
    ) : MlSnapshotRecord {
        /** The face's id in the face index, derived from its photo and its position within it. */
        val faceId: String get() = "$photoKey#$faceIndex"

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Face) return false
            return photoKey == other.photoKey &&
                faceIndex == other.faceIndex &&
                left.toBits() == other.left.toBits() &&
                top.toBits() == other.top.toBits() &&
                right.toBits() == other.right.toBits() &&
                bottom.toBits() == other.bottom.toBits() &&
                landmarks.contentEquals(other.landmarks) &&
                score.toBits() == other.score.toBits() &&
                blur?.toBits() == other.blur?.toBits() &&
                rejected == other.rejected &&
                personName == other.personName &&
                confirmedName == other.confirmedName &&
                embedding.contentEquals(other.embedding)
        }

        override fun hashCode(): Int {
            var result = photoKey.hashCode()
            result = 31 * result + faceIndex
            result = 31 * result + left.hashCode()
            result = 31 * result + top.hashCode()
            result = 31 * result + right.hashCode()
            result = 31 * result + bottom.hashCode()
            result = 31 * result + landmarks.contentHashCode()
            result = 31 * result + score.hashCode()
            result = 31 * result + (blur?.hashCode() ?: 0)
            result = 31 * result + rejected.hashCode()
            result = 31 * result + (personName?.hashCode() ?: 0)
            result = 31 * result + (confirmedName?.hashCode() ?: 0)
            result = 31 * result + embedding.contentHashCode()
            return result
        }

        override fun toString(): String =
            "Face($faceId, box=[$left, $top, $right, $bottom], score=$score, blur=$blur, rejected=$rejected, " +
                "person=$personName, confirmed=$confirmedName, points=${landmarks.size / 2}, " +
                "embedding=${embedding.size} bytes)"
    }

    /** A photo attached to [personName] by hand. */
    data class ManualPhoto(val personName: String, val photoKey: String) : MlSnapshotRecord

    /** The face [faceIndex] of [photoKey] is marked as not being [personName]. */
    data class NotPerson(val personName: String, val photoKey: String, val faceIndex: Int) : MlSnapshotRecord {
        val faceId: String get() = "$photoKey#$faceIndex"
    }

    /** The photo picked as [personName]'s cover. */
    data class Cover(val personName: String, val photoKey: String) : MlSnapshotRecord

    /** A photo the face indexer finished, with or without faces. */
    data class ScanMarker(val photoKey: String, val hiResScanned: Boolean) : MlSnapshotRecord

    /**
     * One photo's search embedding as the search index's own blob, embeddingDim little-endian f32 values.
     * All zero bytes is the tombstone and travels as a flag alone; any other vector travels at half
     * precision, so it reads back rounded to the nearest f16.
     */
    class SearchEmbedding(val photoKey: String, val embedding: ByteArray) : MlSnapshotRecord {
        val isTombstone: Boolean get() = embedding.all { it == 0.toByte() }

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is SearchEmbedding) return false
            return photoKey == other.photoKey && embedding.contentEquals(other.embedding)
        }

        override fun hashCode(): Int = 31 * photoKey.hashCode() + embedding.contentHashCode()

        override fun toString(): String =
            "SearchEmbedding($photoKey, ${if (isTombstone) "tombstone" else "${embedding.size} bytes"})"
    }
}

/** The stream is not a well-formed snapshot. An [IOException], so a caller reading from storage handles
 *  it along with every other read failure. */
open class MlSnapshotFormatException(message: String, cause: Throwable? = null) : IOException(message, cause)

/** The stream ended before its END tag: an interrupted write or a partial transfer. */
class MlSnapshotTruncatedException : MlSnapshotFormatException("the snapshot ends before its end marker")

/** The stream is a snapshot of a [version] this build does not read, typically one a newer build wrote. */
class UnsupportedMlSnapshotVersionException(val version: Long) :
    MlSnapshotFormatException("unsupported snapshot version $version")
