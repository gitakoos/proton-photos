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
 * The capture time a vaulted photo carries in its own file name, as `<stem>__<epochMillis>.<ext>`.
 *
 * A vault file is a COPY written into app-private storage, and no MediaStore row survives the hide,
 * so nothing outside the file itself records when the photo was taken. The bytes are no help either:
 * a PNG, a WebP, a screenshot or a video carries no EXIF DateTimeOriginal to fall back on. That makes
 * this suffix the vault's only record of the capture date, and every side that touches a vault name
 * has to read it the same way — the grid that displays the photo, the restore that rebuilds
 * DATE_TAKEN, and the rename that must carry the suffix onto the new name.
 *
 * A name can record nothing at all, so [parse] answering null is an ordinary state rather than a
 * failure, and each caller keeps its own fallback for it.
 */
object HiddenCaptureTime {

    private const val SEPARATOR = "__"

    /** The suffix a vault file name carries for [captureTimeMs], or the empty string when the capture
     *  time is unknown — the name then simply records nothing. */
    fun suffix(captureTimeMs: Long?): String =
        if (captureTimeMs != null && captureTimeMs > 0L) "$SEPARATOR$captureTimeMs" else ""

    /**
     * The capture time [fileName] records, or null when it records none. Takes the name with or
     * without its extension, so a caller can pass whichever it has.
     *
     * Read from the LAST separator, because a user-chosen name reaches the stem through a rename and
     * may itself contain one.
     */
    fun parse(fileName: String): Long? =
        fileName.substringAfterLast(SEPARATOR, "")
            .substringBefore('.')
            .toLongOrNull()
            ?.takeIf { it > 0L }

    /**
     * [fileName] without the capture time it records, extension intact — the name a user sees and a
     * restore writes to the device, since the suffix is the vault's own bookkeeping and means nothing
     * outside it. A name recording no capture time is returned unchanged.
     *
     * Read from the last separator, matching [parse], so a user-chosen stem carrying one of its own
     * keeps it. A name that is nothing BUT a suffix keeps it too: stripping it would leave a file with
     * no name at all.
     */
    fun strip(fileName: String): String {
        val dot = fileName.lastIndexOf('.')
        val stem = if (dot > 0) fileName.substring(0, dot) else fileName
        val ext = if (dot > 0) fileName.substring(dot) else ""
        val separator = stem.lastIndexOf(SEPARATOR)
        if (separator <= 0) return fileName
        val recorded = stem.substring(separator + SEPARATOR.length).toLongOrNull()
        if (recorded == null || recorded <= 0L) return fileName
        return stem.substring(0, separator) + ext
    }

    /** [fileName] carrying [captureTimeMs] in place of whatever it recorded before, so a date edit
     *  reaches the one store the vault reads its dates from. */
    fun restamp(fileName: String, captureTimeMs: Long?): String {
        val bare = strip(fileName)
        val dot = bare.lastIndexOf('.')
        val stem = if (dot > 0) bare.substring(0, dot) else bare
        val ext = if (dot > 0) bare.substring(dot) else ""
        return stem + suffix(captureTimeMs) + ext
    }
}
