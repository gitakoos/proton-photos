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

import eu.akoos.photos.data.crypto.DriveCryptoHelper
import me.proton.core.crypto.common.context.CryptoContext
import me.proton.core.crypto.common.pgp.SessionKey
import me.proton.core.domain.entity.UserId
import me.proton.core.user.domain.entity.UserAddressKey
import me.proton.core.user.domain.repository.UserAddressRepository
import javax.inject.Inject

/**
 * The production [EnvelopeCrypto]: OpenPGP through [DriveCryptoHelper] on the account's Proton address
 * keys, the same keys and helpers the face-index export encrypts and signs with. Every call runs in the
 * calling process under the helper's crypto lock, as that export does; the `:crypto` service exposes
 * decrypt calls only.
 */
class AddressKeyEnvelopeCrypto @Inject constructor(
    private val cryptoHelper: DriveCryptoHelper,
    private val cryptoContext: CryptoContext,
    private val userAddressRepository: UserAddressRepository,
) : EnvelopeCrypto {

    /** The volume-owner address key the face-index export encrypts to and signs with. */
    suspend fun sealKeys(userId: UserId): EnvelopeSealKeys {
        val key = cryptoHelper.getAddressSigningKey(userId)
        return EnvelopeSealKeys(recipientPublicKey = key.publicKeyArmored, signingKey = key.unlockedKeyBytes)
    }

    /**
     * Every key of every enabled address, primary address and primary key first, so an envelope still opens
     * after a key rotation or a primary-address change; the trailer verifies against all of their public
     * keys. [refreshAddresses] refetches the address list first, for a key made on another device since this
     * one cached the list.
     */
    suspend fun openKeys(userId: UserId, refreshAddresses: Boolean = false): EnvelopeOpenKeys {
        val addresses = userAddressRepository.getAddresses(userId, refreshAddresses)
            .filter { it.enabled && it.keys.isNotEmpty() }
            .sortedBy { it.order }
        val decryptionKeys = addresses.flatMap { address ->
            address.keys.sortedByDescending { it.privateKey.isPrimary }.mapNotNull { unlockOrNull(it) }
        }
        return EnvelopeOpenKeys(decryptionKeys, cryptoHelper.getOwnPublicKeysArmored(userId))
    }

    override fun generateSessionKey(): ByteArray = cryptoHelper.generateSessionKey().key

    override fun wrapSessionKey(sessionKey: ByteArray, recipientPublicKey: String): ByteArray =
        cryptoHelper.encryptSessionKeyToNode(SessionKey(sessionKey), recipientPublicKey)

    override fun unwrapSessionKey(wrappedKey: ByteArray, decryptionKey: ByteArray): ByteArray? =
        cryptoHelper.decryptSessionKey(cryptoHelper.base64Encode(wrappedKey), decryptionKey)?.key

    override fun encryptChunk(plain: ByteArray, sessionKey: ByteArray): ByteArray =
        cryptoHelper.encryptBlock(plain, SessionKey(sessionKey))

    override fun decryptChunk(cipher: ByteArray, sessionKey: ByteArray): ByteArray? {
        // Shorter than any real data packet; very short input can abort the native library instead of throwing.
        if (cipher.size < MIN_DATA_PACKET_BYTES) return null
        return runCatching {
            cryptoHelper.withCryptoLock { cryptoContext.pgpCrypto.decryptData(cipher, SessionKey(sessionKey)) }
        }.getOrNull()
    }

    override fun sign(data: ByteArray, signingKey: ByteArray): ByteArray =
        cryptoHelper.signData(data, signingKey).toByteArray(Charsets.UTF_8)

    override fun verify(data: ByteArray, signature: ByteArray, verificationKeys: List<String>): Boolean {
        // Only armored signatures reach the native verifier; blank armor can abort it instead of throwing.
        val armored = String(signature, Charsets.UTF_8)
        if (!armored.startsWith(ARMORED_SIGNATURE_PREFIX)) return false
        return cryptoHelper.verifyDetachedSignature(data, armored, verificationKeys)
    }

    /** Unlocks one address key with the calls [DriveCryptoHelper] unlocks its signing key with; null when the
     *  key has no passphrase or does not unlock. */
    private fun unlockOrNull(key: UserAddressKey): ByteArray? {
        val passphrase = key.privateKey.passphrase ?: return null
        return runCatching {
            cryptoHelper.withCryptoLock {
                val plainPassphrase = cryptoContext.keyStoreCrypto.decrypt(passphrase)
                try {
                    val unlocked = cryptoContext.pgpCrypto.unlock(key.privateKey.key, plainPassphrase.array)
                    try {
                        unlocked.value.copyOf()
                    } finally {
                        unlocked.close()
                    }
                } finally {
                    plainPassphrase.close()
                }
            }
        }.getOrNull()
    }

    private companion object {
        const val MIN_DATA_PACKET_BYTES = 16
        const val ARMORED_SIGNATURE_PREFIX = "-----BEGIN PGP SIGNATURE-----"
    }
}
