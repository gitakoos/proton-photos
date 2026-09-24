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

package eu.akoos.photos.util

import android.Manifest
import android.annotation.SuppressLint
import android.os.Build

/**
 * The runtime permissions behind media access for a given OS level, so every screen that asks for
 * access or checks it works from the same sets. Pure, so both sets are unit-testable without an
 * Android runtime.
 *
 * Access is judged by [readSet] alone. On Android 9 and older every write to shared storage also
 * needs the write permission, so [requestSet] adds it there. It shares the storage group with the
 * read permission: one dialog grants both, and an app already holding read access gets it with no
 * dialog. It is never asked for from Android 10 on, where the manifest does not declare it and the
 * request would only come back denied.
 */
object MediaPermissions {

    /** What a media-access request asks for on [sdkInt]. */
    fun requestSet(sdkInt: Int): Array<String> =
        if (sdkInt <= Build.VERSION_CODES.P) readSet(sdkInt) + Manifest.permission.WRITE_EXTERNAL_STORAGE
        else readSet(sdkInt)

    /** The permissions that decide whether media access is granted on [sdkInt]. */
    @SuppressLint("InlinedApi")
    fun readSet(sdkInt: Int): Array<String> =
        if (sdkInt >= Build.VERSION_CODES.TIRAMISU)
            arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
        else
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)

    /** The [readSet] entries of a media-access request's [results], so the write permission never
     *  decides the outcome. */
    fun readResults(results: Map<String, Boolean>, sdkInt: Int): Map<String, Boolean> {
        val read = readSet(sdkInt)
        return results.filterKeys { it in read }
    }
}
