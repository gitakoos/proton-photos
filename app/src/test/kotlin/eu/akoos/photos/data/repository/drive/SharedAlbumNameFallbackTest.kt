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

package eu.akoos.photos.data.repository.drive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * What the shared-with-me listing shows for one album when its name will not decrypt, which an
 * owner's rename is enough to cause (#88). The listing writes its answer straight into the cache
 * with a REPLACE upsert, so both directions are durable damage: store a stub and the correct name
 * is gone, drop the entry and the album leaves the Shared tab and the cache with it.
 *
 * The two feeds answer differently because they know different things. The Photos album endpoint
 * returns albums only, so a nameless entry is still an album. The v2 backup feed also carries
 * ordinary folder shares and has no album flag, so there an undecryptable name is the only thing
 * separating the two.
 *
 * Pure strings → no DI, no DB, no crypto.
 */
class SharedAlbumNameFallbackTest {

    @Test
    fun `a fresh decrypt outranks the cached name`() {
        // The common case, and the one that repairs a cache still holding an older name.
        assertEquals(
            "Iceland 2026",
            resolveSharedAlbumName("Iceland 2026", "Iceland", LINK_ID),
        )
        assertEquals(
            "Iceland 2026",
            resolveBackupFeedAlbumName("Iceland 2026", "Iceland"),
        )
    }

    @Test
    fun `a failed decrypt keeps the cached name instead of the linkId stub`() {
        // The whole point of the fallback: a stub that reaches the REPLACE upsert destroys the last
        // good name, and nothing but a later successful decrypt can undo that.
        assertEquals("Iceland 2026", resolveSharedAlbumName(null, "Iceland 2026", LINK_ID))
    }

    @Test
    fun `a blank decrypt is a failure report and never a name`() {
        // Name decryption yields an empty string when the crypto fails, so a blank result must be
        // read the same way a null one is.
        assertEquals("Iceland 2026", resolveSharedAlbumName("", "Iceland 2026", LINK_ID))
        assertEquals("Iceland 2026", resolveSharedAlbumName("   ", "Iceland 2026", LINK_ID))
        assertEquals("Iceland 2026", resolveBackupFeedAlbumName("", "Iceland 2026"))
    }

    @Test
    fun `the stub is used only for an album this device has never named`() {
        // Nothing is being destroyed here, so the stub is the honest answer: it is all that is
        // known, and the album still has to appear.
        assertEquals("aaaabbbb", resolveSharedAlbumName(null, null, LINK_ID))
        assertEquals("aaaabbbb", resolveSharedAlbumName(null, "", LINK_ID))
    }

    @Test
    fun `the stub is eight characters of the linkId`() {
        assertEquals(8, resolveSharedAlbumName(null, null, LINK_ID).length)
        // A linkId shorter than the stub is taken whole rather than throwing.
        assertEquals("abc", resolveSharedAlbumName(null, null, "abc"))
    }

    @Test
    fun `the backup feed still drops an entry it cannot identify as an album`() {
        // The constraint the drop exists for: this feed carries folder shares too, and their names
        // are exactly the ones that will not decrypt. Losing this would put folders in the gallery.
        assertNull(resolveBackupFeedAlbumName(null, null))
        assertNull(resolveBackupFeedAlbumName("", null))
        assertNull(resolveBackupFeedAlbumName(null, ""))
    }

    @Test
    fun `the backup feed keeps an album it has cached, under its cached name`() {
        // A cached shared-album row is this device's own record that the entry is an album, so the
        // failed decrypt says nothing more than "not today". Dropping it here evicts the row as
        // well, so a renamed album leaves the Shared tab outright.
        assertEquals("Iceland 2026", resolveBackupFeedAlbumName(null, "Iceland 2026"))
    }

    @Test
    fun `the backup feed never invents a stub`() {
        // A stub would make every folder share look like an album, since the fallback would always
        // succeed. Only the album-endpoint path may reach for one.
        assertNull(resolveBackupFeedAlbumName(null, null))
    }

    private companion object {
        const val LINK_ID = "aaaabbbbccccdddd"
    }
}
