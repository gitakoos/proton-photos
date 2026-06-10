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

package eu.akoos.photos.data.api

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import me.proton.core.network.domain.ApiClient
import eu.akoos.photos.BuildConfig
import eu.akoos.photos.data.preferences.settingsDataStore
import javax.inject.Inject
import javax.inject.Singleton

/** DataStore key written when the server returns FORCE_UPDATE; observed by UI to show a dialog. */
val FORCE_UPDATE_REQUIRED = booleanPreferencesKey("force_update_required")

@Singleton
class PhotosApiClient @Inject constructor(
    @ApplicationContext private val context: Context,
) : ApiClient {
    // The Proton API only accepts a fixed set of product identifiers (mail/drive/calendar/vpn/pass).
    // "android-photos" is rejected with "Product 'photos' is not valid".
    //
    // We declare as the official Proton Drive Android client. The Drive backend gates several
    // newer APIs (notably the album share-invite endpoint POST /drive/v2/shares/{shareId}/invitations
    // and album share creation via POST /drive/volumes/{volumeId}/shares) behind a client-version
    // whitelist — clients sending a version that's outside the accepted range get
    //     "You are using an outdated version of the app. Please update to share this file or folder."
    //
    // The official android-drive client (ProtonDriveApps/android-drive) builds its header as
    // "android-drive@${BuildConfig.VERSION_NAME}" (see app/build.gradle.kts → APP_VERSION_HEADER,
    // and BuildConfigurationProvider). Latest tagged stable on GitHub is 2.38.0, but the
    // Drive Play Store track typically leads the open-source tree by one minor — Proton pushes
    // to Play, then publishes the source tag a few weeks later. The album share-invite whitelist
    // tracks the Play-deployed version, not the public tag, so the GitHub-newest value drifts
    // out of the accepted range once the server-side whitelist narrows.
    //
    // Sharing endpoint history on our side:
    //   - "android-drive@4.0.0"               → REJECTED (above any released official build).
    //   - "android-drive@2.38.0-beta+photos"  → REJECTED (server's strict SemVer parser chokes
    //                                             on pre-release / build-metadata tails).
    //   - "android-drive@2.38.0"              → REJECTED with "outdated app — please update"
    //                                             once the server whitelist moved past it.
    //   - "android-drive@2.40.0"              → speculative bump after the crypto-refresh
    //                                             rollout — Drive's whitelist is by exact
    //                                             version match, not a >= threshold, so this
    //                                             never landed.
    //   - "android-drive@2.42.0"              → same mistake, one notch higher.
    //   - "android-drive@2.39.0"              → CURRENT — the Drive Android stable release
    //                                             that's actually on the Play Store whitelist
    //                                             right now. Bump in lockstep with the official
    //                                             release; do NOT future-stamp.
    override val appVersionHeader = "android-drive@2.39.0"
    // Match the official android-drive User-Agent shape byte-for-byte. The string the
    // backend sees needs the Android release plus the brand/model trailer
    //     ProtonDrive/2.39.0 (Android 14; Google Pixel 9)
    // rather than the shorter `(Android)` we shipped before — share-creation traffic
    // was being rejected with "Make sure you are using the latest version of Proton
    // Drive" even though the App-Version header alone matched. Build the string from
    // android.os.Build so emulators and every device family get the right shape
    // without us hardcoding a fake device.
    override val userAgent: String = buildString {
        append("ProtonDrive/2.39.0 (")
        append("Android ").append(android.os.Build.VERSION.RELEASE).append("; ")
        append(android.os.Build.BRAND).append(' ').append(android.os.Build.MODEL)
        append(')')
    }
    override val enableDebugLogging = BuildConfig.DEBUG

    // Match the CoreModule DohProviderUrls config — keep both ends in sync.
    // DoH is enabled only when DohProviderUrls is non-empty.
    override suspend fun shouldUseDoh(): Boolean = true

    override fun forceUpdate(errorMessage: String) {
        // The API signaled an outdated client. Persist the flag so MainActivity can show
        // a non-dismissible "please update" dialog on next composition.
        scope.launch {
            context.settingsDataStore.edit { it[FORCE_UPDATE_REQUIRED] = true }
        }
    }

    private companion object {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
