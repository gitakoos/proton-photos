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

import eu.akoos.photos.data.importer.SourceReadException
import java.io.EOFException
import java.io.FileNotFoundException
import java.io.IOException
import java.util.zip.ZipException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What an import failure is called, on screen and in the diagnostics log. The log line must name the kind
 * of fault without ever repeating the exception's message, which can quote a file name.
 */
class ImportFailureTest {

    @Test
    fun `a full disk is storage, even when it is wrapped`() {
        val full = IOException("write failed: ENOSPC (No space left on device)")
        assertEquals(ImportFailureKind.STORAGE_FULL, ImportFailure.kindOf(full))
        assertEquals(ImportFailureKind.STORAGE_FULL, ImportFailure.kindOf(RuntimeException("wrapped", full)))
    }

    @Test
    fun `damage in the zip's own bytes is the archive`() {
        assertEquals(ImportFailureKind.ARCHIVE_DAMAGED, ImportFailure.kindOf(ZipException("invalid entry size")))
        assertEquals(ImportFailureKind.ARCHIVE_DAMAGED, ImportFailure.kindOf(EOFException("Unexpected end of ZLIB input stream")))
        assertEquals(
            ImportFailureKind.ARCHIVE_DAMAGED,
            ImportFailure.kindOf(IllegalArgumentException("MALFORMED")),
        )
    }

    @Test
    fun `any other read failure is the source, and an unrelated bug is neither`() {
        assertEquals(ImportFailureKind.SOURCE_UNREADABLE, ImportFailure.kindOf(IOException("connection reset")))
        assertEquals(ImportFailureKind.SOURCE_UNREADABLE, ImportFailure.kindOf(FileNotFoundException("gone")))
        assertEquals(ImportFailureKind.SOURCE_UNREADABLE, ImportFailure.kindOf(SecurityException("grant revoked")))
        assertEquals(ImportFailureKind.OTHER, ImportFailure.kindOf(IllegalStateException("bug")))
    }

    @Test
    fun `only the zip stream's own faults count as archive faults`() {
        assertTrue(ImportFailure.isArchiveFault(ZipException("x")))
        assertTrue(ImportFailure.isArchiveFault(IllegalArgumentException("MALFORMED input")))
        assertFalse(ImportFailure.isArchiveFault(IllegalArgumentException("something else")))
        assertFalse(ImportFailure.isArchiveFault(IOException("source dropped")))
    }

    @Test
    fun `the description carries class names and a known tag but never the message`() {
        val secret = "IMG_Birthday_Anna.jpg"
        val described = ImportFailure.describe(ZipException("invalid entry CRC for $secret"))
        assertTrue(described, described.contains("ZipException"))
        assertTrue(described, described.contains("bad-crc"))
        assertFalse(described, described.contains(secret))
    }

    @Test
    fun `a revoked folder grant is the source, and its uri never reaches the log`() {
        val revoked = SourceReadException(
            IOException(SecurityException("Permission Denial: reading content://provider/tree/primary%3APhotos")),
        )
        assertEquals(ImportFailureKind.SOURCE_UNREADABLE, ImportFailure.kindOf(revoked))
        val described = ImportFailure.describe(revoked)
        assertTrue(described, described.startsWith("SourceReadException < IOException < SecurityException"))
        assertTrue(described, described.contains("permission"))
        assertFalse(described, described.contains("primary"))
    }

    @Test
    fun `a message with no known fragment adds no tag`() {
        assertEquals("IOException", ImportFailure.describe(IOException("a name I would not log")))
    }

    @Test
    fun `the chain is described outermost first and cut at three`() {
        val deep = RuntimeException("a", IOException("b", IllegalStateException("c", ZipException("d"))))
        assertEquals("RuntimeException < IOException < IllegalStateException", ImportFailure.describe(deep))
    }
}
