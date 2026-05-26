package me.proton.photos.data.repository.drive

import android.util.Log
import kotlinx.coroutines.sync.withPermit
import me.proton.core.domain.entity.UserId
import me.proton.core.network.data.ApiProvider
import me.proton.photos.data.api.DriveApiService
import me.proton.photos.data.api.dto.BatchLinkDto
import me.proton.photos.data.api.dto.BatchLinksRequest
import me.proton.photos.data.api.dto.ThumbnailBatchRequest
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "LinkDetailHelpers"
private const val BATCH_SIZE = 50

/** Pre-fetched thumbnail download coordinates from the batch URL endpoint. */
data class ThumbnailUrlInfo(val bareUrl: String, val token: String?)

/**
 * Batch lookups against `drive/volumes/{volumeId}/links` + Photos thumbnail-URL endpoint,
 * shared by several Drive services. Uses [PhotosShareService.networkSemaphore] so
 * concurrent batch fetches share the same 4-slot Drive API permit pool.
 */
@Singleton
class LinkDetailHelpers @Inject constructor(
    private val apiProvider: ApiProvider,
    private val shareService: PhotosShareService,
) {
    /**
     * Returns linkId → BatchLinkDto for every link in [linkIds] that the server returns.
     * Missing entries (404, permission errors) are silently omitted; the caller decides
     * how to react to a partial map. Each chunk failure is logged and skipped, mirroring
     * the original façade behavior.
     */
    suspend fun batchFetchLinkDetails(
        userId: UserId,
        volumeId: String,
        linkIds: List<String>,
    ): Map<String, BatchLinkDto> {
        val result = mutableMapOf<String, BatchLinkDto>()
        val manager = apiProvider.get<DriveApiService>(userId)
        for (chunk in linkIds.chunked(BATCH_SIZE)) {
            try {
                shareService.networkSemaphore.withPermit {
                    val response = manager.invoke {
                        batchGetLinks(volumeId, BatchLinksRequest(chunk))
                    }.valueOrThrow
                    for (dto in response.links) {
                        result[dto.link.linkId] = dto
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "batchFetchLinkDetails chunk failed: ${e.message}")
            }
        }
        return result
    }

    /**
     * Batch-fetches ContentKeyPackets for a list of photo linkIds using the standard
     * Drive API (POST drive/shares/{shareId}/links/fetch_metadata). Unlike the Photos
     * batch endpoint, this returns the full LinkDto with FileProperties.ContentKeyPacket.
     *
     * Returns linkId → ContentKeyPacket (base64-encoded PKESK).
     */
    suspend fun batchFetchContentKeyPackets(
        userId: UserId,
        shareId: String,
        linkIds: List<String>,
    ): Map<String, String> {
        val result = mutableMapOf<String, String>()
        val manager = apiProvider.get<DriveApiService>(userId)
        for (chunk in linkIds.chunked(BATCH_SIZE)) {
            try {
                shareService.networkSemaphore.withPermit {
                    val resp = manager.invoke {
                        fetchLinkMetadata(shareId, BatchLinksRequest(chunk))
                    }.valueOrThrow
                    for (link in resp.links) {
                        val ckp = link.fileProperties?.contentKeyPacket ?: continue
                        result[link.linkId] = ckp
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "batchFetchContentKeyPackets failed: ${e.message}")
            }
        }
        Log.d(TAG, "batchFetchContentKeyPackets: got ${result.size}/${linkIds.size} CKPs")
        return result
    }

    /**
     * Batch-fetches thumbnail download URLs for a list of ThumbnailIDs using the
     * POST /drive/volumes/{volumeId}/thumbnails endpoint.
     *
     * Returns ThumbnailID → [ThumbnailUrlInfo] (BareURL + Token).
     */
    suspend fun batchFetchThumbnailUrls(
        userId: UserId,
        volumeId: String,
        thumbnailIds: List<String>,
    ): Map<String, ThumbnailUrlInfo> {
        val result = mutableMapOf<String, ThumbnailUrlInfo>()
        val manager = apiProvider.get<DriveApiService>(userId)
        for (chunk in thumbnailIds.chunked(BATCH_SIZE)) {
            try {
                shareService.networkSemaphore.withPermit {
                    val response = manager.invoke {
                        getThumbnailUrls(volumeId, ThumbnailBatchRequest(chunk))
                    }.valueOrThrow
                    for (dto in response.thumbnails) {
                        val url = dto.bareUrl ?: continue
                        result[dto.thumbnailId] = ThumbnailUrlInfo(url, dto.token)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "batchFetchThumbnailUrls chunk failed: ${e.message}")
            }
        }
        return result
    }
}
