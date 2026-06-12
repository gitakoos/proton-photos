/*
 * Photos for Proton
 * Copyright (C) 2026 Akoos <https://akoos.eu>
 *
 * Source:  https://github.com/gitakoos/proton-photos
 * Website: https://photos.akoos.eu
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

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import eu.akoos.photos.domain.entity.GalleryItem

/**
 * Builds the ACTION_SEND / ACTION_SEND_MULTIPLE intents that hand decrypted media to other
 * apps (messaging, mail, etc.) through the system share sheet. Pure helpers — URI resolution
 * (local content URI vs. decrypted-then-FileProvider for cloud-only items) lives in the
 * caller, since that step is suspending and network-bound.
 */
object ShareIntentBuilder {

    /**
     * Narrowest MIME the whole selection fits under, so the chooser only lists apps that can
     * actually accept it: an image wildcard when every item is a photo, a video wildcard when
     * every item is a video, else a fully open wildcard for a mixed batch. Reads the per-subtype
     * MIME the same way the selection counter does (cloud items carry it on
     * [GalleryItem.CloudOnly.cloud], the rest on their local twin).
     */
    fun shareableMime(items: List<GalleryItem>): String = shareableMimeOf(
        items.map { item ->
            when (item) {
                is GalleryItem.LocalOnly -> item.local.mimeType
                is GalleryItem.Synced    -> item.local.mimeType
                is GalleryItem.CloudOnly -> item.cloud.mimeType
            }
        },
    )

    /**
     * MIME-string variant of [shareableMime] for callers that already hold raw per-item MIME
     * types (e.g. an album's [eu.akoos.photos.domain.entity.CloudPhoto] list) rather than
     * [GalleryItem]s. Same narrowing rule: image wildcard if all photos, video wildcard if all
     * videos, else a fully open wildcard.
     */
    fun shareableMimeOf(mimes: List<String>): String {
        if (mimes.isEmpty()) return "*/*"
        return when {
            mimes.all { it.startsWith("image/") } -> "image/*"
            mimes.all { it.startsWith("video/") } -> "video/*"
            else -> "*/*"
        }
    }

    /**
     * Wraps the resolved URIs in a send intent. A single URI uses ACTION_SEND with EXTRA_STREAM;
     * multiple URIs use ACTION_SEND_MULTIPLE with a parcelable URI list. A [ClipData] covering
     * every URI is attached alongside FLAG_GRANT_READ_URI_PERMISSION so the temporary read grant
     * reaches the receiver on every supported API level (the EXTRA_STREAM grant alone is unreliable
     * for the multiple case). Caller wraps the result in [Intent.createChooser] and launches it.
     */
    fun buildSendIntent(context: Context, uris: List<Uri>, mime: String): Intent {
        val clip = ClipData.newUri(context.contentResolver, "shared", uris.first()).apply {
            for (i in 1 until uris.size) addItem(ClipData.Item(uris[i]))
        }
        return if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).apply {
                type = mime
                putExtra(Intent.EXTRA_STREAM, uris.first())
                clipData = clip
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = mime
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
                clipData = clip
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
    }
}
