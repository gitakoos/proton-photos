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
import eu.akoos.photos.data.localcrypto.CacheFiles
import eu.akoos.photos.data.localcrypto.LocalKeyUnavailableException
import eu.akoos.photos.data.localcrypto.VaultFiles
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

/** Where stripped share copies live: the scratch folder emptied at every start, never the full-size cache. */
internal fun strippedShareDir(context: Context): File = File(context.cacheDir, VaultFiles.SHARE_DIR)

/** How long a stripped share copy is kept for the receiving app to read, at most; the next start removes it
 *  sooner. */
internal const val SHARE_COPY_KEEP_MS = 60 * 60 * 1000L

private const val SHARE_WORK_DIR = "work"
private const val STRIPPED_PREFIX = "stripped_"

/** Removes stripped share copies (and unfinished strips) in [dir] made before [cutoff]. */
internal fun sweepStrippedShareCopies(dir: File, cutoff: Long) {
    val files = (dir.listFiles().orEmpty().toList() + File(dir, SHARE_WORK_DIR).listFiles().orEmpty())
    for (f in files) {
        if (f.isFile && f.name.startsWith(STRIPPED_PREFIX) && f.lastModified() < cutoff) runCatching { f.delete() }
    }
}

/**
 * Removes the stripped share copies an earlier version wrote in plain into the full-size cache folder, made before
 * [before] (the start of this process).
 */
fun sweepLegacyShareCopies(context: Context, before: Long) {
    sweepStrippedShareCopies(File(context.cacheDir, "fullres"), before)
}

/**
 * Turns the stripped [plain] copy into the share copy in [dir], encrypted like the other caches, and removes
 * [plain]. When no key can be had now the copy stays as it is, in the scratch folder the next start empties.
 */
internal fun sealShareCopy(context: Context, plain: File, dir: File): File {
    val target = File(dir, plain.name)
    try {
        val sealed = CacheFiles.encryptingStream(java.io.FileOutputStream(target), context)
        sealed.use { out -> plain.inputStream().use { it.copyTo(out) } }
        plain.delete()
        return target
    } catch (e: LocalKeyUnavailableException) {
        target.delete()
        if (!plain.renameTo(target)) throw java.io.IOException("share copy not moved")
        return target
    } catch (e: Exception) {
        target.delete()
        plain.delete()
        throw e
    }
}

/**
 * A copy made only to be shared, named [name] and written by [write], kept as [sealShareCopy] keeps a stripped
 * one (encrypted, in the scratch folder emptied at every start), as a share-provider uri.
 */
fun shareCopyUri(context: Context, name: String, write: (java.io.OutputStream) -> Unit): Uri {
    val dir = strippedShareDir(context).apply { mkdirs() }
    val work = File(dir, SHARE_WORK_DIR).apply { mkdirs() }
    val plain = File(work, name)
    try {
        java.io.FileOutputStream(plain).use(write)
    } catch (e: Exception) {
        plain.delete()
        throw e
    }
    val copy = sealShareCopy(context, plain, dir)
    return FileProvider.getUriForFile(context, "${context.packageName}.share.fileprovider", copy)
}

/**
 * Produces a share-ready content [Uri] whose bytes have [config]'s metadata removed, or null to tell
 * the caller to share the ORIGINAL unchanged. The source is never mutated and no in-place stripper is
 * called: a stripped copy is written, encrypted, into the scratch folder emptied at every start (never the
 * full-size cache) and handed out through the share FileProvider, which decrypts it as the receiver reads,
 * with [displayName] reported as the filename. Copies older than [SHARE_COPY_KEEP_MS] go at the next strip.
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
        val shareDir = strippedShareDir(context).apply { mkdirs() }
        sweepStrippedShareCopies(shareDir, System.currentTimeMillis() - SHARE_COPY_KEEP_MS)
        val work = File(shareDir, SHARE_WORK_DIR).apply { mkdirs() }
        val stripped = when (kind) {
            ShareStripKind.IMAGE -> ExifHelper.stripToTempFile(context, sourceUri, config, work)
            ShareStripKind.VIDEO ->
                // The remux always emits an MPEG-4 container, so the copy is named .mp4 regardless of
                // source. remuxWithoutLocation deletes the partial output on failure, so a false leaves
                // no orphan here.
                File.createTempFile(STRIPPED_PREFIX, ".mp4", work).let { out ->
                    if (VideoMetadataStripper.remuxWithoutLocation(context, sourceUri, out)) out else null
                }
            ShareStripKind.UNSUPPORTED -> null
        } ?: return null
        val temp = sealShareCopy(context, stripped, shareDir)

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
