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

package eu.akoos.photos.presentation.metadata

import eu.akoos.photos.domain.entity.TimestampSanity
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Reads the capture date a filename carries, for photos that reached the device with no EXIF of their
 * own (memes, downloads, screenshots) but whose name records when they were taken or saved. The date
 * in the name is the only original date such a file has, so setting it beats letting the photo fall to
 * its import date and scatter across the timeline.
 *
 * Only the unambiguous, human-written date shapes are read: `IMG_20230115_123456`, `20230115`,
 * `2023-01-15`, `2023.01.15`, `Screenshot_2023-01-15-12-34-56`, the WhatsApp `IMG-20230115-WA0001`.
 * A bare Unix timestamp is deliberately NOT read, because a random numeric meme id would parse as a
 * plausible date and corrupt it silently. Every candidate is validated as a real, non-future calendar
 * date at or after 1990, so a digit run that is not a date is rejected rather than guessed.
 *
 * No time in the name lands the photo at local noon, so converting between zones can never cross the
 * day. Free of Android types, and the current instant and the zone are parameters rather than a clock
 * read, so every rule here is verifiable on the JVM.
 */
object FilenameDate {

    /** Digital photography predates this by little; a 4-digit year below it is not a capture date. */
    private const val MIN_YEAR = 1990

    /** `yyyy-MM-dd` (or `.`/`_` separators) with an optional `HH:MM(:SS)` time. */
    private val SEPARATED = Regex(
        """(?<!\d)(\d{4})[-._](\d{1,2})[-._](\d{1,2})(?:[ _tT-](\d{1,2})[-._:](\d{1,2})(?:[-._:](\d{1,2}))?)?(?!\d)""",
    )
    /** `yyyyMMdd` immediately followed (optional single separator) by `HHmmss`, tolerating an optional
     *  sub-second suffix (e.g. `...539`) so a millisecond tail does not block the match. */
    private val COMPACT_DATETIME = Regex("""(?<!\d)(\d{4})(\d{2})(\d{2})[ _tT.-]?(\d{2})(\d{2})(\d{2})(?:[.,]?\d{1,3})?(?!\d)""")
    /** A bare `yyyyMMdd` run bounded by non-digits. */
    private val COMPACT_DATE = Regex("""(?<!\d)(\d{4})(\d{2})(\d{2})(?!\d)""")

    /**
     * The capture instant [name] encodes, or null when it carries no readable date. [nowMs] is the
     * ceiling (a future date is never returned); [zone] converts the wall-clock date the name records
     * into an instant.
     */
    fun parse(name: String, nowMs: Long, zone: ZoneId = ZoneId.systemDefault()): Long? {
        val stem = name.substringBeforeLast('.', name).trim()
        if (stem.isEmpty()) return null

        // Separated first, so a `2023-01-15` form is never half-eaten by the compact patterns. A run
        // whose time is out of range (a counter, not a clock, such as SquareQuick's `..594755`) still
        // yields its date, so the fallback re-reads the same valid `yyyyMMdd` at noon.
        SEPARATED.find(stem)?.groupValues?.let { g ->
            (toEpoch(g[1], g[2], g[3], g[4], g[5], g[6], zone, nowMs)
                ?: toEpoch(g[1], g[2], g[3], "", "", "", zone, nowMs))?.let { return it }
        }
        COMPACT_DATETIME.find(stem)?.groupValues?.let { g ->
            (toEpoch(g[1], g[2], g[3], g[4], g[5], g[6], zone, nowMs)
                ?: toEpoch(g[1], g[2], g[3], "", "", "", zone, nowMs))?.let { return it }
        }
        COMPACT_DATE.find(stem)?.groupValues?.let { g ->
            toEpoch(g[1], g[2], g[3], "", "", "", zone, nowMs)?.let { return it }
        }
        return null
    }

    /** Builds the instant from the captured fields, returning null when they are not a real calendar
     *  date, predate [MIN_YEAR], or land after [nowMs]. A blank time component lands at local noon. */
    private fun toEpoch(
        y: String, mo: String, d: String, h: String, mi: String, s: String, zone: ZoneId, nowMs: Long,
    ): Long? {
        val year = y.toIntOrNull() ?: return null
        val month = mo.toIntOrNull() ?: return null
        val day = d.toIntOrNull() ?: return null
        if (year < MIN_YEAR) return null
        val hour = h.toIntOrNull() ?: NOON
        val minute = mi.toIntOrNull() ?: 0
        val second = s.toIntOrNull() ?: 0
        val ms = runCatching {
            LocalDateTime.of(year, month, day, hour, minute, second).atZone(zone).toInstant().toEpochMilli()
        }.getOrNull() ?: return null
        return if (TimestampSanity.isReal(ms) && ms <= nowMs) ms else null
    }

    private const val NOON = 12
}
