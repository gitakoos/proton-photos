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

package eu.akoos.photos.data.settingssync

import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import eu.akoos.photos.data.preferences.SettingsKeys
import kotlinx.serialization.json.JsonElement
import java.security.MessageDigest

/** When a setting last changed on this device, and what it held then, so a later difference is a change. */
internal data class SettingsStamp(val at: Long, val fingerprint: String)

/**
 * When each synced setting and set member last changed, kept beside the settings so the shared backup can put this
 * device's settings together with another's, the later change winning. Stored as `"id|millis|fingerprint"` strings
 * in [SettingsKeys.SETTINGS_SYNC_TIMES]; a set member is stamped as `"setId#member"` with the fingerprint `1`
 * (in the set) or `0` (taken out). The fingerprint is a short hash of the value the stamp was made for, so a value
 * that no longer matches it was changed since, by the user: the stamp needs no watcher state of its own, and a value
 * applied from another device is written together with its stamp and so never looks like a change here.
 *
 * Every function works on the caller's DataStore edit, so a value and its stamp land together, and the sign-out
 * wipe, which removes the values and this record in one edit, can never leave a removal that reads as a reset.
 */
internal object SettingsSyncTimes {

    /** Marks that the first stamping pass has run: values that exist before it are stamped 0, those after it now. */
    private const val SEEDED = "~seeded"
    private const val RESET = "-"
    private const val IN = "1"
    private const val OUT = "0"
    private const val MEMBER_SEPARATOR = '#'
    private const val HIDE_ALL = "albums.hidePhotosInAlbums"

    /** The `"key|millis|fingerprint"` entries of [entries] as a map; an entry that does not parse is skipped. */
    fun parse(entries: Set<String>?): Map<String, SettingsStamp> {
        if (entries.isNullOrEmpty()) return emptyMap()
        val out = HashMap<String, SettingsStamp>(entries.size)
        for (entry in entries) {
            val last = entry.lastIndexOf('|')
            val middle = if (last > 0) entry.lastIndexOf('|', last - 1) else -1
            if (middle <= 0) continue
            val at = entry.substring(middle + 1, last).toLongOrNull() ?: continue
            out[entry.substring(0, middle)] = SettingsStamp(at, entry.substring(last + 1))
        }
        return out
    }

    /** [stamps] as `"key|millis|fingerprint"` entries. */
    fun format(stamps: Map<String, SettingsStamp>): Set<String> =
        stamps.mapTo(HashSet(stamps.size)) { (key, s) -> "$key|${s.at}|${s.fingerprint}" }

    /** A short hash of a setting's value, [RESET] for a setting at its default. */
    fun fingerprint(value: JsonElement?): String =
        if (value == null) RESET
        else MessageDigest.getInstance("SHA-256").digest(value.toString().toByteArray(Charsets.UTF_8))
            .take(8).joinToString("") { "%02x".format(it) }

    fun memberKey(setId: String, element: String) = "$setId$MEMBER_SEPARATOR$element"

    /** The settings (id to stamp) and the set members (set id, member, stamp) [stamps] hold. */
    fun split(stamps: Map<String, SettingsStamp>): Pair<Map<String, SettingsStamp>, List<Triple<String, String, SettingsStamp>>> {
        val scalars = HashMap<String, SettingsStamp>()
        val members = ArrayList<Triple<String, String, SettingsStamp>>()
        for ((key, stamp) in stamps) {
            val at = key.indexOf(MEMBER_SEPARATOR)
            if (at > 0) members += Triple(key.substring(0, at), key.substring(at + 1), stamp) else scalars[key] = stamp
        }
        return scalars to members
    }

    /**
     * Stamps what changed in [prefs] since the stamps were made: a setting whose value no longer matches its stamp, a
     * set member that came or went. Values that carry no stamp yet are the user's own change once the first pass has
     * run, and are seeded at 0 on the first, which only fills a gap elsewhere. True when a stamp was written.
     */
    fun stampChanges(prefs: MutablePreferences, nowMs: Long): Boolean {
        val before = prefs[SettingsKeys.SETTINGS_SYNC_TIMES]
        val stamps = parse(before).toMutableMap()
        val seeded = SEEDED in stamps
        val fresh = if (seeded) nowMs else 0L
        var stamped = false

        fun stamp(key: String, at: Long, fingerprint: String) {
            stamps[key] = SettingsStamp(at, fingerprint)
            stamped = true
        }

        for (setting in SettingsSyncRegistry.scalars) {
            val held = stamps[setting.id]
            when (val reading = setting.read(prefs)) {
                SettingReading.Unsyncable -> Unit
                SettingReading.Unset -> if (held != null && held.fingerprint != RESET) stamp(setting.id, nowMs, RESET)
                is SettingReading.Value -> {
                    val now = fingerprint(reading.json)
                    if (held == null) stamp(setting.id, fresh, now) else if (held.fingerprint != now) stamp(setting.id, nowMs, now)
                }
            }
        }
        for (setting in SettingsSyncRegistry.sets) {
            val present = setting.members(prefs)
            val prefix = "${setting.id}$MEMBER_SEPARATOR"
            val known = stamps.keys.filter { it.startsWith(prefix) }.map { it.removePrefix(prefix) }
            for (element in present + known) {
                val key = memberKey(setting.id, element)
                val now = if (element in present) IN else OUT
                val held = stamps[key]
                if (held == null) stamp(key, fresh, now) else if (held.fingerprint != now) stamp(key, nowMs, now)
            }
        }
        if (!seeded) stamps[SEEDED] = SettingsStamp(0L, RESET)
        val after = format(stamps)
        if (after != before.orEmpty()) prefs[SettingsKeys.SETTINGS_SYNC_TIMES] = after
        return stamped
    }

    /**
     * Writes what other devices' copies brought ([changes]) into [prefs], each value together with the time it came
     * with, so the apply is not stamped again as a change made here. Returns the ids of the settings that changed.
     */
    fun applyChanges(prefs: MutablePreferences, changes: SettingsCopyMerge.Changes): Set<String> {
        val stamps = parse(prefs[SettingsKeys.SETTINGS_SYNC_TIMES]).toMutableMap()
        val applied = HashSet<String>()
        val hideAllBefore = prefs[SettingsKeys.HIDE_PHOTOS_IN_ALBUMS] == true
        for ((id, taken) in changes.scalars) {
            val setting = SettingsSyncRegistry.scalar(id) ?: continue
            setting.write(prefs, taken.value)
            val now = when (val reading = setting.read(prefs)) {
                is SettingReading.Value -> fingerprint(reading.json)
                else -> RESET
            }
            stamps[id] = SettingsStamp(taken.at, now)
            applied += id
        }
        for ((id, byElement) in changes.members) {
            val setting = SettingsSyncRegistry.set(id) ?: continue
            setting.write(prefs, byElement.mapValues { it.value.present })
            for ((element, taken) in byElement) {
                stamps[memberKey(id, element)] = SettingsStamp(taken.at, if (taken.present) IN else OUT)
            }
            applied += id
        }
        // The legacy dark flag only matters while no theme word exists.
        if ("appearance.theme" in applied) prefs.remove(SettingsKeys.DARK_MODE)
        // Hide-all means every album excluded, spread here onto the per-album set; another device
        // turning it off brings every album back, as the switch does here.
        if (HIDE_ALL in applied && hideAllBefore && prefs[SettingsKeys.HIDE_PHOTOS_IN_ALBUMS] != true) {
            prefs.remove(SettingsKeys.TIMELINE_EXCLUDED_ALBUM_IDS)
        }
        if (applied.isNotEmpty()) prefs[SettingsKeys.SETTINGS_SYNC_TIMES] = format(stamps)
        return applied
    }

    /** This device's synced settings with their times; only what carries a stamp, so call [stampChanges] first. */
    fun stateOf(prefs: Preferences): SettingsSyncState {
        val (scalarStamps, memberStamps) = split(parse(prefs[SettingsKeys.SETTINGS_SYNC_TIMES]))
        val state = SettingsSyncState()
        for (setting in SettingsSyncRegistry.scalars) {
            val stamp = scalarStamps[setting.id] ?: continue
            when (val reading = setting.read(prefs)) {
                SettingReading.Unsyncable -> Unit
                SettingReading.Unset -> state.scalars[setting.id] = SettingsScalarState(stamp.at, null)
                is SettingReading.Value -> state.scalars[setting.id] = SettingsScalarState(stamp.at, reading.json)
            }
        }
        for ((id, element, stamp) in memberStamps) {
            val setting = SettingsSyncRegistry.set(id) ?: continue
            val present = element in setting.members(prefs)
            state.members.getOrPut(id) { HashMap() }[element] = SettingsMemberState(stamp.at, present)
        }
        return state
    }
}
