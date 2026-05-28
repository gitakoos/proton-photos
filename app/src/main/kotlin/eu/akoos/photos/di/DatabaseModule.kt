package eu.akoos.photos.di

import android.content.Context
import androidx.room.Room
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.Multibinds
import me.proton.core.eventmanager.domain.EventListener
import me.proton.core.account.data.db.AccountDatabase
import me.proton.core.auth.data.db.AuthDatabase
import me.proton.core.challenge.data.db.ChallengeDatabase
import me.proton.core.featureflag.data.db.FeatureFlagDatabase
import me.proton.core.humanverification.data.db.HumanVerificationDatabase
import me.proton.core.key.data.db.KeySaltDatabase
import me.proton.core.key.data.db.PublicAddressDatabase
import me.proton.core.eventmanager.data.db.EventMetadataDatabase
import me.proton.core.notification.data.local.db.NotificationDatabase
import me.proton.core.observability.data.db.ObservabilityDatabase
import me.proton.core.telemetry.data.db.TelemetryDatabase
import me.proton.core.user.data.db.AddressDatabase
import me.proton.core.user.data.db.UserDatabase
import me.proton.core.usersettings.data.db.OrganizationDatabase
import me.proton.core.usersettings.data.db.UserSettingsDatabase
import eu.akoos.photos.data.db.AppDatabase
import eu.akoos.photos.data.db.Migrations
import eu.akoos.photos.data.db.dao.PhotoListingDao
import eu.akoos.photos.data.db.dao.SyncStateDao
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class DatabaseModule {

    @Multibinds
    abstract fun bindEventListeners(): Set<@JvmSuppressWildcards EventListener<*, *>>

    @Binds
    abstract fun bindAccountDatabase(db: AppDatabase): AccountDatabase

    @Binds
    abstract fun bindUserDatabase(db: AppDatabase): UserDatabase

    @Binds
    abstract fun bindAddressDatabase(db: AppDatabase): AddressDatabase

    @Binds
    abstract fun bindKeySaltDatabase(db: AppDatabase): KeySaltDatabase

    @Binds
    abstract fun bindPublicAddressDatabase(db: AppDatabase): PublicAddressDatabase

    @Binds
    abstract fun bindHumanVerificationDatabase(db: AppDatabase): HumanVerificationDatabase

    @Binds
    abstract fun bindFeatureFlagDatabase(db: AppDatabase): FeatureFlagDatabase

    @Binds
    abstract fun bindAuthDatabase(db: AppDatabase): AuthDatabase

    @Binds
    abstract fun bindChallengeDatabase(db: AppDatabase): ChallengeDatabase

    @Binds
    abstract fun bindNotificationDatabase(db: AppDatabase): NotificationDatabase

    @Binds
    abstract fun bindUserSettingsDatabase(db: AppDatabase): UserSettingsDatabase

    @Binds
    abstract fun bindOrganizationDatabase(db: AppDatabase): OrganizationDatabase

    @Binds
    abstract fun bindEventMetadataDatabase(db: AppDatabase): EventMetadataDatabase

    @Binds
    abstract fun bindObservabilityDatabase(db: AppDatabase): ObservabilityDatabase

    @Binds
    abstract fun bindTelemetryDatabase(db: AppDatabase): TelemetryDatabase

    companion object {

        @Provides
        @Singleton
        fun provideAppDatabase(@ApplicationContext context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "proton_photos.db")
                .addMigrations(*Migrations.ALL)
                .build()

        // @Singleton on each DAO so we don't allocate a fresh wrapper per injection;
        // the underlying [AppDatabase] is @Singleton and DAOs are stateless wrappers,
        // so caching one instance per app lifetime is correct and cheaper.
        @Provides
        @Singleton
        fun provideSyncStateDao(db: AppDatabase): SyncStateDao = db.syncStateDao()

        @Provides
        @Singleton
        fun providePhotoListingDao(db: AppDatabase): PhotoListingDao = db.photoListingDao()
    }
}
