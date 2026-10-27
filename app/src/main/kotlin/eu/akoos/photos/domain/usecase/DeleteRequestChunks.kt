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

/**
 * Platform ceiling for URIs handed to [android.provider.MediaStore.createDeleteRequest] /
 * [android.provider.MediaStore.createTrashRequest] in one shot (Baklava / API 36 docs; also a
 * practical Binder size guard on earlier targets). Reclaim paths must never build a single request
 * larger than this.
 */
const val DELETE_REQUEST_MAX_URIS = 2_000

/**
 * Returns the next stable-order URI batch for a MediaStore delete/trash request builder.
 *
 * Callers remove a completed batch from their queue before requesting the next one. Keeping this
 * helper single-batch avoids materialising a potentially large list of chunks when only the first
 * can be launched. Empty input returns an empty list.
 */
fun nextMediaStoreRequestBatch(
    uris: Iterable<String>,
    max: Int = DELETE_REQUEST_MAX_URIS,
): List<String> {
    require(max > 0) { "max must be positive, was $max" }
    return uris.take(max)
}
