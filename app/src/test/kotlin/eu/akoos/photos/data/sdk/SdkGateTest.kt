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

package eu.akoos.photos.data.sdk

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Coverage for [isSdkCapable]: the API 29 floor and the one unsupported primary ABI (32-bit x86),
 * with ABI lists ordered the way devices report them, primary first.
 */
class SdkGateTest {

    private val arm64Device = listOf("arm64-v8a", "armeabi-v7a", "armeabi")
    private val arm32Device = listOf("armeabi-v7a", "armeabi")
    private val x64Device = listOf("x86_64", "x86", "arm64-v8a", "armeabi-v7a")
    private val x86Device = listOf("x86", "armeabi-v7a", "armeabi")

    @Test
    fun `API 28 is not capable on any ABI`() {
        listOf(arm64Device, arm32Device, x64Device, x86Device).forEach { abis ->
            assertFalse("$abis", isSdkCapable(28, abis))
        }
    }

    @Test
    fun `API 29 is capable on arm64, arm32 and x86_64`() {
        assertTrue(isSdkCapable(29, arm64Device))
        assertTrue(isSdkCapable(29, arm32Device))
        assertTrue(isSdkCapable(29, x64Device))
    }

    @Test
    fun `API 36 is capable on arm64, arm32 and x86_64`() {
        assertTrue(isSdkCapable(36, arm64Device))
        assertTrue(isSdkCapable(36, arm32Device))
        assertTrue(isSdkCapable(36, x64Device))
    }

    @Test
    fun `a 32-bit x86 primary ABI is never capable`() {
        assertFalse(isSdkCapable(29, x86Device))
        assertFalse(isSdkCapable(36, x86Device))
    }

    @Test
    fun `only the primary ABI counts`() {
        // x86 behind an x86_64 primary does not block.
        assertTrue(isSdkCapable(36, x64Device))
        // An arm ABI behind a 32-bit x86 primary does not unblock.
        assertFalse(isSdkCapable(36, listOf("x86", "arm64-v8a")))
    }

    @Test
    fun `an empty ABI list is not capable`() {
        assertFalse(isSdkCapable(36, emptyList()))
    }
}
