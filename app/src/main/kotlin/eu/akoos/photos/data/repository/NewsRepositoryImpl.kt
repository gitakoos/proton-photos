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
import androidx.datastore.preferences.core.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.akoos.photos.data.api.NewsApi
import eu.akoos.photos.data.api.model.NewsFeed
import eu.akoos.photos.data.preferences.SettingsKeys
import eu.akoos.photos.data.preferences.settingsDataStore
import eu.akoos.photos.domain.repository.NewsInbox
import eu.akoos.photos.domain.repository.NewsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reads the news screen and the unread dot from one cached feed, and refreshes that cache from the
 * website. The cache is the single source of truth so both surfaces agree with no network wait, and
 * a failed refresh simply leaves the last good feed in place.
 */
@Singleton
class NewsRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val api: NewsApi,
) : NewsRepository {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    override suspend fun refresh() {
        if (!newsEnabled()) return
        try {
            val feed = api.getFeed()
            context.settingsDataStore.edit { it[SettingsKeys.NEWS_CACHE_JSON] = json.encodeToString(feed) }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            // A failed fetch keeps the last good cache: news never emptying on a dropped network is
            // worth more than being a refresh late.
        }
    }

    override fun observeInbox(): Flow<NewsInbox> =
        context.settingsDataStore.data.map { prefs ->
            val feed = parse(prefs[SettingsKeys.NEWS_CACHE_JSON])
            val read = prefs[SettingsKeys.NEWS_READ_IDS] ?: emptySet()
            NewsInbox(
                items = feed.items,
                feedbackUrl = feed.feedbackUrl?.takeIf { it.isNotBlank() },
                feedbackJoinUrl = feed.feedbackJoinUrl?.takeIf { it.isNotBlank() },
                feedbackEmail = feed.feedbackEmail?.takeIf { it.isNotBlank() },
                unreadCount = feed.items.count { it.id !in read },
            )
        }

    override fun observeUnreadCount(): Flow<Int> =
        context.settingsDataStore.data.map { prefs ->
            if (prefs[SettingsKeys.NEWS_ENABLED] == false) return@map 0
            val read = prefs[SettingsKeys.NEWS_READ_IDS] ?: emptySet()
            parse(prefs[SettingsKeys.NEWS_CACHE_JSON]).items.count { it.id !in read }
        }

    override suspend fun snapshotUnreadIds(): Set<String> {
        val prefs = context.settingsDataStore.data.first()
        if (prefs[SettingsKeys.NEWS_ENABLED] == false) return emptySet()
        val read = prefs[SettingsKeys.NEWS_READ_IDS] ?: emptySet()
        return parse(prefs[SettingsKeys.NEWS_CACHE_JSON]).items
            .mapNotNull { item -> item.id.takeIf { it !in read } }
            .toSet()
    }

    override suspend fun markAllRead() {
        val feed = parse(context.settingsDataStore.data.first()[SettingsKeys.NEWS_CACHE_JSON])
        // Set the read set to exactly the current feed's ids, so it stays bounded to the feed and a
        // later new id is the only thing that reads as unread.
        context.settingsDataStore.edit {
            it[SettingsKeys.NEWS_READ_IDS] = feed.items.mapTo(mutableSetOf()) { item -> item.id }
        }
    }

    override fun observeEnabled(): Flow<Boolean> =
        context.settingsDataStore.data.map { it[SettingsKeys.NEWS_ENABLED] != false }

    override suspend fun setEnabled(enabled: Boolean) {
        context.settingsDataStore.edit { it[SettingsKeys.NEWS_ENABLED] = enabled }
    }

    private suspend fun newsEnabled(): Boolean =
        context.settingsDataStore.data.first()[SettingsKeys.NEWS_ENABLED] != false

    /** A malformed or absent cache reads as an empty feed rather than throwing into a collector. */
    private fun parse(cached: String?): NewsFeed {
        if (cached.isNullOrBlank()) return NewsFeed()
        return runCatching { json.decodeFromString<NewsFeed>(cached) }.getOrDefault(NewsFeed())
    }
}
