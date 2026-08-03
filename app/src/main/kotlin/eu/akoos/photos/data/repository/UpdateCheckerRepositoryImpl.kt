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
 * background check throttles to once per 4h.
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
            // (The repository can't reconstruct the full candidate cheaply, so it returns
            // UpToDate here; the caller lights the persistent indicator from
            // knownAvailableVersion() separately, and the dialog only follows a fresh check.)
            UpdateStatus.UpToDate
        } else {
            performCheck()
        }
    }.getOrElse { UpdateStatus.UpToDate }

    override suspend fun knownAvailableVersion(): String? = runCatching {
        context.settingsDataStore.data.first()[SettingsKeys.UPDATE_AVAILABLE_VERSION]
    }.getOrNull()

    /**
     * Fetches the latest release, applies all the filters, and returns the resolved status.
     * Bookkeeps [SettingsKeys.UPDATE_LAST_CHECK_MS] after a successful fetch so the cache
     * window starts ticking, and persists [SettingsKeys.UPDATE_AVAILABLE_VERSION] to the
     * available version (or clears it) so the persistent update indicator survives relaunch.
     * Failed fetches leave the timestamp alone so the next call can retry immediately instead
     * of waiting out the throttle after a transient outage.
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
        if (release.prerelease) return upToDate()
        if (hasPrereleaseSuffix(release.tagName)) return upToDate()

        val remoteVersion = stripTagPrefix(release.tagName)
        val localVersion = BuildConfig.VERSION_NAME
        if (compareSemver(remoteVersion, localVersion) <= 0) {
            // Remote is not strictly newer than what's installed — nothing to do.
            return upToDate()
        }

        val asset = pickAssetForDevice(release.assets) ?: return upToDate()

        // Persist the available version so the avatar dot can re-light on the next relaunch
        // without hitting the network. There is no per-version suppression: a fresh check
        // that still finds this update always reports it Available so the dialog re-nags.
        context.settingsDataStore.edit { prefs ->
            prefs[SettingsKeys.UPDATE_AVAILABLE_VERSION] = remoteVersion
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

    /** Clears the persisted available-version marker (the app is up to date) and returns UpToDate. */
    private suspend fun upToDate(): UpdateStatus {
        context.settingsDataStore.edit { prefs ->
            prefs.remove(SettingsKeys.UPDATE_AVAILABLE_VERSION)
        }
        return UpdateStatus.UpToDate
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

    private companion object {
        private const val CACHE_TTL_MS = 4L * 60L * 60L * 1000L
        /** Matches the `base { archivesName }` setting in app/build.gradle.kts. */
        private const val APK_BASE_NAME = "photosforproton"
    }
}

/**
 * True if the tag carries a pre-release suffix the auto-check skips. The updater only ever offers
 * stable releases, so a preview tag has to be ruled out by its own name as well as by GitHub's
 * pre-release flag: a tag can be published as a full release and still be a preview.
 *
 * Matched on the suffix, never the bare word, so a release whose name happens to contain one of
 * these ("2.4.0-rc" is skipped, "2.4.0" is not) stays on the stable path.
 */
internal fun hasPrereleaseSuffix(tag: String): Boolean {
    val lower = tag.lowercase()
    return lower.contains("-beta") ||
        lower.contains("-alpha") ||
        lower.contains("-rc") ||
        lower.contains("-pre") ||
        lower.contains("-snapshot")
}

/**
 * SemVer-precedence compare of two version names ("2.3.10", "2.3.10-test12").
 *
 * Numeric segments decide first, left to right; missing trailing segments count as 0 so
 * "2.0" compares equal to "2.0.0". When every numeric segment matches, a version carrying a
 * pre-release suffix ranks BELOW the same version without one — so a preview build ranks an
 * older stable as older, but still ranks its own final release as newer and can move onto it.
 *
 * Unparseable segments count as 0 rather than throwing: the caller resolves every failure to
 * UpToDate, so a malformed tag must not surface as an error.
 */
internal fun compareSemver(a: String, b: String): Int {
    val aParts = a.substringBefore('-').split('.')
    val bParts = b.substringBefore('-').split('.')
    val max = maxOf(aParts.size, bParts.size)
    for (i in 0 until max) {
        val aPart = aParts.getOrNull(i)?.toIntOrNull() ?: 0
        val bPart = bParts.getOrNull(i)?.toIntOrNull() ?: 0
        if (aPart != bPart) return aPart.compareTo(bPart)
    }
    val aSuffix = a.substringAfter('-', "")
    val bSuffix = b.substringAfter('-', "")
    if (aSuffix == bSuffix) return 0
    if (aSuffix.isEmpty()) return 1
    if (bSuffix.isEmpty()) return -1
    return comparePrerelease(aSuffix, bSuffix)
}

/**
 * Orders two pre-release suffixes by their leading text, then by the trailing digit run
 * numerically, so "test9" precedes "test12" instead of sorting after it the way a plain
 * lexical compare would.
 */
private fun comparePrerelease(a: String, b: String): Int {
    val aDigits = a.takeLastWhile { it.isDigit() }
    val bDigits = b.takeLastWhile { it.isDigit() }
    val aText = a.dropLast(aDigits.length)
    val bText = b.dropLast(bDigits.length)
    if (aText != bText) return aText.compareTo(bText)
    return (aDigits.toIntOrNull() ?: 0).compareTo(bDigits.toIntOrNull() ?: 0)
}
