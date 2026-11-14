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

package eu.akoos.photos.presentation.people

import eu.akoos.photos.data.db.entity.PhotoLocationEntity
import eu.akoos.photos.data.face.FaceIndexingScheduler
import eu.akoos.photos.domain.usecase.GetGalleryItemsUseCase
import eu.akoos.photos.domain.usecase.ObservePeopleUseCase
import eu.akoos.photos.domain.usecase.ShowPersonUseCase
import eu.akoos.photos.presentation.common.UndoAction
import eu.akoos.photos.presentation.common.UndoController
import eu.akoos.photos.presentation.gallery.PersonUi
import eu.akoos.photos.presentation.gallery.toPersonUi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import me.proton.core.accountmanager.domain.AccountManager
import javax.inject.Inject

/**
 * The people the user hid, for the two screens that list them (the Hidden section of People and the excluded
 * faces), and the one way either shows one again. Works signed out, the account being the local partition then.
 */
class HiddenPeople @Inject constructor(
    private val accountManager: AccountManager,
    private val observePeopleUseCase: ObservePeopleUseCase,
    private val getGalleryItems: GetGalleryItemsUseCase,
    private val showPersonUseCase: ShowPersonUseCase,
    private val faceIndexingScheduler: FaceIndexingScheduler,
    private val undoController: UndoController,
    private val peopleVisibility: PeopleVisibility,
) {

    /** The hidden people, most-photographed first; a never-named group reads as unnamed. Empty while the people
     *  surfaces are not shown ([PeopleVisibility]), the same gate every people surface holds. */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun observe(): Flow<List<PersonUi>> = combine(
        peopleVisibility.visible(),
        accountManager.getPrimaryUserId(),
    ) { faceOn, userId -> faceOn to userId }
        .flatMapLatest { (faceOn, userId) ->
            if (!faceOn) flowOf(emptyList())
            else observePeopleUseCase(
                userId,
                if (userId == null) getGalleryItems.invokeLocalOnly() else getGalleryItems.invoke(userId),
                hiddenOnly = true,
            ).map { list -> list.mapNotNull { it.toPersonUi() } }
        }

    /**
     * Show a hidden person again. A never-named group has its reserved label taken off, so the grouping is asked
     * to run again; the change is offered on the shared undo bar, whose Undo hides the person once more.
     */
    suspend fun show(personId: Long) {
        val userId = accountManager.getPrimaryUserId().first()
        val account = userId?.id ?: PhotoLocationEntity.LOCAL_USER
        if (showPersonUseCase(account, personId)) faceIndexingScheduler.requestRecluster(userId)
        undoController.offer(UndoAction.PersonVisibility(listOf(personId), nowHidden = false))
    }
}
