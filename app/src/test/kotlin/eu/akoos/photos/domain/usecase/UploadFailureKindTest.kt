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

import eu.akoos.photos.data.repository.drive.EmptySourceFileException
import eu.akoos.photos.data.repository.drive.UploadSourceChangedException
import eu.akoos.photos.domain.entity.LocalMediaItem
import eu.akoos.photos.domain.entity.QueueSource
import eu.akoos.photos.domain.entity.SyncState
import eu.akoos.photos.domain.entity.SyncStatus
import me.proton.core.network.domain.ApiException
import me.proton.core.network.domain.ApiResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.UnknownHostException

/**
 * How a failed upload leaves its photo ([uploadFailureKind]), and when a file set aside as unuploadable
 * is tried again ([unuploadableRetryDue]).
 */
class UploadFailureKindTest {

    private fun protonError(httpCode: Int, protonCode: Int) = ApiException(
        ApiResult.Error.Http(
            httpCode = httpCode,
            message = "http $httpCode",
            proton = ApiResult.Error.ProtonData(code = protonCode, error = "refused"),
        ),
    )

    @Test
    fun `an empty file is unuploadable`() {
        assertEquals(UploadFailureKind.UNUPLOADABLE, uploadFailureKind(EmptySourceFileException()))
    }

    @Test
    fun `an empty file wrapped by the upload layer is unuploadable`() {
        assertEquals(
            UploadFailureKind.UNUPLOADABLE,
            uploadFailureKind(IllegalStateException("upload failed", EmptySourceFileException())),
        )
    }

    @Test
    fun `this app's own encrypted bytes are unuploadable`() {
        assertEquals(
            UploadFailureKind.UNUPLOADABLE,
            uploadFailureKind(eu.akoos.photos.data.localcrypto.EncryptedBytesException()),
        )
    }

    @Test
    fun `a file that changed while it was read retries`() {
        assertEquals(UploadFailureKind.RETRY, uploadFailureKind(UploadSourceChangedException("changed during the upload")))
    }

    @Test
    fun `Drive refusing the request built from the file is a refusal`() {
        assertEquals(UploadFailureKind.REFUSED, uploadFailureKind(protonError(422, 2000)))
        assertEquals(UploadFailureKind.REFUSED, uploadFailureKind(protonError(400, 2001)))
    }

    @Test
    fun `a refusal through the SDK route is a refusal`() {
        val sdk = me.proton.drive.sdk.SdkHttpClientException.Http(422, 2001, null, "refused")

        assertEquals(UploadFailureKind.REFUSED, uploadFailureKind(sdk))
    }

    @Test
    fun `an answer that would meet every file alike retries`() {
        assertEquals(UploadFailureKind.RETRY, uploadFailureKind(protonError(422, 200002)))
        assertEquals(UploadFailureKind.RETRY, uploadFailureKind(protonError(422, 2501)))
        assertEquals(UploadFailureKind.RETRY, uploadFailureKind(protonError(422, 2011)))
        assertEquals(UploadFailureKind.RETRY, uploadFailureKind(protonError(403, 2001)))
    }

    @Test
    fun `a network failure retries`() {
        assertEquals(
            UploadFailureKind.RETRY,
            uploadFailureKind(ApiException(ApiResult.Error.Connection(false, UnknownHostException("no host")))),
        )
    }

    @Test
    fun `a batch made only of refused files is not worth retrying`() {
        val result = UploadPendingUseCase.Result(attempted = 2, successCount = 0, refusedCount = 2)

        assertTrue(result.allFailed)
        assertFalse(result.retryWorthwhile)
    }

    @Test
    fun `a batch with one failure besides the refusals is worth retrying`() {
        val result = UploadPendingUseCase.Result(attempted = 2, successCount = 0, refusedCount = 1)

        assertTrue(result.retryWorthwhile)
    }

    private fun setAside(sizeBytes: Long, at: Long?) = SyncState(
        localUri = "content://media/1",
        cloudFileId = null,
        localHash = "",
        cloudHash = null,
        status = SyncStatus.LOCAL_ONLY,
        lastSyncAttemptMs = 0L,
        lastSyncSuccessMs = null,
        backedUpAtMs = null,
        sizeBytes = sizeBytes,
        queued = false,
        queueSource = QueueSource.UNUPLOADABLE,
        queuedAt = at,
    )

    private fun device(sizeBytes: Long, modifiedSec: Long) = LocalMediaItem(
        uri = "content://media/1",
        dateTaken = 0L,
        displayName = "empty.jpg",
        mimeType = "image/jpeg",
        sizeBytes = sizeBytes,
        bucketName = "Camera",
        dateModified = modifiedSec,
    )

    @Test
    fun `an unchanged file stays set aside`() {
        assertFalse(unuploadableRetryDue(setAside(0L, at = 10_000L), device(0L, modifiedSec = 9L), appUpdatedAtMs = 5_000L))
    }

    @Test
    fun `a file whose size changed is tried again`() {
        assertTrue(unuploadableRetryDue(setAside(0L, at = 10_000L), device(2_048L, modifiedSec = 9L), appUpdatedAtMs = 5_000L))
    }

    @Test
    fun `a file modified after it was set aside is tried again`() {
        assertTrue(unuploadableRetryDue(setAside(0L, at = 10_000L), device(0L, modifiedSec = 11L), appUpdatedAtMs = 5_000L))
    }

    @Test
    fun `an app update after the file was set aside tries it again`() {
        assertTrue(unuploadableRetryDue(setAside(0L, at = 10_000L), device(0L, modifiedSec = 9L), appUpdatedAtMs = 20_000L))
    }

    @Test
    fun `a row with no moment recorded is tried again`() {
        assertTrue(unuploadableRetryDue(setAside(0L, at = null), device(0L, modifiedSec = 9L), appUpdatedAtMs = 5_000L))
    }
}
