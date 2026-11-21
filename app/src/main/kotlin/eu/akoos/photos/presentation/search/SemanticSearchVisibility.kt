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

package eu.akoos.photos.presentation.search

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.akoos.photos.data.db.dao.ImageEmbeddingDao
import eu.akoos.photos.data.preferences.SettingsKeys
import eu.akoos.photos.data.preferences.settingsDataStore
import eu.akoos.photos.util.retryOnDbTear
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import me.proton.core.accountmanager.domain.AccountManager
import javax.inject.Inject

/**
 * Whether searching by what a photo shows is available.
 *
 * It is while this phone indexes its photos for it, and also, for a signed-in account, whenever the account
 * holds search embeddings, so the ones another device brought through the shared backup are searchable with
 * indexing here off. A guest has no shared backup, so a guest follows the indexing switch alone. Indexing
 * itself keeps following [indexingHere].
 */
class SemanticSearchVisibility @Inject constructor(
    private val accountManager: AccountManager,
    private val imageEmbeddingDao: ImageEmbeddingDao,
    @ApplicationContext private val context: Context,
) {

    /** Whether this phone indexes for content search: the master AI switch and the search switch both on. */
    fun indexingHere(): Flow<Boolean> = context.settingsDataStore.data
        .map { it[SettingsKeys.AI_FEATURES_ENABLED] == true && it[SettingsKeys.SEMANTIC_ENABLED] == true }
        .distinctUntilChanged()

    /** Whether content search is available, by [semanticSearchAvailable]. */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun available(): Flow<Boolean> = combine(indexingHere(), accountManager.getPrimaryUserId()) { indexing, userId ->
        indexing to userId
    }
        .flatMapLatest { (indexing, userId) ->
            if (indexing || userId == null) {
                flowOf(semanticSearchAvailable(indexing, signedIn = userId != null, accountHoldsEmbeddings = false))
            } else {
                imageEmbeddingDao.observeCountForUser(userId.id)
                    .retryOnDbTear(TEAR_TAG)
                    .map { count -> semanticSearchAvailable(indexing, signedIn = true, accountHoldsEmbeddings = count > 0) }
            }
        }
        .distinctUntilChanged()

    private companion object {
        const val TEAR_TAG = "SemanticSearchVisibility"
    }
}

/**
 * The rule behind [SemanticSearchVisibility.available]: available while this phone indexes, or for a
 * signed-in account that holds embeddings. A guest follows the indexing switch alone.
 */
internal fun semanticSearchAvailable(indexingHere: Boolean, signedIn: Boolean, accountHoldsEmbeddings: Boolean): Boolean =
    indexingHere || (signedIn && accountHoldsEmbeddings)

/**
 * Whether the search text model should be fetched now: content search is available, the text model is not
 * on the phone, no fetch of it is running, and the connection is unmetered. The fetch is automatic, so it
 * never uses mobile data; the app retries when Wi-Fi comes back.
 */
internal fun shouldFetchSearchTextModel(
    searchAvailable: Boolean,
    textModelPresent: Boolean,
    fetchRunning: Boolean,
    unmetered: Boolean,
): Boolean = searchAvailable && !textModelPresent && !fetchRunning && unmetered
