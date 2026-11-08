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

import eu.akoos.photos.data.db.dao.PhotoListingDao
import eu.akoos.photos.data.db.entity.LinkIdName
import eu.akoos.photos.domain.repository.SyncStateRepository
import eu.akoos.photos.util.flatMapSqlChunks
import me.proton.core.domain.entity.UserId
import javax.inject.Inject

/** Two capture times this far apart are not the same photo, whatever their names. */
internal const val NAME_PAIR_DATE_SLACK_MS = 24 * 60 * 60 * 1000L

/**
 * The Drive photo each moved file pairs with by name, for the files the backup state does not pair: exactly one
 * photo of the user's own library carries its name, case aside (the name-only pairing of
 * [GetGalleryItemsUseCase.merge]). A name more than one Drive photo carries, or a Drive photo two moved files would
 * both claim, pairs with nothing. Where both dates are known ([takenMsByUri], the Drive photo's capture time) they
 * must be within a day, so a photo that only shares a camera's file name with the device file is never taken out
 * of an album or put into one in its place; a date either side does not know leaves the name to decide, as a
 * re-downloaded PNG's often is.
 */
internal fun pairByUniqueName(
    namesByUri: Map<String, String>,
    ownStream: List<LinkIdName>,
    takenMsByUri: Map<String, Long> = emptyMap(),
): Map<String, String> {
    val byName = ownStream.groupBy { it.displayName.lowercase() }
    val paired = namesByUri.mapNotNull { (uri, name) ->
        byName[name.lowercase()]?.singleOrNull()
            ?.takeIf { sameDayOrUnknown(takenMsByUri[uri] ?: 0L, it.captureTime * 1000L) }
            ?.let { uri to it.linkId }
    }
    val claims = paired.groupingBy { it.second }.eachCount()
    return paired.filter { claims.getValue(it.second) == 1 }.toMap()
}

private fun sameDayOrUnknown(a: Long, b: Long): Boolean = a <= 0L || b <= 0L || kotlin.math.abs(a - b) <= NAME_PAIR_DATE_SLACK_MS

/**
 * The Drive photo of each device file, paired the way the timeline pairs them for display: the backup state first,
 * then a unique file name ([pairByUniqueName]). So a photo the timeline shows with a green cloud is one this finds,
 * whether this app uploaded it or it reached Drive another way.
 */
class DrivePairLookup @Inject constructor(
    private val syncStateRepo: SyncStateRepository,
    private val photoListingDao: PhotoListingDao,
) {
    /** Device uri to Drive link for the files of [namesByUri] (uri to file name) that are on Drive; [takenMsByUri]
     *  holds the device files' capture times, which a pairing by name must agree with. */
    suspend fun pairs(
        userId: UserId,
        namesByUri: Map<String, String?>,
        takenMsByUri: Map<String, Long> = emptyMap(),
    ): Map<String, String> {
        if (namesByUri.isEmpty()) return emptyMap()
        val bySync = syncStateRepo.cloudPairedLinkIds(userId, namesByUri.keys.toList())
        val unpaired = namesByUri
            .filter { (uri, name) -> uri !in bySync && !name.isNullOrEmpty() }
            .mapValues { it.value!! }
        if (unpaired.isEmpty()) return bySync
        val ownStream = unpaired.values.map { it.lowercase() }.distinct()
            .flatMapSqlChunks { photoListingDao.ownStreamByLowerNames(userId.id, it) }
        val taken = bySync.values.toSet()
        return bySync + pairByUniqueName(unpaired, ownStream, takenMsByUri).filterValues { it !in taken }
    }
}
