/*
 * Photos for Proton
 * Copyright (C) 2026 Akoos <https://akoos.eu>
 *
 * Source:  https://github.com/gitakoos/proton-photos
 * Website: https://photos.akoos.eu
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

package eu.akoos.photos.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import eu.akoos.photos.domain.entity.Album

/**
 * Cached cloud-album snapshot for instant cold-launch paint. Mirrors [Album] except
 * [Album.coverThumbnailUrl] — an expiring decrypted CDN URL we don't keep at rest; the repository
 * re-derives it on demand from the encrypted cover-photo material.
 */
@Entity(tableName = "cloud_albums")
data class CloudAlbumEntity(
    @PrimaryKey val linkId: String,
    val name: String,
    val photoCount: Int,
    val coverLinkId: String? = null,
    val lastActivityTimeMs: Long? = null,
    /** Non-null when the album has been shared (contains the child share ID). */
    val sharingShareId: String? = null,
    /** Non-null when a public share URL exists for this album. */
    val sharingShareUrlId: String? = null,
    /** Non-null for shared-with-me albums — the email of the user who shared it. */
    val sharedByEmail: String? = null,
    /** The volume this album lives in; may differ from the current user's own volume for shared-with-me albums. */
    val volumeId: String? = null,
    /** Wall-clock ms when the repository last refreshed this row from the network. */
    @ColumnInfo(defaultValue = "0")
    val lastFetchedMs: Long = 0L,
) {
    fun toDomain(): Album = Album(
        linkId = linkId,
        name = name,
        photoCount = photoCount,
        coverLinkId = coverLinkId,
        lastActivityTimeMs = lastActivityTimeMs,
        coverThumbnailUrl = null,
        sharingShareId = sharingShareId,
        sharingShareUrlId = sharingShareUrlId,
        sharedByEmail = sharedByEmail,
        volumeId = volumeId,
    )

    companion object {
        fun fromDomain(album: Album, nowMs: Long = System.currentTimeMillis()): CloudAlbumEntity =
            CloudAlbumEntity(
                linkId = album.linkId,
                name = album.name,
                photoCount = album.photoCount,
                coverLinkId = album.coverLinkId,
                lastActivityTimeMs = album.lastActivityTimeMs,
                sharingShareId = album.sharingShareId,
                sharingShareUrlId = album.sharingShareUrlId,
                sharedByEmail = album.sharedByEmail,
                volumeId = album.volumeId,
                lastFetchedMs = nowMs,
            )
    }
}
