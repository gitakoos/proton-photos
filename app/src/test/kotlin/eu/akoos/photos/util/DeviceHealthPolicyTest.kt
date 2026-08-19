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

package eu.akoos.photos.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Coverage for [evaluateDeviceHealth], the pure decision behind the device-health gate. Asserts the
 * two-tier split (heavy ML vs deferrable background walks), the physical-stress gates, the
 * charging override, the interaction rule, and the fail-open behaviour on unreadable signals.
 */
class DeviceHealthPolicyTest {

    private fun snap(
        batteryPercent: Int = 80,
        charging: Boolean = false,
        batteryTempCelsius: Float = 25f,
        thermal: ThermalState = ThermalState.NORMAL,
        powerSaveOn: Boolean = false,
        interacting: Boolean = false,
        online: Boolean = true,
    ) = HealthSnapshot(batteryPercent, charging, batteryTempCelsius, thermal, powerSaveOn, interacting, online)

    @Test
    fun `a healthy device allows both tiers`() {
        val v = evaluateDeviceHealth(snap())
        assertTrue(v.heavyMlAllowed)
        assertTrue(v.backgroundWorkAllowed)
    }

    @Test
    fun `low battery and not charging blocks both tiers`() {
        val v = evaluateDeviceHealth(snap(batteryPercent = 10, charging = false))
        assertFalse(v.heavyMlAllowed)
        assertFalse(v.backgroundWorkAllowed)
    }

    @Test
    fun `low battery while charging is allowed`() {
        val v = evaluateDeviceHealth(snap(batteryPercent = 8, charging = true))
        assertTrue(v.heavyMlAllowed)
        assertTrue(v.backgroundWorkAllowed)
    }

    @Test
    fun `exactly at the low-battery floor counts as low`() {
        // The rule is strictly-greater-than the floor, so 15 itself is not enough.
        assertFalse(evaluateDeviceHealth(snap(batteryPercent = LOW_BATTERY_PERCENT)).backgroundWorkAllowed)
        assertTrue(evaluateDeviceHealth(snap(batteryPercent = LOW_BATTERY_PERCENT + 1)).backgroundWorkAllowed)
    }

    @Test
    fun `a hot battery blocks both tiers`() {
        val v = evaluateDeviceHealth(snap(batteryTempCelsius = 44f))
        assertFalse(v.heavyMlAllowed)
        assertFalse(v.backgroundWorkAllowed)
    }

    @Test
    fun `thermal throttling blocks both tiers`() {
        val v = evaluateDeviceHealth(snap(thermal = ThermalState.THROTTLING))
        assertFalse(v.heavyMlAllowed)
        assertFalse(v.backgroundWorkAllowed)
    }

    @Test
    fun `power saver stands down both background tiers`() {
        val v = evaluateDeviceHealth(snap(powerSaveOn = true))
        assertFalse(v.heavyMlAllowed)
        assertFalse(v.backgroundWorkAllowed)
    }

    @Test
    fun `interaction pauses heavy ML only while background walks continue`() {
        val v = evaluateDeviceHealth(snap(interacting = true))
        assertFalse(v.heavyMlAllowed)
        assertTrue(v.backgroundWorkAllowed)
    }

    @Test
    fun `an unreadable battery level does not block`() {
        val v = evaluateDeviceHealth(snap(batteryPercent = -1, charging = false))
        assertTrue(v.heavyMlAllowed)
        assertTrue(v.backgroundWorkAllowed)
    }

    @Test
    fun `an unreadable temperature and unknown thermal state do not block`() {
        val v = evaluateDeviceHealth(snap(batteryTempCelsius = Float.NaN, thermal = ThermalState.UNKNOWN))
        assertTrue(v.heavyMlAllowed)
        assertTrue(v.backgroundWorkAllowed)
    }

    @Test
    fun `standDownForThermal is true only while the OS is throttling`() {
        // The maintenance workers skip a run on throttling; an unknown thermal state must not, so an
        // OEM that never reports thermal keeps its pre-gate behaviour.
        assertTrue(standDownForThermal(ThermalState.THROTTLING))
        assertFalse(standDownForThermal(ThermalState.NORMAL))
        assertFalse(standDownForThermal(ThermalState.UNKNOWN))
    }
}
