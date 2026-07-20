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
