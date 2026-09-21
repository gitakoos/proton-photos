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

package eu.akoos.photos.util

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer

/**
 * Pins the mvhd capture time the app reads out of a video, against box trees built byte by byte so
 * every shape the walk has to survive is exercised without shipping binary fixtures or a device.
 *
 * Two things carry the whole feature. The 1904 epoch, which has to survive both directions or every
 * video in a library shifts by 66 years; and the absent value, because a great many muxers leave the
 * creation time 0, and a 0 read as a date would look like a capture from 1904 that the date
 * correction would then dutifully write over every one of those files.
 *
 * A third carries the reveal: whether a stamp runs at all. A video comes out of the vault as the raw
 * byte copy that went in, so a reveal that rewrote timestamps the file already carries would hand back
 * a different file for no gain. The field counts whole seconds, so "already carries" is settled at the
 * second and a sub-second difference is no difference.
 *
 * Everything here goes through the file route. The descriptor route runs on positional syscalls that
 * only a device provides, so what it does with a real descriptor is pinned by the instrumented
 * RealLibraryMetadataTest instead, against the videos the device itself holds.
 */
class Mp4CreationTimeTest {

    /** Seconds between the MP4 epoch (1904-01-01) and the Unix epoch, spelled out here so the test
     *  measures the conversion rather than reusing whatever the code believes. */
    private val mp4EpochOffsetSeconds = 2_082_844_800L

    private val captureMs = 1_783_507_135_000L // a real capture instant (ms)

    // ---- box builders -----------------------------------------------------

    private fun ascii(text: String): ByteArray = text.toByteArray(Charsets.US_ASCII)

    /**
     * A timestamp-carrying full box of [type] — mvhd, tkhd or mdhd — holding [creationTime] in MP4
     * seconds. Version 1 stores 64-bit times and version 0 32-bit ones, and the trailing fixed fields
     * (timescale, duration, rate, matrix and the rest) are left zeroed: neither walk looks past the
     * two timestamps, but the bytes have to be there or the walk runs off the end of the file.
     */
    private fun timestampBox(type: String, version: Int, creationTime: Long): ByteArray {
        val contentSize = if (version == 1) 112 else 100
        val buffer = ByteBuffer.allocate(8 + contentSize)
        buffer.putInt(8 + contentSize)
        buffer.put(ascii(type))
        buffer.put(version.toByte())
        buffer.put(ByteArray(3)) // flags
        if (version == 1) {
            buffer.putLong(creationTime)
            buffer.putLong(creationTime)
        } else {
            buffer.putInt(creationTime.toInt())
            buffer.putInt(creationTime.toInt())
        }
        return buffer.array()
    }

    private fun mvhd(version: Int, creationTime: Long): ByteArray =
        timestampBox("mvhd", version, creationTime)

    /** A container box of [type] wrapping [children]. */
    private fun container(type: String, vararg children: ByteArray): ByteArray {
        val size = 8 + children.sumOf { it.size }
        val buffer = ByteBuffer.allocate(size)
        buffer.putInt(size)
        buffer.put(ascii(type))
        children.forEach { buffer.put(it) }
        return buffer.array()
    }

    /** A container box of [type] declaring its size in the 64-bit form: the 32-bit field holds 1 and
     *  the true size follows the type, which pushes the content 16 bytes in rather than 8. */
    private fun largeContainer(type: String, vararg children: ByteArray): ByteArray {
        val size = 16 + children.sumOf { it.size }
        val buffer = ByteBuffer.allocate(size)
        buffer.putInt(1)
        buffer.put(ascii(type))
        buffer.putLong(size.toLong())
        children.forEach { buffer.put(it) }
        return buffer.array()
    }

    /** The ftyp box every real MP4 opens with, so the walk has to step over a sibling to find moov. */
    private fun ftyp(): ByteArray {
        val buffer = ByteBuffer.allocate(16)
        buffer.putInt(16)
        buffer.put(ascii("ftyp"))
        buffer.put(ascii("isom"))
        buffer.putInt(512)
        return buffer.array()
    }

    private fun fileOf(vararg boxes: ByteArray): File {
        val file = File.createTempFile("pfp_mp4_", ".mp4")
        file.deleteOnExit()
        file.writeBytes(boxes.fold(ByteArray(0)) { acc, box -> acc + box })
        return file
    }

    // ---- the epoch conversion, both directions ----------------------------

    @Test
    fun `an epoch instant converts to MP4 seconds and back`() {
        val mp4Seconds = Mp4CreationTime.toMp4Time(captureMs)
        assertEquals(captureMs / 1000L + mp4EpochOffsetSeconds, mp4Seconds)
        assertEquals(captureMs, Mp4CreationTime.fromMp4Time(mp4Seconds))
    }

    @Test
    fun `the conversion drops the sub-second part the field cannot hold`() {
        // The field counts whole seconds, so a round trip can only ever return to the second.
        assertEquals(captureMs, Mp4CreationTime.fromMp4Time(Mp4CreationTime.toMp4Time(captureMs + 750L)))
    }

    @Test
    fun `an absent creation time decodes to nothing`() {
        // What a muxer that never filled the field leaves behind. Decoded as a date it would read as
        // 1904, which is exactly the kind of value the date correction would act on.
        assertNull(Mp4CreationTime.fromMp4Time(0L))
        assertNull(Mp4CreationTime.fromMp4Time(-1L))
    }

    @Test
    fun `a creation time at or before the Unix epoch decodes to nothing`() {
        // Positive but still pre-1970: no camera captured it, so it is the absent value in another
        // disguise rather than a date to correct towards.
        assertNull(Mp4CreationTime.fromMp4Time(mp4EpochOffsetSeconds))
        assertNull(Mp4CreationTime.fromMp4Time(mp4EpochOffsetSeconds - 1L))
        assertEquals(1000L, Mp4CreationTime.fromMp4Time(mp4EpochOffsetSeconds + 1L))
    }

    // ---- the box walk -----------------------------------------------------

    @Test
    fun `a version 0 mvhd reads back as the instant it holds`() {
        // The 32-bit field is unsigned: this value is past the signed range, so a signed read would
        // land in 1902 rather than in 2026.
        val mp4Seconds = captureMs / 1000L + mp4EpochOffsetSeconds
        val file = fileOf(ftyp(), container("moov", mvhd(version = 0, creationTime = mp4Seconds)))
        assertEquals(captureMs, Mp4CreationTime.read(file))
    }

    @Test
    fun `a version 1 mvhd reads back as the instant it holds`() {
        // Past what 32 bits can hold at all, which is the whole reason the 64-bit version exists.
        val farFutureMs = 5_000_000_000_000L
        val mp4Seconds = farFutureMs / 1000L + mp4EpochOffsetSeconds
        val file = fileOf(ftyp(), container("moov", mvhd(version = 1, creationTime = mp4Seconds)))
        assertEquals(farFutureMs, Mp4CreationTime.read(file))
    }

    @Test
    fun `a stamped file reads back as the date it was stamped with`() {
        // The write and the read are one walk over one tree, so they have to agree on every offset.
        val file = fileOf(ftyp(), container("moov", mvhd(version = 0, creationTime = 0L)))
        assertNull("the fixture must start with no usable date", Mp4CreationTime.read(file))
        assertTrue(Mp4CreationTime.stamp(file, captureMs))
        assertEquals(captureMs, Mp4CreationTime.read(file))
    }

    @Test
    fun `a stamp reaches an mvhd nested behind other boxes`() {
        // moov holds trak and udta siblings in a real file, so the mvhd is rarely the first child.
        val file = fileOf(
            ftyp(),
            container(
                "moov",
                container("udta", ftyp()),
                mvhd(version = 0, creationTime = 0L),
                container("trak", container("mdia", ByteArray(0))),
            ),
        )
        assertTrue(Mp4CreationTime.stamp(file, captureMs))
        assertEquals(captureMs, Mp4CreationTime.read(file))
    }

    @Test
    fun `a stamp reaches an mvhd inside a 64-bit sized container`() {
        // A size field of 1 means the real size follows the type as a 64-bit value, which is how a box
        // too large for the 32-bit field declares itself. Both walks have to step over 16 header bytes
        // there instead of 8, or the write lands mid-field and the read answers from the wrong offset.
        val file = fileOf(ftyp(), largeContainer("moov", mvhd(version = 0, creationTime = 0L)))
        assertTrue(Mp4CreationTime.stamp(file, captureMs))
        assertEquals(captureMs, Mp4CreationTime.read(file))
    }

    @Test
    fun `a zeroed mvhd reads as no date at all`() {
        val file = fileOf(ftyp(), container("moov", mvhd(version = 0, creationTime = 0L)))
        assertNull(Mp4CreationTime.read(file))
    }

    @Test
    fun `a container with no mvhd reads as no date`() {
        assertNull(Mp4CreationTime.read(fileOf(ftyp())))
        assertNull(Mp4CreationTime.read(fileOf(ftyp(), container("moov", container("udta", ftyp())))))
    }

    @Test
    fun `a truncated or malformed file reads as no date instead of throwing`() {
        // A box declaring more bytes than the file holds stops the walk; so does a file that is not
        // an MP4 at all. Either way the caller gets null and the date leg leaves the file alone.
        val truncated = fileOf(ftyp(), container("moov", mvhd(version = 0, creationTime = 12345L)))
        truncated.writeBytes(truncated.readBytes().copyOf(24))
        assertNull(Mp4CreationTime.read(truncated))
        assertNull(Mp4CreationTime.read(fileOf("not an mp4 at all".toByteArray())))
        assertNull(Mp4CreationTime.read(fileOf(ByteArray(0))))
    }

    @Test
    fun `a missing file reads as no date`() {
        val missing = File(System.getProperty("java.io.tmpdir"), "pfp_mp4_absent_${System.nanoTime()}.mp4")
        assertNull(Mp4CreationTime.read(missing))
    }

    // ---- whether the stamp runs at all ------------------------------------

    /**
     * A movie whose mvhd holds [mvhdSeconds] and whose track headers hold nothing.
     *
     * The mvhd is what the decision reads, and the two track headers are what makes a write visible:
     * a stamp reaches all three, so a run that writes the value the mvhd already holds still changes
     * the file. Leaving tkhd and mdhd at zero is therefore the only way to tell "wrote the same value"
     * apart from "wrote nothing", which is the whole claim under test.
     */
    private fun movieWithTracks(mvhdSeconds: Long): File = fileOf(
        ftyp(),
        container(
            "moov",
            mvhd(version = 0, creationTime = mvhdSeconds),
            container(
                "trak",
                timestampBox("tkhd", version = 0, creationTime = 0L),
                container("mdia", timestampBox("mdhd", version = 0, creationTime = 0L)),
            ),
        ),
    )

    @Test
    fun `a modern capture lands past what a signed 32-bit field could hold`() {
        // The 1904 epoch pushes every date after 2004 beyond the signed range, so the seconds field
        // is written and read unsigned. A signed read of this value answers a date in 1902.
        val mp4Seconds = Mp4CreationTime.toMp4Time(captureMs)
        assertTrue("the fixture must exercise the unsigned range", mp4Seconds > Int.MAX_VALUE.toLong())
        assertEquals(captureMs, Mp4CreationTime.fromMp4Time(mp4Seconds))
    }

    @Test
    fun `a difference the field cannot record is no difference`() {
        // Whole seconds are all the field holds, so a stamp of either value lands on the same bytes.
        assertFalse(Mp4CreationTime.stampWouldChange(captureMs, captureMs))
        assertFalse(Mp4CreationTime.stampWouldChange(captureMs, captureMs + 750L))
        assertFalse(Mp4CreationTime.stampWouldChange(captureMs + 999L, captureMs))
    }

    @Test
    fun `a difference of a whole second is a difference`() {
        // Either side of the granularity: 999ms later still lands on the recorded second, 1000ms
        // later does not, and the same one millisecond earlier crosses back.
        assertFalse(Mp4CreationTime.stampWouldChange(captureMs, captureMs + 999L))
        assertTrue(Mp4CreationTime.stampWouldChange(captureMs, captureMs + 1_000L))
        assertTrue(Mp4CreationTime.stampWouldChange(captureMs, captureMs - 1L))
    }

    @Test
    fun `a video already recording the instant is handed back untouched`() {
        // The claim a reveal rests on: a vault copy is the bytes that went in, so a video already
        // carrying its capture second comes back out byte for byte, sub-second slack included.
        val file = movieWithTracks(Mp4CreationTime.toMp4Time(captureMs))
        val before = file.readBytes()

        assertTrue(Mp4CreationTime.stampIfChanged(file, captureMs + 750L))

        assertArrayEquals("no byte may be written for a date the file already records", before, file.readBytes())
        assertEquals(captureMs, Mp4CreationTime.read(file))
    }

    @Test
    fun `a video recording another instant is rewritten`() {
        val file = movieWithTracks(Mp4CreationTime.toMp4Time(captureMs))
        val before = file.readBytes()
        val corrected = captureMs + 1_000L

        assertTrue(Mp4CreationTime.stampIfChanged(file, corrected))

        assertFalse("a second's difference has to reach the file", before.contentEquals(file.readBytes()))
        assertEquals(corrected, Mp4CreationTime.read(file))
    }

    @Test
    fun `a video recording no instant at all is stamped`() {
        // What most muxers leave behind, and the case the stamp exists for: nothing to compare
        // against, so the date is written.
        val file = movieWithTracks(0L)

        assertTrue(Mp4CreationTime.stampIfChanged(file, captureMs))

        assertEquals(captureMs, Mp4CreationTime.read(file))
    }

    @Test
    fun `a file with no readable mvhd is left as it is`() {
        // A tree holding no mvhd, and bytes that are no MP4 at all. Neither offers an instant to
        // compare against and neither offers a field to write one into, so the walk runs, finds
        // nothing and touches nothing.
        for (file in listOf(fileOf(ftyp()), fileOf("not an mp4 at all".toByteArray()))) {
            val before = file.readBytes()
            // The answer is the walk completing, the same as a plain [Mp4CreationTime.stamp]'s.
            assertTrue(Mp4CreationTime.stampIfChanged(file, captureMs))
            assertArrayEquals(before, file.readBytes())
        }
    }

    @Test
    fun `a capture past the signed 32-bit range is recognised as the one already recorded`() {
        // The decision compares against what [Mp4CreationTime.read] answers, so a signed read of the
        // 32-bit field would answer 1902 here, call the file wrong and rewrite every video in a
        // library on every reveal.
        val file = movieWithTracks(Mp4CreationTime.toMp4Time(captureMs))
        val before = file.readBytes()

        assertTrue(Mp4CreationTime.stampIfChanged(file, captureMs))

        assertArrayEquals(before, file.readBytes())
    }
}
