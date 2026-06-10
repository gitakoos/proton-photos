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

package eu.akoos.photos.data.repository.drive

import android.util.Log
import eu.akoos.photos.data.crypto.DriveCryptoHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Single source of truth for "which key decrypts what" in the album sharing
 * chain. Centralises three pieces of logic that used to live in 8+ places with
 * subtle policy drift between them:
 *
 *   1. **Album NodeKey decrypt** — every call site walks the same `try { ... }
 *      catch { Log.w + null }` boilerplate. [decryptAlbumKey] is the canonical
 *      wrapper so the surrounding code never has to re-implement the policy.
 *
 *   2. **Photo parent-key selection** — the question "which key does this
 *      photo's NodePassphrase decrypt under?" historically had three different
 *      answers spread across [AlbumService], [PhotoStreamService], and
 *      [ThumbnailDecryptScheduler]. [selectPhotoParentKey] codifies the rule
 *      that mirrors Drive Android's `BuildNodeKey.kt:86-103` + `CreateAddToAlbumInfo.kt:77-92`:
 *      after `addPhotosToAlbum` runs, every photo's wire NodePassphrase is
 *      encrypted to the ALBUM NodeKey regardless of where the link physically
 *      sits in the volume.
 *
 *   3. **Sharing context bundle** — bootstrapping a shared share to recover
 *      its private key, then using that key to unlock the album NodeKey, was
 *      open-coded in [AlbumService.loadAlbumPhotos]. [SharingContext] packages
 *      the share + album-key pair so any downstream code (notably the
 *      thumbnail scheduler's fallback fetch) can reach for the right keys
 *      without re-running the bootstrap.
 *
 * The helper is intentionally stateless — no caching, no I/O for the
 * pure-policy methods. The bootstrap convenience [decryptSharedShareKey] does
 * one network round-trip but does not memoise; cache invalidation is the
 * caller's concern. Keeping the helper free of mutable state lets the
 * scheduler, AlbumService, and PhotoStreamService share it without coupling
 * to each other's lifecycles.
 */
@Singleton
class AlbumCryptoChain @Inject constructor(
    private val cryptoHelper: DriveCryptoHelper,
) {

    /**
     * Decrypts an album link's NodeKey to its raw private-key bytes. Returns
     * `null` on any failure with a Warn-level log carrying the optional
     * [contextHint] so a logcat capture pinpoints the failing call site
     * without grep-archaeology.
     *
     * Note: the underlying [DriveCryptoHelper.decryptNodeKey] is suspending
     * and acquires its own crypto lock, so this wrapper does NOT need its
     * own lock or dispatcher.
     */
    suspend fun decryptAlbumKey(
        nodeKeyArmored: String,
        nodePassphraseArmored: String,
        parentKeyBytes: ByteArray,
        contextHint: String = "",
    ): ByteArray? = withContext(Dispatchers.IO) {
        try {
            cryptoHelper.decryptNodeKey(nodeKeyArmored, nodePassphraseArmored, parentKeyBytes)
        } catch (e: Exception) {
            Log.w(
                TAG,
                "decryptAlbumKey failed${if (contextHint.isNotBlank()) " ($contextHint)" else ""}: ${e.message}",
            )
            null
        }
    }

    /**
     * Picks the parent key bytes a photo's NodePassphrase decrypts under when
     * the photo is being loaded inside an album. This is the rule:
     *
     *   • **Shared-with-me album** ([sharingContext] non-null) → always
     *     [SharingContext.albumKeyBytes]. The sender re-encrypts every
     *     photo's NodePassphrase to the album's public key when calling
     *     `addPhotosToAlbum`, so the recipient — who only has the album key
     *     via the share chain — can always unlock it. Falling back to a root
     *     key here would surface the RECIPIENT's own root link key (not the
     *     owner's), which is never going to decrypt the owner-encrypted
     *     payload.
     *
     *   • **Owner-side album, post-add-to-album rewrap** → [albumKeyBytes].
     *     Photos added via `AlbumService.addPhotosToAlbum` have their wire
     *     NodePassphrase encrypted to the album's public key even though
     *     their wire `parentLinkId` continues to point at the photos root
     *     (the photo isn't physically moved — only the passphrase wrapping
     *     changes). Preferring the album key here is the correct first
     *     attempt.
     *
     *   • **Legacy data** → [rootLinkKeyBytes]. Photos that pre-date the
     *     add-to-album rewrap still have their NodePassphrase encrypted to
     *     the photos root link key. This fallback is exercised when the
     *     album key is unavailable for any reason; it matches the old
     *     `AlbumService.kt:833-843` behaviour for back-compatibility.
     *
     * Returns null when no key fits the situation — the caller should treat
     * that as a hard "cannot decrypt" and skip the photo with a Warn log
     * rather than fall through to a different key by accident.
     */
    fun selectPhotoParentKey(
        rootLinkKeyBytes: ByteArray?,
        albumKeyBytes: ByteArray?,
        photoParentLinkId: String?,
        photosRootLinkId: String?,
        sharingContext: SharingContext?,
    ): ByteArray? {
        // Shared-with-me path: only the album key works for the recipient.
        // The recipient's own root link key is unrelated to whatever the
        // owner used at upload time.
        if (sharingContext != null) {
            return albumKeyBytes ?: sharingContext.albumKeyBytes
        }
        // Owner-side: prefer the album key (the post-rewrap target).
        if (albumKeyBytes != null) return albumKeyBytes
        // Legacy fallback: photos that never went through addPhotosToAlbum
        // still sit under root.
        if (photoParentLinkId == null || photoParentLinkId == photosRootLinkId) {
            return rootLinkKeyBytes
        }
        return null
    }

    /**
     * Bundle of keys + ids needed to read a shared-with-me album end-to-end.
     * The thumbnail scheduler, [PhotoEntityBuilder], and the recipient-side
     * load path all need the same set; passing them as one record keeps the
     * "did we resolve the share yet?" question single-valued instead of
     * scattered across `if (sharingShareId != null && albumKeyBytes != null
     * && sharedAlbumParentKeyBytes != null)` chains.
     */
    data class SharingContext(
        /** The album link's id — same in owner's and recipient's views; used to
         *  index the scheduler's parent-key cache and as the override target on
         *  recipient-side photo entities whose wire parentLinkId is meaningless
         *  to the recipient. */
        val albumLinkId: String,
        /** The share id we used to bootstrap the chain. Lets downstream code
         *  re-fetch link details via the share endpoint (`fetch_metadata`)
         *  instead of the recipient's own volume endpoint, which 404s for
         *  links the recipient doesn't actually own. */
        val sharingShareId: String,
        /** Decrypted share private-key bytes. Used to unlock the album NodeKey
         *  on cold cache, and as the second decryption candidate for photo
         *  Name PKESKs whose target is the share key rather than the album. */
        val sharedShareKeyBytes: ByteArray,
        /** Decrypted album NodeKey bytes. The "right answer" for unlocking
         *  every photo's NodePassphrase inside this album. */
        val albumKeyBytes: ByteArray,
    )

    companion object {
        private const val TAG = "AlbumCryptoChain"
    }
}
