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

package eu.akoos.photos.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import me.proton.core.account.domain.entity.AccountType
import me.proton.core.domain.entity.AppStore
import me.proton.core.domain.entity.Product
import me.proton.core.humanverification.presentation.HumanVerificationApiHost
import me.proton.core.humanverification.presentation.utils.HumanVerificationVersion
import me.proton.core.network.data.client.ExtraHeaderProviderImpl
import me.proton.core.network.data.di.AlternativeApiPins
import me.proton.core.network.data.di.BaseProtonApiUrl
import me.proton.core.network.data.di.CertificatePins
import me.proton.core.network.data.di.DohProviderUrls
import me.proton.core.network.domain.client.ExtraHeaderProvider
import me.proton.core.network.domain.serverconnection.DohAlternativesListener
import me.proton.core.payment.presentation.entity.SecureEndpoint
import me.proton.core.plan.domain.SupportSignupPaidPlans
import me.proton.core.plan.domain.SupportUpgradePaidPlans
import okhttp3.CertificatePinner
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import javax.inject.Qualifier
import javax.inject.Singleton

/**
 * Tags the pinned [OkHttpClient] used by CDN block + thumbnail fetches so it
 * doesn't collide with ProtonCore's own bound clients on the DI graph.
 */
@Qualifier @Retention(AnnotationRetention.RUNTIME) annotation class CdnOkHttpClient

@Module
@InstallIn(SingletonComponent::class)
object CoreModule {

    @Provides
    @Singleton
    fun provideProduct(): Product = Product.Drive

    @Provides
    @Singleton
    @BaseProtonApiUrl
    // Use the dedicated API gateway (`drive-api.proton.me/`), not the web-frontend
    // host (`drive.proton.me/api/`). The Drive web host proxies the Drive-specific
    // paths transparently, BUT it returns its own HTML 404 page for Core endpoints
    // like `/core/v4/keys/all` — which kotlinx-serialization then surfaces as
    // "Unexpected JSON token at offset 0, had '<'". The gateway routes everything
    // (drive + core + auth) and matches both the official Drive Android client
    // (proton-environment apiPrefix = "drive-api") and the JS SDK
    // (https://drive-api.proton.me).
    fun provideBaseApiUrl(): HttpUrl = "https://drive-api.proton.me/".toHttpUrl()

    @Provides
    @Singleton
    @DohProviderUrls
    fun provideDohProviderUrls(): Array<String> = arrayOf(
        // Quad9 (Switzerland based, no logging) + Cloudflare (1.1.1.1, no logging,
        // explicit privacy policy). Google was previously included as a fallback but
        // we deliberately avoid Google infrastructure for DNS — every DoH lookup
        // would advertise the user's interest in Proton to Google's logs.
        "https://dns11.quad9.net/dns-query/",
        "https://cloudflare-dns.com/dns-query/",
    )

    // Public SHA-256 SPKI pins for *.proton.me — sourced from
    // protoncore_android/network/data/.../di/Constants.DEFAULT_SPKI_PINS.
    // Without these, the connection accepts any chain a trusted CA issues — MITM is possible.
    @Provides
    @Singleton
    @CertificatePins
    fun provideCertificatePins(): Array<String> = arrayOf(
        "drtmcR2kFkM8qJClsuWgUzxgBkePfRCkRpqUesyDmeE=",
        "YRGlaY0jyJ4Jw2/4M8FIftwbDIQfh8Sdro96CeEel54=",
        "AfMENBVvOS8MnISprtvyPsjKlPooqh8nMB/pvCrpJpw=",
        "CT56BhOTmj5ZIPgb/xD5mH8rY3BLo/MlhP7oPyJUEDo=",
        "35Dx28/uzN3LeltkCBQ8RHK0tlNSa2kCpCRGNp34Gxc=",
        "qYIukVc63DEITct8sFT7ebIq5qsWmuscaIKeJx+5J5A=",
    )

    @Provides
    @Singleton
    fun provideExtraHeaderProvider(): ExtraHeaderProvider = ExtraHeaderProviderImpl()

    @Provides
    @Singleton
    fun provideDohAlternativesListener(): DohAlternativesListener =
        object : DohAlternativesListener {
            override suspend fun onAlternativesUnblock(alternativesBlockCall: suspend () -> Unit) = alternativesBlockCall()
            override suspend fun onProxiesFailed() {}
        }

    // Payments are disabled in this build (IsPaymentsV5Enabled = false,
    // IsMobileUpgradesEnabled = false, ProtonIAPBillingLibrary.isAvailable() = false
    // in StubModule). SecureEndpoint is still required by the ProtonCore Hilt graph,
    // so we point it at an unresolvable placeholder — any code path that managed to
    // reach this string would fail fast on DNS instead of silently contacting Proton's
    // payments host with the user's session.
    @Provides
    @Singleton
    fun provideSecureEndpoint(): SecureEndpoint = SecureEndpoint("localhost")

    // SPKI pins used for the alternative-routing (DoH-resolved) hosts —
    // protoncore_android.di.Constants.ALTERNATIVE_API_SPKI_PINS.
    @Provides
    @Singleton
    @AlternativeApiPins
    fun provideAlternativeApiPins(): List<String> = listOf(
        "EU6TS9MO0L/GsDHvVc9D5fChYLNy5JdGYpJw0ccgetM=",
        "iKPIHPnDNqdkvOnTClQ8zQAIKG0XavaPkcEo0LBAABA=",
        "MSlVrBCdL0hKyczvgYVSRNm88RicyY04Q2y5qrBt0xA=",
        "C2UxW0T1Ckl9s+8cXfjXxlEqwAfPM4HiW2y3UdtBeCw=",
    )

    /**
     * Shared OkHttpClient for the CDN block + thumbnail fetches. The block PUT/GET
     * traffic bypasses ProtonCore's ApiProvider (it uses pm-storage-token headers
     * instead of Bearer auth), so by default it had no cert pinning at all — a
     * compromised CA in the device's trust store could MITM the encrypted blocks.
     * Pinning to the same SPKIs CoreModule uses for the main API closes that gap.
     * The pin entries below match [provideCertificatePins] in raw value, just
     * reformatted to OkHttp's "sha256/<base64>" convention.
     */
    @Provides
    @Singleton
    @CdnOkHttpClient
    fun provideCdnOkHttpClient(): OkHttpClient {
        val pins = arrayOf(
            "drtmcR2kFkM8qJClsuWgUzxgBkePfRCkRpqUesyDmeE=",
            "YRGlaY0jyJ4Jw2/4M8FIftwbDIQfh8Sdro96CeEel54=",
            "AfMENBVvOS8MnISprtvyPsjKlPooqh8nMB/pvCrpJpw=",
            "CT56BhOTmj5ZIPgb/xD5mH8rY3BLo/MlhP7oPyJUEDo=",
            "35Dx28/uzN3LeltkCBQ8RHK0tlNSa2kCpCRGNp34Gxc=",
            "qYIukVc63DEITct8sFT7ebIq5qsWmuscaIKeJx+5J5A=",
        )
        val builder = CertificatePinner.Builder()
        // The Proton CDN returns block URLs anywhere on the proton.me public suffix
        // (drive-blocks.proton.me, drive-static.proton.me, etc.). Wildcard pinning
        // at the registrable domain covers any future subdomain reshuffle without
        // requiring a client rebuild.
        for (pin in pins) {
            builder.add("*.proton.me", "sha256/$pin")
            builder.add("proton.me", "sha256/$pin")
        }
        return OkHttpClient.Builder()
            .certificatePinner(builder.build())
            .build()
    }

    @Provides
    @Singleton
    fun provideAppStore(): AppStore = AppStore.GooglePlay

    @Provides
    @Singleton
    fun provideAccountType(): AccountType = AccountType.Internal

    @Provides
    @Singleton
    @SupportSignupPaidPlans
    fun provideSupportSignupPaidPlans(): Boolean = false

    @Provides
    @Singleton
    @SupportUpgradePaidPlans
    fun provideSupportUpgradePaidPlans(): Boolean = false

    @Provides
    @Singleton
    @HumanVerificationApiHost
    fun provideHumanVerificationApiHost(): String = "verify.proton.me"

    @Provides
    @Singleton
    fun provideHumanVerificationVersion(): HumanVerificationVersion =
        HumanVerificationVersion.HV3

    // Note: the new ProtonCore LoginTwoStepActivity (used for username+password) is Compose-
    // only and hard-codes a light Compose theme — it ignores both AppCompatDelegate's
    // night-mode setting and our XML manifest theme override. The 2FA / TwoPassMode /
    // CreateAddress activities are still XML-themed (respect Theme.ProtonPhotos.Auth).
    // We can't disable the two-step flow via Hilt because the ProtonCore default
    // IsLoginTwoStepEnabledImpl is bound in auth-data with @Binds, and binding it again
    // here triggers a Dagger DuplicateBindings error in production. Leaving the sign-in
    // screen on the ProtonCore Compose theme until upstream exposes a theme override hook.
}
