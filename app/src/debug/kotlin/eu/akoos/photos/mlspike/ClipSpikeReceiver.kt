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
