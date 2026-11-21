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

package eu.akoos.photos.data.preferences

import androidx.datastore.preferences.core.mutablePreferencesOf
import me.proton.core.domain.entity.UserId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * What a sign-out's preference wipe leaves of the hidden vault's records.
 *
 * A vault file the sign-out left on disk is the only copy of its photo, and its records are how the
 * Hidden screen reaches it under its name. A sign-in that gave up never had a session and owns none of
 * what is stored, so it takes none of it.
 */
class AccountScopedPreferencesTest {

    private val kept = "file:///data/files/hidden/kept.jpg"
    private val gone = "file:///data/files/hidden/gone.jpg"
    private val isVault: (String) -> Boolean = { it.startsWith("file:///data/files/hidden/") }

    private fun vaultPrefs() = mutablePreferencesOf(
        SettingsKeys.HIDDEN_PHOTO_URIS to setOf(kept, gone, "cloud-filtered"),
        SettingsKeys.HIDDEN_URI_SOURCE_FOLDER_MAP to setOf("$kept|DCIM/Trip", "$gone|Pictures/Other"),
        SettingsKeys.HIDDEN_URI_ORIGINAL_NAME_MAP to setOf("$kept|beach.jpg", "$gone|old.jpg"),
        SettingsKeys.HIDDEN_URI_CLOUD_ID_MAP to setOf("$gone|L-1"),
        SettingsKeys.HIDDEN_URI_CARRIED_MAP to setOf("$kept|carried", "$gone|carried"),
        SettingsKeys.HIDDEN_PENDING_HIDES to setOf("$kept|content://media/1"),
        SettingsKeys.HIDDEN_FOLDER_NAMES to setOf("Trip", "Other"),
        SettingsKeys.FAVORITE_IDS to setOf(kept, gone, "content://media/9"),
        SettingsKeys.SYNC_FOLDER_NAMES to setOf("Camera"),
    )

    @Test
    fun `the records of the files the sign-out kept stay and every other goes`() {
        val prefs = vaultPrefs()

        AccountScopedPreferences.clearIn(prefs, UserId("u1"), keptVaultUris = setOf(kept), keepFavorites = false, isVaultUri = isVault)

        assertEquals(setOf(kept), prefs[SettingsKeys.HIDDEN_PHOTO_URIS])
        assertEquals(setOf("$kept|DCIM/Trip"), prefs[SettingsKeys.HIDDEN_URI_SOURCE_FOLDER_MAP])
        assertEquals(setOf("$kept|beach.jpg"), prefs[SettingsKeys.HIDDEN_URI_ORIGINAL_NAME_MAP])
        assertNull(prefs[SettingsKeys.HIDDEN_URI_CLOUD_ID_MAP])
        assertEquals(setOf("$kept|carried"), prefs[SettingsKeys.HIDDEN_URI_CARRIED_MAP])
        assertEquals("a hide still in flight keeps its journal entry", setOf("$kept|content://media/1"), prefs[SettingsKeys.HIDDEN_PENDING_HIDES])
        assertEquals(setOf("Trip"), prefs[SettingsKeys.HIDDEN_FOLDER_NAMES])
        assertEquals("a heart given in the vault stays with its photo", setOf(kept), prefs[SettingsKeys.FAVORITE_IDS])
        assertNull(prefs[SettingsKeys.SYNC_FOLDER_NAMES])
    }

    @Test
    fun `a vault emptied whole leaves no vault record behind`() {
        val prefs = vaultPrefs()

        AccountScopedPreferences.clearIn(prefs, UserId("u1"), keptVaultUris = emptySet(), keepFavorites = false, isVaultUri = isVault)

        assertNull(prefs[SettingsKeys.HIDDEN_PHOTO_URIS])
        assertNull(prefs[SettingsKeys.HIDDEN_URI_SOURCE_FOLDER_MAP])
        assertNull(prefs[SettingsKeys.HIDDEN_PENDING_HIDES])
        assertNull(prefs[SettingsKeys.HIDDEN_FOLDER_NAMES])
        assertNull(prefs[SettingsKeys.FAVORITE_IDS])
    }

    @Test
    fun `a vault left alone keeps every vault record`() {
        val prefs = vaultPrefs()

        AccountScopedPreferences.clearIn(prefs, UserId("u1"), keptVaultUris = null, keepFavorites = false, isVaultUri = isVault)

        assertEquals(setOf(kept, gone, "cloud-filtered"), prefs[SettingsKeys.HIDDEN_PHOTO_URIS])
        assertEquals(setOf("$gone|L-1"), prefs[SettingsKeys.HIDDEN_URI_CLOUD_ID_MAP])
        assertEquals(setOf("Trip", "Other"), prefs[SettingsKeys.HIDDEN_FOLDER_NAMES])
        assertEquals("the account's own hearts still go", setOf(kept, gone), prefs[SettingsKeys.FAVORITE_IDS])
    }

    @Test
    fun `a guest whose sign-in gave up keeps its vault index, its hidden folders and its favourites`() {
        val prefs = vaultPrefs()

        AccountScopedPreferences.clearIn(prefs, UserId("u-unfinished"), keptVaultUris = null, keepFavorites = true, isVaultUri = isVault)

        assertEquals(setOf(kept, gone, "cloud-filtered"), prefs[SettingsKeys.HIDDEN_PHOTO_URIS])
        assertEquals(setOf("$kept|DCIM/Trip", "$gone|Pictures/Other"), prefs[SettingsKeys.HIDDEN_URI_SOURCE_FOLDER_MAP])
        assertEquals(setOf("$kept|content://media/1"), prefs[SettingsKeys.HIDDEN_PENDING_HIDES])
        assertEquals(setOf("Trip", "Other"), prefs[SettingsKeys.HIDDEN_FOLDER_NAMES])
        assertEquals(setOf(kept, gone, "content://media/9"), prefs[SettingsKeys.FAVORITE_IDS])
    }

    @Test
    fun `the pending short-copy work goes with the account and the short link ids stay`() {
        val prefs = mutablePreferencesOf(
            SettingsKeys.SHORT_CLOUD_COPY_IDS to setOf("L-1"),
            SettingsKeys.UNVERIFIABLE_CLOUD_COPY_IDS to setOf("L-2"),
            SettingsKeys.SHORT_CLOUD_COPY_REPLACEMENTS to setOf("L-1|content://media/1"),
            SettingsKeys.DELETE_AFTER_WHOLE_BACKUP_URIS to setOf("content://media/1"),
        )

        AccountScopedPreferences.clearIn(prefs, UserId("u1"), keptVaultUris = emptySet(), keepFavorites = false, isVaultUri = isVault)

        assertEquals(setOf("L-1"), prefs[SettingsKeys.SHORT_CLOUD_COPY_IDS])
        assertEquals(setOf("L-2"), prefs[SettingsKeys.UNVERIFIABLE_CLOUD_COPY_IDS])
        assertNull(prefs[SettingsKeys.SHORT_CLOUD_COPY_REPLACEMENTS])
        assertNull(prefs[SettingsKeys.DELETE_AFTER_WHOLE_BACKUP_URIS])
    }
}
