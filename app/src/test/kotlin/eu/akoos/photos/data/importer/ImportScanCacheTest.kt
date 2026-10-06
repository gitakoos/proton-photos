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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** The saved first-pass result a resumed import relies on: it must come back exactly, belong to one
 *  archive, and a damaged file must read as "no saved scan" rather than an error. */
class ImportScanCacheTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val full = SidecarMeta(
        takenMs = 1609459200000L, lat = 47.5, lng = 19.04,
        description = "Árvíztűrő tükörfúrógép ☃", title = "IMG_1.jpg", favorited = true,
    )
    private val sparse = SidecarMeta(takenMs = null, lat = null, lng = null, description = null, title = null)

    private fun scan() = TakeoutScan(mapOf("img_1.jpg" to full, "img_2.jpg" to sparse), mediaCount = 7)

    @Test
    fun `a saved scan comes back exactly`() {
        val cache = ImportScanCache(tmp.newFolder())
        cache.save("content://zip", 1_000L, scan())

        val loaded = cache.load("content://zip", 1_000L)

        assertNotNull(loaded)
        assertEquals(7, loaded!!.mediaCount)
        assertEquals(full, loaded.sidecars["img_1.jpg"])
        assertEquals(sparse, loaded.sidecars["img_2.jpg"])
    }

    @Test
    fun `a scan belongs to one archive`() {
        val cache = ImportScanCache(tmp.newFolder())
        cache.save("content://zip", 1_000L, scan())

        assertNull("another uri has no saved scan", cache.load("content://other", 1_000L))
        assertNull("a different size is a different file", cache.load("content://zip", 2_000L))
        assertNotNull("an unknown size cannot rule it out", cache.load("content://zip", -1L))
    }

    @Test
    fun `a damaged file is a fresh start and is removed`() {
        val dir = tmp.newFolder()
        val cache = ImportScanCache(dir)
        cache.save("content://zip", 1_000L, scan())
        val file = dir.listFiles()!!.single { it.name.endsWith(".scan") }
        file.writeBytes(file.readBytes().copyOf(file.length().toInt() / 2))

        assertNull(cache.load("content://zip", 1_000L))
        assertFalse(file.exists())
    }

    @Test
    fun `delete drops the saved scan and a half-written temp file is never read as one`() {
        val dir = tmp.newFolder()
        val cache = ImportScanCache(dir)
        cache.save("content://zip", 1_000L, scan())
        assertTrue(cache.exists("content://zip"))
        cache.delete("content://zip")
        assertFalse(cache.exists("content://zip"))
        assertNull(cache.load("content://zip", 1_000L))
    }
}
