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

package eu.akoos.photos.domain.repository

/**
 * Polls GitHub Releases for a newer stable build, picks the right APK for the device's
 * ABI, and surfaces the result to the UI. Beta / RC / alpha tags are filtered out at this
 * layer — the in-app updater is stable-only by design (users who want betas grab them
 * straight from the GitHub releases page).
 *
 * The repository is intentionally non-throwing: every failure mode (no network, parse
 * error, rate-limited, missing APK asset) collapses to [UpdateStatus.UpToDate]. The
 * silent-failure stance protects the auto-check from yelling at the user about
 * transient network issues; the manual "Check for updates" path lives in a separate
 * higher-level surface that can present errors when it cares to.
 */
interface UpdateCheckerRepository {
    /** Forces a fresh GitHub fetch (ignores 24h cache). For manual "Check for updates" tap. */
    suspend fun checkForUpdateForced(): UpdateStatus

    /** Returns cached result if last check was <24h ago; otherwise fetches fresh. */
    suspend fun checkForUpdateCached(): UpdateStatus

    /** Marks a version as dismissed so future cached checks return Hidden until a newer tag appears. */
    suspend fun dismissVersion(versionName: String)
}

sealed class UpdateStatus {
    /** No newer version available, OR network error, OR rate-limited. */
    data object UpToDate : UpdateStatus()

    /** A newer stable release exists for this device's ABI. */
    data class Available(
        val versionName: String,    // e.g. "2.0.1" (no leading 'v')
        val tagName: String,        // e.g. "v2.0.1"
        val apkUrl: String,         // direct download URL of the device-matched asset
        val apkSizeBytes: Long,
        val apkAssetName: String,   // e.g. "photosforproton-arm64-v8a-release.apk"
        val releaseNotes: String,   // release body markdown
    ) : UpdateStatus()

    /** A newer version exists but the user previously dismissed it. */
    data class DismissedVersion(val versionName: String) : UpdateStatus()
}
