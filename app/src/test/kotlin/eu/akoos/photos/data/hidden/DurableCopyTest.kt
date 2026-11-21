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

package eu.akoos.photos.data.hidden

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import kotlin.random.Random

class DurableCopyTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun `the copy is forced to the storage after its last byte and before it returns`() {
        val bytes = Random(7).nextBytes(300_000)
        val target = File(tmp.root, "out.jpg")
        val events = mutableListOf<String>()
        var lengthAtSync = -1L
        val out = object : FileOutputStream(target) {
            override fun write(b: ByteArray, off: Int, len: Int) {
                events += "write"
                super.write(b, off, len)
            }
        }
        out.use {
            DurableCopy.copy(ByteArrayInputStream(bytes), it) { fd ->
                events += "sync"
                lengthAtSync = target.length()
                fd.sync()
            }
            events += "returned"
        }

        assertEquals(listOf("sync", "returned"), events.takeLast(2))
        assertEquals(1, events.count { it == "sync" })
        assertEquals(bytes.size.toLong(), lengthAtSync)
        assertArrayEquals(bytes, target.readBytes())
    }

    @Test(expected = IOException::class)
    fun `a copy that cannot be forced to the storage fails`() {
        FileOutputStream(File(tmp.root, "out.jpg")).use {
            DurableCopy.copy(ByteArrayInputStream(ByteArray(10)), it) { throw java.io.SyncFailedException("EIO") }
        }
    }
}
