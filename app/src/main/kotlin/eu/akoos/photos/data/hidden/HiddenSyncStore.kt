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

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.akoos.photos.data.preferences.SettingsKeys
import eu.akoos.photos.data.preferences.settingsDataStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * This device's hidden photos and albums as the shared backup reads and writes them, and the way another device's
 * changes are carried out here. A hidden photo is a cloud-only photo of [SettingsKeys.HIDDEN_CLOUD_PHOTO_IDS], the
 * Drive copy of a photo in the vault, or a photo another device hid that waits to be moved into the vault; a
 * hidden album is one of [SettingsKeys.HIDDEN_ALBUM_IDS]. Times come from [HiddenSyncTimes]; a hide kept from
 * before the times were recorded reads as 0.
 *
 * Another device's change is carried out the way this phone does it itself. A photo hidden there waits for the
 * timeline ([SettingsKeys.HIDDEN_REMOTE_HIDE_QUEUE]), which alone knows whether this phone also holds it as a
 * device file: the timeline then runs the ordinary hide, by its link id for a cloud-only photo and into the vault
 * behind the system's delete prompt for one with a device file. An album goes by its set, and a photo shown again
 * by its set or through the ordinary reveal of the vault. Every change keeps the other device's time, so it is not
 * sent back as a new one.
 */
@Singleton
class HiddenSyncStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val restorer: HiddenVaultRestorer,
) {

    /** This device's hidden list with the times of each hide and unhide. */
    suspend fun load(): HiddenSyncState = stateOf(context.settingsDataStore.data.first())

    /**
     * Carries out [changes], the later changes another device's copy brought. Returns how many photos wait for the
     * timeline to hide them.
     */
    suspend fun apply(changes: HiddenCopyMerge.Changes): Int {
        if (changes.isEmpty) return 0
        val prefs = context.settingsDataStore.data.first()
        val cloudHidden = prefs[SettingsKeys.HIDDEN_CLOUD_PHOTO_IDS] ?: emptySet()
        val vaultUriByLink = vaultUriByLink(prefs)
        val queue = HiddenSyncTimes.remoteHideQueue(prefs).toMutableMap()

        val timeOnly = HashMap<String, Long>()
        for ((link, at) in changes.photosHidden) {
            if (link in cloudHidden || link in vaultUriByLink) timeOnly[link] = at else queue[link] = at
        }

        val shownTimeOnly = HashMap<String, Long>()
        for ((link, at) in changes.photosShown) {
            queue.remove(link)
            when {
                link in cloudHidden -> HiddenCloudPhotos.reveal(context, listOf(link), at)
                link in vaultUriByLink -> reveal(vaultUriByLink.getValue(link), at)
                else -> shownTimeOnly[link] = at
            }
        }
        context.settingsDataStore.edit { edit ->
            HiddenSyncTimes.setRemoteHideQueue(edit, queue)
            for ((link, at) in timeOnly) HiddenSyncTimes.photosHidden(edit, listOf(link), at)
            for ((link, at) in shownTimeOnly) HiddenSyncTimes.photosShown(edit, listOf(link), at)
        }
        for ((link, at) in changes.albumsHidden) HiddenCloudPhotos.hideAlbums(context, listOf(link), at)
        for ((link, at) in changes.albumsShown) HiddenCloudPhotos.revealAlbums(context, listOf(link), at)
        return queue.size
    }

    /** Brings a vaulted photo back through the ordinary reveal, as of the other device's unhide. */
    private suspend fun reveal(vaultUri: String, at: Long) {
        try {
            if (!restorer.restorePhoto(vaultUri, shownAt = at)) Log.w(TAG, "a vaulted photo did not come back")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "vault reveal failed: ${e.javaClass.simpleName}")
        }
    }

    companion object {
        private const val TAG = "HiddenSyncStore"

        /** The vault file of each Drive link id the vault recorded. */
        internal fun vaultUriByLink(prefs: Preferences): Map<String, String> =
            (prefs[SettingsKeys.HIDDEN_URI_CLOUD_ID_MAP] ?: emptySet()).mapNotNull { entry ->
                val link = entry.substringAfter('|', "")
                if (link.isBlank()) null else link to entry.substringBefore('|')
            }.toMap()

        /** The hidden list [prefs] holds, with times. Pure over the preferences, so it is unit-tested directly. */
        internal fun stateOf(prefs: Preferences): HiddenSyncState {
            val hideTimes = HiddenSyncTimes.parse(prefs[SettingsKeys.HIDDEN_PHOTO_HIDE_TIMES])
            val showTimes = HiddenSyncTimes.parse(prefs[SettingsKeys.HIDDEN_PHOTO_SHOW_TIMES])
            val queue = HiddenSyncTimes.remoteHideQueue(prefs)
            val hiddenPhotos = (prefs[SettingsKeys.HIDDEN_CLOUD_PHOTO_IDS] ?: emptySet()) +
                vaultUriByLink(prefs).keys + queue.keys
            val photos = HiddenKindState(
                hidden = hiddenPhotos.associateWithTo(HashMap()) { queue[it] ?: hideTimes[it] ?: 0L },
                shown = showTimes.filterKeys { it !in hiddenPhotos }.toMap(HashMap()),
            )
            val albumHide = HiddenSyncTimes.parse(prefs[SettingsKeys.HIDDEN_ALBUM_HIDE_TIMES])
            val albumShow = HiddenSyncTimes.parse(prefs[SettingsKeys.HIDDEN_ALBUM_SHOW_TIMES])
            val hiddenAlbums = prefs[SettingsKeys.HIDDEN_ALBUM_IDS] ?: emptySet()
            val albums = HiddenKindState(
                hidden = hiddenAlbums.associateWithTo(HashMap()) { albumHide[it] ?: 0L },
                shown = albumShow.filterKeys { it !in hiddenAlbums }.toMap(HashMap()),
            )
            return HiddenSyncState(photos, albums)
        }
    }
}
