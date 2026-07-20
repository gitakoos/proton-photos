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

package eu.akoos.photos.data.repository.drive

import eu.akoos.photos.data.db.dao.AlbumPhotoMembershipDao
import eu.akoos.photos.data.db.dao.CloudAlbumDao
import eu.akoos.photos.data.db.dao.PhotoListingDao
import eu.akoos.photos.util.forEachSqlChunk
import me.proton.core.domain.entity.UserId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The one place an album's local cache is torn down, shared by the two paths that do it: leaving an
 * album shared with this user, and removing an owned one. Both face the same ownership question,
 * and the answer decides whether photo rows are cleaned up or destroyed.
 */
@Singleton
class AlbumCacheCleanup @Inject constructor(
    private val photoListingDao: PhotoListingDao,
    private val cloudAlbumDao: CloudAlbumDao,
    private val albumPhotoMembershipDao: AlbumPhotoMembershipDao,
) {

    /**
     * Removes every local trace of [albumLinkId] for [userId]: its photo rows where they belong to
     * the album alone, its membership edges, and the cached album row.
     *
     * Only an album shared WITH this user gives up its photo rows. Such a row carries the OWNER's
     * volumeId, is parented to the album, and exists purely to paint it, so once the album is gone
     * it can never be reached again. An owned album's photos live in the photos root and belong to
     * the timeline whether or not the album exists, so the same delete there would take the user's
     * own photos away along with the album.
     *
     * Statement order is the failure plan. The edges are the only record of which rows were the
     * album's, so they are read first and dropped last: a statement that throws part-way leaves a
     * recoverable state where the rows can still be identified, rather than stranding them with
     * nothing left pointing at them.
     */
    suspend fun dropCachedAlbum(userId: UserId, albumLinkId: String) {
        if (cloudAlbumDao.isSharedWithMe(albumLinkId)) {
            // An album's membership is unbounded, so the id list is sliced to stay inside SQLite's
            // per-statement host-variable cap.
            albumPhotoMembershipDao.getPhotoLinkIds(albumLinkId)
                .forEachSqlChunk { photoListingDao.deleteByLinkIds(userId.id, it) }
        }
        albumPhotoMembershipDao.deleteAllForAlbum(albumLinkId)
        cloudAlbumDao.deleteByLinkId(albumLinkId)
    }
}
