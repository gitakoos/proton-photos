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

package eu.akoos.photos.presentation.gallery

import eu.akoos.photos.domain.entity.CloudPhoto
import eu.akoos.photos.domain.entity.GalleryItem
import eu.akoos.photos.domain.entity.LocalMediaItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * The timeline's cell key → position lookup behind tap-to-open, drag-select, the decrypt driver and
 * the scroll-date label. [GalleryKeyIndex] stands in for a HashMap keyed by built key strings, so each
 * case holds it to that map's answers: the same position for every key, nothing for a cell with no
 * photo behind it, and the last position for a repeated key. Plain JVM assertions over hand-built items.
 */
class GalleryKeyIndexTest {

    private fun local(uri: String) = LocalMediaItem(
        uri = uri,
        dateTaken = 1_700_000_000_000L,
        displayName = "IMG_0001.jpg",
        mimeType = "image/jpeg",
        sizeBytes = 2048L,
        bucketName = "Camera",
    )

    private fun cloud(linkId: String) = CloudPhoto(
        linkId = linkId,
        shareId = "share1",
        volumeId = "vol1",
        captureTime = 1_700_000_000L,
        displayName = "IMG_0001.jpg",
        mimeType = "image/jpeg",
        sizeBytes = 2048L,
        thumbnailUrl = null,
        revisionId = "rev1",
    )

    private fun localOnly(uri: String) = GalleryItem.LocalOnly(local(uri))
    private fun synced(uri: String, linkId: String) = GalleryItem.Synced(cloud(linkId), local(uri))
    private fun cloudOnly(linkId: String) = GalleryItem.CloudOnly(cloud(linkId))

    /** The map the grid is held to: every item's key string, filled in list order. */
    private fun keyMap(items: List<GalleryItem>): Map<String, Int> =
        HashMap<String, Int>().apply { items.forEachIndexed { i, item -> put(GalleryKeyIndex.keyOf(item), i) } }

    private fun mixedLibrary(size: Int): List<GalleryItem> = List(size) { i ->
        when (i % 3) {
            0 -> localOnly("content://media/external/images/media/$i")
            1 -> synced("content://media/external/images/media/$i", "link-$i==")
            else -> cloudOnly("link-$i==")
        }
    }

    // region the key scheme

    @Test
    fun `cell keys keep the local, synced and cloud prefixes on the item's own id`() {
        assertEquals("local_content://m/1", GalleryKeyIndex.keyOf(localOnly("content://m/1")))
        // A synced cell is keyed on its device uri, not on its linkId.
        assertEquals("synced_content://m/2", GalleryKeyIndex.keyOf(synced("content://m/2", "L2")))
        assertEquals("cloud_L3", GalleryKeyIndex.keyOf(cloudOnly("L3")))
    }

    // endregion

    // region lookups agree with the key map

    @Test
    fun `every key resolves to the position the key map holds`() {
        val items = mixedLibrary(3_000)
        val index = GalleryKeyIndex(items)
        val expected = keyMap(items)

        assertEquals(expected.size, index.size)
        for ((key, position) in expected) {
            assertEquals(key, position, index[key])
            assertTrue(key, index.containsKey(key))
            assertSame(key, items[position], index.itemFor(key))
        }
    }

    @Test
    fun `an item finds its own position without a key string`() {
        val items = mixedLibrary(3_000)
        val index = GalleryKeyIndex(items)

        items.forEachIndexed { i, item -> assertEquals(i, index.positionOf(item)) }
    }

    @Test
    fun `a reversed timeline indexes the reversed positions`() {
        // The newest-last order hands the grid an asReversed() view of the same list.
        val items = mixedLibrary(1_000).asReversed()
        val index = GalleryKeyIndex(items)

        items.forEachIndexed { i, item -> assertEquals(i, index[GalleryKeyIndex.keyOf(item)]) }
    }

    @Test
    fun `a large library with repeated ids matches the key map entry for entry`() {
        // Random linkIds, some drawn twice, so the probe chains run long and cross the table's end.
        val random = Random(42)
        val ids = List(20_000) { "L" + random.nextLong().toString(36) }
        val items = List(30_000) { i -> if (i < ids.size) cloudOnly(ids[i]) else cloudOnly(ids[random.nextInt(ids.size)]) }
        val index = GalleryKeyIndex(items)
        val expected = keyMap(items)

        assertEquals(expected.size, index.size)
        assertEquals(expected, HashMap(index))
        for ((key, position) in expected) assertEquals(key, position, index[key])
    }

    @Test
    fun `ids whose String hashes collide are told apart`() {
        // "Aa" and "BB" share a String hash, so each group below lands on one probe chain.
        assertEquals("xAa".hashCode(), "xBB".hashCode())
        assertEquals("AaAa".hashCode(), "BBBB".hashCode())
        val items = listOf(
            cloudOnly("xAa"), cloudOnly("xBB"),
            localOnly("AaAa"), localOnly("BBBB"), localOnly("AaBB"), localOnly("BBAa"),
            synced("AaAa", "S1"),
        )
        val index = GalleryKeyIndex(items)

        assertEquals(keyMap(items), HashMap(index))
        items.forEachIndexed { i, item ->
            assertEquals(i, index[GalleryKeyIndex.keyOf(item)])
            assertEquals(i, index.positionOf(item))
        }
    }

    // endregion

    // region cells with no photo behind them

    @Test
    fun `keys with no photo behind them resolve to nothing`() {
        val items = listOf(localOnly("content://m/1"), synced("content://m/2", "L2"), cloudOnly("L3"))
        val index = GalleryKeyIndex(items)

        // The memories row carries a plain key; headers carry none the grid would look up.
        assertNull(index["on_this_day"])
        assertNull(index[""])
        assertFalse(index.containsKey("on_this_day"))
        // The right id under another prefix is another cell.
        assertNull(index["synced_content://m/1"])
        assertNull(index["local_content://m/2"])
        assertNull(index["cloud_content://m/2"])
        assertNull(index["local_L3"])
        // A synced cell is not reachable through its linkId.
        assertNull(index["synced_L2"])
        assertNull(index["cloud_L2"])
        // A bare prefix, and ids that differ only in length.
        assertNull(index["cloud_"])
        assertNull(index["cloud_L"])
        assertNull(index["cloud_L33"])
        assertNull(index.itemFor("cloud_missing"))
    }

    @Test
    fun `an item that is not in the list has no position`() {
        val index = GalleryKeyIndex(listOf(synced("content://m/2", "L2"), cloudOnly("L3")))

        assertEquals(-1, index.positionOf(cloudOnly("missing")))
        // The device-only twin of a synced uri is a different cell.
        assertEquals(-1, index.positionOf(localOnly("content://m/2")))
        assertEquals(-1, index.positionOf(cloudOnly("L2")))
    }

    @Test
    fun `an empty timeline answers nothing`() {
        val index = GalleryKeyIndex(emptyList())

        assertEquals(0, index.size)
        assertTrue(index.isEmpty())
        assertNull(index["cloud_L1"])
        assertEquals(-1, index.positionOf(cloudOnly("L1")))
        assertTrue(index.entries.isEmpty())
    }

    // endregion

    // region a repeated key

    @Test
    fun `a repeated key keeps its last position, as the key map did`() {
        val items = listOf(cloudOnly("L1"), localOnly("content://m/1"), cloudOnly("L1"))
        val index = GalleryKeyIndex(items)

        assertEquals(2, index["cloud_L1"])
        assertEquals(2, index.positionOf(items[0]))
        assertEquals(2, index.size)
        assertEquals(keyMap(items), HashMap(index))
        // Values are the surviving positions only.
        assertFalse(index.containsValue(0))
        assertTrue(index.containsValue(1))
        assertTrue(index.containsValue(2))
        assertFalse(index.containsValue(3))
        assertFalse(index.containsValue(-1))
    }

    // endregion

    @Test
    fun `equality is identity, so a rebuilt index always reads as a new value`() {
        // State holders compare the old and new index on every list emission; an entry-wise compare
        // would build every key string the index avoids holding.
        val items = mixedLibrary(10)
        val index = GalleryKeyIndex(items)

        assertEquals(index, index)
        assertNotEquals(index, GalleryKeyIndex(items))
    }
}
