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
    // "android-photos" is rejected with "Product 'photos' is not valid", so requests route under the
    // Drive product.
    //
    // The App-Version header identifies this as an external (third-party) client to the Drive
    // backend, per the Drive SDK operational requirements
    // (https://github.com/ProtonDriveApps/sdk#operational-requirements). It must not impersonate
    // an official Proton client.
    //
    // CONSTRAINT (do not "fix" to the hyphenated form): the bundled ProtonCore network library
    // (network-data 36.x) runs its OWN client-side validation in ApiManagerFactory and throws
    // `IllegalArgumentException: Invalid app version code` BEFORE any request is sent if the value
    // doesn't match its regex. The SDK-example `external-drive-akoos-proton-photos@2.1.0`
    // (hyphen-separated name) FAILS that validation and crashes the app on launch; this underscore
    // form passes BOTH ProtonCore's client regex AND the auth server (sign-in confirmed working).
    //
    // The {semver} tracks the app's release version, derived from BuildConfig so it bumps with each
    // release automatically. A pre-release suffix (e.g. "-test15", "-beta") is stripped so a test
    // build still emits a clean {semver}; the channel stays `stable`.
    override val appVersionHeader =
        "external-drive-akoos_proton_photos@${BuildConfig.VERSION_NAME.substringBefore('-')}-stable"
    // The backend validates the User-Agent shape for share-creation traffic: it needs the Android
    // release plus the brand/model trailer, e.g. "ProtonDrive/2.39.0 (Android 14; Google Pixel 9)",
    // rather than a bare "(Android)". Build it from android.os.Build so every device family gets the
    // right shape without hardcoding a device.
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
