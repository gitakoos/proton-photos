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

package eu.akoos.photos.data.hidden

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import androidx.datastore.preferences.core.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.akoos.photos.BuildConfig
import eu.akoos.photos.data.preferences.SettingsKeys
import eu.akoos.photos.data.preferences.settingsDataStore
import eu.akoos.photos.util.CaptureDateOverride
import eu.akoos.photos.util.ExifDateFormat
import eu.akoos.photos.util.ExifHelper
import eu.akoos.photos.util.HiddenCaptureTime
import eu.akoos.photos.util.Mp4CreationTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.time.ZoneId
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * Moves photos in and out of app-private storage so that other gallery apps on the device
 * cannot see hidden items. The Hidden Album then renders directly from the private files
 * (file://...path) instead of from MediaStore.
 *
 * Hide flow:
 *   1. [store] copies the source bytes into `filesDir/hidden/<uuid>.<ext>` (private to this app)
 *   2. The caller is expected to delete the source MediaStore entry via the existing
 *      DeletePhotoUseCase pipeline (which handles the Android 11+ system trash confirmation).
 *
 * Unhide flow:
 *   1. [restore] inserts a fresh MediaStore entry carrying the original bytes, at the path the
 *      file was hidden from where that is known, then deletes the private copy.
 */
@Singleton
class HiddenStorageManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val hiddenDir: File
        get() = File(context.filesDir, "hidden").also { it.mkdirs() }

    /** Deletes every blob in the vault. Used on sign-out, after the hidden index is cleared, so the
     *  now-orphaned decrypted photos do not linger on disk for the next account on this device. */
    fun clearVault() {
        hiddenDir.deleteRecursively()
    }

    /** Every blob the vault currently holds, as `file://` URIs — the same form the index and the
     *  per-uri maps are keyed by. The directory layout is this class's, so the enumeration lives
     *  here rather than in the reconciliation that consumes it. */
    fun vaultBlobUris(): Set<String> =
        hiddenDir.listFiles()
            ?.asSequence()
            ?.filter { it.isFile }
            ?.map { Uri.fromFile(it).toString() }
            ?.toSet()
            ?: emptySet()

    /** What the vault occupies on disk, in bytes. Read by the diagnostics snapshot, which answers
     *  for the vault in counts and sizes alone. */
    fun vaultSizeBytes(): Long =
        hiddenDir.listFiles()
            ?.asSequence()
            ?.filter { it.isFile }
            ?.sumOf { it.length() }
            ?: 0L

    /**
     * Resolves the full folder a source media item lives in, stashed at hide time so [restore] can
     * return the file to its EXACT original location — including non-MediaStore folders such as an
     * app's "Android/media/<pkg>/…". Returns the real MediaStore RELATIVE_PATH unchanged (e.g.
     * "DCIM/Camera", "Pictures/Vacation", "Android/media/com.whatsapp/Media/WhatsApp Images"), or
     * [fallbackBucketName] when RELATIVE_PATH is unreadable (pre-Q / non-MediaStore URI), or null.
     */
    fun sourceFolderFor(srcUri: String, fallbackBucketName: String?): String? {
        val relativePath: String? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching {
                val src = Uri.parse(srcUri)
                context.contentResolver.query(
                    src, arrayOf(MediaStore.MediaColumns.RELATIVE_PATH), null, null, null,
                )?.use { c ->
                    if (c.moveToFirst()) c.getString(0) else null
                }
            }.getOrNull()
        } else null
        // Keep the FULL original RELATIVE_PATH so restore can put the file back exactly where it
        // came from, not just somewhere under Pictures/.
        val resolved = relativePath?.trim('/')?.takeIf { it.isNotBlank() }
        return (resolved ?: fallbackBucketName?.trim('/'))?.takeIf { it.isNotBlank() }
    }

    /** Whether the vault still holds the file [hiddenUri] names. A reveal that finds nothing there
     *  and one that could not write the file back are different answers: only the second leaves a
     *  copy the records still have to point at. */
    fun hasBlob(hiddenUri: String): Boolean {
        val parsed = runCatching { Uri.parse(hiddenUri) }.getOrNull() ?: return false
        val file = parsed.path?.let { File(it) } ?: return false
        return file.exists()
    }

    /** The vault directory resolved WITHOUT creating it, so asking whether a uri belongs to the vault
     *  never has the side effect of making the directory exist. */
    private val vaultPath: String
        get() = File(context.filesDir, "hidden").absolutePath

    /** Returns true when [uri] is a private hidden-storage file URI managed by this class. Cheap
     *  enough for a caller that asks it about the photo it is currently drawing. */
    fun isHiddenUri(uri: String): Boolean {
        val parsed = runCatching { Uri.parse(uri) }.getOrNull() ?: return false
        val path = parsed.path ?: return false
        return parsed.scheme == "file" && path.startsWith(vaultPath)
    }

    /**
     * Copies [srcUri] (typically `content://media/...`) into private storage.
     * Returns the new `file://...` URI string on success, null on failure.
     *
     * Does NOT delete the source — callers must do that explicitly (so the user can confirm
     * the system trash dialog on Android 11+).
     *
     * A uri comes back only for a copy proven whole against what the source states it holds, checked
     * once the bytes are on the disk. The copy is what the caller then destroys the original for, and a
     * source that ends early without ever failing would otherwise leave a part of a photo as the only
     * one there is. A copy that cannot be proven is deleted and answered for as a failure, which is
     * what keeps the original: [HiddenVaultDecisions.deletableOriginals] deletes for the copies that
     * were written and for nothing else.
     */
    suspend fun store(
        srcUri: String,
        originalDisplayName: String? = null,
        mimeType: String? = null,
        /** Original capture-time of the source in epoch-ms. Encoded into the hidden file's
         *  name by [HiddenCaptureTime] so the vault keeps the date without depending on EXIF
         *  (which PNGs / WebPs / Screenshots / Videos rarely carry). When null, the grid reads
         *  the file's modified time and restore falls back to EXIF DateTimeOriginal, then "now". */
        captureTimeMs: Long? = null,
    ): String? =
        withContext(Dispatchers.IO) {
            // Non-reversible per-file token so a failure can be correlated in the diagnostics
            // log without recording the file name (which would leak what was being hidden).
            val ref = logRef(srcUri)
            val startedAt = SystemClock.elapsedRealtime()
            val src = runCatching { Uri.parse(srcUri) }.getOrNull() ?: run {
                eu.akoos.photos.util.SyncDiagnostics.log("hide $ref: skipped, bad source uri")
                return@withContext null
            }
            val ext = (mimeType ?: context.contentResolver.getType(src))
                ?.substringAfterLast('/')
                ?.let { if (it == "jpeg") "jpg" else it }
                ?.lowercase()
                ?: originalDisplayName?.substringAfterLast('.', "")
                ?: "bin"
            val dest = File(hiddenDir, "${UUID.randomUUID()}${HiddenCaptureTime.suffix(captureTimeMs)}.$ext")
            val expectedBytes = sourceLengthBytes(src)
            val copiedBytes = try {
                context.contentResolver.openInputStream(src)?.use { input ->
                    dest.outputStream().use { output ->
                        val copied = input.copyTo(output)
                        // Flushed and synced before anything is compared, so the check answers for what
                        // is on the disk rather than for what is still in a buffer.
                        output.flush()
                        output.fd.sync()
                        copied
                    }
                } ?: run {
                    eu.akoos.photos.util.SyncDiagnostics.log("hide $ref: skipped, source stream unavailable")
                    return@withContext null
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.w(TAG, "store: failed to copy source: ${e.message}")
                eu.akoos.photos.util.SyncDiagnostics.log(
                    "hide $ref: failed copy (${e.javaClass.simpleName})",
                )
                dest.delete()
                return@withContext null
            }
            if (!HiddenVaultDecisions.vaultCopyIsWhole(expectedBytes, copiedBytes, dest.length())) {
                Log.w(TAG, "store: the copy is not the whole source, refusing it")
                eu.akoos.photos.util.SyncDiagnostics.log("hide $ref: failed copy (incomplete)")
                dest.delete()
                return@withContext null
            }
            trace("store $ref: $copiedBytes bytes in ${SystemClock.elapsedRealtime() - startedAt}ms")
            Uri.fromFile(dest).toString()
        }

    /**
     * What the source at [src] states it holds, in bytes, or -1 when it states nothing.
     *
     * The descriptor is asked first: it answers for every scheme a source can arrive under and for the
     * bytes the stream itself will produce. The media row's own size column is the second chance, for a
     * provider that hands over a stream of undeclared length.
     */
    private fun sourceLengthBytes(src: Uri): Long {
        val fromDescriptor = runCatching {
            context.contentResolver.openAssetFileDescriptor(src, "r")?.use { it.length }
        }.getOrNull()
        if (fromDescriptor != null && fromDescriptor >= 0L) return fromDescriptor
        val fromRow = runCatching {
            context.contentResolver.query(
                src, arrayOf(MediaStore.MediaColumns.SIZE), null, null, null,
            )?.use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null }
        }.getOrNull()
        return fromRow?.takeIf { it >= 0L } ?: -1L
    }

    /**
     * Makes a vault file of [extension] carrying [captureTimeMs] and lets [write] fill it, returning
     * its `file://` uri or null when the bytes could not be written.
     *
     * The one way into the vault for content that has no source file to copy — the photo editor saving
     * a fresh copy of a vaulted photo, which holds edited pixels and nothing else. The name follows the
     * same private-code-plus-capture-time shape [store] gives every other vault file, so the grid, a
     * rename and a restore read it exactly as they read a hidden photo's.
     */
    suspend fun create(
        extension: String,
        captureTimeMs: Long?,
        write: (java.io.OutputStream) -> Unit,
    ): String? = withContext(Dispatchers.IO) {
        val ext = extension.trim('.').lowercase().ifBlank { "bin" }
        val dest = File(hiddenDir, "${UUID.randomUUID()}${HiddenCaptureTime.suffix(captureTimeMs)}.$ext")
        val uri = Uri.fromFile(dest).toString()
        try {
            dest.outputStream().use(write)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.w(TAG, "create: failed to write ${logRef(uri)}: ${e.message}")
            dest.delete()
            return@withContext null
        }
        trace("create ${logRef(uri)}: ${dest.length()} bytes")
        uri
    }

    /**
     * A photo the device took back: the uri it came back under, and which branch put it there.
     *
     * [viaOriginalPath] is true where the bytes were written straight into the folder the photo came
     * from and named by a media scan, the branch a location the media index does not manage needs;
     * false is the ordinary MediaStore insert. A caller reporting on a reveal cannot read that off
     * the uri, since a scan that answers hands back a media uri from either branch.
     *
     * [recordsSettled] is what the caller answered when it was handed the uri, and it decides whether
     * the vault copy was released: false leaves the copy on disk, so a photo whose records could not be
     * put right is still reachable from the hidden area rather than from nowhere.
     */
    data class RestoredFile(
        val uri: String,
        val viaOriginalPath: Boolean,
        val recordsSettled: Boolean,
    )

    /**
     * Restores a previously hidden file back to MediaStore so it becomes visible to other
     * gallery apps again. The hidden private file is removed once the MediaStore entry is
     * created. Returns the restored file, or null on failure.
     *
     * @param albumFolderName No longer used for routing. A restored file goes back to the
     *   RELATIVE_PATH it was hidden from when that path is a standard media root, and only falls
     *   back to the `Pictures/` (or `Movies/`) root when nothing is known about where it came
     *   from. Routing by album name instead surfaced a redundant phantom album in the device
     *   gallery, which is why the parameter stays unused rather than being removed.
     * @param onRestoredUri Run with the uri the file came back under, at the earliest moment that
     *   uri exists and before the file is published: while the MediaStore row is still marked
     *   pending, or straight after the media scan names a hand-written one. That is where a caller's
     *   record of the photo has to land — a restored file the sync pass reaches first reads as a
     *   brand-new local photo and is queued for an upload it does not need. It answers whether those
     *   records are now right, and the vault copy is deleted only when they are: the bytes are the
     *   one thing that can still bring the photo back, so they outlive a record that would not settle.
     */
    suspend fun restore(
        hiddenUri: String,
        originalDisplayName: String? = null,
        albumFolderName: String? = null,
        onRestoredUri: suspend (String) -> Boolean = { true },
    ): RestoredFile? =
        withContext(Dispatchers.IO) {
            val ref = logRef(hiddenUri)
            val startedAt = SystemClock.elapsedRealtime()
            val parsed = runCatching { Uri.parse(hiddenUri) }.getOrNull() ?: return@withContext null
            val srcFile = parsed.path?.let { File(it) }?.takeIf { it.exists() } ?: return@withContext null
            val ext = srcFile.extension.lowercase()
            val isVideo = ext in setOf("mp4", "m4v", "mov", "3gp", "ts", "mkv", "webm", "avi")
            val resolvedName = originalDisplayName ?: "recovered_${System.currentTimeMillis()}.$ext"
            val mime = when (ext) {
                "jpg", "jpeg" -> "image/jpeg"
                "png" -> "image/png"
                "heic", "heif" -> "image/heic"
                "webp" -> "image/webp"
                "gif" -> "image/gif"
                "mp4", "m4v" -> "video/mp4"
                "mov" -> "video/quicktime"
                "webm" -> "video/webm"
                "mkv" -> "video/x-matroska"
                "avi" -> "video/x-msvideo"
                else -> if (isVideo) "video/*" else "image/*"
            }
            val collection = if (isVideo)
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            else
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            val baseDir = if (isVideo) "Movies" else "Pictures"

            // Preserve the original capture time across hide → unhide. Without this, the
            // restored MediaStore row gets DATE_TAKEN = now, which makes the file look like
            // a brand-new photo: (1) the gallery groups it under today instead of the
            // original capture day; (2) ReconcileSyncStateUseCase's byNameAndDate match
            // fails (cloud has the original timestamp, local has "now"), the file is
            // classified LOCAL_ONLY, and it gets re-uploaded as a duplicate.
            //
            // Strategy: pull DateTimeOriginal out of the hidden file's EXIF. JPEG / HEIC
            // images that came through our backup pipeline carry the original timestamp
            // even after a hide/unhide round-trip. Videos rarely have an EXIF block, so their
            // capture time comes from the filename suffix; whatever value is found is written
            // back into the file's mvhd/EXIF below so the scanner keeps it.
            // 1. The capture time the file name records wins — that's what [store] stashed at hide
            //    time from its captureTimeMs param, covering PNG/WebP/Screenshot/Video where EXIF
            //    DateTimeOriginal is absent. The format lives in [HiddenCaptureTime].
            // 2. Fallback to EXIF for files imported into hidden through older code paths
            //    that didn't pass captureTimeMs (or were copied around outside this app).
            // 3. Both null = "now" (acceptable last-resort; user can manually fix DATE_TAKEN
            //    via a gallery editor if it matters to them).
            val captureTimeFromName: Long? = HiddenCaptureTime.parse(srcFile.name)
            val captureTimeMs: Long? = captureTimeFromName ?: if (!isVideo) runCatching {
                androidx.exifinterface.media.ExifInterface(srcFile.absolutePath)
                    .getAttribute(androidx.exifinterface.media.ExifInterface.TAG_DATETIME_ORIGINAL)
                    ?.let { dt ->
                        // EXIF DateTimeOriginal format: "yyyy:MM:dd HH:mm:ss" (local time, no zone).
                        ExifDateFormat.fromExifLocal(dt, ZoneId.systemDefault())
                    }
            }.getOrNull() else null

            // Write the recovered capture time into the file's embedded metadata (mvhd for video,
            // EXIF for image) BEFORE it is copied back, so MediaStore derives DATE_TAKEN from the
            // original instead of the restore-time file mtime on strict scanners. Mirrors the download
            // path; without it a hidden video (which usually has no EXIF) restores at "today".
            //
            // Both writes are conditional, and for the same reason: a hide is a raw byte copy, so a
            // reveal that rewrote metadata the file already carries would hand back a file that is not
            // the one it was given.
            if (captureTimeMs != null && captureTimeMs > 0L) {
                if (isVideo) {
                    Mp4CreationTime.stampIfChanged(srcFile, captureTimeMs)
                } else {
                    ExifHelper.stampDateTakenIfMissing(srcFile, captureTimeMs)
                }
            }

            // Restore the file to exactly where it came from. [albumFolderName] is the full original
            // RELATIVE_PATH stashed at hide time (e.g. "DCIM/Camera", "Pictures/Vacation", or an app's
            // "Android/media/com.whatsapp/Media/WhatsApp Images").
            val origPath = albumFolderName?.trim()?.trim('/')?.takeIf { it.isNotBlank() }
            val firstSeg = origPath?.substringBefore('/')
            // MediaStore can only create image/video rows under these roots; anything else (an app's
            // Android/media folder, Download/, …) has to be written by hand and then scanned in.
            val mediaStoreRoots = if (isVideo) setOf("DCIM", "Movies") else setOf("DCIM", "Pictures")
            val standardRoots = setOf(
                "DCIM", "Pictures", "Movies", "Music", "Download", "Documents",
                "Android", "Audiobooks", "Podcasts", "Ringtones", "Alarms", "Notifications", "Recordings",
            )

            // 1. Original location is a real path OUTSIDE MediaStore's image/video roots (e.g. the
            //    WhatsApp media folder) — write the bytes straight back there and index them. Needs
            //    all-files access; if that is not granted the write fails and we fall through to the
            //    Pictures/Movies root below.
            if (origPath != null && firstSeg != null && firstSeg in standardRoots && firstSeg !in mediaStoreRoots) {
                val restored = restoreToOriginalPath(srcFile, origPath, resolvedName, captureTimeMs)
                if (restored != null) {
                    val settled = onRestoredUri(restored)
                    if (settled) srcFile.delete()
                    trace("restore $ref: original path in ${SystemClock.elapsedRealtime() - startedAt}ms")
                    return@withContext RestoredFile(restored, viaOriginalPath = true, recordsSettled = settled)
                }
            }

            // 2. MediaStore path. A standard image/video dir (DCIM/Pictures/Movies) is restored in
            //    place; an older stripped bare folder name lands under Pictures/<name>; nothing known
            //    falls back to the Pictures/ (or Movies/) root.
            val relPath = when {
                origPath == null -> baseDir
                firstSeg in mediaStoreRoots -> origPath
                firstSeg in standardRoots -> baseDir
                else -> "$baseDir/$origPath"
            }

            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, resolvedName)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.MediaColumns.RELATIVE_PATH, relPath)
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                if (captureTimeMs != null && captureTimeMs > 0L) {
                    // Both DATE_TAKEN (ms) and DATE_MODIFIED (s) reflect the original
                    // capture moment — matches what DownloadPhotosUseCase writes so the
                    // unhide produces the same row shape as a fresh cloud download would.
                    put(MediaStore.MediaColumns.DATE_TAKEN, captureTimeMs)
                    put(MediaStore.MediaColumns.DATE_MODIFIED, captureTimeMs / 1000L)
                }
            }
            // Some MediaProvider builds reject an uncommon video mime (notably video/x-matroska for
            // .mkv) by THROWING IllegalArgumentException instead of returning null. Retry under the
            // generic video/* (or image/*) mime so the file still lands, and skip gracefully rather
            // than crashing the unhide if even that is refused.
            val target = try {
                context.contentResolver.insert(collection, values)
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "restore: insert rejected mime '$mime', retrying generic: ${e.message}")
                values.put(MediaStore.MediaColumns.MIME_TYPE, if (isVideo) "video/*" else "image/*")
                runCatching { context.contentResolver.insert(collection, values) }.getOrNull()
            } ?: return@withContext null
            try {
                context.contentResolver.openOutputStream(target)?.use { out ->
                    srcFile.inputStream().use { it.copyTo(out) }
                } ?: return@withContext null
                // Between the whole bytes landing and the row being published, so what the caller
                // records for the photo is already true when the change notification goes out and
                // the sync pass it wakes comes to look.
                val settled = onRestoredUri(target.toString())
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val finalize = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
                    context.contentResolver.update(target, finalize, null, null)
                }
                recordDateOverrideIfRefused(target, captureTimeMs)
                // The private copy goes only once the records naming the photo are right. While they
                // are not, it is the one copy the hidden area can still offer the photo from.
                if (settled) srcFile.delete()
                trace("restore $ref: media store in ${SystemClock.elapsedRealtime() - startedAt}ms")
                RestoredFile(target.toString(), viaOriginalPath = false, recordsSettled = settled)
            } catch (e: Exception) {
                Log.w(TAG, "restore: failed for $ref: ${e.message}")
                // Roll back the half-created MediaStore entry.
                runCatching { context.contentResolver.delete(target, null, null) }
                null
            }
        }

    /**
     * Records the capture date for a restored row the provider refused to date, so the photo comes back
     * on the day it was taken rather than on the day it was revealed.
     *
     * A PNG or a WebP is the case that needs it: MediaStore derives DATE_TAKEN from a JPEG/HEIF EXIF
     * block and a video's mvhd and from nothing else, so for those two it drops the column write and
     * leaves it at 0 however the value arrives. The override map is then the ONLY place the date lives,
     * exactly as it is for a download of the same photo — and a reveal that skipped it would hand back a
     * photo dated today. Reading the column back rather than assuming keeps a JPEG, where the write
     * sticks, from ever putting an entry in the map.
     */
    private suspend fun recordDateOverrideIfRefused(target: Uri, captureTimeMs: Long?) {
        if (captureTimeMs == null || captureTimeMs <= 0L) return
        // One read answers both halves: DATE_TAKEN says whether an entry is needed, DATE_MODIFIED
        // anchors it to the row it was written from so a later edit elsewhere voids it.
        val stored = runCatching {
            context.contentResolver.query(
                target,
                arrayOf(MediaStore.MediaColumns.DATE_TAKEN, MediaStore.MediaColumns.DATE_MODIFIED),
                null, null, null,
            )?.use { c -> if (c.moveToFirst()) c.getLong(0) to c.getLong(1) else null }
        }.getOrNull()
        if (!CaptureDateOverride.shouldRecord(captureTimeMs, stored?.first ?: 0L)) return
        try {
            context.settingsDataStore.edit { prefs ->
                val current = prefs[SettingsKeys.DOWNLOAD_DATE_OVERRIDES] ?: emptySet()
                prefs[SettingsKeys.DOWNLOAD_DATE_OVERRIDES] =
                    current + CaptureDateOverride.encode(target.toString(), captureTimeMs, stored?.second)
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.w(TAG, "restore: capture-date override not recorded: ${e.message}")
        }
    }

    /**
     * Writes a restored file straight to its original [relPath] under primary external storage and
     * indexes it with the media scanner, carrying the name the file actually landed under. Returns null
     * on any failure (e.g. all-files access not granted) so the caller can fall back to a MediaStore
     * location.
     *
     * The uri it answers with is the one the scan mints, because that is the uri the gallery lists the
     * photo under and therefore the one key that everything a reveal puts back on the photo — its
     * heart, its categories, its place in an album queue, its cloud pairing — can be written under. The
     * file's own `file://` uri names the same bytes and is what comes back when the scan stays silent:
     * the photo is on the device either way, and a reveal left waiting on the scanner would be the
     * worse answer.
     */
    private suspend fun restoreToOriginalPath(
        srcFile: File,
        relPath: String,
        name: String,
        captureTimeMs: Long?,
    ): String? {
        val dest = runCatching {
            @Suppress("DEPRECATION")
            val root = android.os.Environment.getExternalStorageDirectory()
            val destDir = File(root, relPath).apply { mkdirs() }
            // The stream below truncates whatever it opens, so a folder that has since gained a file
            // of this name would lose it to a photo it has nothing to do with. MediaProvider
            // uniquifies on the branch it manages; this branch has to do it itself.
            val written = File(
                destDir,
                HiddenVaultDecisions.uniqueRestoreName(name) { candidate -> File(destDir, candidate).exists() },
            )
            srcFile.inputStream().use { input -> written.outputStream().use { input.copyTo(it) } }
            if (captureTimeMs != null && captureTimeMs > 0L) written.setLastModified(captureTimeMs)
            written
        }.getOrNull() ?: return null
        return scannedUriOf(dest) ?: Uri.fromFile(dest).toString()
    }

    /**
     * The media uri a scan of [file] mints, or null when the scan answers with none or does not answer
     * within [SCAN_ANSWER_TIMEOUT_MS].
     *
     * The scan has to happen regardless — a file written by hand is invisible to every gallery on the
     * device until the index knows of it — and its answer is waited for because a reveal has records to
     * key on the uri it produces. The wait is bounded: a folder reveal walks thousands of photos, so a
     * scanner that never replies costs one wait rather than the whole reveal.
     */
    private suspend fun scannedUriOf(file: File): String? =
        withTimeoutOrNull(SCAN_ANSWER_TIMEOUT_MS) {
            suspendCancellableCoroutine<String?> { cont ->
                android.media.MediaScannerConnection.scanFile(
                    context,
                    arrayOf(file.absolutePath),
                    null,
                ) { _, uri ->
                    if (cont.isActive) cont.resume(uri?.toString())
                }
            }
        }

    /** Hard-deletes a hidden file from app-private storage. */
    fun delete(hiddenUri: String): Boolean {
        val parsed = runCatching { Uri.parse(hiddenUri) }.getOrNull() ?: return false
        val file = parsed.path?.let { File(it) } ?: return false
        return file.exists() && file.delete()
    }

    /**
     * Rename a hidden file on disk. Returns the new file:// URI string on success.
     *
     * Preserves the capture time [store] embeds in the name (see [HiddenCaptureTime]) so the grid
     * and a later [restore] still read the original date — only the stem gets the user's name. An
     * older entry whose name records no capture time simply keeps its extension.
     *
     * The user-supplied [newName] is sanitised: filesystem-illegal characters become `_`,
     * extension stripped (we always reuse the original file's extension to keep the MIME
     * lookup in [LocalMediaRepositoryImpl.queryByUri] working).
     */
    suspend fun rename(hiddenUri: String, newName: String): String? = withContext(Dispatchers.IO) {
        val srcFile = fileOf(hiddenUri) ?: return@withContext null
        val ext = srcFile.extension
        // Re-encode the capture time the OLD name records onto the new one. Without this the renamed
        // file loses the vault's only record of its date, and it reads as "now" everywhere after.
        val captureSuffix = HiddenCaptureTime.suffix(HiddenCaptureTime.parse(srcFile.name))
        val userStem = HiddenVaultRecords.sanitizedStem(newName)
        val newFileName = if (ext.isNotEmpty()) "$userStem$captureSuffix.$ext" else "$userStem$captureSuffix"
        moveTo(srcFile, newFileName)
    }

    /**
     * Rewrites the capture time [hiddenUri]'s name records to [captureTimeMs], returning the new
     * `file://` uri. The stem and the extension are untouched.
     *
     * The counterpart of a MediaStore DATE_TAKEN update for a photo that has no row: the name is where
     * the vault keeps a date (see [HiddenCaptureTime]) and it outranks the file's own EXIF everywhere
     * the vault reads one, so an edited date that did not reach the name would not reach the grid or a
     * later restore either.
     */
    suspend fun restampCaptureTime(hiddenUri: String, captureTimeMs: Long): String? =
        withContext(Dispatchers.IO) {
            val srcFile = fileOf(hiddenUri) ?: return@withContext null
            val restamped = HiddenCaptureTime.restamp(srcFile.name, captureTimeMs)
            if (restamped == srcFile.name) return@withContext hiddenUri
            moveTo(srcFile, restamped)
        }

    /**
     * Duplicates the vault file [hiddenUri] into a second vault file, returning its `file://` uri.
     *
     * Serves "save as copy" on a hidden photo, which has to stay hidden: a copy written anywhere else
     * would be a plain visible file holding exactly the bytes the user asked the vault to keep. The
     * copy takes a private code of its own and carries the source's capture time, so it reads as the
     * ordinary vault file it is; the name the user typed is a record, not a file name.
     */
    suspend fun duplicate(hiddenUri: String): String? = withContext(Dispatchers.IO) {
        val srcFile = fileOf(hiddenUri) ?: return@withContext null
        create(srcFile.extension, HiddenCaptureTime.parse(srcFile.name)) { out ->
            srcFile.inputStream().use { it.copyTo(out) }
        }
    }

    /** The existing vault file [hiddenUri] names, or null when it names none. */
    private fun fileOf(hiddenUri: String): File? {
        val parsed = runCatching { Uri.parse(hiddenUri) }.getOrNull() ?: return null
        return parsed.path?.let { File(it) }?.takeIf { it.exists() }
    }

    /**
     * Moves [srcFile] to [newFileName] beside itself, returning the new `file://` uri, or null when
     * ANOTHER file of that name is already there or the move is refused.
     *
     * A name that resolves to the file itself is a move of no distance and answers with the uri the
     * file already has. The user asking for the name the photo already carries is asking for the state
     * it is already in, and the file sitting there is what makes that true rather than a collision —
     * the same answer [restampCaptureTime] gives when the date it is asked for is the one recorded.
     */
    private fun moveTo(srcFile: File, newFileName: String): String? {
        val dest = File(srcFile.parentFile, newFileName)
        if (dest.absolutePath == srcFile.absolutePath) return Uri.fromFile(srcFile).toString()
        val ref = logRef(Uri.fromFile(srcFile).toString())
        if (dest.exists()) {
            Log.w(TAG, "rename: target already exists, refusing")
            return null
        }
        if (!srcFile.renameTo(dest)) {
            Log.w(TAG, "rename: renameTo() was refused for $ref")
            return null
        }
        val moved = Uri.fromFile(dest).toString()
        trace("rename $ref: now ${logRef(moved)}")
        return moved
    }

    /**
     * Verbose per-photo trace, debug builds only, so a debug run reads as a full walk of the vault
     * while a release build costs nothing and stays quiet.
     *
     * A debug logcat is pasted into issues exactly as the shareable bundle is, so [message] carries
     * the same hashed refs and never a name or a path.
     */
    private fun trace(message: String) {
        if (BuildConfig.DEBUG) Log.d(TAG, message)
    }

    private companion object {
        const val TAG = "HiddenStorage"

        /** How long a reveal waits for the media scan to name the file it has just written back. */
        const val SCAN_ANSWER_TIMEOUT_MS = 5_000L

        /** Stable, non-reversible 6-char token from a source URI for privacy-safe diagnostics
         *  correlation — the vault's one short-token form, shared with every other line it writes. */
        fun logRef(uri: String): String = HiddenVaultDiagnostics.ref(uri)
    }
}
