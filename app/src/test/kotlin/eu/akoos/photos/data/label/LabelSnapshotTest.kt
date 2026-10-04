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

    private fun snapshot(deviceId: String, vararg photos: LabelSnapshotPhoto) =
        LabelSnapshot(deviceId = deviceId, deviceLabel = "Phone", createdAtMillis = 1L, photos = photos.toList())

    private fun photo(key: String, at: Long, vararg labels: String) = LabelSnapshotPhoto(key, at, labels.toList())

    @Test
    fun `a snapshot survives the round trip through its plaintext form`() {
        val original = snapshot(OWN, photo(LINK, 5L, "Café", "beach"), photo(LINK_2, 6L))

        val decoded = LabelSnapshotCodec.decode(LabelSnapshotCodec.encode(original))

        assertEquals(original, decoded)
    }

    @Test
    fun `the labels without a photo survive the round trip, and an older file without them reads as none`() {
        val original = snapshot(OWN, photo(LINK, 5L, "a")).copy(names = listOf("Empty", "Café"))

        assertEquals(original, LabelSnapshotCodec.decode(LabelSnapshotCodec.encode(original)))
        val old = """{"v":1,"d":"$OWN","n":"x","c":1,"p":[]}""".toByteArray()
        assertEquals(emptyList<String>(), LabelSnapshotCodec.decode(old)?.names)
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
    fun `a photo changed after the held list is taken, an older or equal one is not`() {
        val remote = snapshot(
            OTHER_DEVICE,
            photo("newer", 20L, "a"),
            photo("equal", 10L, "b"),
            photo("older", 5L, "c"),
        )

        val plan = LabelSnapshotMerge.plan(remote, OWN, local = mapOf("newer" to 10L, "equal" to 10L, "older" to 10L), now = 100L)

        assertEquals(listOf("newer"), plan.map { it.key })
    }

    @Test
    fun `a photo nothing is held for is taken, an empty list included`() {
        val remote = snapshot(OTHER_DEVICE, photo("new", 3L, "a"), photo("gone", 4L))

        val plan = LabelSnapshotMerge.plan(remote, OWN, local = emptyMap(), now = 100L)

        assertEquals(listOf("new", "gone"), plan.map { it.key })
        assertTrue(plan.last().labels.isEmpty())
    }

    @Test
    fun `a removal saved later outranks an older list held here`() {
        val remote = snapshot(OTHER_DEVICE, photo("p", 50L))

        val plan = LabelSnapshotMerge.plan(remote, OWN, local = mapOf("p" to 40L), now = 100L)

        assertEquals(1, plan.size)
        assertTrue(plan.single().labels.isEmpty())
    }

    @Test
    fun `a device key is taken only from the snapshot this install wrote`() {
        val photos = arrayOf(photo(URI, 5L, "a"), photo(LINK, 5L, "b"))

        val fromOther = LabelSnapshotMerge.plan(snapshot(OTHER_DEVICE, *photos), OWN, emptyMap(), now = 100L)
        val fromOwn = LabelSnapshotMerge.plan(snapshot(OWN, *photos), OWN, emptyMap(), now = 100L)

        assertEquals(listOf(LINK), fromOther.map { it.key })
        assertEquals(listOf(URI, LINK), fromOwn.map { it.key })
    }

    @Test
    fun `lists are cleaned and capped like a label write`() {
        val remote = snapshot(
            OTHER_DEVICE,
            photo("p", 5L, " summer   trip ", "Summer Trip", "", "a", "b", "c", "d", "e"),
        )

        val labels = LabelSnapshotMerge.plan(remote, OWN, emptyMap(), now = 100L).single().labels

        assertEquals(listOf("summer trip", "a", "b", "c", "d"), labels)
    }

    @Test
    fun `a time in the future counts as now`() {
        val remote = snapshot(OTHER_DEVICE, photo("p", 9_999_999L, "a"))

        val plan = LabelSnapshotMerge.plan(remote, OWN, emptyMap(), now = 100L)

        assertEquals(100L, plan.single().changedAt)
    }

    @Test
    fun `a photo without a key is skipped`() {
        val remote = snapshot(OTHER_DEVICE, photo("", 5L, "a"))

        assertTrue(LabelSnapshotMerge.plan(remote, OWN, emptyMap(), now = 100L).isEmpty())
    }

    @Test
    fun `only a link id is portable between devices`() {
        assertTrue(LabelSnapshotMerge.isPortableKey(LINK))
        assertFalse(LabelSnapshotMerge.isPortableKey(URI))
        assertFalse(LabelSnapshotMerge.isPortableKey("file:///sdcard/a.jpg"))
        assertFalse(LabelSnapshotMerge.isPortableKey(""))
    }

    private companion object {
        const val OWN = "0b5f3c2e-1111-4a4a-9c9c-123456789abc"
        const val OTHER_DEVICE = "7d1e9a40-2222-4b4b-8d8d-abcdefabcdef"
        const val LINK = "link-1"
        const val LINK_2 = "link-2"
        const val URI = "content://media/external/images/media/1"
    }
}
