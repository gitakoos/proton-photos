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

package eu.akoos.photos.presentation.updater

import eu.akoos.photos.data.updater.DownloadProgress
import eu.akoos.photos.data.updater.InstallOutcome
import eu.akoos.photos.data.updater.StagedUpdateStore
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
    private val installer: UpdateInstaller,
    private val stagedUpdates: StagedUpdateStore,
) {

    private val _state = MutableStateFlow<UpdatePromptState?>(null)
    /** Hot state surface for the Compose layer. null = no dialog visible. */
    val state: StateFlow<UpdatePromptState?> = _state.asStateFlow()

    /**
     * Persistent "an update exists" signal driving the avatar update dot. TRUE whenever a check
     * has found an available update, independent of the dialog. It survives dismissing the dialog
     * and (via the repository's persisted marker, re-hydrated in [runSilentCheck]) an app relaunch;
     * it only clears when a check confirms the app is up to date.
     */
    private val _updateAvailable = MutableStateFlow(false)
    val updateAvailable: StateFlow<Boolean> = _updateAvailable.asStateFlow()

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
     * Fires from onResume on every foreground entry. Three responsibilities:
     *
     *  1. Re-light the persistent update dot from the repository's saved marker so a
     *     previously-found update survives a relaunch, WITHOUT opening the dialog during the check.
     *  2. Run the throttled check. Only when the throttle has elapsed does this hit the network;
     *     a fresh Available result re-nags via the dialog, a fresh UpToDate clears the dot. A
     *     throttled call returns UpToDate but leaves the saved marker alone, so the dot (re-hydrated
     *     from the marker below) stays lit.
     *  3. Adopt an APK the background check already downloaded, so the prompt opens on its
     *     install-ready step instead of asking for a download that has already happened.
     *
     * Errors stay silent, a one-off network flake at foreground shouldn't yell at the user.
     */
    suspend fun runSilentCheck() {
        // If a download / install flow is already mid-flight, don't disturb its state.
        if (_state.value is UpdatePromptState.Downloading ||
            _state.value is UpdatePromptState.InstallReady
        ) return

        // Dot on immediately if a prior check left an update pending, dialog stays closed.
        _updateAvailable.value = repository.knownAvailableVersion() != null

        when (val status = repository.checkForUpdateCached()) {
            is UpdateStatus.Available -> showAvailable(status)
            is UpdateStatus.UpToDate -> Unit
        }
        // The cached check updates the persisted marker only on a fresh network fetch (Available
        // sets it, UpToDate clears it); a throttled call leaves it. Re-reading it here reconciles
        // the dot in all three cases without the dialog being touched by the throttled branch.
        val known = repository.knownAvailableVersion()
        _updateAvailable.value = known != null
        // Covers the throttled branch too, which never reaches showAvailable: the marker names the
        // version, and a background download may already have the APK for it.
        adoptStagedUpdate(known)
    }

    /**
     * Moves straight to [UpdatePromptState.InstallReady] when the background check has already
     * fetched the APK for [versionName], skipping a download the user would otherwise pay for
     * twice. The archive is re-verified here rather than trusted from the record: it was written
     * by an earlier process and has sat in a cache directory since.
     *
     * Passing null (the app is up to date) clears the record and the bytes behind it, which is what
     * removes the archive after a successful install.
     */
    private suspend fun adoptStagedUpdate(versionName: String?) {
        if (_state.value is UpdatePromptState.Downloading ||
            _state.value is UpdatePromptState.InstallReady
        ) return
        val file = stagedUpdates.claimFor(versionName)
        if (file == null || versionName == null) return
        if (!installer.verifyApkSignature(file)) {
            stagedUpdates.discard()
            return
        }
        pendingFile = file
        _state.value = UpdatePromptState.InstallReady(versionName)
    }

    /**
     * Force-checks regardless of the 4h cache. Used by the manual "Check for updates"
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
                is UpdateStatus.UpToDate -> {
                    // A forced check is authoritative: no update, so drop the dot too.
                    _updateAvailable.value = false
                    ManualCheckOutcome.UpToDate
                }
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
                        // Only offer to install an APK signed by our own certificate — a
                        // substituted/tampered download is rejected here, not mid-install.
                        if (installer.verifyApkSignature(progress.file)) {
                            pendingFile = progress.file
                            _state.value = UpdatePromptState.InstallReady(available.versionName)
                        } else {
                            runCatching { progress.file.delete() }
                            pendingFile = null
                            _state.value = UpdatePromptState.Error(
                                versionName = available.versionName,
                                errorKind = UpdatePromptState.ErrorKind.VERIFICATION,
                            )
                        }
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
     * "Not now" (or a tap outside the dialog): close the dialog only. The dismissal is transient
     * and in-memory, nothing is persisted, and the persistent update dot stays lit. The next
     * check that still finds this update re-shows the dialog, so the reminder comes back on the
     * normal check cadence rather than being suppressed forever.
     */
    fun dismiss(scope: CoroutineScope) {
        downloadJob?.cancel()
        downloadJob = null
        pendingFile = null
        _state.value = null
        // The staged record outlives this screen, and every foreground entry re-adopts it, so
        // clearing memory alone put the prompt back within seconds and Not now could not be
        // answered at all. Dropping the record is what leaves the next scheduled check to raise
        // it again, which is the cadence this is meant to have.
        scope.launch { runCatching { stagedUpdates.discard() } }
    }

    /**
     * The downloaded APK file (if the state is currently [UpdatePromptState.InstallReady]).
     * Returns null if no install is staged — the Activity uses this to decide whether to
     * build the install intent or fall through.
     */
    fun pendingInstallFile(): File? = pendingFile

    /**
     * Applies the staged APK through the silent PackageInstaller session. The two refusals the
     * installer can name (wrong signer, not actually newer) are terminal and surface as dialog
     * errors with the staged file dropped; everything else is handed back so the host can either
     * launch the OS confirmation screen or fall through to the legacy intent.
     */
    fun installPending(): InstallOutcome {
        val file = pendingFile ?: return InstallOutcome.Failed("No staged update")
        val outcome = installer.installViaSession(file)
        when (outcome) {
            is InstallOutcome.SignatureMismatch -> {
                discardStagedFile(file)
                _state.value = UpdatePromptState.Error(
                    versionName = pendingAvailable?.versionName,
                    errorKind = UpdatePromptState.ErrorKind.VERIFICATION,
                )
            }
            is InstallOutcome.NotNewer -> {
                discardStagedFile(file)
                // The staged build does not rank above what is running, so the dot is stale too.
                _updateAvailable.value = false
                _state.value = UpdatePromptState.Error(
                    versionName = pendingAvailable?.versionName,
                    errorKind = UpdatePromptState.ErrorKind.ALREADY_CURRENT,
                )
            }
            else -> Unit
        }
        return outcome
    }

    private fun discardStagedFile(file: File) {
        runCatching { file.delete() }
        pendingFile = null
    }

    /**
     * Surfaces the dialog in its Available state. Bytes-to-MB rounds UP so a 23.4 MB
     * payload reads as "24 MB" instead of "23" (users compare against their cellular
     * data plan — overshooting is friendlier than undershooting).
     */
    private suspend fun showAvailable(status: UpdateStatus.Available) {
        pendingAvailable = status
        _updateAvailable.value = true
        val sizeMb = ((status.apkSizeBytes + 1024L * 1024L - 1L) / (1024L * 1024L))
            .toInt()
            .coerceAtLeast(0)
        _state.value = UpdatePromptState.Available(
            versionName = status.versionName,
            sizeMb = sizeMb,
        )
        // Upgrade straight past the download when the background check already fetched this one.
        adoptStagedUpdate(status.versionName)
    }

    sealed class ManualCheckOutcome {
        data object UpToDate : ManualCheckOutcome()
        data object NewVersionShown : ManualCheckOutcome()
        data class Failed(val errorKind: UpdatePromptState.ErrorKind) : ManualCheckOutcome()
        data object InProgress : ManualCheckOutcome()
    }
}
