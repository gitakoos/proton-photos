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

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Coverage for both directions of the app's own MIME table.
 *
 * [mimeToFileExtension] is the single source of truth mapping a MIME type to the cache-file
 * extension. The explicit table entries exist because a naive subtype split yields container hints
 * (".quicktime", ".x-matroska") that ExoPlayer/Coil can't decode, so each special case is locked
 * down here, plus the subtype fallback and the video/audio-split on "mpeg".
 *
 * [mimeFromPath] answers the other way for a file no content provider speaks for — a vault copy, a
 * cache blob — where the name is the only thing left to read a type from. What it has to survive is
 * an awkward name rather than an awkward type: a dotted directory, a stem carrying dots of its own,
 * a file with no extension at all.
 */
class MimeExtensionsTest {

    @Test
    fun `jpeg maps to jpg`() {
        assertEquals("jpg", mimeToFileExtension("image/jpeg"))
    }

    @Test
    fun `png falls through to the raw subtype`() {
        // Not in the explicit table; the subtype IS the extension.
        assertEquals("png", mimeToFileExtension("image/png"))
    }

    @Test
    fun `quicktime maps to mov not the unusable raw subtype`() {
        assertEquals("mov", mimeToFileExtension("video/quicktime"))
    }

    @Test
    fun `matroska maps to mkv`() {
        assertEquals("mkv", mimeToFileExtension("video/x-matroska"))
    }

    @Test
    fun `msvideo maps to avi and ms-wmv maps to wmv`() {
        assertEquals("avi", mimeToFileExtension("video/x-msvideo"))
        assertEquals("wmv", mimeToFileExtension("video/x-ms-wmv"))
    }

    @Test
    fun `mpeg resolves to mpg for video but mp3 for audio`() {
        // The same "mpeg" subtype splits on the top-level type.
        assertEquals("mpg", mimeToFileExtension("video/mpeg"))
        assertEquals("mp3", mimeToFileExtension("audio/mpeg"))
    }

    @Test
    fun `subtype is lowercased`() {
        assertEquals("png", mimeToFileExtension("IMAGE/PNG"))
    }

    @Test
    fun `webp and heic fall through to their subtypes`() {
        assertEquals("webp", mimeToFileExtension("image/webp"))
        assertEquals("heic", mimeToFileExtension("image/heic"))
    }

    // ── the other direction, for a file no content provider answers for ─────────────────────────

    @Test
    fun `an app-private photo resolves its type from the name alone`() {
        // The case this exists for: a vault file is a plain file:// path, so getType returns null and
        // every mime-gated write would otherwise read a blank type and skip the branch that fits it.
        assertEquals("image/jpeg", mimeFromPath("/data/user/0/eu.akoos.photos/files/hidden/3f1a__1783507135000.jpg"))
        assertEquals("video/mp4", mimeFromPath("file:///data/user/0/eu.akoos.photos/files/hidden/3f1a.mp4"))
    }

    @Test
    fun `the two spellings of jpeg both resolve`() {
        assertEquals("image/jpeg", mimeFromPath("photo.jpg"))
        assertEquals("image/jpeg", mimeFromPath("photo.jpeg"))
    }

    @Test
    fun `the extension is read case-insensitively`() {
        assertEquals("image/png", mimeFromPath("SCREENSHOT.PNG"))
        assertEquals("video/quicktime", mimeFromPath("Clip.MOV"))
    }

    @Test
    fun `the containers with an unusable raw subtype map back to themselves`() {
        // Round-trips with [mimeToFileExtension], which is the pairing the vault relies on: a file
        // stored under the extension that mapping chose has to read back as the type it came from.
        for (mime in listOf(
            "image/jpeg", "image/png", "image/webp", "image/heic", "image/gif",
            "video/mp4", "video/quicktime", "video/x-matroska", "video/x-msvideo", "video/webm",
        )) {
            assertEquals(mime, mimeFromPath("photo.${mimeToFileExtension(mime)}"))
        }
    }

    @Test
    fun `a name with no extension, and one this app has no type for, resolve to nothing`() {
        // Blank is what the mime gates read as "a container I cannot write", which is the right
        // answer for a file whose type is unknown.
        assertEquals("", mimeFromPath("3f1a-4d21"))
        assertEquals("", mimeFromPath("archive.zip"))
        assertEquals("", mimeFromPath(""))
    }

    @Test
    fun `only the part after the last dot is read`() {
        assertEquals("image/jpeg", mimeFromPath("holiday.2026.beach.jpg"))
    }

    @Test
    fun `the still formats a camera and a screenshot produce all resolve`() {
        // A screenshot tool writes BMP, a modern camera and a share sheet write AVIF, and a raw
        // capture writes DNG. All three reach the vault as plain files, where the name is the only
        // thing left to read a type from.
        assertEquals("image/bmp", mimeFromPath("capture.bmp"))
        assertEquals("image/avif", mimeFromPath("IMG_0042.avif"))
        assertEquals("image/x-adobe-dng", mimeFromPath("DSC_0001.dng"))
    }

    @Test
    fun `the video containers a phone camera and a recording produce resolve`() {
        assertEquals("video/3gpp", mimeFromPath("VID_20260101.3gp"))
        assertEquals("video/mp2t", mimeFromPath("stream.ts"))
    }

    @Test
    fun `the newer extensions are read case-insensitively too`() {
        assertEquals("image/bmp", mimeFromPath("CAPTURE.BMP"))
        assertEquals("image/avif", mimeFromPath("Photo.Avif"))
        assertEquals("image/x-adobe-dng", mimeFromPath("RAW.DNG"))
        assertEquals("video/3gpp", mimeFromPath("Clip.3GP"))
        assertEquals("video/mp2t", mimeFromPath("Stream.TS"))
    }

    @Test
    fun `a vault name carrying the capture time still resolves its type`() {
        // Every vault file is named `<stem>__<epochMillis>.<ext>`, and a user-chosen stem may hold
        // dots of its own, so the type has to come from the last one and nothing earlier.
        assertEquals("image/bmp", mimeFromPath("file:///data/hidden/Beach.trip__1783507135000.bmp"))
        assertEquals("video/3gpp", mimeFromPath("/data/hidden/3f1a__1783507135000.3gp"))
    }

    @Test
    fun `a directory, with or without a dot in its name, resolves to nothing`() {
        // A dot anywhere on the path would be read as an extension by a naive split, and the app's
        // own private directory is named after a dotted package.
        assertEquals("", mimeFromPath("/storage/emulated/0/DCIM/Camera"))
        assertEquals("", mimeFromPath("/data/user/0/eu.akoos.photos/files/hidden"))
        assertEquals("", mimeFromPath("/data/user/0/eu.akoos.photos/files/hidden/3f1a"))
        assertEquals("", mimeFromPath("/storage/emulated/0/Pictures/Album.jpg/"))
    }

    @Test
    fun `the two extensions the cache mapping names differently do not round-trip`() {
        // The cache names a blob from the MIME type and the vault reads a type back from the name,
        // and the two tables agree for every container the round-trip test above walks. These do
        // not: the subtype fallback produces ".x-adobe-dng", ".3gpp" and ".mp2t", none of which is
        // an extension this side answers for.
        assertEquals("x-adobe-dng", mimeToFileExtension("image/x-adobe-dng"))
        assertEquals("3gpp", mimeToFileExtension("video/3gpp"))
        assertEquals("mp2t", mimeToFileExtension("video/mp2t"))
        for (mime in listOf("image/x-adobe-dng", "video/3gpp", "video/mp2t")) {
            assertEquals("", mimeFromPath("photo.${mimeToFileExtension(mime)}"))
        }
    }

    @Test
    fun `the two still formats with a plain subtype round-trip`() {
        for (mime in listOf("image/bmp", "image/avif")) {
            assertEquals(mime, mimeFromPath("photo.${mimeToFileExtension(mime)}"))
        }
    }
}
