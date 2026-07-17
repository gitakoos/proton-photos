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

/**
 * Locks the seconds → milliseconds conversion of [parsePhotoDuration]. The upload path writes
 * Media.Duration in SECONDS (a trimmed decimal), while every video-time formatter downstream wants
 * milliseconds, so the ×1000 here is load-bearing. Robolectric because the helper uses org.json.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ParsePhotoDurationTest {

    @Test
    fun `whole-second integer duration converts to milliseconds`() {
        assertEquals(7000L, parsePhotoDuration("""{"Media":{"Width":1,"Height":1,"Duration":7}}"""))
    }

    @Test
    fun `fractional-second duration converts and rounds to milliseconds`() {
        assertEquals(7500L, parsePhotoDuration("""{"Media":{"Duration":7.5}}"""))
        assertEquals(1230L, parsePhotoDuration("""{"Media":{"Duration":1.23}}"""))
    }

    @Test
    fun `duration encoded as a JSON string is still parsed`() {
        assertEquals(12000L, parsePhotoDuration("""{"Media":{"Duration":"12"}}"""))
    }

    @Test
    fun `missing Media block returns null`() {
        assertNull(parsePhotoDuration("""{"Common":{"Size":10}}"""))
    }

    @Test
    fun `missing Duration field returns null`() {
        assertNull(parsePhotoDuration("""{"Media":{"Width":1,"Height":1}}"""))
    }

    @Test
    fun `non-positive duration returns null`() {
        assertNull(parsePhotoDuration("""{"Media":{"Duration":0}}"""))
        assertNull(parsePhotoDuration("""{"Media":{"Duration":-3}}"""))
    }

    @Test
    fun `malformed json returns null instead of throwing`() {
        assertNull(parsePhotoDuration("not json at all"))
    }
}
