package eu.akoos.photos.data.repository.drive

import android.util.Base64
import me.proton.core.crypto.common.context.CryptoContext
import me.proton.core.domain.entity.UserId
import eu.akoos.photos.data.api.dto.CreatePhotosVolumeRequest
import eu.akoos.photos.data.api.dto.PhotosVolumeLink
import eu.akoos.photos.data.api.dto.PhotosVolumeShare
import eu.akoos.photos.data.crypto.DriveCryptoHelper
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Builds the body for POST `/drive/photos/volumes` — the call that lazily materialises a
 * brand-new account's Photos share + root link. The server rejects an empty body with
 * "Missing required attributes Share,Link", so every caller must hand in the full nested
 * key material this builder produces.
 *
 * Wire shape mirrors the official Proton Drive Android client's
 * `me.proton.core.drive.volume.data.api.request.CreatePhotoVolumeRequest`
 * (see [ProtonDriveApps/android-drive `drive/volume/data/.../CreatePhotoVolumeRequest.kt`](
 *  https://github.com/ProtonDriveApps/android-drive/blob/main/drive/volume/data/src/main/kotlin/me/proton/core/drive/volume/data/api/request/CreatePhotoVolumeRequest.kt)).
 *
 * Key hierarchy created in one body:
 *   address key → share passphrase → share key (a freshly-generated PGP key pair)
 *   share key  → link  passphrase → root-link key (a freshly-generated PGP key pair)
 *   root-link key → encrypted-and-signed `NodeHashKey` (HMAC seed used for name hashing later)
 *   share key  → encrypted-and-signed root-folder name "PhotosRoot"
 *
 * The official client uses literal `"PhotosRoot"` as `DEFAULT_PHOTOS_ROOT_FOLDER_NAME`
 * (see [`VolumeInfo.kt`](https://github.com/ProtonDriveApps/android-drive/blob/main/drive/volume/domain/src/main/kotlin/me/proton/core/drive/volume/domain/entity/VolumeInfo.kt));
 * we keep that exact name so the web client / iOS shows the same canonical root.
 */
@Singleton
class PhotosVolumeBootstrap @Inject constructor(
    private val cryptoHelper: DriveCryptoHelper,
    private val cryptoContext: CryptoContext,
) {

    /**
     * Generates every PGP artefact `POST /drive/photos/volumes` requires and returns a
     * ready-to-serialize [CreatePhotosVolumeRequest]. Each call produces fresh keys —
     * never reuse the result. Designed to be idempotent on the server: a second call
     * with different keys still succeeds, the server just returns the existing volume.
     */
    suspend fun build(userId: UserId): CreatePhotosVolumeRequest {
        val signingKey = cryptoHelper.getAddressSigningKey(userId)
        // The crypto-heavy block from here down is wrapped under one withCryptoLock call —
        // first-launch volume bootstrap generates two PGP key pairs, performs unlocks, and
        // encrypts the hash seed via direct cryptoContext.pgpCrypto.* calls that would
        // otherwise race the same Go signal handlers cryptoHelper's own methods are
        // already protected against. ReentrantLock is re-entrant so the nested
        // cryptoHelper.encrypt/sign calls cost nothing extra.
        return cryptoHelper.withCryptoLock { buildCryptoBlock(signingKey) }
    }

    private fun buildCryptoBlock(signingKey: eu.akoos.photos.data.crypto.AddressSigningKey): CreatePhotosVolumeRequest {
        // ─── Share key ────────────────────────────────────────────────────────
        // ShareKey = new PGP key pair, passphrase = base64(random 32B) for clean UTF-8.
        // SharePassphrase = passphrase armored + encrypted-to-address-pubkey + signed by address.
        val sharePassphraseRaw = cryptoContext.pgpCrypto.generateRandomBytes(32)
        val sharePassphraseStr = Base64.encodeToString(sharePassphraseRaw, Base64.NO_WRAP)
        val sharePassphraseBytes = sharePassphraseStr.toByteArray(Charsets.UTF_8)
        val shareKeyArmored = cryptoContext.pgpCrypto.generateNewPrivateKey(
            "drive-key", "proton.me", sharePassphraseBytes,
        )
        val sharePublicKey = cryptoContext.pgpCrypto.getPublicKey(shareKeyArmored)
        val shareUnlocked = cryptoContext.pgpCrypto.unlock(shareKeyArmored, sharePassphraseBytes)
        val shareKeyBytes = shareUnlocked.value.copyOf()
        shareUnlocked.close()

        val sharePassphraseEncrypted = cryptoHelper.encryptDataToPgpMessage(
            sharePassphraseBytes, signingKey.publicKeyArmored,
        )
        val sharePassphraseSignature = cryptoHelper.signData(
            sharePassphraseBytes, signingKey.unlockedKeyBytes,
        )

        // ─── Root-link key (the Photos "PhotosRoot" folder) ───────────────────
        // NodeKey = new PGP key pair, passphrase = base64(random 32B).
        // NodePassphrase = passphrase armored + encrypted-to-share-pubkey + signed by address.
        val linkPassphraseRaw = cryptoContext.pgpCrypto.generateRandomBytes(32)
        val linkPassphraseStr = Base64.encodeToString(linkPassphraseRaw, Base64.NO_WRAP)
        val linkPassphraseBytes = linkPassphraseStr.toByteArray(Charsets.UTF_8)
        val linkKeyArmored = cryptoContext.pgpCrypto.generateNewPrivateKey(
            "drive-key", "proton.me", linkPassphraseBytes,
        )
        val linkPublicKey = cryptoContext.pgpCrypto.getPublicKey(linkKeyArmored)
        val linkPassphraseEncrypted = cryptoHelper.encryptDataToPgpMessage(
            linkPassphraseBytes, sharePublicKey,
        )
        val linkPassphraseSignature = cryptoHelper.signData(
            linkPassphraseBytes, signingKey.unlockedKeyBytes,
        )

        // ─── Folder name ──────────────────────────────────────────────────────
        // Encrypted-and-signed PGP MESSAGE wrapping the literal "PhotosRoot" string.
        // Uses the share public key so anyone holding the share key can decrypt it.
        // Official client: `VolumeInfo.DEFAULT_PHOTOS_ROOT_FOLDER_NAME`.
        val folderName = cryptoHelper.encryptName(
            DEFAULT_PHOTOS_ROOT_FOLDER_NAME,
            sharePublicKey,
            signingKey.unlockedKeyBytes,
        )

        // ─── NodeHashKey ──────────────────────────────────────────────────────
        // Random 32-byte HMAC seed wrapped in an encrypted+signed PGP MESSAGE addressed
        // to the link's own public key. Used by every child link's Hash field later.
        val hashKey = cryptoContext.pgpCrypto.generateNewHashKey()
        val nodeHashKey = try {
            cryptoContext.pgpCrypto.encryptAndSignData(
                hashKey.key,
                linkPublicKey,
                signingKey.unlockedKeyBytes,
                null,
            )
        } finally {
            hashKey.close()
        }

        // We never use shareKeyBytes locally after this point; wipe so a heap dump
        // can't recover it (the server stores the encrypted form).
        shareKeyBytes.fill(0)

        return CreatePhotosVolumeRequest(
            share = PhotosVolumeShare(
                addressId = signingKey.addressId,
                key = shareKeyArmored,
                passphrase = sharePassphraseEncrypted,
                passphraseSignature = sharePassphraseSignature,
                addressKeyId = signingKey.addressKeyId,
            ),
            link = PhotosVolumeLink(
                nodeKey = linkKeyArmored,
                nodePassphrase = linkPassphraseEncrypted,
                nodePassphraseSignature = linkPassphraseSignature,
                nodeHashKey = nodeHashKey,
                name = folderName,
            ),
        )
    }

    private companion object {
        /** Mirror of `VolumeInfo.DEFAULT_PHOTOS_ROOT_FOLDER_NAME` in the official client. */
        const val DEFAULT_PHOTOS_ROOT_FOLDER_NAME = "PhotosRoot"
    }
}
