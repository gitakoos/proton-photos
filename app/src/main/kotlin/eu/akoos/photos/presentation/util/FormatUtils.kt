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

package eu.akoos.photos.presentation.util

import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/** The one byte formatter for every surface. Binary units (1024) to match how Proton
 *  reports storage, so a quota shown here equals the same quota on Proton's own pages. */
fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return "%.1f KB".format(kb)
    val mb = kb / 1024.0
    if (mb < 1024) return "%.1f MB".format(mb)
    val gb = mb / 1024.0
    return "%.2f GB".format(gb)
}

/**
 * Video playhead / trim formatter. `withTenths = false` for `m:ss` (viewer pill,
 * filmstrip labels). `withTenths = true` for `m:ss.t` (trim handles, music start
 * and end pills — single-frame precision is visible on the slider gesture).
 */
fun formatVideoTime(ms: Long, withTenths: Boolean = false): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val m = total / 60
    val s = total % 60
    return if (withTenths) {
        val tenths = (ms % 1000) / 100
        "%d:%02d.%d".format(m, s, tenths)
    } else {
        "%d:%02d".format(m, s)
    }
}

/** "MMMM yyyy" bucket label for the timeline and album/folder month headers. Locale-aware so the
 *  month name reads in the reader's language. Construction only; each call site keeps its own
 *  instance because SimpleDateFormat is not thread-safe. */
fun monthYearFormat(): SimpleDateFormat = SimpleDateFormat("MMMM yyyy", Locale.getDefault())

/** "d MMMM yyyy" day-bucket label for the day-grouped timeline and scrubber. Locale-aware like
 *  monthYearFormat; construction only, never a shared instance. */
fun dayMonthYearFormat(): SimpleDateFormat = SimpleDateFormat("d MMMM yyyy", Locale.getDefault())

/** "yyyy-MM-dd" day-key parser and formatter. Locale is pinned to US so the digits stay Latin on
 *  any device, and the zone is the device default to match how the keys are built. Construction
 *  only; callers keep their own per-thread instance. */
fun isoDateFormat(): SimpleDateFormat =
    SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = TimeZone.getDefault() }
