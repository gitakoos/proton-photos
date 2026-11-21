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

package eu.akoos.photos.data.settingssync

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The settings copy's format and merge rule, the same cases the desktop client's settings merge is tested with:
 * the newer value wins, the older loses, a tie goes to the larger device id, a reset travels with its time, set
 * members merge, a scoped setting of the other platform is ignored, and unknown ids, bad values and a newer format
 * are passed over.
 */
class SettingsCopyMergeTest {

    private val phone = "11111111-2222-3333-4444-555555555555"
    private val phone2 = "00000000-2222-3333-4444-555555555555"
    private val pc = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"

    private fun text(value: String) = JsonPrimitive(value)

    private fun scalar(id: String, at: Long, value: String?) =
        SettingsScalarEntry(id, at, value?.let(::text))

    private fun copy(
        device: String,
        platform: String = "android",
        scalars: List<SettingsScalarEntry> = emptyList(),
        members: List<SettingsMemberEntry> = emptyList(),
    ) = SettingsSnapshot(deviceId = device, platform = platform, deviceLabel = "x", createdAtMillis = 1, scalars = scalars, members = members)

    private fun state(vararg scalars: Pair<String, SettingsScalarState>) =
        SettingsSyncState(scalars.toMap(HashMap()))

    private fun takeIn(state: SettingsSyncState, copy: SettingsSnapshot, own: String = phone, readsAs: String = own) =
        SettingsCopyMerge.takeIn(state, copy, readsAs, own)

    @Test
    fun `a copy is JSON v1 with the platform, scalars with a time and set members with a presence`() {
        val s = SettingsSyncState()
        s.scalars["appearance.theme"] = SettingsScalarState(10L, text("dark"))
        s.scalars["albums.hidePhotosInAlbums"] = SettingsScalarState(12L, null)
        s.members["timeline.excludedAlbums"] = hashMapOf("a1" to SettingsMemberState(13L, true), "a0" to SettingsMemberState(14L, false))
        val json = Json.parseToJsonElement(String(SettingsSnapshotCodec.encode(SettingsCopyMerge.forDrive(s, phone, "Phone", 99))))
        val expected = Json.parseToJsonElement(
            """{"v":1,"d":"$phone","p":"android","n":"Phone","c":99,""" +
                """"s":[{"k":"albums.hidePhotosInAlbums","t":12,"x":null},{"k":"appearance.theme","t":10,"x":"dark"}],""" +
                """"l":[{"k":"timeline.excludedAlbums","e":"a0","h":false,"t":14},{"k":"timeline.excludedAlbums","e":"a1","h":true,"t":13}]}""",
        )
        assertEquals(expected, json as JsonObject)
    }

    @Test
    fun `a copy is read back as written, a reset included`() {
        val s = SettingsSyncState()
        s.scalars["appearance.palette"] = SettingsScalarState(7L, null)
        val read = SettingsSnapshotCodec.decode(SettingsSnapshotCodec.encode(SettingsCopyMerge.forDrive(s, pc, "PC", 5, "desktop")))!!
        assertEquals("desktop", read.platform)
        assertEquals(listOf(SettingsScalarEntry("appearance.palette", 7L, null)), read.scalars)
        assertNull(read.scalars.single().value)
    }

    @Test
    fun `a copy is read defensively, an entry without a key or presence is dropped and a negative time reads 0`() {
        val read = SettingsSnapshotCodec.decode(
            """{"v":1,"d":"$pc","p":"desktop","n":"PC","c":5,"s":[{"k":"","t":1,"x":"dark"},{"k":"appearance.theme","t":-4,"x":"dark"}],"l":[{"k":"timeline.excludedAlbums","e":"a","t":1},{"k":"timeline.excludedAlbums","e":"","h":true,"t":1}]}"""
                .toByteArray(),
        )!!
        assertEquals(listOf(SettingsScalarEntry("appearance.theme", 0L, text("dark"))), read.scalars)
        assertTrue(read.members.isEmpty())
    }

    @Test
    fun `a newer format version is passed over`() {
        assertNull(SettingsSnapshotCodec.decode("""{"v":2,"d":"$pc","p":"desktop","n":"PC","c":5,"s":[],"l":[]}""".toByteArray()))
        assertEquals(2, SettingsSnapshotCodec.versionOf("""{"v":2,"d":"x"}""".toByteArray()))
        assertNull(SettingsSnapshotCodec.versionOf("not json".toByteArray()))
    }

    @Test
    fun `the newer remote value wins and the older one loses`() {
        val s = state(
            "appearance.theme" to SettingsScalarState(100L, text("dark")),
            "appearance.palette" to SettingsScalarState(100L, text("rose")),
        )
        val changes = takeIn(s, copy(pc, "desktop", listOf(scalar("appearance.theme", 200L, "light"), scalar("appearance.palette", 50L, "sea"))))
        assertEquals(setOf("appearance.theme"), changes.scalars.keys)
        assertEquals(SettingsScalarState(200L, text("light")), s.scalars["appearance.theme"])
        assertEquals(SettingsScalarState(100L, text("rose")), s.scalars["appearance.palette"])
    }

    @Test
    fun `at an equal time the larger device id wins`() {
        val larger = state("appearance.theme" to SettingsScalarState(100L, text("dark")))
        takeIn(larger, copy(pc, "desktop", listOf(scalar("appearance.theme", 100L, "light"))), own = phone)
        assertEquals(text("light"), larger.scalars["appearance.theme"]!!.value)

        val smaller = state("appearance.theme" to SettingsScalarState(100L, text("dark")))
        takeIn(smaller, copy(phone2, "desktop", listOf(scalar("appearance.theme", 100L, "light"))), own = phone)
        assertEquals(text("dark"), smaller.scalars["appearance.theme"]!!.value)
    }

    @Test
    fun `a reset travels with its time and a later local value stays over it`() {
        val s = state(
            "appearance.theme" to SettingsScalarState(100L, text("dark")),
            "appearance.palette" to SettingsScalarState(300L, text("rose")),
        )
        val changes = takeIn(s, copy(pc, "desktop", listOf(scalar("appearance.theme", 200L, null), scalar("appearance.palette", 200L, null))))
        assertEquals(SettingsScalarState(200L, null), changes.scalars["appearance.theme"])
        assertNull(s.scalars["appearance.theme"]!!.value)
        assertEquals(text("rose"), s.scalars["appearance.palette"]!!.value)
    }

    @Test
    fun `a setting this device never changed reads as -1, so a time of 0 fills the gap and no more`() {
        val empty = SettingsSyncState()
        takeIn(empty, copy(pc, "desktop", listOf(scalar("appearance.theme", 0L, "light"))))
        assertEquals(SettingsScalarState(0L, text("light")), empty.scalars["appearance.theme"])

        val held = state("appearance.theme" to SettingsScalarState(0L, text("dark")))
        takeIn(held, copy(phone2, "desktop", listOf(scalar("appearance.theme", 0L, "light"))), own = phone)
        assertEquals("a tie at 0 follows the device id rule too", text("dark"), held.scalars["appearance.theme"]!!.value)
    }

    @Test
    fun `set members of this install's own earlier copy merge, and a later removal beats an earlier add`() {
        val s = SettingsSyncState()
        s.members["timeline.excludedAlbums"] = hashMapOf("mine" to SettingsMemberState(100L, true), "gone" to SettingsMemberState(100L, true))
        val members = listOf(
            SettingsMemberEntry("timeline.excludedAlbums", "theirs", true, 150L),
            SettingsMemberEntry("timeline.excludedAlbums", "gone", false, 200L),
            SettingsMemberEntry("timeline.excludedAlbums", "mine", false, 50L),
        )
        val changes = takeIn(s, copy(phone, "android", members = members), own = phone, readsAs = "")
        val held = s.members.getValue("timeline.excludedAlbums")
        assertEquals(SettingsMemberState(150L, true), held["theirs"])
        assertEquals(SettingsMemberState(100L, true), held["mine"])
        assertEquals(SettingsMemberState(200L, false), held["gone"])
        assertEquals(setOf("theirs", "gone"), changes.members.getValue("timeline.excludedAlbums").keys)
    }

    @Test
    fun `a platform setting from a copy of the other platform is ignored, a shared one is taken`() {
        val s = SettingsSyncState()
        val changes = takeIn(
            s,
            copy(
                pc, "desktop",
                scalars = listOf(scalar("appearance.theme", 10L, "light"), scalar("appearance.language", 10L, "de"), scalar("upload.compressCodec", 10L, "av1")),
                members = listOf(SettingsMemberEntry("timeline.excludedAlbums", "a", true, 10L)),
            ),
        )
        assertEquals(setOf("appearance.theme"), changes.scalars.keys)
        assertTrue(changes.members.values.all { it.isEmpty() })
    }

    @Test
    fun `a platform setting from another android device is ignored, a shared one is taken`() {
        val s = SettingsSyncState()
        val changes = takeIn(
            s,
            copy(
                phone2, "android",
                listOf(scalar("appearance.language", 10L, "de"), scalar("upload.compressCodec", 10L, "av1"), scalar("appearance.theme", 10L, "light")),
            ),
        )
        assertEquals(setOf("appearance.theme"), changes.scalars.keys)
        assertNull(s.scalars["appearance.language"])
    }

    @Test
    fun `a platform setting from this install's own earlier copy is taken`() {
        val s = SettingsSyncState()
        val own = copy(phone, "android", listOf(scalar("appearance.language", 10L, "de"), scalar("upload.compressCodec", 10L, "av1")))
        val changes = takeIn(s, own, own = phone, readsAs = "")
        assertEquals(setOf("appearance.language", "upload.compressCodec"), changes.scalars.keys)
    }

    @Test
    fun `a platform setting from a desktop copy with this device's id is still ignored`() {
        val s = SettingsSyncState()
        val changes = takeIn(s, copy(phone, "desktop", listOf(scalar("appearance.language", 10L, "de"))), own = phone, readsAs = "")
        assertTrue(changes.isEmpty)
    }

    @Test
    fun `an unknown id and a value that fails its check are ignored`() {
        val s = SettingsSyncState()
        val changes = takeIn(
            s,
            copy(
                pc, "desktop",
                scalars = listOf(
                    scalar("appearance.nothing", 10L, "x"),
                    scalar("appearance.theme", 10L, "purple"),
                    SettingsScalarEntry("appearance.amoled", 10L, text("true")),
                    SettingsScalarEntry("appearance.palette", 10L, JsonPrimitive(3)),
                ),
                members = listOf(SettingsMemberEntry("nothing.set", "a", true, 10L)),
            ),
        )
        assertTrue(changes.isEmpty)
        assertNull(s.scalars["appearance.theme"])
        assertNull(s.scalars["appearance.amoled"])
        assertTrue(s.members.isEmpty())
    }

    @Test
    fun `this install's own copy is passed over, except while it is read as an earlier install`() {
        val own = copy(phone, "android", listOf(scalar("appearance.theme", 10L, "light")))
        val skipped = SettingsSyncState()
        assertTrue(takeIn(skipped, own, own = phone, readsAs = phone).isEmpty)
        assertTrue(skipped.scalars.isEmpty())

        val restored = SettingsSyncState()
        assertEquals(setOf("appearance.theme"), takeIn(restored, own, own = phone, readsAs = "").scalars.keys)
    }

    @Test
    fun `taking the same copy twice changes nothing`() {
        val s = SettingsSyncState()
        val c = copy(pc, "desktop", listOf(scalar("appearance.theme", 10L, "light")))
        assertTrue(!takeIn(s, c).isEmpty)
        assertTrue(takeIn(s, c).isEmpty)
    }

    @Test
    fun `the compression tier words map to the stored ordinals and back`() {
        val tier = SettingsSyncRegistry.scalar("upload.compressTier")!!
        assertTrue(tier.accepts(text("high")) && tier.accepts(text("balanced")) && tier.accepts(text("small")))
        assertTrue(!tier.accepts(text("tiny")) && !tier.accepts(JsonPrimitive(1)))
    }

    @Test
    fun `an old android album id is taken from this install's own earlier copy under the shared id`() {
        val old = copy(
            phone,
            scalars = listOf(
                SettingsScalarEntry("android.albums.sortMode", 50L, JsonPrimitive(0)),
                SettingsScalarEntry("android.albums.customOrder", 50L, JsonPrimitive("b|a")),
            ),
        )
        val state = SettingsSyncState()
        val changes = takeIn(state, old, own = phone, readsAs = "earlier-install")
        assertEquals(SettingsScalarState(50L, JsonPrimitive("custom")), changes.scalars["albums.sortMode"])
        assertEquals(
            SettingsScalarState(50L, kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive("b"), JsonPrimitive("a")))),
            changes.scalars["albums.order"],
        )
        // Another phone's old copy carries a platform value, never taken.
        val other = copy(phone2, scalars = listOf(SettingsScalarEntry("android.albums.sortMode", 90L, JsonPrimitive(1))))
        assertTrue(takeIn(SettingsSyncState(), other).scalars.isEmpty())
    }

    @Test
    fun `the album list sort and order come from any device, the desktop included`() {
        val desk = copy(
            pc, platform = "desktop",
            scalars = listOf(
                SettingsScalarEntry("albums.sortMode", 70L, JsonPrimitive("newest")),
                SettingsScalarEntry("albums.order", 70L, kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive("x")))),
            ),
        )
        val changes = takeIn(state("albums.sortMode" to SettingsScalarState(60L, JsonPrimitive("custom"))), desk)
        assertEquals(JsonPrimitive("newest"), changes.scalars.getValue("albums.sortMode").value)
        assertEquals(70L, changes.scalars.getValue("albums.order").at)
    }
}
