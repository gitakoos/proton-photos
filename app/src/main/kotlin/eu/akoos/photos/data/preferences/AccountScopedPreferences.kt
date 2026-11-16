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

import android.content.Context
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import eu.akoos.photos.data.hidden.HiddenFolderRecords
import me.proton.core.domain.entity.UserId

/**
 * Everything stored about the account that is signing out, in one place both ways out of a session
 * can reach.
 *
 * A user does not always leave by pressing Sign out. The server can end the session, two-factor can
 * fail, and the app converges those on its own account-disabled handler, which is a different path
 * from the Settings one. While this wipe lived inside the Settings screen, only the deliberate exit
 * ran it: an account ended by the server left its folder selection, album mapping, hidden folder
 * names and queued Drive cleanups behind, and the next person to sign in on the phone inherited them.
 * Their backup then uploaded folders they never chose, into an album belonging to someone else.
 *
 * UI preferences (theme, palette, language) and machine-level flags (onboarding done, app lock, the
 * update throttle) are deliberately kept: they describe the phone and the person holding it, not the
 * account, and clearing them would reset the app around a user who only switched login.
 */
object AccountScopedPreferences {

    /**
     * Clears what the account [userId] left behind.
     *
     * [keptVaultUris] are the vault files the sign-out left on disk. Their records (the index entry, the
     * four per-photo maps, a pending hide, a heart given in the vault, the hidden folder they came from)
     * stay, so the Hidden screen goes on showing them under their names: the file is the only copy of
     * its photo and the records are how the user reaches it. Null means the vault was left alone, and
     * then every vault record stays. [keepFavorites] keeps the whole favourite set, for an account that
     * never had a session, whose favourites are not its own but those of whoever is still using the app.
     */
    suspend fun clear(
        context: Context,
        userId: UserId,
        keptVaultUris: Set<String>? = emptySet(),
        keepFavorites: Boolean = false,
    ) {
        // Read only when a favourite has to be told apart; a context with no files directory names no vault.
        val vaultPrefix by lazy {
            runCatching { android.net.Uri.fromFile(java.io.File(context.filesDir, "hidden")).toString() + "/" }.getOrNull()
        }
        context.settingsDataStoreStrict.edit { prefs ->
            clearIn(prefs, userId, keptVaultUris, keepFavorites) { uri -> vaultPrefix?.let { uri.startsWith(it) } == true }
        }
    }

    /** [clear] on [prefs]; [isVaultUri] tells a vault file's uri from any other favourite. */
    internal fun clearIn(
        prefs: MutablePreferences,
        userId: UserId,
        keptVaultUris: Set<String>?,
        keepFavorites: Boolean,
        isVaultUri: (String) -> Boolean,
    ) {
        clearVaultRecords(prefs, keptVaultUris, keepFavorites, isVaultUri)
        val accountTied = setOf<androidx.datastore.preferences.core.Preferences.Key<*>>(
            SettingsKeys.LAST_SYNC_MS,
            SettingsKeys.SYNC_FOLDER_NAMES,
            SettingsKeys.BACKUP_EVERYTHING,
            SettingsKeys.EXCLUDED_FOLDER_NAMES,
            SettingsKeys.MANUAL_LOCAL_FOLDER_NAMES,
            SettingsKeys.ALBUM_BUCKET_MAP,
            SettingsKeys.PENDING_DELETE_URIS,
            SettingsKeys.FREE_UP_UNCHECKED,
            SettingsKeys.OFFLINE_PIN_IDS,
            SettingsKeys.RECENT_UPLOAD_IDS,
            // Hide-photos-in-albums is a per-user view preference. Without
            // adding it here, a shared device that signs out → signs back in
            // as a different account inherits the previous user's choice.
            SettingsKeys.HIDE_PHOTOS_IN_ALBUMS,
            SettingsKeys.SHOW_SCROLL_DATE,
            SettingsKeys.REVERSE_TIMELINE_ORDER,
            SettingsKeys.MOSAIC_GRID,
            SettingsKeys.SEAMLESS_GRID,
            // Albums-tab filter preference + its remembered last value.
            SettingsKeys.ALBUMS_DEFAULT_FILTER,
            SettingsKeys.ALBUMS_REMEMBER_LAST_FILTER,
            SettingsKeys.ALBUMS_LAST_FILTER,
            // A hand-dragged album order and the folders opted into album mirroring
            // both name the previous account's albums, which the next user has no
            // access to and would never see the effect of.
            SettingsKeys.ALBUMS_CUSTOM_ORDER,
            SettingsKeys.ALBUM_OPT_IN_FOLDER_NAMES,
            SettingsKeys.ALBUM_OPT_OUT_FOLDER_NAMES,
            SettingsKeys.ALBUM_ALL_BY_DEFAULT,
            // Timeline folder and album filters are likewise per-user view preferences.
            SettingsKeys.TIMELINE_EXCLUDED_FOLDER_NAMES,
            SettingsKeys.TIMELINE_EXCLUDED_ALBUM_IDS,
            // Hidden albums are a per-user, client-side view preference; a second
            // account on a shared device must not inherit the previous user's set.
            SettingsKeys.HIDDEN_ALBUM_IDS,
            // Individually-hidden cloud photos are the same per-user client-side state.
            SettingsKeys.HIDDEN_CLOUD_PHOTO_IDS,
            // The times of those hides and unhides, the queue of other devices' hides, and the
            // shared backup's switch and record, all of them about the account signing out.
            SettingsKeys.HIDDEN_PHOTO_HIDE_TIMES,
            SettingsKeys.HIDDEN_PHOTO_SHOW_TIMES,
            SettingsKeys.HIDDEN_ALBUM_HIDE_TIMES,
            SettingsKeys.HIDDEN_ALBUM_SHOW_TIMES,
            SettingsKeys.HIDDEN_REMOTE_HIDE_QUEUE,
            SettingsKeys.SHARED_BACKUP_ENABLED,
            SettingsKeys.SHARED_BACKUP_OWN_COPY_TAKEN,
            SettingsKeys.SHARED_BACKUP_DEVICE_NAMES,
            SettingsKeys.SHARED_BACKUP_FACES_WIPED_AT,
            SettingsKeys.SHARED_BACKUP_FACES_TRASH_PENDING,
            SettingsKeys.SHARED_BACKUP_LAST_AT,
            SettingsKeys.SHARED_BACKUP_LAST_FAILED,
            SettingsKeys.SHARED_BACKUP_SAVED,
            SettingsKeys.SHARED_BACKUP_SEEN,
            SettingsKeys.SHARED_BACKUP_CHANGED_AT,
            SettingsKeys.SHARED_BACKUP_FACES_CHANGED_AT,
            SettingsKeys.SHARED_BACKUP_SEARCH_CHANGED_AT,
            SettingsKeys.SHARED_BACKUP_FACES_SAVED_AT,
            SettingsKeys.SHARED_BACKUP_SEARCH_SAVED_AT,
            SettingsKeys.SHARED_BACKUP_FACES_USER_CHANGED_AT,
            // The settings' change times go in the same edit as the values the wipe removes, so a
            // removal here is never stamped as a reset another device would take over.
            SettingsKeys.SETTINGS_SYNC_TIMES,
            // Queued "shareId|linkId" cleanups name Drive nodes of the account being
            // signed out. The next user's upload pass drains this set and would retry
            // each delete against a share their session cannot reach.
            SettingsKeys.PENDING_ORPHAN_DELETES,
        )
        accountTied.forEach { prefs.remove(it) }
        // Per-user dynamic keys (one per volume): the events anchor + the photo
        // listing resume cursor/complete flag. A stale events anchor surviving
        // sign-out is what returned a 404 on the next login until the cache was
        // cleared by hand.
        val dynamicPrefixes = listOf(
            "event_anchor_${userId.id}_",
            "photo_listing_cursor_${userId.id}_",
            "photo_listing_complete_${userId.id}_",
            "photo_listing_ever_complete_${userId.id}_",
            "pairing_settled_${userId.id}",
            "cloud_verified_at_${userId.id}",
        )
        prefs.asMap().keys
            .filter { key -> dynamicPrefixes.any { key.name.startsWith(it) } }
            .toList()
            .forEach { prefs.remove(it) }
    }

    /**
     * The vault's records, kept for the files [kept] names and dropped for every other: the index, the
     * four per-photo maps, the pending hides and their delete requests, the hidden folder names and the
     * favourites. Null keeps them all. A favourite naming no vault file is the account's own and goes
     * unless [keepFavorites].
     */
    private fun clearVaultRecords(
        prefs: MutablePreferences,
        kept: Set<String>?,
        keepFavorites: Boolean,
        isVaultUri: (String) -> Boolean,
    ) {
        val favorites = prefs[SettingsKeys.FAVORITE_IDS]
        if (!keepFavorites && favorites != null) {
            val left = favorites.filterTo(HashSet()) { isVaultUri(it) && (kept == null || it in kept) }
            if (left.isEmpty()) prefs.remove(SettingsKeys.FAVORITE_IDS) else prefs[SettingsKeys.FAVORITE_IDS] = left
        }
        if (kept == null) return
        val index = prefs[SettingsKeys.HIDDEN_PHOTO_URIS]
        if (index != null) keepOnly(prefs, SettingsKeys.HIDDEN_PHOTO_URIS, index.filterTo(HashSet()) { it in kept })
        for (key in listOf(
            SettingsKeys.HIDDEN_URI_CLOUD_ID_MAP,
            SettingsKeys.HIDDEN_URI_ORIGINAL_NAME_MAP,
            SettingsKeys.HIDDEN_URI_SOURCE_FOLDER_MAP,
            SettingsKeys.HIDDEN_URI_CARRIED_MAP,
            SettingsKeys.HIDDEN_PENDING_HIDES,
            SettingsKeys.HIDDEN_DELETE_REQUESTS,
            // Plain uris rather than "uri|value", which the same key match reads whole.
            SettingsKeys.HIDDEN_URI_EDITED,
        )) {
            val entries = prefs[key] ?: continue
            keepOnly(prefs, key, entries.filterTo(HashSet()) { it.substringBefore('|') in kept })
        }
        // A hidden folder stays hidden while a photo kept in the vault came from it; it is the handle the
        // vault reaches that photo's folder by.
        val folders = prefs[SettingsKeys.HIDDEN_FOLDER_NAMES]
        if (folders != null) {
            val keptBuckets = (prefs[SettingsKeys.HIDDEN_URI_SOURCE_FOLDER_MAP] ?: emptySet())
                .mapTo(HashSet()) { HiddenFolderRecords.bucketOf(it.substringAfter('|', "")) }
            keepOnly(prefs, SettingsKeys.HIDDEN_FOLDER_NAMES, folders.filterTo(HashSet()) { it in keptBuckets })
        }
    }

    private fun keepOnly(prefs: MutablePreferences, key: Preferences.Key<Set<String>>, value: Set<String>) {
        if (value.isEmpty()) prefs.remove(key) else prefs[key] = value
    }
}
