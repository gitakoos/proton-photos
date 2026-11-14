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


package eu.akoos.photos.data.sdk

import me.proton.core.crypto.common.context.CryptoContext
import me.proton.core.domain.entity.UserId
import me.proton.core.key.domain.extension.primary
import me.proton.core.key.domain.extension.publicKeyRing
import me.proton.core.key.domain.repository.PublicAddressRepository
import me.proton.core.key.domain.useKeys
import me.proton.core.user.domain.entity.AddressId
import me.proton.core.user.domain.entity.UserAddress
import me.proton.core.user.domain.extension.canEncrypt
import me.proton.core.user.domain.extension.canVerify
import me.proton.core.user.domain.extension.primary
import me.proton.core.user.domain.repository.UserAddressRepository
import me.proton.drive.sdk.PublicAddressResolver
import me.proton.drive.sdk.UserAddressResolver
import me.proton.drive.sdk.entity.Address

/** Gives the Drive SDK the keys of the addresses other accounts hold, read through the sign-in stack. */
internal class CorePublicAddressResolver(
    private val userId: UserId,
    private val publicAddressRepository: PublicAddressRepository,
) : PublicAddressResolver {

    override suspend fun getAddressPublicKeys(emailAddress: String): List<ByteArray> {
        val publicAddressInfo = publicAddressRepository.getPublicAddressInfo(
            sessionUserId = userId,
            email = emailAddress,
        )
        val publicAddressKeys = publicAddressInfo.address.keys + publicAddressInfo.unverified?.keys.orEmpty()
        return publicAddressKeys.publicKeyRing().keys.map { publicKey ->
            publicKey.key.toByteArray()
        }
    }
}

/** Gives the Drive SDK the signed-in account's own addresses and their unlocked private keys. */
internal class CoreUserAddressResolver(
    private val userId: UserId,
    private val cryptoContext: CryptoContext,
    private val userAddressRepository: UserAddressRepository,
) : UserAddressResolver {

    override suspend fun getAddress(id: String): Address = address(id).toSdkAddress()

    override suspend fun getDefaultAddress(): Address =
        checkNotNull(userAddressRepository.getAddresses(userId).primary()) {
            "Cannot find the default address"
        }.toSdkAddress()

    override suspend fun <T> getAddressPrimaryPrivateKey(id: String, block: (ByteArray) -> T): T =
        address(id).useKeys(cryptoContext) {
            block(privateKeyRing.unlockedPrimaryKey.unlockedKey.value)
        }

    override suspend fun <T> getAddressPrivateKeys(id: String, block: (List<ByteArray>) -> T): T =
        address(id).useKeys(cryptoContext) {
            block(privateKeyRing.unlockedKeys.map { key -> key.unlockedKey.value })
        }

    private suspend fun address(id: String): UserAddress =
        checkNotNull(userAddressRepository.getAddress(userId, AddressId(id))) {
            "Cannot find address: $id"
        }

    private fun UserAddress.toSdkAddress() = Address(
        addressId = addressId.id,
        order = order,
        emailAddress = email,
        status = when {
            enabled -> Address.Status.ENABLED
            else -> Address.Status.DISABLED
        },
        keys = keys.map { key ->
            Address.Key(
                addressId = key.addressId.id,
                keyId = key.keyId.id,
                active = key.active,
                allowedForEncryption = key.canEncrypt(),
                allowedForVerification = key.canVerify(),
            )
        },
        primaryKeyIndex = keys.indexOf(keys.primary()),
    )
}
