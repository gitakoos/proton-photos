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

package eu.akoos.photos.domain.importer

import eu.akoos.photos.domain.entity.TimestampSanity

/**
 * The capture date an imported entry is uploaded with. Every entry gets one, so nothing is ever dated
 * 1 January 1970: the sidecar's date, then the date the file itself records (EXIF or the video's mvhd),
 * then the date its name encodes, then the archive entry's modified time, and finally [resolve]'s `nowMs`.
 * A source counts only when it is plausible: after [TimestampSanity.FLOOR_MS] and at most a day ahead.
 */
object ImportCaptureDate {

    private const val FUTURE_SLACK_MS = 86_400_000L

    /** [fileMs] reads the file, so it runs only when the sidecar has no usable date. */
    fun resolve(
        sidecarMs: Long?,
        fileMs: () -> Long?,
        nameMs: Long?,
        entryModifiedMs: Long?,
        nowMs: Long,
    ): Long {
        sidecarMs?.takeIf { isPlausible(it, nowMs) }?.let { return it }
        fileMs()?.takeIf { isPlausible(it, nowMs) }?.let { return it }
        nameMs?.takeIf { isPlausible(it, nowMs) }?.let { return it }
        entryModifiedMs?.takeIf { isPlausible(it, nowMs) }?.let { return it }
        return nowMs
    }

    internal fun isPlausible(ms: Long, nowMs: Long): Boolean =
        TimestampSanity.isReal(ms) && ms <= nowMs + FUTURE_SLACK_MS
}
