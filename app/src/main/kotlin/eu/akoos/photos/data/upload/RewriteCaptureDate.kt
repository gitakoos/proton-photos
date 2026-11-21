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

package eu.akoos.photos.data.upload

import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import androidx.datastore.preferences.core.edit
import androidx.exifinterface.media.ExifInterface
import eu.akoos.photos.data.preferences.SettingsKeys
import eu.akoos.photos.data.preferences.settingsDataStore
import eu.akoos.photos.data.repository.DeviceFileStamps
import eu.akoos.photos.util.CaptureDateOverride
import eu.akoos.photos.util.ExifDateFormat
import eu.akoos.photos.util.ExifHelper
import java.time.ZoneId

/**
 * Keeps a gallery photo's capture date through an in-place rewrite of its file (see
 * [CaptureDateOverride.keptAcrossRewrite] for why the media scan would drop it). [before] is read ahead of
 * the write and [after] once it landed; [after] records the date as an override stamped with the modified
 * time the write left, so the next scan reads it as current. Blocking, as every caller of
 * [LocalOverwriteGuard.run] is. Neither step ever fails the rewrite.
 */
internal object RewriteCaptureDate {

    private const val TAG = "RewriteCaptureDate"

    /** What the row and the file said before the rewrite. */
    class Before(
        val embedded: EmbeddedDate?,
        val rawDateTakenMs: Long,
        val modifiedSeconds: Long,
        val fileModifiedSeconds: Long?,
    )

    /** The date fields inside the file, compared as written. */
    data class EmbeddedDate(val original: String?, val dateTime: String?, val offsetOriginal: String?, val offset: String?) {
        /** The key [CaptureDateOverride.keptAcrossRewrite] compares. */
        val key: String get() = "$original|$dateTime|$offsetOriginal|$offset"

        /** The instant the file records, read in the device zone when it carries no offset. */
        fun captureMs(): Long? = (original ?: dateTime)?.let {
            ExifDateFormat.fromExif(it, offsetOriginal ?: offset, ZoneId.systemDefault())
        }

        /** Whether the media scan reads an exact instant here; it looks at OffsetTimeOriginal only. */
        val scanSeesOffset: Boolean get() = ExifDateFormat.hasUsableOffset(offsetOriginal)
    }

    /** True for a MediaStore row, the only kind of file the timeline dates from its columns. */
    fun applies(uri: Uri): Boolean = uri.scheme == "content" && uri.authority == MediaStore.AUTHORITY

    fun before(context: Context, uri: String): Before? = runCatching {
        val parsed = Uri.parse(uri)
        val projection = arrayOf(MediaStore.MediaColumns.DATE_TAKEN, MediaStore.MediaColumns.DATE_MODIFIED)
        val columns = context.contentResolver.query(parsed, projection, null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            val taken = if (cursor.isNull(0)) 0L else cursor.getLong(0)
            val modified = if (cursor.isNull(1)) 0L else cursor.getLong(1)
            taken to modified
        } ?: return@runCatching null
        Before(
            embedded = embeddedDate(context, uri),
            rawDateTakenMs = columns.first,
            modifiedSeconds = columns.second,
            fileModifiedSeconds = runCatching { DeviceFileStamps.read(context, uri)?.modifiedSec }.getOrNull(),
        )
    }.onFailure { Log.w(TAG, "could not read the date of $uri before its rewrite: ${it.message}") }.getOrNull()

    fun after(context: Context, uri: String, before: Before) {
        runCatching {
            val embedded = embeddedDate(context, uri)
            val modifiedNow = runCatching { DeviceFileStamps.read(context, uri)?.modifiedSec }.getOrNull()
            var wrote = false
            kotlinx.coroutines.runBlocking {
                context.settingsDataStore.edit { prefs ->
                    val current = prefs[SettingsKeys.DOWNLOAD_DATE_OVERRIDES] ?: emptySet()
                    val kept = CaptureDateOverride.keptAcrossRewrite(
                        embeddedBefore = before.embedded?.key,
                        embeddedAfter = embedded?.key,
                        embeddedMs = embedded?.captureMs(),
                        embeddedHasOffset = embedded?.scanSeesOffset == true,
                        rawDateTakenMs = before.rawDateTakenMs,
                        entry = CaptureDateOverride.parse(current)[uri],
                        modifiedSecondsBefore = before.modifiedSeconds,
                        fileModifiedSecondsBefore = before.fileModifiedSeconds,
                    ) ?: return@edit
                    CaptureDateOverride.upsert(current, mapOf(uri to kept), mapOf(uri to modifiedNow))?.let {
                        if (it != current) {
                            prefs[SettingsKeys.DOWNLOAD_DATE_OVERRIDES] = it
                            wrote = true
                        }
                    }
                }
            }
            // The scan the write set off may have re-read the gallery before this entry landed.
            if (wrote) context.contentResolver.notifyChange(Uri.parse(uri), null)
        }.onFailure { Log.w(TAG, "could not keep the date of $uri after its rewrite: ${it.message}") }
    }

    /** The date fields inside the file at [uri], null when it holds no datetime. */
    private fun embeddedDate(context: Context, uri: String): EmbeddedDate? {
        val exif = ExifHelper.readExifSnapshot(context, uri) ?: return null
        val date = EmbeddedDate(
            original = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL),
            dateTime = exif.getAttribute(ExifInterface.TAG_DATETIME),
            offsetOriginal = exif.getAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL),
            offset = exif.getAttribute(ExifInterface.TAG_OFFSET_TIME),
        )
        return date.takeIf { it.original != null || it.dateTime != null }
    }
}
