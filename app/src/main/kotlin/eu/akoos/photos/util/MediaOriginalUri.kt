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
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.core.content.ContextCompat

/** The MediaStore provider authority. A URI from any other provider carries no original to ask for. */
private const val MEDIA_STORE_AUTHORITY = "media"

/**
 * The URI an EXIF READ should open so Android 10+ hands back the file's own location instead of a
 * redacted stream. Single source of the [MediaStore.setRequireOriginal] rule for every reader: the
 * viewer's details sheet, the metadata editor's prefill, the place resolver and the GPS backfill all
 * route through it, so one file's coordinates read the same wherever they are shown.
 *
 * Returns [uri] untouched unless [shouldRequireOriginal] allows the upgrade, and falls back to it
 * again if the provider refuses, so the worst case stays the redacted read the caller would have had
 * anyway. WRITE paths keep the plain URI: they open their own `rw` descriptor, which the original
 * flag does not apply to.
 */
fun originalUriForExif(context: Context, uri: String): String {
    if (!shouldRequireOriginal(Build.VERSION.SDK_INT, hasMediaLocationGrant(context), uri)) return uri
    // The version gate lives in the predicate so it can be tested, but that hides it from the API
    // check, which only reads a comparison it can see in the same body. This repeats it in the form
    // the check understands, so the call is proven unreachable below Q at build time as well.
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return uri
    return runCatching { MediaStore.setRequireOriginal(Uri.parse(uri)).toString() }.getOrDefault(uri)
}

/**
 * Whether the original of [uri] may be asked for at all, given [sdkInt] and [hasGrant]. Three gates,
 * each of which makes the upgrade either pointless or harmful:
 *
 *  - below [Build.VERSION_CODES.Q] nothing is redacted, so the plain URI already carries the GPS.
 *  - without the ACCESS_MEDIA_LOCATION grant the request is refused at read time, turning a working
 *    redacted read into a failed one, so an ungranted app reads what it is allowed to read.
 *  - a non-MediaStore URI (a `file://` vault item, an app provider) has no redacted variant, and the
 *    flag is a query parameter its provider never asked for.
 *
 * Pure, so the whole decision is unit-testable without an Android runtime.
 */
fun shouldRequireOriginal(sdkInt: Int, hasGrant: Boolean, uri: String): Boolean =
    sdkInt >= Build.VERSION_CODES.Q && hasGrant && isMediaStoreUri(uri)

/**
 * True when [uri] is served by MediaStore, i.e. a `content://media/…` URI. Matched on the scheme and
 * authority alone so a MediaStore URI with a query string or a fragment still counts. Pure.
 */
fun isMediaStoreUri(uri: String): Boolean {
    val separator = uri.indexOf("://")
    if (separator < 0) return false
    if (!uri.substring(0, separator).equals("content", ignoreCase = true)) return false
    val authority = uri.substring(separator + 3).substringBefore('/')
        .substringBefore('?').substringBefore('#')
    return authority.equals(MEDIA_STORE_AUTHORITY, ignoreCase = true)
}

/** Whether ACCESS_MEDIA_LOCATION is held. Always true below [Build.VERSION_CODES.Q], where the
 *  permission does not exist and no location is stripped. */
fun hasMediaLocationGrant(context: Context): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return true
    return ContextCompat.checkSelfPermission(
        context, Manifest.permission.ACCESS_MEDIA_LOCATION,
    ) == PackageManager.PERMISSION_GRANTED
}
