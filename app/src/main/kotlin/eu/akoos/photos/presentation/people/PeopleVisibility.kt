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

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.akoos.photos.data.db.dao.PersonDao
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
 * Whether the people surfaces that only read and name people (the People page, its tile, the person page,
 * the face chips in the viewer, the search pills and the timeline rail) are shown.
 *
 * They show while this phone scans for faces, and also, for a signed-in account, whenever the account holds
 * people, so the people another device brought through the shared backup stay visible with the scan off. A
 * guest has no shared backup, so a guest's people follow the scan switch alone. Whatever needs the local
 * face model (finding more photos, detecting faces on demand, adding a photo to a person, the excluded
 * faces) keeps following [scanningHere].
 */
class PeopleVisibility @Inject constructor(
    private val accountManager: AccountManager,
    private val personDao: PersonDao,
    @ApplicationContext private val context: Context,
) {

    /** Whether this phone scans for faces: the master AI switch and the face switch both on. */
    fun scanningHere(): Flow<Boolean> = context.settingsDataStore.data
        .map { it[SettingsKeys.AI_FEATURES_ENABLED] == true && it[SettingsKeys.FACE_ENABLED] == true }
        .distinctUntilChanged()

    /** Whether the read-only people surfaces are shown, by [peopleSurfacesVisible]. */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun visible(): Flow<Boolean> = combine(scanningHere(), accountManager.getPrimaryUserId()) { scan, userId ->
        scan to userId
    }
        .flatMapLatest { (scan, userId) ->
            if (scan || userId == null) flowOf(peopleSurfacesVisible(scan, signedIn = userId != null, accountHoldsPeople = false))
            else personDao.observeCountForUser(userId.id)
                .retryOnDbTear(TEAR_TAG)
                .map { count -> peopleSurfacesVisible(scan, signedIn = true, accountHoldsPeople = count > 0) }
        }
        .distinctUntilChanged()

    private companion object {
        const val TEAR_TAG = "PeopleVisibility"
    }
}

/**
 * The rule behind [PeopleVisibility.visible]: shown while this phone scans, or for a signed-in account that
 * holds people. A guest's people follow the scan switch alone.
 */
internal fun peopleSurfacesVisible(scanningHere: Boolean, signedIn: Boolean, accountHoldsPeople: Boolean): Boolean =
    scanningHere || (signedIn && accountHoldsPeople)
