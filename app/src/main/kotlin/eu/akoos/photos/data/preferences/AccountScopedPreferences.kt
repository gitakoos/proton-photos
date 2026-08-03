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
import androidx.datastore.preferences.core.edit
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

    suspend fun clear(context: Context, userId: UserId) {
                context.settingsDataStore.edit { prefs ->
                    val accountTied = setOf<androidx.datastore.preferences.core.Preferences.Key<*>>(
                        SettingsKeys.LAST_SYNC_MS,
                        SettingsKeys.SYNC_FOLDER_NAMES,
                        SettingsKeys.BACKUP_EVERYTHING,
                        SettingsKeys.EXCLUDED_FOLDER_NAMES,
                        SettingsKeys.HIDDEN_URI_CLOUD_ID_MAP,
                        SettingsKeys.HIDDEN_URI_ORIGINAL_NAME_MAP,
                        SettingsKeys.HIDDEN_URI_SOURCE_FOLDER_MAP,
                        // Everything else a vaulted photo carried, and any hide still in flight.
                        // Both are keyed by a vault file, and sign-out deletes every vault file,
                        // so an entry left behind here is unreachable for the rest of the
                        // install's life: the prune paths that would clear it all start from a
                        // blob that no longer exists.
                        SettingsKeys.HIDDEN_URI_CARRIED_MAP,
                        SettingsKeys.HIDDEN_PENDING_HIDES,
                        SettingsKeys.MANUAL_LOCAL_FOLDER_NAMES,
                        SettingsKeys.ALBUM_BUCKET_MAP,
                        SettingsKeys.PENDING_DELETE_URIS,
                        SettingsKeys.HIDDEN_PHOTO_URIS,
                        SettingsKeys.OFFLINE_PIN_IDS,
                        SettingsKeys.FAVORITE_IDS,
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
                        // Timeline folder and album filters are likewise per-user view preferences.
                        SettingsKeys.TIMELINE_EXCLUDED_FOLDER_NAMES,
                        SettingsKeys.TIMELINE_EXCLUDED_ALBUM_IDS,
                        // Hidden albums are a per-user, client-side view preference; a second
                        // account on a shared device must not inherit the previous user's set.
                        SettingsKeys.HIDDEN_ALBUM_IDS,
                        // Hidden device-folder cards are the same kind of preference, and a card
                        // missing from a second user's grid for a choice they never made is a
                        // folder they have no reason to go looking for.
                        SettingsKeys.HIDDEN_FOLDER_NAMES,
                        // Individually-hidden cloud photos are the same per-user client-side state.
                        SettingsKeys.HIDDEN_CLOUD_PHOTO_IDS,
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
                }    }
}
