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

package eu.akoos.photos.util

/**
 * Pure rule for which rows of the local-tag cache (LocalTagDao) a finished media scan may delete.
 *
 * The cache gains a row per device file and the library churns, so without a prune the table grows
 * for as long as the app is installed. What a scan can and cannot prove about a missing file is the
 * general question, and [MediaScanCoverage] answers it for every store that prunes this way; this
 * cache then adds ONE condition of its own on top:
 *
 *  - the row carries no user-chosen categories. Everything else in a row is a detection the next
 *    scan recomputes, so dropping one costs a re-detect at worst, while a chosen category is an
 *    answer only a person can give and no scan can put back. LocalTagDao.clearDetections draws the
 *    same line for the same reason. The case that makes this necessary is a trashed file: it still
 *    exists, it is restorable for weeks, and a default MediaStore query omits it.
 *
 * The table stays bounded regardless, because the rows that scale with the library are the
 * detections; the choices are bounded by how many photos a person cares to categorise.
 */
object LocalTagPrune {

    /**
     * The subset of [cachedUris] whose files this scan proves are gone, ready for
     * LocalTagDao.deleteByUris. Empty when nothing qualifies, so the caller can skip the delete.
     *
     * [scannedRoots] carries the collection uris whose query SUCCEEDED, as [MediaScanCoverage]
     * describes.
     *
     * [userTagsCsvOf] answers with the row's stored choice, the empty string when it holds none. It
     * is a lookup rather than a second collection so the caller can read straight off the row map it
     * already has, without building a parallel copy on a path that runs per media scan.
     */
    fun staleUris(
        cachedUris: Collection<String>,
        liveUris: Set<String>,
        scannedRoots: Collection<String>,
        userTagsCsvOf: (String) -> String,
    ): List<String> =
        MediaScanCoverage.provenDeleted(cachedUris, liveUris, scannedRoots)
            .filter { userTagsCsvOf(it).isEmpty() }
}
