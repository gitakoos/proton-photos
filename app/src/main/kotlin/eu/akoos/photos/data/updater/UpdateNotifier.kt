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

package eu.akoos.photos.data.updater

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.akoos.photos.R
import eu.akoos.photos.data.notification.NotificationIds
import eu.akoos.photos.data.notification.ensureNotificationChannel
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Posts the "a newer version exists" notification the background check raises. Its own channel, so
 * the user can silence update news without touching backup or download notifications.
 *
 * Tapping it opens the app on its normal launch intent; the update prompt the orchestrator raises
 * from its resume check is what carries the download and install from there, so the notification
 * itself stays a pointer rather than a second control surface.
 */
@Singleton
class UpdateNotifier @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    fun notifyAvailable(versionName: String) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        ensureChannel()
        val launchIntent = context.packageManager
            .getLaunchIntentForPackage(context.packageName)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ?: return
        val contentIntent = PendingIntent.getActivity(
            context,
            REQUEST_CODE,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.update_available_title))
            .setContentText(context.getString(R.string.update_notification_text, versionName))
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        // A denied POST_NOTIFICATIONS grant makes the platform drop the post silently rather than
        // throw, so this only guards the rarer failures the manager itself can raise.
        runCatching { nm.notify(NOTIFICATION_ID, notification) }
    }

    /**
     * Takes the notice back down. The shade must not keep pointing at a version that is no longer
     * on offer, whether it just finished installing or the release it named has been withdrawn.
     */
    fun cancel() {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        runCatching { nm.cancel(NOTIFICATION_ID) }
    }

    private fun ensureChannel() {
        ensureNotificationChannel(
            context,
            id = CHANNEL_ID,
            name = context.getString(R.string.update_channel_name),
            description = context.getString(R.string.update_channel_desc),
            importance = NotificationManager.IMPORTANCE_DEFAULT,
            silent = false,
        )
    }

    companion object {
        const val CHANNEL_ID = "update_available"

        const val NOTIFICATION_ID = NotificationIds.UPDATE_AVAILABLE

        private const val REQUEST_CODE = 9320
    }
}
