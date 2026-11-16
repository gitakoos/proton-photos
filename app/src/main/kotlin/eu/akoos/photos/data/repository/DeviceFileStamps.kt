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

package eu.akoos.photos.data.repository

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import eu.akoos.photos.data.localcrypto.VaultFiles
import eu.akoos.photos.domain.entity.DeviceFileStamp
import java.io.File

/**
 * A device file's size and the time its bytes last changed, read where free-up and the app's own writes
 * need them ([eu.akoos.photos.domain.repository.LocalMediaRepository.fileStamp]). Blocking: call off the
 * main thread.
 */
object DeviceFileStamps {

    /** [uri]'s stamp, or null when nothing is there to read. [stat] reads an open file's own size and
     *  modified time in seconds. */
    fun read(
        context: Context,
        uri: String,
        stat: (ParcelFileDescriptor) -> Pair<Long, Long> = { pfd ->
            android.system.Os.fstat(pfd.fileDescriptor).let { it.st_size to it.st_mtime }
        },
    ): DeviceFileStamp? {
        val parsed = Uri.parse(uri)
        if (parsed.scheme == "file") {
            val file = parsed.path?.let { File(it) }?.takeIf { it.exists() } ?: return null
            return DeviceFileStamp(VaultFiles.plainLength(file), file.lastModified() / 1000L)
        }
        val column = try {
            val projection = arrayOf(MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.DATE_MODIFIED)
            context.contentResolver.query(parsed, projection, null, null, null)?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                DeviceFileStamp(
                    sizeBytes = cursor.getLong(0),
                    modifiedSec = if (cursor.isNull(1)) 0L else cursor.getLong(1),
                )
            }
        } catch (e: Exception) {
            null
        } ?: return null
        val own = try {
            context.contentResolver.openFileDescriptor(parsed, "r")?.use(stat)
        } catch (e: Exception) {
            null
        }
        return combine(column, own?.first, own?.second)
    }

    /**
     * The stamp from the media library's [column] and the file's own [statSize] and [statModifiedSec],
     * null when the file could not be read. The column lags a write until the next scan, and a download
     * sets it to the capture time, so the later of the two modified times is when the bytes last changed;
     * the file's own size is the one its bytes have now. Pure.
     */
    internal fun combine(column: DeviceFileStamp, statSize: Long?, statModifiedSec: Long?): DeviceFileStamp =
        DeviceFileStamp(
            sizeBytes = statSize?.takeIf { it > 0L } ?: column.sizeBytes,
            modifiedSec = maxOf(column.modifiedSec, statModifiedSec ?: 0L),
        )
}
