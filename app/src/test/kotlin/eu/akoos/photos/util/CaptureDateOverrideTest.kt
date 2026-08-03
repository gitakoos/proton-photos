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
 * Verifies the pure capture-date override logic that keeps a file dated by its own capture date
 * rather than by whatever MediaStore put in DATE_TAKEN, WITHOUT the app, a device, or MediaStore in
 * the loop. The scenarios encode the format matrix: a format whose DATE_TAKEN persists
 * (JPEG/HEIF/video) never records a download override; only a format MediaStore refuses (PNG) does.
 * They also encode the correction rule, where an EXIF offset is what decides how far the two dates
 * may drift before the difference counts as an error rather than as a timezone, and the entry format,
 * where the file's own modified time is what tells a recorded date apart from the file it describes.
 */
class CaptureDateOverrideTest {

    private val captureMs = 1_783_507_135_000L // the real capture date (ms)
    private val downloadSeconds = 1_783_804_678L // DATE_ADDED at download time (seconds)
    private val modifiedSeconds = 1_783_804_690L // DATE_MODIFIED when the entry was recorded (seconds)
    private val hourMs = 60 * 60 * 1000L
    private val dayMs = 24 * hourMs

    // ── shouldRecord: only when the date is known AND MediaStore dropped the column ──────────────

    @Test
    fun `PNG whose DATE_TAKEN was refused records an override`() {
        // MediaStore stored 0 for the PNG despite a known capture date.
        assertTrue(CaptureDateOverride.shouldRecord(dateTakenMs = captureMs, storedDateTaken = 0L))
    }

    @Test
    fun `JPEG whose DATE_TAKEN stuck records nothing`() {
        // MediaStore derived DATE_TAKEN from the JPEG EXIF, so the column already carries the date.
        assertFalse(CaptureDateOverride.shouldRecord(dateTakenMs = captureMs, storedDateTaken = captureMs))
    }

    @Test
    fun `no known date records nothing`() {
        // A PNG with no capture date anywhere: nothing to record, so no override (the file legitimately
        // has no known date and must not be pinned to a made-up one).
        assertFalse(CaptureDateOverride.shouldRecord(dateTakenMs = null, storedDateTaken = 0L))
        assertFalse(CaptureDateOverride.shouldRecord(dateTakenMs = 0L, storedDateTaken = 0L))
    }

    // ── captureDateCorrection: an EXIF date replaces the column only when it proves it is wrong ──

    @Test
    fun `no usable EXIF date corrects nothing`() {
        assertNull(CaptureDateOverride.captureDateCorrection(captureMs, exifMs = null, exifHasOffset = true))
        assertNull(CaptureDateOverride.captureDateCorrection(captureMs, exifMs = 0L, exifHasOffset = true))
        assertNull(CaptureDateOverride.captureDateCorrection(captureMs, exifMs = -1L, exifHasOffset = false))
    }

    @Test
    fun `an empty column takes the EXIF date whatever the offset`() {
        assertEquals(captureMs, CaptureDateOverride.captureDateCorrection(0L, captureMs, exifHasOffset = true))
        assertEquals(captureMs, CaptureDateOverride.captureDateCorrection(0L, captureMs, exifHasOffset = false))
    }

    @Test
    fun `an exact EXIF instant half a minute off is left alone`() {
        // The offset pins one instant, so this small a gap is truncation, not a wrong date.
        val exif = captureMs + 30_000L
        assertNull(CaptureDateOverride.captureDateCorrection(captureMs, exif, exifHasOffset = true))
    }

    @Test
    fun `an exact EXIF instant hours off corrects the column, in both directions`() {
        assertEquals(
            captureMs + 3 * hourMs,
            CaptureDateOverride.captureDateCorrection(captureMs, captureMs + 3 * hourMs, exifHasOffset = true),
        )
        assertEquals(
            captureMs - 3 * hourMs,
            CaptureDateOverride.captureDateCorrection(captureMs, captureMs - 3 * hourMs, exifHasOffset = true),
        )
    }

    @Test
    fun `an offsetless EXIF date hours off is left alone`() {
        // The timezone-safety case: with no offset the wall clock is only device-zone-accurate, and a
        // few hours is exactly what a zone difference looks like, so the column stands.
        assertNull(CaptureDateOverride.captureDateCorrection(captureMs, captureMs + 3 * hourMs, exifHasOffset = false))
        assertNull(CaptureDateOverride.captureDateCorrection(captureMs, captureMs - 3 * hourMs, exifHasOffset = false))
    }

    @Test
    fun `an offsetless EXIF date days off corrects the column, in both directions`() {
        // No zone on earth is five days wide, so this is a real error.
        assertEquals(
            captureMs + 5 * dayMs,
            CaptureDateOverride.captureDateCorrection(captureMs, captureMs + 5 * dayMs, exifHasOffset = false),
        )
        assertEquals(
            captureMs - 5 * dayMs,
            CaptureDateOverride.captureDateCorrection(captureMs, captureMs - 5 * dayMs, exifHasOffset = false),
        )
    }

    @Test
    fun `a video's mvhd instant is measured on the wall-clock slack`() {
        // A video's date comes from the mvhd creation time, which enters here as offsetless on
        // purpose: the spec calls that field UTC, but a large share of cameras write local wall clock
        // into it, so a whole-timezone gap must never move a video's date. A date wrong by months
        // still must, which is the fault the leg exists for.
        assertNull(CaptureDateOverride.captureDateCorrection(captureMs, captureMs + 3 * hourMs, exifHasOffset = false))
        assertNull(CaptureDateOverride.captureDateCorrection(captureMs, captureMs - 3 * hourMs, exifHasOffset = false))
        val monthsOff = captureMs - 90 * dayMs
        assertEquals(monthsOff, CaptureDateOverride.captureDateCorrection(captureMs, monthsOff, exifHasOffset = false))
    }

    @Test
    fun `each threshold corrects only past its own edge`() {
        // One minute with an offset, two days without: the edge itself stays uncorrected.
        assertNull(CaptureDateOverride.captureDateCorrection(captureMs, captureMs + 60_000L, exifHasOffset = true))
        assertEquals(
            captureMs + 60_001L,
            CaptureDateOverride.captureDateCorrection(captureMs, captureMs + 60_001L, exifHasOffset = true),
        )
        assertNull(CaptureDateOverride.captureDateCorrection(captureMs, captureMs + 2 * dayMs, exifHasOffset = false))
        assertEquals(
            captureMs + 2 * dayMs + 1L,
            CaptureDateOverride.captureDateCorrection(captureMs, captureMs + 2 * dayMs + 1L, exifHasOffset = false),
        )
    }

    // ── resolveDateTaken: a recorded override outranks the column ────────────────────────────────

    @Test
    fun `resolve prefers the override over a non-zero DATE_TAKEN`() {
        // An entry is recorded only where the column is established to be wrong, so reading the column
        // first would keep reporting the very value the entry exists to replace.
        val wrongColumn = captureMs + 400 * 24 * 60 * 60 * 1000L
        assertEquals(captureMs, CaptureDateOverride.resolveDateTaken(wrongColumn, overrideMs = captureMs, dateAddedSeconds = downloadSeconds))
    }

    @Test
    fun `resolve uses the column when there is no override`() {
        assertEquals(captureMs, CaptureDateOverride.resolveDateTaken(captureMs, overrideMs = null, dateAddedSeconds = downloadSeconds))
        assertEquals(captureMs, CaptureDateOverride.resolveDateTaken(captureMs, overrideMs = 0L, dateAddedSeconds = downloadSeconds))
    }

    @Test
    fun `resolve uses the override when DATE_TAKEN is zero`() {
        // PNG path after the cloud twin is gone: the override rescues the true date.
        assertEquals(captureMs, CaptureDateOverride.resolveDateTaken(rawDateTaken = 0L, overrideMs = captureMs, dateAddedSeconds = downloadSeconds))
    }

    @Test
    fun `resolve falls back to the added time when neither is available`() {
        // No column, no override: the download time (seconds, promoted to ms) is the only value left.
        assertEquals(downloadSeconds * 1000L, CaptureDateOverride.resolveDateTaken(rawDateTaken = 0L, overrideMs = null, dateAddedSeconds = downloadSeconds))
    }

    // ── encode / parse round-trip + robustness ───────────────────────────────────────────────────

    @Test
    fun `encode then parse round-trips a uri to its capture date and modified time`() {
        val uri = "content://media/external/images/media/1000012591"
        val parsed = CaptureDateOverride.parse(setOf(CaptureDateOverride.encode(uri, captureMs, modifiedSeconds)))
        assertEquals(mapOf(uri to CaptureDateOverride.Entry(captureMs, modifiedSeconds)), parsed)
    }

    @Test
    fun `encode then parse round-trips an unknown modified time as unknown`() {
        val uri = "content://media/external/images/media/1000012591"
        val parsed = CaptureDateOverride.parse(
            setOf(CaptureDateOverride.encode(uri, captureMs, modifiedSeconds = null))
        )
        assertEquals(mapOf(uri to CaptureDateOverride.Entry(captureMs, null)), parsed)
    }

    @Test
    fun `parse reads a recorded and a legacy entry out of the same set`() {
        // Every entry a device already holds carries no modified time, so both forms have to resolve
        // side by side or the map would be dropped wholesale on the first read.
        val parsed = CaptureDateOverride.parse(
            setOf("content://legacy|$captureMs", "content://recorded|$captureMs|$modifiedSeconds")
        )
        assertEquals(
            mapOf(
                "content://legacy" to CaptureDateOverride.Entry(captureMs, null),
                "content://recorded" to CaptureDateOverride.Entry(captureMs, modifiedSeconds),
            ),
            parsed,
        )
    }

    @Test
    fun `parse resolves a uri that itself contains the separator, in both forms`() {
        // The numeric fields are read from the right, so a separator inside the uri stays in the uri.
        val uri = "content://odd|name"
        assertEquals(
            mapOf(uri to CaptureDateOverride.Entry(captureMs, null)),
            CaptureDateOverride.parse(setOf("$uri|$captureMs")),
        )
        assertEquals(
            mapOf(uri to CaptureDateOverride.Entry(captureMs, modifiedSeconds)),
            CaptureDateOverride.parse(setOf("$uri|$captureMs|$modifiedSeconds")),
        )
    }

    @Test
    fun `a malformed modified time costs that field alone, not the correction`() {
        // A damaged tail must degrade to unknown: the walk then re-derives the entry, whereas dropping
        // it outright would lose a date nothing else on the device holds.
        assertEquals(
            mapOf("content://a" to CaptureDateOverride.Entry(captureMs, null)),
            CaptureDateOverride.parse(setOf("content://a|$captureMs|notanumber")),
        )
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
        val parsed = CaptureDateOverride.parse(entries)
        assertEquals(mapOf("content://a" to CaptureDateOverride.Entry(1_783_507_135_000L, null)), parsed)
    }

    @Test
    fun `parse handles null and empty`() {
        assertTrue(CaptureDateOverride.parse(null).isEmpty())
        assertTrue(CaptureDateOverride.parse(emptySet()).isEmpty())
    }

    // ── isCurrent: an entry is only good for the file it was written from ────────────────────────

    @Test
    fun `an entry is current only while the file's modified time still matches`() {
        val entry = CaptureDateOverride.Entry(captureMs, modifiedSeconds)
        assertTrue(CaptureDateOverride.isCurrent(entry, modifiedSeconds))
        assertFalse(CaptureDateOverride.isCurrent(entry, modifiedSeconds + 1))
        assertFalse(CaptureDateOverride.isCurrent(entry, 0L))
    }

    @Test
    fun `an entry with an unknown modified time is never current`() {
        // A legacy entry, or one a metadata edit moved: nothing says which state of the file it
        // describes, so the walk has to drop it and read the file again.
        val entry = CaptureDateOverride.Entry(captureMs, modifiedSeconds = null)
        assertFalse(CaptureDateOverride.isCurrent(entry, modifiedSeconds))
        assertFalse(CaptureDateOverride.isCurrent(entry, 0L))
    }

    // ── dropUris: bounded map, no needless write, nothing dropped unasked ────────────────────────

    @Test
    fun `dropUris drops the entries it is given`() {
        val entries = setOf("content://live|100", "content://gone|200")
        val pruned = CaptureDateOverride.dropUris(entries, uris = setOf("content://gone"))
        assertEquals(setOf("content://live|100"), pruned)
    }

    @Test
    fun `dropUris returns null when it drops nothing`() {
        val entries = setOf("content://live|100")
        // null signals "nothing changed" so the caller can skip persisting.
        assertNull(CaptureDateOverride.dropUris(entries, uris = setOf("content://gone")))
        assertNull(CaptureDateOverride.dropUris(entries, uris = emptySet()))
        assertNull(CaptureDateOverride.dropUris(emptySet(), uris = setOf("content://gone")))
    }

    @Test
    fun `dropUris reads the uri out of a recorded entry too`() {
        val live = CaptureDateOverride.encode("content://live", captureMs, modifiedSeconds)
        val gone = CaptureDateOverride.encode("content://gone", captureMs, modifiedSeconds)
        assertEquals(setOf(live), CaptureDateOverride.dropUris(setOf(live, gone), setOf("content://gone")))
        assertNull(CaptureDateOverride.dropUris(setOf(live), setOf("content://gone")))
    }

    @Test
    fun `dropUris keeps an entry the caller did not name`() {
        // The map is read once per media scan and edited afterwards, so a download landing in between
        // is present here but named by no decision. Keeping it is what stops the scan from deleting a
        // capture date it never even looked at — for a PNG or WebP, the only copy of that date.
        val scanned = CaptureDateOverride.encode("content://scanned", captureMs, modifiedSeconds)
        val landedAfter = CaptureDateOverride.encode("content://later", captureMs, modifiedSeconds)
        assertEquals(
            setOf(landedAfter),
            CaptureDateOverride.dropUris(setOf(scanned, landedAfter), setOf("content://scanned")),
        )
    }

    @Test
    fun `dropUris also sheds an entry that no longer parses`() {
        // Unreadable to every reader here, so nothing can ever act on it; a pass that is writing
        // anyway may as well take it.
        val kept = CaptureDateOverride.encode("content://live", captureMs, modifiedSeconds)
        assertEquals(
            setOf(kept),
            CaptureDateOverride.dropUris(setOf(kept, "no-separator", "content://gone|1"), setOf("content://gone")),
        )
    }

    // ── retarget: an edited date reaches the map the grid reads ──────────────────────────────────

    private val editedMs = 1_600_000_000_000L // the date the metadata editor writes

    @Test
    fun `retarget moves an edited file's override to the new date`() {
        // The PNG whose DATE_TAKEN column the platform refuses: the map is the only place the edit can
        // live, so it has to carry the new value or the grid keeps reporting the recorded one.
        val entries = setOf(CaptureDateOverride.encode("content://png", captureMs, modifiedSeconds = null))
        val moved = CaptureDateOverride.retarget(entries, setOf("content://png"), editedMs)
        assertEquals(
            mapOf("content://png" to CaptureDateOverride.Entry(editedMs, null)),
            CaptureDateOverride.parse(moved),
        )
    }

    @Test
    fun `retarget moves a recorded entry and leaves its modified time unknown`() {
        // The edit rewrites the file, so any modified time readable here is already behind that write.
        // Unknown is what makes the next walk drop the entry and re-decide from the file itself.
        val entries = setOf(CaptureDateOverride.encode("content://png", captureMs, modifiedSeconds))
        val moved = CaptureDateOverride.retarget(entries, setOf("content://png"), editedMs)
        assertEquals(
            mapOf("content://png" to CaptureDateOverride.Entry(editedMs, null)),
            CaptureDateOverride.parse(moved),
        )
    }

    @Test
    fun `retarget leaves every other entry untouched`() {
        val entries = setOf(
            CaptureDateOverride.encode("content://edited", captureMs, modifiedSeconds = null),
            CaptureDateOverride.encode("content://other", captureMs, modifiedSeconds),
        )
        val moved = CaptureDateOverride.retarget(entries, setOf("content://edited"), editedMs)
        assertEquals(
            mapOf(
                "content://edited" to CaptureDateOverride.Entry(editedMs, null),
                "content://other" to CaptureDateOverride.Entry(captureMs, modifiedSeconds),
            ),
            CaptureDateOverride.parse(moved),
        )
    }

    @Test
    fun `retarget moves every edited file of a batch at once`() {
        val entries = setOf(
            CaptureDateOverride.encode("content://a", captureMs, modifiedSeconds = null),
            CaptureDateOverride.encode("content://b", captureMs, modifiedSeconds),
        )
        val moved = CaptureDateOverride.retarget(entries, setOf("content://a", "content://b"), editedMs)
        assertEquals(
            mapOf(
                "content://a" to CaptureDateOverride.Entry(editedMs, null),
                "content://b" to CaptureDateOverride.Entry(editedMs, null),
            ),
            CaptureDateOverride.parse(moved),
        )
    }

    @Test
    fun `retarget adds nothing for a file that has no override`() {
        // A JPEG keeps its date in its own DATE_TAKEN column, so an entry would only bloat the map.
        val entries = setOf(CaptureDateOverride.encode("content://png", captureMs, modifiedSeconds))
        assertNull(CaptureDateOverride.retarget(entries, setOf("content://jpeg"), editedMs))
    }

    @Test
    fun `retarget returns null when the date is already the recorded one`() {
        // Re-picking the same date changes nothing, so no persist is needed.
        val entries = setOf(CaptureDateOverride.encode("content://png", captureMs, modifiedSeconds = null))
        assertNull(CaptureDateOverride.retarget(entries, setOf("content://png"), captureMs))
    }

    @Test
    fun `retarget returns null for an empty map, no targets or a non-positive date`() {
        val entries = setOf(CaptureDateOverride.encode("content://png", captureMs, modifiedSeconds))
        assertNull(CaptureDateOverride.retarget(emptySet(), setOf("content://png"), editedMs))
        assertNull(CaptureDateOverride.retarget(entries, emptySet(), editedMs))
        assertNull(CaptureDateOverride.retarget(entries, setOf("content://png"), captureMs = 0L))
    }

    @Test
    fun `retarget gives each shifted file its own date`() {
        // A bulk shift moves every file by one delta from its OWN date, so the map has to reach each
        // entry with a different value; one shared date here would collapse what the shift preserved.
        val entries = setOf(
            CaptureDateOverride.encode("content://a", captureMs, modifiedSeconds = null),
            CaptureDateOverride.encode("content://b", captureMs, modifiedSeconds),
        )
        val moved = CaptureDateOverride.retarget(
            entries,
            mapOf("content://a" to editedMs, "content://b" to editedMs + 120_000L),
        )
        assertEquals(
            mapOf(
                "content://a" to CaptureDateOverride.Entry(editedMs, null),
                "content://b" to CaptureDateOverride.Entry(editedMs + 120_000L, null),
            ),
            CaptureDateOverride.parse(moved),
        )
    }

    @Test
    fun `retarget leaves a file the shift did not reach alone`() {
        val entries = setOf(
            CaptureDateOverride.encode("content://shifted", captureMs, modifiedSeconds = null),
            CaptureDateOverride.encode("content://untouched", captureMs, modifiedSeconds),
        )
        val moved = CaptureDateOverride.retarget(entries, mapOf("content://shifted" to editedMs))
        assertEquals(
            mapOf(
                "content://shifted" to CaptureDateOverride.Entry(editedMs, null),
                "content://untouched" to CaptureDateOverride.Entry(captureMs, modifiedSeconds),
            ),
            CaptureDateOverride.parse(moved),
        )
    }

    @Test
    fun `retarget returns null for an empty per-file map or a non-positive date in it`() {
        val entries = setOf(CaptureDateOverride.encode("content://png", captureMs, modifiedSeconds))
        assertNull(CaptureDateOverride.retarget(entries, emptyMap()))
        assertNull(CaptureDateOverride.retarget(emptySet(), mapOf("content://png" to editedMs)))
        assertNull(CaptureDateOverride.retarget(entries, mapOf("content://png" to 0L)))
    }
}
