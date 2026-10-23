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

package eu.akoos.photos.presentation.picker

import android.content.ClipData
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import eu.akoos.photos.MainActivity

/**
 * What an app asking for a photo will take: the MIME patterns it named and whether it accepts more
 * than one. Only photos and videos are ever offered, whatever the patterns allow.
 */
data class PickRequest(val mimeTypes: List<String>, val allowMultiple: Boolean) {

    /** Whether a file of [mime] answers this request. */
    fun accepts(mime: String): Boolean {
        val m = mime.lowercase()
        if (!m.startsWith("image/") && !m.startsWith("video/")) return false
        return mimeTypes.any { pattern ->
            pattern == "*/*" || pattern == "*" || pattern == m ||
                (pattern.endsWith("/*") && m.startsWith(pattern.dropLast(1)))
        }
    }
}

/**
 * The request an incoming intent makes, from its type, the [Intent.EXTRA_MIME_TYPES] list that
 * narrows it, and [Intent.EXTRA_ALLOW_MULTIPLE]. An ACTION_PICK on a media collection carries the
 * collection's directory type rather than a MIME pattern, so those two are read as photos and videos.
 * A request that names nothing takes either.
 */
fun pickRequestOf(type: String?, extraMimeTypes: Array<String>?, allowMultiple: Boolean): PickRequest {
    val named = extraMimeTypes?.toList().orEmpty().ifEmpty { listOfNotNull(type) }
    val patterns = named.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.map {
        when (it) {
            "vnd.android.cursor.dir/image" -> "image/*"
            "vnd.android.cursor.dir/video" -> "video/*"
            else -> it
        }
    }
    return PickRequest(patterns.ifEmpty { listOf("*/*") }, allowMultiple)
}

/** The request [intent] makes. */
fun pickRequestOf(intent: Intent): PickRequest = pickRequestOf(
    type = intent.type,
    extraMimeTypes = intent.getStringArrayExtra(Intent.EXTRA_MIME_TYPES),
    allowMultiple = intent.getBooleanExtra(Intent.EXTRA_ALLOW_MULTIPLE, false),
)

/**
 * The answer to the asking app: the first pick as the data, every pick in the clip, and a read grant
 * on each, which lasts as long as the receiving screen does. [mimeTypes] describes the clip.
 */
fun pickResultIntent(uris: List<Uri>, mimeTypes: List<String>): Intent {
    require(uris.isNotEmpty())
    val clip = ClipData("photos", mimeTypes.distinct().toTypedArray(), ClipData.Item(uris.first())).apply {
        for (i in 1 until uris.size) addItem(ClipData.Item(uris[i]))
    }
    return Intent().apply {
        data = uris.first()
        clipData = clip
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
}

/**
 * The switch that lists the app among the places other apps can pick a photo from. Off by default:
 * the entry is a manifest alias of the main screen that ships disabled, so until it is turned on the
 * system never offers the app. The system keeps the state itself, which is also what this reads, so the
 * switch cannot drift from what the system does. The main screen is exported for the launcher, so it
 * checks this switch too before it answers a pick sent to it directly.
 */
object ExternalPicker {

    private fun entry(context: Context) =
        ComponentName(context, "${MainActivity::class.java.name.substringBeforeLast('.')}.PickPhotoEntry")

    fun isEnabled(context: Context): Boolean =
        context.packageManager.getComponentEnabledSetting(entry(context)) ==
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED

    fun setEnabled(context: Context, enabled: Boolean) {
        context.packageManager.setComponentEnabledSetting(
            entry(context),
            if (enabled) PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            else PackageManager.COMPONENT_ENABLED_STATE_DEFAULT,
            PackageManager.DONT_KILL_APP,
        )
    }
}

/** Whether [action] asks for a photo to be picked and handed back. */
fun isPickAction(action: String?): Boolean =
    action == Intent.ACTION_GET_CONTENT || action == Intent.ACTION_PICK
