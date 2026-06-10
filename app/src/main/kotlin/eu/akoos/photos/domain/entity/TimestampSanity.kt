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

/**
 * One effective-capture-timestamp rule shared by every photo-list sort and timeline-grouping
 * site (gallery merge, timeline grouping, album detail, calendar, search, on-this-day) so the
 * same photo lands in the same place everywhere regardless of its sync state.
 *
 * The inputs differ per source: a local file's MediaStore DATE_TAKEN already self-heals to
 * DATE_ADDED at query time, whereas a cloud photo's captureTime is 0 when Drive holds no
 * capture timestamp (e.g. metadata stripped before reaching this client, or a non-photo
 * import). [FLOOR_MS] separates a real timestamp from that sentinel; [effectiveMs] applies the
 * single fallback rule (prefer the primary timestamp, else the secondary) so a sub-floor value
 * never glues the item to epoch 0 at the list tail when a better timestamp is in hand.
 */
object TimestampSanity {
    /** Epoch-ms below this (1971-01-01) is treated as "no real timestamp". */
    const val FLOOR_MS: Long = 31_536_000_000L

    fun isReal(ms: Long): Boolean = ms > FLOOR_MS

    /** [primaryMs] when it clears the floor, else [fallbackMs] (which may itself be 0). */
    fun effectiveMs(primaryMs: Long, fallbackMs: Long): Long =
        if (isReal(primaryMs)) primaryMs else fallbackMs
}
