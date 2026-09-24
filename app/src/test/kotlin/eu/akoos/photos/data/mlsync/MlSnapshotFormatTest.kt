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

import eu.akoos.photos.data.mlsync.MlSnapshotRecord.Cover
import eu.akoos.photos.data.mlsync.MlSnapshotRecord.Face
import eu.akoos.photos.data.mlsync.MlSnapshotRecord.ManualPhoto
import eu.akoos.photos.data.mlsync.MlSnapshotRecord.NotPerson
import eu.akoos.photos.data.mlsync.MlSnapshotRecord.Person
import eu.akoos.photos.data.mlsync.MlSnapshotRecord.PhotoKey
import eu.akoos.photos.data.mlsync.MlSnapshotRecord.ScanMarker
import eu.akoos.photos.data.mlsync.MlSnapshotRecord.SearchEmbedding
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.FilterInputStream
import java.io.InputStream
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Coverage for the ML snapshot format: FACES and SEARCH round trips through the streaming writer and
 * reader, golden bytes that pin the layout, the half-precision conversion, the refusals (truncation, an
 * unknown version, dangling references, unknown flags) and the writer's section discipline. Plain JVM.
 */
class MlSnapshotFormatTest {

    private fun snapshotBytes(header: MlSnapshotHeader, records: List<MlSnapshotRecord>): ByteArray {
        val buffer = ByteArrayOutputStream()
        val writer = MlSnapshotWriter(buffer, header)
        records.forEach { writer.write(it) }
        writer.finish()
        return buffer.toByteArray()
    }

    private fun readAll(bytes: ByteArray): Pair<MlSnapshotHeader, List<MlSnapshotRecord>> {
        val reader = MlSnapshotReader(ByteArrayInputStream(bytes))
        val records = ArrayList<MlSnapshotRecord>()
        reader.forEach { records += it }
        return reader.header to records
    }

    private fun facesHeader(
        records: List<MlSnapshotRecord>,
        embeddingDim: Int,
        deviceId: String = "device-a",
        deviceLabel: String = "Test phone",
        createdAtMillis: Long = 1_758_600_000_000L,
        faceModelVersion: Int = 7,
        faceModelSha256: String = "ffd8203a0c9e93d90a4957e24d173a883a5a04d64d23c771742c66273518a0db",
    ) = MlSnapshotHeader.Faces(
        deviceId = deviceId,
        deviceLabel = deviceLabel,
        createdAtMillis = createdAtMillis,
        faceModelVersion = faceModelVersion,
        faceModelSha256 = faceModelSha256,
        clusterParamsVersion = 1,
        embeddingDim = embeddingDim,
        keyCount = records.count { it is PhotoKey },
        personCount = records.count { it is Person },
        faceCount = records.count { it is Face },
        manualPhotoCount = records.count { it is ManualPhoto },
        notPersonCount = records.count { it is NotPerson },
        coverCount = records.count { it is Cover },
        scanMarkerCount = records.count { it is ScanMarker },
    )

    private fun searchHeader(
        records: List<MlSnapshotRecord>,
        embeddingDim: Int,
        deviceId: String = "device-a",
        deviceLabel: String = "Test phone",
        createdAtMillis: Long = 1_758_600_000_000L,
        searchModelId: String = "clip-vit-b16:2",
    ) = MlSnapshotHeader.Search(
        deviceId = deviceId,
        deviceLabel = deviceLabel,
        createdAtMillis = createdAtMillis,
        searchModelId = searchModelId,
        embeddingDim = embeddingDim,
        keyCount = records.count { it is PhotoKey },
        embeddingCount = records.count { it is SearchEmbedding },
    )

    /** Little-endian f32 values, the blob form the face and search indexes store. */
    private fun floats(vararg values: Float): ByteArray {
        val out = ByteArray(values.size * 4)
        values.forEachIndexed { i, value ->
            val bits = value.toRawBits()
            for (b in 0 until 4) out[i * 4 + b] = (bits ushr (8 * b)).toByte()
        }
        return out
    }

    private fun unpack(blob: ByteArray): FloatArray = FloatArray(blob.size / 4) { i ->
        var bits = 0
        for (b in 3 downTo 0) bits = (bits shl 8) or (blob[i * 4 + b].toInt() and 0xFF)
        Float.fromBits(bits)
    }

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02X".format(it) }

    private fun unhex(vararg parts: String): ByteArray {
        val text = parts.joinToString("").replace(" ", "")
        return ByteArray(text.length / 2) { text.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }

    private fun half(value: Float): Int = MlSnapshotFormat.floatToHalf(value).toInt() and 0xFFFF

    private fun widen(bits: Int): Float = MlSnapshotFormat.halfToFloat(bits.toShort())

    private fun assertSameFloat(expected: Float, actual: Float) =
        assertEquals("expected $expected, got $actual", expected.toRawBits(), actual.toRawBits())

    private fun sampleFacesRecords(embeddingDim: Int): List<MlSnapshotRecord> {
        val random = Random(42)
        fun embedding() = ByteArray(embeddingDim * 4).also { random.nextBytes(it) }
        val fivePoints = floatArrayOf(412.5f, 300.25f, 498f, 301.5f, 455f, 352f, 420.75f, 401f, 490f, 399.5f)
        return listOf(
            PhotoKey("link-a"),
            PhotoKey("link-b"),
            PhotoKey("link-faceless"),
            PhotoKey("link-manual"),
            Person("Ann"),
            Person("Bob"),
            Person("Cleo"),
            Face(
                photoKey = "link-a", faceIndex = 0, left = 0.1f, top = 0.2f, right = 0.3f, bottom = 0.4f,
                landmarks = fivePoints, score = 0.98f, blur = 35.5f, rejected = false,
                personName = "Ann", confirmedName = "Ann", embedding = embedding(),
            ),
            Face(
                photoKey = "link-a", faceIndex = 1, left = 0.5f, top = 0.5f, right = 0.62f, bottom = 0.7f,
                landmarks = fivePoints.copyOf(), score = 0.64f, blur = null, rejected = true,
                personName = null, confirmedName = null, embedding = embedding(),
            ),
            Face(
                photoKey = "link-b", faceIndex = 0, left = 0f, top = 0f, right = 1f, bottom = 1f,
                landmarks = FloatArray(0), score = 0.7f, blur = 0f, rejected = false,
                personName = "Bob", confirmedName = null, embedding = embedding(),
            ),
            Face(
                photoKey = "link-b", faceIndex = 3, left = 0.25f, top = 0.125f, right = 0.375f, bottom = 0.3f,
                landmarks = fivePoints.copyOf(), score = 0.91f, blur = 120f, rejected = false,
                personName = null, confirmedName = "Cleo", embedding = embedding(),
            ),
            ManualPhoto("Ann", "link-manual"),
            ManualPhoto("Cleo", "link-b"),
            NotPerson("Bob", "link-a", 1),
            Cover("Ann", "link-a"),
            Cover("Bob", "link-b"),
            ScanMarker("link-a", hiResScanned = false),
            ScanMarker("link-b", hiResScanned = false),
            ScanMarker("link-faceless", hiResScanned = true),
        )
    }

    // Golden FACES snapshot, embedding width 2. The four parts are kept apart so a test can find a record.
    private val goldenFacesHeader = unhex(
        "50 50 4D 4C 53 4E 41 50", // magic "PPMLSNAP"
        "04 00 00 00", // version 4
        "01", // kind FACES
        "02 00 64 31", // device id "d1"
        "05 00 50 68 6F 6E 65", // device label "Phone"
        "08 07 06 05 04 03 02 01", // created at 0x0102030405060708
        "07 00 00 00", // face model version 7
        "02 00 61 62", // face model sha "ab"
        "01 00 00 00", // clustering parameters version 1
        "02 00 00 00", // embedding width 2
        "02 00 00 00", // keys
        "02 00 00 00", // people
        "01 00 00 00", // faces
        "01 00 00 00", // manual photos
        "01 00 00 00", // not-person marks
        "01 00 00 00", // covers
        "02 00 00 00", // scan markers
    )
    private val goldenFacesTables = unhex(
        "01 02 00 6B 31", // KEY 0 "k1"
        "01 02 00 6B 32", // KEY 1 "k2"
        "02 03 00 41 6E 6E", // PERSON 0 "Ann"
        "02 04 00 5A 6F C3 AB", // PERSON 1 "Zoë", the e-diaeresis as two UTF-8 bytes
    )
    private val goldenFace = unhex(
        "03", // FACE
        "00 00 00 00", // key 0
        "01 00 00 00", // face index 1
        "00 00 80 3E 00 00 00 3F 00 00 40 3F 00 00 80 3F", // box 0.25, 0.5, 0.75, 1.0
        "00 00 60 3F", // score 0.875
        "00 00 00 40", // blur 2.0
        "02", // flags: blur known
        "00 00 00 00", // grouped under person 0
        "FF FF FF FF", // no confirmed name
        "01", // one landmark point
        "00 00 80 3F 00 00 00 40", // (1.0, 2.0)
        "00 00 80 3F 00 00 80 BF", // embedding 1.0, -1.0
    )
    private val goldenFacesRest = unhex(
        "04 01 00 00 00 01 00 00 00", // MANUAL_PHOTO person 1, key 1
        "05 00 00 00 00 00 00 00 00 01 00 00 00", // NOT_PERSON person 0, key 0, face index 1
        "06 00 00 00 00 00 00 00 00", // COVER person 0, key 0
        "07 00 00 00 00 00", // SCAN_MARKER key 0
        "07 01 00 00 00 01", // SCAN_MARKER key 1, hi-res checked
        "FF", // END
    )
    private val goldenFacesBytes = goldenFacesHeader + goldenFacesTables + goldenFace + goldenFacesRest
    private val goldenFaceStart = goldenFacesHeader.size + goldenFacesTables.size

    private fun goldenFacesRecords(): List<MlSnapshotRecord> = listOf(
        PhotoKey("k1"),
        PhotoKey("k2"),
        Person("Ann"),
        Person("Zoë"),
        Face(
            photoKey = "k1", faceIndex = 1, left = 0.25f, top = 0.5f, right = 0.75f, bottom = 1f,
            landmarks = floatArrayOf(1f, 2f), score = 0.875f, blur = 2f, rejected = false,
            personName = "Ann", confirmedName = null, embedding = floats(1f, -1f),
        ),
        ManualPhoto("Zoë", "k2"),
        NotPerson("Ann", "k1", 1),
        Cover("Ann", "k1"),
        ScanMarker("k1", hiResScanned = false),
        ScanMarker("k2", hiResScanned = true),
    )

    private fun goldenFacesHeaderValue(records: List<MlSnapshotRecord>) = facesHeader(
        records,
        embeddingDim = 2,
        deviceId = "d1",
        deviceLabel = "Phone",
        createdAtMillis = 0x0102030405060708L,
        faceModelVersion = 7,
        faceModelSha256 = "ab",
    )

    @Test
    fun `a faces snapshot round-trips every record kind`() {
        val records = sampleFacesRecords(embeddingDim = 512)
        val header = facesHeader(records, embeddingDim = 512)

        val (readHeader, readRecords) = readAll(snapshotBytes(header, records))

        assertEquals(header, readHeader)
        assertEquals(records, readRecords)
        val faces = readRecords.filterIsInstance<Face>()
        assertEquals(listOf("link-a#0", "link-a#1", "link-b#0", "link-b#3"), faces.map { it.faceId })
        assertEquals(null, faces[1].blur)
        assertEquals(0f, faces[2].blur)
        assertEquals("link-a#1", readRecords.filterIsInstance<NotPerson>().single().faceId)
    }

    @Test
    fun `a search snapshot round-trips embeddings and tombstones`() {
        val random = Random(3)
        // Components already on the half grid, so the half-precision trip returns them bit for bit.
        fun onHalfGrid() = floats(*FloatArray(512) { widen(half(random.nextFloat() * 2f - 1f)) })
        val records = listOf(
            PhotoKey("link-a"),
            PhotoKey("link-b"),
            PhotoKey("link-unreadable"),
            SearchEmbedding("link-a", onHalfGrid()),
            SearchEmbedding("link-unreadable", ByteArray(512 * 4)),
            SearchEmbedding("link-b", onHalfGrid()),
        )
        val header = searchHeader(records, embeddingDim = 512)

        val (readHeader, readRecords) = readAll(snapshotBytes(header, records))

        assertEquals(header, readHeader)
        assertEquals(records, readRecords)
        assertEquals(listOf(false, true, false), readRecords.filterIsInstance<SearchEmbedding>().map { it.isTombstone })
    }

    @Test
    fun `a search embedding travels at half precision within rounding error`() {
        val random = Random(11)
        val raw = FloatArray(512) { random.nextFloat() * 2f - 1f }
        val norm = sqrt(raw.sumOf { it.toDouble() * it }).toFloat()
        val vector = FloatArray(raw.size) { raw[it] / norm }
        val records = listOf(PhotoKey("link-a"), SearchEmbedding("link-a", floats(*vector)))
        val bytes = snapshotBytes(searchHeader(records, embeddingDim = 512), records)
        val tombstoned = listOf(PhotoKey("link-a"), SearchEmbedding("link-a", ByteArray(512 * 4)))
        val tombstoneBytes = snapshotBytes(searchHeader(tombstoned, embeddingDim = 512), tombstoned)

        // Two bytes per component on the wire, none for a tombstone.
        assertEquals(512 * 2, bytes.size - tombstoneBytes.size)

        val back = unpack((readAll(bytes).second[1] as SearchEmbedding).embedding)
        var dot = 0.0
        for (i in vector.indices) {
            assertSameFloat(widen(half(vector[i])), back[i])
            val bound = maxOf(abs(vector[i]) / 2048f, Math.scalb(1f, -25))
            assertTrue("component $i is off by more than half a step", abs(back[i] - vector[i]) <= bound)
            dot += vector[i].toDouble() * back[i]
        }
        assertTrue("cosine $dot", dot > 0.9999)
    }

    @Test
    fun `a faces snapshot has the golden byte layout`() {
        val records = goldenFacesRecords()

        val written = snapshotBytes(goldenFacesHeaderValue(records), records)

        assertEquals(hex(goldenFacesBytes), hex(written))
        val (header, readRecords) = readAll(goldenFacesBytes)
        assertEquals(goldenFacesHeaderValue(records), header)
        assertEquals(records, readRecords)
    }

    @Test
    fun `a search snapshot has the golden byte layout`() {
        val expected = unhex(
            "50 50 4D 4C 53 4E 41 50", // magic "PPMLSNAP"
            "04 00 00 00", // version 4
            "02", // kind SEARCH
            "02 00 64 31", // device id "d1"
            "05 00 50 68 6F 6E 65", // device label "Phone"
            "08 07 06 05 04 03 02 01", // created at 0x0102030405060708
            "04 00 63 6C 69 70", // search model id "clip"
            "02 00 00 00", // embedding width 2
            "02 00 00 00", // keys
            "02 00 00 00", // embeddings
            "01 02 00 6B 31", // KEY 0 "k1"
            "01 02 00 6B 32", // KEY 1 "k2"
            "08 00 00 00 00 00 55 35 00 B8", // SEARCH_EMBEDDING key 0: 1/3 rounds to f16 0x3555, -0.5 is 0xB800
            "08 01 00 00 00 01", // SEARCH_EMBEDDING key 1, tombstone
            "FF", // END
        )
        val records = listOf(
            PhotoKey("k1"),
            PhotoKey("k2"),
            SearchEmbedding("k1", floats(1f / 3f, -0.5f)),
            SearchEmbedding("k2", ByteArray(8)),
        )
        val header = searchHeader(
            records,
            embeddingDim = 2,
            deviceId = "d1",
            deviceLabel = "Phone",
            createdAtMillis = 0x0102030405060708L,
            searchModelId = "clip",
        )

        assertEquals(hex(expected), hex(snapshotBytes(header, records)))
        val readBack = readAll(expected).second[2] as SearchEmbedding
        assertEquals(hex(floats(0.33325195f, -0.5f)), hex(readBack.embedding))
    }

    @Test
    fun `half conversion keeps zeros, limits, subnormals and special values`() {
        assertEquals(0x0000, half(0f))
        assertEquals(0x8000, half(-0f))
        assertEquals(0x3C00, half(1f))
        assertEquals(0xC000, half(-2f))
        assertEquals(0x3555, half(1f / 3f))
        assertEquals(0x7BFF, half(65504f))
        assertEquals(0x7BFF, half(Math.nextDown(65520f)))
        assertEquals(0x7C00, half(65520f))
        assertEquals(0x7C00, half(1e6f))
        assertEquals(0xFC00, half(-1e6f))
        assertEquals(0x7C00, half(Float.POSITIVE_INFINITY))
        assertEquals(0xFC00, half(Float.NEGATIVE_INFINITY))
        assertEquals(0x0400, half(Math.scalb(1f, -14)))
        assertEquals(0x03FF, half(Math.scalb(1023f, -24)))
        assertEquals(0x0001, half(Math.scalb(1f, -24)))
        assertEquals(0x0000, half(Math.scalb(1f, -25)))
        assertEquals(0x0001, half(Math.nextUp(Math.scalb(1f, -25))))
        assertEquals(0x8000, half(-Math.scalb(1f, -26)))
        assertEquals(0x0000, half(Float.MIN_VALUE))

        val nan = half(Float.NaN)
        assertEquals(0x7C00, nan and 0x7C00)
        assertNotEquals(0, nan and 0x03FF)
        assertTrue(widen(nan).isNaN())

        assertSameFloat(1f, widen(0x3C00))
        assertSameFloat(65504f, widen(0x7BFF))
        assertSameFloat(Float.POSITIVE_INFINITY, widen(0x7C00))
        assertSameFloat(Float.NEGATIVE_INFINITY, widen(0xFC00))
        assertSameFloat(Math.scalb(1f, -24), widen(0x0001))
        assertSameFloat(Math.scalb(1023f, -24), widen(0x03FF))
        assertSameFloat(-0f, widen(0x8000))
    }

    @Test
    fun `every half widens exactly and narrows back to itself`() {
        for (bits in 0..0xFFFF) {
            val exponent = (bits ushr 10) and 0x1F
            val mantissa = bits and 0x3FF
            if (exponent == 0x1F) continue
            val magnitude = if (exponent == 0) {
                Math.scalb(mantissa.toFloat(), -24)
            } else {
                Math.scalb((1024 + mantissa).toFloat(), exponent - 25)
            }
            val expected = if ((bits and 0x8000) != 0) -magnitude else magnitude
            assertSameFloat(expected, widen(bits))
            assertEquals(bits, half(widen(bits)))
        }
    }

    @Test
    fun `half conversion rounds to nearest with ties to even across the finite range`() {
        for (low in 0 until 0x7BFF) {
            val mid = (widen(low) + widen(low + 1)) / 2f
            val even = if (low % 2 == 0) low else low + 1
            assertEquals(low, half(Math.nextDown(mid)))
            assertEquals(even, half(mid))
            assertEquals(low + 1, half(Math.nextUp(mid)))
            assertEquals(even or 0x8000, half(-mid))
            assertEquals((low + 1) or 0x8000, half(-Math.nextUp(mid)))
        }
    }

    @Test
    fun `a stream cut short at any offset reads as truncated`() {
        val records = sampleFacesRecords(embeddingDim = 4)
        val bytes = snapshotBytes(facesHeader(records, embeddingDim = 4), records)

        for (cut in 0 until bytes.size) {
            val error = runCatching { readAll(bytes.copyOf(cut)) }.exceptionOrNull()
            assertTrue("a cut at $cut of ${bytes.size} gave $error", error is MlSnapshotTruncatedException)
        }
        assertEquals(records, readAll(bytes).second)
    }

    @Test
    fun `bytes after the end marker are refused`() {
        val error = assertThrows(MlSnapshotFormatException::class.java) { readAll(goldenFacesBytes + byteArrayOf(0)) }
        assertFalse(error is MlSnapshotTruncatedException)
    }

    @Test
    fun `an unknown version is refused before any record is read`() {
        for (version in listOf(5L, 3L, 0xFFFFFFFFL)) {
            val bytes = goldenFacesBytes.copyOf()
            for (b in 0 until 4) bytes[8 + b] = (version ushr (8 * b)).toByte()
            val error = assertThrows(UnsupportedMlSnapshotVersionException::class.java) {
                MlSnapshotReader(ByteArrayInputStream(bytes))
            }
            assertEquals(version, error.version)
        }
    }

    @Test
    fun `a foreign magic or an unknown kind is refused`() {
        val foreign = goldenFacesBytes.copyOf().also { it[0] = 'Q'.code.toByte() }
        val magicError = assertThrows(MlSnapshotFormatException::class.java) { MlSnapshotReader(ByteArrayInputStream(foreign)) }
        assertEquals(MlSnapshotFormatException::class.java, magicError.javaClass)

        val unknownKind = goldenFacesBytes.copyOf().also { it[12] = 3 }
        val kindError = assertThrows(MlSnapshotFormatException::class.java) { MlSnapshotReader(ByteArrayInputStream(unknownKind)) }
        assertEquals(MlSnapshotFormatException::class.java, kindError.javaClass)
    }

    @Test
    fun `a dangling reference, an unknown flag bit or an out-of-order record is refused`() {
        val keyOutOfRange = goldenFacesBytes.copyOf().also { it[goldenFaceStart + 1] = 2 }
        val unknownFlag = goldenFacesBytes.copyOf().also { it[goldenFaceStart + 33] = 0x06 }
        val personOutOfRange = goldenFacesBytes.copyOf().also { it[goldenFaceStart + 34] = 2 }
        val personTagInKeys = goldenFacesBytes.copyOf().also { it[goldenFacesHeader.size] = 0x02 }
        for (bytes in listOf(keyOutOfRange, unknownFlag, personOutOfRange, personTagInKeys)) {
            val error = assertThrows(MlSnapshotFormatException::class.java) { readAll(bytes) }
            assertFalse(error is MlSnapshotTruncatedException)
        }
    }

    @Test
    fun `non-ASCII names and labels survive as standard UTF-8`() {
        val names = listOf(
            "José Álvarez",
            "Ærøskøbing Søren",
            "Łukasz Żółć",
            "山田太郎",
            "김민준",
            "👩‍👧 Mama",
        )
        val records = buildList {
            add(PhotoKey("link-a"))
            names.forEach { add(Person(it)) }
            names.forEach { add(ManualPhoto(it, "link-a")) }
            add(Cover(names[3], "link-a"))
        }
        val header = facesHeader(records, embeddingDim = 4, deviceLabel = "Téléphone de Zoë")

        val bytes = snapshotBytes(header, records)
        val (readHeader, readRecords) = readAll(bytes)

        assertEquals(header, readHeader)
        assertEquals(records, readRecords)
        // The length prefix counts UTF-8 bytes: four CJK characters are twelve bytes.
        assertTrue(hex(bytes).contains("020C00E5B1B1E794B0E5A4AAE9838E"))
    }

    @Test
    fun `a string that is not valid UTF-8 is refused`() {
        val bytes = goldenFacesBytes.copyOf()
        val nameStart = goldenFacesHeader.size + 10 + 3
        assertEquals('A'.code.toByte(), bytes[nameStart])
        bytes[nameStart] = 0xFF.toByte()

        val error = assertThrows(MlSnapshotFormatException::class.java) { readAll(bytes) }
        assertFalse(error is MlSnapshotTruncatedException)
    }

    @Test
    fun `a long stream is read back one record at a time and in order`() {
        val count = 20_000
        val records = ArrayList<MlSnapshotRecord>(count * 2)
        for (i in 0 until count) records += PhotoKey("link-$i")
        for (i in count - 1 downTo 0) {
            records += SearchEmbedding("link-$i", floats((i % 1024).toFloat(), (i / 1024).toFloat(), 0.5f, -0.25f))
        }
        val bytes = snapshotBytes(searchHeader(records, embeddingDim = 4), records)
        val input = CountingInputStream(ByteArrayInputStream(bytes))

        val reader = MlSnapshotReader(input)
        assertTrue(reader.hasNext())
        assertEquals(PhotoKey("link-0"), reader.next())
        assertTrue("the reader drew ${input.consumed} of ${bytes.size} bytes for one record", input.consumed < bytes.size / 2)
        var index = 1
        while (reader.hasNext()) {
            assertEquals(records[index], reader.next())
            index++
        }

        assertEquals(records.size, index)
        assertFalse(reader.hasNext())
        assertThrows(NoSuchElementException::class.java) { reader.next() }
    }

    @Test
    fun `the writer holds the caller to the declared sections`() {
        val header = facesHeader(listOf(PhotoKey("link-a"), Person("Ann")), embeddingDim = 4)

        val early = MlSnapshotWriter(ByteArrayOutputStream(), header)
        assertThrows(IllegalStateException::class.java) { early.write(Person("Ann")) }

        val overflow = MlSnapshotWriter(ByteArrayOutputStream(), header)
        overflow.write(PhotoKey("link-a"))
        overflow.write(Person("Ann"))
        assertThrows(IllegalStateException::class.java) { overflow.write(Person("Bob")) }

        val short = MlSnapshotWriter(ByteArrayOutputStream(), header)
        short.write(PhotoKey("link-a"))
        assertThrows(IllegalStateException::class.java) { short.finish() }
    }

    @Test
    fun `a refused record leaves the stream intact`() {
        fun face(photoKey: String = "link-a", personName: String? = null, embedding: ByteArray = floats(1f, 0f, 0f, 0f)) = Face(
            photoKey = photoKey, faceIndex = 0, left = 0f, top = 0f, right = 1f, bottom = 1f,
            landmarks = FloatArray(0), score = 0.9f, blur = null, rejected = false,
            personName = personName, confirmedName = null, embedding = embedding,
        )
        val records = listOf(PhotoKey("link-a"), face())
        val buffer = ByteArrayOutputStream()
        val writer = MlSnapshotWriter(buffer, facesHeader(records, embeddingDim = 4))
        writer.write(PhotoKey("link-a"))

        assertThrows(IllegalArgumentException::class.java) { writer.write(face(photoKey = "link-unlisted")) }
        assertThrows(IllegalArgumentException::class.java) { writer.write(face(personName = "Nobody")) }
        assertThrows(IllegalArgumentException::class.java) { writer.write(face(embedding = ByteArray(12))) }
        writer.write(face())
        writer.finish()

        assertEquals(records, readAll(buffer.toByteArray()).second)
    }

    private class CountingInputStream(input: InputStream) : FilterInputStream(input) {
        var consumed = 0L

        override fun read(): Int = super.read().also { if (it >= 0) consumed++ }

        override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { if (it > 0) consumed += it }
    }
}
