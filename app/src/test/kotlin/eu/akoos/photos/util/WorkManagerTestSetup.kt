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

import android.content.Context
import android.content.Intent
import android.os.BatteryManager
import androidx.work.Configuration
import androidx.work.WorkManager

/**
 * Initialises WorkManager for a Robolectric test with synchronous executors.
 *
 * WorkManager reads the battery state from the sticky battery broadcast when work that requires a battery
 * that is not low is enqueued, and Robolectric has no such broadcast: the read comes back null and the
 * constraint tracker throws a NullPointerException that lands in whichever test runs next in the same JVM, a
 * failure of tests that have nothing to do with WorkManager. A sticky broadcast of a charged battery is sent
 * first, so the tracker has a state to read.
 */
object WorkManagerTestSetup {

    @Suppress("DEPRECATION")
    fun initialize(context: Context) {
        context.sendStickyBroadcast(
            Intent(Intent.ACTION_BATTERY_CHANGED)
                .putExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_CHARGING)
                .putExtra(BatteryManager.EXTRA_LEVEL, 100)
                .putExtra(BatteryManager.EXTRA_SCALE, 100),
        )
        runCatching {
            WorkManager.initialize(
                context,
                Configuration.Builder().setExecutor { it.run() }.setTaskExecutor { it.run() }.build(),
            )
        }
    }
}
