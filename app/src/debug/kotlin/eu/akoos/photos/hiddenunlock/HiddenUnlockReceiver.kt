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

package eu.akoos.photos.hiddenunlock

import android.app.Activity
import android.app.Application
import android.content.BroadcastReceiver
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.biometric.DebugPromptCompleter
import androidx.fragment.app.FragmentActivity
import eu.akoos.photos.R
import java.util.Collections
import java.util.WeakHashMap

/**
 * Debug build only. Opens the Hidden screen for an automated test: the Hidden prompt that is waiting is
 * finished as a successful authentication, so the screen's own success callback unlocks it. Stored lock
 * settings are not read or changed.
 *
 * Trigger: adb shell am broadcast -a eu.akoos.photos.DEBUG_UNLOCK_HIDDEN -p eu.akoos.photos
 * Result 1 = unlocked, 0 = no Hidden prompt was waiting (retry once the prompt shows).
 */
class HiddenUnlockReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val unlocked = HiddenUnlockActivities.live().any {
            DebugPromptCompleter.complete(it, it.getString(R.string.hidden_photos_title))
        }
        Log.i(TAG, if (unlocked) "Hidden prompt completed" else "no Hidden prompt waiting")
        if (isOrderedBroadcast) {
            resultCode = if (unlocked) 1 else 0
            resultData = if (unlocked) "unlocked" else "no Hidden prompt waiting"
        }
    }

    private companion object {
        const val TAG = "HiddenUnlock"
    }
}

/** Keeps a weak list of the app's live activities so [HiddenUnlockReceiver] can reach the one with the prompt. */
internal object HiddenUnlockActivities : Application.ActivityLifecycleCallbacks {
    private val activities = Collections.synchronizedSet(Collections.newSetFromMap(WeakHashMap<Activity, Boolean>()))

    fun live(): List<FragmentActivity> =
        synchronized(activities) { activities.filterIsInstance<FragmentActivity>() }.filterNot { it.isFinishing }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) { activities += activity }
    override fun onActivityDestroyed(activity: Activity) { activities -= activity }
    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityResumed(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
}

/** Registers [HiddenUnlockActivities] at process start, before any activity exists. Serves no data. */
class HiddenUnlockTrackerProvider : ContentProvider() {
    override fun onCreate(): Boolean {
        (context?.applicationContext as? Application)?.registerActivityLifecycleCallbacks(HiddenUnlockActivities)
        return true
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
