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

package eu.akoos.photos.data.share

import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import androidx.core.content.IntentCompat
import eu.akoos.photos.util.ProtonPhotosStorage

/**
 * Turns the uris another app shares into the MediaStore uris the backup works on. The backup reads a
 * photo by its MediaStore uri long after the share has ended, while a shared uri is often the sending
 * app's own (a gallery's provider, a browser's, a chat app's), readable only while the share lasts. So
 * each one is matched to the photo the phone already holds: directly when it is a MediaStore uri, else
 * by its file name and size. A photo or video the phone does not hold yet is saved into the camera
 * folder first, so sharing it again finds that copy instead of making another.
 */
class SharedMediaResolver(private val resolver: ContentResolver, private val ownPackage: String) {

    /**
     * The MediaStore uri of each shared item, in order and without repeats, how many were saved to the
     * phone on the way, and how many could not be read as a photo or video. [typeHint] is the share's
     * own MIME type, used when the sending app does not report one per item.
     */
    fun resolve(shared: List<Uri>, typeHint: String?): Resolved {
        val found = LinkedHashSet<String>()
        var saved = 0
        var unreadable = 0
        for (uri in shared) {
            if (!readableShare(uri.scheme, uri.authority, ownPackage)) {
                unreadable++
                continue
            }
            val match = runCatching { direct(uri) ?: byNameAndSize(uri) }.getOrNull()
            val copy = if (match == null) runCatching { saveToPhone(uri, typeHint) }.getOrNull() else null
            when {
                match != null -> found.add(match)
                copy != null -> if (found.add(copy)) saved++
                else -> unreadable++
            }
        }
        return Resolved(found.toList(), saved, unreadable)
    }

    private fun saveToPhone(uri: Uri, typeHint: String?): String? {
        val mime = (runCatching { resolver.getType(uri) }.getOrNull() ?: typeHint?.takeUnless { '*' in it })
            ?.lowercase() ?: return null
        val isVideo = mime.startsWith("video/")
        if (!isVideo && !mime.startsWith("image/")) return null
        val collection = if (isVideo) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, sharedFileName(displayName(uri), mime, System.currentTimeMillis()))
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, if (isVideo) ProtonPhotosStorage.DEFAULT_MOVIES else ProtonPhotosStorage.DEFAULT_PICTURES)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        }
        val target = resolver.insert(collection, values) ?: return null
        val copied = runCatching {
            resolver.openInputStream(uri)!!.use { input -> resolver.openOutputStream(target)!!.use { input.copyTo(it) } }
        }.isSuccess
        if (!copied) {
            runCatching { resolver.delete(target, null, null) }
            return null
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            resolver.update(target, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        }
        return Uri.withAppendedPath(collection, ContentUris.parseId(target).toString()).toString()
    }

    private fun displayName(uri: Uri): String? =
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (c.moveToFirst() && i >= 0 && !c.isNull(i)) c.getString(i) else null
        }

    private fun direct(uri: Uri): String? {
        val canonical = canonicalMediaStoreUri(uri.scheme, uri.authority, uri.pathSegments) ?: return null
        return canonical.takeIf { exists(Uri.parse(it)) }
    }

    private fun exists(uri: Uri): Boolean =
        resolver.query(uri, arrayOf(MediaStore.MediaColumns._ID), null, null, null)?.use { it.moveToFirst() } == true

    private fun byNameAndSize(uri: Uri): String? {
        val (name, size) = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
            ?.use { c ->
                if (!c.moveToFirst()) return null
                val n = c.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 && !c.isNull(it) }?.let(c::getString)
                val s = c.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 && !c.isNull(it) }?.let(c::getLong)
                n to s
            } ?: return null
        if (name.isNullOrBlank() || size == null || size <= 0L) return null
        for (collection in listOf(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, MediaStore.Video.Media.EXTERNAL_CONTENT_URI)) {
            resolver.query(
                collection,
                arrayOf(MediaStore.MediaColumns._ID),
                "${MediaStore.MediaColumns.DISPLAY_NAME} = ? AND ${MediaStore.MediaColumns.SIZE} = ?",
                arrayOf(name, size.toString()),
                null,
            )?.use { c ->
                if (c.moveToFirst()) return Uri.withAppendedPath(collection, c.getLong(0).toString()).toString()
            }
        }
        return null
    }

    data class Resolved(val uris: List<String>, val saved: Int, val unreadable: Int)
}

/**
 * The name a shared file is saved under: its own name with an extension that fits [mime], or a
 * dated one when the sending app gives none. MediaStore numbers a clash itself.
 */
/**
 * Whether a shared uri may be read here: only a content uri of another app. What is read is opened with this
 * app's own rights, so a file uri, or one of this app's own providers, would hand any sender this app's private
 * files (its database, its cached photos, the vault) to copy into the public gallery and up to Drive.
 */
internal fun readableShare(scheme: String?, authority: String?, ownPackage: String): Boolean =
    scheme == ContentResolver.SCHEME_CONTENT &&
        !authority.isNullOrBlank() &&
        authority != ownPackage &&
        !authority.startsWith("$ownPackage.")

internal fun sharedFileName(name: String?, mime: String, nowMs: Long): String {
    val ext = COMMON_EXTENSIONS[mime] ?: MimeTypeMap.getSingleton().getExtensionFromMimeType(mime)
        ?: if (mime.startsWith("video/")) "mp4" else "jpg"
    val base = name?.trim()?.replace(Regex("[\\\\/:*?\"<>|]"), "_")?.takeIf { it.isNotBlank() }
        ?: "shared_$nowMs"
    return if (base.contains('.')) base else "$base.$ext"
}

private val COMMON_EXTENSIONS = mapOf(
    "image/jpeg" to "jpg", "image/png" to "png", "image/gif" to "gif", "image/webp" to "webp",
    "image/heic" to "heic", "image/heif" to "heif", "image/avif" to "avif",
    "video/mp4" to "mp4", "video/webm" to "webm", "video/quicktime" to "mov", "video/3gpp" to "3gp",
)

/**
 * The uri the phone's own media index gives a photo or video, as the local listing writes it
 * (`content://media/external/images/media/<id>`), for a MediaStore uri of any volume; null for
 * anything else, including the system photo picker's uris, which only a name and size lookup can match.
 */
internal fun canonicalMediaStoreUri(scheme: String?, authority: String?, segments: List<String>): String? {
    if (scheme != ContentResolver.SCHEME_CONTENT || authority != MediaStore.AUTHORITY) return null
    if (segments.size != 4 || segments[2] != "media") return null
    val id = segments[3].toLongOrNull()?.takeIf { it > 0 } ?: return null
    val kind = segments[1].takeIf { it == "images" || it == "video" } ?: return null
    return "content://${MediaStore.AUTHORITY}/external/$kind/media/$id"
}

/** The uris a SEND or SEND_MULTIPLE intent carries, from its stream extra or, failing that, its clip data. */
fun sharedUrisOf(intent: Intent): List<Uri> {
    val fromExtra = when (intent.action) {
        Intent.ACTION_SEND -> listOfNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))
        Intent.ACTION_SEND_MULTIPLE ->
            IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java).orEmpty().filterNotNull()
        else -> return emptyList()
    }
    if (fromExtra.isNotEmpty()) return fromExtra
    val clip = intent.clipData ?: return emptyList()
    return (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).uri }
}
