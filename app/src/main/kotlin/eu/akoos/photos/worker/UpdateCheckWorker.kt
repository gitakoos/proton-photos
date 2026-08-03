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

package eu.akoos.photos.worker

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import eu.akoos.photos.data.preferences.SettingsKeys
import eu.akoos.photos.data.preferences.settingsDataStore
import eu.akoos.photos.data.updater.DownloadProgress
import eu.akoos.photos.data.updater.StagedUpdateStore
import eu.akoos.photos.data.updater.UpdateDownloader
import eu.akoos.photos.data.updater.UpdateInstaller
import eu.akoos.photos.data.updater.UpdateNotifier
import eu.akoos.photos.data.updater.shouldNotifyForUpdate
import eu.akoos.photos.domain.repository.NewsRepository
import eu.akoos.photos.domain.repository.UpdateCheckerRepository
import eu.akoos.photos.domain.repository.UpdateStatus
import eu.akoos.photos.util.NetworkObserver
import kotlinx.coroutines.flow.first
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Periodic release check that runs with the app closed. The in-app check only fires from the
 * gallery's resume hook, so an app nobody opens for a week learns about a new version a week late.
 *
 * Constraints match [CachePruneWorker]: [NetworkType.CONNECTED] because the check is a network
 * call, and battery-not-low so version news never competes with the user's remaining charge.
 *
 * Two separate outcomes on a newer version. The notification always posts, because that is the
 * whole point of checking while closed. The pre-download only runs on an unmetered link: an APK is
 * tens of megabytes and nobody asked for it to arrive over cellular data. The user still gets the
 * ordinary in-app download on any connection when they act on the notification.
 */
@HiltWorker
class UpdateCheckWorker @AssistedInject constructor(
    @Assisted private val context: Context,
    @Assisted params: WorkerParameters,
    private val repository: UpdateCheckerRepository,
    private val downloader: UpdateDownloader,
    private val installer: UpdateInstaller,
    private val notifier: UpdateNotifier,
    private val stagedUpdates: StagedUpdateStore,
    private val networkObserver: NetworkObserver,
    private val newsRepository: NewsRepository,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        // Pull the news feed on the same wake-up, so the unread dot is ready the next time the app
        // opens rather than waiting for that open to fetch it. Self-guarded by the news on/off switch.
        runCatching { newsRepository.refresh() }
        runCatching { check() }
        // Always success: a failed check is nothing to retry against a battery, and the next
        // period comes round on its own.
        return Result.success()
    }

    private suspend fun check() {
        val prefs = context.settingsDataStore.data.first()
        // Belt to [reconcile]'s braces: an enqueued request outlives the toggle flip that
        // cancelled it if the cancellation lost a race with a run already dispatched.
        if (prefs[SettingsKeys.UPDATE_BACKGROUND_CHECK] == false) return

        // The cached entry point, so a check the user just triggered by opening the app is reused
        // rather than repeated.
        val available = repository.checkForUpdateCached() as? UpdateStatus.Available
        if (available == null) {
            settleUpToDate()
            return
        }

        if (shouldNotifyForUpdate(available.versionName, prefs[SettingsKeys.UPDATE_NOTIFIED_VERSION])) {
            notifier.notifyAvailable(available.versionName)
            context.settingsDataStore.edit { p ->
                p[SettingsKeys.UPDATE_NOTIFIED_VERSION] = available.versionName
            }
        }

        // claimFor also sweeps an archive left over from a superseded version.
        val alreadyStaged = stagedUpdates.claimFor(available.versionName) != null
        if (!alreadyStaged && networkObserver.isUnmetered.value) {
            stage(available)
        }
    }

    /**
     * Clears the notification and staging markers, but only once the repository confirms nothing is
     * on offer. The throttled branch of the cached check also reads as up to date without having
     * asked the network, and clearing on that would let the same version notify again next period.
     */
    private suspend fun settleUpToDate() {
        if (repository.knownAvailableVersion() != null) return
        stagedUpdates.discard()
        // The staged archive is gone and the marker is cleared, so a notice still in the shade
        // would offer a download of a version this run just established is not on offer.
        notifier.cancel()
        runCatching {
            context.settingsDataStore.edit { p -> p.remove(SettingsKeys.UPDATE_NOTIFIED_VERSION) }
        }
    }

    /**
     * Downloads the APK and records it for the next launch. The archive is only recorded once it is
     * signed by this app's own certificate, so the prompt never offers an install the installer
     * would refuse a moment later.
     */
    private suspend fun stage(available: UpdateStatus.Available) {
        var downloaded: File? = null
        downloader.download(available.apkUrl, available.apkAssetName).collect { progress ->
            if (progress is DownloadProgress.Complete) downloaded = progress.file
        }
        val file = downloaded ?: return
        if (!installer.verifyApkSignature(file)) {
            runCatching { file.delete() }
            return
        }
        stagedUpdates.record(available.versionName, file)
    }

    companion object {
        const val UNIQUE_NAME = "update_check_periodic"
        private const val INTERVAL_HOURS = 6L

        /**
         * Brings the periodic work in line with [SettingsKeys.UPDATE_BACKGROUND_CHECK]. Turning the
         * setting off cancels the request rather than leaving it enqueued to wake up and no-op, so
         * a user who opts out stops paying for the wake-ups too. Absent reads as on.
         */
        suspend fun reconcile(context: Context) {
            val enabled = runCatching {
                context.settingsDataStore.data.first()[SettingsKeys.UPDATE_BACKGROUND_CHECK] != false
            }.getOrDefault(true)
            val workManager = WorkManager.getInstance(context)
            if (enabled) schedule(workManager) else workManager.cancelUniqueWork(UNIQUE_NAME)
        }

        private fun schedule(workManager: WorkManager) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .setRequiresBatteryNotLow(true)
                .build()
            val request = PeriodicWorkRequestBuilder<UpdateCheckWorker>(INTERVAL_HOURS, TimeUnit.HOURS)
                .setConstraints(constraints)
                .build()
            // UPDATE so a later interval or constraint change replaces the registered request
            // instead of stacking a second one.
            workManager.enqueueUniquePeriodicWork(
                UNIQUE_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request,
            )
        }
    }
}
