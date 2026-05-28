@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package eu.akoos.photos.presentation.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import me.proton.core.accountmanager.domain.AccountManager
import eu.akoos.photos.domain.entity.GalleryItem
import eu.akoos.photos.domain.usecase.GetGalleryItemsUseCase
import eu.akoos.photos.presentation.gallery.ContentFilter
import eu.akoos.photos.presentation.gallery.MediaType
import eu.akoos.photos.presentation.gallery.SyncStatusFilter
import java.util.Calendar
import javax.inject.Inject

/** Backs the Search screen. Filters the merged gallery by displayName substring + ContentFilter. */
@HiltViewModel
class SearchViewModel @Inject constructor(
    private val getGalleryItems: GetGalleryItemsUseCase,
    private val accountManager: AccountManager,
) : ViewModel() {

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _contentFilter = MutableStateFlow(ContentFilter())
    val contentFilter: StateFlow<ContentFilter> = _contentFilter.asStateFlow()

    val results: StateFlow<List<GalleryItem>> = accountManager.getPrimaryUserId()
        .flatMapLatest { userId ->
            if (userId == null) flowOf(emptyList())
            else combine(
                getGalleryItems.invoke(userId),
                _query,
                _contentFilter,
            ) { all, q, filter -> applyAll(all, q, filter) }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun setQuery(value: String) { _query.value = value }
    fun setContentFilter(filter: ContentFilter) { _contentFilter.value = filter }
    fun clearAll() {
        _query.value = ""
        _contentFilter.value = ContentFilter()
    }

    private fun applyAll(items: List<GalleryItem>, q: String, filter: ContentFilter): List<GalleryItem> {
        val qTrimmed = q.trim()
        if (qTrimmed.isEmpty() && filter == ContentFilter()) return emptyList()
        var out = items
        if (qTrimmed.isNotEmpty()) {
            val needle = qTrimmed.lowercase()
            out = out.filter { displayNameOf(it).lowercase().contains(needle) }
        }
        out = applyContentFilter(out, filter)
        return out
    }

    private fun displayNameOf(item: GalleryItem): String = when (item) {
        is GalleryItem.LocalOnly -> item.local.displayName
        is GalleryItem.Synced    -> item.local.displayName
        is GalleryItem.CloudOnly -> item.cloud.displayName
    }

    private fun applyContentFilter(items: List<GalleryItem>, filter: ContentFilter): List<GalleryItem> {
        var out = items
        out = when (filter.mediaType) {
            MediaType.All        -> out
            MediaType.PhotosOnly -> out.filter { !mimeOf(it).startsWith("video/") }
            MediaType.VideosOnly -> out.filter { mimeOf(it).startsWith("video/") }
        }
        out = when (filter.syncStatus) {
            SyncStatusFilter.All       -> out
            SyncStatusFilter.LocalOnly -> out.filter { it is GalleryItem.LocalOnly }
            SyncStatusFilter.BackedUp  -> out.filter { it is GalleryItem.Synced || it is GalleryItem.CloudOnly }
        }
        val year = filter.year
        if (year != null) {
            val month = filter.month
            val cal = Calendar.getInstance()
            out = out.filter {
                cal.timeInMillis = it.captureTimeMs
                val y = cal.get(Calendar.YEAR)
                val m = cal.get(Calendar.MONTH) + 1
                y == year && (month == null || m == month)
            }
        }
        return out
    }

    private fun mimeOf(item: GalleryItem): String = when (item) {
        is GalleryItem.LocalOnly -> item.local.mimeType
        is GalleryItem.Synced    -> item.local.mimeType
        is GalleryItem.CloudOnly -> item.cloud.mimeType
    }
}
