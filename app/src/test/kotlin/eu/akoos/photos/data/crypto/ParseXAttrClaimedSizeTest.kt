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

package eu.akoos.photos.data.crypto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The size an upload declared, read from its XAttr's Common block. Robolectric for org.json. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ParseXAttrClaimedSizeTest {

    @Test
    fun `the declared size is Common Size`() {
        assertEquals(10_000L, parseXAttrClaimedSize("""{"Common":{"ModificationTime":"x","Size":10000,"BlockSizes":[10000]}}"""))
    }

    @Test
    fun `a missing, empty or malformed size reads as unknown`() {
        assertNull(parseXAttrClaimedSize("""{"Common":{"ModificationTime":"x"}}"""))
        assertNull(parseXAttrClaimedSize("""{"Common":{"Size":0}}"""))
        assertNull(parseXAttrClaimedSize("""{"Media":{"Width":1}}"""))
        assertNull(parseXAttrClaimedSize("not json"))
    }
}
