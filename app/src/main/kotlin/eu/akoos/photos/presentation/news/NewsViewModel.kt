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
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
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

    init {
        // Fetch on open too, so opening straight to the screen still lands the latest even when the
        // resume refresh has not finished yet.
        viewModelScope.launch { runCatching { newsRepository.refresh() } }
    }

    /** Called once the screen is shown: everything currently listed counts as seen, so the dot clears. */
    fun markAllRead() {
        viewModelScope.launch { runCatching { newsRepository.markAllRead() } }
    }

    fun setNewsEnabled(value: Boolean) {
        viewModelScope.launch {
            newsRepository.setEnabled(value)
            // Turning it back on should show something without waiting for the next resume.
            if (value) runCatching { newsRepository.refresh() }
        }
    }
}
