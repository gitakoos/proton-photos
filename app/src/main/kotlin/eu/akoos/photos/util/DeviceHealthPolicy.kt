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

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/** How the OS thermal signal is folded to the one distinction the app acts on. */
enum class ThermalState { NORMAL, THROTTLING, UNKNOWN }

/** A point-in-time reading of the device conditions the background tiers care about. */
data class HealthSnapshot(
    val batteryPercent: Int,       // 0..100, or -1 when the level is unreadable
    val charging: Boolean,         // on external power (AC / USB / wireless)
    val batteryTempCelsius: Float, // Float.NaN when the temperature is unreadable
    val thermal: ThermalState,
    val powerSaveOn: Boolean,
    val interacting: Boolean,      // the user has touched the screen within the interaction window
    val online: Boolean,
)

/** What a given [HealthSnapshot] permits, split by how disruptive the work is. */
data class HealthVerdict(
    val heavyMlAllowed: Boolean,        // on-device model inference (face indexing, text recognition)
    val backgroundWorkAllowed: Boolean, // deferrable background walks (metadata backfills, hashing, thumbnail warm-up)
    val reason: String,                 // short, for diagnostics
)

/**
 * The specific condition standing heavy on-device work down, mirroring [evaluateDeviceHealth]'s
 * precedence so a caller can name the block the same way the gate decides it. [NONE] means heavy work
 * is permitted. [INTERACTION] is transient and clears once the user stops touching the screen, while
 * [LOW_BATTERY], [WARM] and [POWER_SAVE] persist until the condition or the setting changes, so the
 * settings card treats those three as the blocks a pause or resume cannot lift.
 */
enum class HealthBlockReason {
    NONE, LOW_BATTERY, WARM, POWER_SAVE, INTERACTION;

    /** True for a block that will not clear on its own, so the face card disables its pause / resume
     *  control while one holds; a transient interaction pause and [NONE] leave it live. */
    val isPersistent: Boolean
        get() = this == LOW_BATTERY || this == WARM || this == POWER_SAVE
}

/**
 * Decides what a snapshot permits. Pure and Android-free so it can be unit-tested directly.
 *
 * Two levels, because the two kinds of background work tolerate different conditions:
 *  - **Heavy ML** (model inference) is the most power and heat intensive, and running it while the
 *    user is touching the screen competes with the UI, so it also stops on interaction.
 *  - **Background walks** are lighter and invisible, so they keep going while the user interacts,
 *    but still stand down under the physical-stress and power-saver conditions.
 *
 * Physical-stress gates are shared: a low battery that is NOT charging, a hot battery, or the OS
 * reporting thermal throttling. The power saver stands down both background tiers (it is an explicit
 * "spend less" signal), but callers that back up the user's photos deliberately do NOT consult this
 * verdict for that: a backup follows the user's own network preference, not the power saver.
 *
 * Unreadable signals do not block: a device that cannot report its temperature or thermal state
 * behaves exactly as it did before this gate existed, so adding the gate never suppresses work on a
 * guess, it only stands work down on a condition it can positively see.
 */
fun evaluateDeviceHealth(
    snapshot: HealthSnapshot,
    lowBatteryPercent: Int = LOW_BATTERY_PERCENT,
    hotBatteryCelsius: Float = HOT_BATTERY_CELSIUS,
): HealthVerdict {
    val batteryOk = snapshot.charging ||
        snapshot.batteryPercent < 0 ||
        snapshot.batteryPercent > lowBatteryPercent
    val temperatureOk = snapshot.batteryTempCelsius.isNaN() ||
        snapshot.batteryTempCelsius <= hotBatteryCelsius
    val thermalOk = snapshot.thermal != ThermalState.THROTTLING
    val physicallyOk = batteryOk && temperatureOk && thermalOk

    val backgroundWorkAllowed = physicallyOk && !snapshot.powerSaveOn
    val heavyMlAllowed = backgroundWorkAllowed && !snapshot.interacting

    val reason = when {
        !batteryOk -> "battery ${snapshot.batteryPercent}% not charging"
        !temperatureOk -> "battery ${snapshot.batteryTempCelsius}C"
        !thermalOk -> "thermal throttling"
        snapshot.powerSaveOn -> "power saver on"
        snapshot.interacting -> "user interacting"
        else -> "ok"
    }
    return HealthVerdict(heavyMlAllowed, backgroundWorkAllowed, reason)
}

/**
 * Which condition, in [evaluateDeviceHealth]'s exact precedence, is standing heavy ML down, or
 * [HealthBlockReason.NONE] when it is permitted. Shares [evaluateDeviceHealth]'s thresholds and order
 * so the two never drift: a hot battery and thermal throttling both fold to [HealthBlockReason.WARM],
 * the single "phone is warm" story the card tells. Pure and Android-free, so it is unit-tested directly.
 */
fun heavyMlBlockReason(
    snapshot: HealthSnapshot,
    lowBatteryPercent: Int = LOW_BATTERY_PERCENT,
    hotBatteryCelsius: Float = HOT_BATTERY_CELSIUS,
): HealthBlockReason {
    val batteryOk = snapshot.charging ||
        snapshot.batteryPercent < 0 ||
        snapshot.batteryPercent > lowBatteryPercent
    val temperatureOk = snapshot.batteryTempCelsius.isNaN() ||
        snapshot.batteryTempCelsius <= hotBatteryCelsius
    val thermalOk = snapshot.thermal != ThermalState.THROTTLING
    return when {
        !batteryOk -> HealthBlockReason.LOW_BATTERY
        !temperatureOk -> HealthBlockReason.WARM
        !thermalOk -> HealthBlockReason.WARM
        snapshot.powerSaveOn -> HealthBlockReason.POWER_SAVE
        snapshot.interacting -> HealthBlockReason.INTERACTION
        else -> HealthBlockReason.NONE
    }
}

/**
 * Whether a deferrable WorkManager maintenance job should skip this run to let the phone cool.
 *
 * Thermal throttling is the one physical-stress signal a WorkManager `Constraints` block cannot
 * express (battery-not-low and connectivity it can), so the periodic maintenance workers re-check it
 * themselves at the head of `doWork` and stand down while the OS is actively shedding performance.
 * Kept pure so the rule is unit-testable without a device.
 */
fun standDownForThermal(thermal: ThermalState): Boolean = thermal == ThermalState.THROTTLING

/** The OS "battery low" floor; matches the workers' `setRequiresBatteryNotLow` behaviour. */
const val LOW_BATTERY_PERCENT = 15

/** Battery temperature above which heavy on-device work stands down to let the phone cool. */
const val HOT_BATTERY_CELSIUS = 42f

/** How often a parked background walk re-checks device health, so a resume (or a stop) takes effect
 *  within a second without a busy loop. */
const val HEALTH_PAUSE_POLL_MS = 1_000L

// Face indexing yields to an active touch but resumes quickly after it, so a brief tap does not stall
// the walk: heavy ML stands down only while a touch is fresh, then picks straight back up.
private const val INTERACTION_WINDOW_MS = 3_000L

/**
 * One place every background tier asks "is now a good time?", so a single component owns the device
 * conditions instead of each walker rolling its own partial check. Wraps the battery, thermal, power
 * saver and interaction signals into a live [snapshot] and a derived [HealthVerdict].
 *
 * Reads:
 *  - battery level / charging / temperature from the sticky `ACTION_BATTERY_CHANGED` broadcast,
 *  - thermal throttling from `PowerManager` (API 29+; treated as [ThermalState.UNKNOWN] below that),
 *  - the power saver from `PowerManager.isPowerSaveMode` and its change broadcast,
 *  - interaction from [markInteraction], which the activity calls on every touch; the flag clears
 *    after [INTERACTION_WINDOW_MS] of no touch,
 *  - reachability from [NetworkObserver].
 *
 * Process-lifetime: the receivers and the thermal listener are registered once and live for the app.
 */
@Singleton
class DeviceHealthPolicy @Inject constructor(
    @ApplicationContext context: Context,
    networkObserver: NetworkObserver,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager

    private val battery = MutableStateFlow(readStickyBattery(context))
    private val thermal = MutableStateFlow(readThermal())
    private val powerSave = MutableStateFlow(powerManager?.isPowerSaveMode == true)
    private val interacting = MutableStateFlow(false)

    private val lastInteractionMs = AtomicLong(0L)
    @Volatile private var idleJob: Job? = null

    /** Live device conditions, always readable synchronously via `.value`. */
    val snapshot: StateFlow<HealthSnapshot> =
        combine(battery, thermal, powerSave, interacting, networkObserver.isOnline) {
            b, t, ps, inter, online ->
            HealthSnapshot(b.percent, b.charging, b.tempCelsius, t, ps, inter, online)
        }.stateIn(
            scope,
            SharingStarted.Eagerly,
            HealthSnapshot(
                batteryPercent = battery.value.percent,
                charging = battery.value.charging,
                batteryTempCelsius = battery.value.tempCelsius,
                thermal = thermal.value,
                powerSaveOn = powerSave.value,
                interacting = false,
                online = networkObserver.isOnline.value,
            ),
        )

    init {
        val batteryReceiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                if (intent != null) battery.value = readBattery(intent)
            }
        }
        // ACTION_BATTERY_CHANGED comes from the system UID, so the receiver must be EXPORTED or the
        // broadcast is dropped silently (the receiver would look armed but never fire). Registration
        // is wrapped so a hostile OEM ROM refusing it leaves the signal at its fail-open default
        // rather than taking down app launch.
        runCatching {
            ContextCompat.registerReceiver(
                context,
                batteryReceiver,
                IntentFilter(Intent.ACTION_BATTERY_CHANGED),
                ContextCompat.RECEIVER_EXPORTED,
            )
        }

        val powerSaveReceiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                powerSave.value = powerManager?.isPowerSaveMode == true
            }
        }
        runCatching {
            ContextCompat.registerReceiver(
                context,
                powerSaveReceiver,
                IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED),
                ContextCompat.RECEIVER_EXPORTED,
            )
        }

        val pm = powerManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && pm != null) {
            runCatching {
                pm.addThermalStatusListener(ContextCompat.getMainExecutor(context)) { status ->
                    thermal.value = mapThermal(status)
                }
            }
        }
    }

    /** Snapshot the current verdict. */
    fun verdict(): HealthVerdict = evaluateDeviceHealth(snapshot.value)

    fun heavyMlAllowed(): Boolean = verdict().heavyMlAllowed

    fun backgroundWorkAllowed(): Boolean = verdict().backgroundWorkAllowed

    /**
     * True when the OS is actively throttling to cool the device. The periodic maintenance workers
     * skip a run on this; it reads the live thermal signal only, independent of the battery and
     * power-saver gates that a backup deliberately ignores.
     */
    fun thermallyThrottled(): Boolean = standDownForThermal(snapshot.value.thermal)

    /** Suspends until the device is in a state that permits a deferrable background walk. */
    suspend fun awaitBackgroundWorkAllowed() {
        snapshot.first { evaluateDeviceHealth(it).backgroundWorkAllowed }
    }

    /** Suspends until the device is in a state that permits heavy on-device model inference. */
    suspend fun awaitHeavyMlAllowed() {
        snapshot.first { evaluateDeviceHealth(it).heavyMlAllowed }
    }

    /**
     * Records that the user just touched the screen. The activity calls this from `dispatchTouchEvent`
     * on the main thread, so the transition guard needs no lock; the flag clears once no touch has
     * arrived for [INTERACTION_WINDOW_MS].
     */
    fun markInteraction() {
        lastInteractionMs.set(SystemClock.elapsedRealtime())
        if (!interacting.value) {
            interacting.value = true
            idleJob = scope.launch {
                while (true) {
                    val remaining = INTERACTION_WINDOW_MS - (SystemClock.elapsedRealtime() - lastInteractionMs.get())
                    if (remaining <= 0L) {
                        interacting.value = false
                        return@launch
                    }
                    delay(remaining)
                }
            }
        }
    }

    private fun readThermal(): ThermalState =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && powerManager != null) {
            mapThermal(powerManager.currentThermalStatus)
        } else {
            ThermalState.UNKNOWN
        }

    private data class BatteryReading(val percent: Int, val charging: Boolean, val tempCelsius: Float)

    private fun readStickyBattery(context: Context): BatteryReading {
        val sticky = runCatching {
            context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        }.getOrNull() ?: return BatteryReading(-1, charging = false, tempCelsius = Float.NaN)
        return readBattery(sticky)
    }

    private fun readBattery(intent: Intent): BatteryReading {
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        val percent = if (level < 0 || scale <= 0) -1 else (level * 100) / scale
        val charging = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
        val tempTenths = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
        val tempCelsius = if (tempTenths == Int.MIN_VALUE) Float.NaN else tempTenths / 10f
        return BatteryReading(percent, charging, tempCelsius)
    }

    private companion object {
        // PowerManager thermal statuses at or above SEVERE mean the OS is actively shedding
        // performance to cool down; that is the point on-device work should stand aside.
        fun mapThermal(status: Int): ThermalState =
            if (status >= PowerManager.THERMAL_STATUS_SEVERE) ThermalState.THROTTLING else ThermalState.NORMAL
    }
}
