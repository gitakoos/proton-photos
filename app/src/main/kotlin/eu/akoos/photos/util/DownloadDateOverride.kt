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

/**
 * Pure helpers for the download capture-date override map (SettingsKeys.DOWNLOAD_DATE_OVERRIDES),
 * persisted as a set of "mediaUri|captureMs" strings.
 *
 * MediaStore only derives DATE_TAKEN from the embedded date for JPEG/HEIF images (and the video
 * mvhd). For a downloaded PNG/WebP/GIF it refuses the DATE_TAKEN column write entirely, leaving the
 * column 0 — so the file would read as its download date once its cloud twin is gone (the local scan
 * falls back to DATE_ADDED). The download records the real capture date here when the read-back shows
 * the column did not stick, and the local media scan applies it whenever DATE_TAKEN is 0. It is a pure
 * safety net: it is written ONLY when the date is known but the column is 0, and read ONLY when the
 * column is 0, so a format whose DATE_TAKEN persists (JPEG/HEIF/video) is never touched.
 *
 * Centralised so the write side (the downloader) and the read side (the media scan) share one
 * encoding and can be verified without a device.
 */
object DownloadDateOverride {

    /** True when a download should record an override: the capture date is known but MediaStore left
     *  DATE_TAKEN unset (0), so nothing else carries the date for this file. */
    fun shouldRecord(dateTakenMs: Long?, storedDateTaken: Long): Boolean =
        dateTakenMs != null && dateTakenMs > 0L && storedDateTaken <= 0L

    /** Flatten a (uri, captureMs) pair into the persisted "uri|captureMs" entry. */
    fun encode(uri: String, captureMs: Long): String = "$uri|$captureMs"

    /** Parse the persisted entry set into a uri -> captureMs map, dropping malformed or non-positive
     *  entries. Splits on the LAST separator so a uri that itself contained '|' would still resolve. */
    fun parse(entries: Set<String>?): Map<String, Long> {
        if (entries.isNullOrEmpty()) return emptyMap()
        val out = HashMap<String, Long>(entries.size)
        for (entry in entries) {
            val sep = entry.lastIndexOf('|')
            if (sep <= 0) continue
            val ms = entry.substring(sep + 1).toLongOrNull() ?: continue
            if (ms > 0L) out[entry.substring(0, sep)] = ms
        }
        return out
    }

    /** The date a local media row should report: the MediaStore DATE_TAKEN (ms) when set, else the
     *  recorded override (ms), else the file's added time ([dateAddedSeconds], promoted to ms). */
    fun resolveDateTaken(rawDateTaken: Long, overrideMs: Long?, dateAddedSeconds: Long): Long = when {
        rawDateTaken > 0L -> rawDateTaken
        overrideMs != null && overrideMs > 0L -> overrideMs
        else -> dateAddedSeconds * 1000L
    }

    /** Drop entries whose uri is no longer among [liveUris] so the map stays bounded. Returns null when
     *  nothing changed, so the caller can skip a needless persist. */
    fun prune(entries: Set<String>, liveUris: Set<String>): Set<String>? {
        val kept = entries.filterTo(HashSet()) { it.substringBeforeLast('|') in liveUris }
        return if (kept.size == entries.size) null else kept
    }
}
