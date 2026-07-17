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

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure-value coverage for how an upload chooses the capture time it stamps onto its Drive copy,
 * extracted from [UploadPendingUseCase.resolveUploadCaptureTimeMs]. The EXIF read that produces the
 * fallback stays in the use case (it is I/O); only the choice between the sources is pinned here.
 * Precedence: strip-timestamp floors to now; otherwise an explicit MediaStore DATE_TAKEN wins;
 * otherwise a non-explicit MediaStore date falls back to a positive EXIF DateTimeOriginal; with none
 * of those the MediaStore date is kept. No Android, no Context, no real image: plain JVM assertions.
 */
class UploadCaptureTimeTest {

    private val nowMs = 2_000_000_000_000L
    private val mediaStoreMs = 1_700_000_000_000L
    private val exifMs = 1_500_000_000_000L

    @Test
    fun `strip-timestamp floors the capture time to now regardless of the other sources`() {
        for (explicit in listOf(true, false)) {
            for (exif in listOf<Long?>(null, 0L, exifMs)) {
                assertEquals(
                    "strip must always floor to now (explicit=$explicit, exif=$exif)",
                    nowMs,
                    UploadPendingUseCase.resolveUploadCaptureTimeMs(
                        mediaStoreDateTakenMs = mediaStoreMs,
                        dateTakenIsExplicit = explicit,
                        exifDateTimeOriginalMs = exif,
                        stripTimestamp = true,
                        nowMs = nowMs,
                    ),
                )
            }
        }
    }

    @Test
    fun `an explicit MediaStore date wins over EXIF`() {
        assertEquals(
            mediaStoreMs,
            UploadPendingUseCase.resolveUploadCaptureTimeMs(
                mediaStoreDateTakenMs = mediaStoreMs,
                dateTakenIsExplicit = true,
                exifDateTimeOriginalMs = exifMs,
                stripTimestamp = false,
                nowMs = nowMs,
            ),
        )
    }

    @Test
    fun `a non-explicit MediaStore date falls back to the EXIF capture date`() {
        assertEquals(
            exifMs,
            UploadPendingUseCase.resolveUploadCaptureTimeMs(
                mediaStoreDateTakenMs = mediaStoreMs,
                dateTakenIsExplicit = false,
                exifDateTimeOriginalMs = exifMs,
                stripTimestamp = false,
                nowMs = nowMs,
            ),
        )
    }

    @Test
    fun `a non-explicit MediaStore date is kept when there is no usable EXIF date`() {
        // null EXIF (not an image, or the read/parse failed) and a non-positive EXIF both fall
        // through to the MediaStore fallback date.
        for (exif in listOf<Long?>(null, 0L, -1L)) {
            assertEquals(
                "no usable exif ($exif) must keep the MediaStore date",
                mediaStoreMs,
                UploadPendingUseCase.resolveUploadCaptureTimeMs(
                    mediaStoreDateTakenMs = mediaStoreMs,
                    dateTakenIsExplicit = false,
                    exifDateTimeOriginalMs = exif,
                    stripTimestamp = false,
                    nowMs = nowMs,
                ),
            )
        }
    }

    @Test
    fun `an explicit MediaStore date is kept when EXIF is absent and strip is off`() {
        assertEquals(
            mediaStoreMs,
            UploadPendingUseCase.resolveUploadCaptureTimeMs(
                mediaStoreDateTakenMs = mediaStoreMs,
                dateTakenIsExplicit = true,
                exifDateTimeOriginalMs = null,
                stripTimestamp = false,
                nowMs = nowMs,
            ),
        )
    }
}
