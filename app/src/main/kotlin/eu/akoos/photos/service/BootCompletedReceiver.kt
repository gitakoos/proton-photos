package eu.akoos.photos.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.work.WorkManager
import dagger.hilt.android.AndroidEntryPoint
import eu.akoos.photos.data.preferences.SettingsKeys
import eu.akoos.photos.data.preferences.settingsDataStore
import eu.akoos.photos.worker.SyncWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Re-arms the three sync triggers on device boot so users who reboot and never re-open
 * the app still get auto-upload within seconds of their next camera capture:
 *
 *  1. [SyncWorker.scheduleContentObserver] — OS-level MediaStore content-URI trigger
 *     (a OneTime work item that fires ONCE then disappears, so it must be re-armed
 *     after reboot).
 *  2. [BackgroundSyncService] — the persistent foreground service that holds the
 *     in-process MediaStore observer alive on Samsung One UI.
 *  3. [SyncWorker.schedule] — periodic 15-min safety net. WorkManager persists this
 *     across reboots itself, but re-issuing with [ExistingPeriodicWorkPolicy.UPDATE]
 *     is a cheap belt-and-braces.
 *
 * Skips everything when AUTO_SYNC is false — the user has opted out of continuous
 * backup and we shouldn't ressurect the BG notification behind their back.
 *
 * No-ops for non-`BOOT_COMPLETED` intents. Lifetime is the brief broadcast window
 * (~10s), so we use a SupervisorJob+IO scope and don't await — DataStore read +
 * service start typically settle in under 100ms.
 */
class BootCompletedReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_LOCKED_BOOT_COMPLETED) return

        // Application context — the receiver's own context is short-lived; queuing work
        // on it would race against onReceive returning.
        val appContext = context.applicationContext

        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val prefs = appContext.settingsDataStore.data.first()
                val autoSync = prefs[SettingsKeys.AUTO_SYNC] != false
                if (!autoSync) {
                    Log.d(TAG, "boot: autoSync off, skipping re-arm")
                    return@launch
                }
                val wifiOnly = prefs[SettingsKeys.SYNC_WIFI_ONLY] != false

                SyncWorker.schedule(
                    WorkManager.getInstance(appContext),
                    wifiOnly,
                    SyncWorker.MIN_INTERVAL_MINUTES,
                )
                SyncWorker.scheduleContentObserver(appContext, wifiOnly)
                BackgroundSyncService.start(appContext)
                Log.d(TAG, "boot: re-armed sync triggers (wifiOnly=$wifiOnly)")
            } catch (t: Throwable) {
                Log.w(TAG, "boot re-arm failed: ${t.message}")
            }
        }
    }

    companion object {
        private const val TAG = "boot_receiver"
    }
}
