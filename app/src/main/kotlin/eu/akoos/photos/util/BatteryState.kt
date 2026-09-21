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
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Runtime analogue of the workers' `setRequiresBatteryNotLow(true)`, for the background work that
 * runs on a plain coroutine scope and so inherits no WorkManager constraint: reads the sticky
 * ACTION_BATTERY_CHANGED broadcast and compares to the OS's 15% "battery low" floor. Returns false
 * (don't block) when the level can't be read, so a missing broadcast never suppresses work.
 */
fun Context.isBatteryLow(): Boolean {
    val status = runCatching {
        registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    }.getOrNull() ?: return false
    val level = status.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
    val scale = status.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
    if (level < 0 || scale <= 0) return false
    return level.toFloat() / scale.toFloat() <= 0.15f
}

/**
 * [isBatteryLow] as a live flow, for a screen that explains why backup is waiting: the answer stops
 * being true the moment the phone goes on a charger, and a note that lingers after that would be
 * the same kind of stale claim it exists to replace.
 *
 * Emits the current value immediately (the sticky broadcast answers without waiting for a change),
 * then on every battery change, deduplicated to the low/not-low boolean so the frequent per-percent
 * and per-voltage broadcasts do not repaint anything.
 *
 * EXPORTED is required: ACTION_BATTERY_CHANGED comes from the system UID, and NOT_EXPORTED drops it
 * silently, which leaves the receiver looking armed while it never fires.
 */
fun Context.batteryLowFlow(): Flow<Boolean> = callbackFlow {
    val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            trySend(isBatteryLow())
        }
    }
    val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
    // The sticky broadcast makes this registration itself deliver the current state, so no separate
    // priming send is needed.
    runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(receiver, filter)
        }
    }.onFailure {
        // Without the broadcast there is nothing to report, and claiming "waiting for battery" on a
        // guess would be worse than staying quiet.
        trySend(false)
    }
    awaitClose { runCatching { unregisterReceiver(receiver) } }
}.distinctUntilChanged()
