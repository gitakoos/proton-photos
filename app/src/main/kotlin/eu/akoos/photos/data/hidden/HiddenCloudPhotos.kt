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

import android.content.Context
import androidx.datastore.preferences.core.edit
import eu.akoos.photos.data.preferences.SettingsKeys
import eu.akoos.photos.data.preferences.settingsDataStore

/**
 * The hidden set for cloud-only photos, which have no device file to move and so hide by linkId
 * alone. Adding an id drops the photo from every listing at once; removing it brings the photo back.
 * Nothing on Drive changes either way.
 *
 * One place for both directions because they are one pair. Every surface that hides a selection
 * writes the ids first, before the device half can fail, so each surface also owes the matching
 * [reveal] on the paths where the rest of the hide did not land. Four surfaces each keeping their
 * own copy of the write is what let the reveal go missing on all four: a hide that reported "not
 * enough free space" had already made the user's cloud photos disappear, with no undo offered and
 * nothing on screen tying the two together.
 */
object HiddenCloudPhotos {

    /** Hide these cloud photos from every listing. No-op on an empty list. */
    suspend fun hide(context: Context, linkIds: Collection<String>) {
        if (linkIds.isEmpty()) return
        context.settingsDataStore.edit { prefs ->
            val existing = prefs[SettingsKeys.HIDDEN_CLOUD_PHOTO_IDS] ?: emptySet()
            prefs[SettingsKeys.HIDDEN_CLOUD_PHOTO_IDS] = existing + linkIds
        }
    }

    /** Put these cloud photos back in every listing. No-op on an empty list. */
    suspend fun reveal(context: Context, linkIds: Collection<String>) {
        if (linkIds.isEmpty()) return
        context.settingsDataStore.edit { prefs ->
            val existing = prefs[SettingsKeys.HIDDEN_CLOUD_PHOTO_IDS] ?: emptySet()
            prefs[SettingsKeys.HIDDEN_CLOUD_PHOTO_IDS] = existing - linkIds.toSet()
        }
    }
}
