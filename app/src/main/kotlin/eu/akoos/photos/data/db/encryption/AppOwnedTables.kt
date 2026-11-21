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

package eu.akoos.photos.data.db.encryption

/**
 * The database tables that belong to the app itself, as opposed to the Proton core tables beside them (account,
 * session, keys, ...). Restoring a set-aside database copies only these: the core tables stay from the sign-in that
 * came after the loss, because the old ones hold a session and key passphrases sealed by the lost Keystore key.
 *
 * A unit test checks this list and [PROTON_CORE] against the newest exported Room schema, so a new table cannot be
 * added without deciding which side it is on.
 */
object AppOwnedTables {
    val NAMES: List<String> = listOf(
        "sync_state",
        "photo_listing",
        "day_meta",
        "cloud_albums",
        "album_photo_membership",
        "local_tag",
        "photo_location",
        "perceptual_hash",
        "upload_album_target",
        "pending_metadata_edit",
        "pending_cloud_compress",
        "import_staged",
        "import_history",
        "import_uploaded",
        "import_album_member",
        "listing_sweep_snapshot",
        "face",
        "person",
        "face_scan",
        "person_manual_photo",
        "not_person",
        "person_cover",
        "cluster_summary",
        "image_embedding",
        "photo_label",
        "photo_label_name",
        "photo_label_state",
        "photo_label_deleted",
        "drive_copy_applied",
        "person_name_state",
        "person_hidden",
        "person_name_change",
        "face_curation_time",
        "local_exif_check",
        "album_photo_order",
        "album_order_state",
    )

    /**
     * The user's own work, by table: what a set-aside database must never be copied over. Rows that a fresh
     * listing, scan or index rebuilds count for nothing; an upload counts once it is queued or has gone through.
     */
    val USER_WORK: Map<String, String> = listOf(
        "pending_metadata_edit",
        "pending_cloud_compress",
        "upload_album_target",
        "import_staged",
        "import_history",
        "import_uploaded",
        "import_album_member",
        "local_tag",
        "person",
        "person_manual_photo",
        "not_person",
        "person_cover",
        "person_name_state",
        "person_hidden",
        "person_name_change",
        "face_curation_time",
        "photo_label",
        "photo_label_name",
        "photo_label_state",
        "photo_label_deleted",
        "album_photo_order",
        "album_order_state",
        "day_meta",
    ).associateWith { "SELECT count(*) FROM \"$it\"" } +
        // A photo the user hid keeps its own uri on the row; the rows the vault reseeds after a rebuild name the
        // vault file, so they are not counted.
        (
            "sync_state" to "SELECT count(*) FROM sync_state WHERE queued = 1 OR lastSyncSuccessMs IS NOT NULL " +
                "OR (status = 'HIDDEN' AND localUri NOT LIKE '%/files/hidden/%')"
            )

    /** The Proton core tables, never restored from a set-aside database. */
    val PROTON_CORE: List<String> = listOf(
        "AccountEntity",
        "AccountMetadataEntity",
        "SessionEntity",
        "SessionDetailsEntity",
        "UserEntity",
        "UserKeyEntity",
        "AddressEntity",
        "AddressKeyEntity",
        "KeySaltEntity",
        "PublicAddressEntity",
        "PublicAddressInfoEntity",
        "PublicAddressKeyDataEntity",
        "PublicAddressKeyEntity",
        "HumanVerificationEntity",
        "FeatureFlagEntity",
        "AuthDeviceEntity",
        "DeviceSecretEntity",
        "MemberDeviceEntity",
        "ChallengeFrameEntity",
        "NotificationEntity",
        "UserSettingsEntity",
        "OrganizationEntity",
        "OrganizationKeysEntity",
        "EventMetadataEntity",
        "ObservabilityEventEntity",
        "TelemetryEventEntity",
    )
}
