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

package eu.akoos.photos.data.repository

import android.content.Context
import android.os.Build
import androidx.datastore.preferences.core.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.akoos.photos.BuildConfig
import eu.akoos.photos.data.api.GitHubReleasesApi
import eu.akoos.photos.data.api.model.GitHubRelease
import eu.akoos.photos.data.preferences.SettingsKeys
import eu.akoos.photos.data.preferences.settingsDataStore
import eu.akoos.photos.domain.repository.UpdateCheckerRepository
import eu.akoos.photos.domain.repository.UpdateStatus
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Hits the GitHub Releases API, compares the latest stable tag to [BuildConfig.VERSION_NAME],
 * and picks the ABI-matched APK asset. Cache windows are tracked in DataStore so the
 * background check throttles to once per 24h.
 *
 * Failure handling: every exception path (network, parse, missing asset, unknown ABI)
 * resolves to [UpdateStatus.UpToDate]. The repository never throws — by design. Loud
 * errors during the silent auto-check would surface as confusing dialogs at app launch;
 * the manual "Check for updates" surface gets its own error path higher up the stack.
 */
@Singleton
class UpdateCheckerRepositoryImpl @Inject constructor(
    private val api: GitHubReleasesApi,
    @ApplicationContext private val context: Context,
) : UpdateCheckerRepository {

    override suspend fun checkForUpdateForced(): UpdateStatus = runCatching {
        performCheck()
    }.getOrElse { UpdateStatus.UpToDate }

    override suspend fun checkForUpdateCached(): UpdateStatus = runCatching {
        val prefs = context.settingsDataStore.data.first()
        val lastCheck = prefs[SettingsKeys.UPDATE_LAST_CHECK_MS] ?: 0L
        val now = System.currentTimeMillis()
        // Treat clock-skew (negative delta from a system time change) as fresh too — we
        // don't want a one-time clock jump backwards to permanently block all checks.
        val delta = now - lastCheck
        if (delta in 0 until CACHE_TTL_MS) {
            // Within the throttle window. Skip the network round-trip and stay silent.
            // (The repository can't reconstruct the "candidate" object cheaply, so it
            // returns UpToDate; this is correct because anything actionable would have
            // been shown to the user on the original check.)
            UpdateStatus.UpToDate
        } else {
            performCheck()
        }
    }.getOrElse { UpdateStatus.UpToDate }

    override suspend fun dismissVersion(versionName: String) {
        context.settingsDataStore.edit { prefs ->
            prefs[SettingsKeys.UPDATE_DISMISSED_VERSION] = versionName
        }
    }

    /**
     * Fetches the latest release, applies all the filters, and returns the resolved status.
     * Bookkeeps [SettingsKeys.UPDATE_LAST_CHECK_MS] after a successful fetch so the cache
     * window starts ticking. Failed fetches leave the timestamp alone so the next call can
     * retry immediately instead of waiting 24h after a transient outage.
     */
    private suspend fun performCheck(): UpdateStatus {
        val release = api.getLatestRelease()

        // Mark the successful fetch immediately — even if we ultimately decide the user
        // is up-to-date, GitHub did return a response so the throttle should kick in.
        context.settingsDataStore.edit { prefs ->
            prefs[SettingsKeys.UPDATE_LAST_CHECK_MS] = System.currentTimeMillis()
        }

        // GitHub's own flag wins over tag-suffix parsing — if the maintainer marked a
        // release as a pre-release in the GitHub UI without bothering with a -beta suffix,
        // we still skip it.
        if (release.prerelease) return UpdateStatus.UpToDate
        if (hasPrereleaseSuffix(release.tagName)) return UpdateStatus.UpToDate

        val remoteVersion = stripTagPrefix(release.tagName)
        val localVersion = BuildConfig.VERSION_NAME
        if (compareSemver(remoteVersion, localVersion) <= 0) {
            // Remote is not strictly newer than what's installed — nothing to do.
            return UpdateStatus.UpToDate
        }

        val asset = pickAssetForDevice(release.assets) ?: return UpdateStatus.UpToDate

        val prefs = context.settingsDataStore.data.first()
        val dismissed = prefs[SettingsKeys.UPDATE_DISMISSED_VERSION]
        if (dismissed != null && dismissed == remoteVersion) {
            return UpdateStatus.DismissedVersion(remoteVersion)
        }

        return UpdateStatus.Available(
            versionName = remoteVersion,
            tagName = release.tagName,
            apkUrl = asset.browserDownloadUrl,
            apkSizeBytes = asset.size,
            apkAssetName = asset.name,
            releaseNotes = release.body,
        )
    }

    /**
     * Walks [Build.SUPPORTED_ABIS] in priority order (the device's preferred ABI is first)
     * and returns the first asset whose name matches. Falls back to the universal APK if
     * no per-ABI match is found, then null if even the universal isn't published.
     */
    private fun pickAssetForDevice(assets: List<GitHubRelease.Asset>): GitHubRelease.Asset? {
        val byName = assets.associateBy { it.name }
        for (abi in Build.SUPPORTED_ABIS) {
            val expectedName = "$APK_BASE_NAME-$abi-release.apk"
            byName[expectedName]?.let { return it }
        }
        return byName["$APK_BASE_NAME-universal-release.apk"]
    }

    /** Strips the leading 'v' and any "-beta" / "-rc1" suffix from a tag. */
    private fun stripTagPrefix(tag: String): String {
        val noV = if (tag.startsWith("v") || tag.startsWith("V")) tag.substring(1) else tag
        val dash = noV.indexOf('-')
        return if (dash >= 0) noV.substring(0, dash) else noV
    }

    /** True if the tag carries any pre-release suffix we want to skip. */
    private fun hasPrereleaseSuffix(tag: String): Boolean {
        val lower = tag.lowercase()
        return lower.contains("-beta") ||
            lower.contains("-alpha") ||
            lower.contains("-rc") ||
            lower.contains("-pre") ||
            lower.contains("-snapshot")
    }

    /**
     * Three-segment SemVer compare. Missing trailing segments are treated as 0 so
     * "2.0" compares equal to "2.0.0". Non-numeric segments fall back to 0 — every tag
     * in this project is plain numeric so the fallback is purely defensive.
     */
    private fun compareSemver(a: String, b: String): Int {
        val aParts = a.split('.')
        val bParts = b.split('.')
        val max = maxOf(aParts.size, bParts.size)
        for (i in 0 until max) {
            val aPart = aParts.getOrNull(i)?.toIntOrNull() ?: 0
            val bPart = bParts.getOrNull(i)?.toIntOrNull() ?: 0
            if (aPart != bPart) return aPart.compareTo(bPart)
        }
        return 0
    }

    private companion object {
        private const val CACHE_TTL_MS = 24L * 60L * 60L * 1000L
        /** Matches the `base { archivesName }` setting in app/build.gradle.kts. */
        private const val APK_BASE_NAME = "photosforproton"
    }
}
