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

package eu.akoos.photos.data.hidden

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
 * Tells the user why hidden photos turned up in the gallery after a sign-out they did not ask for.
 *
 * The photos come back into a folder of their own, and without a word the folder would read as
 * photos that appeared from nowhere. Its own channel, so the notice can be silenced on its own.
 */
@Singleton
class VaultRescueNotifier @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    /** [zipName] is the zip in Downloads the photos went into, or null when they went to the gallery. */
    fun notifyMoved(zipName: String? = null) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        ensureNotificationChannel(
            context,
            id = CHANNEL_ID,
            name = context.getString(R.string.vault_rescue_channel_name),
            description = context.getString(R.string.vault_rescue_channel_desc),
            importance = NotificationManager.IMPORTANCE_DEFAULT,
            silent = false,
        )
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
        val text = if (zipName != null) {
            context.getString(R.string.vault_rescue_zip_text, zipName)
        } else {
            context.getString(R.string.vault_rescue_text, HiddenRescue.FOLDER)
        }
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.vault_rescue_title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        // A denied POST_NOTIFICATIONS grant makes the platform drop the post silently rather than
        // throw, so this only guards the rarer failures the manager itself can raise.
        runCatching { nm.notify(NotificationIds.VAULT_RESCUE, notification) }
    }

    private companion object {
        const val CHANNEL_ID = "vault_rescue"
        const val REQUEST_CODE = 9321
    }
}
