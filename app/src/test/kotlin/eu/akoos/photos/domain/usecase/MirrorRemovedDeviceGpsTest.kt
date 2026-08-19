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

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins when an upload's mirror step has actually rewritten the on-device file GPS-free, the only case
 * that makes that file's stored `photo_location` fix stale, extracted from
 * [UploadPendingUseCase.mirrorRemovedDeviceGps]. The stakes are one-directional: dropping the row when
 * the device file still holds its coordinates makes the photo vanish from the map for good (the GPS
 * backfill skips any file that already has a row), so a plain strip-on-upload with no mirror, or a
 * mirror whose device write was refused, must stay a no-op. All three conditions have to hold. No
 * Android, no Context, no database: plain JVM assertions.
 */
class MirrorRemovedDeviceGpsTest {

    @Test
    fun `a mirror strip that removed GPS and landed the device rewrite is stale`() {
        assertTrue(
            UploadPendingUseCase.mirrorRemovedDeviceGps(
                stripGpsOnUpload = true,
                mirrorToLocal = true,
                deviceRewriteSucceeded = true,
            ),
        )
    }

    @Test
    fun `a strip with no mirror leaves the device fix valid`() {
        // The data-loss guard: with no mirror only a temp copy is stripped, the device file keeps its
        // GPS, so its row still describes the bytes and must survive.
        assertFalse(
            UploadPendingUseCase.mirrorRemovedDeviceGps(
                stripGpsOnUpload = true,
                mirrorToLocal = false,
                deviceRewriteSucceeded = true,
            ),
        )
    }

    @Test
    fun `a mirror rewrite that spared GPS is not stale`() {
        // A timestamp-only strip rewrites the file but leaves the coordinates, so the fix still stands.
        assertFalse(
            UploadPendingUseCase.mirrorRemovedDeviceGps(
                stripGpsOnUpload = false,
                mirrorToLocal = true,
                deviceRewriteSucceeded = true,
            ),
        )
    }

    @Test
    fun `a mirror strip whose device rewrite failed is not stale`() {
        // The OS refused the in-place write, so the original is intact and its fix is untouched.
        assertFalse(
            UploadPendingUseCase.mirrorRemovedDeviceGps(
                stripGpsOnUpload = true,
                mirrorToLocal = true,
                deviceRewriteSucceeded = false,
            ),
        )
    }

    @Test
    fun `only all three conditions together make the fix stale`() {
        for (stripGps in listOf(false, true)) {
            for (mirror in listOf(false, true)) {
                for (rewrite in listOf(false, true)) {
                    val expected = stripGps && mirror && rewrite
                    val actual = UploadPendingUseCase.mirrorRemovedDeviceGps(stripGps, mirror, rewrite)
                    if (expected) {
                        assertTrue("stripGps=$stripGps mirror=$mirror rewrite=$rewrite", actual)
                    } else {
                        assertFalse("stripGps=$stripGps mirror=$mirror rewrite=$rewrite", actual)
                    }
                }
            }
        }
    }
}
