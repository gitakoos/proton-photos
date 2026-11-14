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

import android.content.Context
import androidx.datastore.preferences.core.edit
import eu.akoos.photos.data.preferences.AccountScopedPreferences
import eu.akoos.photos.data.preferences.InMemoryPreferencesDataStore
import eu.akoos.photos.data.preferences.SettingsKeys
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import me.proton.core.domain.entity.UserId
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The change times of the synced settings: what the user changes is stamped, what another device brings is written
 * with its own time and is not stamped again, and the sign-out wipe, which removes values and stamps in one edit,
 * never leaves a reset behind for the other devices.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SettingsSyncTimesTest {

    private val context = mockk<Context>(relaxed = true)
    private lateinit var data: InMemoryPreferencesDataStore
    private lateinit var store: SettingsSyncStore

    @Before
    fun setUp() {
        data = InMemoryPreferencesDataStore()
        InMemoryPreferencesDataStore.install(context, data)
        store = SettingsSyncStore(context)
    }

    @After
    fun tearDown() = InMemoryPreferencesDataStore.uninstall()

    private fun stamps() = SettingsSyncTimes.parse(data.current[SettingsKeys.SETTINGS_SYNC_TIMES])

    @Test
    fun `values chosen before the first pass are seeded at 0 and a pass without any values writes no file`() = runBlocking {
        assertFalse(store.stampChanges(1_000L))
        assertTrue(store.load(1_000L).isEmpty)

        data.edit { it[SettingsKeys.THEME_MODE] = "dark" }
        // The first pass already ran, so this one is a change made now.
        assertTrue(store.stampChanges(2_000L))
        assertEquals(2_000L, stamps().getValue("appearance.theme").at)
    }

    @Test
    fun `the first pass seeds what is already set at 0, only filling gaps`() = runBlocking {
        data.edit {
            it[SettingsKeys.THEME_MODE] = "dark"
            it[SettingsKeys.HIDE_PHOTOS_IN_ALBUMS] = true
            it[SettingsKeys.TIMELINE_EXCLUDED_ALBUM_IDS] = setOf("a1", "a2")
        }
        assertTrue(store.stampChanges(5_000L))
        assertEquals(0L, stamps().getValue("appearance.theme").at)
        assertEquals(0L, stamps().getValue("albums.hidePhotosInAlbums").at)
        assertEquals(0L, stamps().getValue("timeline.excludedAlbums#a1").at)
        assertFalse("a second pass with nothing changed stamps nothing", store.stampChanges(6_000L))
    }

    @Test
    fun `a change after the first pass is stamped now, a member that came or went included`() = runBlocking {
        data.edit { it[SettingsKeys.TIMELINE_EXCLUDED_ALBUM_IDS] = setOf("a1") }
        store.stampChanges(1_000L)

        data.edit {
            it[SettingsKeys.TIMELINE_EXCLUDED_ALBUM_IDS] = setOf("a2")
            it[SettingsKeys.AMOLED_BLACK] = true
        }
        assertTrue(store.stampChanges(3_000L))
        val s = stamps()
        assertEquals(3_000L, s.getValue("appearance.amoled").at)
        assertEquals(3_000L, s.getValue("timeline.excludedAlbums#a2").at)
        assertEquals("a1 left the set", 3_000L, s.getValue("timeline.excludedAlbums#a1").at)
        val state = store.load(3_000L)
        assertEquals(SettingsMemberState(3_000L, false), state.members.getValue("timeline.excludedAlbums")["a1"])
        assertEquals(SettingsMemberState(3_000L, true), state.members.getValue("timeline.excludedAlbums")["a2"])
    }

    @Test
    fun `a setting the user clears is stamped as a reset`() = runBlocking {
        data.edit { it[SettingsKeys.THEME_PALETTE] = "rose" }
        store.stampChanges(1_000L)
        data.edit { it.remove(SettingsKeys.THEME_PALETTE) }
        assertTrue(store.stampChanges(2_000L))
        assertEquals(SettingsScalarState(2_000L, null), store.load(2_000L).scalars["appearance.palette"])
    }

    @Test
    fun `a value another device brought is written with its time and is not stamped again`() = runBlocking {
        data.edit { it[SettingsKeys.THEME_MODE] = "dark" }
        store.stampChanges(1_000L)

        val changes = SettingsCopyMerge.Changes(
            scalars = mapOf("appearance.theme" to SettingsScalarState(9_000L, JsonPrimitive("light"))),
            members = mapOf("timeline.excludedAlbums" to mapOf("a9" to SettingsMemberState(9_500L, true))),
        )
        assertEquals(2, store.apply(changes))
        assertEquals("light", data.current[SettingsKeys.THEME_MODE])
        assertEquals(setOf("a9"), data.current[SettingsKeys.TIMELINE_EXCLUDED_ALBUM_IDS])
        assertEquals(9_000L, stamps().getValue("appearance.theme").at)
        assertFalse("applying is not a change made here", store.stampChanges(20_000L))
        assertEquals(9_000L, stamps().getValue("appearance.theme").at)
        assertEquals(9_500L, stamps().getValue("timeline.excludedAlbums#a9").at)
    }

    @Test
    fun `a remote reset removes the value and keeps its time`() = runBlocking {
        data.edit { it[SettingsKeys.HIDE_PHOTOS_IN_ALBUMS] = true }
        store.stampChanges(1_000L)
        store.apply(SettingsCopyMerge.Changes(scalars = mapOf("albums.hidePhotosInAlbums" to SettingsScalarState(7_000L, null))))
        assertNull(data.current[SettingsKeys.HIDE_PHOTOS_IN_ALBUMS])
        assertFalse(store.stampChanges(8_000L))
        assertEquals(SettingsScalarState(7_000L, null), store.load(8_000L).scalars["albums.hidePhotosInAlbums"])
    }

    @Test
    fun `entries this setting does not carry stay when its members change`() = runBlocking {
        data.edit { it[SettingsKeys.ALBUMS_GRID_EXCLUDED] = setOf("album:a1", "folder:Camera") }
        store.stampChanges(1_000L)
        assertEquals(setOf("a1"), store.load(1_000L).members.getValue("albums.gridExcludedAlbums").keys)

        store.apply(SettingsCopyMerge.Changes(members = mapOf("albums.gridExcludedAlbums" to mapOf("a2" to SettingsMemberState(5L, true), "a1" to SettingsMemberState(6L, false)))))
        assertEquals(setOf("album:a2", "folder:Camera"), data.current[SettingsKeys.ALBUMS_GRID_EXCLUDED])
    }

    @Test
    fun `the sign-out wipe stamps nothing and sends no reset, whichever side of it a pass runs`() = runBlocking {
        data.edit {
            it[SettingsKeys.HIDE_PHOTOS_IN_ALBUMS] = true
            it[SettingsKeys.TIMELINE_EXCLUDED_ALBUM_IDS] = setOf("a1")
            it[SettingsKeys.ALBUMS_CUSTOM_ORDER] = "a1|a2"
            it[SettingsKeys.SHOW_SCROLL_DATE] = true
            it[SettingsKeys.THEME_MODE] = "dark"
        }
        store.stampChanges(1_000L)
        data.edit { it[SettingsKeys.HIDE_PHOTOS_IN_ALBUMS] = false }
        store.stampChanges(2_000L)
        assertTrue(stamps().isNotEmpty())

        AccountScopedPreferences.clear(context, UserId("acct"))

        assertNull(data.current[SettingsKeys.SETTINGS_SYNC_TIMES])
        // A pass right after the wipe has nothing to stamp as a reset: the removed values left no stamp behind.
        store.stampChanges(3_000L)
        val state = store.load(3_000L)
        assertTrue("a wiped setting is not reported as a reset", state.scalars.values.none { it.value == null })
        assertNull(state.scalars["albums.hidePhotosInAlbums"])
        assertNull(state.scalars["android.timeline.showScrollDate"])
        assertNull(state.scalars["android.albums.customOrder"])
        assertTrue(state.members["timeline.excludedAlbums"].isNullOrEmpty())
        val copy = SettingsCopyMerge.forDrive(state, "11111111-2222-3333-4444-555555555555", "Phone", 3_000L)
        assertTrue("no tombstone leaves the phone", copy.scalars.none { it.value == null } && copy.members.none { it.present == false })
        // What the wipe kept is seeded at 0 again, which only fills a gap on another device.
        assertEquals(0L, stamps().getValue("appearance.theme").at)
    }

    @Test
    fun `parse and format round-trip, and a malformed entry is skipped`() {
        val stamps = mapOf(
            "appearance.theme" to SettingsStamp(10L, "ab12"),
            "timeline.excludedAlbums#a|b=" to SettingsStamp(11L, "1"),
        )
        assertEquals(stamps, SettingsSyncTimes.parse(SettingsSyncTimes.format(stamps) + setOf("broken", "x|y|z", "|1|2")))
    }

    @Test
    fun `the legacy dark flag goes when a theme arrives`() = runBlocking {
        data.edit { it[SettingsKeys.DARK_MODE] = true }
        store.apply(SettingsCopyMerge.Changes(scalars = mapOf("appearance.theme" to SettingsScalarState(5L, JsonPrimitive("light")))))
        assertNull(data.current[SettingsKeys.DARK_MODE])
        assertEquals("light", data.current[SettingsKeys.THEME_MODE])
    }
}
