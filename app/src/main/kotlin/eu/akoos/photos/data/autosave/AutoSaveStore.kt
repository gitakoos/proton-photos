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

package eu.akoos.photos.data.autosave

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.akoos.photos.data.preferences.settingsDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject

/**
 * One automatic save's saved state: whether it is on, how often it runs, and the times it keeps
 * ([AutoSaveClock]) plus when it last finished ([lastSaveOkMs]).
 */
data class AutoSaveState(
    val enabled: Boolean = false,
    val frequency: AutoSaveFrequency = AutoSaveFrequency.DAILY,
    val dirtySinceMs: Long = 0L,
    val lastChangeMs: Long = 0L,
    val lastSaveStartMs: Long = 0L,
    val lastSaveOkMs: Long = 0L,
    val lastFailureMs: Long = 0L,
) {
    internal val clock: AutoSaveClock
        get() = AutoSaveClock(dirtySinceMs, lastChangeMs, lastSaveStartMs, lastFailureMs)
}

/**
 * Where the two automatic saves keep their settings and history, in the app's settings store, one set of keys
 * per [AutoSaveKind]. Every change is one atomic edit, so a process that dies between two writes never leaves
 * the pair half done.
 */
class AutoSaveStore @Inject constructor(@ApplicationContext private val context: Context) {

    private class Keys(kind: AutoSaveKind) {
        private val p = "autosave_${kind.name.lowercase()}_"
        val enabled = booleanPreferencesKey(p + "enabled")
        val frequency = stringPreferencesKey(p + "frequency")
        val dirtySince = longPreferencesKey(p + "dirty_since")
        val lastChange = longPreferencesKey(p + "last_change")
        val lastSaveStart = longPreferencesKey(p + "last_save_start")
        val lastSaveOk = longPreferencesKey(p + "last_save_ok")
        val lastFailure = longPreferencesKey(p + "last_failure")
    }

    private fun Preferences.stateOf(k: Keys) = AutoSaveState(
        enabled = this[k.enabled] == true,
        frequency = this[k.frequency]?.let { name -> AutoSaveFrequency.entries.firstOrNull { it.name == name } }
            ?: AutoSaveFrequency.DAILY,
        dirtySinceMs = this[k.dirtySince] ?: 0L,
        lastChangeMs = this[k.lastChange] ?: 0L,
        lastSaveStartMs = this[k.lastSaveStart] ?: 0L,
        lastSaveOkMs = this[k.lastSaveOk] ?: 0L,
        lastFailureMs = this[k.lastFailure] ?: 0L,
    )

    fun flow(kind: AutoSaveKind): Flow<AutoSaveState> {
        val k = Keys(kind)
        return context.settingsDataStore.data.map { it.stateOf(k) }
    }

    suspend fun read(kind: AutoSaveKind): AutoSaveState = flow(kind).first()

    /** Switches the save on or off. Switching on counts the data as changed, so the first save runs at once. */
    suspend fun setEnabled(kind: AutoSaveKind, enabled: Boolean, nowMs: Long) {
        val k = Keys(kind)
        context.settingsDataStore.edit { prefs ->
            prefs[k.enabled] = enabled
            if (enabled) {
                if ((prefs[k.dirtySince] ?: 0L) == 0L) prefs[k.dirtySince] = nowMs
                prefs[k.lastChange] = nowMs - AUTO_SAVE_QUIET_MS
                prefs[k.lastFailure] = 0L
            }
        }
    }

    suspend fun setFrequency(kind: AutoSaveKind, frequency: AutoSaveFrequency) {
        val k = Keys(kind)
        context.settingsDataStore.edit { it[k.frequency] = frequency.name }
    }

    /**
     * Notes a change of the data at [nowMs] while the save is on. Returns whether the data was clean before,
     * so a caller can tell the first change from a following one; false too when the save is off.
     */
    suspend fun markChanged(kind: AutoSaveKind, nowMs: Long): Boolean {
        val k = Keys(kind)
        var wasClean = false
        context.settingsDataStore.edit { prefs ->
            if (prefs[k.enabled] != true) return@edit
            wasClean = (prefs[k.dirtySince] ?: 0L) == 0L
            if (wasClean) prefs[k.dirtySince] = nowMs
            prefs[k.lastChange] = nowMs
        }
        return wasClean
    }

    /** Records a finished save that began at [startMs] and ended at [endMs]. A change that came while it ran stays waiting. */
    suspend fun recordSaved(kind: AutoSaveKind, startMs: Long, endMs: Long) {
        val k = Keys(kind)
        context.settingsDataStore.edit { prefs ->
            prefs[k.lastSaveStart] = startMs
            prefs[k.lastSaveOk] = endMs
            prefs[k.lastFailure] = 0L
            prefs[k.dirtySince] = dirtySinceAfterSave(prefs[k.lastChange] ?: 0L, startMs)
        }
    }

    /** Records a save that ended at [endMs] without finishing. */
    suspend fun recordFailed(kind: AutoSaveKind, endMs: Long) {
        val k = Keys(kind)
        context.settingsDataStore.edit { it[k.lastFailure] = endMs }
    }

    /** Debug aid: counts the data as changed now. */
    suspend fun forceDirty(kind: AutoSaveKind, nowMs: Long) {
        val k = Keys(kind)
        context.settingsDataStore.edit { prefs ->
            if ((prefs[k.dirtySince] ?: 0L) == 0L) prefs[k.dirtySince] = nowMs
            prefs[k.lastChange] = nowMs
        }
    }

    /** Forgets everything of both saves: a sign-out leaves no automatic save behind for the next account. */
    suspend fun clearAll() {
        context.settingsDataStore.edit { prefs ->
            for (kind in AutoSaveKind.entries) {
                val k = Keys(kind)
                prefs.remove(k.enabled)
                prefs.remove(k.frequency)
                prefs.remove(k.dirtySince)
                prefs.remove(k.lastChange)
                prefs.remove(k.lastSaveStart)
                prefs.remove(k.lastSaveOk)
                prefs.remove(k.lastFailure)
            }
        }
    }
}
