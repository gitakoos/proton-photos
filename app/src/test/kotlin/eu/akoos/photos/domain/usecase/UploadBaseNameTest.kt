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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-value coverage for the cloud displayName a rename-on-upload derives from a source's capture
 * timestamp, extracted from [UploadPendingUseCase.uploadRenamedName]. The formatted timestamp is
 * timezone-dependent, so nothing here hardcodes a rendered date; the base is asserted structurally
 * against the `yyyy-MM-dd_HH-mm-ss` shape. No Android, no Context: plain JVM assertions.
 */
class UploadBaseNameTest {

    private val basePattern = Regex("^\\d{4}-\\d{2}-\\d{2}_\\d{2}-\\d{2}-\\d{2}$")
    private val dateTakenMs = 1_700_000_000_000L
    private val nowA = 1_500_000_000_000L
    private val nowB = 1_600_000_000_000L

    @Test
    fun `keeps the original extension and formats the base as a timestamp`() {
        val result = UploadPendingUseCase.uploadRenamedName("IMG.jpg", dateTakenMs, 0L)
        assertTrue("expected '$result' to end with .jpg", result.endsWith(".jpg"))
        val base = result.removeSuffix(".jpg")
        assertTrue("base '$base' did not match the timestamp shape", basePattern.matches(base))
    }

    @Test
    fun `preserves an uppercase or odd extension verbatim`() {
        assertTrue(UploadPendingUseCase.uploadRenamedName("photo.JPEG", dateTakenMs, 0L).endsWith(".JPEG"))
        assertTrue(UploadPendingUseCase.uploadRenamedName("clip.MP4", dateTakenMs, 0L).endsWith(".MP4"))
    }

    @Test
    fun `uses only the last segment of a multi-dot name as the extension`() {
        val result = UploadPendingUseCase.uploadRenamedName("my.holiday.png", dateTakenMs, 0L)
        assertTrue("expected '$result' to end with .png", result.endsWith(".png"))
        assertEquals("expected exactly one dot in '$result'", 1, result.count { it == '.' })
    }

    @Test
    fun `a name with no extension yields a bare timestamp with no trailing dot`() {
        val result = UploadPendingUseCase.uploadRenamedName("IMG", dateTakenMs, 0L)
        assertTrue("'$result' did not match the timestamp shape", basePattern.matches(result))
        assertFalse("expected no dot in '$result'", result.contains('.'))
    }

    @Test
    fun `a non-positive dateTaken formats nowMs instead`() {
        // Both 0 and a negative dateTaken fall back to nowMs, so a different nowMs must change the
        // result; the two fallbacks agree for a shared nowMs.
        for (nonPositive in listOf(0L, -5L)) {
            assertNotEquals(
                "dateTaken=$nonPositive must format nowMs, so distinct nowMs must differ",
                UploadPendingUseCase.uploadRenamedName("x.jpg", nonPositive, nowA),
                UploadPendingUseCase.uploadRenamedName("x.jpg", nonPositive, nowB),
            )
        }
        assertEquals(
            "0 and a negative dateTaken must both format the same nowMs identically",
            UploadPendingUseCase.uploadRenamedName("x.jpg", 0L, nowA),
            UploadPendingUseCase.uploadRenamedName("x.jpg", -5L, nowA),
        )
    }

    @Test
    fun `a positive dateTaken ignores nowMs`() {
        assertEquals(
            "a positive dateTaken must format dateTaken, not nowMs",
            UploadPendingUseCase.uploadRenamedName("x.jpg", dateTakenMs, nowA),
            UploadPendingUseCase.uploadRenamedName("x.jpg", dateTakenMs, nowB),
        )
    }
}
