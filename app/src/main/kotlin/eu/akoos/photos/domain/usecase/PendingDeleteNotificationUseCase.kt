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
 * pieces always travel together — the notification's `createTrashRequest`
 * PendingIntent throws if any of its URIs is stale, so we prune first and then
 * post the notification with whatever's left alive.
 *
 * Idempotent and cheap (one row probe per queued URI). Called from two places:
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

        // Probe each URI for a row and its trashed flag, and let
        // [pendingDeleteEntryIsStale] rule on the pair. Stale entries get their
        // SyncState collapsed to CLOUD_ONLY so the gallery stops painting them as
        // locally present, and they're removed from the queue so the next
        // createTrashRequest doesn't trip over them.
        val (aliveAll, stale) = pendingRaw.partition { uriStr ->
            !runCatching {
                val uri = Uri.parse(uriStr)
                context.contentResolver.query(
                    uri,
                    arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.IS_TRASHED),
                    null, null, null,
                )?.use { c ->
                    if (!c.moveToFirst()) {
                        pendingDeleteEntryIsStale(rowExists = false, isTrashed = false)
                    } else {
                        val col = c.getColumnIndex(MediaStore.MediaColumns.IS_TRASHED)
                        pendingDeleteEntryIsStale(
                            rowExists = true,
                            isTrashed = col >= 0 && c.getInt(col) != 0,
                        )
                    }
                } ?: pendingDeleteEntryIsStale(rowExists = false, isTrashed = false)
            }.getOrDefault(true)
        }
        // A queue that outgrows the cap is one the user keeps declining; drop the
        // longest-waiting entries so it can't grow without bound. Those files stay on
        // the device with a SYNCED row (free-up-space can still reclaim them), so
        // unlike a stale entry they must NOT be collapsed to CLOUD_ONLY.
        val alive = trimPendingDeleteQueue(aliveAll, SettingsKeys.PENDING_DELETE_URIS_MAX)
        val dropped = aliveAll.size - alive.size
        if (dropped > 0) {
            Log.w(TAG, "Delete queue over ${SettingsKeys.PENDING_DELETE_URIS_MAX} — dropped $dropped longest-waiting URI(s)")
        }
        if (stale.isNotEmpty() || dropped > 0) {
            context.settingsDataStore.edit { p ->
                p[SettingsKeys.PENDING_DELETE_URIS] = alive.toSet()
            }
        }
        if (stale.isNotEmpty()) {
            Log.d(TAG, "Pruning ${stale.size} stale URI(s) from delete queue")
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
        val trashRequestPi = runCatching {
            MediaStore.createTrashRequest(context.contentResolver, uris, true)
        }.getOrElse {
            Log.w(TAG, "createTrashRequest failed after prune: ${it.message}")
            return
        }
        val notif = NotificationCompat.Builder(context, DELETE_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.delete_consent_title, uris.size))
            .setContentText(context.getString(R.string.delete_consent_text))
            .setContentIntent(trashRequestPi)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        nm.notify(DELETE_NOTIFICATION_ID, notif)
        Log.d(TAG, "Delete consent notification refreshed for ${uris.size} URI(s)")
    }

    companion object {
        /**
         * Whether a queued delete-after-backup URI is done with and can leave the queue. Pure and
         * side-effect-free so it can be pinned by a plain JVM test.
         *
         * Tapping the consent notification hands the user straight to the OS trash dialog, which
         * reports nothing back to the app, so this probe is the only thing that ever empties the
         * queue: an entry it keeps calling alive is re-posted forever and grows the set without
         * bound. An entry is therefore done when its row is gone ([rowExists] false — deleted
         * outright, or withheld by MediaStore because it is trashed) OR when the row still reads
         * back and is marked [isTrashed]. Both arms are deliberate: whether MediaProvider hands a
         * trashed row back to a per-item query turns on the caller owning the file, and the camera
         * owns most of these, so neither answer is safe to build on. Below R nothing is ever
         * trashed, which leaves [isTrashed] false and the ruling exactly as it is on any version.
         */
        fun pendingDeleteEntryIsStale(rowExists: Boolean, isTrashed: Boolean): Boolean =
            !rowExists || isTrashed

        /**
         * [alive] trimmed to at most [max] entries, dropping the longest-waiting ones. Pure and
         * side-effect-free so the choice can be pinned by a plain JVM test.
         *
         * A DataStore string set carries no insertion order, so "oldest" cannot be read back off
         * the queue itself. The MediaStore row id trailing each URI is the closest recoverable
         * stand-in: the provider hands ids out in ascending order as it records files, so the
         * lowest ids are the entries that have waited longest. It is an approximation — a rescan
         * renumbers rows, and a deleted top row lets SQLite reuse its id — but it is a stable total
         * order, so every pass keeps the same survivors instead of dropping a different arbitrary
         * slice each time. An entry with no numeric id sorts oldest: it is not an item URI the
         * trash request could act on anyway.
         */
        fun trimPendingDeleteQueue(alive: List<String>, max: Int): List<String> =
            if (alive.size <= max) alive
            else alive.sortedByDescending { mediaStoreRowId(it) }.take(max)

        /** The trailing MediaStore row id of a queued URI, or -1 when it carries none. */
        private fun mediaStoreRowId(uri: String): Long =
            uri.substringAfterLast('/').toLongOrNull() ?: -1L

        const val DELETE_CHANNEL_ID = "delete_consent"
        // Distinct from SyncWorker.NOTIFICATION_ID (4243), AlbumDownloadWorker
        // (4242), and BackgroundSyncService (4244). A unique ID per channel is
        // required because a single foreground service update would otherwise
        // clobber the delete consent notification post.
        const val DELETE_NOTIFICATION_ID = 4245
        private const val TAG = "PendingDeleteNotif"
    }
}
