@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package eu.akoos.photos.presentation.calendar

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import me.proton.core.accountmanager.domain.AccountManager
import eu.akoos.photos.data.db.dao.DayMetaDao
import eu.akoos.photos.data.db.entity.DayMetaEntity
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
) : ViewModel() {

    private val _selectedDate = MutableStateFlow<String?>(null)
    private val _uiState = MutableStateFlow(DayDetailUiState())
    val uiState: StateFlow<DayDetailUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            _selectedDate
                .flatMapLatest { date ->
                    val userIdFlow = accountManager.getPrimaryUserId()
                    if (date.isNullOrBlank()) {
                        flowOf(Triple(emptyList<GalleryItem>(), null, null))
                    } else {
                        userIdFlow.flatMapLatest { userId ->
                            if (userId == null) {
                                flowOf(Triple(emptyList<GalleryItem>(), null, date))
                            } else {
                                combine(
                                    getGalleryItems.invoke(userId),
                                    dayMetaDao.observeByDate(userId.id, date),
                                ) { items, meta -> Triple(items, meta, date) }
                            }
                        }
                    }
                }
                .collect { (items, meta, date) ->
                    if (date == null) {
                        _uiState.update { it.copy(isLoading = false, items = emptyList(), meta = null) }
                        return@collect
                    }
                    val dayItems = items
                        .filter { isInDay(it.captureTimeMs, date) }
                        .sortedByDescending { it.captureTimeMs }
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
