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

package eu.akoos.photos.data.mlsync

import me.proton.core.domain.entity.UserId
import javax.inject.Inject

/** The writing install as a snapshot header names it: its sync id and a label another device shows. */
data class FaceSnapshotDevice(val id: String, val label: String)

/**
 * What sealing and opening a face snapshot needs besides the database: the envelope crypto, the account's
 * keys for it, and this install's identity for the snapshot header. The app builds it from
 * [AddressKeyEnvelopeCrypto] and [MlDriveStore]; a test builds one around a fake crypto.
 */
class FaceSnapshotSealing(
    val crypto: EnvelopeCrypto,
    private val sealKeysFor: suspend (UserId) -> EnvelopeSealKeys,
    private val openKeysFor: suspend (UserId, Boolean) -> EnvelopeOpenKeys,
    private val deviceFor: suspend () -> FaceSnapshotDevice,
) {
    @Inject
    constructor(envelopeCrypto: AddressKeyEnvelopeCrypto, driveStore: MlDriveStore) : this(
        crypto = envelopeCrypto,
        sealKeysFor = { userId -> envelopeCrypto.sealKeys(userId) },
        openKeysFor = { userId, refresh -> envelopeCrypto.openKeys(userId, refreshAddresses = refresh) },
        deviceFor = { FaceSnapshotDevice(driveStore.deviceId(), driveStore.deviceLabel) },
    )

    /** The key a snapshot is sealed to and signed with. The caller closes it, which wipes the private key. */
    suspend fun sealKeys(userId: UserId): EnvelopeSealKeys = sealKeysFor(userId)

    /** Every key a snapshot may open with; [refreshAddresses] refetches the account's addresses first. The
     *  caller closes it, which wipes the private keys. */
    suspend fun openKeys(userId: UserId, refreshAddresses: Boolean): EnvelopeOpenKeys =
        openKeysFor(userId, refreshAddresses)

    /** This install's identity for a snapshot header. */
    suspend fun device(): FaceSnapshotDevice = deviceFor()
}
