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

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs

/**
 * Converts between an epoch-millisecond instant and the EXIF wall-clock datetime string
 * (`yyyy:MM:dd HH:mm:ss`, no zone), both interpreted in a caller-supplied [ZoneId], and both derives
 * and reads back the matching EXIF OffsetTime string. EXIF stores local time with no offset, so the
 * zone is what pins correctness, and an OffsetTime tag on the file replaces that guess with the real
 * one. Pure and Android-free, so timezone and DST handling are unit-tested on the JVM.
 */
object ExifDateFormat {

    private const val PATTERN = "yyyy:MM:dd HH:mm:ss"
    private val FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern(PATTERN, Locale.US)

    /** The EXIF datetime string for [ms] as read in [zone]. */
    fun toExifLocal(ms: Long, zone: ZoneId): String =
        Instant.ofEpochMilli(ms).atZone(zone).toLocalDateTime().format(FORMATTER)

    /** The EXIF OffsetTime string (`+HH:MM` / `-HH:MM`) that pairs with [toExifLocal] for the same [ms]
     *  and [zone], so the pair resolves to exactly one absolute instant. The offset is the one in force
     *  at that instant, which is what makes a DST-crossing edit land on the right side. EXIF has no
     *  field for a sub-minute offset, so a zone that carries one truncates to whole minutes. */
    fun toExifOffset(ms: Long, zone: ZoneId): String {
        val totalSeconds = zone.rules.getOffset(Instant.ofEpochMilli(ms)).totalSeconds
        val sign = if (totalSeconds < 0) "-" else "+"
        val minutes = abs(totalSeconds) / 60
        return "%s%02d:%02d".format(Locale.US, sign, minutes / 60, minutes % 60)
    }

    /** Parses an EXIF datetime [s] interpreted in [zone] back to epoch millis, or null when it is not a
     *  valid `yyyy:MM:dd HH:mm:ss` value. */
    fun fromExifLocal(s: String, zone: ZoneId): Long? =
        runCatching {
            LocalDateTime.parse(s.trim(), FORMATTER).atZone(zone).toInstant().toEpochMilli()
        }.getOrNull()

    /** True when [offset] is an EXIF OffsetTime value this can honour, so a caller knows whether a
     *  [fromExif] result is an exact instant or a wall clock read through a guessed zone. */
    fun hasUsableOffset(offset: String?): Boolean = parseOffset(offset) != null

    /** Parses an EXIF datetime [dateTime] together with its OffsetTime [offset] to epoch millis. A
     *  usable offset pins the pair to one absolute instant with no device zone involved; an absent,
     *  blank or unreadable offset leaves the wall clock unanchored, so it is read in [fallbackZone].
     *  Null when the datetime itself is not a valid `yyyy:MM:dd HH:mm:ss` value. */
    fun fromExif(dateTime: String, offset: String?, fallbackZone: ZoneId): Long? {
        val parsed = parseOffset(offset) ?: return fromExifLocal(dateTime, fallbackZone)
        return runCatching {
            LocalDateTime.parse(dateTime.trim(), FORMATTER).toInstant(parsed).toEpochMilli()
        }.getOrNull()
    }

    /** The [ZoneOffset] an EXIF OffsetTime string denotes, or null when there is none to read. `Z` is
     *  UTC, and the colon is optional because real files carry both `+HH:MM` and `+HHMM`. */
    private fun parseOffset(offset: String?): ZoneOffset? {
        val trimmed = offset?.trim().orEmpty()
        if (trimmed.isEmpty()) return null
        if (trimmed.equals("Z", ignoreCase = true)) return ZoneOffset.UTC
        return runCatching { ZoneOffset.of(trimmed) }.getOrNull()
    }
}
