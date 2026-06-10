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

package eu.akoos.photos.presentation.updater

import eu.akoos.photos.data.updater.DownloadProgress
import eu.akoos.photos.data.updater.UpdateDownloader
import eu.akoos.photos.data.updater.UpdateInstaller
import eu.akoos.photos.domain.repository.UpdateCheckerRepository
import eu.akoos.photos.domain.repository.UpdateStatus
import eu.akoos.photos.presentation.common.UpdatePromptState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The single piece of glue between the repository (does an update exist?), the downloader
 * (fetch the APK), the installer (hand it to the OS), and the Compose dialog state. Lives
 * as a @Singleton so a recreated MainActivity (config change, theme swap) still observes
 * the same in-flight download.
 *
 * Threading model: every public method either takes a [CoroutineScope] explicitly or is
 * suspend. The orchestrator never spins up its own scope — that way, when the host
 * Activity dies, its `lifecycleScope` cancellation correctly tears down anything we
 * started. The download Flow is collected on a scope the caller controls, not a leaked
 * GlobalScope.
 */
@Singleton
class UpdateOrchestrator @Inject constructor(
    private val repository: UpdateCheckerRepository,
    private val downloader: UpdateDownloader,
    @Suppress("unused") private val installer: UpdateInstaller,
) {

    private val _state = MutableStateFlow<UpdatePromptState?>(null)
    /** Hot state surface for the Compose layer. null = no dialog visible. */
    val state: StateFlow<UpdatePromptState?> = _state.asStateFlow()

    /**
     * Held between phases so we can recover the install URL after the user grants
     * "Install unknown apps", and so [pendingInstallFile] can resolve the file path
     * for the Activity without re-deriving it from state.
     */
    private var pendingAvailable: UpdateStatus.Available? = null
    private var pendingFile: File? = null

    /** Tracks the in-flight download so a second confirmUpdate tap is a no-op. */
    private var downloadJob: Job? = null

    /**
     * Silently checks for an update if the 24h cache hasn't elapsed. On hit, swaps the
     * dialog state to [UpdatePromptState.Available]. Errors stay silent — the silent
     * check fires from onResume on every foreground entry and shouldn't yell about a
     * one-off network flake.
     */
    suspend fun runSilentCheck() {
        // If a download / install flow is already mid-flight, don't disturb its state.
        if (_state.value is UpdatePromptState.Downloading ||
            _state.value is UpdatePromptState.InstallReady
        ) return

        when (val status = repository.checkForUpdateCached()) {
            is UpdateStatus.Available -> showAvailable(status)
            else -> Unit
        }
    }

    /**
     * Force-checks regardless of the 24h cache. Used by the manual "Check for updates"
     * Settings row. Returns a discriminated outcome so the caller can show a snackbar
     * for the up-to-date / error branches (the available branch surfaces via [state]).
     */
    suspend fun runManualCheck(): ManualCheckOutcome {
        if (_state.value is UpdatePromptState.Downloading ||
            _state.value is UpdatePromptState.InstallReady
        ) {
            return ManualCheckOutcome.InProgress
        }
        return try {
            when (val status = repository.checkForUpdateForced()) {
                is UpdateStatus.Available -> {
                    showAvailable(status)
                    ManualCheckOutcome.NewVersionShown
                }
                is UpdateStatus.DismissedVersion,
                is UpdateStatus.UpToDate -> ManualCheckOutcome.UpToDate
            }
        } catch (t: Throwable) {
            // The repository contract is non-throwing, but defend against future changes.
            ManualCheckOutcome.Failed(UpdatePromptState.ErrorKind.NETWORK)
        }
    }

    /**
     * Begins downloading the pending APK. Transitions the dialog through
     * Available → Downloading(progress) → InstallReady, or to Error on failure.
     * Caller passes the scope so cancellation follows the host Activity's lifecycle
     * and not some internal long-lived scope.
     */
    fun confirmUpdate(scope: CoroutineScope) {
        val available = pendingAvailable ?: return
        if (downloadJob?.isActive == true) return

        _state.value = UpdatePromptState.Downloading(
            versionName = available.versionName,
            progressPercent = 0,
        )

        downloadJob = scope.launch {
            downloader.download(available.apkUrl, available.apkAssetName).collect { progress ->
                when (progress) {
                    is DownloadProgress.Downloading -> {
                        _state.value = UpdatePromptState.Downloading(
                            versionName = available.versionName,
                            progressPercent = progress.percent,
                        )
                    }
                    is DownloadProgress.Complete -> {
                        pendingFile = progress.file
                        _state.value = UpdatePromptState.InstallReady(available.versionName)
                    }
                    is DownloadProgress.Failed -> {
                        _state.value = UpdatePromptState.Error(
                            versionName = available.versionName,
                            errorKind = UpdatePromptState.ErrorKind.NETWORK,
                        )
                    }
                }
            }
        }
    }

    /**
     * Records the dismissal for the version the user just declined (so the silent check
     * doesn't re-pester them until a newer release ships) and clears the dialog. Caller
     * passes the scope so the DataStore write is bound to the host's lifecycle.
     */
    fun dismiss(scope: CoroutineScope) {
        val version = pendingAvailable?.versionName
        pendingAvailable = null
        pendingFile = null
        downloadJob?.cancel()
        downloadJob = null
        _state.value = null
        if (version != null) {
            scope.launch {
                runCatching { repository.dismissVersion(version) }
            }
        }
    }

    /**
     * The downloaded APK file (if the state is currently [UpdatePromptState.InstallReady]).
     * Returns null if no install is staged — the Activity uses this to decide whether to
     * build the install intent or fall through.
     */
    fun pendingInstallFile(): File? = pendingFile

    /**
     * Surfaces the dialog in its Available state. Bytes-to-MB rounds UP so a 23.4 MB
     * payload reads as "24 MB" instead of "23" (users compare against their cellular
     * data plan — overshooting is friendlier than undershooting).
     */
    private fun showAvailable(status: UpdateStatus.Available) {
        pendingAvailable = status
        val sizeMb = ((status.apkSizeBytes + 1024L * 1024L - 1L) / (1024L * 1024L))
            .toInt()
            .coerceAtLeast(0)
        _state.value = UpdatePromptState.Available(
            versionName = status.versionName,
            sizeMb = sizeMb,
        )
    }

    sealed class ManualCheckOutcome {
        data object UpToDate : ManualCheckOutcome()
        data object NewVersionShown : ManualCheckOutcome()
        data class Failed(val errorKind: UpdatePromptState.ErrorKind) : ManualCheckOutcome()
        data object InProgress : ManualCheckOutcome()
    }
}
