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

package eu.akoos.photos.presentation.news

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import eu.akoos.photos.domain.repository.NewsInbox
import eu.akoos.photos.domain.repository.NewsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class NewsViewModel @Inject constructor(
    private val newsRepository: NewsRepository,
) : ViewModel() {

    val inbox: StateFlow<NewsInbox> = newsRepository.observeInbox()
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            NewsInbox(items = emptyList(), feedbackUrl = null, feedbackJoinUrl = null, feedbackEmail = null, unreadCount = 0),
        )

    val enabled: StateFlow<Boolean> = newsRepository.observeEnabled()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    // The ids that were unread when the screen opened, captured before markAllRead clears them, so the
    // per-item "new" markers stay put for the whole visit even though opening the screen marks the feed
    // read (that is what clears the settings dot). Empty when news is off or nothing was new.
    private val _newAtOpen = MutableStateFlow<Set<String>>(emptySet())
    val newAtOpen: StateFlow<Set<String>> = _newAtOpen.asStateFlow()

    init {
        // Fetch on open, snapshot which entries are new to the user, then mark the feed read so the
        // settings dot clears. The snapshot MUST come before markAllRead, or the markers would vanish
        // the same instant they appear.
        viewModelScope.launch {
            runCatching { newsRepository.refresh() }
            _newAtOpen.value = runCatching { newsRepository.snapshotUnreadIds() }.getOrDefault(emptySet())
            runCatching { newsRepository.markAllRead() }
        }
    }

    fun setNewsEnabled(value: Boolean) {
        viewModelScope.launch {
            newsRepository.setEnabled(value)
            // Turning it back on should show something without waiting for the next resume.
            if (value) runCatching { newsRepository.refresh() }
        }
    }
}
