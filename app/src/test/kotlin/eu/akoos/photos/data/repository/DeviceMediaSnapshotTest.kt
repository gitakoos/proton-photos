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

package eu.akoos.photos.data.repository

import eu.akoos.photos.domain.entity.LocalMediaItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/** The device list kept for the next cold start reads back exactly, and only under the access it was made in. */
class DeviceMediaSnapshotTest {

    private val items = listOf(
        LocalMediaItem(
            uri = "content://media/external/images/media/1000144989", dateTaken = 1_700_000_000_000L,
            displayName = "PFP_1.jpg", mimeType = "image/jpeg", sizeBytes = 44_100L, bucketName = "Camera",
            width = 4000, height = 3000, dateModified = 1_700_000_100L, tags = setOf(3, 5), userTags = setOf(7),
        ),
        LocalMediaItem(
            uri = "content://media/external/video/media/42", dateTaken = 1_600_000_000_000L,
            displayName = "clip \u0151.mp4", mimeType = "video/mp4", sizeBytes = 9_000_000L, bucketName = null,
            duration = 12_000L, dateTakenIsExplicit = false,
        ),
        LocalMediaItem(
            uri = "content://some.provider/odd/path", dateTaken = 0L,
            displayName = "x", mimeType = "image/png", sizeBytes = 1L, bucketName = "Camera",
        ),
    )

    private fun bytes(env: String, list: List<LocalMediaItem>) =
        ByteArrayOutputStream().also { DeviceMediaSnapshot.write(it, env, list) }.toByteArray()

    @Test
    fun `a snapshot reads back the same items in the same order`() {
        assertEquals(items, DeviceMediaSnapshot.read(ByteArrayInputStream(bytes("env", items)), "env"))
    }

    @Test
    fun `an empty library reads back empty`() {
        assertEquals(emptyList<LocalMediaItem>(), DeviceMediaSnapshot.read(ByteArrayInputStream(bytes("env", emptyList())), "env"))
    }

    @Test
    fun `a snapshot made under other grants or volumes is not read`() {
        assertNull(DeviceMediaSnapshot.read(ByteArrayInputStream(bytes("env-a", items)), "env-b"))
    }

    @Test
    fun `a cut or damaged file reads as none instead of throwing`() {
        val whole = bytes("env", items)
        for (cut in listOf(0, 3, 9, whole.size / 2, whole.size - 1)) {
            assertNull("cut at $cut", DeviceMediaSnapshot.read(ByteArrayInputStream(whole.copyOf(cut)), "env"))
        }
        val garbage = whole.copyOf().also { it[0] = 0 }
        assertNull(DeviceMediaSnapshot.read(ByteArrayInputStream(garbage), "env"))
    }

    @Test
    fun `odd uris are kept whole`() {
        val odd = listOf("content://media/external/images/media/007", "content://x/y/", "content://x/y/99999999999999999999")
            .map { items[0].copy(uri = it) }
        assertEquals(odd, DeviceMediaSnapshot.read(ByteArrayInputStream(bytes("env", odd)), "env"))
    }

    @Test
    fun `a snapshot larger than the heap allows, or a read no one waits for, reads as none`() {
        val whole = bytes("env", items)
        assertNull(DeviceMediaSnapshot.read(ByteArrayInputStream(whole), "env", maxItems = 2))
        assertNull(DeviceMediaSnapshot.read(ByteArrayInputStream(whole), "env", isActive = { false }))
    }

    @Test
    fun `a file write replaces the old snapshot whole`() {
        val dir = kotlin.io.path.createTempDirectory().toFile()
        val file = java.io.File(dir, "snap.bin")
        DeviceMediaSnapshot.writeFile(file, "env", items)
        DeviceMediaSnapshot.writeFile(file, "env", items.take(1))
        assertEquals(items.take(1), DeviceMediaSnapshot.readFile(file, "env"))
        assertEquals(listOf("snap.bin"), dir.list()!!.toList())
        dir.deleteRecursively()
    }
}
