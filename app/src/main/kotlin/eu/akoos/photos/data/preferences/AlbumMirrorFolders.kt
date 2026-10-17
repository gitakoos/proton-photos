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

import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences

/**
 * The device folders whose photos also join a Drive album as they upload. Once "Back up everything" has been turned
 * on with this default ([allByDefault]), every folder does, a folder created later included, except the ones the
 * user switched off ([optedOut]); otherwise only the folders the user switched on ([optedIn]). Switching one folder
 * writes both sets, so a later change of the backup mode keeps what the user chose for it.
 *
 * The default starts when the user turns "Back up everything" on, or picks it in the setup, never on an install
 * that already had it on, so an update does not start creating albums for the folders its user left out.
 */
data class AlbumMirrorFolders(
    val optedIn: Set<String> = emptySet(),
    val optedOut: Set<String> = emptySet(),
    val allByDefault: Boolean = false,
) {
    operator fun contains(folder: String): Boolean =
        folder in optedIn || (allByDefault && folder !in optedOut)

    /** The rule after flipping [folder]: what is on goes off, what is off goes on. */
    fun flipped(folder: String): AlbumMirrorFolders =
        if (folder in this) copy(optedIn = optedIn - folder, optedOut = optedOut + folder)
        else copy(optedIn = optedIn + folder, optedOut = optedOut - folder)
}

/** The album rule these preferences hold. */
fun Preferences.albumMirrorFolders(): AlbumMirrorFolders = AlbumMirrorFolders(
    optedIn = this[SettingsKeys.ALBUM_OPT_IN_FOLDER_NAMES] ?: emptySet(),
    optedOut = this[SettingsKeys.ALBUM_OPT_OUT_FOLDER_NAMES] ?: emptySet(),
    allByDefault = (this[SettingsKeys.BACKUP_EVERYTHING] ?: false) &&
        (this[SettingsKeys.ALBUM_ALL_BY_DEFAULT] ?: false),
)

/** Stores the two folder sets of [rule]; the backup mode is the user's own setting and is not written. */
fun MutablePreferences.putAlbumMirrorFolders(rule: AlbumMirrorFolders) {
    this[SettingsKeys.ALBUM_OPT_IN_FOLDER_NAMES] = rule.optedIn
    this[SettingsKeys.ALBUM_OPT_OUT_FOLDER_NAMES] = rule.optedOut
}
