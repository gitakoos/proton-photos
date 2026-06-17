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
    // Routes under the Drive product — the API rejects "photos" ("Product 'photos' is not valid").
    // DO NOT switch to the hyphenated name form: ProtonCore's ApiManagerFactory client-side regex
    // throws "Invalid app version code" on launch for it. This underscore form passes both that
    // regex and the auth server. The pre-release suffix is stripped so the {semver} stays clean.
    override val appVersionHeader =
        "external-drive-akoos_proton_photos@${BuildConfig.VERSION_NAME.substringBefore('-')}-stable"
    // Share-creation traffic needs the brand/model trailer (e.g. "(Android 14; Google Pixel 9)"),
    // not a bare "(Android)" — built from android.os.Build so no device is hardcoded.
    override val userAgent: String = buildString {
        append("AkoosProtonPhotos/").append(BuildConfig.VERSION_NAME).append(" (")
        append("Android ").append(android.os.Build.VERSION.RELEASE).append("; ")
        append(android.os.Build.BRAND).append(' ').append(android.os.Build.MODEL)
        append(')')
    }
    override val enableDebugLogging = BuildConfig.DEBUG

    override suspend fun shouldUseDoh(): Boolean = true

    override fun forceUpdate(errorMessage: String) {
        // Persist the flag so MainActivity can show a non-dismissible "please update" dialog.
        scope.launch {
            context.settingsDataStore.edit { it[FORCE_UPDATE_REQUIRED] = true }
        }
    }

    private companion object {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
