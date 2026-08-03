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

package eu.akoos.photos.presentation.metadata

import eu.akoos.photos.domain.entity.CloudPhoto
import eu.akoos.photos.domain.entity.GalleryItem
import eu.akoos.photos.domain.entity.LocalMediaItem
import eu.akoos.photos.domain.entity.TimestampSanity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the bulk date shift: the whole point of it is that the spacing between the photos survives a
 * clock correction, so the assertions hold the delta maths, the set it is applied to and the ceiling
 * it may not cross. Plain JVM assertions, no ViewModel, no MediaStore, and the current instant is
 * always passed in.
 */
class DateShiftTest {

    private val day = 24 * 60 * 60 * 1000L
    private val nowMs = TimestampSanity.FLOOR_MS + 1_500_000_000_000L

    /** Three shots of one outing with uneven gaps: 2 minutes, then 3 hours. */
    private val firstMs = nowMs - 40 * day
    private val secondMs = firstMs + 2 * 60_000L
    private val thirdMs = secondMs + 3 * 60 * 60_000L

    private fun local(uri: String, dateTaken: Long) = GalleryItem.LocalOnly(
        LocalMediaItem(
            uri = uri,
            dateTaken = dateTaken,
            displayName = uri.substringAfterLast('/') + ".jpg",
            mimeType = "image/jpeg",
            sizeBytes = 1_200_000L,
            bucketName = "Camera",
        ),
    )

    private fun cloud(linkId: String, captureTimeSec: Long) = GalleryItem.CloudOnly(
        CloudPhoto(
            linkId = linkId,
            shareId = "share1",
            volumeId = "vol1",
            captureTime = captureTimeSec,
            displayName = "$linkId.jpg",
            mimeType = "image/jpeg",
            sizeBytes = 1_200_000L,
            thumbnailUrl = null,
            revisionId = "rev1",
        ),
    )

    private val outing = listOf(
        local("content://media/1", firstMs),
        local("content://media/2", secondMs),
        local("content://media/3", thirdMs),
    )
    private val outingUris = setOf("content://media/1", "content://media/2", "content://media/3")

    // region the photos a shift can move

    @Test
    fun `every dated photo of the selection is shiftable`() {
        assertEquals(
            listOf(
                DateShift.Target("content://media/1", firstMs),
                DateShift.Target("content://media/2", secondMs),
                DateShift.Target("content://media/3", thirdMs),
            ),
            DateShift.targets(outing, outingUris),
        )
    }

    @Test
    fun `a photo with no real date is left out and the rest still shift`() {
        // A file the platform dated 0, and one under the sanity floor: neither carries a date a delta
        // can be added to, and neither may cost the others their shift.
        val items = outing + local("content://media/4", 0L) +
            local("content://media/5", TimestampSanity.FLOOR_MS - 1_000L)

        val targets = DateShift.targets(items, outingUris + setOf("content://media/4", "content://media/5"))

        assertEquals(outingUris.toList().sorted(), targets.map { it.uri }.sorted())
        assertEquals(3, targets.size)
    }

    @Test
    fun `the floor itself is not a real date`() {
        val items = listOf(local("content://media/1", TimestampSanity.FLOOR_MS))

        assertTrue(DateShift.targets(items, setOf("content://media/1")).isEmpty())
    }

    @Test
    fun `a file the date write cannot reach is never shifted`() {
        // The shift set is a filter on top of the date targets, so a photo held back there (a HEIC,
        // whose column write the next scan reverts) stays held back here too.
        val targets = DateShift.targets(outing, setOf("content://media/2"))

        assertEquals(listOf(DateShift.Target("content://media/2", secondMs)), targets)
    }

    @Test
    fun `a cloud-only photo contributes nothing`() {
        val items = listOf(cloud("link1", nowMs / 1000L)) + outing

        assertEquals(3, DateShift.targets(items, outingUris).size)
    }

    @Test
    fun `no date targets means nothing to shift`() {
        assertTrue(DateShift.targets(outing, emptySet()).isEmpty())
        assertTrue(DateShift.targets(emptyList(), outingUris).isEmpty())
    }

    // endregion
    // region the span those photos cover

    @Test
    fun `the span runs from the oldest to the newest shiftable photo`() {
        val span = DateShift.span(DateShift.targets(outing, outingUris))

        assertEquals(DateShift.Span(firstMs, thirdMs), span)
        assertEquals(2 * 60_000L + 3 * 60 * 60_000L, span?.widthMs)
    }

    @Test
    fun `a dateless photo never drags the span down to the epoch`() {
        val items = outing + local("content://media/4", 0L)

        val span = DateShift.span(DateShift.targets(items, outingUris + "content://media/4"))

        assertEquals(DateShift.Span(firstMs, thirdMs), span)
    }

    @Test
    fun `a single photo spans no time at all`() {
        val span = DateShift.span(DateShift.targets(outing, setOf("content://media/2")))

        assertEquals(DateShift.Span(secondMs, secondMs), span)
        assertEquals(0L, span?.widthMs)
    }

    @Test
    fun `an empty set has no span`() {
        assertNull(DateShift.span(emptyList()))
    }

    // endregion
    // region how far the user may move them

    @Test
    fun `the ceiling leaves room for the newest photo`() {
        val span = DateShift.Span(firstMs, thirdMs)

        assertEquals(nowMs - span.widthMs, DateShift.maxEarliestMs(span, nowMs))
    }

    @Test
    fun `the boundary is allowed exactly and one millisecond past it is refused`() {
        val span = DateShift.Span(firstMs, thirdMs)
        val ceiling = DateShift.maxEarliestMs(span, nowMs)

        assertTrue(DateShift.allowsEarliest(span, ceiling, nowMs))
        assertFalse(DateShift.allowsEarliest(span, ceiling + 1, nowMs))
        // The newest photo lands exactly on the current instant, never past it.
        assertEquals(nowMs, DateShift.shifted(span, DateShift.deltaFor(span, ceiling)).latestMs)
    }

    @Test
    fun `moving the selection into the past is always allowed`() {
        val span = DateShift.Span(firstMs, thirdMs)

        assertTrue(DateShift.allowsEarliest(span, firstMs - 900 * day, nowMs))
    }

    @Test
    fun `a single photo may be put on the current instant itself`() {
        val span = DateShift.Span(secondMs, secondMs)

        assertEquals(nowMs, DateShift.maxEarliestMs(span, nowMs))
        assertTrue(DateShift.allowsEarliest(span, nowMs, nowMs))
        assertFalse(DateShift.allowsEarliest(span, nowMs + 1, nowMs))
    }

    // endregion
    // region the delta and where each photo lands

    @Test
    fun `putting the oldest photo earlier gives a negative delta`() {
        val span = DateShift.Span(firstMs, thirdMs)

        assertEquals(-2 * day, DateShift.deltaFor(span, firstMs - 2 * day))
    }

    @Test
    fun `putting the oldest photo later gives a positive delta`() {
        val span = DateShift.Span(firstMs, thirdMs)

        // The camera clock ran a year and a half behind: the whole outing moves forward by that much.
        assertEquals(547 * day, DateShift.deltaFor(span, firstMs + 547 * day))
    }

    @Test
    fun `picking the date the oldest photo already carries gives no delta`() {
        val span = DateShift.Span(firstMs, thirdMs)

        assertEquals(0L, DateShift.deltaFor(span, firstMs))
    }

    @Test
    fun `every photo keeps its own spacing after a shift`() {
        val targets = DateShift.targets(outing, outingUris)
        val delta = DateShift.deltaFor(DateShift.span(targets)!!, firstMs - 400 * day)

        val moved = DateShift.shifted(targets, delta, outingUris)

        assertEquals(
            listOf(firstMs - 400 * day, secondMs - 400 * day, thirdMs - 400 * day),
            moved.map { it.captureMs },
        )
        // The uneven gaps are the record of the day and survive the correction untouched.
        assertEquals(2 * 60_000L, moved[1].captureMs - moved[0].captureMs)
        assertEquals(3 * 60 * 60_000L, moved[2].captureMs - moved[1].captureMs)
        assertEquals(outingUris.toList().sorted(), moved.map { it.uri }.sorted())
    }

    @Test
    fun `a shift forward keeps the same spacing`() {
        val targets = DateShift.targets(outing, outingUris)

        val moved = DateShift.shifted(targets, 547 * day, outingUris)

        assertEquals(2 * 60_000L, moved[1].captureMs - moved[0].captureMs)
        assertEquals(3 * 60 * 60_000L, moved[2].captureMs - moved[0].captureMs - 2 * 60_000L)
        assertEquals(firstMs + 547 * day, moved[0].captureMs)
    }

    @Test
    fun `the shifted span moves without widening`() {
        val span = DateShift.Span(firstMs, thirdMs)

        val moved = DateShift.shifted(span, -30 * day)

        assertEquals(DateShift.Span(firstMs - 30 * day, thirdMs - 30 * day), moved)
        assertEquals(span.widthMs, moved.widthMs)
    }

    @Test
    fun `a zero delta moves nothing`() {
        val targets = DateShift.targets(outing, outingUris)
        val span = DateShift.Span(firstMs, thirdMs)

        assertSame(targets, DateShift.shifted(targets, 0L, outingUris))
        assertEquals(span, DateShift.shifted(span, 0L))
    }

    @Test
    fun `only the photos a write landed on move`() {
        // A partial batch: the refused files stay measured from where they still are, so a second
        // shift does not move them twice.
        val targets = DateShift.targets(outing, outingUris)

        val moved = DateShift.shifted(targets, 10 * day, setOf("content://media/1", "content://media/3"))

        assertEquals(listOf(firstMs + 10 * day, secondMs, thirdMs + 10 * day), moved.map { it.captureMs })
    }

    @Test
    fun `an empty or single target set shifts sanely`() {
        val single = DateShift.targets(outing, setOf("content://media/2"))

        assertTrue(DateShift.shifted(emptyList(), 5 * day, outingUris).isEmpty())
        assertEquals(
            listOf(secondMs + 5 * day),
            DateShift.shifted(single, 5 * day, setOf("content://media/2")).map { it.captureMs },
        )
    }

    // endregion
}
