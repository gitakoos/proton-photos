package eu.akoos.photos.data.repository.drive

import android.util.Log
import kotlinx.coroutines.delay
import me.proton.core.crypto.common.pgp.SessionKey
import eu.akoos.photos.data.crypto.DriveCryptoHelper
import eu.akoos.photos.util.retryAfterMs
import eu.akoos.photos.util.retryWithBackoff
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "ThumbnailHelpers"

/**
 * Thumbnail download + on-disk cache management, shared by [PhotoStreamService],
 * `AlbumService`, and `PhotoDownloadService`.
 *
 * Owns its own [OkHttpClient] — the thumbnail CDN call needs the `pm-storage-token`
 * header instead of the standard Drive `Authorization: Bearer ...`, so it bypasses the
 * ApiProvider stack entirely.
 */
@Singleton
class ThumbnailHelpers @Inject constructor(
    private val cryptoHelper: DriveCryptoHelper,
) {
    private val httpClient = OkHttpClient()

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

            val reqBuilder = Request.Builder().url(info.bareUrl)
            // Thumbnail CDN uses pm-storage-token (same as block CDN), not Authorization: Bearer.
            if (info.token != null) reqBuilder.header("pm-storage-token", info.token)
            val encryptedBytes: ByteArray = retryWithBackoff(maxAttempts = 3) { attempt ->
                httpClient.newCall(reqBuilder.build()).execute().use { resp ->
                    if (resp.code == 429 || resp.code == 503) {
                        val ra = resp.retryAfterMs()
                        if (ra != null) delay(ra)
                        error("HTTP ${resp.code} on thumbnail download (attempt ${attempt + 1})")
                    }
                    if (!resp.isSuccessful) {
                        Log.w(TAG, "thumbnail download failed HTTP ${resp.code} for linkId=$linkId")
                        return@retryWithBackoff ByteArray(0)
                    }
                    resp.body?.bytes() ?: ByteArray(0)
                }
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
