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

package eu.akoos.photos.data.db.entity

import eu.akoos.photos.domain.entity.CloudPhoto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * The timeline's listing mapping holds each repeated value once and changes nothing else: the mapped
 * list equals the plain row-by-row mapping field for field and in order, every repeated value is one
 * instance across the rows, and the values that do not repeat are the row's own.
 *
 * The fixture hands every row its own String instances, as a cursor does, so sharing can only come
 * from the mapping.
 */
class PhotoListingLiteMappingTest {

    /** The row mapping with nothing shared, written out field by field: the reference the listing
     *  mapping must equal. */
    private fun referenceMapping(row: PhotoListingLite) = CloudPhoto(
        linkId = row.linkId,
        shareId = row.shareId,
        volumeId = row.volumeId,
        captureTime = row.captureTime,
        displayName = row.displayName,
        mimeType = row.mimeType,
        sizeBytes = row.sizeBytes,
        thumbnailUrl = null,
        revisionId = row.revisionId,
        contentHash = row.contentHash,
        tags = if (row.tagsCsv.isEmpty()) emptySet() else row.tagsCsv.split(',').mapNotNull { it.toIntOrNull() }.toSet(),
        durationMs = row.durationMs,
    )

    /** A new String with [value]'s content, never the same instance. */
    private fun copyOf(value: String): String = String(value.toCharArray())

    private fun fixture(size: Int, random: Random): List<PhotoListingLite> = List(size) { i ->
        val video = random.nextInt(5) == 0
        // A bare stub: the listing walk saw the link, its detail batch has not filled it in yet.
        val stub = random.nextInt(10) == 0
        PhotoListingLite(
            linkId = "link-$i",
            shareId = copyOf(SHARE_IDS[random.nextInt(SHARE_IDS.size)]),
            volumeId = copyOf(VOLUME_IDS[random.nextInt(VOLUME_IDS.size)]),
            captureTime = random.nextLong(0L, 2_000_000_000L),
            displayName = if (stub) "" else "IMG_$i.jpg",
            mimeType = copyOf(if (stub) "" else if (video) "video/mp4" else MIME_TYPES[random.nextInt(MIME_TYPES.size)]),
            sizeBytes = random.nextLong(0L, 50_000_000L),
            revisionId = if (stub) "" else "rev-$i",
            contentHash = listOf(null, "", "hash-$i")[random.nextInt(3)],
            tagsCsv = copyOf(TAG_CSVS[random.nextInt(TAG_CSVS.size)]),
            durationMs = if (video && random.nextBoolean()) random.nextLong(1L, 600_000L) else null,
        )
    }

    @Test
    fun `the listing maps to what the row mapping gives, row for row and in order`() {
        val rows = fixture(3_000, Random(7))
        val reference = rows.map { referenceMapping(it) }

        val mapped = rows.toCloudPhotos()

        assertEquals(reference, mapped)
        assertEquals("the single-row mapping gives the same rows", reference, rows.map { it.toDomain() })
        assertTrue("the thumbnail stays with the in-memory store", mapped.all { it.thumbnailUrl == null })
    }

    @Test
    fun `a link the rows carry twice is mapped once, from its first row`() {
        // A read the table changed under can hand a row back twice, re-dated or not; the grid keys
        // cloud items by link and throws on the repeat, so the mapping keeps the first and drops the rest.
        val rows = fixture(40, Random(13))
        val repeated = rows[10].copy(captureTime = rows[10].captureTime - 1)
        val torn = rows.take(25) + repeated + rows[3] + rows.drop(25)

        val mapped = torn.toCloudPhotos()

        assertEquals(rows.map { referenceMapping(it) }, mapped)
        assertEquals(mapped.size, mapped.map { it.linkId }.toSet().size)
    }

    @Test
    fun `a repeated value is one instance across the whole listing`() {
        val rows = fixture(2_000, Random(11))
        val first = rows.first()
        val twin = rows.drop(1).first { it.shareId == first.shareId }
        assertNotSame("the fixture must hand each row its own copy", first.shareId, twin.shareId)

        val pairs = rows.zip(rows.toCloudPhotos())

        assertOneInstancePerValue("share id", pairs.map { (row, photo) -> row.shareId to photo.shareId })
        assertOneInstancePerValue("volume id", pairs.map { (row, photo) -> row.volumeId to photo.volumeId })
        assertOneInstancePerValue("mime type", pairs.map { (row, photo) -> row.mimeType to photo.mimeType })
        assertOneInstancePerValue("tag set", pairs.map { (row, photo) -> row.tagsCsv to photo.tags })
    }

    @Test
    fun `a value that does not repeat is carried as the row holds it`() {
        val rows = fixture(500, Random(3))

        rows.zip(rows.toCloudPhotos()).forEach { (row, photo) ->
            assertSame(row.linkId, photo.linkId)
            assertSame(row.displayName, photo.displayName)
            assertSame(row.revisionId, photo.revisionId)
            assertSame(row.contentHash, photo.contentHash)
            assertEquals(row.durationMs, photo.durationMs)
        }
    }

    @Test
    fun `a pass holds nothing over from an earlier one`() {
        // Nothing a pass shares outlives the list it mapped, so no value is pinned past its listing.
        val rows = fixture(50, Random(5))
        val copies = rows.map { it.copy(shareId = copyOf(it.shareId)) }

        val earlier = rows.toCloudPhotos()
        val later = copies.toCloudPhotos()

        assertEquals(earlier, later)
        assertSame("a pass shares the instance of the first row holding a value", copies.first().shareId, later.first().shareId)
        assertNotSame(earlier.first().shareId, later.first().shareId)
    }

    @Test
    fun `past its bound the interner hands a new value back unshared`() {
        val interner = ListingValueInterner(maxEntries = 2)
        val a = interner.string(copyOf("a"))
        interner.string(copyOf("b"))

        val c1 = copyOf("c")
        val c2 = copyOf("c")
        assertSame(c1, interner.string(c1))
        assertSame("a value past the bound is not held", c2, interner.string(c2))
        assertSame("a value held before the bound stays shared", a, interner.string(copyOf("a")))

        val one = interner.tags(copyOf("1"))
        interner.tags(copyOf("2"))
        val threeOnce = interner.tags(copyOf("3"))
        val threeAgain = interner.tags(copyOf("3"))
        assertEquals(setOf(3), threeAgain)
        assertNotSame("a tag set past the bound is not held", threeOnce, threeAgain)
        assertSame("a tag set held before the bound stays shared", one, interner.tags(copyOf("1")))
    }

    @Test
    fun `tag lists parse as the row mapping parses them`() {
        val interner = ListingValueInterner()

        TAG_CSVS.forEach { csv ->
            val expected = if (csv.isEmpty()) emptySet() else csv.split(',').mapNotNull { it.toIntOrNull() }.toSet()
            assertEquals("tags of '$csv'", expected, interner.tags(copyOf(csv)))
        }
        assertEquals(emptySet<Int>(), interner.tags(""))
    }

    /** Each pair is a row's value and the mapped one; every row with the same value must map to the
     *  same instance. */
    private fun <V : Any> assertOneInstancePerValue(what: String, pairs: List<Pair<Any, V>>) {
        val held = HashMap<Any, V>()
        pairs.forEach { (key, mapped) -> assertSame(what, held.getOrPut(key) { mapped }, mapped) }
        assertTrue("the fixture must repeat every $what", held.size < pairs.size)
    }

    private companion object {
        // Shaped like Drive ids: 88 characters of base64.
        val SHARE_IDS = listOf("S".repeat(86) + "==", "s".repeat(86) + "==")
        val VOLUME_IDS = listOf("V".repeat(86) + "==")
        val MIME_TYPES = listOf("image/jpeg", "image/heic", "image/png", "video/quicktime", "image/x-adobe-dng")
        val TAG_CSVS = listOf("", "0", "2", "0,2", "3,4", "1", "0,1,5", "7,x", ",", "9,,3")
    }
}
