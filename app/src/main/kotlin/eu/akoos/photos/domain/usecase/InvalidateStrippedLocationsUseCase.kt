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

import android.util.Log
import eu.akoos.photos.data.db.dao.PhotoLocationDao
import eu.akoos.photos.data.db.entity.PhotoLocationEntity
import eu.akoos.photos.util.MetadataStripConfig
import eu.akoos.photos.util.forEachSqlChunk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import me.proton.core.accountmanager.domain.AccountManager
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The stored-fix ids a landed strip of [config] over [strippedUris] makes stale.
 *
 * Two things decide the answer. A config that leaves [MetadataStripConfig.stripGps] off never touches
 * the coordinates in the file, so the stored fix still describes the bytes and has to stand: a
 * timestamp-only strip invalidates nothing. And every id is a device content URI, the key
 * `photo_location` records a local fix under, which is the conclusion the metadata editor already
 * reaches: the cloud side of a synced pair is keyed by linkId and holds coordinates decrypted from
 * the server XAttr, which no device-side write reaches, so dropping that row would lose a fix nothing
 * re-derives. Every strip surface hands in device URIs alone, so the rule is a filter, not a mapping.
 *
 * Pure, so it is verifiable without a ViewModel or a database.
 */
fun strippedLocationIds(config: MetadataStripConfig, strippedUris: Collection<String>): List<String> =
    if (!config.stripGps) emptyList() else strippedUris.distinct()

/**
 * Drops the stored GPS fix of the device files a strip just removed location from.
 *
 * The map, Search's place facet and the location screen all plot `photo_location` rows, and the GPS
 * backfill skips any file that already has one, so a row left standing keeps every one of them on
 * coordinates the file no longer carries, for good. Removing it clears the point from the live
 * queries and puts the file back in the backfill's queue, which then finds no location and records
 * none. Best-effort: a delete failure costs a stale point until the next strip and never fails the
 * strip it rides on.
 */
@Singleton
class InvalidateStrippedLocationsUseCase @Inject constructor(
    private val photoLocationDao: PhotoLocationDao,
    private val accountManager: AccountManager,
) {

    suspend operator fun invoke(config: MetadataStripConfig, strippedUris: Collection<String>) {
        val ids = strippedLocationIds(config, strippedUris)
        if (ids.isEmpty()) return
        // A guest strips GPS the same as a signed-in user; drop the stored fix under the local
        // partition so the map, Search places and location screen stop plotting the old point.
        val account = accountManager.getPrimaryUserId().first()?.id ?: PhotoLocationEntity.LOCAL_USER
        try {
            ids.forEachSqlChunk { photoLocationDao.deleteByIds(account, it) }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.w(TAG, "stored location drop for ${ids.size} stripped files failed: ${e.message}")
        }
    }

    private companion object {
        const val TAG = "StripLocationInvalidate"
    }
}
