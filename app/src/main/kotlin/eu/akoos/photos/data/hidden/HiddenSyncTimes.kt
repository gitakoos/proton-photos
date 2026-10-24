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

import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import eu.akoos.photos.data.preferences.SettingsKeys

/**
 * When each photo and album was hidden or shown again, kept beside the hidden sets so the shared backup can put
 * this device's hidden list together with another's, the later change winning. A link id sits in the hide times
 * or in the show times, never in both: hiding clears its show time and showing clears its hide time. Stored as
 * `"linkId|millis"` strings, the flatten every per-id map here uses. The helpers write inside the caller's
 * DataStore edit, so a hide and its time land together.
 */
object HiddenSyncTimes {

    /** The `"key|millis"` entries of [entries] as a map; an entry that does not parse is skipped. */
    fun parse(entries: Set<String>?): Map<String, Long> {
        if (entries.isNullOrEmpty()) return emptyMap()
        val out = HashMap<String, Long>(entries.size)
        for (entry in entries) {
            val bar = entry.lastIndexOf('|')
            if (bar <= 0) continue
            val at = entry.substring(bar + 1).toLongOrNull() ?: continue
            out[entry.substring(0, bar)] = at
        }
        return out
    }

    /** [times] as `"key|millis"` entries. */
    fun format(times: Map<String, Long>): Set<String> = times.mapTo(HashSet(times.size)) { (key, at) -> "$key|$at" }

    /** Records [linkIds] as photos hidden at [at]. */
    fun photosHidden(prefs: MutablePreferences, linkIds: Collection<String>, at: Long) =
        record(prefs, SettingsKeys.HIDDEN_PHOTO_HIDE_TIMES, SettingsKeys.HIDDEN_PHOTO_SHOW_TIMES, linkIds, at)

    /** Records [linkIds] as photos shown again at [at]. */
    fun photosShown(prefs: MutablePreferences, linkIds: Collection<String>, at: Long) =
        record(prefs, SettingsKeys.HIDDEN_PHOTO_SHOW_TIMES, SettingsKeys.HIDDEN_PHOTO_HIDE_TIMES, linkIds, at)

    /** Records [linkIds] as albums hidden at [at]. */
    fun albumsHidden(prefs: MutablePreferences, linkIds: Collection<String>, at: Long) =
        record(prefs, SettingsKeys.HIDDEN_ALBUM_HIDE_TIMES, SettingsKeys.HIDDEN_ALBUM_SHOW_TIMES, linkIds, at)

    /** Records [linkIds] as albums shown again at [at]. */
    fun albumsShown(prefs: MutablePreferences, linkIds: Collection<String>, at: Long) =
        record(prefs, SettingsKeys.HIDDEN_ALBUM_SHOW_TIMES, SettingsKeys.HIDDEN_ALBUM_HIDE_TIMES, linkIds, at)

    /** The photos another device hid that wait for the app to move them into the vault, with that device's times. */
    fun remoteHideQueue(prefs: Preferences): Map<String, Long> = parse(prefs[SettingsKeys.HIDDEN_REMOTE_HIDE_QUEUE])

    /** Sets the waiting remote hides to [queue]. */
    fun setRemoteHideQueue(prefs: MutablePreferences, queue: Map<String, Long>) {
        if (queue.isEmpty()) prefs.remove(SettingsKeys.HIDDEN_REMOTE_HIDE_QUEUE)
        else prefs[SettingsKeys.HIDDEN_REMOTE_HIDE_QUEUE] = format(queue)
    }

    private fun record(
        prefs: MutablePreferences,
        into: Preferences.Key<Set<String>>,
        outOf: Preferences.Key<Set<String>>,
        linkIds: Collection<String>,
        at: Long,
    ) {
        val ids = linkIds.filter { it.isNotBlank() }
        if (ids.isEmpty()) return
        val target = parse(prefs[into]).toMutableMap()
        val other = parse(prefs[outOf]).toMutableMap()
        for (id in ids) {
            target[id] = at
            other.remove(id)
        }
        prefs[into] = format(target)
        prefs[outOf] = format(other)
    }
}
