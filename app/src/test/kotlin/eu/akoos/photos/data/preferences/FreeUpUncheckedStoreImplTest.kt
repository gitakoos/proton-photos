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

package eu.akoos.photos.data.preferences

import eu.akoos.photos.domain.entity.DeviceFileStamp
import eu.akoos.photos.domain.repository.FreeUpUncheckedStore.Kept
import org.junit.Assert.assertEquals
import org.junit.Test

/** The stored form of the copies free-up kept unchecked must read back exactly, whatever the uri holds. */
class FreeUpUncheckedStoreImplTest {

    @Test
    fun `entries read back as written`() {
        val entries = linkedMapOf(
            "content://media/external/images/media/1" to Kept(DeviceFileStamp(1100L, 1_700_000_000L), "a1b2"),
            "content://media/external/images/media/2|odd" to Kept(DeviceFileStamp(5L, 0L), "0"),
        )
        assertEquals(entries, FreeUpUncheckedStoreImpl.decode(FreeUpUncheckedStoreImpl.encode(entries)))
    }

    @Test
    fun `a damaged entry is skipped and nothing stored reads as none`() {
        assertEquals(emptyMap<String, Kept>(), FreeUpUncheckedStoreImpl.decode(null))
        assertEquals(
            mapOf("u" to Kept(DeviceFileStamp(1L, 2L), "k")),
            FreeUpUncheckedStoreImpl.decode(setOf("x|2|k|bad", "1|2|k|", "1|2||u", "1|2|u", "nonsense", "1|2|k|u")),
        )
    }

    @Test
    fun `past the limit the oldest entries go`() {
        val entries = (1..FreeUpUncheckedStoreImpl.MAX_ENTRIES + 3)
            .associate { "u$it" to Kept(DeviceFileStamp(it.toLong(), 0L), "k") }
        val kept = FreeUpUncheckedStoreImpl.decode(FreeUpUncheckedStoreImpl.encode(entries))
        assertEquals(FreeUpUncheckedStoreImpl.MAX_ENTRIES, kept.size)
        assertEquals(false, "u1" in kept)
        assertEquals(true, "u${FreeUpUncheckedStoreImpl.MAX_ENTRIES + 3}" in kept)
    }
}
