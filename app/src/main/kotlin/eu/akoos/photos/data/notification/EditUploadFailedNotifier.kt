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

package eu.akoos.photos.data.notification

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.akoos.photos.R
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Tells the user that the edit of a photo or video they only have in Drive was not uploaded.
 *
 * The editor reports such a save as done the moment the upload starts, so a failure arrives long
 * after the screen is gone and has nowhere else to be seen. Its own channel, so the notice can be
 * silenced on its own.
 */
@Singleton
class EditUploadFailedNotifier @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    /** [keptOnDevice] is whether the edited copy reached the gallery, which decides what the user is told to do. */
    fun notifyFailed(keptOnDevice: Boolean) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        ensureNotificationChannel(
            context,
            id = CHANNEL_ID,
            name = context.getString(R.string.edit_upload_failed_channel_name),
            description = context.getString(R.string.edit_upload_failed_channel_desc),
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
        val text = context.getString(
            if (keptOnDevice) R.string.edit_upload_failed_text_kept else R.string.edit_upload_failed_text_lost,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.edit_upload_failed_title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        // A denied POST_NOTIFICATIONS grant makes the platform drop the post silently rather than
        // throw, so this only guards the rarer failures the manager itself can raise.
        runCatching { nm.notify(NotificationIds.EDIT_UPLOAD_FAILED, notification) }
    }

    private companion object {
        const val CHANNEL_ID = "edit_upload_failed"
        const val REQUEST_CODE = 9322
    }
}
