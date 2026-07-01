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

package eu.akoos.photos.data.crypto

import android.util.Base64
import android.util.Log
import me.proton.core.crypto.common.context.CryptoContext
import me.proton.core.crypto.common.pgp.PGPHeader
import me.proton.core.crypto.common.pgp.PacketType
import me.proton.core.crypto.common.pgp.SessionKey
import me.proton.core.crypto.common.pgp.SignatureContext
import me.proton.core.crypto.common.pgp.VerificationStatus
import me.proton.core.domain.entity.UserId
import me.proton.core.key.domain.decryptData
import me.proton.core.key.domain.decryptSessionKey
import me.proton.core.key.domain.useKeys
import me.proton.core.user.domain.repository.UserAddressRepository
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Drive signature contexts the server checks against (mirror of android-drive's SignatureContexts).
 * All critical. The SDK exposes contexts ONLY for share-member ops — NodePassphrase, Manifest,
 * ContentKeyPacket, Name and Block sign without one, so we deliberately don't add notations there
 * (doing so produces signatures the server/web client refuse to verify).
 */
internal object DriveSignatureContexts {
    val INVITER = SignatureContext(value = "drive.share-member.inviter", isCritical = true)
    val MEMBER  = SignatureContext(value = "drive.share-member.member", isCritical = true)
}

private const val TAG = "DriveCrypto"

/**
 * Extracts (latitude, longitude) from a plaintext XAttr JSON blob produced by [DriveCryptoHelper].
 * The Location block is optional (absent when the photo had no GPS), so a missing/malformed block
 * returns null. Coordinates outside the valid WGS84 ranges are rejected. Pure and main-process safe.
 */
fun parsePhotoLocation(xAttrJson: String): Pair<Double, Double>? = runCatching {
    val location = JSONObject(xAttrJson).optJSONObject("Location") ?: return@runCatching null
    if (!location.has("Latitude") || !location.has("Longitude")) return@runCatching null
    val lat = location.getDouble("Latitude")
    val lon = location.getDouble("Longitude")
    if (lat in -90.0..90.0 && lon in -180.0..180.0) lat to lon else null
}.getOrNull()

data class NodeKeyMaterial(
    val armoredPrivateKey: String,
    val passphraseBytes: ByteArray,
    val publicKeyArmored: String,
    val unlockedKeyBytes: ByteArray,
)

data class AddressSigningKey(
    val email: String,
    val addressId: String,
    val unlockedKeyBytes: ByteArray,
    /** Armored public key of the primary address. Used when we need to encrypt to ourselves. */
    val publicKeyArmored: String,
    /**
     * Server-side ID of the key whose private half is [unlockedKeyBytes]. Required by
     * `POST /drive/photos/volumes` (the `AddressKeyID` field) to tag the new Photos share with the
     * key that signed its passphrase.
     */
    val addressKeyId: String,
)

/** Result of a decrypt-and-verify operation: plaintext bytes plus whether the signature matched. */
data class VerifiedBytes(val data: ByteArray, val verified: Boolean) {
    override fun equals(other: Any?): Boolean = other is VerifiedBytes &&
        data.contentEquals(other.data) && verified == other.verified
    override fun hashCode(): Int = 31 * data.contentHashCode() + verified.hashCode()
}

@Singleton
class DriveCryptoHelper @Inject constructor(
    private val cryptoContext: CryptoContext,
    private val userAddressRepository: UserAddressRepository,
    /** Lazy: PhotosShareService transitively depends on this helper, so direct injection would
     *  deadlock the Hilt graph. Only used to read the volume-owner addressId. */
    private val photosShareServiceLazy: dagger.Lazy<eu.akoos.photos.data.repository.drive.PhotosShareService>,
) {
    private val photosShareService get() = photosShareServiceLazy.get()
    private val shareKeyCache = ConcurrentHashMap<String, ByteArray>()

    // Serializes every libgojni-bound call. Android 16's CMC GC userfaultfd races Go's signal
    // handlers when multiple JNI threads enter the same Go runtime, SIGABRT-ing after a few hundred
    // parallel decrypts; PhotoStreamService chunk-pacing alone can't cover AlbumService + thumbnail
    // pressure. ~2x slower but crash-free. Fair-FIFO so a multi-second upload-encrypt run can't
    // starve viewer decrypts (without fairness the gallery freezes for the upload's duration).
    private val cryptoLock = java.util.concurrent.locks.ReentrantLock(true)

    /**
     * Wraps a direct pgpCrypto.* call (not already routed through this helper) so the same lock
     * serializes it into libgojni. Used by PhotoEntityBuilder, AlbumService, PhotosShareService,
     * PhotosVolumeBootstrap, and PhotoUploadService, which decrypt/generate keys outside this class.
     */
    fun <T> withCryptoLock(block: () -> T): T = cryptoLock.withLock(block)

    // ─── Decryption (download) ────────────────────────────────────────────────

    /**
     * Every active email address on the account, lowercased. Lets us recognise own membership on a
     * shared resource regardless of which alias the inviter used — matching by primary alone fails
     * after a primary change and blocks "Leave album" with "no permission".
     */
    suspend fun getOwnEmailAddresses(userId: UserId): List<String> {
        return userAddressRepository.getAddresses(userId, false)
            .filter { it.enabled }
            .mapNotNull { it.email.takeIf { e -> e.isNotBlank() }?.lowercase() }
            .distinct()
    }

    /**
     * Armored PUBLIC keys of EVERY enabled address — verification keys for content WE uploaded.
     * Aggregating all addresses (not just primary) avoids false "VERIFY_FAIL" + blank-name results
     * when an upload was signed by a non-primary address (aliases, multi-address, changed primary).
     */
    suspend fun getOwnPublicKeysArmored(userId: UserId): List<String> {
        val addresses = userAddressRepository.getAddresses(userId, false)
            .filter { it.enabled && it.keys.isNotEmpty() }
        if (addresses.isEmpty()) return emptyList()
        return addresses.flatMap { address ->
            address.keys.mapNotNull { addrKey ->
                // getPublicKey enters libgojni; serialize it (addresses already fetched, so the lock
                // stays off the suspension path).
                cryptoLock.withLock {
                    runCatching { cryptoContext.pgpCrypto.getPublicKey(addrKey.privateKey.key) }.getOrNull()
                }
            }
        }
    }

    /**
     * Decrypts [encryptedArmored] with [decryptionKeyBytes] AND verifies the embedded
     * signature against [verificationPublicKeysArmored]. Returns [VerifiedBytes] with
     * `verified=true` only when the signature matches an expected signer key.
     *
     * **Never throws on verification failure** — verification is logged but the
     * plaintext is still returned (graceful degradation, matches the web client's
     * "name signature invalid" badge behavior).
     */
    fun decryptAndVerifyData(
        encryptedArmored: String,
        decryptionKeyBytes: ByteArray,
        verificationPublicKeysArmored: List<String>,
    ): VerifiedBytes? = decryptAndVerifyData(
        encryptedArmored = encryptedArmored,
        decryptionKeyCandidates = listOf(decryptionKeyBytes),
        verificationPublicKeysArmored = verificationPublicKeysArmored,
    )

    /**
     * Multi-key variant — gopenpgp tries each candidate against the message's PKESKs. Needed for
     * shared-with-me content where the backend's PKESK substitution can target the share's
     * encryption subkey rather than the obvious parent, so both keys must be offered.
     */
    fun decryptAndVerifyData(
        encryptedArmored: String,
        decryptionKeyCandidates: List<ByteArray>,
        verificationPublicKeysArmored: List<String>,
    ): VerifiedBytes? = cryptoLock.withLock { try {
        val decrypted = cryptoContext.pgpCrypto.decryptAndVerifyData(
            message = encryptedArmored,
            publicKeys = verificationPublicKeysArmored,
            unlockedKeys = decryptionKeyCandidates,
        )
        VerifiedBytes(decrypted.data, decrypted.status == VerificationStatus.Success)
    } catch (e: Exception) {
        // Verify-API rejects unsigned messages; fall back to encrypt-only decrypt. Plain decrypt
        // takes one key, so walk each candidate and stop at the first hit.
        var fallbackResult: ByteArray? = null
        var lastFallbackErr: Throwable? = null
        for (candidate in decryptionKeyCandidates) {
            val attempt = runCatching {
                cryptoContext.pgpCrypto.decryptData(encryptedArmored, candidate)
            }
            if (attempt.isSuccess) { fallbackResult = attempt.getOrNull(); break }
            lastFallbackErr = attempt.exceptionOrNull()
        }
        if (fallbackResult != null) {
            Log.d(TAG, "decryptAndVerifyData: verify failed (${e.message}) — returning unverified plaintext")
            VerifiedBytes(fallbackResult, verified = false)
        } else {
            Log.w(TAG, "decryptAndVerifyData: verify failed (${e.message}) AND encrypt-only fallback failed (${lastFallbackErr?.message})")
            null
        }
    } }

    /**
     * Verifies a plain detached PGP signature over [data] against any of [verificationPublicKeysArmored].
     * Used for ManifestSignature, NodePassphraseSignature, ContentKeyPacketSignature — all detached
     * signatures (not encrypted-signature MESSAGE format).
     */
    fun verifyDetachedSignature(
        data: ByteArray,
        signatureArmored: String,
        verificationPublicKeysArmored: List<String>,
    ): Boolean = verificationPublicKeysArmored.any { pub ->
        runCatching {
            cryptoLock.withLock { cryptoContext.pgpCrypto.verifyData(data, signatureArmored, pub) }
        }.getOrDefault(false)
    }

    suspend fun getOrDecryptShareKey(
        userId: UserId,
        shareKeyArmored: String,
        sharePassphraseArmored: String,
    ): ByteArray {
        shareKeyCache[userId.id]?.let { return it }
        val addresses = userAddressRepository.getAddresses(userId, false)
            .filter { it.enabled && it.keys.isNotEmpty() }
            .sortedBy { it.order }
        if (addresses.isEmpty()) error("No active address for userId=${userId.id}")

        // Try every active address: the share passphrase is encrypted to ONE of them, not always
        // the primary (aliases, multi-address, changed primary all hit "Cannot decrypt with
        // provided Key list" otherwise). The WHOLE attempt (decrypt + unlock) must be in one
        // runCatching — a non-matching address can decrypt to bytes that only fail at unlock(), and
        // wrapping just decryptData would let that throw escape the loop instead of trying the next.
        var lastError: Throwable? = null
        for (address in addresses) {
            val attempt = runCatching {
                // useKeys + unlock both enter libgojni; lock so the post-login burst doesn't race.
                cryptoLock.withLock {
                    val passphraseBytes = address.useKeys(cryptoContext) {
                        decryptData(sharePassphraseArmored)
                    }
                    val unlockedKey = cryptoContext.pgpCrypto.unlock(shareKeyArmored, passphraseBytes)
                    val keyBytes = unlockedKey.value.copyOf()
                    unlockedKey.close()
                    keyBytes
                }
            }
            if (attempt.isSuccess) {
                val keyBytes = attempt.getOrThrow()
                shareKeyCache[userId.id] = keyBytes
                return keyBytes
            }
            lastError = attempt.exceptionOrNull()
        }
        throw lastError ?: error("Share passphrase did not match any address key")
    }

    fun decryptNodeKey(
        nodeKeyArmored: String,
        nodePassphraseArmored: String,
        parentKeyBytes: ByteArray,
    ): ByteArray = cryptoLock.withLock {
        // Blank PGP armor makes the Go library panic "slice bounds out of range [:-1]" and SIGABRT
        // (uncatchable). Fail fast in Kotlin so an exception replaces the process abort.
        require(nodeKeyArmored.isNotBlank()) { "decryptNodeKey: nodeKeyArmored is blank" }
        require(nodePassphraseArmored.isNotBlank()) { "decryptNodeKey: nodePassphraseArmored is blank" }
        val nodePassphraseBytes = cryptoContext.pgpCrypto.decryptData(nodePassphraseArmored, parentKeyBytes)
        val unlockedKey = cryptoContext.pgpCrypto.unlock(nodeKeyArmored, nodePassphraseBytes)
        val keyBytes = unlockedKey.value.copyOf()
        unlockedKey.close()
        keyBytes
    }

    fun decryptLinkName(encryptedNameArmored: String, nodeKeyBytes: ByteArray): String? = cryptoLock.withLock {
        if (encryptedNameArmored.isBlank()) return@withLock null
        try {
            // decryptData handles both binary (encryptAndSignData) and text (encryptText) names.
            val bytes = cryptoContext.pgpCrypto.decryptData(encryptedNameArmored, nodeKeyBytes)
            String(bytes, Charsets.UTF_8)
        } catch (e: Exception) {
            // Fallback: legacy text-mode messages
            try { cryptoContext.pgpCrypto.decryptText(encryptedNameArmored, nodeKeyBytes) }
            catch (e2: Exception) { Log.w(TAG, "decryptLinkName failed: ${e2.message}"); null }
        }
    }

    /**
     * Decrypts a cloud photo's armored XAttr blob to its plaintext JSON with the node key.
     * Like [decryptLinkName], it runs under [cryptoLock] without signature verification.
     * Returns null on any failure (the Go runtime guards apply: blank armor is rejected first).
     */
    fun decryptXAttr(xAttrArmored: String, nodeKeyBytes: ByteArray): String? = cryptoLock.withLock {
        if (xAttrArmored.isBlank()) return@withLock null
        runCatching {
            cryptoContext.pgpCrypto.decryptText(xAttrArmored, nodeKeyBytes)
        }.getOrElse {
            Log.w(TAG, "decryptXAttr failed: ${it.message}")
            null
        }
    }

    fun decryptSessionKey(contentKeyPacketBase64: String, nodeKeyBytes: ByteArray): SessionKey? = cryptoLock.withLock {
        if (contentKeyPacketBase64.isBlank()) return@withLock null
        try {
            val keyPacketBytes = Base64.decode(contentKeyPacketBase64, Base64.DEFAULT)
            if (keyPacketBytes.isEmpty()) return@withLock null
            cryptoContext.pgpCrypto.decryptSessionKey(keyPacketBytes, nodeKeyBytes)
        } catch (e: Exception) {
            Log.w(TAG, "decryptSessionKey failed: ${e.message}")
            null
        }
    }

    fun decryptFileToDestination(sessionKey: SessionKey, encryptedFile: File, destFile: File): File = cryptoLock.withLock {
        // Empty/missing bytes trigger the same [:-1] Go panic/SIGABRT — validate in Kotlin first.
        require(encryptedFile.exists() && encryptedFile.length() > 0) {
            "decryptFileToDestination: encryptedFile is empty or missing"
        }
        val result = cryptoContext.pgpCrypto.decryptFile(encryptedFile, destFile, sessionKey)
        result.file
    }

    /**
     * Decrypts a self-contained binary OpenPGP block (PKESK + SEIPD) with the node's unlocked key
     * bytes. Drive block downloads have no separate ContentKeyPacket — the PKESK rides in the block.
     */
    fun decryptBinaryPgpWithNodeKey(data: ByteArray, nodeKeyBytes: ByteArray): ByteArray? = cryptoLock.withLock {
        // Reject too-short input before Go: a 0-byte buffer triggers the [:-1] panic/SIGABRT parsing
        // the armor header. 16 is below the smallest real PGP message.
        if (data.size < 16) return@withLock null
        try {
            // getArmored() gives proper ASCII-armor + CRC24, then the standard decryptData(armored,
            // keyBytes) path. The split + decryptData(packet, sk) path fails ("EncryptedFile cannot
            // be extracted") — that overload is for the attachment/literal-file format only.
            val armored = cryptoContext.pgpCrypto.getArmored(data, PGPHeader.Message)
            cryptoContext.pgpCrypto.decryptData(armored, nodeKeyBytes)
        } catch (e: Exception) {
            Log.w(TAG, "decryptBinaryPgpWithNodeKey failed: ${e.message}")
            null
        }
    }

    // ─── Encryption (upload) ─────────────────────────────────────────────────

    suspend fun getAddressSigningKey(userId: UserId): AddressSigningKey {
        val addresses = userAddressRepository.getAddresses(userId, false)
        // Volume owner address (sticky from registration), NOT today's mail-primary: Drive signs
        // every album/upload/share/invite with the volume owner, and an alias promoted to primary
        // later would produce signatures recipients can't verify. Fall back to mail-primary only on
        // cold start before the share bootstrap has run.
        val volumeOwnerAddressId = photosShareService.volumeOwnerAddressId(userId)
        val resolved = volumeOwnerAddressId
            ?.let { ownerId -> addresses.firstOrNull { it.addressId.id == ownerId && it.enabled && it.keys.isNotEmpty() } }
            ?: addresses.filter { it.enabled && it.keys.isNotEmpty() }.minByOrNull { it.order }
            ?: error("No active address for userId=${userId.id}")
        return unlockAddressAsSigningKey(resolved)
    }

    /**
     * Unlocks the specific [addressId] as an [AddressSigningKey]. Used when a share lists an owner
     * address that may differ from the current primary — the backend rejects invitations whose
     * `InviterAddressID` doesn't match the share's owner.
     */
    suspend fun getAddressSigningKeyById(userId: UserId, addressId: String): AddressSigningKey {
        val addresses = userAddressRepository.getAddresses(userId, false)
        val address = addresses.firstOrNull { it.addressId.id == addressId && it.enabled && it.keys.isNotEmpty() }
            ?: error("No active address with id=$addressId for userId=${userId.id}")
        return unlockAddressAsSigningKey(address)
    }

    private fun unlockAddressAsSigningKey(
        address: me.proton.core.user.domain.entity.UserAddress,
    ): AddressSigningKey {
        // Pick the isPrimary key (the one published in the user's SKL as signer), not just the first
        // active one. After a re-key/recovery, older keys stay active but signing with a secondary
        // makes Drive web's SKL verifier flag SIGNED_NO_VERIFIER → recipient "Failed to decrypt node".
        val activeKeys = address.keys.filter { it.privateKey.isActive && it.privateKey.passphrase != null }
        val key = activeKeys.firstOrNull { it.privateKey.isPrimary }
            ?: activeKeys.firstOrNull()
            ?: error("No active address key with passphrase for ${address.email}")
        // unlock + getPublicKey enter libgojni; serialize. Synchronous, so the lock never suspends.
        val (keyBytes, publicKey) = cryptoLock.withLock {
            val plainPassphrase = cryptoContext.keyStoreCrypto.decrypt(key.privateKey.passphrase!!)
            val unlocked = cryptoContext.pgpCrypto.unlock(key.privateKey.key, plainPassphrase.array)
            plainPassphrase.close()
            val bytes = unlocked.value.copyOf()
            unlocked.close()
            bytes to cryptoContext.pgpCrypto.getPublicKey(key.privateKey.key)
        }
        return AddressSigningKey(
            email = address.email,
            addressId = address.addressId.id,
            unlockedKeyBytes = keyBytes,
            publicKeyArmored = publicKey,
            addressKeyId = key.keyId.id,
        )
    }

    /** Album NodeKey via the same gopenpgp path Drive Android's CreateAlbum uses. */
    fun generateAlbumNodeKey(): NodeKeyMaterial = generateNodeKey()


    fun generateNodeKey(): NodeKeyMaterial = cryptoLock.withLock {
        val rawBytes = cryptoContext.pgpCrypto.generateRandomBytes(32)
        // Clients decrypt NodePassphrase as a UTF-8 string (web TextDecoder), which raw random
        // bytes break — base64-encode to valid UTF-8.
        val passphraseStr   = Base64.encodeToString(rawBytes, Base64.NO_WRAP)
        val passphraseBytes = passphraseStr.toByteArray(Charsets.UTF_8)
        // User ID must be "drive-key@proton.me" — Drive Android hardcodes it, and Drive web's
        // openpgp.js validators are tuned to that canonical packet shape. Any other User ID was the
        // one wire difference that broke the recipient's per-photo decrypt chain.
        val armoredKey = cryptoContext.pgpCrypto.generateNewPrivateKey("drive-key", "proton.me", passphraseBytes)
        val publicKey  = cryptoContext.pgpCrypto.getPublicKey(armoredKey)
        val unlocked   = cryptoContext.pgpCrypto.unlock(armoredKey, passphraseBytes)
        val keyBytes   = unlocked.value.copyOf()
        unlocked.close()
        NodeKeyMaterial(
            armoredPrivateKey = armoredKey,
            passphraseBytes   = passphraseBytes,
            publicKeyArmored  = publicKey,
            unlockedKeyBytes  = keyBytes,
        )
    }

    fun encryptDataToPgpMessage(data: ByteArray, recipientPublicKeyArmored: String): String =
        cryptoLock.withLock { cryptoContext.pgpCrypto.encryptData(data, recipientPublicKeyArmored) }

    /**
     * Encrypt-and-sign into one PGP MESSAGE — for fields Drive verifies against the signatureEmail
     * chain (e.g. the album NodeHashKey). Without the signature Drive web shows "Missing signature
     * for hash key" and refuses to decrypt downstream children.
     */
    fun encryptAndSignDataToPgpMessage(
        data: ByteArray,
        recipientPublicKeyArmored: String,
        signerKeyBytes: ByteArray,
    ): String = cryptoLock.withLock {
        cryptoContext.pgpCrypto.encryptAndSignData(
            data,
            recipientPublicKeyArmored,
            signerKeyBytes,
            null,
        )
    }

    /**
     * Re-wraps a NodePassphrase for a server-side copy: extracts the session key from the source
     * with the old parent key and writes a fresh PKESK to the target parent, plus a detached
     * signature over the raw passphrase. The NodeKey blob is unchanged, so the same passphrase still
     * unlocks it. Used by the "Save shared album to my library" copy pipeline.
     */
    fun reencryptNodePassphraseForCopy(
        sourceNodePassphraseArmored: String,
        sourceParentKeyBytes: ByteArray,
        targetParentPublicKeyArmored: String,
        signerKeyBytes: ByteArray,
    ): ReencryptedNodePassphrase = cryptoLock.withLock {
        // PRESERVE the original SEIPD; only rewrap the PKESK (matches Drive Android's ChangeMessage,
        // which reuses the existing session key). Decrypt-then-encrypt loses framing (literal type
        // t/b, signature inclusion) and the recipient's S2K can't reproduce it → "Message cannot be
        // decrypted". Keeping the SEIPD bytes means the recipient extracts byte-identical plaintext.
        val originalPackets = cryptoContext.pgpCrypto.getEncryptedPackets(sourceNodePassphraseArmored)
        val pkeskPackets = originalPackets.filter { it.type == PacketType.Key }.map { it.packet }
        val seipdPackets = originalPackets.filter { it.type == PacketType.Data }.map { it.packet }
        require(pkeskPackets.isNotEmpty()) { "reencryptNodePassphraseForCopy: source has no PKESK" }
        require(seipdPackets.isNotEmpty()) { "reencryptNodePassphraseForCopy: source has no SEIPD" }
        val sessionKey = pkeskPackets.firstNotNullOfOrNull { pk ->
            runCatching { cryptoContext.pgpCrypto.decryptSessionKey(pk, sourceParentKeyBytes) }.getOrNull()
        } ?: error("reencryptNodePassphraseForCopy: no PKESK decrypted with source key")
        // Detached signature over the RAW passphrase bytes for the separate wire
        // `nodePassphraseSignature` field.
        val rawPassphrase = cryptoContext.pgpCrypto.decryptData(
            sourceNodePassphraseArmored,
            sourceParentKeyBytes,
        )
        val signature = cryptoContext.pgpCrypto.signData(rawPassphrase, signerKeyBytes, null)
        // New PKESK to the target, reusing the session key so the existing SEIPD stays parseable.
        val newPkeskBytes = cryptoContext.pgpCrypto.encryptSessionKey(sessionKey, targetParentPublicKeyArmored)
        // New PKESK + original SEIPD; getArmored adds headers + CRC24 like encryptData would.
        val combined = newPkeskBytes + seipdPackets[0]
        val newPassphraseArmored = cryptoContext.pgpCrypto.getArmored(combined, PGPHeader.Message)
        ReencryptedNodePassphrase(newPassphraseArmored, signature)
    }

    /**
     * Re-targets a link Name to a new parent key the way Drive Android's ChangeMessage does: reuse
     * the OLD name's session key, re-encrypt + embed-sign the plaintext under it, write a fresh
     * PKESK to the new parent. Preserving the session-key lineage matters — Drive web's decryptName
     * rejects fresh-session-key re-wraps (the same failure [reencryptNodePassphraseForCopy] fixed).
     */
    fun changeNameRecipient(
        oldNameArmored: String,
        oldDecryptKeyBytes: ByteArray,
        newPlaintextName: String,
        targetPublicKeyArmored: String,
        signerKeyBytes: ByteArray,
    ): String = cryptoLock.withLock {
        val packets = cryptoContext.pgpCrypto.getEncryptedPackets(oldNameArmored)
        val pkesks = packets.filter { it.type == PacketType.Key }.map { it.packet }
        require(pkesks.isNotEmpty()) { "changeNameRecipient: source has no PKESK" }
        val sessionKey = pkesks.firstNotNullOfOrNull { pk ->
            runCatching { cryptoContext.pgpCrypto.decryptSessionKey(pk, oldDecryptKeyBytes) }.getOrNull()
        } ?: error("changeNameRecipient: no PKESK decrypted with source key")
        val dataPacket = cryptoContext.pgpCrypto.encryptAndSignData(
            newPlaintextName.toByteArray(Charsets.UTF_8),
            sessionKey,
            signerKeyBytes,
            null,
        )
        val keyPacket = cryptoContext.pgpCrypto.encryptSessionKey(sessionKey, targetPublicKeyArmored)
        cryptoContext.pgpCrypto.getArmored(keyPacket + dataPacket, PGPHeader.Message)
    }

    /**
     * Re-wraps a Link Name for the copy pipeline: decrypts with the source parent key, re-encrypts +
     * signs under the target parent. Returns the new ciphertext AND plaintext bytes so the caller can
     * feed the plaintext into the HMAC-SHA256 `hash` field without re-decrypting.
     */
    fun reencryptLinkNameForCopy(
        sourceNameArmored: String,
        sourceParentKeyBytes: ByteArray,
        targetParentPublicKeyArmored: String,
        signerKeyBytes: ByteArray,
    ): ReencryptedName = cryptoLock.withLock {
        val plainBytes = cryptoContext.pgpCrypto.decryptData(sourceNameArmored, sourceParentKeyBytes)
        val newArmored = cryptoContext.pgpCrypto.encryptAndSignData(
            plainBytes,
            targetParentPublicKeyArmored,
            signerKeyBytes,
            null,
        )
        ReencryptedName(newArmored, plainBytes)
    }

    data class ReencryptedNodePassphrase(
        val armoredPassphrase: String,
        val armoredSignature: String,
    )

    data class ReencryptedName(
        val armoredName: String,
        val plainBytes: ByteArray,
    )

    /**
     * Plain detached armored signature. For NodePassphraseSignature / ContentKeyPacketSignature —
     * the Drive API expects a detached signature here, NOT an encrypted one.
     */
    fun signData(data: ByteArray, signerKeyBytes: ByteArray): String =
        cryptoLock.withLock { cryptoContext.pgpCrypto.signData(data, signerKeyBytes, null) }

    /**
     * Signs [data] and encrypts the result to [recipientPublicKeyArmored] (a PGP MESSAGE). For block
     * EncSignature. Do NOT use for NodePassphraseSignature — the API needs a plain detached signature.
     */
    fun signDataEncrypted(
        data: ByteArray,
        signerKeyBytes: ByteArray,
        recipientPublicKeyArmored: String,
    ): String = cryptoLock.withLock {
        cryptoContext.pgpCrypto.signDataEncrypted(
            data, signerKeyBytes, listOf(recipientPublicKeyArmored), null,
        )
    }

    /**
     * Encrypts the link name to [parentPublicKeyArmored] AND signs it. The web client always signs
     * names; without a signature it shows "Missing signature for name".
     */
    fun encryptName(
        name: String,
        parentPublicKeyArmored: String,
        signerKeyBytes: ByteArray,
    ): String = cryptoLock.withLock {
        // Must use encryptAndSignText (text-mode literal, type `t`), NOT encryptAndSignData (binary
        // `b`): Drive web's decryptAndVerifyText refuses to verify across the text/binary boundary
        // even when plaintext round-trips, cascading into a rejected album + broken per-photo decrypt.
        cryptoContext.pgpCrypto.encryptAndSignText(
            name,
            parentPublicKeyArmored,
            signerKeyBytes,
            null,
        )
    }

    fun generateSessionKey(): SessionKey =
        cryptoLock.withLock { cryptoContext.pgpCrypto.generateNewSessionKey() }

    fun encryptSessionKeyToNode(sessionKey: SessionKey, nodePublicKeyArmored: String): ByteArray =
        cryptoLock.withLock { cryptoContext.pgpCrypto.encryptSessionKey(sessionKey, nodePublicKeyArmored) }

    // NO_WRAP (no newlines): the Proton server's PHP Base64::decode() rejects the whitespace
    // pgpCrypto.getBase64Encoded() may emit.
    fun base64Encode(bytes: ByteArray): String =
        Base64.encodeToString(bytes, Base64.NO_WRAP)

    /** Encrypts block bytes with the session key. Returns encrypted binary PGP message. */
    fun encryptBlock(blockData: ByteArray, sessionKey: SessionKey): ByteArray =
        cryptoLock.withLock { cryptoContext.pgpCrypto.encryptData(blockData, sessionKey) }

    /** Signs the plaintext block, encrypted to the node key — server-verifiable yet key-holder-only. */
    fun signBlockEncrypted(
        blockPlaintext: ByteArray,
        signerKeyBytes: ByteArray,
        nodePublicKeyArmored: String,
    ): String = cryptoLock.withLock {
        cryptoContext.pgpCrypto.signDataEncrypted(
            blockPlaintext, signerKeyBytes, listOf(nodePublicKeyArmored), null,
        )
    }

    /** Returns the raw 32-byte SHA-256 digest of [data]. */
    fun sha256(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(data)

    /** Hex-encoded SHA-256. */
    fun sha256Hex(data: ByteArray): String = sha256(data).joinToString("") { "%02x".format(it) }

    /**
     * HMAC-SHA256 of the plaintext name with the raw NodeHashKey bytes — the Hash field in album /
     * link creation, letting the server look up links by name without learning the plaintext.
     */
    fun computeNameHash(plaintextName: String, nodeHashKeyBytes: ByteArray): String {
        val mac = javax.crypto.Mac.getInstance("HmacSHA256")
        mac.init(javax.crypto.spec.SecretKeySpec(nodeHashKeyBytes, "HmacSHA256"))
        val hmac = mac.doFinal(plaintextName.toByteArray(Charsets.UTF_8))
        return hmac.joinToString("") { "%02x".format(it) }
    }

    /**
     * Signs the revision manifest — the raw SHA-256 bytes of every block (32 × count) concatenated
     * in block-index order, matching the SDK's RevisionManifestSignatureManager.
     */
    fun signManifest(blockHashBytes: List<ByteArray>, signerKeyBytes: ByteArray): String {
        val manifest = blockHashBytes.fold(ByteArray(0)) { acc, hash -> acc + hash }
        // signData enters libgojni; serialize it. Assembly above is pure CPU, kept outside the lock.
        return cryptoLock.withLock { cryptoContext.pgpCrypto.signData(manifest, signerKeyBytes, null) }
    }

    /**
     * Album/folder XAttr — emits ONLY Common.ModificationTime, matching Drive Android's no-arg
     * createXAttr(). Adding Size / BlockSizes / Media breaks the album trust chain so every recipient
     * fails to decrypt every photo.
     */
    fun encryptAlbumXAttr(
        modificationTimeIso: String,
        nodePublicKeyArmored: String,
        signerKeyBytes: ByteArray,
    ): String {
        val json = """{"Common":{"ModificationTime":"$modificationTimeIso"}}"""
        return cryptoLock.withLock {
            cryptoContext.pgpCrypto.encryptAndSignTextWithCompression(
                json,
                nodePublicKeyArmored,
                signerKeyBytes,
                null,
            )
        }
    }

    /**
     * Photo/video xAttr. Mirrors Drive Android's `XAttrPhotoAdditionalMetadata` field-for-field:
     * Common (ModificationTime/Size/BlockSizes/Digests), Media (Width/Height/Duration), optional
     * Location (Latitude/Longitude) and Camera (CaptureTime/Device/Orientation/SubjectCoordinates).
     *
     * [width]/[height] must already be DISPLAY dimensions (caller swaps for rotated media).
     * Camera/Location are gated by the caller against the strip config so xAttr never re-leaks a
     * field the file itself had erased.
     */
    fun encryptXAttr(
        modificationTimeIso: String,
        sizeBytes: Long,
        blockSizes: List<Long>,
        width: Int,
        height: Int,
        durationMillis: Long,
        nodePublicKeyArmored: String,
        signerKeyBytes: ByteArray,
        sha1HexDigest: String? = null,
        latitude: Double? = null,
        longitude: Double? = null,
        cameraOrientation: Int? = null,
        cameraCaptureTimeIso: String? = null,
        cameraDevice: String? = null,
        subjectCoordinates: IntArray? = null,
    ): String {
        val blockSizesJson = blockSizes.joinToString(",", "[", "]")
        // Common.Digests.SHA1 is required — Drive rejects payloads omitting it ("Cannot build photo
        // payload without a content hash"), and it's the same digest the wire ContentHash HMAC uses.
        val digestsClause = if (!sha1HexDigest.isNullOrEmpty()) {
            ""","Digests":{"SHA1":"$sha1HexDigest"}"""
        } else ""
        // Drive's duration is seconds; MediaStore.DURATION is milliseconds. Convert, or every video
        // renders 1000× (a 7 s clip shows as "2:03:45" on the Drive web grid).
        val durationSeconds = durationMillis / 1000.0
        val durationLiteral = if (durationMillis == 0L) "0" else "%.3f".format(java.util.Locale.US, durationSeconds).trimEnd('0').trimEnd('.')
        // Location block — only when the caller supplied coords (i.e. GPS not stripped).
        val locationClause = if (latitude != null && longitude != null) {
            val lat = "%s".format(java.util.Locale.US, latitude)
            val lon = "%s".format(java.util.Locale.US, longitude)
            ""","Location":{"Latitude":$lat,"Longitude":$lon}"""
        } else ""
        // Camera block — Orientation is the raw EXIF orientation int (1..8), NOT degrees, matching
        // XAttr.Camera. Device/CaptureTime/SubjectCoordinates are each optional.
        val cameraClause = if (cameraOrientation != null || cameraCaptureTimeIso != null ||
            cameraDevice != null || subjectCoordinates != null) {
            buildString {
                append(""","Camera":{""")
                val parts = mutableListOf<String>()
                cameraCaptureTimeIso?.let { parts.add(""""CaptureTime":"${jsonEscape(it)}"""") }
                cameraDevice?.let { parts.add(""""Device":"${jsonEscape(it)}"""") }
                cameraOrientation?.let { parts.add(""""Orientation":$it""") }
                subjectCoordinates?.takeIf { it.size == 4 }?.let { rect ->
                    parts.add(
                        """"SubjectCoordinates":{"Top":${rect[0]},"Left":${rect[1]},"Bottom":${rect[2]},"Right":${rect[3]}}"""
                    )
                }
                append(parts.joinToString(","))
                append("}")
            }
        } else ""
        val json = buildString {
            append("""{"Common":{"ModificationTime":"$modificationTimeIso","Size":$sizeBytes,"BlockSizes":$blockSizesJson""")
            append(digestsClause)
            append("""},"Media":{"Width":$width,"Height":$height,"Duration":$durationLiteral}""")
            append(locationClause)
            append(cameraClause)
            append("}")
        }
        // Must use encryptAndSignTextWithCompression (text-mode + ZIP-deflate), NOT
        // encryptAndSignData: Drive web verifies xAttr via decryptAndVerifyText, and a binary `b`
        // blob drops to "unknown signer", tainting the album trust chain → "Failed to decrypt node".
        return cryptoContext.pgpCrypto.encryptAndSignTextWithCompression(
            json,
            nodePublicKeyArmored,
            signerKeyBytes,
            null,
        )
    }

    /** Escapes a string for embedding in a manually-built JSON literal (Device names can carry
     *  quotes/backslashes). Covers the control chars that would otherwise break xAttr parsing. */
    private fun jsonEscape(s: String): String = buildString {
        for (c in s) when (c) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (c < ' ') append("\\u%04x".format(c.code)) else append(c)
        }
    }

    /**
     * Produces the `SessionKeySignature` body field for `POST /shares/invitations/{id}/accept`.
     *
     * 1. Decrypt the share session key from [keyPacketBase64] (PKESK encrypted to the invitee's address key).
     * 2. Sign the raw session-key bytes with the invitee's primary address private key (detached).
     * 3. Strip the ASCII armor and base64-encode the binary signature (NO_WRAP).
     */
    suspend fun signInvitationKeyPacket(
        userId: UserId,
        keyPacketBase64: String,
        inviteeEmail: String,
    ): String {
        val keyPacketBytes = Base64.decode(keyPacketBase64, Base64.DEFAULT)
        // Resolve by the EXACT invitee email, not brute force: decryptSessionKey can return garbage
        // bytes (not throw) on a key mismatch, producing a valid-looking but wrong signature that
        // leaves the membership unable to decrypt anything in the album.
        val addresses = userAddressRepository.getAddresses(userId, false)
            .filter { it.enabled && it.keys.isNotEmpty() }
        val invitee = inviteeEmail.lowercase().trim()
        val address = addresses.firstOrNull { it.email.lowercase() == invitee }
            ?: error("No active address matching invitee email=$inviteeEmail for userId=${userId.id}")
        val signingKey = unlockAddressAsSigningKey(address)
        // decryptSessionKey + signData + getUnarmored enter libgojni; serialize. Suspend work
        // (address fetch, unlock) already ran above, so the lock never suspends.
        val unarmored = cryptoLock.withLock {
            val sessionKey = cryptoContext.pgpCrypto.decryptSessionKey(keyPacketBytes, signingKey.unlockedKeyBytes)
            // MEMBER context is required — without it the server rejects with "wrong context".
            val armoredSignature = cryptoContext.pgpCrypto.signData(
                sessionKey.key,
                signingKey.unlockedKeyBytes,
                DriveSignatureContexts.MEMBER,
            )
            cryptoContext.pgpCrypto.getUnarmored(armoredSignature)
        }
        Log.d(TAG, "signInvitationKeyPacket: matched invitee addressId=${address.addressId.id}")
        return Base64.encodeToString(unarmored, Base64.NO_WRAP)
    }

    /**
     * Re-encrypts the share [sessionKey] to the invitee's public key and detached-signs it, returning
     * the (base64 keyPacket, base64 signature) pair `/invitations` expects. Critical: the inviter
     * signs the encrypted PKESK bytes, NOT the raw session key — signing the session key gets the
     * invitation rejected as "outdated version of the app".
     */
    fun encryptAndSignSessionKeyForInvitee(
        sessionKey: SessionKey,
        inviteePublicKeyArmored: String,
        signerKeyBytes: ByteArray,
    ): Pair<String, String> = cryptoLock.withLock {
        // encryptSessionKey + signData + getUnarmored enter libgojni; serialize. Synchronous.
        val keyPacket = try {
            cryptoContext.pgpCrypto.encryptSessionKey(sessionKey, inviteePublicKeyArmored)
        } catch (t: Throwable) {
            // Log the session-key size (expect 32 for AES-256) so a wrong-material bug is diagnosable.
            Log.e(TAG, "encryptSessionKey to invitee failed: sessionKeyBytes=${sessionKey.key.size} cause=${t.message}", t)
            throw t
        }
        val keyPacketBase64 = Base64.encodeToString(keyPacket, Base64.NO_WRAP)
        // INVITER context; sign the encrypted key packet bytes (see KDoc).
        val armoredSig = cryptoContext.pgpCrypto.signData(
            keyPacket,
            signerKeyBytes,
            DriveSignatureContexts.INVITER,
        )
        val unarmoredSig = cryptoContext.pgpCrypto.getUnarmored(armoredSig)
        keyPacketBase64 to Base64.encodeToString(unarmoredSig, Base64.NO_WRAP)
    }

    /**
     * Crypto material from [generateNewShareForLink]. [rawPassphraseBytes] is the unlocked share-key
     * passphrase, kept in memory to build invitation key packets without a round-trip.
     */
    data class GeneratedShare(
        val shareKeyArmored: String,
        val sharePassphraseArmored: String,
        val sharePassphraseSignature: String,
        val passphraseKeyPacketBase64: String,
        val nameEncryptedArmored: String,
        val nameKeyPacketBase64: String,
        val sharePublicKey: String,
        val rawPassphraseBytes: ByteArray,
        /** In-memory session key for [sharePassphraseArmored] — reuse when minting a URL right after
         *  createVolumeShare instead of round-tripping [decryptSharePassphraseSessionKey]. */
        val passphraseSessionKey: SessionKey,
    )

    /**
     * Builds the PGP artefacts `POST /drive/volumes/{volumeId}/shares` expects (ShareKey,
     * SharePassphrase + signature, PassphraseKeyPacket, Name, NameKeyPacket), mirroring
     * android-drive's GenerateShareKey + GenerateSharePassphrase + name encryption.
     */
    fun generateNewShareForLink(
        addressPublicKeyArmored: String,
        signerKeyBytes: ByteArray,
        plaintextName: String,
    ): GeneratedShare = cryptoLock.withLock {
        // 1. New key + base64 passphrase (round-trips as UTF-8, same as node passphrases).
        val rawPassphrase = cryptoContext.pgpCrypto.generateRandomBytes(32)
        val passphraseStr = Base64.encodeToString(rawPassphrase, Base64.NO_WRAP)
        val passphraseBytes = passphraseStr.toByteArray(Charsets.UTF_8)
        val shareKeyArmored = cryptoContext.pgpCrypto.generateNewPrivateKey("share", "proton.me", passphraseBytes)
        val sharePubKey = cryptoContext.pgpCrypto.getPublicKey(shareKeyArmored)

        // 2. SharePassphrase = PKESK + SEIPD; split so the PKESK can also be exposed alone.
        val passphraseSessionKey = cryptoContext.pgpCrypto.generateNewSessionKey()
        val passphraseKeyPacketBytes =
            cryptoContext.pgpCrypto.encryptSessionKey(passphraseSessionKey, addressPublicKeyArmored)
        val passphraseSeipd =
            cryptoContext.pgpCrypto.encryptData(passphraseBytes, passphraseSessionKey)
        val sharePassphraseArmored = cryptoContext.pgpCrypto.getArmored(
            passphraseKeyPacketBytes + passphraseSeipd, PGPHeader.Message,
        )
        val sharePassphraseSignature =
            cryptoContext.pgpCrypto.signData(passphraseBytes, signerKeyBytes, null)

        // 3. Name encryption — same shape as the passphrase; matching PKESK → NameKeyPacket.
        val nameSessionKey = cryptoContext.pgpCrypto.generateNewSessionKey()
        val nameKeyPacketBytes =
            cryptoContext.pgpCrypto.encryptSessionKey(nameSessionKey, addressPublicKeyArmored)
        val nameSeipd = cryptoContext.pgpCrypto.encryptData(
            plaintextName.toByteArray(Charsets.UTF_8), nameSessionKey,
        )
        val nameEncryptedArmored = cryptoContext.pgpCrypto.getArmored(
            nameKeyPacketBytes + nameSeipd, PGPHeader.Message,
        )

        GeneratedShare(
            shareKeyArmored = shareKeyArmored,
            sharePassphraseArmored = sharePassphraseArmored,
            sharePassphraseSignature = sharePassphraseSignature,
            passphraseKeyPacketBase64 = Base64.encodeToString(passphraseKeyPacketBytes, Base64.NO_WRAP),
            nameEncryptedArmored = nameEncryptedArmored,
            nameKeyPacketBase64 = Base64.encodeToString(nameKeyPacketBytes, Base64.NO_WRAP),
            sharePublicKey = sharePubKey,
            rawPassphraseBytes = passphraseBytes,
            passphraseSessionKey = passphraseSessionKey,
        )
    }

    /**
     * Material from [generateShareForAlbum], mirroring Drive Android's ShareInfo. The key-packet
     * fields carry the ALBUM's session keys re-encrypted under the new share key, NOT the share's own
     * PKESKs — the latter is what got [generateNewShareForLink] rejected (code 2001).
     */
    data class GeneratedAlbumShare(
        val shareKeyArmored: String,
        val sharePassphraseArmored: String,
        val sharePassphraseSignature: String,
        /** Album.nodePassphrase session key re-encrypted under [shareKeyArmored]. */
        val passphraseKeyPacketBase64: String,
        /** Album.name session key re-encrypted under [shareKeyArmored]. */
        val nameKeyPacketBase64: String,
        val sharePublicKey: String,
        val rawPassphraseBytes: ByteArray,
        /** In-memory session key for [sharePassphraseArmored] — reused when minting URLs. */
        val passphraseSessionKey: SessionKey,
    )

    /**
     * Builds the album-share-create artefacts, mirroring Drive Android's CreateShareInfo +
     * ReencryptKeyPacket. Differs from [generateNewShareForLink]: the SharePassphrase is
     * multi-recipient (album node + address), and the key packets are the ALBUM link's existing
     * nodePassphrase/name session keys re-encrypted under the new share key — without that link the
     * backend rejects share-creation (code 2001) as structurally disconnected.
     */
    suspend fun generateShareForAlbum(
        albumNodeKeyArmored: String,
        albumNodePassphraseArmored: String,
        albumNameArmored: String,
        parentLinkKeyBytes: ByteArray,
        addressPublicKeyArmored: String,
        signerKeyBytes: ByteArray,
    ): GeneratedAlbumShare = cryptoLock.withLock {
        // 1. Album public key — the share passphrase is encrypted to both it (member ops) and the
        //    user's address (owner).
        val albumPublicKeyArmored = cryptoContext.pgpCrypto.getPublicKey(albumNodeKeyArmored)

        // 2. Share key + base64 passphrase (round-trips as UTF-8, same as the node-key path).
        val rawPassphrase = cryptoContext.pgpCrypto.generateRandomBytes(32)
        val passphraseAscii = Base64.encodeToString(rawPassphrase, Base64.NO_WRAP)
        val passphraseBytes = passphraseAscii.toByteArray(Charsets.UTF_8)
        val shareKeyArmored = cryptoContext.pgpCrypto.generateNewPrivateKey("share", "proton.me", passphraseBytes)
        val sharePubKey = cryptoContext.pgpCrypto.getPublicKey(shareKeyArmored)

        // 3. Multi-recipient SharePassphrase: PKESK(albumPub) + PKESK(addressPub) + SEIPD, so either
        //    the album node key or the address key recovers the passphrase.
        val sharePassphraseSessionKey = cryptoContext.pgpCrypto.generateNewSessionKey()
        val pkeskForAlbum =
            cryptoContext.pgpCrypto.encryptSessionKey(sharePassphraseSessionKey, albumPublicKeyArmored)
        val pkeskForAddress =
            cryptoContext.pgpCrypto.encryptSessionKey(sharePassphraseSessionKey, addressPublicKeyArmored)
        val sharePassphraseSeipd =
            cryptoContext.pgpCrypto.encryptData(passphraseBytes, sharePassphraseSessionKey)
        val sharePassphraseArmored = cryptoContext.pgpCrypto.getArmored(
            pkeskForAlbum + pkeskForAddress + sharePassphraseSeipd,
            PGPHeader.Message,
        )

        // 4. Detached signature over the raw passphrase bytes, no context.
        val sharePassphraseSignature =
            cryptoContext.pgpCrypto.signData(passphraseBytes, signerKeyBytes, null)

        // 5. Re-encrypt album.nodePassphrase session key from parent→share.
        val passphraseKeyPacketBase64 = reencryptKeyPacketForAdditionalRecipient(
            originalMessageArmored = albumNodePassphraseArmored,
            unlockedSourceKeyBytes = parentLinkKeyBytes,
            recipientPublicKeyArmored = sharePubKey,
            diagnosticLabel = "passphraseKeyPacket",
        )

        // 6. Re-encrypt album.name session key from parent→share, same pattern.
        val nameKeyPacketBase64 = reencryptKeyPacketForAdditionalRecipient(
            originalMessageArmored = albumNameArmored,
            unlockedSourceKeyBytes = parentLinkKeyBytes,
            recipientPublicKeyArmored = sharePubKey,
            diagnosticLabel = "nameKeyPacket",
        )

        GeneratedAlbumShare(
            shareKeyArmored = shareKeyArmored,
            sharePassphraseArmored = sharePassphraseArmored,
            sharePassphraseSignature = sharePassphraseSignature,
            passphraseKeyPacketBase64 = passphraseKeyPacketBase64,
            nameKeyPacketBase64 = nameKeyPacketBase64,
            sharePublicKey = sharePubKey,
            rawPassphraseBytes = passphraseBytes,
            passphraseSessionKey = sharePassphraseSessionKey,
        )
    }

    /** Diagnostic: decodes the recipient KeyID from a raw PKESK packet ("?" if not a v3 PKESK). */
    private fun pkeskKeyIdHex(packetBytes: ByteArray): String {
        // New-format header: 0xC1, 1-byte length, then version + KeyID(8) + algo + material.
        return runCatching {
            var idx = 0
            if (packetBytes[0].toInt() and 0xc0 != 0xc0) return@runCatching "?"
            idx = 1
            val l1 = packetBytes[idx].toInt() and 0xff
            idx += if (l1 < 192) 1 else if (l1 < 224) 2 else 5
            val ver = packetBytes[idx].toInt()
            if (ver != 3) return@runCatching "v$ver:?"
            val keyId = packetBytes.copyOfRange(idx + 1, idx + 9)
            keyId.joinToString("") { "%02X".format(it) }
        }.getOrDefault("?")
    }

    /**
     * Diagnostic: hex KeyIDs of every public key in an armored block (LSB 8 bytes of the SHA-1
     * fingerprint over each packet's canonical public portion).
     */
    private fun publicKeyIdHexes(armoredPublicKey: String): String {
        return runCatching {
            // Strip armor by walking lines and keeping base64-only ones — gopenpgp's output
            // sometimes omits the Version header, so a "double-newline after headers" regex misses it.
            val lines = armoredPublicKey.lines()
            val bodyLines = mutableListOf<String>()
            var inBody = false
            for (line in lines) {
                val l = line.trim()
                if (l.startsWith("-----BEGIN")) { inBody = false; continue }
                if (l.startsWith("-----END")) break
                if (!inBody) {
                    if (l.isEmpty()) { inBody = true; continue }
                    if (l.contains(":")) continue // header line e.g. "Version: ProtonMail"
                    // No header section at all — already in body.
                    inBody = true
                }
                if (l.startsWith("=") || l.isEmpty()) continue
                if (l.all { it.isLetterOrDigit() || it == '+' || it == '/' || it == '=' }) {
                    bodyLines += l
                }
            }
            val body = bodyLines.joinToString("")
            if (body.isEmpty()) return@runCatching "?"
            val raw = Base64.decode(body, Base64.DEFAULT)
            val out = mutableListOf<String>()
            var idx = 0
            while (idx < raw.size) {
                val tag = raw[idx].toInt() and 0xff
                if (tag and 0x40 == 0) break
                val ptype = tag and 0x3f
                idx++
                val l1 = raw[idx].toInt() and 0xff
                val length = when {
                    l1 < 192 -> { idx++; l1 }
                    l1 < 224 -> { val ln = ((l1 - 192) shl 8) + (raw[idx + 1].toInt() and 0xff) + 192; idx += 2; ln }
                    l1 == 255 -> {
                        val ln = ((raw[idx + 1].toInt() and 0xff) shl 24) or
                            ((raw[idx + 2].toInt() and 0xff) shl 16) or
                            ((raw[idx + 3].toInt() and 0xff) shl 8) or
                            (raw[idx + 4].toInt() and 0xff)
                        idx += 5
                        ln
                    }
                    else -> break
                }
                if (ptype == 6 || ptype == 14) {
                    val pkt = raw.copyOfRange(idx, idx + length)
                    val ver = pkt[0].toInt()
                    if (ver == 4) {
                        val algo = pkt[5].toInt() and 0xff
                        val nMpis = mapOf(18 to 3, 22 to 3, 1 to 2, 16 to 2, 17 to 4, 19 to 3)[algo] ?: 0
                        var mi = 6
                        repeat(nMpis) {
                            val bits = ((pkt[mi].toInt() and 0xff) shl 8) or (pkt[mi + 1].toInt() and 0xff)
                            val bl = (bits + 7) / 8
                            mi += 2 + bl
                        }
                        val pubPortion = pkt.copyOfRange(0, mi)
                        val md = java.security.MessageDigest.getInstance("SHA-1")
                        md.update(0x99.toByte())
                        md.update((pubPortion.size shr 8).toByte())
                        md.update(pubPortion.size.toByte())
                        md.update(pubPortion)
                        val fp = md.digest().joinToString("") { "%02X".format(it) }
                        out += "${if (ptype == 6) "PK" else "SK"}=${fp.substring(fp.length - 16)}"
                    }
                }
                idx += length
            }
            out.joinToString(",")
        }.getOrDefault("?")
    }

    /** Yanks the first PKESK / Key packet bytes out of an armored MESSAGE. */
    private fun extractPkeskBytesOrThrow(armoredMessage: String): ByteArray {
        val packets = cryptoContext.pgpCrypto.getEncryptedPackets(armoredMessage)
        return packets.firstOrNull { it.type == PacketType.Key }?.packet
            ?: error("Armored message has no PKESK / Key packet")
    }

    /**
     * Re-encrypts the session key of [originalMessageArmored] (recovered with [unlockedSourceKeyBytes])
     * to [recipientPublicKeyArmored] and returns just the new PKESK packet as base64, for
     * CreateShareRequest's passphraseKeyPacket / nameKeyPacket.
     */
    private fun reencryptKeyPacketForAdditionalRecipient(
        originalMessageArmored: String,
        unlockedSourceKeyBytes: ByteArray,
        recipientPublicKeyArmored: String,
        diagnosticLabel: String,
    ): String {
        // Plain decryptSessionKey + encryptSessionKey, matching Drive Android's ReencryptKeyPacketImpl
        // byte-for-byte. The earlier encryptMessageToAdditionalKey variant began failing backend
        // verification (code 200501) once share-creation checks tightened.
        val originalPackets = cryptoContext.pgpCrypto.getEncryptedPackets(originalMessageArmored)
        val pkeskPackets = originalPackets.filter { it.type == PacketType.Key }.map { it.packet }
        require(pkeskPackets.isNotEmpty()) {
            "$diagnosticLabel: original armored message has no PKESK packets"
        }

        // The source key may match any of the multi-recipient PKESKs; try each, first hit wins.
        val sessionKey = pkeskPackets.firstNotNullOfOrNull { packet ->
            runCatching { cryptoContext.pgpCrypto.decryptSessionKey(packet, unlockedSourceKeyBytes) }.getOrNull()
        } ?: error("$diagnosticLabel: none of ${pkeskPackets.size} PKESK packets decrypted with the source key")

        val newPkeskBytes = cryptoContext.pgpCrypto.encryptSessionKey(sessionKey, recipientPublicKeyArmored)
        val targetKeyIdHex = pkeskKeyIdHex(newPkeskBytes)
        val recipientKeyIds = publicKeyIdHexes(recipientPublicKeyArmored)
        Log.d(
            TAG,
            "$diagnosticLabel: new PKESK target=$targetKeyIdHex  recipientPubKeyIDs=$recipientKeyIds  (simple decrypt+encrypt path)",
        )
        return Base64.encodeToString(newPkeskBytes, Base64.NO_WRAP)
    }

    /**
     * Crypto bundle for `POST drive/shares/{shareId}/urls` — an SRP-authenticated public link that
     * lets a recipient unlock the share session key from the URL + an embedded password. permissions=4
     * (read-only), flags=2 (random URL password) match the official client's newly-minted URLs.
     */
    data class ShareUrlCryptoPackage(
        val permissions: Long = 4L,
        val flags: Long = 2L,
        val urlPasswordSalt: String,
        val sharePasswordSalt: String,
        val srpVerifier: String,
        val srpModulusId: String,
        val sharePassphraseKeyPacketBase64: String,
        val encryptedUrlPassword: String,
        /** Plaintext URL password (12 base64 chars) for the link's `#fragment`; only
         *  [encryptedUrlPassword] is sent to the server. */
        val urlPassword: String,
    )

    /**
     * Mirrors android-drive's CreateShareUrlInfo: mint a random URL password, compute an SRP verifier
     * from it (caller supplies [modulus]/[modulusId]), symmetrically encrypt [shareSessionKey] under a
     * salted passphrase the visitor regenerates client-side, and PGP-encrypt the password to the
     * creator's address for a "show password" echo. With [permissions]=4 / [flags]=2 this fills
     * CreateShareUrlRequest.
     */
    suspend fun buildShareUrlCryptoPackage(
        addressPublicKeyArmored: String,
        shareSessionKey: SessionKey,
        modulus: String,
        modulusId: String,
    ): ShareUrlCryptoPackage {
        // SRP verifier is suspend (Go via libgojni), so run it outside the non-suspending cryptoLock;
        // only the PGP portion below is locked.
        val urlPasswordBytes = generateRandomUrlPassword().toByteArray(Charsets.UTF_8)
        val auth = cryptoContext.srpCrypto.calculatePasswordVerifier(
            username = "",
            password = urlPasswordBytes,
            modulusId = modulusId,
            modulus = modulus,
        )
        return cryptoLock.withLock { buildPgpHalfOfShareUrlPackage(addressPublicKeyArmored, shareSessionKey, urlPasswordBytes, auth) }
    }

    /**
     * Random URL password: 9 raw bytes → 12 padding-free Base64 chars (matches Drive's
     * urlPassphraseSize = 9.bytes). For a new link or when clearing a custom password back to
     * "anyone with the link". Rides in the `#fragment`; only an encrypted echo reaches the server.
     */
    suspend fun generateRandomUrlPassword(): String {
        val raw = cryptoLock.withLock { cryptoContext.pgpCrypto.generateRandomBytes(9) }
        return Base64.encodeToString(raw, Base64.NO_WRAP)
    }

    /**
     * Like [buildShareUrlCryptoPackage] but from a CHOSEN password — the package a
     * `PUT .../urls/{urlID}` needs to switch a link's password. [flags] records the type
     * (1 = custom, recipient types it; 2 = random, carried in the fragment).
     */
    suspend fun buildShareUrlCryptoPackageFromPassword(
        addressPublicKeyArmored: String,
        shareSessionKey: SessionKey,
        modulus: String,
        modulusId: String,
        password: String,
        flags: Long,
    ): ShareUrlCryptoPackage {
        val urlPasswordBytes = password.toByteArray(Charsets.UTF_8)
        // SRP verifier is suspend (Go via libgojni) so it runs outside the non-suspending
        // cryptoLock; the PGP-only portion below is serialized under the lock.
        val auth = cryptoContext.srpCrypto.calculatePasswordVerifier(
            username = "",
            password = urlPasswordBytes,
            modulusId = modulusId,
            modulus = modulus,
        )
        return cryptoLock.withLock {
            buildPgpHalfOfShareUrlPackage(addressPublicKeyArmored, shareSessionKey, urlPasswordBytes, auth, flags)
        }
    }

    /**
     * Internal helper: the PGP-only portion of [buildShareUrlCryptoPackage], runs under
     * [cryptoLock] so the libgojni calls serialize against everything else in this class.
     */
    private fun buildPgpHalfOfShareUrlPackage(
        addressPublicKeyArmored: String,
        shareSessionKey: SessionKey,
        urlPasswordBytes: ByteArray,
        auth: me.proton.core.crypto.common.srp.Auth,
        flags: Long = 2L,
    ): ShareUrlCryptoPackage {
        val sharePasswordSalt = cryptoContext.pgpCrypto.generateNewKeySalt()
        val saltedPassword = cryptoContext.pgpCrypto.getPassphrase(urlPasswordBytes, sharePasswordSalt)
        val sharePassphraseKeyPacket =
            cryptoContext.pgpCrypto.encryptSessionKeyWithPassword(shareSessionKey, saltedPassword)
        val sharePassphraseKeyPacketB64 =
            Base64.encodeToString(sharePassphraseKeyPacket, Base64.NO_WRAP)
        val encryptedUrlPassword =
            cryptoContext.pgpCrypto.encryptData(urlPasswordBytes, addressPublicKeyArmored)
        return ShareUrlCryptoPackage(
            flags = flags,
            urlPasswordSalt = auth.salt,
            sharePasswordSalt = sharePasswordSalt,
            srpVerifier = auth.verifier,
            srpModulusId = auth.modulusId,
            sharePassphraseKeyPacketBase64 = sharePassphraseKeyPacketB64,
            encryptedUrlPassword = encryptedUrlPassword,
            // The exact bytes the SRP verifier above was built from — the recipient's URL
            // fragment (random links) or typed password (custom links) must match it so
            // their SRP auth succeeds.
            urlPassword = String(urlPasswordBytes, Charsets.UTF_8),
        )
    }

    /**
     * Extracts the PKESK from an armored `SharePassphrase` message and decrypts it to a
     * raw [SessionKey] using any of the user's enabled address keys. Mirrors the
     * `GetSessionKeyFromEncryptedMessage` use case in the official Drive Android sources.
     *
     * Used when minting a public URL for a share that already exists (so we don't have the
     * in-memory session key from [generateNewShareForLink]). For newly minted shares, pass
     * the in-memory session key into [buildShareUrlCryptoPackage] directly — there's no
     * benefit to round-tripping it through this decrypt call.
     */
    suspend fun decryptSharePassphraseSessionKey(
        userId: UserId,
        sharePassphraseArmored: String,
    ): SessionKey {
        // Fetch addresses BEFORE acquiring the non-suspending cryptoLock — getAddresses
        // is a suspend call that may block on Room I/O, and holding cryptoLock across a
        // suspension point both starves other libgojni callers and trips the static
        // checker (function inside a critical section).
        val addresses = userAddressRepository.getAddresses(userId, false)
            .filter { it.enabled && it.keys.isNotEmpty() }
            .sortedBy { it.order }
        if (addresses.isEmpty()) error("No active address for userId=${userId.id}")

        // Pull just the PKESK out of the armored MESSAGE — we don't need the SEIPD
        // payload (the passphrase plaintext) for URL minting, only the session key.
        val keyPacketBytes = cryptoLock.withLock {
            val packets = cryptoContext.pgpCrypto.getEncryptedPackets(sharePassphraseArmored)
            packets.firstOrNull { it.type == PacketType.Key }?.packet
        } ?: error("SharePassphrase has no PKESK / Key packet — cannot extract session key")

        var lastError: Throwable? = null
        for (address in addresses) {
            // useKeys enters libgojni; serialize each attempt. Lock taken per iteration so it's
            // never held across the loop control flow, and addresses were fetched before the loop.
            val attempt = runCatching {
                cryptoLock.withLock { address.useKeys(cryptoContext) { decryptSessionKey(keyPacketBytes) } }
            }
            if (attempt.isSuccess) return attempt.getOrThrow()
            lastError = attempt.exceptionOrNull()
        }
        throw lastError ?: error("Could not decrypt share passphrase key packet with any address key")
    }

    /**
     * Decrypts the random URL password of a public share-URL back to plaintext.
     *
     * The backend stores it as an armored PGP message encrypted to the creator's own
     * address public key (the `Password` field of a `/urls` item). We decrypt it with the
     * user's ADDRESS private key and return the plaintext, which the caller appends to the
     * link as `#<password>` so an "anyone with the link" share opens without a prompt.
     *
     * Mirrors the official Drive `DecryptUrlPassword` use case (`decryptData(...)
     * .toString(UTF_8)`). Uses the same address-key `decryptData` primitive as
     * [decryptExternalShareKey] / [decryptSharePassphraseSessionKey]: tries every enabled
     * address in primary-first order under [cryptoLock], since the message may be encrypted
     * to any of the user's addresses. Our password is exactly 12 chars, so the whole
     * decrypted string is the fragment (no fixed-size prefix to trim).
     */
    suspend fun decryptUrlPassword(
        userId: UserId,
        encryptedUrlPasswordArmored: String,
    ): String {
        // Fetch addresses BEFORE acquiring the non-suspending cryptoLock — getAddresses is a
        // suspend Room call, and holding cryptoLock across a suspension starves libgojni.
        val addresses = userAddressRepository.getAddresses(userId, false)
            .filter { it.enabled && it.keys.isNotEmpty() }
            .sortedBy { it.order }
        if (addresses.isEmpty()) error("No active address for userId=${userId.id}")
        var lastError: Throwable? = null
        for (address in addresses) {
            // useKeys enters libgojni; serialize each attempt. Lock taken per iteration so it's
            // never held across loop control flow; addresses fetched above.
            val attempt = runCatching {
                cryptoLock.withLock { address.useKeys(cryptoContext) { decryptData(encryptedUrlPasswordArmored) } }
            }
            if (attempt.isSuccess) return String(attempt.getOrThrow(), Charsets.UTF_8)
            lastError = attempt.exceptionOrNull()
        }
        throw lastError ?: error("Could not decrypt URL password with any address key")
    }

    /**
     * Decrypts an external share's key without touching the internal cache.
     *
     * Tries every active address in primary-first order — same rationale as
     * [getOrDecryptShareKey]: the share's passphrase may be encrypted to any of the user's
     * addresses (especially for accounts with aliases or address-history), and assuming
     * primary-only produces the same "Cannot decrypt with provided Key list" failure mode
     * we hit on 2FA + multi-address accounts.
     */
    suspend fun decryptExternalShareKey(
        userId: UserId,
        shareKeyArmored: String,
        sharePassphraseArmored: String,
    ): ByteArray {
        val addresses = userAddressRepository.getAddresses(userId, false)
            .filter { it.enabled && it.keys.isNotEmpty() }
            .sortedBy { it.order }
        if (addresses.isEmpty()) error("No active address for userId=${userId.id}")
        var lastError: Throwable? = null
        for (address in addresses) {
            // useKeys and the follow-up unlock both enter libgojni; serialize each. Locks taken
            // per iteration so they're never held across loop control flow; addresses fetched above.
            val attempt = runCatching {
                cryptoLock.withLock { address.useKeys(cryptoContext) { decryptData(sharePassphraseArmored) } }
            }
            if (attempt.isSuccess) {
                val passphraseBytes = attempt.getOrThrow()
                val keyBytes = cryptoLock.withLock {
                    val unlockedKey = cryptoContext.pgpCrypto.unlock(shareKeyArmored, passphraseBytes)
                    val bytes = unlockedKey.value.copyOf()
                    unlockedKey.close()
                    bytes
                }
                Log.d(TAG, "decryptExternalShareKey: succeeded (passphraseBytes=${passphraseBytes.size} keyBytes=${keyBytes.size})")
                return keyBytes
            }
            lastError = attempt.exceptionOrNull()
        }
        throw lastError ?: error("External share passphrase did not match any address key")
    }

    /** Wipes the cached share key bytes for [userId] before dropping the map entry, so heap
     *  inspection cannot recover plaintext key material after sign-out / user switch. */
    fun clearShareKeyCache(userId: UserId) {
        shareKeyCache.remove(userId.id)?.fill(0)
    }

    /** Wipes every cached share key, used on full sign-out or process boundaries. */
    fun clearAllCaches() {
        val it = shareKeyCache.values.iterator()
        while (it.hasNext()) it.next().fill(0)
        shareKeyCache.clear()
    }
}
