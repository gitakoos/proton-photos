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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import me.proton.core.auth.domain.usecase.PostLoginAccountSetup
import me.proton.core.auth.presentation.HelpOptionHandler
import me.proton.core.biometric.domain.AuthenticatorsResolver
import me.proton.core.biometric.domain.BiometricAuthenticator
import me.proton.core.biometric.domain.CheckBiometricAuthAvailability
import me.proton.core.compose.theme.AppTheme
import me.proton.core.country.domain.entity.Country
import me.proton.core.country.domain.repository.CountriesRepository
import me.proton.core.domain.entity.AppStore
import me.proton.core.domain.entity.UserId
import me.proton.core.featureflag.domain.FeatureFlagOverrider
import me.proton.core.payment.domain.PurchaseManager
import me.proton.core.payment.domain.entity.GooglePurchase
import me.proton.core.payment.domain.entity.GooglePurchaseToken
import me.proton.core.payment.domain.entity.ProductId
import me.proton.core.payment.domain.entity.ProtonPaymentToken
import me.proton.core.payment.domain.entity.Purchase
import me.proton.core.payment.domain.features.IsMobileUpgradesEnabled
import me.proton.core.payment.domain.features.IsOmnichannelEnabled
import me.proton.core.payment.domain.features.IsPaymentsV5Enabled
import me.proton.core.payment.domain.repository.GooglePurchaseRepository
import me.proton.core.payment.domain.repository.PaymentsRepository
import me.proton.core.payment.domain.repository.PurchaseRepository
import me.proton.core.payment.domain.usecase.AcknowledgeGooglePlayPurchase
import me.proton.core.payment.domain.usecase.ConvertToObservabilityGiapStatus
import me.proton.core.payment.domain.usecase.CreatePaymentTokenForGooglePurchase
import me.proton.core.payment.domain.usecase.FindGooglePurchaseForPaymentOrderId
import me.proton.core.payment.domain.usecase.FindUnacknowledgedGooglePurchase
import me.proton.core.payment.domain.usecase.GetStorePrice
import me.proton.core.payment.domain.usecase.GoogleServicesUtils
import me.proton.core.payment.domain.usecase.PaymentProvider
import me.proton.core.payment.domain.usecase.ProtonIAPBillingLibrary
import me.proton.core.payment.presentation.ActivePaymentProvider
import me.proton.core.push.domain.entity.Push
import me.proton.core.push.domain.entity.PushId
import me.proton.core.push.domain.entity.PushObjectType
import me.proton.core.push.domain.remote.PushRemoteDataSource
import me.proton.core.push.domain.repository.PushRepository
import me.proton.core.user.domain.entity.User
import java.util.Optional
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object StubModule {

    // FeatureFlag overrider — no overrides in dev
    @Provides @Singleton
    fun provideFeatureFlagOverrider(): FeatureFlagOverrider =
        object : FeatureFlagOverrider {
            override fun getOverrideOrNull(key: String): Boolean? = null
        }

    // Auth — post-login user check (accepts all users)
    @Provides @Singleton
    fun providePostLoginUserCheck(): PostLoginAccountSetup.UserCheck =
        object : PostLoginAccountSetup.UserCheck {
            override suspend fun invoke(user: User): PostLoginAccountSetup.UserCheckResult =
                PostLoginAccountSetup.UserCheckResult.Success
        }

    @Provides @Singleton
    fun provideAppTheme(): AppTheme = AppTheme { content -> content() }

    @Provides @Singleton
    fun provideHelpOptionHandler(): HelpOptionHandler =
        object : HelpOptionHandler {
            override fun onForgotUsername(context: androidx.appcompat.app.AppCompatActivity) = Unit
            override fun onForgotPassword(context: androidx.appcompat.app.AppCompatActivity) = Unit
            override fun onCustomerSupport(context: androidx.appcompat.app.AppCompatActivity) = Unit
            override fun onOtherSignInIssues(context: androidx.appcompat.app.AppCompatActivity) = Unit
            override fun onTroubleshoot(context: androidx.appcompat.app.AppCompatActivity) = Unit
        }

    // Biometric — not available
    @Provides @Singleton
    fun provideCheckBiometricAuthAvailability(): CheckBiometricAuthAvailability =
        object : CheckBiometricAuthAvailability {
            override fun invoke(
                allowedAuthenticators: Set<BiometricAuthenticator>,
                authenticatorsResolver: AuthenticatorsResolver,
            ): CheckBiometricAuthAvailability.Result = CheckBiometricAuthAvailability.Result.Failure.NoHardware
        }

    // Payment feature flags — all disabled (no Google IAP)
    @Provides @Singleton
    fun provideProtonIAPBillingLibrary(): ProtonIAPBillingLibrary =
        object : ProtonIAPBillingLibrary {
            override fun isAvailable(): Boolean = false
        }

    @Provides @Singleton
    fun provideIsMobileUpgradesEnabled(): IsMobileUpgradesEnabled =
        object : IsMobileUpgradesEnabled {
            override fun isLocalEnabled(): Boolean = false
            override fun isRemoteEnabled(userId: UserId?): Boolean = false
            override fun invoke(userId: UserId?): Boolean = false
        }

    @Provides @Singleton
    fun provideIsOmnichannelEnabled(): IsOmnichannelEnabled =
        object : IsOmnichannelEnabled {
            override fun isLocalEnabled(): Boolean = false
            override fun isRemoteEnabled(userId: UserId?): Boolean = false
            override fun invoke(userId: UserId?): Boolean = false
        }

    @Provides @Singleton
    fun provideIsPaymentsV5Enabled(): IsPaymentsV5Enabled =
        object : IsPaymentsV5Enabled {
            override fun isLocalEnabled(): Boolean = false
            override fun isRemoteEnabled(userId: UserId?): Boolean = false
            override fun invoke(userId: UserId?): Boolean = false
        }

    @Provides @Singleton
    fun provideCreatePaymentTokenForGooglePurchase(): CreatePaymentTokenForGooglePurchase =
        object : CreatePaymentTokenForGooglePurchase {
            override suspend fun invoke(
                googleProductId: ProductId,
                purchase: GooglePurchase,
                userId: UserId?,
            ): CreatePaymentTokenForGooglePurchase.Result = error("stub: no Google IAP")
        }

    // Payment repositories — stub (no billing)
    @Provides @Singleton
    fun providePaymentsRepository(): PaymentsRepository =
        object : PaymentsRepository {
            override suspend fun createOmnichannelPaymentToken(sessionUserId: UserId?, packageName: String, productId: String, orderId: String) = error("stub: no payment")
            @Suppress("OVERRIDE_DEPRECATION") // upstream-deprecated, we don't ship billing
            override suspend fun createPaymentToken(sessionUserId: UserId?, paymentType: me.proton.core.payment.domain.entity.PaymentType) = error("stub: no payment")
            override suspend fun getPaymentTokenStatus(sessionUserId: UserId?, paymentToken: ProtonPaymentToken) = error("stub: no payment")
            override suspend fun getAvailablePaymentMethods(sessionUserId: UserId): List<me.proton.core.payment.domain.entity.PaymentMethod> = emptyList()
            override suspend fun getPaymentStatus(sessionUserId: UserId?, appStore: AppStore) = error("stub: no payment")
        }

    @Provides @Singleton
    fun providePurchaseRepository(): PurchaseRepository =
        object : PurchaseRepository {
            override fun observePurchase(planName: String): Flow<Purchase?> = emptyFlow()
            override fun observePurchases(): Flow<List<Purchase>> = emptyFlow()
            override suspend fun getPurchase(planName: String): Purchase? = null
            override suspend fun getPurchases(): List<Purchase> = emptyList()
            override suspend fun upsertPurchase(purchase: Purchase) = Unit
            override suspend fun deletePurchase(planName: String) = Unit
            override fun onPurchaseStateChanged(initialState: Boolean): Flow<Purchase> = emptyFlow()
        }

    @Provides @Singleton
    fun providePurchaseManager(): PurchaseManager =
        object : PurchaseManager {
            override suspend fun addPurchase(purchase: Purchase) = Unit
            override suspend fun getPurchase(planName: String): Purchase? = null
            override suspend fun getPurchases(): List<Purchase> = emptyList()
            override fun observePurchase(planName: String): Flow<Purchase?> = emptyFlow()
            override fun observePurchases(): Flow<List<Purchase>> = emptyFlow()
            override fun onPurchaseStateChanged(initialState: Boolean): Flow<Purchase> = emptyFlow()
        }

    @Provides @Singleton
    fun provideGooglePurchaseRepository(): GooglePurchaseRepository =
        object : GooglePurchaseRepository {
            override suspend fun deleteByGooglePurchaseToken(googlePurchaseToken: GooglePurchaseToken) = Unit
            override suspend fun deleteByProtonPaymentToken(paymentToken: ProtonPaymentToken) = Unit
            override suspend fun findGooglePurchaseToken(paymentToken: ProtonPaymentToken): GooglePurchaseToken? = null
            override suspend fun updateGooglePurchase(googlePurchaseToken: GooglePurchaseToken, paymentToken: ProtonPaymentToken) = Unit
        }

    @Provides @Singleton
    fun provideActivePaymentProvider(): ActivePaymentProvider =
        object : ActivePaymentProvider {
            override suspend fun getActivePaymentProvider(): PaymentProvider? = null
            override fun switchNextPaymentProvider(): PaymentProvider? = null
            override fun getNextPaymentProviderText(): Int? = null
        }

    // Push — no-op stubs (no push-dagger module)
    @Provides @Singleton
    fun providePushRepository(): PushRepository =
        object : PushRepository {
            override suspend fun deletePush(userId: UserId, pushId: PushId) = Unit
            override suspend fun getAllPushes(userId: UserId, type: PushObjectType, refresh: Boolean): List<Push> = emptyList()
            override fun observeAllPushes(userId: UserId, type: PushObjectType, refresh: Boolean): Flow<List<Push>> = emptyFlow()
            override fun markAsStale(userId: UserId, type: PushObjectType) = Unit
        }

    @Provides @Singleton
    fun providePushRemoteDataSource(): PushRemoteDataSource =
        object : PushRemoteDataSource {
            override suspend fun getAllPushes(userId: UserId): List<Push> = emptyList()
            override suspend fun deletePush(userId: UserId, pushId: PushId) = Unit
        }

    // Countries — no-op stub (no country-dagger module)
    @Provides @Singleton
    fun provideCountriesRepository(): CountriesRepository =
        object : CountriesRepository {
            override suspend fun getAllCountriesSorted(): List<Country> = emptyList()
            override suspend fun getCountry(countryName: String): Country = error("stub: no countries")
        }

    // Optional payment use cases — all absent (no Google IAP)
    @Provides @Singleton
    fun provideFindGooglePurchaseForPaymentOrderId(): Optional<FindGooglePurchaseForPaymentOrderId> = Optional.empty()

    @Provides @Singleton
    fun provideAcknowledgeGooglePlayPurchase(): Optional<AcknowledgeGooglePlayPurchase> = Optional.empty()

    @Provides @Singleton
    fun provideFindUnacknowledgedGooglePurchase(): Optional<FindUnacknowledgedGooglePurchase> = Optional.empty()

    @Provides @Singleton
    fun provideGetStorePrice(): Optional<GetStorePrice> = Optional.empty()

    @Provides @Singleton
    fun provideConvertToObservabilityGiapStatus(): Optional<ConvertToObservabilityGiapStatus> = Optional.empty()

    @Provides @Singleton
    fun provideGoogleServicesUtils(): Optional<GoogleServicesUtils> = Optional.empty()

    // Telemetry/Observability are NOT overridden here: ProtonCore hard-binds IsTelemetryEnabledImpl,
    // so an app-side @Provides would be a Dagger DuplicateBindings break. We never emit telemetry
    // ourselves, and the user can disable it in their Proton account settings.
}
