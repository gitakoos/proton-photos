/*
 * Photos for Proton
 * Copyright (C) 2026 Akoos <https://akoos.eu>
 *
 * Source:  https://github.com/gitakoos/proton-photos
 * Website: https://photos.akoos.eu
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

package eu.akoos.photos.domain.entity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-value coverage for [CloudPhoto.captureTimeMs] (seconds → ms promotion), the favorite-tag
 * derivation, and the shared [TimestampSanity] floor/fallback rule that every photo-list sort
 * leans on. No Android, no Robolectric — these are plain arithmetic/branch assertions.
 */
class CloudPhotoTest {

    private fun cloud(captureTimeSec: Long, tags: Set<Int> = emptySet()) = CloudPhoto(
        linkId = "link1",
        shareId = "share1",
        volumeId = "vol1",
        captureTime = captureTimeSec,
        displayName = "photo.jpg",
        mimeType = "image/jpeg",
        sizeBytes = 1024L,
        thumbnailUrl = null,
        revisionId = "rev1",
        tags = tags,
    )

    // ─── captureTimeMs: seconds → milliseconds ────────────────────────────────

    @Test
    fun `captureTimeMs promotes epoch seconds to milliseconds`() {
        // 1_700_000_000s (2023-11-14) → ×1000 ms.
        assertEquals(1_700_000_000_000L, cloud(1_700_000_000L).captureTimeMs)
    }

    @Test
    fun `captureTimeMs of zero stays zero`() {
        // Drive returns 0 when it holds no capture timestamp; the property does NOT floor it —
        // that decision lives in TimestampSanity / GalleryItem.Synced, asserted below.
        assertEquals(0L, cloud(0L).captureTimeMs)
    }

    @Test
    fun `captureTimeMs is exactly captureTime times one thousand`() {
        val sec = 42L
        assertEquals(sec * 1000L, cloud(sec).captureTimeMs)
    }

    // ─── isFavoriteOnCloud ────────────────────────────────────────────────────

    @Test
    fun `isFavoriteOnCloud true only when tag 0 is present`() {
        assertTrue(cloud(1L, tags = setOf(0)).isFavoriteOnCloud)
        assertTrue(cloud(1L, tags = setOf(0, 3)).isFavoriteOnCloud)
        assertFalse(cloud(1L, tags = setOf(3)).isFavoriteOnCloud)
        assertFalse(cloud(1L, tags = emptySet()).isFavoriteOnCloud)
    }

    // ─── TimestampSanity floor + effective fallback ───────────────────────────

    @Test
    fun `FLOOR_MS isReal boundary is exclusive`() {
        // FLOOR_MS itself is NOT real (strictly greater than required).
        assertFalse(TimestampSanity.isReal(TimestampSanity.FLOOR_MS))
        assertTrue(TimestampSanity.isReal(TimestampSanity.FLOOR_MS + 1))
        assertFalse(TimestampSanity.isReal(0L))
        assertFalse(TimestampSanity.isReal(TimestampSanity.FLOOR_MS - 1))
    }

    @Test
    fun `effectiveMs prefers the primary when it clears the floor`() {
        val realPrimary = TimestampSanity.FLOOR_MS + 5_000L
        val fallback = TimestampSanity.FLOOR_MS + 999_999L
        assertEquals(realPrimary, TimestampSanity.effectiveMs(realPrimary, fallback))
    }

    @Test
    fun `effectiveMs falls back when the primary is sub-floor`() {
        val subFloor = 1_000L
        val fallback = TimestampSanity.FLOOR_MS + 7_000L
        assertEquals(fallback, TimestampSanity.effectiveMs(subFloor, fallback))
    }

    @Test
    fun `effectiveMs returns the fallback even when the fallback is itself zero`() {
        // Documented behaviour: a sub-floor primary yields the fallback unconditionally, including 0.
        assertEquals(0L, TimestampSanity.effectiveMs(500L, 0L))
    }

    // ─── GalleryItem.Synced wires the floor/fallback to a real photo ──────────

    @Test
    fun `Synced item borrows the local date when the cloud captureTime is sub-floor`() {
        val cloudNoTime = cloud(0L) // captureTimeMs == 0, sub-floor
        val localDate = TimestampSanity.FLOOR_MS + 123_456L
        val local = LocalMediaItem(
            uri = "uri://1",
            dateTaken = localDate,
            displayName = "photo.jpg",
            mimeType = "image/jpeg",
            sizeBytes = 1024L,
            bucketName = "Camera",
        )
        val synced = GalleryItem.Synced(cloud = cloudNoTime, local = local)
        assertEquals(localDate, synced.captureTimeMs)
    }

    @Test
    fun `Synced item keeps the cloud captureTime when it is real`() {
        val realSec = (TimestampSanity.FLOOR_MS / 1000L) + 10_000L
        val cloudWithTime = cloud(realSec)
        val local = LocalMediaItem(
            uri = "uri://1",
            dateTaken = 1_000L, // sub-floor local, must NOT win
            displayName = "photo.jpg",
            mimeType = "image/jpeg",
            sizeBytes = 1024L,
            bucketName = "Camera",
        )
        val synced = GalleryItem.Synced(cloud = cloudWithTime, local = local)
        assertEquals(realSec * 1000L, synced.captureTimeMs)
    }
}
