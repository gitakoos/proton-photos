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

package eu.akoos.photos.data.sdk

import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import me.proton.core.domain.entity.UserId
import me.proton.drive.sdk.DownloadAbortedException
import me.proton.drive.sdk.entity.LegacyNodeUid
import me.proton.drive.sdk.entity.PhotosDownloaderRequest
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/** Thrown when the SDK cannot deliver a fully verified photo, so the app's own download runs instead. */
class SdkDownloadException(message: String, cause: Throwable? = null) : IllegalStateException(message, cause)

/**
 * A photo's full-resolution bytes through the Drive SDK's Photos client: the SDK fetches the blocks,
 * decrypts them and checks the manifest signature, streaming the plaintext into the caller's temp file, so
 * the cache, the atomic rename and the de-duplication of [eu.akoos.photos.data.repository.drive.PhotoDownloadService]
 * stay as they are. A download the SDK could not verify is a failure here, never a result. How many run at
 * once is the callers' choice, as on the app's own path; the SDK fetches each file's blocks in parallel
 * itself. Run only through [SdkRouting] ([SdkRoute.DOWNLOAD]).
 */
@Singleton
class SdkPhotoDownloadOps @Inject constructor(
    private val clientProvider: SdkDriveClientProvider,
) {
    suspend fun streamToFile(
        userId: UserId,
        volumeId: String,
        linkId: String,
        tempFile: File,
        onProgress: ((doneBytes: Long, totalBytes: Long) -> Unit)?,
        fallbackTotal: Long,
    ) {
        clientProvider.withPhotosClient(userId) { client ->
            val downloader = client.downloader(
                PhotosDownloaderRequest(nodeUid = LegacyNodeUid(volumeId = volumeId, linkId = linkId), noWaiting = false),
            )
            try {
                coroutineScope {
                    FileOutputStream(tempFile).use { out ->
                        val controller = downloader.downloadToStream(coroutineScope = this, channel = out.channel)
                        try {
                            val progress = controller.progressFlow
                                .filterNotNull()
                                .onEach { onProgress?.invoke(it.bytesCompleted, it.bytesInTotal ?: fallbackTotal) }
                                .launchIn(this)
                            controller.tryResume(this)
                            try {
                                controller.awaitCompletion()
                            } catch (e: DownloadAbortedException) {
                                val unverified = runCatching { controller.isDownloadCompleteWithVerificationIssue() }.getOrDefault(false)
                                throw SdkDownloadException(if (unverified) "downloaded but not verified" else "download aborted", e)
                            }
                            progress.cancel()
                        } finally {
                            controller.close()
                        }
                    }
                }
            } finally {
                downloader.close()
            }
        } ?: throw SdkDownloadException("the SDK cannot run on this device")
    }
}

/**
 * Whether a file the SDK wrote can stand as the photo: some bytes arrived, and as many as Drive says the
 * photo holds when it says ([expectedBytes] 0 means it did not), the same rule the cache check applies.
 */
internal fun sdkDownloadComplete(actualBytes: Long, expectedBytes: Long): Boolean =
    actualBytes > 0 && (expectedBytes == 0L || actualBytes == expectedBytes)
