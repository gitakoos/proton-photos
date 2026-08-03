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

package eu.akoos.photos.data.updater

import android.content.Context
import androidx.datastore.preferences.core.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.akoos.photos.data.preferences.SettingsKeys
import eu.akoos.photos.data.preferences.settingsDataStore
import kotlinx.coroutines.flow.first
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Durable record of an APK the background check has already fetched. The orchestrator holds its
 * staged file in memory, which the process that downloaded it does not share with the process that
 * later shows the prompt, so the version and the path are written to DataStore instead.
 *
 * Every read validates before handing the file back: the archive has to exist, carry bytes, and be
 * recorded against the version currently on offer. A record that fails any of those is dropped
 * along with the files behind it, so the cache directory holds at most the one archive still worth
 * installing.
 */
@Singleton
class StagedUpdateStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    /** Notes [file] as the staged archive for [versionName]. */
    suspend fun record(versionName: String, file: File) {
        context.settingsDataStore.edit { prefs ->
            prefs[SettingsKeys.UPDATE_STAGED_VERSION] = versionName
            prefs[SettingsKeys.UPDATE_STAGED_FILE] = file.absolutePath
        }
    }

    /**
     * The staged archive for [versionName], or null when nothing usable is staged for it. A
     * mismatch, a missing file and an empty file all clear the record and sweep the directory, so
     * an archive for a superseded version cannot sit in the cache indefinitely.
     */
    suspend fun claimFor(versionName: String?): File? {
        val prefs = runCatching { context.settingsDataStore.data.first() }.getOrNull()
        val stagedVersion = prefs?.get(SettingsKeys.UPDATE_STAGED_VERSION)
        val stagedFile = prefs?.get(SettingsKeys.UPDATE_STAGED_FILE)
            ?.let(::File)
            ?.takeIf { it.isFile && it.length() > 0L }
        if (stagedFile == null || !stagedUpdateMatches(stagedVersion, versionName)) {
            discard()
            return null
        }
        // Anything else in the directory belongs to a version already superseded.
        deleteStagedFiles(keep = stagedFile)
        return stagedFile
    }

    /** Drops the record and the archives behind it. */
    suspend fun discard() {
        deleteStagedFiles(keep = null)
        runCatching {
            context.settingsDataStore.edit { prefs ->
                prefs.remove(SettingsKeys.UPDATE_STAGED_VERSION)
                prefs.remove(SettingsKeys.UPDATE_STAGED_FILE)
            }
        }
    }

    /**
     * Deletes every finished archive in the updates directory except [keep]. Partial writes are
     * left alone: a `.part` file is a download in flight, and its owner removes it either way.
     */
    private fun deleteStagedFiles(keep: File?) {
        runCatching {
            File(context.cacheDir, UPDATES_DIR).listFiles()?.forEach { candidate ->
                if (candidate.name.endsWith(PART_SUFFIX)) return@forEach
                if (keep != null && candidate.absolutePath == keep.absolutePath) return@forEach
                candidate.delete()
            }
        }
    }

    private companion object {
        /** Matches the directory [UpdateDownloader] streams into. */
        const val UPDATES_DIR = "updates"
        const val PART_SUFFIX = ".part"
    }
}
