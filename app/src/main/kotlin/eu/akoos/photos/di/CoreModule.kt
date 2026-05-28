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
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object CoreModule {

    @Provides
    @Singleton
    fun provideProduct(): Product = Product.Drive

    @Provides
    @Singleton
    @BaseProtonApiUrl
    fun provideBaseApiUrl(): HttpUrl = "https://drive.proton.me/api/".toHttpUrl()

    @Provides
    @Singleton
    @DohProviderUrls
    fun provideDohProviderUrls(): Array<String> = arrayOf(
        "https://dns11.quad9.net/dns-query/",
        "https://dns.google/dns-query/",
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

    @Provides
    @Singleton
    fun provideSecureEndpoint(): SecureEndpoint = SecureEndpoint("payments.proton.me")

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
