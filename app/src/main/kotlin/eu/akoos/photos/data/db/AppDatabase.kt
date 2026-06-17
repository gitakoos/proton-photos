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

// PublicAddressEntity / PublicAddressKeyEntity are deprecated, but PublicAddressDatabase still
// requires DAOs backed by them — keep until the SDK drops those interface members.
@file:Suppress("DEPRECATION")

package eu.akoos.photos.data.db

import androidx.room.Database
import androidx.room.TypeConverters
import me.proton.core.account.data.db.AccountConverters
import me.proton.core.account.data.db.AccountDatabase
import me.proton.core.account.data.db.AccountDao
import me.proton.core.account.data.db.AccountMetadataDao
import me.proton.core.account.data.db.SessionDao
import me.proton.core.account.data.db.SessionDetailsDao
import me.proton.core.account.data.entity.AccountEntity
import me.proton.core.account.data.entity.AccountMetadataEntity
import me.proton.core.account.data.entity.SessionDetailsEntity
import me.proton.core.account.data.entity.SessionEntity
import me.proton.core.auth.data.dao.AuthDeviceDao
import me.proton.core.auth.data.dao.DeviceSecretDao
import me.proton.core.auth.data.dao.MemberDeviceDao
import me.proton.core.auth.data.db.AuthConverters
import me.proton.core.auth.data.db.AuthDatabase
import me.proton.core.auth.data.entity.AuthDeviceEntity
import me.proton.core.auth.data.entity.DeviceSecretEntity
import me.proton.core.auth.data.entity.MemberDeviceEntity
import me.proton.core.challenge.data.db.ChallengeConverters
import me.proton.core.challenge.data.db.ChallengeDatabase
import me.proton.core.challenge.data.db.ChallengeFramesDao
import me.proton.core.challenge.data.entity.ChallengeFrameEntity
import me.proton.core.crypto.android.keystore.CryptoConverters
import me.proton.core.data.room.db.BaseDatabase
import me.proton.core.data.room.db.CommonConverters
import me.proton.core.eventmanager.data.db.EventManagerConverters
import me.proton.core.eventmanager.data.db.EventMetadataDatabase
import me.proton.core.eventmanager.data.db.dao.EventMetadataDao
import me.proton.core.eventmanager.data.entity.EventMetadataEntity
import me.proton.core.featureflag.data.db.FeatureFlagDao
import me.proton.core.featureflag.data.db.FeatureFlagDatabase
import me.proton.core.featureflag.data.entity.FeatureFlagEntity
import me.proton.core.humanverification.data.db.HumanVerificationConverters
import me.proton.core.humanverification.data.db.HumanVerificationDatabase
import me.proton.core.humanverification.data.db.HumanVerificationDetailsDao
import me.proton.core.humanverification.data.entity.HumanVerificationEntity
import me.proton.core.key.data.db.KeySaltDao
import me.proton.core.key.data.db.KeySaltDatabase
import me.proton.core.key.data.db.PublicAddressDao
import me.proton.core.key.data.db.PublicAddressDatabase
import me.proton.core.key.data.db.PublicAddressInfoDao
import me.proton.core.key.data.db.PublicAddressInfoWithKeysDao
import me.proton.core.key.data.db.PublicAddressKeyDao
import me.proton.core.key.data.db.PublicAddressKeyDataDao
import me.proton.core.key.data.db.PublicAddressWithKeysDao
import me.proton.core.key.data.entity.KeySaltEntity
import me.proton.core.key.data.entity.PublicAddressEntity
import me.proton.core.key.data.entity.PublicAddressInfoEntity
import me.proton.core.key.data.entity.PublicAddressKeyDataEntity
import me.proton.core.key.data.entity.PublicAddressKeyEntity
import me.proton.core.notification.data.local.db.NotificationConverters
import me.proton.core.notification.data.local.db.NotificationDao
import me.proton.core.notification.data.local.db.NotificationDatabase
import me.proton.core.notification.data.local.db.NotificationEntity
import me.proton.core.observability.data.db.ObservabilityDao
import me.proton.core.observability.data.db.ObservabilityDatabase
import me.proton.core.observability.data.entity.ObservabilityEventEntity
import me.proton.core.telemetry.data.db.TelemetryDao
import me.proton.core.telemetry.data.db.TelemetryDatabase
import me.proton.core.telemetry.data.entity.TelemetryEventEntity
import me.proton.core.user.data.db.AddressDatabase
import me.proton.core.user.data.db.UserConverters
import me.proton.core.user.data.db.UserDatabase
import me.proton.core.user.data.db.dao.AddressDao
import me.proton.core.user.data.db.dao.AddressKeyDao
import me.proton.core.user.data.db.dao.AddressWithKeysDao
import me.proton.core.user.data.db.dao.UserDao
import me.proton.core.user.data.db.dao.UserKeyDao
import me.proton.core.user.data.db.dao.UserWithKeysDao
import me.proton.core.user.data.entity.AddressEntity
import me.proton.core.user.data.entity.AddressKeyEntity
import me.proton.core.user.data.entity.UserEntity
import me.proton.core.user.data.entity.UserKeyEntity
import me.proton.core.usersettings.data.db.OrganizationDatabase
import me.proton.core.usersettings.data.db.UserSettingsConverters
import me.proton.core.usersettings.data.db.UserSettingsDatabase
import me.proton.core.usersettings.data.db.dao.OrganizationDao
import me.proton.core.usersettings.data.db.dao.OrganizationKeysDao
import me.proton.core.usersettings.data.db.dao.UserSettingsDao
import me.proton.core.usersettings.data.entity.OrganizationEntity
import me.proton.core.usersettings.data.entity.OrganizationKeysEntity
import me.proton.core.usersettings.data.entity.UserSettingsEntity
import eu.akoos.photos.data.db.dao.AlbumPhotoMembershipDao
import eu.akoos.photos.data.db.dao.CloudAlbumDao
import eu.akoos.photos.data.db.dao.DayMetaDao
import eu.akoos.photos.data.db.dao.LocalTagDao
import eu.akoos.photos.data.db.dao.PhotoListingDao
import eu.akoos.photos.data.db.dao.SyncStateDao
import eu.akoos.photos.data.db.entity.AlbumPhotoMembershipEntity
import eu.akoos.photos.data.db.entity.CloudAlbumEntity
import eu.akoos.photos.data.db.entity.DayMetaEntity
import eu.akoos.photos.data.db.entity.LocalTagEntity
import eu.akoos.photos.data.db.entity.PhotoListingEntity
import eu.akoos.photos.data.db.entity.SyncStateEntity

@TypeConverters(
    CommonConverters::class,
    CryptoConverters::class,
    AccountConverters::class,
    UserConverters::class,
    UserSettingsConverters::class,
    HumanVerificationConverters::class,
    AuthConverters::class,
    ChallengeConverters::class,
    NotificationConverters::class,
    EventManagerConverters::class,
)
@Database(
    entities = [
        SyncStateEntity::class,
        PhotoListingEntity::class,
        DayMetaEntity::class,
        CloudAlbumEntity::class,
        AlbumPhotoMembershipEntity::class,
        LocalTagEntity::class,
        // Account
        AccountEntity::class,
        AccountMetadataEntity::class,
        SessionEntity::class,
        SessionDetailsEntity::class,
        // User
        UserEntity::class,
        UserKeyEntity::class,
        AddressEntity::class,
        AddressKeyEntity::class,
        // Key
        KeySaltEntity::class,
        PublicAddressEntity::class,
        PublicAddressInfoEntity::class,
        PublicAddressKeyDataEntity::class,
        PublicAddressKeyEntity::class,
        // HumanVerification
        HumanVerificationEntity::class,
        // FeatureFlag
        FeatureFlagEntity::class,
        // Auth
        AuthDeviceEntity::class,
        DeviceSecretEntity::class,
        MemberDeviceEntity::class,
        // Challenge
        ChallengeFrameEntity::class,
        // Notification
        NotificationEntity::class,
        // UserSettings + Organization
        UserSettingsEntity::class,
        OrganizationEntity::class,
        OrganizationKeysEntity::class,
        // EventManager
        EventMetadataEntity::class,
        // Observability
        ObservabilityEventEntity::class,
        // Telemetry
        TelemetryEventEntity::class,
    ],
    version = 11,
    exportSchema = true,
)
abstract class AppDatabase : BaseDatabase(),
    AccountDatabase,
    UserDatabase,
    AddressDatabase,
    KeySaltDatabase,
    PublicAddressDatabase,
    HumanVerificationDatabase,
    FeatureFlagDatabase,
    AuthDatabase,
    ChallengeDatabase,
    NotificationDatabase,
    UserSettingsDatabase,
    OrganizationDatabase,
    EventMetadataDatabase,
    ObservabilityDatabase,
    TelemetryDatabase {

    abstract fun syncStateDao(): SyncStateDao
    abstract fun photoListingDao(): PhotoListingDao
    abstract fun dayMetaDao(): DayMetaDao
    abstract fun cloudAlbumDao(): CloudAlbumDao
    abstract fun albumPhotoMembershipDao(): AlbumPhotoMembershipDao
    abstract fun localTagDao(): LocalTagDao

    abstract override fun accountDao(): AccountDao
    abstract override fun sessionDao(): SessionDao
    abstract override fun accountMetadataDao(): AccountMetadataDao
    abstract override fun sessionDetailsDao(): SessionDetailsDao

    abstract override fun userDao(): UserDao
    abstract override fun userWithKeysDao(): UserWithKeysDao
    abstract override fun userKeyDao(): UserKeyDao

    abstract override fun addressDao(): AddressDao
    abstract override fun addressWithKeysDao(): AddressWithKeysDao
    abstract override fun addressKeyDao(): AddressKeyDao

    abstract override fun keySaltDao(): KeySaltDao

    abstract override fun publicAddressDao(): PublicAddressDao
    abstract override fun publicAddressKeyDao(): PublicAddressKeyDao
    abstract override fun publicAddressWithKeysDao(): PublicAddressWithKeysDao
    abstract override fun publicAddressInfoDao(): PublicAddressInfoDao
    abstract override fun publicAddressKeyDataDao(): PublicAddressKeyDataDao
    abstract override fun publicAddressInfoWithKeysDao(): PublicAddressInfoWithKeysDao

    abstract override fun humanVerificationDetailsDao(): HumanVerificationDetailsDao

    abstract override fun featureFlagDao(): FeatureFlagDao

    abstract override fun deviceSecretDao(): DeviceSecretDao
    abstract override fun authDeviceDao(): AuthDeviceDao
    abstract override fun memberDeviceDao(): MemberDeviceDao

    abstract override fun challengeFramesDao(): ChallengeFramesDao

    abstract override fun notificationDao(): NotificationDao

    abstract override fun userSettingsDao(): UserSettingsDao
    abstract override fun organizationDao(): OrganizationDao
    abstract override fun organizationKeysDao(): OrganizationKeysDao

    abstract override fun eventMetadataDao(): EventMetadataDao

    abstract override fun observabilityDao(): ObservabilityDao

    abstract override fun telemetryDao(): TelemetryDao
}
