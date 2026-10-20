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
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Puts a finished vault zip where the user can reach it: the Downloads folder, which a gallery app
 * does not index, so the photos inside stay out of it.
 */
@Singleton
class VaultZipPublisher @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    /** Copies [source] to Downloads as [displayName]. True only when the copy has the source's length and is no longer pending. */
    fun publish(source: File, displayName: String): Boolean = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) publishViaMediaStore(source, displayName)
        else publishToPublicDirectory(source, displayName)
    }.getOrDefault(false)

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun publishViaMediaStore(source: File, displayName: String): Boolean {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, "application/zip")
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return false
        var copied = -1L
        try {
            resolver.openOutputStream(uri)?.use { out ->
                source.inputStream().use { input -> copied = input.copyTo(out) }
            }
            if (copied != source.length()) {
                resolver.delete(uri, null, null)
                return false
            }
            val done = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
            if (resolver.update(uri, done, null, null) != 1) {
                resolver.delete(uri, null, null)
                return false
            }
            return true
        } catch (e: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            throw e
        }
    }

    @Suppress("DEPRECATION")
    private fun publishToPublicDirectory(source: File, displayName: String): Boolean {
        val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        if (!dir.exists() && !dir.mkdirs()) return false
        val target = File(dir, displayName)
        source.copyTo(target, overwrite = false)
        if (target.length() != source.length()) {
            target.delete()
            return false
        }
        return true
    }
}
