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

package eu.akoos.photos.data.offline

import android.content.Context
import androidx.datastore.preferences.core.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.akoos.photos.data.preferences.SettingsKeys
import eu.akoos.photos.data.preferences.settingsDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persistent store for the full-resolution blobs of photos the user has pinned for offline
 * access. Blobs live in `filesDir/offline/<linkId>.<ext>` — app-private (so the device
 * gallery / other apps never see them) and outside cacheDir (so a system cache clear can't
 * evict a pinned photo).
 *
 * The on-disk filename uses the SAME `<linkId>.<ext>` stem as the full-res cache
 * ([PhotoDownloadService.fullResFile]) so the two stores agree on naming and a pin can be
 * fed straight from the cache.
 */
@Singleton
class OfflineStorageManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val offlineDir: File
        get() = File(context.filesDir, "offline").also { it.mkdirs() }

    /** Canonical blob path for [linkId] with extension [ext] — matches the full-res cache stem. */
    fun blobFile(linkId: String, ext: String): File = File(offlineDir, "$linkId.$ext")

    /**
     * The existing non-empty offline blob for [linkId] regardless of extension, or null when
     * nothing is pinned for it. Matches on the `<linkId>.` filename prefix so a blob stored
     * under any mime extension is found.
     */
    fun findBlob(linkId: String): File? {
        val prefix = "$linkId."
        return offlineDir.listFiles()
            ?.firstOrNull { it.isFile && !it.name.endsWith(".tmp") && it.name.startsWith(prefix) && it.length() > 0 }
    }

    /** True when a non-empty offline blob exists for [linkId]. */
    fun isOffline(linkId: String): Boolean = findBlob(linkId) != null

    /**
     * Copies [source] into offline storage under `<linkId>.<source.extension>` and returns the
     * stored file. Writes to a `.tmp` sibling first, then atomically renames, so a crash
     * mid-copy never leaves a truncated blob that [findBlob] would treat as complete.
     */
    suspend fun store(linkId: String, source: File): File = withContext(Dispatchers.IO) {
        val ext = source.extension.ifBlank { "bin" }
        val dest = blobFile(linkId, ext)
        val tmp = File(offlineDir, "$linkId.$ext.tmp")
        if (tmp.exists()) tmp.delete()
        source.inputStream().use { input ->
            tmp.outputStream().use { output -> input.copyTo(output) }
        }
        // Drop any prior blob for this linkId — including one stored under a different extension —
        // so a re-pin never leaves a stale duplicate that would double-count or shadow the new
        // blob. The just-written .tmp is excluded so the rename below still has its source.
        offlineDir.listFiles()
            ?.filter { it.isFile && it.name.startsWith("$linkId.") && it.absolutePath != tmp.absolutePath }
            ?.forEach { it.delete() }
        if (!tmp.renameTo(dest)) {
            tmp.delete()
            error("Failed to publish offline blob for $linkId")
        }
        dest
    }

    /** Removes the offline blob (and any leftover `.tmp`) for [linkId]. */
    fun delete(linkId: String) {
        val prefix = "$linkId."
        offlineDir.listFiles()
            ?.filter { it.isFile && it.name.startsWith(prefix) }
            ?.forEach { runCatching { it.delete() } }
    }

    /** Total bytes occupied by every offline blob. */
    fun computeSizeBytes(): Long =
        offlineDir.listFiles()
            ?.filter { it.isFile }
            ?.sumOf { runCatching { it.length() }.getOrDefault(0L) }
            ?: 0L

    /** Drops every offline blob. */
    fun clearAll() {
        offlineDir.deleteRecursively()
    }

    /**
     * Drops every offline blob AND clears the pin set, so a wiped store never leaves dangling pins
     * that point at bytes which are no longer here. Used on sign-out, where both the blobs and the
     * pin list belong to the account that is going away.
     */
    suspend fun clearAllAndUnpin() {
        clearAll()
        runCatching { context.settingsDataStore.edit { it.remove(SettingsKeys.OFFLINE_PIN_IDS) } }
    }
}
