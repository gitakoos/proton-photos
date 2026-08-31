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

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Which strip path a share takes. UNSUPPORTED means share the original untouched. */
enum class ShareStripKind { IMAGE, VIDEO, UNSUPPORTED }

/**
 * Media kind for a share strip, decided from [mimeType] and falling back to the [uri]'s extension
 * only when the mime is absent or blank. A parameter list (`video/mp4; codecs=avc1`) and mixed case
 * both resolve to the base type. Pure, so a plain JVM test can pin the mapping without Android.
 */
internal fun shareStripKind(mimeType: String?, uri: String): ShareStripKind {
    val mime = mimeType?.substringBefore(';')?.trim()?.lowercase()?.takeIf { it.isNotBlank() }
        ?: mimeFromPath(uri).takeIf { it.isNotBlank() }
    return when {
        mime == null -> ShareStripKind.UNSUPPORTED
        mime.startsWith("image/") -> ShareStripKind.IMAGE
        mime.startsWith("video/") -> ShareStripKind.VIDEO
        else -> ShareStripKind.UNSUPPORTED
    }
}

/**
 * Produces a share-ready content [Uri] whose bytes have [config]'s metadata removed, or null to tell
 * the caller to share the ORIGINAL unchanged. The source is never mutated and no in-place stripper is
 * called: a stripped copy is written under `cacheDir/fullres` (the one cache root the share
 * FileProvider exposes) and handed out through it, with [displayName] reported as the filename.
 *
 * IMAGE runs the EXIF temp-file strip. VIDEO can only shed its location atom, so it acts only when
 * [MetadataStripConfig.stripGps] is set and otherwise returns null. Any failure — an unsupported
 * kind, a null/false strip result, or a thrown exception — returns null so the share falls back to
 * the original bytes rather than being blocked.
 */
fun stripForShare(
    context: Context,
    sourceUri: String,
    mimeType: String?,
    displayName: String?,
    config: MetadataStripConfig,
): Uri? {
    if (config.isNoOp) return null
    val kind = shareStripKind(mimeType, sourceUri)
    if (kind == ShareStripKind.UNSUPPORTED) return null
    // A video strip has nothing to remove without the location drop.
    if (kind == ShareStripKind.VIDEO && !config.stripGps) return null

    return try {
        val shareDir = File(context.cacheDir, "fullres").apply { mkdirs() }
        val temp = when (kind) {
            ShareStripKind.IMAGE -> ExifHelper.stripToTempFile(context, sourceUri, config, shareDir)
            ShareStripKind.VIDEO ->
                // The remux always emits an MPEG-4 container, so the copy is named .mp4 regardless of
                // source. remuxWithoutLocation deletes the partial output on failure, so a false leaves
                // no orphan here.
                File.createTempFile("stripped_", ".mp4", shareDir).let { out ->
                    if (VideoMetadataStripper.remuxWithoutLocation(context, sourceUri, out)) out else null
                }
            ShareStripKind.UNSUPPORTED -> null
        } ?: return null

        FileProvider.getUriForFile(context, "${context.packageName}.share.fileprovider", temp).also {
            if (!displayName.isNullOrBlank()) ShareFileProvider.putDisplayName(it, displayName)
        }
    } catch (_: Exception) {
        null
    }
}

/**
 * Maps [original] to a stripped share copy per [config], or returns [original] unchanged. A null
 * [config] (share-strip master off) passes the original straight through; otherwise the blocking
 * [stripForShare] runs on [Dispatchers.IO], and its null return (unsupported kind, nothing to
 * remove, or a failure) also falls back to the original. The source is only ever read, never
 * written, so every call-site keeps the original — device file or decrypted cloud temp — intact.
 */
suspend fun stripForShareOrOriginal(
    context: Context,
    original: Uri,
    mimeType: String?,
    displayName: String?,
    config: MetadataStripConfig?,
): Uri {
    if (config == null) return original
    return withContext(Dispatchers.IO) {
        stripForShare(context, original.toString(), mimeType, displayName, config)
    } ?: original
}
