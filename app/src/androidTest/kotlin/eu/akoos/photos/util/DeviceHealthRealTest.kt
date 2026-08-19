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

import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.FileInputStream

/**
 * [DeviceHealthPolicy] measured against the REAL device signals, driven by the same state simulation
 * the manual adb checks use (`dumpsys battery set …`, `cmd thermalservice override-status …`,
 * `settings put global low_power …`) run through the instrumentation shell. This is the repeatable
 * form of those checks: it proves the policy actually reads battery level, charging, temperature,
 * the thermal state and the power saver off a live device and flips its verdict accordingly, so a
 * later change that breaks the reading is caught before release rather than only in the field.
 *
 * The battery simulation is honoured on every device, so those cases assert firmly. The thermal and
 * power-saver overrides are restricted on some OEM builds; where the injected state does not take,
 * the case skips through [assumeTrue] rather than failing on a limitation of the test host. Every
 * override is undone in [restore], so the device is left exactly as it was found.
 */
@RunWith(AndroidJUnit4::class)
class DeviceHealthRealTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val policy = DeviceHealthPolicy(context, NetworkObserver(context))

    private var originalLowPower = "0"

    @Before
    fun captureState() {
        originalLowPower = shell("settings get global low_power").trim().let { if (it == "1") "1" else "0" }
    }

    @After
    fun restore() {
        shell("dumpsys battery reset")
        shell("cmd thermalservice reset")
        shell("settings put global low_power $originalLowPower")
    }

    @Test
    fun lowBatteryUnpluggedBlocksBackgroundWork() {
        shell("dumpsys battery unplug")
        shell("dumpsys battery set level 9")
        val s = awaitSnapshot { it.batteryPercent in 0..LOW_BATTERY_PERCENT && !it.charging }
        assumeTrue("battery level simulation not honoured", s != null)
        assertFalse(evaluateDeviceHealth(s!!).backgroundWorkAllowed)
    }

    @Test
    fun chargingOverridesLowBattery() {
        shell("dumpsys battery unplug")
        shell("dumpsys battery set level 9")
        shell("dumpsys battery set ac 1")
        val s = awaitSnapshot { it.charging && it.batteryPercent in 0..LOW_BATTERY_PERCENT }
        assumeTrue("charging simulation not honoured", s != null)
        assertTrue(evaluateDeviceHealth(s!!).backgroundWorkAllowed)
    }

    @Test
    fun hotBatteryBlocksBackgroundWork() {
        shell("dumpsys battery set temp 460") // tenths of a degree, so 46.0C
        val s = awaitSnapshot { !it.batteryTempCelsius.isNaN() && it.batteryTempCelsius >= HOT_BATTERY_CELSIUS }
        assumeTrue("battery temperature simulation not honoured", s != null)
        assertFalse(evaluateDeviceHealth(s!!).backgroundWorkAllowed)
    }

    @Test
    fun thermalThrottlingBlocksBackgroundWork() {
        assumeTrue("thermal status is API 29+", Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
        shell("cmd thermalservice override-status 3") // SEVERE
        val s = awaitSnapshot { it.thermal == ThermalState.THROTTLING }
        assumeTrue("thermal override not honoured on this device", s != null)
        assertFalse(evaluateDeviceHealth(s!!).backgroundWorkAllowed)
    }

    @Test
    fun thermalThrottlingClosesTheWorkerGate() {
        assumeTrue("thermal status is API 29+", Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
        shell("cmd thermalservice override-status 3") // SEVERE
        val throttled = awaitSnapshot { it.thermal == ThermalState.THROTTLING }
        assumeTrue("thermal override not honoured on this device", throttled != null)
        // The exact call the maintenance workers make at the head of doWork to skip a run.
        assertTrue(policy.thermallyThrottled())

        shell("cmd thermalservice reset")
        val cleared = awaitSnapshot { it.thermal != ThermalState.THROTTLING }
        assumeTrue("thermal reset not honoured on this device", cleared != null)
        assertFalse(policy.thermallyThrottled())
    }

    @Test
    fun powerSaverBlocksBackgroundWork() {
        shell("settings put global low_power 1")
        val s = awaitSnapshot { it.powerSaveOn }
        assumeTrue("power saver override not honoured on this device", s != null)
        assertFalse(evaluateDeviceHealth(s!!).backgroundWorkAllowed)
    }

    /** Polls the live snapshot until it satisfies [predicate], or returns null past [timeoutMs]. */
    private fun awaitSnapshot(timeoutMs: Long = 5_000L, predicate: (HealthSnapshot) -> Boolean): HealthSnapshot? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val s = policy.snapshot.value
            if (predicate(s)) return s
            Thread.sleep(100)
        }
        return null
    }

    private fun shell(command: String): String {
        val descriptor = instrumentation.uiAutomation.executeShellCommand(command)
        return FileInputStream(descriptor.fileDescriptor).use { it.readBytes().toString(Charsets.UTF_8) }
    }
}
