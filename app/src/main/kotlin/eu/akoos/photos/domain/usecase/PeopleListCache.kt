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

package eu.akoos.photos.domain.usecase

import eu.akoos.photos.data.db.entity.PhotoLocationEntity
import eu.akoos.photos.domain.model.PersonSummary
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import me.proton.core.domain.entity.UserId
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The last full people list built for each account, kept for the life of the process. A screen that opens
 * again shows that list at once while [ObservePeopleUseCase] builds the current one, which on a library of
 * tens of thousands of groups takes a while; without it every visit started from an empty list.
 */
@Singleton
class PeopleListCache @Inject constructor() {
    private val lists = ConcurrentHashMap<String, List<PersonSummary>>()

    /** [people] for [userId], led by the list last built for that account and remembering each new one. */
    fun remembered(userId: UserId?, people: Flow<List<PersonSummary>>): Flow<List<PersonSummary>> {
        val account = userId?.id ?: PhotoLocationEntity.LOCAL_USER
        return people
            .onStart { lists[account]?.let { emit(it) } }
            .onEach { lists[account] = it }
    }
}
