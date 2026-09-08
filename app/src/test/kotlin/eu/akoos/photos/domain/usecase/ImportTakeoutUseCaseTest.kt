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

package eu.akoos.photos.domain.usecase

import eu.akoos.photos.domain.importer.SidecarMeta
import eu.akoos.photos.presentation.metadata.FilenameDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the pure metadata-mapping helpers the Takeout import builds each upload from: which capture date
 * wins, how a [SidecarMeta] and an entry name become a [eu.akoos.photos.domain.entity.LocalMediaItem]
 * and an upload xAttr, and how an entry name maps to a MIME type. Plain JVM, no Android, no I/O; the
 * Android `import()` shell is out of scope here.
 */
class ImportTakeoutUseCaseTest {

    /** 2021-01-01T00:00:00Z, the instant the sidecar-date fixtures encode. */
    private val jan2021Ms = 1_609_459_200_000L
    private val nowMs = 4_102_444_800_000L // 2100-01-01, a fixed ceiling well past the fixtures

    private fun meta(
        takenMs: Long? = null,
        lat: Double? = null,
        lng: Double? = null,
        description: String? = null,
        title: String? = null,
    ) = SidecarMeta(takenMs, lat, lng, description, title, favorited = false)

    // ---- resolveDateMs ---------------------------------------------------------------------------

    @Test fun `resolveDateMs prefers the sidecar date over the file name`() {
        val m = meta(takenMs = jan2021Ms)
        // The name encodes a different date; the sidecar must still win.
        val resolved = ImportTakeoutUseCase.resolveDateMs(m, "IMG_20231231_235959.jpg", nowMs)
        assertEquals(jan2021Ms, resolved)
    }

    @Test fun `resolveDateMs falls back to the file name when the sidecar has no date`() {
        val name = "Takeout/Google Photos/IMG_20210115_120000.jpg"
        // No date in the sidecar (only GPS), so the name's date is read, from its base name.
        val resolved = ImportTakeoutUseCase.resolveDateMs(meta(lat = 47.5, lng = 19.0), name, nowMs)
        val expected = FilenameDate.parse("IMG_20210115_120000.jpg", nowMs)
        assertNotNull(resolved)
        assertEquals(expected, resolved)
    }

    @Test fun `resolveDateMs reads the file name when there is no sidecar at all`() {
        val resolved = ImportTakeoutUseCase.resolveDateMs(null, "2019-08-24 14.30.10.jpg", nowMs)
        assertEquals(FilenameDate.parse("2019-08-24 14.30.10.jpg", nowMs), resolved)
        assertNotNull(resolved)
    }

    @Test fun `resolveDateMs is null when neither the sidecar nor the name carries a date`() {
        assertNull(ImportTakeoutUseCase.resolveDateMs(null, "funny_meme.jpg", nowMs))
        assertNull(ImportTakeoutUseCase.resolveDateMs(meta(description = "no date here"), "funny_meme.jpg", nowMs))
    }

    // ---- buildLocalMediaItem ---------------------------------------------------------------------

    @Test fun `buildLocalMediaItem marks a known date explicit and an unknown date not`() {
        val withDate = ImportTakeoutUseCase.buildLocalMediaItem(
            uri = "file:///t.jpg", meta = null, entryName = "t.jpg",
            sizeBytes = 10L, mimeType = "image/jpeg", dateMs = jan2021Ms,
        )
        assertEquals(jan2021Ms, withDate.dateTaken)
        assertTrue(withDate.dateTakenIsExplicit)

        val noDate = ImportTakeoutUseCase.buildLocalMediaItem(
            uri = "file:///t.jpg", meta = null, entryName = "t.jpg",
            sizeBytes = 10L, mimeType = "image/jpeg", dateMs = null,
        )
        assertEquals(0L, noDate.dateTaken)
        assertFalse(noDate.dateTakenIsExplicit)
    }

    @Test fun `buildLocalMediaItem takes the sidecar title but falls back to the base name`() {
        val titled = ImportTakeoutUseCase.buildLocalMediaItem(
            uri = "file:///x", meta = meta(title = "Sunset over the bay.jpg"),
            entryName = "Takeout/Google Photos/IMG_1.jpg",
            sizeBytes = 1L, mimeType = "image/jpeg", dateMs = null,
        )
        assertEquals("Sunset over the bay.jpg", titled.displayName)

        val blankTitle = ImportTakeoutUseCase.buildLocalMediaItem(
            uri = "file:///x", meta = meta(title = "   "),
            entryName = "Takeout/Google Photos/IMG_1.jpg",
            sizeBytes = 1L, mimeType = "image/jpeg", dateMs = null,
        )
        assertEquals("IMG_1.jpg", blankTitle.displayName)

        val noMeta = ImportTakeoutUseCase.buildLocalMediaItem(
            uri = "file:///x", meta = null, entryName = "Album/clip.mp4",
            sizeBytes = 1L, mimeType = "video/mp4", dateMs = null,
        )
        assertEquals("clip.mp4", noMeta.displayName)
    }

    @Test fun `buildLocalMediaItem carries the mime and sizes through with a null bucket`() {
        val item = ImportTakeoutUseCase.buildLocalMediaItem(
            uri = "file:///x", meta = null, entryName = "clip.mp4",
            sizeBytes = 4242L, mimeType = "video/mp4", dateMs = null,
        )
        assertEquals("video/mp4", item.mimeType)
        assertEquals(4242L, item.sizeBytes)
        assertNull(item.bucketName)
    }

    // ---- buildXAttr ------------------------------------------------------------------------------

    @Test fun `buildXAttr passes GPS through and formats the capture time as ISO UTC`() {
        val x = ImportTakeoutUseCase.buildXAttr(
            meta = meta(lat = 47.4979, lng = 19.0402), dateMs = jan2021Ms,
            width = 4032, height = 3024, durationMs = 15_000L,
        )
        assertEquals(47.4979, x.latitude!!, 1e-9)
        assertEquals(19.0402, x.longitude!!, 1e-9)
        assertEquals("2021-01-01T00:00:00Z", x.cameraCaptureTimeIso)
        assertEquals(4032, x.displayWidth)
        assertEquals(3024, x.displayHeight)
        assertEquals(15_000L, x.durationMillis)
    }

    @Test fun `buildXAttr leaves everything null when the sidecar and date are absent`() {
        val x = ImportTakeoutUseCase.buildXAttr(
            meta = null, dateMs = null, width = null, height = null, durationMs = null,
        )
        assertNull(x.latitude)
        assertNull(x.longitude)
        assertNull(x.cameraCaptureTimeIso)
        assertNull(x.displayWidth)
        assertNull(x.displayHeight)
        assertNull(x.durationMillis)
    }

    // ---- mimeTypeFor / isVideo -------------------------------------------------------------------

    @Test fun `mimeTypeFor maps the common extensions and defaults unknown ones to octet-stream`() {
        assertEquals("image/jpeg", ImportTakeoutUseCase.mimeTypeFor("a.jpg"))
        assertEquals("image/jpeg", ImportTakeoutUseCase.mimeTypeFor("Album/B.JPEG"))
        assertEquals("image/png", ImportTakeoutUseCase.mimeTypeFor("c.png"))
        assertEquals("image/heic", ImportTakeoutUseCase.mimeTypeFor("d.heic"))
        assertEquals("video/mp4", ImportTakeoutUseCase.mimeTypeFor("e.mp4"))
        assertEquals("video/quicktime", ImportTakeoutUseCase.mimeTypeFor("f.mov"))
        assertEquals("video/3gpp", ImportTakeoutUseCase.mimeTypeFor("g.3gp"))
        assertEquals("application/octet-stream", ImportTakeoutUseCase.mimeTypeFor("h.xyz"))
        assertEquals("application/octet-stream", ImportTakeoutUseCase.mimeTypeFor("noextension"))
    }

    @Test fun `isVideo is true only for a video mime`() {
        assertTrue(ImportTakeoutUseCase.isVideo("video/mp4"))
        assertTrue(ImportTakeoutUseCase.isVideo("video/quicktime"))
        assertFalse(ImportTakeoutUseCase.isVideo("image/jpeg"))
        assertFalse(ImportTakeoutUseCase.isVideo("application/octet-stream"))
    }
}
