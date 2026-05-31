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

@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package eu.akoos.photos.presentation.calendar

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import me.proton.core.accountmanager.domain.AccountManager
import eu.akoos.photos.data.db.dao.DayMetaDao
import eu.akoos.photos.data.db.entity.DayMetaEntity
import eu.akoos.photos.data.preferences.SettingsKeys
import eu.akoos.photos.data.preferences.settingsDataStore
import eu.akoos.photos.domain.entity.GalleryItem
import eu.akoos.photos.domain.usecase.GetGalleryItemsUseCase
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import javax.inject.Inject

/**
 * Backs DayDetailScreen — owns the live photos-of-day stream, the per-day metadata row
 * and the editing surface (location, description, cover photo).
 *
 * The [date] selector flips when the screen is opened for a different day; the inner
 * flows are flatMapLatest'd over it so the previous day's collection is cancelled and
 * we don't leak state across consecutive opens.
 */
@HiltViewModel
class DayDetailViewModel @Inject constructor(
    private val getGalleryItems: GetGalleryItemsUseCase,
    private val accountManager: AccountManager,
    private val dayMetaDao: DayMetaDao,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    private val _selectedDate = MutableStateFlow<String?>(null)
    private val _uiState = MutableStateFlow(DayDetailUiState())
    val uiState: StateFlow<DayDetailUiState> = _uiState.asStateFlow()

    /** Hidden vault filter — without this filter the Day detail screen and its
     *  metadata-edit pickers would surface hidden photos by date match. Same DataStore
     *  key the gallery uses. */
    private val hiddenUrisFlow = context.settingsDataStore.data.map {
        it[SettingsKeys.HIDDEN_PHOTO_URIS] ?: emptySet()
    }

    init {
        viewModelScope.launch {
            _selectedDate
                .flatMapLatest { date ->
                    val userIdFlow = accountManager.getPrimaryUserId()
                    if (date.isNullOrBlank()) {
                        flowOf(DayLoadData(emptyList(), null, null, emptySet()))
                    } else {
                        userIdFlow.flatMapLatest { userId ->
                            if (userId == null) {
                                flowOf(DayLoadData(emptyList(), null, date, emptySet()))
                            } else {
                                combine(
                                    getGalleryItems.invoke(userId),
                                    dayMetaDao.observeByDate(userId.id, date),
                                    hiddenUrisFlow,
                                ) { items, meta, hidden -> DayLoadData(items, meta, date, hidden) }
                            }
                        }
                    }
                }
                .collect { data ->
                    val date = data.date
                    if (date == null) {
                        _uiState.update { it.copy(isLoading = false, items = emptyList(), meta = null) }
                        return@collect
                    }
                    val dayItems = data.items
                        .filter { item ->
                            val uri = when (item) {
                                is GalleryItem.LocalOnly -> item.local.uri
                                is GalleryItem.Synced -> item.local.uri
                                is GalleryItem.CloudOnly -> null
                            }
                            (uri == null || uri !in data.hiddenUris) && isInDay(item.captureTimeMs, date)
                        }
                        .sortedByDescending { it.captureTimeMs }
                    val meta = data.meta
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            date = date,
                            items = dayItems,
                            meta = meta,
                        )
                    }
                }
        }
    }

    fun setDate(date: String) {
        if (_selectedDate.value == date) return
        _selectedDate.value = date
        _uiState.update { it.copy(isLoading = true, date = date) }
    }

    fun updateLocation(text: String) {
        upsertWith { existing ->
            (existing ?: blank()).copy(
                locationText = text.ifBlank { null },
                updatedAt = System.currentTimeMillis(),
            )
        }
    }

    fun updateDescription(text: String) {
        upsertWith { existing ->
            (existing ?: blank()).copy(
                description = text.ifBlank { null },
                updatedAt = System.currentTimeMillis(),
            )
        }
    }

    fun setCover(item: GalleryItem) {
        val uri = when (item) {
            is GalleryItem.LocalOnly -> item.local.uri
            is GalleryItem.Synced    -> item.local.uri
            is GalleryItem.CloudOnly -> item.cloud.linkId
        }
        upsertWith { existing ->
            (existing ?: blank()).copy(
                coverPhotoUri = uri,
                updatedAt = System.currentTimeMillis(),
            )
        }
    }

    private fun upsertWith(block: (DayMetaEntity?) -> DayMetaEntity) {
        val date = _selectedDate.value ?: return
        viewModelScope.launch {
            val userId = accountManager.getPrimaryUserId().first() ?: return@launch
            val current = dayMetaDao.getByDate(userId.id, date)
            val next = block(current).copy(date = date, userId = userId.id)
            dayMetaDao.upsert(next)
        }
    }

    private fun blank(): DayMetaEntity = DayMetaEntity(
        date = _selectedDate.value ?: "",
        userId = "",
    )

    private fun isInDay(captureTimeMs: Long, date: String): Boolean {
        if (captureTimeMs <= 0L) return false
        return ISO_DATE.get().format(Date(captureTimeMs)) == date
    }

    companion object {
        private val ISO_DATE = object : ThreadLocal<SimpleDateFormat>() {
            override fun initialValue(): SimpleDateFormat =
                SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
                    timeZone = TimeZone.getDefault()
                }
        }
    }
}

data class DayDetailUiState(
    val isLoading: Boolean = true,
    val date: String? = null,
    val items: List<GalleryItem> = emptyList(),
    val meta: DayMetaEntity? = null,
)

/** Combined collector payload — Triple ran out of slots once the hidden-URIs Set joined
 *  the day's items + meta. Named fields make the consumer site read clearly. */
private data class DayLoadData(
    val items: List<GalleryItem>,
    val meta: DayMetaEntity?,
    val date: String?,
    val hiddenUris: Set<String>,
)
