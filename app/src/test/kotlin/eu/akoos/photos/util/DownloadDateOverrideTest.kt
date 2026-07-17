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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies the pure download capture-date override logic that keeps a downloaded PNG/WebP dated
 * correctly after its cloud twin is deleted, WITHOUT the app, a device, or MediaStore in the loop.
 * The scenarios encode the format matrix: a format whose DATE_TAKEN persists (JPEG/HEIF/video) never
 * records or reads an override; only a format MediaStore refuses (PNG) does.
 */
class DownloadDateOverrideTest {

    private val captureMs = 1_783_507_135_000L // the real capture date (ms)
    private val downloadSeconds = 1_783_804_678L // DATE_ADDED at download time (seconds)

    // ── shouldRecord: only when the date is known AND MediaStore dropped the column ──────────────

    @Test
    fun `PNG whose DATE_TAKEN was refused records an override`() {
        // MediaStore stored 0 for the PNG despite a known capture date.
        assertTrue(DownloadDateOverride.shouldRecord(dateTakenMs = captureMs, storedDateTaken = 0L))
    }

    @Test
    fun `JPEG whose DATE_TAKEN stuck records nothing`() {
        // MediaStore derived DATE_TAKEN from the JPEG EXIF, so the column already carries the date.
        assertFalse(DownloadDateOverride.shouldRecord(dateTakenMs = captureMs, storedDateTaken = captureMs))
    }

    @Test
    fun `no known date records nothing`() {
        // A PNG with no capture date anywhere: nothing to record, so no override (the file legitimately
        // has no known date and must not be pinned to a made-up one).
        assertFalse(DownloadDateOverride.shouldRecord(dateTakenMs = null, storedDateTaken = 0L))
        assertFalse(DownloadDateOverride.shouldRecord(dateTakenMs = 0L, storedDateTaken = 0L))
    }

    // ── resolveDateTaken: the read side never overrides a real DATE_TAKEN ────────────────────────

    @Test
    fun `resolve prefers a real DATE_TAKEN over any override`() {
        // JPEG/HEIF/video path: the column is authoritative even if a stale override existed.
        assertEquals(captureMs, DownloadDateOverride.resolveDateTaken(captureMs, overrideMs = 999L, dateAddedSeconds = downloadSeconds))
    }

    @Test
    fun `resolve uses the override when DATE_TAKEN is zero`() {
        // PNG path after the cloud twin is gone: the override rescues the true date.
        assertEquals(captureMs, DownloadDateOverride.resolveDateTaken(rawDateTaken = 0L, overrideMs = captureMs, dateAddedSeconds = downloadSeconds))
    }

    @Test
    fun `resolve falls back to the added time when neither is available`() {
        // No column, no override: the download time (seconds, promoted to ms) is the only value left.
        assertEquals(downloadSeconds * 1000L, DownloadDateOverride.resolveDateTaken(rawDateTaken = 0L, overrideMs = null, dateAddedSeconds = downloadSeconds))
    }

    // ── encode / parse round-trip + robustness ───────────────────────────────────────────────────

    @Test
    fun `encode then parse round-trips a uri to its capture date`() {
        val uri = "content://media/external/images/media/1000012591"
        val parsed = DownloadDateOverride.parse(setOf(DownloadDateOverride.encode(uri, captureMs)))
        assertEquals(mapOf(uri to captureMs), parsed)
    }

    @Test
    fun `parse drops malformed and non-positive entries`() {
        val entries = setOf(
            "content://a|1783507135000", // valid
            "no-separator",              // no '|'
            "content://b|notanumber",    // non-numeric
            "content://c|0",             // non-positive
            "content://d|-5",            // negative
        )
        val parsed = DownloadDateOverride.parse(entries)
        assertEquals(mapOf("content://a" to 1_783_507_135_000L), parsed)
    }

    @Test
    fun `parse handles null and empty`() {
        assertTrue(DownloadDateOverride.parse(null).isEmpty())
        assertTrue(DownloadDateOverride.parse(emptySet()).isEmpty())
    }

    // ── prune: bounded map, no needless write ────────────────────────────────────────────────────

    @Test
    fun `prune drops entries for files that are gone`() {
        val entries = setOf("content://live|100", "content://gone|200")
        val pruned = DownloadDateOverride.prune(entries, liveUris = setOf("content://live"))
        assertEquals(setOf("content://live|100"), pruned)
    }

    @Test
    fun `prune returns null when every entry is still live`() {
        val entries = setOf("content://live|100")
        // null signals "nothing changed" so the caller can skip persisting.
        assertNull(DownloadDateOverride.prune(entries, liveUris = setOf("content://live")))
    }
}
