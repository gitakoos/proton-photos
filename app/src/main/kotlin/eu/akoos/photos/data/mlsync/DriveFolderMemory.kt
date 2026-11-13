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

package eu.akoos.photos.data.mlsync

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Where each account's Drive data folders were found last ([MlDriveStore] checks them before every use),
 * kept across app starts so a start does not walk My files again. One entry per account and
 * [DriveDataFolder], in a preferences file of its own: never in sync_state or photo_listing. An
 * account's entries leave with it at sign-out ([clearUser]).
 */
@Singleton
class DriveFolderMemory @Inject constructor(@ApplicationContext private val context: Context) {

    /** The My files folder, the app's folder under it, and the data folder under that. */
    data class Folders(val myFilesUid: String, val appFolderUid: String, val dataFolderUid: String)

    private val prefs by lazy { context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE) }

    fun get(userId: String, area: DriveDataFolder): Folders? {
        val parts = prefs.getString(key(userId, area), null)?.split(SEPARATOR) ?: return null
        if (parts.size != 3 || parts.any { it.isEmpty() }) return null
        return Folders(parts[0], parts[1], parts[2])
    }

    fun put(userId: String, area: DriveDataFolder, folders: Folders) {
        val value = listOf(folders.myFilesUid, folders.appFolderUid, folders.dataFolderUid).joinToString(SEPARATOR)
        prefs.edit().putString(key(userId, area), value).apply()
    }

    fun remove(userId: String, area: DriveDataFolder) {
        prefs.edit().remove(key(userId, area)).apply()
    }

    fun clearUser(userId: String) {
        prefs.edit().apply { DriveDataFolder.entries.forEach { remove(key(userId, it)) } }.apply()
    }

    private fun key(userId: String, area: DriveDataFolder) = "$userId/${area.name}"

    private companion object {
        const val FILE_NAME = "drive_data_folders"
        const val SEPARATOR = "\n"
    }
}
