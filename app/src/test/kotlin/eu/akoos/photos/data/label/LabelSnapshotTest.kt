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

package eu.akoos.photos.data.label

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LabelSnapshotTest {

    private fun snapshot(deviceId: String, vararg photos: LabelSnapshotPhoto, names: List<String> = emptyList()) =
        LabelSnapshot(deviceId = deviceId, deviceLabel = "Phone", createdAtMillis = 1L, photos = photos.toList(), names = names)

    private fun photo(key: String, vararg labels: String) = LabelSnapshotPhoto(key, labels.toList())

    @Test
    fun `a snapshot survives the round trip through its plaintext form`() {
        val original = snapshot(OWN, photo(LINK, "Café", "beach"), photo(LINK_2), names = listOf("Empty", "Café"))

        assertEquals(original, LabelSnapshotCodec.decode(LabelSnapshotCodec.encode(original)))
    }

    @Test
    fun `a snapshot without labels that have no photo reads as having none`() {
        val bytes = """{"v":1,"d":"$OWN","n":"x","c":1,"p":[]}""".toByteArray()

        assertEquals(emptyList<String>(), LabelSnapshotCodec.decode(bytes)?.names)
    }

    @Test
    fun `bytes that are not a snapshot decode to nothing`() {
        assertNull(LabelSnapshotCodec.decode("not json".toByteArray()))
        assertNull(LabelSnapshotCodec.versionOf("not json".toByteArray()))
        assertNull(LabelSnapshotCodec.decode("""{"v":1}""".toByteArray()))
    }

    @Test
    fun `a newer format is told apart from a damaged one`() {
        val newer = """{"v":99,"d":"$OWN","n":"x","c":1,"p":[]}""".toByteArray()

        assertEquals(99, LabelSnapshotCodec.versionOf(newer))
        assertNull(LabelSnapshotCodec.decode(newer))
    }

    @Test
    fun `unknown fields from a later build are ignored`() {
        val bytes = """{"v":1,"d":"$OWN","n":"x","c":1,"p":[],"extra":true}""".toByteArray()

        assertEquals(snapshot(OWN).copy(deviceLabel = "x"), LabelSnapshotCodec.decode(bytes))
    }

    @Test
    fun `only a link id is portable between devices`() {
        assertTrue(LabelSnapshotMerge.isPortableKey(LINK))
        assertFalse(LabelSnapshotMerge.isPortableKey(URI))
        assertFalse(LabelSnapshotMerge.isPortableKey("file:///sdcard/a.jpg"))
        assertFalse(LabelSnapshotMerge.isPortableKey(""))
    }

    @Test
    fun `a device key is restored only from the copy this install saved itself`() {
        val photos = arrayOf(photo(URI, "a"), photo(LINK, "b"))

        val fromOther = LabelSnapshotMerge.usablePhotos(snapshot(OTHER_DEVICE, *photos), OWN)
        val fromOwn = LabelSnapshotMerge.usablePhotos(snapshot(OWN, *photos), OWN)

        assertEquals(listOf(LINK), fromOther.map { it.key })
        assertEquals(listOf(URI, LINK), fromOwn.map { it.key })
    }

    @Test
    fun `a photo without a key is never restored`() {
        assertTrue(LabelSnapshotMerge.usablePhotos(snapshot(OWN, photo("", "a")), OWN).isEmpty())
    }

    @Test
    fun `the saved labels come first and the labels added since stay while there is room`() {
        assertEquals(listOf("a", "b", "c"), LabelSnapshotMerge.restoredList(listOf("a", "b"), listOf("c")))
        assertEquals(listOf("a", "b"), LabelSnapshotMerge.restoredList(listOf("a", "b"), listOf("B", "A")))
        assertEquals(listOf("a"), LabelSnapshotMerge.restoredList(listOf("a"), emptyList()))
        assertEquals(listOf("kept"), LabelSnapshotMerge.restoredList(emptyList(), listOf("kept")))
    }

    @Test
    fun `a restored list never goes past the limit and the saved labels win the room`() {
        val restored = LabelSnapshotMerge.restoredList(listOf("s1", "s2", "s3"), listOf("n1", "n2", "n3"))

        assertEquals(listOf("s1", "s2", "s3", "n1", "n2"), restored)
    }

    @Test
    fun `lists are cleaned like a label write`() {
        assertEquals(
            listOf("summer trip", "a"),
            LabelSnapshotMerge.restoredList(listOf(" summer   trip ", "Summer Trip", "", "a"), emptyList()),
        )
    }

    @Test
    fun `the drawer counts each label's usable photos, most used first, and lists the empty ones last`() {
        val saved = snapshot(
            OTHER_DEVICE,
            photo(LINK, "Beach", "sun"),
            photo(LINK_2, "BEACH"),
            photo(URI, "device only"),
            names = listOf("Empty", "beach"),
        )

        val counts = LabelSnapshotMerge.counts(saved, OWN)

        assertEquals(listOf(LabelCount("Beach", 2), LabelCount("sun", 1), LabelCount("Empty", 0)), counts)
    }

    @Test
    fun `the copy this install saved counts its device photos too`() {
        val saved = snapshot(OWN, photo(URI, "trip"), photo(LINK, "trip"))

        assertEquals(listOf(LabelCount("trip", 2)), LabelSnapshotMerge.counts(saved, OWN))
    }

    @Test
    fun `a copy with nothing in it counts nothing`() {
        assertTrue(LabelSnapshotMerge.counts(snapshot(OWN), OWN).isEmpty())
    }

    private companion object {
        const val OWN = "0b5f3c2e-1111-4a4a-9c9c-123456789abc"
        const val OTHER_DEVICE = "7d1e9a40-2222-4b4b-8d8d-abcdefabcdef"
        const val LINK = "link-1"
        const val LINK_2 = "link-2"
        const val URI = "content://media/external/images/media/1"
    }
}
