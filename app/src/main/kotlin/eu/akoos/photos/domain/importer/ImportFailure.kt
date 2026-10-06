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

import java.io.EOFException
import java.io.IOException
import java.util.zip.ZipException

/** What an import failure means for the person looking at it, so the screen can say something useful. */
enum class ImportFailureKind {
    /** The device ran out of free storage while a file was being unpacked. */
    STORAGE_FULL,

    /** The place the zip is stored stopped answering or lost the file; picking it again continues. */
    SOURCE_UNREADABLE,

    /** The archive itself is damaged, truncated or in a layout the reader cannot follow. */
    ARCHIVE_DAMAGED,

    /** Anything else. */
    OTHER,
}

/**
 * Sorts an import failure into an [ImportFailureKind] and describes it for the diagnostics log without
 * ever carrying the exception's message: a message can quote an entry or file name, so the description is
 * built only from exception class names and a short tag taken from a fixed set of known fragments.
 */
object ImportFailure {

    /** Classifies [error] by walking its cause chain, so a wrapped storage or zip error is still found. */
    fun kindOf(error: Throwable): ImportFailureKind {
        val chain = chainOf(error)
        if (chain.any { isStorageFull(it) }) return ImportFailureKind.STORAGE_FULL
        if (chain.any { isArchiveFault(it) }) return ImportFailureKind.ARCHIVE_DAMAGED
        if (chain.any { it is IOException || it is SecurityException }) return ImportFailureKind.SOURCE_UNREADABLE
        return ImportFailureKind.OTHER
    }

    /** True for the faults a zip stream raises when its own bytes are unusable (as opposed to the place it
     *  is read from failing): the reader treats these as "the archive ends being readable here". */
    fun isArchiveFault(error: Throwable): Boolean =
        error is ZipException || error is EOFException || error is java.util.zip.DataFormatException ||
            (error is IllegalArgumentException && error.message?.contains("MALFORMED", ignoreCase = true) == true)

    /** The class names along [error]'s cause chain (at most three), then a known-fragment tag when the
     *  message carries one. Safe to log: no message text, no file or entry name. */
    fun describe(error: Throwable): String {
        val classes = chainOf(error).take(3).joinToString(" < ") { it.javaClass.simpleName.ifEmpty { "Throwable" } }
        val tag = chainOf(error).firstNotNullOfOrNull { messageTag(it.message) }
        return if (tag == null) classes else "$classes [$tag]"
    }

    internal fun messageTag(message: String?): String? {
        if (message.isNullOrEmpty()) return null
        return TAGS.firstOrNull { (fragment, _) -> message.contains(fragment, ignoreCase = true) }?.second
    }

    private fun isStorageFull(error: Throwable): Boolean {
        val m = error.message ?: return false
        return m.contains("ENOSPC") || m.contains("No space left", ignoreCase = true)
    }

    private fun chainOf(error: Throwable): List<Throwable> {
        val out = ArrayList<Throwable>(4)
        var cur: Throwable? = error
        while (cur != null && out.size < 8 && out.none { it === cur }) {
            out += cur
            cur = cur.cause
        }
        return out
    }

    private val TAGS = listOf(
        "ENOSPC" to "no-space",
        "No space left" to "no-space",
        "EXT descriptor" to "ext-descriptor",
        "invalid entry size" to "bad-size",
        "invalid entry compressed size" to "bad-size",
        "invalid entry CRC" to "bad-crc",
        "MALFORMED" to "malformed-name",
        "Unexpected end of ZLIB" to "zlib-eof",
        "invalid stored block" to "bad-block",
        "invalid distance" to "bad-block",
        "invalid code" to "bad-block",
        "Push back buffer" to "pushback",
        "Permission Denial" to "permission",
        "ENOENT" to "missing",
    )
}
