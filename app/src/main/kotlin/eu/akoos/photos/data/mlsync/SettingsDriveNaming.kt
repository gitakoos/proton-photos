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

/**
 * The fixed names behind the Drive copy of the settings. Every install writes exactly `settings-<deviceId>.pfpst`,
 * where the device id is the install's UUID, and replaces it as a new revision; a name never changes once written.
 * Pure, so it is unit-tested directly.
 */
object SettingsDriveNaming {
    /** Extension of a settings file. */
    const val FILE_EXTENSION = ".pfpst"

    /** Media type a settings file is uploaded with. */
    const val MEDIA_TYPE = "application/octet-stream"

    private const val PREFIX = "settings-"

    /** The file name install [deviceId]'s settings are stored under. */
    fun fileName(deviceId: String): String {
        require(MlDriveNaming.isDeviceId(deviceId)) { "Not a device id" }
        return "$PREFIX$deviceId$FILE_EXTENSION"
    }

    /** The install id in a name written by [fileName], or null for any other name. */
    fun parse(name: String): String? {
        if (!name.startsWith(PREFIX) || !name.endsWith(FILE_EXTENSION)) return null
        val deviceId = name.removePrefix(PREFIX).removeSuffix(FILE_EXTENSION)
        return deviceId.takeIf { MlDriveNaming.isDeviceId(it) }
    }
}
