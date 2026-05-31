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
import me.proton.core.crypto.common.pgp.SessionKey
import eu.akoos.photos.data.crypto.DriveCryptoHelper
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "ThumbnailHelpers"

/**
 * Thumbnail download + on-disk cache management, shared by [PhotoStreamService],
 * `AlbumService`, and `PhotoDownloadService`.
 *
 * Delegates the raw CDN GET to [CdnBlockFetcher] — the thumbnail CDN call needs the
 * `pm-storage-token` header instead of the standard Drive `Authorization: Bearer ...`,
 * so it bypasses the ApiProvider stack entirely.
 */
@Singleton
class ThumbnailHelpers @Inject constructor(
    private val cryptoHelper: DriveCryptoHelper,
    private val cdnBlockFetcher: CdnBlockFetcher,
) {

    /**
     * True when the DB-cached [thumbnailUrl] still resolves to a real file on disk (or is
     * a remote http URL). Used to avoid serving stale `file://` paths whose backing cache
     * file was wiped by the OS.
     */
    fun isCachedValid(thumbnailUrl: String?): Boolean {
        if (thumbnailUrl.isNullOrEmpty()) return false
        if (!thumbnailUrl.startsWith("file://")) return true // remote URL — trust it
        val path = thumbnailUrl.removePrefix("file://")
        val f = File(path)
        return f.exists() && f.length() > 0
    }

    /**
     * Downloads and decrypts a photo thumbnail. Thumbnails are SEIPD-only data (same
     * format as content blocks), encrypted with the session key from ContentKeyPacket —
     * NOT as full PKESK+SEIPD messages to the node key.
     *
     * Falls back to binary-PGP node-key decrypt for legacy blobs where the session key
     * fails.
     *
     * @param sessionKey Session key decrypted from ContentKeyPacket (primary path).
     * @return A `file://` URI to the decrypted JPEG cached on disk, or null on failure.
     */
    suspend fun downloadAndDecryptBinary(
        info: ThumbnailUrlInfo,
        nodeKeyBytes: ByteArray,
        sessionKey: SessionKey?,
        linkId: String,
        cacheDir: File,
    ): String? {
        return try {
            val decFile = File(cacheDir, "thumb_$linkId.jpg")
            if (decFile.exists() && decFile.length() > 0) return "file://${decFile.absolutePath}"

            // Thumbnail CDN uses pm-storage-token (same as block CDN), not Authorization: Bearer.
            val encryptedBytes: ByteArray = try {
                cdnBlockFetcher.fetchBlock(url = info.bareUrl, token = info.token, maxAttempts = 3)
            } catch (e: Exception) {
                // Thumbnails are best-effort — a non-2xx after retries means no thumbnail,
                // not a failed photo. Log and fall through to the empty-bytes short-circuit
                // below so the upper layer surfaces null (and the gallery falls back to a
                // placeholder) instead of bubbling the error to the user.
                Log.w(TAG, "thumbnail download failed for linkId=$linkId: ${e.message}")
                ByteArray(0)
            }
            if (encryptedBytes.isEmpty()) return null

            val decrypted: ByteArray? = if (sessionKey != null) {
                // Primary path: session key from ContentKeyPacket (SEIPD-only blocks).
                val encFile = File(cacheDir, "thumb_enc_$linkId")
                val outFile = File(cacheDir, "thumb_dec_$linkId")
                encFile.writeBytes(encryptedBytes)
                val result = runCatching { cryptoHelper.decryptFileToDestination(sessionKey, encFile, outFile) }
                encFile.delete()
                if (result.isSuccess) {
                    val bytes = outFile.readBytes(); outFile.delete(); bytes
                } else {
                    outFile.delete()
                    Log.w(TAG, "thumbnail sessionKey decrypt failed for $linkId: ${result.exceptionOrNull()?.message}")
                    // Fallback: try binary-PGP (legacy format)
                    cryptoHelper.decryptBinaryPgpWithNodeKey(encryptedBytes, nodeKeyBytes)
                }
            } else {
                // No session key — try binary-PGP (legacy full PKESK+SEIPD format)
                cryptoHelper.decryptBinaryPgpWithNodeKey(encryptedBytes, nodeKeyBytes)
            }

            if (decrypted == null) {
                Log.w(TAG, "thumbnail decrypt returned null for linkId=$linkId (sessionKey=${sessionKey != null})")
                return null
            }
            decFile.writeBytes(decrypted)
            "file://${decFile.absolutePath}"
        } catch (e: Exception) {
            Log.w(TAG, "downloadAndDecryptBinary failed linkId=$linkId: ${e.message}")
            null
        }
    }
}
