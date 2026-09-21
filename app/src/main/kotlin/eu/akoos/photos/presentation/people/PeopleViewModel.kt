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

package eu.akoos.photos.presentation.people

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import me.proton.core.accountmanager.domain.AccountManager
import eu.akoos.photos.data.preferences.SettingsKeys
import eu.akoos.photos.data.preferences.settingsDataStore
import eu.akoos.photos.domain.usecase.GetGalleryItemsUseCase
import eu.akoos.photos.domain.usecase.ObservePeopleUseCase
import eu.akoos.photos.presentation.gallery.PersonUi
import eu.akoos.photos.presentation.gallery.toPersonUi
import javax.inject.Inject

/**
 * Backs the full People screen. Resolves the active account's clustered people to face-crop tiles,
 * ordered most-photographed first and empty when signed out, from the same [ObservePeopleUseCase]
 * against the shared library the People preview and rail read, so all three surfaces show the same
 * faces; it re-resolves on an account switch. No filtering happens here: the screen drops one-off
 * detections when it renders.
 */
@HiltViewModel
class PeopleViewModel @Inject constructor(
    private val accountManager: AccountManager,
    private val observePeopleUseCase: ObservePeopleUseCase,
    private val getGalleryItems: GetGalleryItemsUseCase,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    // People resolve only while the master AI switch and the per-feature face switch are both on; with
    // either off, or signed out, the screen shows nothing and reads no face data. The null seed marks
    // the load before the first emission, so the screen shows a skeleton instead of the empty state.
    val people: StateFlow<List<PersonUi>?> = combine(
        context.settingsDataStore.data
            .map { it[SettingsKeys.AI_FEATURES_ENABLED] == true && it[SettingsKeys.FACE_ENABLED] == true }
            .distinctUntilChanged(),
        accountManager.getPrimaryUserId(),
    ) { faceOn, userId -> faceOn to userId }
        .flatMapLatest { (faceOn, userId) ->
            if (!faceOn) flowOf(emptyList())
            else observePeopleUseCase(
                userId,
                if (userId == null) getGalleryItems.invokeLocalOnly() else getGalleryItems.invoke(userId),
            ).map { list -> list.mapNotNull { it.toPersonUi() } }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), null)
}
