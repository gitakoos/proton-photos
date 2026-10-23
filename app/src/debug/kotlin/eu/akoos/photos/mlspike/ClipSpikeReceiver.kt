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

package eu.akoos.photos.mlspike

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * SPIKE-ONLY (debug source set). Fires [ClipSpikeRunner] off the main thread.
 * Trigger:  adb shell am broadcast -a eu.akoos.photos.CLIP_SPIKE -p <applicationId>
 */
class ClipSpikeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Log.i("ClipSpike", "received ${intent.action}; running")
        val appContext = context.applicationContext
        val pending = goAsync()
        Thread {
            try {
                ClipSpikeRunner.run(appContext)
            } catch (t: Throwable) {
                Log.e("ClipSpike", "spike crashed: ${t.message}", t)
            } finally {
                pending.finish()
            }
        }.start()
    }
}
