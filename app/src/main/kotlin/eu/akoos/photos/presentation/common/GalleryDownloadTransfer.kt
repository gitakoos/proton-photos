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

package eu.akoos.photos.presentation.common

import android.content.Context
import eu.akoos.photos.R
import eu.akoos.photos.data.transfer.ItemCancels
import eu.akoos.photos.data.transfer.TransferCenter
import eu.akoos.photos.domain.entity.GalleryItem
import eu.akoos.photos.domain.usecase.DownloadPhotosUseCase

/**
 * One gallery download as the Activity screen shows it: a row per cloud photo, queued until it starts
 * downloading and gone once it is over, each with its own cancel, plus the batch's own cancel. Photos
 * already on the device finish at once and get no row. Rows are keyed by [GalleryItem.stableId], the
 * linkId of a cloud photo, which is the key [DownloadPhotosUseCase.downloadGalleryItems] reports.
 */
internal class GalleryDownloadTransfer(
    private val transferCenter: TransferCenter,
    context: Context,
    items: List<GalleryItem>,
    thumbUris: List<String>,
    onCancelBatch: () -> Unit,
) {
    val cancels = ItemCancels()
    private val downloadingLabel = context.getString(R.string.activity_downloading_state)
    private val queuedLabel = context.getString(R.string.upload_status_queued)

    val id: Long = transferCenter.start(
        TransferCenter.Kind.DOWNLOAD, items.size, items = thumbUris,
        itemKeys = items.map { it.stableId },
        itemStatus = items.filterIsInstance<GalleryItem.CloudOnly>().associate { it.stableId to queuedLabel },
        onCancel = onCancelBatch,
        onCancelItem = { cancels.cancel(it) },
    )

    fun onItemState(key: String, running: Boolean) {
        // A cancelled photo's row is already gone and must not come back as it winds down.
        if (cancels.isCancelled(key)) return
        transferCenter.setItemStatus(id, key, if (running) downloadingLabel else null)
    }

    fun onProgress(progress: DownloadPhotosUseCase.Progress) {
        transferCenter.progress(id, progress.done, progress.total)
    }

    fun finish() = transferCenter.finish(id)
}
