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

package eu.akoos.photos.data.hidden

import androidx.datastore.preferences.core.mutablePreferencesOf
import eu.akoos.photos.data.preferences.SettingsKeys
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The hidden lists' format and merge rule, the same cases the desktop client's hidden store is tested with, and the
 * phone's own reading of its hidden sets with their times.
 */
class HiddenCopyMergeTest {

    private val phone = "11111111-2222-3333-4444-555555555555"
    private val pc = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"

    private fun state(
        hiddenPhotos: Map<String, Long> = emptyMap(),
        shownPhotos: Map<String, Long> = emptyMap(),
        hiddenAlbums: Map<String, Long> = emptyMap(),
        shownAlbums: Map<String, Long> = emptyMap(),
    ) = HiddenSyncState(
        HiddenKindState(HashMap(hiddenPhotos), HashMap(shownPhotos)),
        HiddenKindState(HashMap(hiddenAlbums), HashMap(shownAlbums)),
    )

    private fun through(state: HiddenSyncState, id: String): HiddenSnapshot =
        HiddenSnapshotCodec.decode(HiddenSnapshotCodec.encode(HiddenCopyMerge.forDrive(state, id, id, 1)))!!

    @Test
    fun `a copy is JSON v1 with a link id, hidden or not, and a time per entry`() {
        val s = state(hiddenPhotos = mapOf("p1" to 10L), shownPhotos = mapOf("p2" to 12L), hiddenAlbums = mapOf("a1" to 13L))
        val json = Json.parseToJsonElement(String(HiddenSnapshotCodec.encode(HiddenCopyMerge.forDrive(s, pc, "PC", 99))))
        val expected = Json.parseToJsonElement(
            """{"v":1,"d":"$pc","n":"PC","c":99,"p":[{"k":"p1","h":true,"t":10},{"k":"p2","h":false,"t":12}],""" +
                """"a":[{"k":"a1","h":true,"t":13}]}""",
        )
        assertEquals(expected, json as JsonObject)
    }

    @Test
    fun `a copy is read defensively, without device keys, stateless entries or a missing time`() {
        val read = HiddenSnapshotCodec.decode(
            """{"v":1,"d":"$phone","n":"Phone","c":5,"p":[{"k":"p1","h":true},{"k":"content://x","h":true,"t":1},{"k":"p2"}],"a":[]}"""
                .toByteArray(),
        )!!
        assertEquals(listOf(HiddenSnapshotEntry("p1", true, 0L)), read.photos)
        assertNull(HiddenSnapshotCodec.decode("""{"v":2,"p":[],"a":[]}""".toByteArray()))
        assertNull(HiddenSnapshotCodec.decode("""{"v":1,"d":"$phone","n":"x","c":1,"p":[]}""".toByteArray()))
    }

    @Test
    fun `the later change to each photo and album wins, and a later local one stays`() {
        val onPc = state(hiddenPhotos = mapOf("mine" to 100L, "both" to 100L))
        val changes = HiddenCopyMerge.takeIn(
            onPc,
            HiddenSnapshot(
                deviceId = phone, deviceLabel = "Phone", createdAtMillis = 1,
                photos = listOf(
                    HiddenSnapshotEntry("theirs", true, 50),
                    HiddenSnapshotEntry("both", false, 200),
                    HiddenSnapshotEntry("mine", false, 50),
                ),
                albums = listOf(HiddenSnapshotEntry("album", true, 60)),
            ),
            pc,
        )
        assertEquals(mapOf("theirs" to 50L), changes.photosHidden)
        assertEquals(mapOf("both" to 200L), changes.photosShown)
        assertEquals(mapOf("album" to 60L), changes.albumsHidden)
        assertEquals(setOf("mine", "theirs"), onPc.photos.hidden.keys)
        val again = HiddenCopyMerge.takeIn(
            onPc,
            HiddenSnapshot(
                deviceId = phone, deviceLabel = "Phone", createdAtMillis = 1,
                photos = listOf(HiddenSnapshotEntry("theirs", true, 50)), albums = emptyList(),
            ),
            pc,
        )
        assertTrue(again.isEmpty)
    }

    @Test
    fun `this install's own copy is never taken in`() {
        val s = state()
        val own = HiddenSnapshot(
            deviceId = phone, deviceLabel = "Phone", createdAtMillis = 1,
            photos = listOf(HiddenSnapshotEntry("p", true, 5)), albums = emptyList(),
        )
        assertTrue(HiddenCopyMerge.takeIn(s, own, phone).isEmpty)
    }

    @Test
    fun `two devices end with the same list once each took in the other's copy through Drive`() {
        val onPhone = state(hiddenPhotos = mapOf("a" to 10L, "b" to 10L), shownAlbums = mapOf("x" to 40L))
        val onPc = state(shownPhotos = mapOf("b" to 20L), hiddenPhotos = mapOf("c" to 30L), hiddenAlbums = mapOf("x" to 31L))
        val fromPhone = through(onPhone, phone)
        val fromPc = through(onPc, pc)
        HiddenCopyMerge.takeIn(onPc, fromPhone, pc)
        HiddenCopyMerge.takeIn(onPhone, fromPc, phone)
        assertEquals(onPc.photos.hidden.keys, onPhone.photos.hidden.keys)
        assertEquals(setOf("a", "c"), onPc.photos.hidden.keys)
        assertEquals(onPc.albums.hidden.keys, onPhone.albums.hidden.keys)
        assertTrue(onPc.albums.hidden.isEmpty())
    }

    @Test
    fun `the phone's hidden list counts cloud hides, vaulted copies and waiting hides, with their times`() {
        val prefs = mutablePreferencesOf(
            SettingsKeys.HIDDEN_CLOUD_PHOTO_IDS to setOf("cloud", "old"),
            SettingsKeys.HIDDEN_URI_CLOUD_ID_MAP to setOf("file:///vault/1|vaulted"),
            SettingsKeys.HIDDEN_REMOTE_HIDE_QUEUE to setOf("waiting|70"),
            SettingsKeys.HIDDEN_PHOTO_HIDE_TIMES to setOf("cloud|10", "vaulted|20"),
            SettingsKeys.HIDDEN_PHOTO_SHOW_TIMES to setOf("back|30"),
            SettingsKeys.HIDDEN_ALBUM_IDS to setOf("album"),
            SettingsKeys.HIDDEN_ALBUM_HIDE_TIMES to setOf("album|40"),
            SettingsKeys.HIDDEN_ALBUM_SHOW_TIMES to setOf("other|50"),
        )
        val s = HiddenSyncStore.stateOf(prefs)
        assertEquals(mapOf("cloud" to 10L, "old" to 0L, "vaulted" to 20L, "waiting" to 70L), s.photos.hidden.toMap())
        assertEquals(mapOf("back" to 30L), s.photos.shown.toMap())
        assertEquals(mapOf("album" to 40L), s.albums.hidden.toMap())
        assertEquals(mapOf("other" to 50L), s.albums.shown.toMap())
    }

    @Test
    fun `a hide and an unhide of the same photo keep only the later time`() {
        val prefs = mutablePreferencesOf()
        HiddenSyncTimes.photosHidden(prefs, listOf("p"), 10)
        HiddenSyncTimes.photosShown(prefs, listOf("p"), 20)
        assertTrue(HiddenSyncTimes.parse(prefs[SettingsKeys.HIDDEN_PHOTO_HIDE_TIMES]).isEmpty())
        assertEquals(mapOf("p" to 20L), HiddenSyncTimes.parse(prefs[SettingsKeys.HIDDEN_PHOTO_SHOW_TIMES]))
    }
}
