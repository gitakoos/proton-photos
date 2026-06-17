/*
 * Photos for Proton
 * Copyright (C) 2026 Akoos <https://akoos.eu>
 *
 * Source:  https://github.com/gitakoos/proton-photos
 * Website: https://photos.akoos.eu
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

package eu.akoos.photos.domain.usecase

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.datastore.preferences.core.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.akoos.photos.R
import eu.akoos.photos.data.preferences.SettingsKeys
import eu.akoos.photos.data.preferences.settingsDataStore
import eu.akoos.photos.domain.entity.SyncStatus
import eu.akoos.photos.domain.repository.SyncStateRepository
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reconciles the delete-after-backup pending queue against MediaStore reality and
 * refreshes (or cancels) the system consent notification accordingly. The two
 * pieces always travel together — the notification's `createDeleteRequest`
 * PendingIntent throws if any of its URIs is stale, so we prune first and then
 * post the notification with whatever's left alive.
 *
 * Idempotent and cheap (one `_ID` query per queued URI). Called from two places:
 *  - [UploadPendingUseCase] at the end of every sync run, to surface the
 *    notification immediately after a successful upload batch.
 *  - `MainActivity.onResume`, so files the user deleted externally while we
 *    were not running still get reconciled the moment they open the app —
 *    even without network connectivity to fire the worker's content URI
 *    trigger.
 */
@Singleton
class PendingDeleteNotificationUseCase @Inject constructor(
    @ApplicationContext private val context: Context,
    private val syncStateRepo: SyncStateRepository,
) {
    suspend operator fun invoke() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val prefs = context.settingsDataStore.data.first()
        // User opted out of the delete-after-backup reminder — don't post or prune.
        if (prefs[SettingsKeys.NOTIFY_DELETE_REMINDER] == false) return
        val pendingRaw = prefs[SettingsKeys.PENDING_DELETE_URIS] ?: emptySet()
        if (pendingRaw.isEmpty()) return

        // Probe each URI with a cheap _ID query — anything MediaProvider doesn't
        // resolve is treated as gone (file manager delete, OS trash flush, user
        // accepted the previous consent dialog). Stale entries get their SyncState
        // collapsed to CLOUD_ONLY so the gallery stops painting them as locally
        // present, and they're removed from the queue so the next createDeleteRequest
        // doesn't trip over them.
        val (alive, stale) = pendingRaw.partition { uriStr ->
            runCatching {
                val uri = Uri.parse(uriStr)
                context.contentResolver.query(
                    uri,
                    arrayOf(MediaStore.MediaColumns._ID),
                    null, null, null,
                )?.use { c -> c.moveToFirst() } ?: false
            }.getOrDefault(false)
        }
        if (stale.isNotEmpty()) {
            Log.d(TAG, "Pruning ${stale.size} stale URI(s) from delete queue")
            context.settingsDataStore.edit { p ->
                p[SettingsKeys.PENDING_DELETE_URIS] = alive.toSet()
            }
            stale.forEach { uri ->
                runCatching { syncStateRepo.updateStatusAndDeleteLocal(uri, SyncStatus.CLOUD_ONLY) }
            }
        }

        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        if (alive.isEmpty()) {
            // Nothing left to ask about — dismiss any lingering notification so the
            // user doesn't see a "X photos ready to remove" badge after they already
            // got rid of everything externally.
            nm.cancel(DELETE_NOTIFICATION_ID)
            return
        }
        val uris = alive.mapNotNull { runCatching { Uri.parse(it) }.getOrNull() }
        if (uris.isEmpty()) {
            nm.cancel(DELETE_NOTIFICATION_ID)
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && nm.getNotificationChannel(DELETE_CHANNEL_ID) == null) {
            val channel = NotificationChannel(
                DELETE_CHANNEL_ID,
                context.getString(R.string.delete_consent_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = context.getString(R.string.delete_consent_channel_desc)
                setShowBadge(false)
                setSound(null, null)
                enableVibration(false)
            }
            nm.createNotificationChannel(channel)
        }
        val deleteRequestPi = runCatching {
            MediaStore.createDeleteRequest(context.contentResolver, uris)
        }.getOrElse {
            Log.w(TAG, "createDeleteRequest failed after prune: ${it.message}")
            return
        }
        val notif = NotificationCompat.Builder(context, DELETE_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.delete_consent_title, uris.size))
            .setContentText(context.getString(R.string.delete_consent_text))
            .setContentIntent(deleteRequestPi)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        nm.notify(DELETE_NOTIFICATION_ID, notif)
        Log.d(TAG, "Delete consent notification refreshed for ${uris.size} URI(s)")
    }

    companion object {
        const val DELETE_CHANNEL_ID = "delete_consent"
        // Distinct from SyncWorker.NOTIFICATION_ID (4243), AlbumDownloadWorker
        // (4242), and BackgroundSyncService (4244). A unique ID per channel is
        // required because a single foreground service update would otherwise
        // clobber the delete consent notification post.
        const val DELETE_NOTIFICATION_ID = 4245
        private const val TAG = "PendingDeleteNotif"
    }
}
