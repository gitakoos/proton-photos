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

import androidx.datastore.preferences.core.mutablePreferencesOf
import eu.akoos.photos.data.mlsync.DriveDataFolder
import eu.akoos.photos.data.mlsync.SettingsDriveNaming
import eu.akoos.photos.data.preferences.SettingsKeys
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The allow list of synced settings: scopes, the value checks and converters, and what must never be in it. */
class SettingsSyncRegistryTest {

    private fun scalar(id: String) = SettingsSyncRegistry.scalar(id)!!

    @Test
    fun `the shared settings are the ones the contract names, every other one is scoped to a platform`() {
        val shared = SettingsSyncRegistry.scalars.filter { it.scope == SettingsScope.SHARED }.map { it.id }.toSet()
        assertEquals(
            setOf(
                "appearance.theme", "appearance.palette", "appearance.amoled", "albums.hidePhotosInAlbums",
                "albums.sortMode", "albums.order",
            ),
            shared,
        )
        assertTrue(SettingsSyncRegistry.sets.all { it.scope == SettingsScope.PLATFORM })
        val platform = SettingsSyncRegistry.scalars.filter { it.scope == SettingsScope.PLATFORM }.map { it.id }
        assertTrue(
            setOf(
                "appearance.language", "upload.compressPhotos", "upload.compressHdr", "upload.compressTier",
                "upload.compressVideos", "upload.compressVideoTier", "upload.compressCodec",
            ).all { it in platform },
        )
    }

    @Test
    fun `ids are unique across settings and sets, and carry no member separator`() {
        val ids = SettingsSyncRegistry.scalars.map { it.id } + SettingsSyncRegistry.sets.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        assertTrue(ids.none { '#' in it || '|' in it })
    }

    @Test
    fun `nothing that is security, device or account state is in the allow list`() {
        val keys = SettingsSyncRegistry.watchedKeys.map { it.name }.toSet()
        val never = listOf(
            SettingsKeys.APP_LOCK_ENABLED, SettingsKeys.APP_LOCK_TIMEOUT_SECONDS, SettingsKeys.APP_LOCK_TIMEOUT_MINUTES,
            SettingsKeys.VAULT_PASSWORD_BLOB, SettingsKeys.HIDDEN_CLOUD_PHOTO_IDS, SettingsKeys.HIDDEN_ALBUM_IDS,
            SettingsKeys.HIDDEN_FOLDER_NAMES, SettingsKeys.SYNC_WIFI_ONLY, SettingsKeys.AUTO_SYNC,
            SettingsKeys.FULLRES_WIFI_ONLY, SettingsKeys.SYNC_FOLDER_NAMES, SettingsKeys.EXCLUDED_FOLDER_NAMES,
            SettingsKeys.TIMELINE_EXCLUDED_FOLDER_NAMES, SettingsKeys.MIRROR_STRIP_TO_LOCAL, SettingsKeys.MIRROR_COMPRESS_TO_LOCAL,
            SettingsKeys.DELETE_LOCAL_AFTER_BACKUP, SettingsKeys.SHARED_BACKUP_ENABLED, SettingsKeys.SETTINGS_SYNC_TIMES,
            SettingsKeys.DUPLICATE_IGNORED_GROUPS, SettingsKeys.FAVORITE_IDS, SettingsKeys.AI_FEATURES_ENABLED,
            SettingsKeys.FACE_ENABLED, SettingsKeys.OCR_ENABLED, SettingsKeys.SEMANTIC_ENABLED,
        ).map { it.name }
        assertEquals(emptySet<String>(), keys.intersect(never.toSet()))
    }

    @Test
    fun `the theme, palette, codec and clock accept only their words`() {
        assertTrue(listOf("system", "light", "dark").all { scalar("appearance.theme").accepts(JsonPrimitive(it)) })
        assertFalse(scalar("appearance.theme").accepts(JsonPrimitive("amoled")))
        assertTrue(scalar("appearance.palette").accepts(JsonPrimitive("ruby")))
        assertFalse(scalar("appearance.palette").accepts(JsonPrimitive("amoled")))
        assertTrue(listOf("auto", "avc", "hevc", "av1", "keep").all { scalar("upload.compressCodec").accepts(JsonPrimitive(it)) })
        assertFalse(scalar("upload.compressCodec").accepts(JsonPrimitive("vp9")))
        assertTrue(scalar("android.clockFormat").accepts(JsonPrimitive("24h")))
        assertFalse(scalar("android.clockFormat").accepts(JsonPrimitive("military")))
    }

    @Test
    fun `a flag is a JSON boolean, never a string or a number`() {
        val amoled = scalar("appearance.amoled")
        assertTrue(amoled.accepts(JsonPrimitive(true)))
        assertFalse(amoled.accepts(JsonPrimitive("true")))
        assertFalse(amoled.accepts(JsonPrimitive(1)))
    }

    @Test
    fun `the language is a tag or system`() {
        val language = scalar("appearance.language")
        assertTrue(listOf("system", "en", "hu", "pt-BR", "zh-Hans").all { language.accepts(JsonPrimitive(it)) })
        assertFalse(language.accepts(JsonPrimitive("")))
        assertFalse(language.accepts(JsonPrimitive("not a tag")))
    }

    @Test
    fun `the tier is stored as an ordinal and travels as high, balanced or small`() {
        val tier = scalar("upload.compressTier")
        for ((ordinal, word) in listOf(0 to "high", 1 to "balanced", 2 to "small")) {
            val prefs = mutablePreferencesOf(SettingsKeys.COMPRESS_UPLOAD_TIER to ordinal)
            assertEquals(SettingReading.Value(JsonPrimitive(word)), tier.read(prefs))
            val written = mutablePreferencesOf()
            tier.write(written, JsonPrimitive(word))
            assertEquals(ordinal, written[SettingsKeys.COMPRESS_UPLOAD_TIER])
        }
        assertEquals(SettingReading.Unsyncable, tier.read(mutablePreferencesOf(SettingsKeys.COMPRESS_UPLOAD_TIER to 7)))
        assertEquals(SettingReading.Unset, tier.read(mutablePreferencesOf()))
        assertFalse(tier.accepts(JsonPrimitive(1)))
        assertFalse(tier.accepts(JsonPrimitive("tiny")))
        val video = scalar("upload.compressVideoTier")
        val prefs = mutablePreferencesOf()
        video.write(prefs, JsonPrimitive("small"))
        assertEquals(2, prefs[SettingsKeys.COMPRESS_UPLOAD_TIER_VIDEO])
    }

    @Test
    fun `a number setting is bounded by its range`() {
        val tab = scalar("android.landingTab")
        assertTrue(tab.accepts(JsonPrimitive(2)))
        assertFalse(tab.accepts(JsonPrimitive(3)))
        assertFalse(tab.accepts(JsonPrimitive(-1)))
        assertFalse(tab.accepts(JsonPrimitive("1")))
    }

    @Test
    fun `an overrides list is a whole array of strings, sorted on the way out`() {
        val overrides = scalar("android.albums.photoGroupingOverrides")
        val prefs = mutablePreferencesOf(SettingsKeys.ALBUM_PHOTO_GROUPING_OVERRIDES to setOf("1:b", "0:a"))
        assertEquals(SettingReading.Value(JsonArray(listOf(JsonPrimitive("0:a"), JsonPrimitive("1:b")))), overrides.read(prefs))
        assertTrue(overrides.accepts(JsonArray(listOf(JsonPrimitive("0:a")))))
        assertFalse(overrides.accepts(JsonArray(listOf(JsonPrimitive(1)))))
        assertFalse(overrides.accepts(JsonPrimitive("0:a")))
    }

    @Test
    fun `the album list sort travels as a shared word and the photo order choices stay out of the settings`() {
        val sort = scalar("albums.sortMode")
        assertEquals(SettingReading.Value(JsonPrimitive("newest")), sort.read(mutablePreferencesOf(SettingsKeys.ALBUMS_SORT_MODE to 4)))
        assertEquals(SettingReading.Value(JsonPrimitive("custom")), sort.read(mutablePreferencesOf(SettingsKeys.ALBUMS_SORT_MODE to 0)))
        val prefs = mutablePreferencesOf()
        sort.write(prefs, JsonPrimitive("count"))
        assertEquals(3, prefs[SettingsKeys.ALBUMS_SORT_MODE])
        assertFalse(sort.accepts(JsonPrimitive("random")))
        assertFalse(sort.accepts(JsonPrimitive(1)))
        assertNull(SettingsSyncRegistry.scalar("android.albums.sortMode"))
        assertNull(SettingsSyncRegistry.scalar("android.albums.photoSortOverrides"))
    }

    @Test
    fun `the album list order is an ordered array with no cap on the joined length`() {
        val order = scalar("albums.order")
        val ids = (1..400).map { "album-link-id-$it-" + "x".repeat(80) }
        val stored = ids.joinToString("|")
        assertTrue(stored.length > 16_384)
        val read = order.read(mutablePreferencesOf(SettingsKeys.ALBUMS_CUSTOM_ORDER to stored))
        assertEquals(SettingReading.Value(JsonArray(ids.map(::JsonPrimitive))), read)
        val prefs = mutablePreferencesOf()
        order.write(prefs, JsonArray(listOf(JsonPrimitive("b"), JsonPrimitive("a"))))
        assertEquals("b|a", prefs[SettingsKeys.ALBUMS_CUSTOM_ORDER])
        assertFalse(order.accepts(JsonArray(listOf(JsonPrimitive("a|b")))))
        assertFalse(order.accepts(JsonArray(listOf(JsonPrimitive(1)))))
        assertFalse(order.accepts(JsonPrimitive("a|b")))
    }

    @Test
    fun `the old android album ids read as the new shared ones`() {
        val sort = SettingsSyncRegistry.legacyAlias("android.albums.sortMode")!!
        assertEquals("albums.sortMode", sort.id)
        assertEquals(JsonPrimitive("name"), sort.convert(JsonPrimitive(1)))
        assertNull(sort.convert(JsonPrimitive(9)))
        val order = SettingsSyncRegistry.legacyAlias("android.albums.customOrder")!!
        assertEquals(JsonArray(listOf(JsonPrimitive("a"), JsonPrimitive("b"))), order.convert(JsonPrimitive("a|b")))
    }

    @Test
    fun `the album sets carry cloud album ids only, never a device folder entry`() {
        val grid = SettingsSyncRegistry.set("albums.gridExcludedAlbums")!!
        val prefs = mutablePreferencesOf(SettingsKeys.ALBUMS_GRID_EXCLUDED to setOf("album:L1", "folder:Camera", "album:"))
        assertEquals(setOf("L1"), grid.members(prefs))
        assertFalse(grid.accepts(""))
        assertTrue(SettingsSyncRegistry.set("timeline.excludedAlbums")!!.accepts("someLinkId=="))
        assertFalse(SettingsSyncRegistry.set("android.rail.hidden")!!.accepts("not a name"))
        assertNull(SettingsSyncRegistry.set("appearance.theme"))
    }

    @Test
    fun `the settings folder and file names are the contract's`() {
        val id = "11111111-2222-4333-8444-555555555555"
        assertEquals("Settings", DriveDataFolder.SETTINGS.folderName)
        assertEquals("settings-$id.pfpst", SettingsDriveNaming.fileName(id))
        assertEquals(id, SettingsDriveNaming.parse("settings-$id.pfpst"))
        assertEquals(id, DriveDataFolder.SETTINGS.deviceIdOf("settings-$id.pfpst"))
        assertNull(SettingsDriveNaming.parse("hidden-$id.pfphd"))
        assertNull(SettingsDriveNaming.parse("settings-$id.pfphd"))
        assertNull(DriveDataFolder.HIDDEN.deviceIdOf("settings-$id.pfpst"))
        assertNull(SettingsDriveNaming.parse("settings-not-an-id.pfpst"))
    }
}
