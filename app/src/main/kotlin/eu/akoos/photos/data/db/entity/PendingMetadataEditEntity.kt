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

package eu.akoos.photos.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import eu.akoos.photos.domain.entity.CloudPhoto
import eu.akoos.photos.domain.usecase.CloudWorkItem
import eu.akoos.photos.domain.usecase.LocationEdit

/**
 * One queued cloud/synced metadata edit, persisted so the durable drain survives a process kill. One
 * row per photo, keyed on the original cloud [linkId]: re-enqueuing the same photo replaces its older
 * pending edit. The [CloudPhoto] is NOT stored (the worker re-fetches it by linkId from the listing),
 * so the row holds primitives only and [toWorkItem] rebuilds the work item from a freshly fetched photo.
 *
 * [deviceUri] null means a cloud-only photo; non-null means a synced one (a device file exists) and
 * selects the synced write path. Each of [description] / [artist] / [copyright] is null to leave that
 * tag unchanged and an empty string to clear it, a distinction Room keeps as SQL NULL vs "".
 * [enqueuedAt] gives the drain its FIFO order.
 */
@Entity(tableName = "pending_metadata_edit")
data class PendingMetadataEditEntity(
    @PrimaryKey val linkId: String,
    val userId: String,
    val deviceUri: String?,
    val newCaptureMs: Long?,
    val locationMode: String,
    val lat: Double?,
    val lng: Double?,
    val description: String?,
    val artist: String?,
    val copyright: String?,
    val enqueuedAt: Long,
    /** Queue-internal resume state: the linkId of the corrected copy once its upload has returned,
     *  persisted BEFORE the album re-add and trash so a process kill here resumes from this link
     *  instead of re-uploading a second copy. Null until that upload succeeds; never part of a
     *  [CloudWorkItem] (the worker owns it), so [fromWorkItem] leaves it defaulted on a fresh insert. */
    val newLinkId: String? = null,
) {

    /** Reads [locationMode] + [lat]/[lng] back to the sealed [LocationEdit]. A "SET" row missing either
     *  coordinate falls back to [LocationEdit.Unchanged] rather than fabricating a place. */
    fun toLocationEdit(): LocationEdit = when (locationMode) {
        MODE_SET -> {
            val latitude = lat
            val longitude = lng
            if (latitude != null && longitude != null) LocationEdit.Set(latitude, longitude)
            else LocationEdit.Unchanged
        }
        MODE_CLEAR -> LocationEdit.Clear
        else -> LocationEdit.Unchanged
    }

    /** Rebuilds the work item from this row plus the freshly fetched [photo]. */
    fun toWorkItem(photo: CloudPhoto): CloudWorkItem = CloudWorkItem(
        photo = photo,
        newCaptureMs = newCaptureMs,
        location = toLocationEdit(),
        deviceUri = deviceUri,
        description = description,
        artist = artist,
        copyright = copyright,
    )

    companion object {
        const val MODE_UNCHANGED = "UNCHANGED"
        const val MODE_CLEAR = "CLEAR"
        const val MODE_SET = "SET"

        /** Flattens a work item to a row: the linkId comes from the photo, the location collapses to
         *  mode + lat/lng, and the three text tags carry through unchanged. */
        fun fromWorkItem(item: CloudWorkItem, userId: String, enqueuedAt: Long): PendingMetadataEditEntity {
            val mode: String
            val lat: Double?
            val lng: Double?
            when (val location = item.location) {
                is LocationEdit.Set -> {
                    mode = MODE_SET
                    lat = location.latitude
                    lng = location.longitude
                }
                LocationEdit.Clear -> {
                    mode = MODE_CLEAR
                    lat = null
                    lng = null
                }
                LocationEdit.Unchanged -> {
                    mode = MODE_UNCHANGED
                    lat = null
                    lng = null
                }
            }
            return PendingMetadataEditEntity(
                linkId = item.photo.linkId,
                userId = userId,
                deviceUri = item.deviceUri,
                newCaptureMs = item.newCaptureMs,
                locationMode = mode,
                lat = lat,
                lng = lng,
                description = item.description,
                artist = item.artist,
                copyright = item.copyright,
                enqueuedAt = enqueuedAt,
            )
        }
    }
}
