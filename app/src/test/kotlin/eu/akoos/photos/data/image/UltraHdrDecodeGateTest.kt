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

package eu.akoos.photos.data.image

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-value coverage for [UltraHdrDecodeGate.handlesUltraHdr], the decision that decides whether a
 * single image load takes the gain-map-preserving [UltraHdrDecoder] instead of Coil's ordinary
 * `BitmapFactory` path.
 *
 * Two properties carry the weight. The API floor, because only the API 34 decoder attaches a
 * `Gainmap` at all, so below it the path costs a header probe and returns a flat frame anyway. And
 * the opt-in, because an HDR bitmap holds the base image plus a gain map plane: the factory is
 * consulted for every image the app loads, and only the full-screen viewer may pay that. Both are
 * value decisions over an SDK level, a flag and a caller-supplied probe, so the whole matrix is
 * pinned here with no Android, no Coil pipeline and no real file.
 */
class UltraHdrDecodeGateTest {

    private companion object {
        /** First SDK level whose `ImageDecoder` attaches a gain map to the decoded bitmap. */
        const val SDK_WITH_GAIN_MAP_DECODE = 34
        const val SDK_BELOW_GAIN_MAP_DECODE = 33
    }

    private fun handles(
        sdkInt: Int = SDK_WITH_GAIN_MAP_DECODE,
        optedIn: Boolean = true,
        gainMap: Boolean = true,
    ) = UltraHdrDecodeGate.handlesUltraHdr(sdkInt, optedIn) { gainMap }

    @Test
    fun `an opted-in gain map on a decoding platform is handled`() {
        assertTrue(handles())
        assertTrue(handles(sdkInt = SDK_WITH_GAIN_MAP_DECODE + 1))
    }

    @Test
    fun `a platform below the gain-map decode is never handled`() {
        assertFalse(handles(sdkInt = SDK_BELOW_GAIN_MAP_DECODE))
        assertFalse(handles(sdkInt = 28))
        assertFalse(handles(sdkInt = 26))
    }

    @Test
    fun `an opted-in load without a gain map is not handled`() {
        assertFalse(handles(gainMap = false))
    }

    @Test
    fun `a gain map that did not opt in is not handled`() {
        assertFalse(handles(optedIn = false))
        assertFalse(handles(optedIn = false, sdkInt = SDK_WITH_GAIN_MAP_DECODE + 1))
    }

    /**
     * The probe reads the file's header and this gate runs for every image the app loads, so a grid,
     * thumbnail, widget or map load must be decided without ever touching the bytes. Only the opt-in
     * and the SDK level together may reach it.
     */
    @Test
    fun `a load that did not opt in never probes`() {
        var probes = 0
        val probe = { probes++; true }

        UltraHdrDecodeGate.handlesUltraHdr(SDK_WITH_GAIN_MAP_DECODE, optedIn = false, probe)
        UltraHdrDecodeGate.handlesUltraHdr(SDK_BELOW_GAIN_MAP_DECODE, optedIn = false, probe)
        UltraHdrDecodeGate.handlesUltraHdr(SDK_WITH_GAIN_MAP_DECODE + 1, optedIn = false, probe)
        assertEquals(0, probes)
    }

    @Test
    fun `a platform below the gain-map decode never probes`() {
        var probes = 0
        val probe = { probes++; true }

        UltraHdrDecodeGate.handlesUltraHdr(SDK_BELOW_GAIN_MAP_DECODE, optedIn = true, probe)
        UltraHdrDecodeGate.handlesUltraHdr(26, optedIn = true, probe)
        assertEquals(0, probes)

        UltraHdrDecodeGate.handlesUltraHdr(SDK_WITH_GAIN_MAP_DECODE, optedIn = true, probe)
        assertEquals(1, probes)
    }

    /** Omitting the probe entirely must not silently claim a load this decoder cannot serve. */
    @Test
    fun `the default probe reports no gain map`() {
        assertFalse(
            UltraHdrDecodeGate.handlesUltraHdr(SDK_WITH_GAIN_MAP_DECODE, optedIn = true)
        )
    }
}
