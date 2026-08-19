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

@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package eu.akoos.photos.presentation.memories

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.akoos.photos.R
import eu.akoos.photos.data.preferences.SettingsKeys
import eu.akoos.photos.data.preferences.settingsDataStore
import eu.akoos.photos.data.repository.drive.ThumbnailDecryptScheduler
import eu.akoos.photos.data.repository.drive.ThumbnailUrlStore
import eu.akoos.photos.domain.entity.GalleryItem
import eu.akoos.photos.domain.model.PersonSummary
import eu.akoos.photos.domain.usecase.GetGalleryItemsUseCase
import eu.akoos.photos.domain.usecase.ObservePeopleUseCase
import eu.akoos.photos.presentation.gallery.FaceBox
import eu.akoos.photos.presentation.gallery.PersonUi
import eu.akoos.photos.util.computeOnThisDay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import me.proton.core.accountmanager.domain.AccountManager
import java.util.Calendar
import javax.inject.Inject

/** A meteorological-season bucket — every item shot in one season-year, newest cover first. */
data class SeasonBucket(
    val title: String,
    val cover: GalleryItem,
    val items: List<GalleryItem>,
)

data class MemoriesUiState(
    val onThisDay: List<Pair<Int, List<GalleryItem>>> = emptyList(),
    val seasons: List<SeasonBucket> = emptyList(),
    val people: List<PersonUi> = emptyList(),
)

/** Backs the Memories screen: derives "On this day" milestones and per-season buckets from the
 *  merged library, exactly like the gallery carousel, plus the clustered people when AI is on,
 *  recomputed off the main thread. */
@HiltViewModel
class MemoriesViewModel @Inject constructor(
    getGalleryItems: GetGalleryItemsUseCase,
    observePeopleUseCase: ObservePeopleUseCase,
    accountManager: AccountManager,
    private val thumbnailDecryptScheduler: ThumbnailDecryptScheduler,
    private val thumbnailUrlStore: ThumbnailUrlStore,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    /** Last cover linkId set handed to the scheduler; re-pin only when it changes, not on every
     *  library re-emit while thumbnails decrypt. */
    private var lastPinnedCovers: List<String> = emptyList()

    val uiState: StateFlow<MemoriesUiState> = accountManager.getPrimaryUserId()
        .flatMapLatest { userId ->
            if (userId == null) {
                flowOf(MemoriesUiState())
            } else {
                val memories = getGalleryItems.invoke(userId)
                    .map { all -> MemoriesUiState(onThisDay = computeOnThisDay(all), seasons = buckets(all)) }
                // People show in the Collection only with AI on, the same opt-in the rest of the
                // grouping honours; the face crop needs the feed's original dimensions, so the use
                // case is handed the same library flow.
                val people = context.settingsDataStore.data
                    .map { it[SettingsKeys.AI_FEATURES_ENABLED] == true && it[SettingsKeys.FACE_ENABLED] == true }
                    .distinctUntilChanged()
                    .flatMapLatest { enabled ->
                        if (!enabled) flowOf(emptyList<PersonUi>())
                        else observePeopleUseCase(userId, getGalleryItems.invoke(userId))
                            .map { list -> list.mapNotNull { it.toPersonUi() } }
                    }
                combine(memories, people) { state, ppl -> state.copy(people = ppl) }
                    // Pin + warm the cover thumbnails so they survive the large-library cache trim and
                    // the Collection cards don't sit blank on a library too big to fully warm. Re-pin
                    // only when the cover set actually changes, not on every decrypt-driven re-emit.
                    .onEach { state ->
                        val covers = coverLinkIds(state)
                        if (covers != lastPinnedCovers) {
                            lastPinnedCovers = covers
                            thumbnailDecryptScheduler.pinCovers(userId, covers)
                        }
                    }
                    // The cards draw their cover imperatively (a bitmap built outside any Compose
                    // cell), so the LocalThumbnailUrls CompositionLocal can't reach them. Overlay the
                    // store's freshly-decrypted URL onto the cloud-only cover items so they render,
                    // and repaint live when a decrypt lands after the screen opened. The card set is
                    // a handful of items, so the extra re-emit per store change is cheap.
                    .combine(thumbnailUrlStore.urls) { state, urls -> state.withThumbnails(urls) }
            }
        }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MemoriesUiState())

    /** Map a clustered person to the round-tile model, dropping any without a resolvable cover. */
    private fun PersonSummary.toPersonUi(): PersonUi? {
        val cover = coverPhotoKey ?: return null
        return PersonUi(
            personId = personId,
            displayName = displayName,
            coverPhotoKey = cover,
            faceBox = faceBox?.let { FaceBox(it.left, it.top, it.right, it.bottom) },
            faceCount = faceCount,
        )
    }

    /** Stamp the store URLs onto every cloud-only cover in this state (both the On-this-day cover
     *  items and each season cover). Local/Synced covers paint from their local uri and are left as
     *  is. Only the cover items are rewritten; the full per-year / per-season item lists carry into
     *  the viewer, which resolves its own thumbnails. */
    private fun MemoriesUiState.withThumbnails(urls: Map<String, String>): MemoriesUiState {
        if (urls.isEmpty()) return this
        return copy(
            onThisDay = onThisDay.map { (year, items) ->
                if (items.isEmpty()) year to items
                else year to (listOf(resolveThumbnail(items.first(), urls)) + items.drop(1))
            },
            seasons = seasons.map { it.copy(cover = resolveThumbnail(it.cover, urls)) },
        )
    }

    /** Overlay the store URL onto a cloud-only cover; a Local/Synced cover is returned untouched. */
    private fun resolveThumbnail(item: GalleryItem, urls: Map<String, String>): GalleryItem =
        if (item is GalleryItem.CloudOnly) {
            val url = urls[item.cloud.linkId] ?: item.cloud.thumbnailUrl
            if (url == item.cloud.thumbnailUrl) item
            else GalleryItem.CloudOnly(item.cloud.copy(thumbnailUrl = url))
        } else {
            item
        }

    /** Cloud linkIds of the Collection cover items (the first On-this-day entry per year plus each
     *  season cover) so they can be pinned and warmed. Local-only covers have no cloud thumbnail to
     *  decrypt and are skipped. */
    private fun coverLinkIds(state: MemoriesUiState): List<String> {
        val covers = state.onThisDay.mapNotNull { it.second.firstOrNull() } + state.seasons.map { it.cover }
        return covers.mapNotNull { item ->
            when (item) {
                is GalleryItem.CloudOnly -> item.cloud.linkId
                is GalleryItem.Synced -> item.cloud.linkId
                is GalleryItem.LocalOnly -> null
            }
        }.distinct()
    }

    /**
     * Buckets every item by its meteorological season-year. A winter is named by the December it
     * starts in, so Jan/Feb roll BACK into the prior year's Winter (Dec 2022 + Jan/Feb 2023 = one
     * "Winter 2022"). The merged feed is already capture-time-descending, so each bucket's first
     * item is its newest = the cover. Buckets are ordered most-recent-first (by season-year, then
     * Winter > Autumn > Summer > Spring within a year). One [Calendar] instance is reused across
     * the whole library to avoid per-item churn.
     */
    private fun buckets(all: List<GalleryItem>): List<SeasonBucket> {
        if (all.isEmpty()) return emptyList()
        val cal = Calendar.getInstance()
        // Preserve the input (capture-time-descending) order inside each bucket.
        val grouped = LinkedHashMap<SeasonKey, MutableList<GalleryItem>>()
        for (item in all) {
            if (item.captureTimeMs <= 0L) continue // skip unknown/sentinel capture times (no "Winter 1971" card)
            cal.timeInMillis = item.captureTimeMs
            val month = cal.get(Calendar.MONTH) + 1
            val year = cal.get(Calendar.YEAR)
            val season = when (month) {
                12 -> Season.WINTER
                1, 2 -> Season.WINTER
                3, 4, 5 -> Season.SPRING
                6, 7, 8 -> Season.SUMMER
                else -> Season.AUTUMN
            }
            // Name the winter by the December it starts in: Jan/Feb roll back a year, every other
            // month keeps its own (Dec 2022 + Jan/Feb 2023 → "Winter 2022", the oldest expected card).
            val seasonYear = if (month == 1 || month == 2) year - 1 else year
            grouped.getOrPut(SeasonKey(seasonYear, season)) { ArrayList() }.add(item)
        }
        return grouped.entries
            .sortedWith(
                compareByDescending<Map.Entry<SeasonKey, MutableList<GalleryItem>>> { it.key.year }
                    .thenByDescending { it.key.season.recencyWithinYear },
            )
            .map { (key, items) ->
                SeasonBucket(
                    title = "${context.getString(key.season.nameRes)} ${key.year}",
                    cover = items.first(),
                    items = items,
                )
            }
    }

    private data class SeasonKey(val year: Int, val season: Season)

    /** Season order within a calendar year, most-recent first: Winter (Dec/Jan/Feb) closes the
     *  season-year, so it ranks highest; Spring opens it, so lowest. */
    private enum class Season(val nameRes: Int, val recencyWithinYear: Int) {
        SPRING(R.string.season_spring, 0),
        SUMMER(R.string.season_summer, 1),
        AUTUMN(R.string.season_autumn, 2),
        WINTER(R.string.season_winter, 3),
    }
}
