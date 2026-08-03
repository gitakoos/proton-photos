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

package eu.akoos.photos.domain.repository

import eu.akoos.photos.data.api.model.NewsItem
import kotlinx.coroutines.flow.Flow

/** The news screen's contents as they stand from the cache: the entries, where feedback goes, and
 *  how many entries the user has not seen yet. */
data class NewsInbox(
    val items: List<NewsItem>,
    val feedbackUrl: String?,
    val feedbackJoinUrl: String?,
    val feedbackEmail: String?,
    val unreadCount: Int,
)

/**
 * The news feed, cached so the screen and the unread dot both read the same thing the instant the
 * app opens and keep working offline. Every read derives from the cache; only [refresh] touches the
 * network.
 */
interface NewsRepository {

    /** Fetch the feed and replace the cache. A no-op when news is switched off, and a failed fetch
     *  keeps the previous cache rather than clearing it. Safe to call on every launch. */
    suspend fun refresh()

    /** The inbox from the cache. Re-emits when the feed, the read set, or the on/off switch change. */
    fun observeInbox(): Flow<NewsInbox>

    /** Just the unread count, for the settings-icon dot. Always 0 while news is switched off. */
    fun observeUnreadCount(): Flow<Int>

    /** Mark every entry currently in the feed as seen, which clears the dot until a new entry lands. */
    suspend fun markAllRead()

    /** Whether the feed is switched on. Absent reads as on. */
    fun observeEnabled(): Flow<Boolean>

    /** Switch the feed on or off. Off stops the fetch and the dot; on triggers a fresh fetch. */
    suspend fun setEnabled(enabled: Boolean)
}
