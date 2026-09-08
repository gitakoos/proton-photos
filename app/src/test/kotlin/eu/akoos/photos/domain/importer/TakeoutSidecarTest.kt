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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the Takeout sidecar reader: [TakeoutSidecar.parse] must lift the real capture date, GPS and
 * caption out of the JSON and reject a garbage date rather than pass one on, and the matcher must
 * collapse a media file and its oddly named sidecar to the same key across every Takeout naming quirk.
 * Plain JVM, no Android, no I/O.
 */
class TakeoutSidecarTest {

    /** The contract the importer relies on: a media file resolves to its sidecar's index key. */
    private fun resolves(media: String, sidecar: String): Boolean =
        TakeoutSidecar.sidecarKey(sidecar) in TakeoutSidecar.candidateMediaKeys(media)

    // ---- parse -------------------------------------------------------------------------------------

    @Test fun `reads capture date GPS caption title and favorite from a full sidecar`() {
        val meta = TakeoutSidecar.parse(
            """
            {
              "title": "IMG_1234.jpg",
              "description": "Sunset at the lake",
              "creationTime": { "timestamp": "1699999999", "formatted": "upload" },
              "photoTakenTime": { "timestamp": "1673789696", "formatted": "taken" },
              "geoData": { "latitude": 47.4979, "longitude": 19.0402, "altitude": 102.0 },
              "geoDataExif": { "latitude": 47.4979, "longitude": 19.0402 },
              "favorited": true
            }
            """.trimIndent(),
        )!!
        // photoTakenTime (seconds) is used, not creationTime.
        assertEquals(1673789696000L, meta.takenMs)
        assertEquals(47.4979, meta.lat!!, 1e-9)
        assertEquals(19.0402, meta.lng!!, 1e-9)
        assertEquals("Sunset at the lake", meta.description)
        assertEquals("IMG_1234.jpg", meta.title)
        assertTrue(meta.favorited)
    }

    @Test fun `falls back to geoDataExif when geoData is the zero sentinel`() {
        val meta = TakeoutSidecar.parse(
            """
            {
              "photoTakenTime": { "timestamp": "1673789696" },
              "geoData": { "latitude": 0.0, "longitude": 0.0 },
              "geoDataExif": { "latitude": 10.5, "longitude": 20.25 }
            }
            """.trimIndent(),
        )!!
        assertEquals(10.5, meta.lat!!, 1e-9)
        assertEquals(20.25, meta.lng!!, 1e-9)
    }

    @Test fun `zero geoData with no exif fallback yields no location`() {
        val meta = TakeoutSidecar.parse(
            """{ "geoData": { "latitude": 0.0, "longitude": 0.0 } }""",
        )!!
        assertNull(meta.lat)
        assertNull(meta.lng)
        assertNull(meta.takenMs)
        assertFalse(meta.favorited)
    }

    @Test fun `missing photoTakenTime leaves the date null`() {
        val meta = TakeoutSidecar.parse(
            """{ "title": "IMG_1.jpg", "creationTime": { "timestamp": "1673789696" } }""",
        )!!
        assertNull(meta.takenMs)
        assertEquals("IMG_1.jpg", meta.title)
    }

    @Test fun `a pre-1990 timestamp is rejected as unreal`() {
        // 315532800 seconds is 1980-01-01, before the capture floor.
        val meta = TakeoutSidecar.parse("""{ "photoTakenTime": { "timestamp": "315532800" } }""")!!
        assertNull(meta.takenMs)
    }

    @Test fun `garbage timestamps become null rather than a wrong date`() {
        assertNull(TakeoutSidecar.parse("""{ "photoTakenTime": { "timestamp": "0" } }""")!!.takenMs)
        assertNull(TakeoutSidecar.parse("""{ "photoTakenTime": { "timestamp": "not-a-number" } }""")!!.takenMs)
        assertNull(TakeoutSidecar.parse("""{ "photoTakenTime": { "timestamp": "9999999999999" } }""")!!.takenMs)
        assertNull(TakeoutSidecar.parse("""{ "photoTakenTime": { "timestamp": "99999999999999999999" } }""")!!.takenMs)
    }

    @Test fun `a blank description is dropped`() {
        val meta = TakeoutSidecar.parse("""{ "title": "IMG_9.jpg", "description": "   " }""")!!
        assertNull(meta.description)
        assertEquals("IMG_9.jpg", meta.title)
    }

    @Test fun `malformed or empty JSON returns null without throwing`() {
        assertNull(TakeoutSidecar.parse("{ this is : not json "))
        assertNull(TakeoutSidecar.parse("not json at all"))
        assertNull(TakeoutSidecar.parse(""))
        assertNull(TakeoutSidecar.parse("   "))
        assertNull(TakeoutSidecar.parse("[]")) // a JSON array is not a sidecar object
    }

    // ---- matching ----------------------------------------------------------------------------------

    @Test fun `quirk 1 old form json beside the media`() {
        assertTrue(resolves("IMG_1234.jpg", "IMG_1234.jpg.json"))
    }

    @Test fun `quirk 2 supplemental-metadata form`() {
        assertTrue(
            resolves("IMG_20230115_123456.jpg", "IMG_20230115_123456.jpg.supplemental-metadata.json"),
        )
    }

    @Test fun `quirk 3 truncated supplemental tag`() {
        val media = "PXL_20230815_142536789.jpg"
        assertTrue(resolves(media, "PXL_20230815_142536789.jpg.supplemental-metad.json"))
        assertTrue(resolves(media, "PXL_20230815_142536789.jpg.suppl.json"))
        assertTrue(resolves(media, "PXL_20230815_142536789.jpg.s.json"))
    }

    @Test fun `quirk 4 extension omitted from the sidecar`() {
        assertTrue(resolves("IMG-20230115-WA0001.jpg", "IMG-20230115-WA0001.json"))
        assertTrue(resolves("SquareQuick_20191212594755.jpg", "SquareQuick_20191212594755.json"))
    }

    @Test fun `quirk 5 numbered duplicate counter before or after the extension`() {
        assertTrue(resolves("IMG_1234(1).jpg", "IMG_1234.jpg(1).json"))
        assertTrue(resolves("IMG_1234(1).jpg", "IMG_1234(1).jpg.json"))
        assertTrue(resolves("IMG_1234(1).jpg", "IMG_1234.jpg.supplemental-metadata(1).json"))
    }

    @Test fun `quirk 6 edited copies share the original sidecar`() {
        val sidecar = "IMG_1234.jpg.supplemental-metadata.json"
        assertTrue(resolves("IMG_1234-edited.jpg", sidecar))
        assertTrue(resolves("IMG_1234-bearbeitet.jpg", sidecar))
        assertTrue(resolves("IMG_1234-modifié.jpg", sidecar))
    }

    @Test fun `unrelated files do not resolve to each other`() {
        assertFalse(resolves("IMG_2000.jpg", "IMG_1000.jpg.json"))
        assertFalse(resolves("VID_0001.mp4", "IMG_1234.jpg.supplemental-metadata.json"))
    }

    // ---- album folder ------------------------------------------------------------------------------

    @Test fun `a Takeout album folder is the media's parent`() {
        assertEquals("Vacation", TakeoutSidecar.albumFolderOf("Takeout/Google Photos/Vacation/IMG_1.jpg"))
    }

    @Test fun `a Takeout year bucket is not an album`() {
        assertNull(TakeoutSidecar.albumFolderOf("Takeout/Google Photos/Photos from 2019/PXL.jpg"))
    }

    @Test fun `the archive and trash buckets are not albums`() {
        assertNull(TakeoutSidecar.albumFolderOf("Takeout/Google Photos/Archive/IMG_2.jpg"))
        assertNull(TakeoutSidecar.albumFolderOf("Takeout/Google Photos/Trash/IMG_3.jpg"))
        assertNull(TakeoutSidecar.albumFolderOf("Takeout/Google Photos/Bin/IMG_4.jpg"))
    }

    @Test fun `an Ente album folder is the media's parent`() {
        assertEquals("Trip 2020", TakeoutSidecar.albumFolderOf("Trip 2020/beach.jpg"))
    }

    @Test fun `media straight under the Google Photos container has no album`() {
        assertNull(TakeoutSidecar.albumFolderOf("Takeout/Google Photos/IMG_5.jpg"))
        assertNull(TakeoutSidecar.albumFolderOf("Takeout/Google Fotos/IMG_6.jpg"))
    }

    @Test fun `a root-level file has no album`() {
        assertNull(TakeoutSidecar.albumFolderOf("IMG_7.jpg"))
    }

    @Test fun `the year bucket match is case-insensitive`() {
        assertNull(TakeoutSidecar.albumFolderOf("Takeout/Google Photos/PHOTOS FROM 2019/x.jpg"))
        assertNull(TakeoutSidecar.albumFolderOf("Takeout/Google Photos/photos from 2021/y.jpg"))
    }

    @Test fun `a padded album folder name is trimmed`() {
        assertEquals("Vacation", TakeoutSidecar.albumFolderOf("Takeout/Google Photos/ Vacation /IMG_8.jpg"))
    }
}
