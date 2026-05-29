package eu.akoos.photos.data.crypto

import android.util.Base64
import android.util.Log
import me.proton.core.crypto.common.context.CryptoContext
import me.proton.core.crypto.common.pgp.PGPHeader
import me.proton.core.crypto.common.pgp.SessionKey
import me.proton.core.crypto.common.pgp.SignatureContext
import me.proton.core.crypto.common.pgp.VerificationStatus
import me.proton.core.domain.entity.UserId
import me.proton.core.key.domain.decryptData
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
) {
    private val shareKeyCache = ConcurrentHashMap<String, ByteArray>()

    // Serializes every libgojni-bound call. Android 16's CMC GC userfaultfd races against
    // Go's signal handlers when multiple JNI threads call into the same Go runtime, surfacing
    // as a SIGABRT after a few hundred parallel decrypts (Samsung S22 / Pixel 9 BP2A builds).
    // The existing chunk-pacing inside PhotoStreamService is necessary but not sufficient
    // because AlbumService + thumbnail fetchers add their own parallel pressure. A global
    // lock yields ~2x slower throughput but eliminates the crash.
    private val cryptoLock = java.util.concurrent.locks.ReentrantLock()

    /**
     * Wrap any direct pgpCrypto.* call site (anything not already routed through this helper)
     * with this guard so the same ReentrantLock serializes the call into libgojni. Without it,
     * services that decrypt names / unlock keys / generate keys in parallel can race the same
     * Go signal handlers that the in-class methods are already protected against. Targets:
     * PhotoEntityBuilder, AlbumService, PhotosShareService, PhotosVolumeBootstrap,
     * PhotoUploadService — the five remaining bypassers identified by the Pixel 9 audit.
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
    ): VerifiedBytes? = cryptoLock.withLock { try {
        val decrypted = cryptoContext.pgpCrypto.decryptAndVerifyData(
            message = encryptedArmored,
            publicKeys = verificationPublicKeysArmored,
            unlockedKeys = listOf(decryptionKeyBytes),
        )
        VerifiedBytes(decrypted.data, decrypted.status == VerificationStatus.Success)
    } catch (e: Exception) {
        // Verify-API rejects messages with no signature; fall back to encrypt-only decrypt.
        // Log both paths so it's clear in production logs which failure mode hit.
        runCatching { cryptoContext.pgpCrypto.decryptData(encryptedArmored, decryptionKeyBytes) }
            .fold(
                onSuccess = {
                    Log.d(TAG, "decryptAndVerifyData: verify failed (${e.message}) — returning unverified plaintext")
                    VerifiedBytes(it, verified = false)
                },
                onFailure = { fallbackErr ->
                    Log.w(TAG, "decryptAndVerifyData: verify failed (${e.message}) AND encrypt-only fallback failed (${fallbackErr.message})")
                    null
                },
            )
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
            cryptoContext.pgpCrypto.verifyData(data, signatureArmored, pub)
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
                val passphraseBytes = address.useKeys(cryptoContext) {
                    decryptData(sharePassphraseArmored)
                }
                val unlockedKey = cryptoContext.pgpCrypto.unlock(shareKeyArmored, passphraseBytes)
                val keyBytes = unlockedKey.value.copyOf()
                unlockedKey.close()
                keyBytes
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
        // Primary address (lowest order) owns the share; use it for signing.
        val address = addresses.filter { it.enabled && it.keys.isNotEmpty() }.minByOrNull { it.order }
            ?: error("No active address for userId=${userId.id}")
        val key = address.keys.firstOrNull { it.privateKey.isActive && it.privateKey.passphrase != null }
            ?: error("No active address key with passphrase")
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

    fun generateNodeKey(): NodeKeyMaterial {
        val rawBytes = cryptoContext.pgpCrypto.generateRandomBytes(32)
        // NodePassphrase is decrypted by clients as a UTF-8 string (web: TextDecoder.decode).
        // Raw random bytes break TextDecoder.  Base64-encoding the bytes produces valid UTF-8.
        val passphraseStr   = Base64.encodeToString(rawBytes, Base64.NO_WRAP)
        val passphraseBytes = passphraseStr.toByteArray(Charsets.UTF_8)
        val armoredKey = cryptoContext.pgpCrypto.generateNewPrivateKey("photos", "proton.me", passphraseBytes)
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
        cryptoContext.pgpCrypto.encryptData(data, recipientPublicKeyArmored)

    /**
     * Plain detached armored PGP signature (-----BEGIN PGP SIGNATURE-----).
     * Used for NodePassphraseSignature and ContentKeyPacketSignature — both the Proton web
     * client and the Drive API expect a detached signature here, NOT an encrypted one.
     */
    fun signData(data: ByteArray, signerKeyBytes: ByteArray): String =
        cryptoContext.pgpCrypto.signData(data, signerKeyBytes, null)

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
    ): String = cryptoContext.pgpCrypto.signDataEncrypted(
        data, signerKeyBytes, listOf(recipientPublicKeyArmored), null,
    )

    /**
     * Encrypts the link name to [parentPublicKeyArmored] AND signs it with [signerKeyBytes].
     * The web client always signs names; without a signature it shows "Missing signature for name".
     */
    fun encryptName(
        name: String,
        parentPublicKeyArmored: String,
        signerKeyBytes: ByteArray,
    ): String = cryptoContext.pgpCrypto.encryptAndSignData(
        name.toByteArray(Charsets.UTF_8),
        parentPublicKeyArmored,
        signerKeyBytes,
        null,
    )

    fun generateSessionKey(): SessionKey =
        cryptoContext.pgpCrypto.generateNewSessionKey()

    fun encryptSessionKeyToNode(sessionKey: SessionKey, nodePublicKeyArmored: String): ByteArray =
        cryptoContext.pgpCrypto.encryptSessionKey(sessionKey, nodePublicKeyArmored)

    // Use Android NO_WRAP (no newlines) — the Proton server uses PHP's strict Base64::decode()
    // which rejects whitespace including newlines that pgpCrypto.getBase64Encoded() may emit.
    fun base64Encode(bytes: ByteArray): String =
        Base64.encodeToString(bytes, Base64.NO_WRAP)

    /** Encrypts block bytes with the session key. Returns encrypted binary PGP message. */
    fun encryptBlock(blockData: ByteArray, sessionKey: SessionKey): ByteArray =
        cryptoContext.pgpCrypto.encryptData(blockData, sessionKey)

    /**
     * Signs the plaintext block data (encrypted to the node key) so the server
     * can store a verifiable signature while only the key holder can read it.
     */
    fun signBlockEncrypted(
        blockPlaintext: ByteArray,
        signerKeyBytes: ByteArray,
        nodePublicKeyArmored: String,
    ): String = cryptoContext.pgpCrypto.signDataEncrypted(
        blockPlaintext, signerKeyBytes, listOf(nodePublicKeyArmored), null,
    )

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

    fun encryptXAttr(
        modificationTimeIso: String,
        sizeBytes: Long,
        blockSizes: List<Long>,
        width: Int,
        height: Int,
        duration: Long,
        nodePublicKeyArmored: String,
        signerKeyBytes: ByteArray,
    ): String {
        val blockSizesJson = blockSizes.joinToString(",", "[", "]")
        val json = buildString {
            append("""{"Common":{"ModificationTime":"$modificationTimeIso","Size":$sizeBytes,"BlockSizes":$blockSizesJson}""")
            append(""","Media":{"Width":$width,"Height":$height,"Duration":$duration}}""")
        }
        return cryptoContext.pgpCrypto.encryptAndSignData(
            json.toByteArray(Charsets.UTF_8),
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
    suspend fun signInvitationKeyPacket(userId: UserId, keyPacketBase64: String): String {
        val keyPacketBytes = Base64.decode(keyPacketBase64, Base64.DEFAULT)
        val signingKey = getAddressSigningKey(userId)
        val sessionKey = cryptoContext.pgpCrypto.decryptSessionKey(keyPacketBytes, signingKey.unlockedKeyBytes)
        // Sign WITH the "drive.share-member.member" context — without it the server rejects
        // the accept with "Invalid Signature: wrong context".
        val armoredSignature = cryptoContext.pgpCrypto.signData(
            sessionKey.key,
            signingKey.unlockedKeyBytes,
            DriveSignatureContexts.MEMBER,
        )
        val unarmored = cryptoContext.pgpCrypto.getUnarmored(armoredSignature)
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
        sessionKeyBytes: ByteArray,
        inviteePublicKeyArmored: String,
        signerKeyBytes: ByteArray,
    ): Pair<String, String> {
        val sessionKey = me.proton.core.crypto.common.pgp.SessionKey(sessionKeyBytes)
        val keyPacket = cryptoContext.pgpCrypto.encryptSessionKey(sessionKey, inviteePublicKeyArmored)
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
        )
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
