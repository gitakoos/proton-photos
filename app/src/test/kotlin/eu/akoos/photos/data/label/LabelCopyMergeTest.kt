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

/**
 * The labels' merge rules, the same cases the desktop client's label store is tested with, so the phone and the
 * computer put each other's copies together the same way.
 */
class LabelCopyMergeTest {

    private val pc = "11111111-1111-4111-8111-111111111111"
    private val phone = "22222222-2222-4222-8222-222222222222"

    private fun pcCopy(
        createdAt: Long,
        photos: List<LabelSnapshotPhoto> = emptyList(),
        names: List<String> = emptyList(),
        nameTimes: Map<String, Long> = emptyMap(),
        deleted: List<LabelSnapshotDeleted> = emptyList(),
    ) = LabelSnapshot(
        deviceId = pc, deviceLabel = "Desk", createdAtMillis = createdAt,
        photos = photos, names = names, nameTimes = nameTimes, deleted = deleted,
    )

    private fun LabelSyncState.labelsOf(key: String) = photos[key]?.labels.orEmpty()

    private fun roundTrip(copy: LabelSnapshot): LabelSnapshot = LabelSnapshotCodec.decode(LabelSnapshotCodec.encode(copy))!!

    @Test
    fun `adds after what a photo has, once each, up to five, and counts the full photos`() {
        val s = LabelSyncState()
        LabelCopyMerge.setLabels(s, "k0", listOf("a", "b", "c", "d"), 1)
        val result = LabelCopyMerge.addLabels(s, listOf("k0", "k1"), listOf("B", "e", "f"), 2)
        assertEquals(listOf("a", "b", "c", "d", "e"), s.labelsOf("k0"))
        assertEquals(listOf("B", "e", "f"), s.labelsOf("k1"))
        assertEquals(2 to 1, result)
    }

    @Test
    fun `takes labels off case and accents aside`() {
        val s = LabelSyncState()
        LabelCopyMerge.setLabels(s, "k0", listOf("Nyár", "Strand"), 1)
        assertEquals(1, LabelCopyMerge.removeLabels(s, listOf("k0", "k9"), listOf("nyar"), 2))
        assertEquals(listOf("Strand"), s.labelsOf("k0"))
    }

    @Test
    fun `a label made by name stays listed and goes out only while no photo carries it`() {
        val s = LabelSyncState()
        assertEquals("Later", LabelCopyMerge.createLabel(s, " Later ", 1))
        LabelCopyMerge.addLabels(s, listOf("k0"), listOf("later"), 2)
        assertEquals(emptyList<String>(), LabelCopyMerge.forDrive(s, pc, "Desk", 3).names)
        LabelCopyMerge.removeLabels(s, listOf("k0"), listOf("Later"), 4)
        val copy = LabelCopyMerge.forDrive(s, pc, "Desk", 5)
        assertEquals(listOf("Later"), copy.names)
        assertEquals(mapOf("Later" to 1L), copy.nameTimes)
    }

    @Test
    fun `renames on every photo, keeping a photo that had both names to one`() {
        val s = LabelSyncState()
        LabelCopyMerge.setLabels(s, "k0", listOf("Old", "New"), 1)
        LabelCopyMerge.setLabels(s, "k1", listOf("Old"), 1)
        assertEquals("New", LabelCopyMerge.renameLabel(s, "Old", "New", 5))
        assertEquals(listOf("New"), s.labelsOf("k0"))
        assertEquals(listOf("New"), s.labelsOf("k1"))
        // Recorded a millisecond before the photos changed, so another device takes the renamed photos too.
        assertEquals(LabelTimedName("Old", 4), s.deleted["old"])
    }

    @Test
    fun `deletes from every photo and remembers when`() {
        val s = LabelSyncState()
        LabelCopyMerge.setLabels(s, "k0", listOf("Beach", "Sun"), 1)
        assertTrue(LabelCopyMerge.deleteLabel(s, "beach", 4))
        assertEquals(listOf("Sun"), s.labelsOf("k0"))
        assertEquals(4L, s.deleted["beach"]?.at)
        assertFalse(LabelCopyMerge.deleteLabel(s, "nothing", 5))
    }

    @Test
    fun `a later change there wins, and a later edit here stays`() {
        val s = LabelSyncState()
        LabelCopyMerge.setLabels(s, "k0", listOf("Here"), 10)
        LabelCopyMerge.setLabels(s, "k1", listOf("Here"), 30)
        LabelCopyMerge.takeIn(
            s,
            pcCopy(
                40,
                photos = listOf(
                    LabelSnapshotPhoto("k0", listOf("There"), 20),
                    LabelSnapshotPhoto("k1", listOf("There"), 20),
                    LabelSnapshotPhoto("k2", listOf("New"), 20),
                ),
            ),
            phone,
        )
        assertEquals(listOf("There"), s.labelsOf("k0"))
        assertEquals(listOf("Here"), s.labelsOf("k1"))
        assertEquals(listOf("New"), s.labelsOf("k2"))
    }

    @Test
    fun `a copy with no change times only fills the photos with no labels here`() {
        val s = LabelSyncState()
        LabelCopyMerge.setLabels(s, "k0", listOf("Here"), 10)
        val old = roundTrip(
            LabelSnapshot(deviceId = pc, deviceLabel = "Old", createdAtMillis = 99, photos = emptyList()),
        ).copy(photos = listOf(LabelSnapshotPhoto("k0", emptyList()), LabelSnapshotPhoto("k1", listOf("Old phone"))))
        LabelCopyMerge.takeIn(s, old, phone)
        assertEquals(listOf("Here"), s.labelsOf("k0"))
        assertEquals(listOf("Old phone"), s.labelsOf("k1"))
    }

    @Test
    fun `a deleted label comes off the photos labelled before it and not one labelled after`() {
        val s = LabelSyncState()
        LabelCopyMerge.setLabels(s, "k0", listOf("Beach"), 10)
        LabelCopyMerge.setLabels(s, "k1", listOf("Beach"), 50)
        LabelCopyMerge.takeIn(s, pcCopy(60, deleted = listOf(LabelSnapshotDeleted("beach", 40))), phone)
        assertEquals(emptyList<String>(), s.labelsOf("k0"))
        assertEquals(listOf("Beach"), s.labelsOf("k1"))
    }

    @Test
    fun `a label deleted here after the other device saved it does not come back`() {
        val s = LabelSyncState()
        LabelCopyMerge.setLabels(s, "k0", listOf("Beach"), 10)
        LabelCopyMerge.deleteLabel(s, "Beach", 30)
        LabelCopyMerge.takeIn(
            s,
            pcCopy(
                60,
                photos = listOf(LabelSnapshotPhoto("k0", listOf("Beach", "Sun"), 20)),
                names = listOf("Beach"),
                nameTimes = mapOf("Beach" to 20L),
            ),
            phone,
        )
        assertEquals(emptyList<String>(), s.labelsOf("k0"))
        assertTrue(s.names.isEmpty())
    }

    @Test
    fun `a copy is taken in once, this install's own never, and a device key never`() {
        val s = LabelSyncState()
        val copy = pcCopy(
            60,
            photos = listOf(
                LabelSnapshotPhoto("k0", listOf("A"), 20),
                LabelSnapshotPhoto("content://media/1", listOf("Device"), 20),
            ),
        )
        assertTrue(LabelCopyMerge.takeIn(s, copy, phone))
        assertNull(s.photos["content://media/1"])
        LabelCopyMerge.setLabels(s, "k0", emptyList(), 5)
        assertFalse(LabelCopyMerge.takeIn(s, copy, phone))
        assertFalse(LabelCopyMerge.takeIn(s, copy.copy(createdAtMillis = 70), pc))
    }

    @Test
    fun `a deletion with no time counts as made when its copy was`() {
        val s = LabelSyncState()
        LabelCopyMerge.setLabels(s, "k0", listOf("Beach"), 10)
        val decoded = LabelSnapshotCodec.decode(
            """{"v":1,"d":"$pc","n":"Desk","c":50,"p":[],"x":[{"n":"Beach"}]}""".toByteArray(),
        )!!
        LabelCopyMerge.takeIn(s, decoded, phone)
        assertEquals(LabelTimedName("Beach", 50), s.deleted["beach"])
        assertEquals(emptyList<String>(), s.labelsOf("k0"))
    }

    @Test
    fun `an older build's copy without the new fields still reads`() {
        val decoded = LabelSnapshotCodec.decode(
            """{"v":1,"d":"$pc","n":"Desk","c":5,"p":[{"k":"k0","l":["A"]}],"m":["B"]}""".toByteArray(),
        )!!
        assertEquals(0L, decoded.photos.single().changedAtMillis)
        assertTrue(decoded.nameTimes.isEmpty())
        assertTrue(decoded.deleted.isEmpty())
    }

    @Test
    fun `a rename on the phone reaches the other device and the old name does not come back`() {
        val onPhone = LabelSyncState()
        val onPc = LabelSyncState()
        LabelCopyMerge.setLabels(onPhone, "k0", listOf("Old"), 10)
        LabelCopyMerge.setLabels(onPc, "k0", listOf("Old"), 10)
        LabelCopyMerge.renameLabel(onPhone, "Old", "New", 50)
        LabelCopyMerge.takeIn(onPc, roundTrip(LabelCopyMerge.forDrive(onPhone, phone, "Phone", 60)), pc)
        assertEquals(listOf("New"), onPc.labelsOf("k0"))
        LabelCopyMerge.takeIn(onPhone, roundTrip(LabelCopyMerge.forDrive(onPc, pc, "Desk", 70)), phone)
        assertEquals(listOf("New"), onPhone.labelsOf("k0"))
    }

    @Test
    fun `a rename whose deletion and photo carry the same time, as the computer writes it, still arrives`() {
        val s = LabelSyncState()
        LabelCopyMerge.setLabels(s, "k0", listOf("Old"), 10)
        LabelCopyMerge.takeIn(
            s,
            pcCopy(
                60,
                photos = listOf(LabelSnapshotPhoto("k0", listOf("New"), 50)),
                deleted = listOf(LabelSnapshotDeleted("Old", 50)),
            ),
            phone,
        )
        assertEquals(listOf("New"), s.labelsOf("k0"))
    }

    @Test
    fun `two devices through Drive end with the same labels whichever saved last`() {
        val onPc = LabelSyncState()
        val onPhone = LabelSyncState()
        LabelCopyMerge.setLabels(onPc, "k0", listOf("Family"), 10)
        LabelCopyMerge.setLabels(onPc, "k2", listOf("Old"), 5)
        LabelCopyMerge.setLabels(onPhone, "k2", listOf("Old"), 5)
        LabelCopyMerge.setLabels(onPhone, "k1", listOf("Trip"), 12)
        LabelCopyMerge.deleteLabel(onPhone, "Old", 13)
        val toPhone = roundTrip(LabelCopyMerge.forDrive(onPc, pc, "Desk", 20))
        val toPc = roundTrip(LabelCopyMerge.forDrive(onPhone, phone, "Pixel", 21))
        LabelCopyMerge.takeIn(onPhone, toPhone, phone)
        LabelCopyMerge.takeIn(onPc, toPc, pc)
        assertEquals(mapOf("k0" to listOf("Family"), "k1" to listOf("Trip"), "k2" to emptyList()), LabelCopyMerge.view(onPc))
        assertEquals(LabelCopyMerge.view(onPc), LabelCopyMerge.view(onPhone))
    }
}
