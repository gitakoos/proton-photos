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
import kotlinx.coroutines.sync.withPermit
import me.proton.core.domain.entity.UserId
import me.proton.core.network.data.ApiProvider
import eu.akoos.photos.data.api.DriveApiService
import eu.akoos.photos.data.api.dto.BatchLinkDto
import eu.akoos.photos.data.api.dto.BatchLinksRequest
import eu.akoos.photos.data.api.dto.ThumbnailBatchRequest
import eu.akoos.photos.util.isTransientApiError
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "LinkDetailHelpers"
private const val BATCH_SIZE = 50
/** Drive `/drive/volumes/{volumeId}/thumbnails` rejects requests with more than 30 IDs per
 *  call. The general batchGetLinks endpoint accepts our [BATCH_SIZE] (50), but thumbnail URL
 *  resolution is throttled tighter server-side, so we chunk it smaller. */
private const val THUMBNAIL_BATCH_SIZE = 30

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
     * how to react to a partial map.
     *
     * A TRANSIENT failure (429 / 5xx / network — see [isTransientApiError]) PROPAGATES instead
     * of being swallowed: a rate-limited chunk must surface as a thrown error so the caller's
     * retry + failure accounting can act, not return a short map that looks like success and
     * silently truncates the listing. A genuinely non-transient single-chunk quirk is still
     * logged and skipped so one odd link can't fail the whole batch.
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
                if (e is kotlinx.coroutines.CancellationException) throw e
                if (isTransientApiError(e)) throw e
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
                if (e is kotlinx.coroutines.CancellationException) throw e
                // Propagate a rate-limit / transient error so the caller retries instead of
                // building rows with missing content-key packets (undecryptable thumbnails).
                if (isTransientApiError(e)) throw e
                Log.w(TAG, "batchFetchContentKeyPackets failed: ${e.message}")
            }
        }
        Log.d(TAG, "batchFetchContentKeyPackets: got ${result.size}/${linkIds.size} CKPs")
        return result
    }

    /**
     * Cross-account variant of [batchFetchLinkDetails] for shared-with-me content.
     * Goes through `POST drive/shares/{shareId}/links/fetch_metadata` instead of the
     * volume endpoint, which returns the full [LinkCoreDto] (size, mimeType,
     * fileProperties, contentKeyPacket — everything) for any link the recipient can
     * see via the share. The volume endpoint partially blanks those fields when the
     * caller isn't a direct member of the owner's photos volume, so the Details
     * sheet shows empty Size / Type rows; this path fills them in.
     *
     * The result is wrapped in a [BatchLinkDto] so the rest of the pipeline can
     * consume it without branching — the `photo` / `album` / `folder` / `sharing`
     * sub-fields stay null because the share endpoint doesn't surface them, but
     * downstream callers only ever read `detail.link` for shared-album photos.
     */
    suspend fun batchFetchLinkDetailsViaShare(
        userId: UserId,
        shareId: String,
        linkIds: List<String>,
    ): Map<String, eu.akoos.photos.data.api.dto.BatchLinkDto> {
        val result = mutableMapOf<String, eu.akoos.photos.data.api.dto.BatchLinkDto>()
        val manager = apiProvider.get<DriveApiService>(userId)
        for (chunk in linkIds.chunked(BATCH_SIZE)) {
            try {
                shareService.networkSemaphore.withPermit {
                    val resp = manager.invoke {
                        fetchLinkMetadata(shareId, BatchLinksRequest(chunk))
                    }.valueOrThrow
                    for (link in resp.links) {
                        result[link.linkId] = eu.akoos.photos.data.api.dto.BatchLinkDto(link = link)
                    }
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.w(TAG, "batchFetchLinkDetailsViaShare failed: ${e.message}")
            }
        }
        Log.d(TAG, "batchFetchLinkDetailsViaShare: got ${result.size}/${linkIds.size} link details")
        return result
    }

    /**
     * Fetches one photo's encrypted XAttr from its active revision. The link-metadata endpoints
     * (volume + share) omit the revision XAttr; only the revision endpoint returns it. Tries the
     * volume revision endpoint first, then the share-based one, mirroring PhotoDownloadService.
     */
    suspend fun fetchRevisionXAttr(
        userId: UserId,
        volumeId: String,
        shareId: String,
        linkId: String,
        revisionId: String,
    ): String? {
        val manager = apiProvider.get<DriveApiService>(userId)
        return shareService.networkSemaphore.withPermit {
            runCatching {
                manager.invoke { getRevisionByVolume(volumeId, linkId, revisionId) }.valueOrThrow.revision.xAttr
            }.getOrNull()
                ?: runCatching {
                    manager.invoke { getRevision(shareId, linkId, revisionId) }.valueOrThrow.revision.xAttr
                }.getOrNull()
        }
    }

    /**
     * Like [fetchRevisionXAttr], but distinguishes a successful fetch with no XAttr (returns null)
     * from a fetch that FAILED (rethrows the underlying error after the volume + share fallbacks are
     * both exhausted). The swallow-and-return-null shape of [fetchRevisionXAttr] can't tell those
     * apart; the GPS backfill needs to, so it only marks a row checked once its revision was actually
     * read and retries the ones whose fetch threw. Rides the same [PhotosShareService.networkSemaphore]
     * permit pool.
     */
    suspend fun fetchRevisionXAttrOrThrow(
        userId: UserId,
        volumeId: String,
        shareId: String,
        linkId: String,
        revisionId: String,
    ): String? {
        val manager = apiProvider.get<DriveApiService>(userId)
        return shareService.networkSemaphore.withPermit {
            runCatching {
                manager.invoke { getRevisionByVolume(volumeId, linkId, revisionId) }.valueOrThrow.revision.xAttr
            }.getOrElse {
                manager.invoke { getRevision(shareId, linkId, revisionId) }.valueOrThrow.revision.xAttr
            }
        }
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
        // Tighter chunk size for the thumbnails endpoint — server returns
        // "This collection should contain 30 elements or less." for anything larger,
        // which silently dropped photo thumbnail URL resolution and left the gallery
        // grid showing placeholder tiles even though album covers (different code path)
        // worked fine.
        for (chunk in thumbnailIds.chunked(THUMBNAIL_BATCH_SIZE)) {
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
                if (e is kotlinx.coroutines.CancellationException) throw e
                // A rate-limit / transient error propagates so the caller's retry runs rather
                // than the grid silently sticking on placeholder tiles for the dropped chunk.
                if (isTransientApiError(e)) throw e
                Log.w(TAG, "batchFetchThumbnailUrls chunk failed: ${e.message}")
            }
        }
        return result
    }
}
