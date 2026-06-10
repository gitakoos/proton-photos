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
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Drive-specific signature contexts the server checks against. Mirror of the constants in
 * `me.proton.core.drive.cryptobase.domain.usecase.SignatureContexts` in the official
 * ProtonDriveApps/android-drive SDK. All are critical (server rejects when missing / mismatched).
 *
 * Note: the SDK exposes contexts ONLY for share-member operations. NodePassphrase, Manifest,
 * ContentKeyPacket, Name and Block signatures are produced without a context on both the
 * official Android client and ours — so we deliberately do NOT add notations to those sites.
 * Doing so could create signatures the server / web client refuse to verify.
 */
internal object DriveSignatureContexts {
    val INVITER = SignatureContext(value = "drive.share-member.inviter", isCritical = true)
    val MEMBER  = SignatureContext(value = "drive.share-member.member", isCritical = true)
}

private const val TAG = "DriveCrypto"

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
     * Primary address-key ID — server-side identifier of the key whose private half is
     * [unlockedKeyBytes]. Required by `POST /drive/photos/volumes` so the backend can
     * tag the new Photos share with the exact key that signed/encrypted its passphrase.
     * Mirrors the `AddressKeyID` field in `CreatePhotoVolumeRequest.Share` (official client).
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
    /** Lazy because PhotosShareService transitively depends on this DriveCryptoHelper —
     *  Hilt would deadlock the graph on direct injection. We only need to read the
     *  volume-owner addressId, which is a small synchronous (or one-time bootstrap)
     *  lookup that doesn't trigger any further crypto. */
    private val photosShareServiceLazy: dagger.Lazy<eu.akoos.photos.data.repository.drive.PhotosShareService>,
) {
    private val photosShareService get() = photosShareServiceLazy.get()
    private val shareKeyCache = ConcurrentHashMap<String, ByteArray>()

    // Serializes every libgojni-bound call. Android 16's CMC GC userfaultfd races against
    // Go's signal handlers when multiple JNI threads call into the same Go runtime, surfacing
    // as a SIGABRT after a few hundred parallel decrypts (observed on several Android 16 builds).
    // The existing chunk-pacing inside PhotoStreamService is necessary but not sufficient
    // because AlbumService + thumbnail fetchers add their own parallel pressure. A global
    // lock yields ~2x slower throughput but eliminates the crash.
    // Fair-FIFO so an in-flight upload's encrypt phase (which may hold the lock for
    // several seconds while it churns through a video's blocks) can't starve viewer
    // thumbnail / fullres decrypts. Without fairness, the next encrypt block can re-grab
    // the lock before a queued decrypt gets a turn — and the gallery visibly freezes for
    // the duration of the upload. Fair locks have a small throughput cost (a few percent
    // of mutex acquisitions go through the queue) but eliminate the starvation pathology.
    private val cryptoLock = java.util.concurrent.locks.ReentrantLock(true)

    /**
     * Wrap any direct pgpCrypto.* call site (anything not already routed through this helper)
     * with this guard so the same ReentrantLock serializes the call into libgojni. Without it,
     * services that decrypt names / unlock keys / generate keys in parallel can race the same
     * Go signal handlers that the in-class methods are already protected against. Targets:
     * PhotoEntityBuilder, AlbumService, PhotosShareService, PhotosVolumeBootstrap, and
     * PhotoUploadService — call sites that decrypt or generate keys outside this helper and
     * must be wrapped to serialize libgojni access.
     */
    fun <T> withCryptoLock(block: () -> T): T = cryptoLock.withLock(block)

    // ─── Decryption (download) ────────────────────────────────────────────────

    /**
     * Returns the PUBLIC keys (armored) of EVERY enabled user address. Used as verification
     * keys when we know the signer is the current user (anything WE uploaded). The previous
     * version returned only the primary address's keys — which produced confusing
     * "VERIFY_FAIL nodePassphrase / name" warnings AND empty display names whenever an
     * upload had been signed by a non-primary address (aliases, multi-address accounts,
     * users who later changed primary). decryptAndVerifyData falls back to an unverified
     * decrypt on signature failure, but that fallback can itself fail in edge cases, leaving
     * the gallery showing blank album / photo names until the user touched the album.
     * Aggregating all addresses' public keys removes the false negative.
     */
    /**
     * Returns every active email address on the user's account, in lowercase. Used to
     * recognise own membership entries on a shared resource regardless of which alias
     * the inviter used in the invitation. A multi-address account whose primary changes
     * after accepting an invitation would otherwise fail to find itself in the member
     * list by primary alone, blocking the "Leave album" action with "no permission".
     */
    suspend fun getOwnEmailAddresses(userId: UserId): List<String> {
        return userAddressRepository.getAddresses(userId, false)
            .filter { it.enabled }
            .mapNotNull { it.email.takeIf { e -> e.isNotBlank() }?.lowercase() }
            .distinct()
    }

    suspend fun getOwnPublicKeysArmored(userId: UserId): List<String> {
        val addresses = userAddressRepository.getAddresses(userId, false)
            .filter { it.enabled && it.keys.isNotEmpty() }
        if (addresses.isEmpty()) return emptyList()
        return addresses.flatMap { address ->
            address.keys.mapNotNull { addrKey ->
                runCatching { cryptoContext.pgpCrypto.getPublicKey(addrKey.privateKey.key) }.getOrNull()
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
     * Multi-key variant — gopenpgp walks every candidate against the message's PKESKs
     * and picks the first one that opens. Used for shared-with-me content where the
     * backend's PKESK substitution can land on a different key than the obvious parent
     * (e.g. photo Name in a shared album is encrypted to the OWNER's root key, but the
     * substituted PKESK targets the share's encryption subkey, so the recipient needs
     * to offer both the album NodeKey AND the share private key bytes before any one
     * of them lands a hit).
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
        // Verify-API rejects messages with no signature; fall back to encrypt-only
        // decrypt. The plain decrypt only takes a single key, so we walk each candidate
        // and stop at the first hit. Logging surfaces which key landed (or all of them
        // failing) so production logs make the failure mode obvious.
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

        // Try every active address in order. The share's passphrase is encrypted to ONE of
        // the user's addresses (usually the one that owned the share at create time), and
        // that isn't always the primary — accounts with aliases, multi-address users, or
        // users who later changed their primary address all hit "Cannot decrypt message with
        // provided Key list" if we only try the primary.
        //
        // CRITICAL: the WHOLE address attempt (decrypt + unlock) goes inside one runCatching.
        // A non-matching address can return non-error bytes that fail at unlock() instead of
        // at decryptData — wrapping only decryptData lets the unlock() throw escape the
        // address loop, so we never fall through to the next address. The wrong "decrypted"
        // passphrase never reaches the cache, but every refresh from cold start hits the same
        // dead address first and fails before trying the right one.
        var lastError: Throwable? = null
        for (address in addresses) {
            val attempt = runCatching {
                // address.useKeys delegates to ProtonCore's libgojni unlock + decryptData,
                // and cryptoContext.pgpCrypto.unlock is the same Go runtime. Both go inside
                // the lock so the post-login sync burst doesn't race other libgojni callers.
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
        // Empty/blank PGP armor strings are NOT just garbage input — they make the underlying
        // Go OpenPGP library panic with "runtime error: slice bounds out of range [:-1]"
        // (it strips a trailing newline from a 0-length string). The panic aborts the whole
        // process via SIGABRT and Kotlin's try/catch can't recover from it. Fail fast in
        // Kotlin so a JobCancellationException replaces the SIGABRT.
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
            // Names uploaded by the web/mobile client are encrypted+signed binary packets.
            // decryptData works for both binary (encryptAndSignData) and text (encryptText) modes.
            val bytes = cryptoContext.pgpCrypto.decryptData(encryptedNameArmored, nodeKeyBytes)
            String(bytes, Charsets.UTF_8)
        } catch (e: Exception) {
            // Fallback: legacy text-mode messages
            try { cryptoContext.pgpCrypto.decryptText(encryptedNameArmored, nodeKeyBytes) }
            catch (e2: Exception) { Log.w(TAG, "decryptLinkName failed: ${e2.message}"); null }
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
        // Same rationale as decryptNodeKey's require()s — empty/missing bytes make the Go
        // OpenPGP library panic with "slice bounds out of range [:-1]" and abort the entire
        // process via SIGABRT, bypassing Kotlin's try/catch. Validate in Kotlin first.
        require(encryptedFile.exists() && encryptedFile.length() > 0) {
            "decryptFileToDestination: encryptedFile is empty or missing"
        }
        val result = cryptoContext.pgpCrypto.decryptFile(encryptedFile, destFile, sessionKey)
        result.file
    }

    /**
     * Decrypts a binary OpenPGP block (PKESK + SEIPD packet) using the node's
     * unlocked private key bytes.
     *
     * Proton Drive blocks are self-contained PGP messages: the key packet (PKESK)
     * is embedded in every block alongside the data packet (SEIPD) — there is no
     * separate ContentKeyPacket for block downloads.
     *
     * Approach:
     *   1. Use getArmored() → proper ASCII-armor WITH CRC24 (manual armoring misses this)
     *   2. getEncryptedPackets() splits into Key (PKESK) + Data (SEIPD) packets
     *   3. decryptSessionKey(keyPacket, nodeKeyBytes) → SessionKey
     *   4. decryptData(dataPacket, sessionKey) → plaintext bytes
     */
    fun decryptBinaryPgpWithNodeKey(data: ByteArray, nodeKeyBytes: ByteArray): ByteArray? = cryptoLock.withLock {
        // Empty / too-short input is rejected here before reaching Go. A 0-byte buffer made
        // the GoPGP library panic with "slice bounds out of range [:-1]" while parsing the
        // armor header, killing the whole process with SIGABRT. Minimum length is the
        // smallest PGP message we'd see in practice (a few dozen bytes).
        if (data.size < 16) return@withLock null
        try {
            // Use getArmored() for proper ASCII-armor including CRC24 checksum, then
            // decrypt via the standard decryptData(armored, keyBytes) path — the same
            // path used for node-passphrase decryption. The split+decryptData(packet,sk)
            // path fails with "EncryptedFile cannot be extracted from EncryptedMessage"
            // because that overload is for the attachment (literal-file) format only.
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
        // Volume owner address (set at registration and STICKY) — NOT today's mail-primary,
        // because users that promote an alias to mail-primary later still have the original
        // address as the Drive volume owner. Drive web + Drive Android sign every album /
        // upload / share / invite with the volume owner; mixing in today's mail-primary
        // produces signatures recipients can't verify against the volume owner's key. Fall
        // back to mail-primary only when the share bootstrap hasn't run yet (cold start).
        val volumeOwnerAddressId = photosShareService.volumeOwnerAddressId(userId)
        val resolved = volumeOwnerAddressId
            ?.let { ownerId -> addresses.firstOrNull { it.addressId.id == ownerId && it.enabled && it.keys.isNotEmpty() } }
            ?: addresses.filter { it.enabled && it.keys.isNotEmpty() }.minByOrNull { it.order }
            ?: error("No active address for userId=${userId.id}")
        return unlockAddressAsSigningKey(resolved)
    }

    /**
     * Loads the address with the specific [addressId] and returns it unlocked as an
     * [AddressSigningKey]. Used when an existing Drive share lists a specific owner
     * address that may NOT match the user's current primary (e.g., the share was
     * created earlier from an alias, or the user later added a higher-priority
     * address). Backend rejects invitations whose `InviterAddressID` doesn't match
     * the share's owner with "The inviter address is not the one used in the
     * context share".
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
        // Match Drive Android's KeyHolderCryptoKt.useKeysAs / PrivateKeyRing.getUnlockedPrimaryKey:
        // pick the address key whose `isPrimary == true` (the one published in the user's SKL
        // as the signing key), not just the first active key. Multi-key addresses (after a
        // re-key / recovery / rollover) keep older keys active but only ONE is primary; signing
        // with a secondary key makes Drive web's SKL-aware verifier flag every signature as
        // SIGNED_NO_VERIFIER, which on the recipient cascades into "Failed to decrypt node".
        val activeKeys = address.keys.filter { it.privateKey.isActive && it.privateKey.passphrase != null }
        val key = activeKeys.firstOrNull { it.privateKey.isPrimary }
            ?: activeKeys.firstOrNull()
            ?: error("No active address key with passphrase for ${address.email}")
        val plainPassphrase = cryptoContext.keyStoreCrypto.decrypt(key.privateKey.passphrase!!)
        val unlocked = cryptoContext.pgpCrypto.unlock(key.privateKey.key, plainPassphrase.array)
        plainPassphrase.close()
        val keyBytes = unlocked.value.copyOf()
        unlocked.close()
        val publicKey = cryptoContext.pgpCrypto.getPublicKey(key.privateKey.key)
        return AddressSigningKey(
            email = address.email,
            addressId = address.addressId.id,
            unlockedKeyBytes = keyBytes,
            publicKeyArmored = publicKey,
            addressKeyId = key.keyId.id,
        )
    }

    /**
     * Generates an album NodeKey via gopenpgp's `generateNewPrivateKey` — the
     * same path Drive Android's `CreateAlbum` → `CreateFolderInfo` →
     * `GenerateNodeKey` → `GenerateNestedPrivateKey` takes for both folder and
     * album keys (`tempandroid-drive/.../CreateAlbum.kt`).
     */
    fun generateAlbumNodeKey(): NodeKeyMaterial = generateNodeKey()


    fun generateNodeKey(): NodeKeyMaterial {
        val rawBytes = cryptoContext.pgpCrypto.generateRandomBytes(32)
        // NodePassphrase is decrypted by clients as a UTF-8 string (web: TextDecoder.decode).
        // Raw random bytes break TextDecoder.  Base64-encoding the bytes produces valid UTF-8.
        val passphraseStr   = Base64.encodeToString(rawBytes, Base64.NO_WRAP)
        val passphraseBytes = passphraseStr.toByteArray(Charsets.UTF_8)
        // Drive Android's GenerateNestedPrivateKey hard-codes the User ID as
        // `drive-key@proton.me` (`tempandroid-drive/.../GenerateNestedPrivateKey.kt:75-76`
        // DEFAULT_USERNAME = "drive-key", DEFAULT_DOMAIN = "proton.me"). Every
        // album NodeKey produced by the official client therefore carries the
        // exact same User ID, and Drive web's openpgp.js validators have been
        // tuned against that one canonical packet shape. Using a different
        // User ID ("photos@proton.me") was the only wire-visible difference
        // between our album NodeKey and one Drive web produces locally —
        // matching the official client byte-for-byte removes that as the
        // failure surface for the recipient's per-photo decrypt chain.
        val armoredKey = cryptoContext.pgpCrypto.generateNewPrivateKey("drive-key", "proton.me", passphraseBytes)
        val publicKey  = cryptoContext.pgpCrypto.getPublicKey(armoredKey)
        val unlocked   = cryptoContext.pgpCrypto.unlock(armoredKey, passphraseBytes)
        val keyBytes   = unlocked.value.copyOf()
        unlocked.close()
        return NodeKeyMaterial(
            armoredPrivateKey = armoredKey,
            passphraseBytes   = passphraseBytes,   // UTF-8 bytes of the base64 string
            publicKeyArmored  = publicKey,
            unlockedKeyBytes  = keyBytes,
        )
    }

    fun encryptDataToPgpMessage(data: ByteArray, recipientPublicKeyArmored: String): String =
        cryptoLock.withLock { cryptoContext.pgpCrypto.encryptData(data, recipientPublicKeyArmored) }

    /**
     * Combined encrypt-and-sign in a single PGP MESSAGE blob — used for fields the
     * Drive backend (and Drive web) verify against the signatureEmail chain, like
     * the album's NodeHashKey. The official Drive Android `encryptAndSignHashKey`
     * pattern signs the random hash-key bytes with the address signing key and
     * encrypts to the node's own public key; without the signature, Drive web
     * surfaces the "Missing signature for hash key" error on the recipient side
     * and refuses to decrypt downstream children.
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
     * Re-wraps a NodePassphrase blob for a server-side copy: decrypts the source
     * `sourceNodePassphraseArmored` using the old parent's key bytes, then re-
     * encrypts the raw passphrase under the target parent's public key and
     * detached-signs it with the caller's address key. The underlying NodeKey
     * blob doesn't change (the backend re-uses the source encrypted private
     * key as-is on the new copy), so the SAME passphrase still unlocks it.
     *
     * Used by the "Save shared album to my library" copy pipeline, where the
     * source photo lives under the album NodeKey we accepted via the share and
     * the target lives under a fresh owned album we just created in the user's
     * own photos volume.
     */
    fun reencryptNodePassphraseForCopy(
        sourceNodePassphraseArmored: String,
        sourceParentKeyBytes: ByteArray,
        targetParentPublicKeyArmored: String,
        signerKeyBytes: ByteArray,
    ): ReencryptedNodePassphrase = cryptoLock.withLock {
        // PRESERVE the original SEIPD packet; only rewrap the PKESK to the new
        // recipient. This matches Drive Android's `ChangeMessage`
        // (drive/crypto-base/.../ChangeMessage.kt:37-62) which calls
        // `getSessionKeyFromEncryptedMessage` and reuses the existing session
        // key. Decrypt-then-encrypt loses information (literal-data type byte
        // t/b, internal framing, signature inclusion) which produces a
        // ciphertext that the photo NodeKey's S2K passphrase cannot reproduce
        // on the recipient side — every photo nodePassphrase rewrap previously
        // failed on the recipient with "Message cannot be decrypted". By
        // KEEPING THE SAME SEIPD bytes and only swapping the key-packet
        // recipient, the bytes the recipient extracts after PKESK unwrap are
        // byte-for-byte the bytes the photo NodeKey was originally encrypted
        // against.
        val originalPackets = cryptoContext.pgpCrypto.getEncryptedPackets(sourceNodePassphraseArmored)
        val pkeskPackets = originalPackets.filter { it.type == PacketType.Key }.map { it.packet }
        val seipdPackets = originalPackets.filter { it.type == PacketType.Data }.map { it.packet }
        require(pkeskPackets.isNotEmpty()) { "reencryptNodePassphraseForCopy: source has no PKESK" }
        require(seipdPackets.isNotEmpty()) { "reencryptNodePassphraseForCopy: source has no SEIPD" }
        val sessionKey = pkeskPackets.firstNotNullOfOrNull { pk ->
            runCatching { cryptoContext.pgpCrypto.decryptSessionKey(pk, sourceParentKeyBytes) }.getOrNull()
        } ?: error("reencryptNodePassphraseForCopy: no PKESK decrypted with source key")
        // Detached signature is computed over the RAW passphrase bytes — same
        // as the original. We still need this because the wire DTO carries a
        // separate `nodePassphraseSignature` field. The signed plaintext is
        // the passphrase itself, NOT any wrapping.
        val rawPassphrase = cryptoContext.pgpCrypto.decryptData(
            sourceNodePassphraseArmored,
            sourceParentKeyBytes,
        )
        val signature = cryptoContext.pgpCrypto.signData(rawPassphrase, signerKeyBytes, null)
        // New PKESK to the album's encryption subkey, reusing the same
        // session key — gopenpgp's `encryptSessionKey` writes the same
        // sym-algo metadata so the existing SEIPD remains parseable.
        val newPkeskBytes = cryptoContext.pgpCrypto.encryptSessionKey(sessionKey, targetParentPublicKeyArmored)
        // Concatenate: new PKESK + original SEIPD. Both are raw PGP packets.
        // gopenpgp's `getArmored(bytes, Message)` adds the BEGIN/END headers
        // and CRC24, matching what `encryptData` would have written.
        val combined = newPkeskBytes + seipdPackets[0]
        val newPassphraseArmored = cryptoContext.pgpCrypto.getArmored(combined, PGPHeader.Message)
        ReencryptedNodePassphrase(newPassphraseArmored, signature)
    }

    /**
     * Re-targets a link Name to a new parent key the way Drive Android's
     * `ChangeMessage` does (tempandroid-drive `drive/crypto-base/.../ChangeMessage.kt`):
     * extract the session key from the OLD armored name using the old parent
     * key, re-encrypt the plaintext under that SAME session key with an
     * embedded signature by the caller's address key (core
     * `encryptAndSignData(data, sessionKey, unlockedKey)` — the exact
     * primitive the official `EncryptText(sessionKey, …)` overload calls),
     * then write a fresh PKESK to the new parent and join key + data packets.
     *
     * Keeping the session-key lineage intact matters: Drive web's
     * `decryptName` accepts ChangeMessage-shaped re-wraps (that is what every
     * official client produces on add-to-album), while our previous
     * fresh-session-key `encryptAndSignText` blobs were rejected on the
     * recipient side even though the plaintext round-tripped locally — the
     * same failure mode the NodePassphrase re-wrap had before
     * [reencryptNodePassphraseForCopy] switched to session-key preservation.
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
     * Re-wraps a Link Name blob for the same copy pipeline. Decrypts the source
     * name with `sourceParentKeyBytes`, then encrypts + signs the recovered
     * plaintext under `targetParentPublicKeyArmored` using the caller's address
     * key. Mirrors Drive Android's `ChangeMessage` for our flat use case.
     *
     * Returns both the new armored ciphertext AND the plaintext bytes so the
     * caller can also feed the plaintext into the HMAC-SHA256 step (`hash`
     * field on CopyLinkRequest) without re-decrypting.
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
     * Plain detached armored PGP signature (-----BEGIN PGP SIGNATURE-----).
     * Used for NodePassphraseSignature and ContentKeyPacketSignature — both the Proton web
     * client and the Drive API expect a detached signature here, NOT an encrypted one.
     */
    fun signData(data: ByteArray, signerKeyBytes: ByteArray): String =
        cryptoLock.withLock { cryptoContext.pgpCrypto.signData(data, signerKeyBytes, null) }

    /**
     * Signs [data] with [signerKeyBytes] and encrypts the result to [recipientPublicKeyArmored].
     * Returns a PGP MESSAGE (-----BEGIN PGP MESSAGE-----).
     * Used for block EncSignature (EncSignature field) per Drive Android SDK convention.
     * Do NOT use for NodePassphraseSignature — the API requires a plain detached signature there.
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
     * Encrypts the link name to [parentPublicKeyArmored] AND signs it with [signerKeyBytes].
     * The web client always signs names; without a signature it shows "Missing signature for name".
     */
    fun encryptName(
        name: String,
        parentPublicKeyArmored: String,
        signerKeyBytes: ByteArray,
    ): String = cryptoLock.withLock {
        // Drive Android's `EncryptAndSignText.kt:39-41` calls `pgpCrypto.encryptAndSignText`
        // (text-mode literal data, type byte `t`), NOT `encryptAndSignData` (binary-mode,
        // type byte `b`). Drive web's `decryptAndVerifyText` consumers refuse to verify
        // signatures across the text/binary literal-type boundary even when the raw
        // plaintext round-trips byte-for-byte. The recipient sees "Missing signature
        // for name" or an "unknown signer" verification status, which cascades into
        // "this album is rejected" on the recipient-side album-load path and breaks
        // per-photo decrypt downstream.
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

    // Use Android NO_WRAP (no newlines) — the Proton server uses PHP's strict Base64::decode()
    // which rejects whitespace including newlines that pgpCrypto.getBase64Encoded() may emit.
    fun base64Encode(bytes: ByteArray): String =
        Base64.encodeToString(bytes, Base64.NO_WRAP)

    /** Encrypts block bytes with the session key. Returns encrypted binary PGP message. */
    fun encryptBlock(blockData: ByteArray, sessionKey: SessionKey): ByteArray =
        cryptoLock.withLock { cryptoContext.pgpCrypto.encryptData(blockData, sessionKey) }

    /**
     * Signs the plaintext block data (encrypted to the node key) so the server
     * can store a verifiable signature while only the key holder can read it.
     */
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

    /** Hex-encoded SHA-256 (kept for internal / legacy use). */
    fun sha256Hex(data: ByteArray): String = sha256(data).joinToString("") { "%02x".format(it) }

    /**
     * HMAC-SHA256 of the plaintext name using the raw NodeHashKey bytes.
     * Used as the Hash field in album / link creation requests so the server can
     * look up links by name without learning the plaintext name.
     */
    fun computeNameHash(plaintextName: String, nodeHashKeyBytes: ByteArray): String {
        val mac = javax.crypto.Mac.getInstance("HmacSHA256")
        mac.init(javax.crypto.spec.SecretKeySpec(nodeHashKeyBytes, "HmacSHA256"))
        val hmac = mac.doFinal(plaintextName.toByteArray(Charsets.UTF_8))
        return hmac.joinToString("") { "%02x".format(it) }
    }

    /**
     * Signs the revision manifest.
     *
     * The manifest is the concatenation of raw SHA-256 bytes for every encrypted block
     * (32 bytes × block count), in block-index order.  This matches the official SDK's
     * `RevisionManifestSignatureManager` which folds `block.hashBytes` (raw SHA-256) together.
     */
    fun signManifest(blockHashBytes: List<ByteArray>, signerKeyBytes: ByteArray): String {
        val manifest = blockHashBytes.fold(ByteArray(0)) { acc, hash -> acc + hash }
        return cryptoContext.pgpCrypto.signData(manifest, signerKeyBytes, null)
    }

    /**
     * Album / folder XAttr variant — Drive Android's `createXAttr()` no-arg overload
     * (`CreateXAttr.kt:36-41`) emits ONLY a `Common.ModificationTime` field for
     * non-file nodes. Drive web's verifier expects that exact shape on the album;
     * adding `Size`, `BlockSizes`, or a `Media` block breaks the trust chain so
     * every recipient downstream fails to decrypt every photo in the album.
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
    ): String {
        val blockSizesJson = blockSizes.joinToString(",", "[", "]")
        // Drive web's `photosTransferPayloadBuilder` and Drive Android's `parseDigests`
        // (tempweb-drive `extendedAttributes.ts:349`) read `Common.Digests.SHA1` from the
        // encrypted xAttr and reject payloads that omit it with the misleading
        // "Cannot build photo payload without a content hash". The SHA-1 hex is also
        // the input to the HMAC-SHA256 that produces the wire ContentHash, so the two
        // must come from the same digest pass.
        val digestsClause = if (!sha1HexDigest.isNullOrEmpty()) {
            ""","Digests":{"SHA1":"$sha1HexDigest"}"""
        } else ""
        // Drive Android's CreateXAttr writes `duration = mediaDuration.inWholeSeconds.toDouble()`
        // (tempandroid-drive .../file/base/.../CreateXAttr.kt:65) and Drive web reads
        // `media.duration` directly out of an HTMLVideoElement whose value is in seconds.
        // MediaStore.MediaColumns.DURATION on Android is milliseconds, so handing the raw
        // value to the JSON payload makes every video render as a 1000× overlay (a 7-second
        // clip lands as "2:03:45" on the Drive web grid). Convert here so the wire stays
        // in the shared seconds-as-number convention.
        val durationSeconds = durationMillis / 1000.0
        val durationLiteral = if (durationMillis == 0L) "0" else "%.3f".format(java.util.Locale.US, durationSeconds).trimEnd('0').trimEnd('.')
        val json = buildString {
            append("""{"Common":{"ModificationTime":"$modificationTimeIso","Size":$sizeBytes,"BlockSizes":$blockSizesJson""")
            append(digestsClause)
            append("""},"Media":{"Width":$width,"Height":$height,"Duration":$durationLiteral}}""")
        }
        // Drive Android's `EncryptAndSignXAttr.kt:39-43` calls
        // `encryptAndSignTextWithCompression` (text-mode literal data + ZIP-deflate
        // compression on the JSON), NOT `encryptAndSignData`. Drive web's xAttr
        // decode path verifies via `decryptAndVerifyText`; when the wire blob is
        // binary literal-data (type byte `b`) instead of text literal-data (type
        // byte `t`), verification status drops to "unknown signer" even though the
        // raw JSON round-trips. Cascading: a failed XAttr signature taints the
        // album's trust chain and Drive web refuses to verify per-photo signatures
        // anchored at the album, surfacing as "Failed to decrypt node" for every
        // child photo.
        return cryptoContext.pgpCrypto.encryptAndSignTextWithCompression(
            json,
            nodePublicKeyArmored,
            signerKeyBytes,
            null,
        )
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
        // Look up the EXACT address the invitation was sent to. Iterating blindly
        // over every active address used to feel safe, but gopenpgp's
        // decryptSessionKey doesn't always throw on a key mismatch — sometimes it
        // returns garbage bytes that still produce a valid-looking signature, and
        // the server-side accept then proceeds with a session-key signature that
        // doesn't match the actual share session key. The membership lands in a
        // half-broken state where the recipient can never decrypt anything in the
        // album. Resolving by email avoids the brute force entirely.
        val addresses = userAddressRepository.getAddresses(userId, false)
            .filter { it.enabled && it.keys.isNotEmpty() }
        val invitee = inviteeEmail.lowercase().trim()
        val address = addresses.firstOrNull { it.email.lowercase() == invitee }
            ?: error("No active address matching invitee email=$inviteeEmail for userId=${userId.id}")
        val signingKey = unlockAddressAsSigningKey(address)
        val sessionKey = cryptoContext.pgpCrypto.decryptSessionKey(keyPacketBytes, signingKey.unlockedKeyBytes)
        // Sign WITH the "drive.share-member.member" context — without it the server rejects
        // the accept with "Invalid Signature: wrong context".
        val armoredSignature = cryptoContext.pgpCrypto.signData(
            sessionKey.key,
            signingKey.unlockedKeyBytes,
            DriveSignatureContexts.MEMBER,
        )
        val unarmored = cryptoContext.pgpCrypto.getUnarmored(armoredSignature)
        Log.d(TAG, "signInvitationKeyPacket: matched invitee=$inviteeEmail addressId=${address.addressId.id}")
        return Base64.encodeToString(unarmored, Base64.NO_WRAP)
    }

    /**
     * Re-encrypts [sessionKeyBytes] (the raw session key of a Drive share) to the recipient's
     * public key and produces a detached signature signed by [signerKeyBytes] (the inviter
     * address key). Returns (base64 keyPacket, base64 unarmored signature) — the exact pair
     * the `/invitations` endpoint expects in `KeyPacket` + `KeyPacketSignature`.
     *
     * Critical detail (matches official Proton Drive Android client, see
     * CreateShareInvitationRequest.createInternalRequest in ProtonDriveApps/android-drive):
     * the inviter signs the **encrypted PKESK bytes** (the keyPacket itself), NOT the raw
     * session key. Signing the session key causes the backend to reject the invitation
     * with the misleading "outdated version of the app" error.
     */
    fun encryptAndSignSessionKeyForInvitee(
        sessionKey: SessionKey,
        inviteePublicKeyArmored: String,
        signerKeyBytes: ByteArray,
    ): Pair<String, String> {
        val keyPacket = try {
            cryptoContext.pgpCrypto.encryptSessionKey(sessionKey, inviteePublicKeyArmored)
        } catch (t: Throwable) {
            // gopenpgp's "SessionKey cannot be encrypted" surfaces here. Log the
            // session-key size so the caller's failure path can correlate to the key
            // selection (we expect 32 bytes for AES-256). A wildly different size
            // means the wrong material was passed in.
            Log.e(TAG, "encryptSessionKey to invitee failed: sessionKeyBytes=${sessionKey.key.size} cause=${t.message}", t)
            throw t
        }
        val keyPacketBase64 = Base64.encodeToString(keyPacket, Base64.NO_WRAP)
        // Inviter-side context: matches DRIVE_SHARE_MEMBER_INVITER on the server.
        // Sign the encrypted key packet bytes — official client signs `encryptedKeyPacket`,
        // not the underlying session key.
        val armoredSig = cryptoContext.pgpCrypto.signData(
            keyPacket,
            signerKeyBytes,
            DriveSignatureContexts.INVITER,
        )
        val unarmoredSig = cryptoContext.pgpCrypto.getUnarmored(armoredSig)
        return keyPacketBase64 to Base64.encodeToString(unarmoredSig, Base64.NO_WRAP)
    }

    /**
     * Bundle of crypto material produced by [generateNewShareForLink]. The returned
     * [rawPassphraseBytes] is the unlocked passphrase for the new share key — keep it
     * in memory so we can build invitation key packets directly afterwards without an
     * extra round-trip.
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
        /**
         * In-memory copy of the session key used to encrypt [sharePassphraseArmored].
         * Callers minting a public URL right after `createVolumeShare` should use this
         * directly instead of round-tripping through [decryptSharePassphraseSessionKey],
         * saving the unlock + decrypt round trip.
         */
        val passphraseSessionKey: SessionKey,
    )

    /**
     * Builds every PGP artefact the `POST /drive/volumes/{volumeId}/shares` endpoint expects.
     *
     * The wire format mirrors ProtonDriveApps/android-drive [GenerateShareKey] +
     * [GenerateSharePassphrase] + name encryption:
     *
     *   ShareKey                = armored locked private key, passphrase = base64(random 32B).
     *   SharePassphrase         = armored PGP MESSAGE wrapping the passphrase bytes,
     *                             encrypted to the inviter's address public key.
     *   SharePassphraseSignature= detached armored signature over the raw passphrase bytes.
     *   PassphraseKeyPacket     = base64 of the PKESK extracted from SharePassphrase.
     *   Name                    = armored PGP MESSAGE wrapping the plaintext album name.
     *   NameKeyPacket           = base64 of the PKESK extracted from Name.
     */
    fun generateNewShareForLink(
        addressPublicKeyArmored: String,
        signerKeyBytes: ByteArray,
        plaintextName: String,
    ): GeneratedShare {
        // 1. New PGP key + passphrase (base64-encoded random bytes so the passphrase round-trips
        //    cleanly as UTF-8 — same trick we use for node passphrases).
        val rawPassphrase = cryptoContext.pgpCrypto.generateRandomBytes(32)
        val passphraseStr = Base64.encodeToString(rawPassphrase, Base64.NO_WRAP)
        val passphraseBytes = passphraseStr.toByteArray(Charsets.UTF_8)
        val shareKeyArmored = cryptoContext.pgpCrypto.generateNewPrivateKey("share", "proton.me", passphraseBytes)
        val sharePubKey = cryptoContext.pgpCrypto.getPublicKey(shareKeyArmored)

        // 2. SharePassphrase = PKESK + SEIPD over the passphrase bytes. We split the
        //    construction so we can also expose the PKESK by itself (PassphraseKeyPacket).
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

        // 3. Name encryption — same shape as passphrase. The plaintext album name is wrapped
        //    in PGP MESSAGE form and the matching PKESK is base64-encoded into NameKeyPacket.
        val nameSessionKey = cryptoContext.pgpCrypto.generateNewSessionKey()
        val nameKeyPacketBytes =
            cryptoContext.pgpCrypto.encryptSessionKey(nameSessionKey, addressPublicKeyArmored)
        val nameSeipd = cryptoContext.pgpCrypto.encryptData(
            plaintextName.toByteArray(Charsets.UTF_8), nameSessionKey,
        )
        val nameEncryptedArmored = cryptoContext.pgpCrypto.getArmored(
            nameKeyPacketBytes + nameSeipd, PGPHeader.Message,
        )

        return GeneratedShare(
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
     * Material produced by [generateShareForAlbum] — the wire shape mirrors the official
     * Drive Android `ShareInfo` after `CreateShareInfo` runs. The two key-packet fields
     * carry the ALBUM's session keys re-encrypted under the new share key, NOT the share's
     * own PKESKs (which is what our earlier `generateNewShareForLink` produced and which
     * the backend rejected with "outdated version of the app" / code 2001).
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
     * Builds every PGP artefact the album-share create endpoint expects, mirroring
     * the canonical Drive Android `CreateShareInfo` + `ReencryptKeyPacket` pipeline.
     *
     * Differs from the earlier [generateNewShareForLink] in three critical ways:
     *  - [GeneratedAlbumShare.sharePassphraseArmored] is multi-recipient — encrypted to
     *    BOTH the album link's node public key AND the user's address public key, so
     *    the owner can unlock the share via address, and downstream member operations
     *    that go through the link can use the node side.
     *  - `passphraseKeyPacket` and `nameKeyPacket` are NOT the share's own PKESK + name
     *    PKESK; they are the ALBUM link's existing nodePassphrase / name session keys,
     *    decrypted with [parentLinkKeyBytes] and re-encrypted under the new share key.
     *    This is how the share key unlocks the album's existing material — without it
     *    the backend rejects share-creation with code 2001 ("outdated version of the
     *    app") because the request describes a share that's structurally disconnected
     *    from the link it claims to share.
     *  - The wire `Name` field stays a plaintext label (handled at the call site); the
     *    actual album name is conveyed via [nameKeyPacketBase64].
     */
    suspend fun generateShareForAlbum(
        albumNodeKeyArmored: String,
        albumNodePassphraseArmored: String,
        albumNameArmored: String,
        parentLinkKeyBytes: ByteArray,
        addressPublicKeyArmored: String,
        signerKeyBytes: ByteArray,
    ): GeneratedAlbumShare = cryptoLock.withLock {
        // 1. Album's public key — needed so the share passphrase is encrypted to BOTH
        //    the album node (for member operations) and the user's address (for owner).
        val albumPublicKeyArmored = cryptoContext.pgpCrypto.getPublicKey(albumNodeKeyArmored)

        // 2. Generate share key + random passphrase. Base64 round-trip so the passphrase
        //    serializes cleanly as UTF-8 — same convention as the existing node-key path.
        val rawPassphrase = cryptoContext.pgpCrypto.generateRandomBytes(32)
        val passphraseAscii = Base64.encodeToString(rawPassphrase, Base64.NO_WRAP)
        val passphraseBytes = passphraseAscii.toByteArray(Charsets.UTF_8)
        val shareKeyArmored = cryptoContext.pgpCrypto.generateNewPrivateKey("share", "proton.me", passphraseBytes)
        val sharePubKey = cryptoContext.pgpCrypto.getPublicKey(shareKeyArmored)

        // 3. Multi-recipient SharePassphrase: PKESK(albumPub) + PKESK(addressPub) + SEIPD.
        //    Any holder of the album node key OR the address key can decrypt to recover
        //    the passphrase, which in turn unlocks the share's own private key.
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

        // 4. Detached signature over raw passphrase bytes, signed by the user's address
        //    key with no context — same as the existing share-passphrase signature flow,
        //    which we verified against the official client at the DTO layer.
        val sharePassphraseSignature =
            cryptoContext.pgpCrypto.signData(passphraseBytes, signerKeyBytes, null)

        // 5. Re-encrypt album.nodePassphrase session key from parent→share. The previous
        //    implementation manually pulled the first PKESK out of the armored message,
        //    decrypted its session key, then re-encrypted that session key to the share's
        //    public key. That round-trip dropped the original PKESK's algorithm metadata
        //    along the way, so the substituted PKESK gopenpgp produced was technically
        //    targeted at the right subkey but encoded the session key under a different
        //    symmetric algorithm than the SEIPD body it pointed at — leaving recipients
        //    with a session key that decrypted PKESK successfully but failed on SEIPD.
        //
        //    `encryptMessageToAdditionalKey` performs the decrypt + re-encrypt as a single
        //    internal step, preserves the original session key's algorithm bookkeeping,
        //    and emits the new PKESK with metadata that matches the existing SEIPD. We
        //    then extract just the new PKESK packet (the one whose target is the share
        //    pub key) and ship it to the backend.
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

    /**
     * Decodes the recipient KeyID written into a raw PKESK packet. Used to verify that
     * gopenpgp picked the encryption subkey we expected when [encryptSessionKey] ran.
     * Returns "?" if the bytes aren't a parsable v3 PKESK.
     */
    private fun pkeskKeyIdHex(packetBytes: ByteArray): String {
        // New-format header: 0xC1, then a 1-byte (most cases here) length, then
        // version (1 byte) + recipient KeyID (8 bytes) + algo + encrypted material.
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
     * Returns the hex KeyIDs of every primary/sub public key in an armored PGP public
     * key block. The KeyID is the LSB 8 bytes of the SHA-1 fingerprint over the
     * canonical public-key portion of each packet.
     */
    private fun publicKeyIdHexes(armoredPublicKey: String): String {
        return runCatching {
            // Strip armor: drop the BEGIN/END lines, any header lines, then the CRC line.
            // gopenpgp's getPublicKey output sometimes omits the `Version:` header line,
            // so a regex tied to "double newline after headers" misses those cases. We
            // walk the lines manually and join everything that's base64-only.
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
     * Adds a new PKESK to [originalMessageArmored] that lets the holder of
     * [recipientPublicKeyArmored] open the existing SEIPD, and returns just
     * that newly-minted PKESK packet as base64.
     *
     * Internally delegates to gopenpgp's `encryptMessageToAdditionalKey`,
     * which decrypts the existing session key with [unlockedSourceKeyBytes]
     * and re-encrypts it to [recipientPublicKeyArmored] in a single step —
     * preserving the session key's algorithm metadata so the new PKESK
     * stays bit-compatible with the unchanged SEIPD. Manually round-tripping
     * `decryptSessionKey` + `encryptSessionKey` is lossy here: gopenpgp
     * defaults the symmetric algorithm when re-encrypting, which leaves the
     * recipient with a valid-looking PKESK that decrypts to a session key
     * the SEIPD can't actually consume.
     *
     * Backend callers (CreateShareRequest's `passphraseKeyPacket` /
     * `nameKeyPacket`) want just the new PKESK packet bytes, not the whole
     * armored message — so we walk the resulting packet stream, isolate the
     * single new Key packet by diffing against the original's packet list,
     * and emit base64 over the lone fresh PKESK.
     */
    private fun reencryptKeyPacketForAdditionalRecipient(
        originalMessageArmored: String,
        unlockedSourceKeyBytes: ByteArray,
        recipientPublicKeyArmored: String,
        diagnosticLabel: String,
    ): String {
        // Drive Android's ReencryptKeyPacketImpl path — get the session key out of the
        // existing armored message with the source key, encrypt that same session key to
        // the new recipient's public key, return just the resulting PKESK packet. The
        // earlier algorithm-preserving variant (encryptMessageToAdditionalKey + diff)
        // started failing backend verification with code 200501 (encryption verification
        // failed) once the server tightened share-creation checks; matching the official
        // client's primitive byte-for-byte clears that gate.
        val originalPackets = cryptoContext.pgpCrypto.getEncryptedPackets(originalMessageArmored)
        val pkeskPackets = originalPackets.filter { it.type == PacketType.Key }.map { it.packet }
        require(pkeskPackets.isNotEmpty()) {
            "$diagnosticLabel: original armored message has no PKESK packets"
        }

        // The source key may match any of the multi-recipient PKESKs (e.g. album-node
        // PKESK first, address-node PKESK second). Try each in order; the first decrypt
        // that succeeds gives us the session key.
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
     * Crypto bundle required by `POST drive/shares/{shareId}/urls`. The backend rejects
     * the create-URL request unless every field below is present and internally consistent
     * — they describe an SRP-authenticated public link that lets a recipient unlock the
     * share session key from just the URL + a random password embedded in the URL itself.
     *
     * Permissions stays at 4 (read-only) and Flags at 2 (random URL password) — the values
     * the official Drive Android client always sends for newly minted album/file URLs.
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
    )

    /**
     * Mirrors the [CreateShareUrlInfo] pipeline in ProtonDriveApps/android-drive:
     *
     *  1. Mint a random URL password (9 raw bytes → 12 base64 ASCII chars, padding-free).
     *  2. Compute an SRP verifier from that password using a [modulus] / [modulusId] pair
     *     supplied by the caller (caller obtains them via `AuthRepository.randomModulus`).
     *  3. Generate a fresh salt, derive a salted passphrase from the URL password, and
     *     symmetrically encrypt [shareSessionKey] with it. The visitor regenerates the
     *     same salted passphrase with the salt sent in `sharePasswordSalt` to recover
     *     the session key client-side — no server roundtrip needed.
     *  4. PGP-encrypt the URL password to the creator's own address public key so we can
     *     echo it back to the creator (e.g. for a "show password" affordance) without
     *     storing it on the server in plaintext.
     *
     * The returned package, plus [permissions]=4 and [flags]=2, fills every required
     * field of [CreateShareUrlRequest].
     */
    suspend fun buildShareUrlCryptoPackage(
        addressPublicKeyArmored: String,
        shareSessionKey: SessionKey,
        modulus: String,
        modulusId: String,
    ): ShareUrlCryptoPackage {
        // SRP verifier is suspend (it goes through Go via libgojni) so it must run
        // outside the non-suspending cryptoLock. We serialize the actual gopenpgp
        // calls below by acquiring the lock around the PGP-only portion only.
        val urlPasswordRaw = cryptoLock.withLock { cryptoContext.pgpCrypto.generateRandomBytes(9) }
        val urlPasswordAscii = Base64.encodeToString(urlPasswordRaw, Base64.NO_WRAP)
        val urlPasswordBytes = urlPasswordAscii.toByteArray(Charsets.UTF_8)
        val auth = cryptoContext.srpCrypto.calculatePasswordVerifier(
            username = "",
            password = urlPasswordBytes,
            modulusId = modulusId,
            modulus = modulus,
        )
        return cryptoLock.withLock { buildPgpHalfOfShareUrlPackage(addressPublicKeyArmored, shareSessionKey, urlPasswordBytes, auth) }
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
            urlPasswordSalt = auth.salt,
            sharePasswordSalt = sharePasswordSalt,
            srpVerifier = auth.verifier,
            srpModulusId = auth.modulusId,
            sharePassphraseKeyPacketBase64 = sharePassphraseKeyPacketB64,
            encryptedUrlPassword = encryptedUrlPassword,
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
            val attempt = runCatching {
                address.useKeys(cryptoContext) { decryptSessionKey(keyPacketBytes) }
            }
            if (attempt.isSuccess) return attempt.getOrThrow()
            lastError = attempt.exceptionOrNull()
        }
        throw lastError ?: error("Could not decrypt share passphrase key packet with any address key")
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
            val attempt = runCatching {
                address.useKeys(cryptoContext) { decryptData(sharePassphraseArmored) }
            }
            if (attempt.isSuccess) {
                val passphraseBytes = attempt.getOrThrow()
                val unlockedKey = cryptoContext.pgpCrypto.unlock(shareKeyArmored, passphraseBytes)
                val keyBytes = unlockedKey.value.copyOf()
                unlockedKey.close()
                Log.d(TAG, "decryptExternalShareKey: address=${address.email} passphraseBytes=${passphraseBytes.size} keyBytes=${keyBytes.size}")
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
