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

package eu.akoos.photos.data.updater

/**
 * Whether a found update earns a notification. The background check repeats on its own cadence and
 * keeps finding the same release every time, so the version name is the whole gate: an update the
 * user has already been told about stays silent until a genuinely different one is published.
 *
 * [lastNotifiedVersion] is the persisted marker, so the silence survives a process restart. A blank
 * or absent [availableVersion] is nothing to announce.
 */
internal fun shouldNotifyForUpdate(
    availableVersion: String?,
    lastNotifiedVersion: String?,
): Boolean {
    if (availableVersion.isNullOrBlank()) return false
    return availableVersion != lastNotifiedVersion
}

/**
 * Whether an APK already on disk belongs to the version currently on offer. A pre-downloaded
 * archive outlives the process that fetched it, so the recorded version is what proves it is still
 * the right one; anything else is a leftover to delete rather than an install to present.
 */
internal fun stagedUpdateMatches(
    stagedVersion: String?,
    availableVersion: String?,
): Boolean {
    if (stagedVersion.isNullOrBlank() || availableVersion.isNullOrBlank()) return false
    return stagedVersion == availableVersion
}
