package me.proton.photos.data.api

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import me.proton.core.network.domain.ApiClient
import me.proton.photos.BuildConfig
import me.proton.photos.data.preferences.settingsDataStore
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
    // and BuildConfigurationProvider). Latest tagged stable at time of writing is 2.38.0.
    //
    // Sharing endpoint history on our side:
    //   - "android-drive@4.0.0"            → REJECTED (above any released official build).
    //   - "android-drive@2.38.0-beta+photos" → REJECTED (server's strict SemVer parser likely
    //                                          chokes on the "+photos" build-metadata tail).
    //   - "android-drive@2.38.0"           → CURRENT — plain SemVer, the exact official-stable
    //                                          tag, accepted by the share-invite whitelist.
    override val appVersionHeader = "android-drive@2.38.0"
    override val userAgent = "ProtonDrive/2.38.0 (Android)"
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
