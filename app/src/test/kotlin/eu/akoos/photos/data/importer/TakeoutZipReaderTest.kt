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

package eu.akoos.photos.data.importer

import eu.akoos.photos.domain.importer.SidecarMeta
import eu.akoos.photos.domain.importer.TakeoutSidecar
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Pins the two-pass Takeout zip reader against a real in-memory `.zip`: pass 1 must index only the tiny
 * sidecar JSONs (an album's title-only `metadata.json` must not leak in) and key them so a media file
 * resolves to its sidecar, and pass 2 must stream exactly the media entries, in order, with a per-entry
 * stream the callback can read in full and even close without severing the walk. Plain JVM, no Android.
 */
class TakeoutZipReaderTest {

    /** Mirrors how the caller resolves a media entry to its sidecar: base name, then candidate keys. */
    private fun resolve(index: Map<String, SidecarMeta>, mediaEntryName: String): SidecarMeta? {
        val base = TakeoutZipReader.baseName(mediaEntryName)
        for (key in TakeoutSidecar.candidateMediaKeys(base)) index[key]?.let { return it }
        return null
    }

    private fun payload(seed: Int, size: Int): ByteArray =
        ByteArray(size) { ((it * 31 + seed) and 0xFF).toByte() }

    /** Builds a real zip in memory. A name ending in `/` is written as a directory entry. */
    private fun buildZip(entries: List<Pair<String, ByteArray?>>): ByteArray {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { zip ->
            for ((name, data) in entries) {
                zip.putNextEntry(ZipEntry(name))
                if (data != null) zip.write(data)
                zip.closeEntry()
            }
        }
        return bytes.toByteArray()
    }

    private fun reader(bytes: ByteArray) = TakeoutZipReader { ByteArrayInputStream(bytes) }

    @Test fun `pass 1 indexes real sidecars and the album metadata does not pollute the index`() {
        val bytes = buildZip(
            listOf(
                "IMG_1.jpg" to payload(1, 64),
                "IMG_1.jpg.supplemental-metadata.json" to
                    """{"title":"IMG_1.jpg","photoTakenTime":{"timestamp":"1609459200"},"geoData":{"latitude":47.5,"longitude":19.0}}"""
                        .toByteArray(),
                "IMG_2.jpg" to payload(2, 64),
                "IMG_2.jpg.json" to
                    """{"title":"IMG_2.jpg","photoTakenTime":{"timestamp":"1612137600"}}""".toByteArray(),
                "Vacation 2021/metadata.json" to
                    """{"title":"Vacation 2021","access":"protected"}""".toByteArray(),
            ),
        )

        val index = reader(bytes).indexSidecars()

        assertEquals("only the two real sidecars are indexed", 2, index.size)
        assertNull("the album's title-only metadata.json must not be indexed", index["metadata"])

        val one = resolve(index, "IMG_1.jpg")
        assertNotNull("the current-form sidecar pairs with its media", one)
        assertEquals(1609459200000L, one!!.takenMs)
        assertEquals(47.5, one.lat!!, 1e-9)

        val two = resolve(index, "IMG_2.jpg")
        assertNotNull("the old-form sidecar pairs with its media", two)
        assertEquals(1612137600000L, two!!.takenMs)
    }

    @Test fun `scan reports the media count from the same walk that indexes sidecars`() {
        val bytes = buildZip(
            listOf(
                "Album/" to null, // a directory entry is neither media nor sidecar
                "IMG_1.jpg" to payload(1, 64),
                "IMG_1.jpg.supplemental-metadata.json" to
                    """{"photoTakenTime":{"timestamp":"1609459200"}}""".toByteArray(),
                "IMG_2.png" to payload(2, 64),
                "clip.mp4" to payload(3, 64),
                "notes.json" to """{"title":"stub"}""".toByteArray(), // title-only stub, not indexed
            ),
        )

        val scan = reader(bytes).scan()

        assertEquals("three media entries are counted, the directory and JSONs are not", 3, scan.mediaCount)
        assertEquals("only the one real sidecar is indexed", 1, scan.sidecars.size)
        assertNotNull("the counted walk still pairs a media file with its sidecar", resolve(scan.sidecars, "IMG_1.jpg"))
    }

    @Test fun `pass 2 visits exactly the media entries in order and reads each entry's bytes`() {
        val a = payload(10, 100)
        val b = payload(20, 20_000) // larger than one read chunk, so multi-chunk reads are exercised
        val c = payload(30, 250)
        val bytes = buildZip(
            listOf(
                "Album/" to null, // a directory entry must be skipped
                "A.jpg" to a,
                "A.jpg.json" to """{"photoTakenTime":{"timestamp":"1609459200"}}""".toByteArray(),
                "B.mp4" to b,
                "notes.json" to """{"title":"stub"}""".toByteArray(),
                "C.png" to c,
            ),
        )

        val visited = mutableListOf<String>()
        val read = mutableMapOf<String, ByteArray>()
        reader(bytes).forEachMedia { name, input ->
            visited += name
            read[name] = input.readBytes()
        }

        assertEquals(listOf("A.jpg", "B.mp4", "C.png"), visited)
        assertArrayEquals(a, read["A.jpg"])
        assertArrayEquals(b, read["B.mp4"])
        assertArrayEquals(c, read["C.png"])
    }

    @Test fun `folder-prefixed entries are handled by base name in both passes`() {
        val media = payload(7, 128)
        val prefix = "Takeout/Google Photos/Album/"
        val bytes = buildZip(
            listOf(
                "${prefix}IMG_1.jpg" to media,
                "${prefix}IMG_1.jpg.supplemental-metadata.json" to
                    """{"photoTakenTime":{"timestamp":"1609459200"},"description":"beach"}""".toByteArray(),
            ),
        )
        val r = reader(bytes)

        val index = r.indexSidecars()
        val meta = resolve(index, "${prefix}IMG_1.jpg")
        assertNotNull("a sidecar under a folder prefix still pairs with its media", meta)
        assertEquals("beach", meta!!.description)

        val visited = mutableListOf<String>()
        r.forEachMedia { name, _ -> visited += name }
        assertEquals(listOf("${prefix}IMG_1.jpg"), visited)
    }

    @Test fun `an Ente export pairs album media with its metadata-subfolder sidecar`() {
        val flower = payload(4, 128)
        val beach = payload(5, 256)
        // Ente lays out each album as the media in the album folder with its sidecar under that album's
        // own metadata/ subfolder, using the same Takeout-format JSON (photoTakenTime, geoData,
        // description). base name strips the metadata/ prefix, so a media file and its sidecar collapse
        // to the same key and pair with no reader change.
        val bytes = buildZip(
            listOf(
                "MyAlbum/" to null,
                "MyAlbum/flower.png" to flower,
                "MyAlbum/metadata/" to null,
                "MyAlbum/metadata/flower.png.json" to
                    """{"title":"flower.png","description":"garden","photoTakenTime":{"timestamp":"1609459200","formatted":"Jan 1, 2021, 12:00:00 AM UTC"},"geoData":{"latitude":47.5,"longitude":19.0},"creationTime":{"timestamp":"1609459300"},"modificationTime":{"timestamp":"1609459400"}}"""
                        .toByteArray(),
                "Trip/" to null,
                "Trip/beach.jpg" to beach,
                "Trip/metadata/" to null,
                "Trip/metadata/beach.jpg.json" to
                    """{"title":"beach.jpg","photoTakenTime":{"timestamp":"1612137600","formatted":"Feb 1, 2021, 12:00:00 AM UTC"},"geoData":{"latitude":36.7,"longitude":-4.4}}"""
                        .toByteArray(),
            ),
        )

        val r = reader(bytes)

        // Pass 1 indexes both sidecars even though each lives in its album's metadata/ subfolder.
        val index = r.indexSidecars()
        assertEquals("both sidecars from the metadata/ subfolders are indexed", 2, index.size)

        // Each album's media resolves through base name + candidate keys to its subfolder sidecar.
        val flowerMeta = resolve(index, "MyAlbum/flower.png")
        assertNotNull("flower.png pairs with MyAlbum/metadata/flower.png.json", flowerMeta)
        assertEquals(1609459200000L, flowerMeta!!.takenMs)
        assertEquals(47.5, flowerMeta.lat!!, 1e-9)
        assertEquals(19.0, flowerMeta.lng!!, 1e-9)
        assertEquals("garden", flowerMeta.description)

        val beachMeta = resolve(index, "Trip/beach.jpg")
        assertNotNull("beach.jpg pairs with Trip/metadata/beach.jpg.json", beachMeta)
        assertEquals(1612137600000L, beachMeta!!.takenMs)
        assertEquals(-4.4, beachMeta.lng!!, 1e-9)

        // Pass 2 visits exactly the album media, never a json and never a metadata directory entry.
        val visited = mutableListOf<String>()
        r.forEachMedia { name, _ -> visited += name }
        assertEquals(
            "only the album media is visited, not the sidecars or the metadata dirs",
            listOf("MyAlbum/flower.png", "Trip/beach.jpg"),
            visited,
        )
    }

    @Test fun `a callback closing its stream does not break the reader`() {
        val a = payload(1, 300)
        val b = payload(2, 400)
        val bytes = buildZip(listOf("A.jpg" to a, "B.jpg" to b))

        val read = mutableMapOf<String, ByteArray>()
        reader(bytes).forEachMedia { name, input ->
            read[name] = input.readBytes()
            input.close() // the non-closing wrapper must swallow this so the next entry is still read
        }

        assertEquals(setOf("A.jpg", "B.jpg"), read.keys)
        assertArrayEquals(a, read["A.jpg"])
        assertArrayEquals("the second entry is intact after the first callback closed its stream", b, read["B.jpg"])
    }

    @Test fun `an oversized json entry is skipped rather than buffered as a sidecar`() {
        // A 2 MB blob with a .json name is past the sidecar cap, so it is never parsed or indexed.
        val huge = ByteArray(2 * 1_048_576) { '{'.code.toByte() }
        val bytes = buildZip(
            listOf(
                "IMG_1.jpg" to payload(1, 32),
                "IMG_1.jpg.json" to """{"photoTakenTime":{"timestamp":"1609459200"}}""".toByteArray(),
                "bloated.json" to huge,
            ),
        )

        val index = reader(bytes).indexSidecars()

        assertEquals("only the small real sidecar survives the cap", 1, index.size)
        assertNotNull(resolve(index, "IMG_1.jpg"))
    }

    @Test fun `helpers classify names by extension and strip directories`() {
        assertEquals("IMG_1.jpg", TakeoutZipReader.baseName("Takeout/Photos/IMG_1.jpg"))
        assertEquals("IMG_1.jpg", TakeoutZipReader.baseName("IMG_1.jpg"))
        for (media in listOf("a.JPG", "b.heic", "c.mp4", "d.mp", "e.3gpp", "f.dng")) {
            assertEquals("$media is media", true, TakeoutZipReader.isMedia(media))
        }
        for (nonMedia in listOf("a.json", "b.txt", "c.html", "noext", "d.")) {
            assertEquals("$nonMedia is not media", false, TakeoutZipReader.isMedia(nonMedia))
        }
        assertEquals(true, TakeoutZipReader.isJson("x.JSON"))
        assertEquals(false, TakeoutZipReader.isJson("x.jpg"))
    }

    @Test fun `each pass reopens the stream so the zip is read twice`() {
        var opens = 0
        val bytes = buildZip(
            listOf(
                "IMG_1.jpg" to payload(1, 16),
                "IMG_1.jpg.json" to """{"photoTakenTime":{"timestamp":"1609459200"}}""".toByteArray(),
            ),
        )
        val r = TakeoutZipReader {
            opens++
            ByteArrayInputStream(bytes) as InputStream
        }

        r.indexSidecars()
        r.forEachMedia { _, _ -> }

        assertEquals("one fresh stream per pass", 2, opens)
    }
}
