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

import eu.akoos.photos.data.db.dao.SyncStateLite
import eu.akoos.photos.domain.entity.CloudPhoto
import eu.akoos.photos.domain.entity.LinkBasis
import eu.akoos.photos.domain.entity.LocalMediaItem
import eu.akoos.photos.domain.entity.SyncStatus

/**
 * Whether a sync row's link of [local] to [cloud] shows them as one item, by what the link rests on
 * ([LinkBasis]). A proven link always does. A link reconcile made by name does while the file still
 * agrees with the Drive photo on the exact name and capture second; one whose upload rewrote the
 * bytes ([LinkBasis.NAME_REWRITTEN]) also takes the re-encoded name. Drive's listing reports no size,
 * so the size is not weighed. A file with the same name and another capture second shows apart.
 *
 * The one check behind every surface that says a Drive photo is also on the device: the merged
 * library, the gallery's and the viewer's on-device lookups and an album's member-to-device map.
 */
internal fun linkShowsAsOne(basis: Int, local: LocalMediaItem, cloud: CloudPhoto): Boolean =
    linkShowsAsOne(basis, local, cloud.displayName, cloud.captureTime)

/** [linkShowsAsOne] on the Drive photo's name and capture second (Unix seconds) alone. */
internal fun linkShowsAsOne(basis: Int, local: LocalMediaItem, cloudName: String, cloudCaptureTime: Long): Boolean {
    if (basis == LinkBasis.PROVEN) return true
    if (local.dateTaken / 1000L != cloudCaptureTime) return false
    return cloudName == local.displayName ||
        (basis == LinkBasis.NAME_REWRITTEN &&
            cloudName == UploadFormatNaming.reencodedName(local.displayName, local.mimeType))
}

/**
 * Whether the bytes show a link made by name ([LinkBasis.NAME]) to be false: the device file's SHA-1 in
 * Drive's form ([fileDriveHash]) is not the Drive photo's content hash ([driveHash]), and the file does
 * not agree with the photo on the exact name and capture second either ([showsAsOne], see
 * [linkShowsAsOne]). Such a file is another photo that only shares a name with the Drive one: left
 * linked it would never be backed up. A file that does agree on name and second keeps its link, as a
 * download this app stamped (a date or a place written in) no longer hashes to Drive's copy. A link of
 * rewritten bytes ([LinkBasis.NAME_REWRITTEN]) can never be checked by hash, and a proven one needs no check.
 */
internal fun nameLinkDisproved(basis: Int, fileDriveHash: String?, driveHash: String?, showsAsOne: Boolean): Boolean =
    basis == LinkBasis.NAME &&
        !fileDriveHash.isNullOrEmpty() &&
        !driveHash.isNullOrEmpty() &&
        fileDriveHash != driveHash &&
        !showsAsOne

/**
 * The two sides the merge weighs a sync link on: the device files and the Drive photos of the own
 * stream, as [GetGalleryItemsUseCase.linkSides] reads them before any album hide drops a photo.
 */
data class LinkSides(val local: List<LocalMediaItem>, val cloud: List<CloudPhoto>) {
    companion object {
        val NONE = LinkSides(emptyList(), emptyList())
    }
}

/**
 * Which SYNCED rows of [rows] show their device file and Drive photo as one item, judged by
 * [linkShowsAsOne] on the device file and the Drive photo the merge itself would weigh, taken from
 * [sides]. A proven row passes on its own. A row resting on a name passes only when both its file and
 * its photo are known and still agree, so a photo the gallery shows apart is never counted as on the
 * device. [moreCloud] adds Drive photos the stream does not list, such as an album's own members; the
 * stream's copy of a photo wins. The sides are walked once, and only when some row rests on a name.
 */
internal fun syncedRowsShownAsOne(
    rows: List<SyncStateLite>,
    sides: LinkSides,
    moreCloud: List<CloudPhoto> = emptyList(),
): (SyncStateLite) -> Boolean {
    val named = rows.filter {
        it.status == SyncStatus.SYNCED && it.linkBasis != LinkBasis.PROVEN &&
            it.cloudFileId != null && it.localUri.isNotBlank()
    }
    if (named.isEmpty()) return { it.linkBasis == LinkBasis.PROVEN }
    val uris = named.mapTo(HashSet()) { it.localUri }
    val cloudIds = named.mapNotNullTo(HashSet()) { it.cloudFileId }
    val localByUri = HashMap<String, LocalMediaItem>()
    for (local in sides.local) if (local.uri in uris) localByUri.putIfAbsent(local.uri, local)
    val cloudByLinkId = HashMap<String, CloudPhoto>()
    for (cloud in sides.cloud) if (cloud.linkId in cloudIds) cloudByLinkId.putIfAbsent(cloud.linkId, cloud)
    for (cloud in moreCloud) if (cloud.linkId in cloudIds) cloudByLinkId.putIfAbsent(cloud.linkId, cloud)
    return { row ->
        row.linkBasis == LinkBasis.PROVEN || run {
            val local = localByUri[row.localUri]
            val cloud = row.cloudFileId?.let { cloudByLinkId[it] }
            local != null && cloud != null && linkShowsAsOne(row.linkBasis, local, cloud)
        }
    }
}
