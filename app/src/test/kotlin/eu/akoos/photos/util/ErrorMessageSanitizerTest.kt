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

package eu.akoos.photos.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Coverage for [sanitizeErrorMessage] — the privacy guard that keeps raw server-error HTML and PII
 * (emails, link/share IDs, filenames, paths, URLs) out of user-facing toasts/snackbars. The order
 * of the redaction passes is load-bearing (URLs/paths before emails before filenames before the
 * broad opaque-ID rule), so the tests assert each placeholder AND that the leaked token is gone.
 */
class ErrorMessageSanitizerTest {

    @Test
    fun `null and blank degrade to a safe placeholder`() {
        assertEquals("unknown error", sanitizeErrorMessage(null))
        assertEquals("unknown error", sanitizeErrorMessage(""))
        assertEquals("unknown error", sanitizeErrorMessage("    "))
    }

    @Test
    fun `html tags are stripped out`() {
        val out = sanitizeErrorMessage("<html><body>Service Unavailable</body></html>")
        assertFalse(out.contains("<html>"))
        assertFalse(out.contains("<body>"))
        assertTrue(out.contains("Service Unavailable"))
    }

    @Test
    fun `an email address is redacted`() {
        val out = sanitizeErrorMessage("upload failed for user@example.com")
        assertTrue(out.contains("<email>"))
        assertFalse(out.contains("user@example.com"))
    }

    @Test
    fun `a url is redacted`() {
        val out = sanitizeErrorMessage("could not reach https://drive.proton.me/api/v1/x?token=abc")
        assertTrue(out.contains("<url>"))
        assertFalse(out.contains("https://"))
    }

    @Test
    fun `a filename is redacted to the file placeholder`() {
        val out = sanitizeErrorMessage("failed to encrypt IMG_2024.jpg before upload")
        assertTrue(out.contains("<file>"))
        assertFalse(out.lowercase().contains("img_2024.jpg"))
    }

    @Test
    fun `an opaque drive id is redacted`() {
        // A 20+ char base64-ish link/share id must collapse to <id>.
        val linkId = "aB3kJ9xQ2mLpZ7wR4tYvNs"
        val out = sanitizeErrorMessage("Failed to decrypt node $linkId")
        assertTrue(out.contains("<id>"))
        assertFalse(out.contains(linkId))
    }

    @Test
    fun `a filesystem path is redacted`() {
        val out = sanitizeErrorMessage("ENOENT open /storage/emulated/0/DCIM/Camera/x.jpg")
        assertTrue(out.contains("<path>"))
        assertFalse(out.contains("/storage/emulated/0"))
    }

    @Test
    fun `short ordinary words are not mistaken for opaque ids`() {
        // Below the 20-char base64 threshold, so plain prose survives intact.
        val out = sanitizeErrorMessage("connection reset by peer")
        assertEquals("connection reset by peer", out)
    }

    @Test
    fun `whitespace runs collapse to single spaces and trim`() {
        val out = sanitizeErrorMessage("  too    many\n\trequests  ")
        assertEquals("too many requests", out)
    }

    @Test
    fun `overly long messages are capped at 200 chars plus ellipsis`() {
        // Many short (<20 char) words so the opaque-id rule doesn't collapse the whole thing to <id>
        // before the cap. After whitespace-collapse this is well over 200 chars.
        val long = ("err " + "ab cd ef gh ij ".repeat(40)).trim()
        assertTrue("fixture must exceed the cap", long.length > 200)
        val out = sanitizeErrorMessage(long)
        // 200 chars + the single-char ellipsis.
        assertEquals(201, out.length)
        assertTrue(out.endsWith("…"))
    }

    @Test
    fun `a message that is only html collapses to the server-error placeholder`() {
        // After tags are removed nothing printable remains → "server error".
        assertEquals("server error", sanitizeErrorMessage("<div></div>"))
    }

    @Test
    fun `redact alias behaves identically to sanitizeErrorMessage`() {
        val raw = "boom for someone@host.org"
        assertEquals(sanitizeErrorMessage(raw), redact(raw))
        assertTrue(redact(raw).contains("<email>"))
    }
}
