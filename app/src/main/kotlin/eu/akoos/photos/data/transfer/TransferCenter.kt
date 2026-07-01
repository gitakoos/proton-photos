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

package eu.akoos.photos.data.transfer

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/** Own DataStore so the transfer log lives apart from the app settings and can be cleared alone. */
private val Context.transferHistoryDataStore: DataStore<Preferences> by
    preferencesDataStore(name = "transfer_history")

/**
 * Single point of truth for the background transfers the Activity screen shows.
 *
 * Two jobs:
 *  - **Live** ([active]) — the in-flight transfers that are NOT WorkManager jobs, i.e. the gallery
 *    multi-download and the offline pin loops that run inside a ViewModel scope. Those ViewModels
 *    call [start]/[progress]/[finish] so the Activity screen (which outlives no single ViewModel)
 *    can render them. WorkManager-backed album downloads and the backup upload are observed
 *    directly from their own streams, so they do NOT go through here.
 *  - **History** ([history]) — a small persisted log of finished uploads and downloads, so the
 *    Activity screen's History tab can show what happened even after the work is long gone. The
 *    log is a capped JSON list in its own DataStore (no database, no migration); newest first.
 */
@Singleton
class TransferCenter @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    enum class Kind { UPLOAD, DOWNLOAD, OFFLINE }

    /** A transfer running right now that isn't a WorkManager job (gallery download / offline pin). */
    data class Active(
        val id: Long,
        val kind: Kind,
        val done: Int,
        val total: Int,
        val name: String? = null,
    )

    /** One finished transfer, persisted for the History tab. [at] is epoch millis, newest first.
     *  [uris] are a few thumbnails of the photos involved (device content URIs or offline blob
     *  paths), so a row can expand to show which photos it covered. */
    @Serializable
    data class HistoryEntry(
        val kind: String,
        val name: String? = null,
        val count: Int,
        val at: Long,
        val uris: List<String> = emptyList(),
    )

    private val ids = AtomicLong(0L)
    private val _active = MutableStateFlow<List<Active>>(emptyList())
    val active: StateFlow<List<Active>> = _active.asStateFlow()

    /** Begin tracking a live transfer; returns its id for [progress] and [finish]. */
    fun start(kind: Kind, total: Int, name: String? = null): Long {
        val id = ids.incrementAndGet()
        _active.update { it + Active(id, kind, done = 0, total = total, name = name) }
        return id
    }

    /** Advance the done counter of a live transfer. */
    fun progress(id: Long, done: Int) {
        _active.update { list -> list.map { if (it.id == id) it.copy(done = done) else it } }
    }

    /** Drop a live transfer once it ends (success or failure). */
    fun finish(id: Long) {
        _active.update { list -> list.filterNot { it.id == id } }
    }

    /** Newest-first view of the persisted transfer log. */
    val history: Flow<List<HistoryEntry>> =
        context.transferHistoryDataStore.data.map { prefs -> decode(prefs[KEY_LOG]) }

    /** Append a finished transfer to the log. No-op for an empty batch. */
    suspend fun log(kind: Kind, count: Int, name: String? = null, uris: List<String> = emptyList()) {
        if (count <= 0) return
        val entry = HistoryEntry(kind.name, name, count, System.currentTimeMillis(), uris.take(MAX_THUMBS))
        context.transferHistoryDataStore.edit { prefs ->
            val next = (listOf(entry) + decode(prefs[KEY_LOG])).take(MAX_ENTRIES)
            prefs[KEY_LOG] = json.encodeToString(next)
        }
    }

    /** Wipe the History tab. */
    suspend fun clearHistory() {
        context.transferHistoryDataStore.edit { it.remove(KEY_LOG) }
    }

    private fun decode(raw: String?): List<HistoryEntry> =
        raw?.let { runCatching { json.decodeFromString<List<HistoryEntry>>(it) }.getOrNull() } ?: emptyList()

    companion object {
        private const val MAX_ENTRIES = 50
        private const val MAX_THUMBS = 12
        private val KEY_LOG = stringPreferencesKey("log")
        private val json = Json { ignoreUnknownKeys = true }
    }
}
