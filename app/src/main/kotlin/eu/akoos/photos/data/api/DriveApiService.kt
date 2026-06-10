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

package eu.akoos.photos.data.api

import me.proton.core.network.data.protonApi.BaseRetrofitApi
import eu.akoos.photos.data.api.dto.BaseResponse
import eu.akoos.photos.data.api.dto.CommitRevisionRequest
import eu.akoos.photos.data.api.dto.CommitRevisionV2Request
import eu.akoos.photos.data.api.dto.CreateFileRequest
import eu.akoos.photos.data.api.dto.CreateFileResponse
import eu.akoos.photos.data.api.dto.CreateRevisionResponse
import eu.akoos.photos.data.api.dto.DeleteLinksRequest
import eu.akoos.photos.data.api.dto.LinkDetailsResponse
import eu.akoos.photos.data.api.dto.PhotoLinksResponse
import eu.akoos.photos.data.api.dto.ShareDetailsResponse
import eu.akoos.photos.data.api.dto.SharesResponse
import eu.akoos.photos.data.api.dto.ThumbnailBatchRequest
import eu.akoos.photos.data.api.dto.ThumbnailBatchResponse
import eu.akoos.photos.data.api.dto.ThumbnailResponse
import eu.akoos.photos.data.api.dto.UploadBlockRequest
import eu.akoos.photos.data.api.dto.UploadBlockResponse
import eu.akoos.photos.data.api.dto.AlbumChildrenResponse
import eu.akoos.photos.data.api.dto.AlbumsResponse
import eu.akoos.photos.data.api.dto.BatchLinksRequest
import eu.akoos.photos.data.api.dto.BatchLinksResponse
import eu.akoos.photos.data.api.dto.AddAlbumMultipleRequest
import eu.akoos.photos.data.api.dto.CreateAlbumRequest
import eu.akoos.photos.data.api.dto.CreateAlbumResponse
import eu.akoos.photos.data.api.dto.CreatePhotoRequest
import eu.akoos.photos.data.api.dto.CreatePhotoResponse
import eu.akoos.photos.data.api.dto.CreatePhotosVolumeRequest
import eu.akoos.photos.data.api.dto.CreatePhotosVolumeResponse
import eu.akoos.photos.data.api.dto.VolumeTrashResponse
import eu.akoos.photos.data.api.dto.PhotosShareResponse
import eu.akoos.photos.data.api.dto.CreateShareUrlRequest
import eu.akoos.photos.data.api.dto.CreateShareUrlResponse
import eu.akoos.photos.data.api.dto.ShareUrlsResponse
import eu.akoos.photos.data.api.dto.FullLinkResponse
import eu.akoos.photos.data.api.dto.ShareInvitationsResponse
import eu.akoos.photos.data.api.dto.SharedWithMeResponse
import eu.akoos.photos.data.api.dto.GlobalInvitationsResponse
import eu.akoos.photos.data.api.dto.InvitationDetailResponse
import eu.akoos.photos.data.api.dto.ShareMembersResponse
import eu.akoos.photos.data.api.dto.DriveEventsResponse
import eu.akoos.photos.data.api.dto.EventAnchorResponse
import eu.akoos.photos.data.api.dto.RevisionListResponse
import eu.akoos.photos.data.api.dto.RevisionResponse
import eu.akoos.photos.data.api.dto.VerificationDataResponse
import eu.akoos.photos.data.api.dto.VolumesResponse
import okhttp3.MultipartBody
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Part
import retrofit2.http.Path
import retrofit2.http.Query
import retrofit2.http.Url

interface DriveApiService : BaseRetrofitApi {

    @GET("drive/volumes")
    suspend fun getVolumes(): VolumesResponse

    @POST("drive/photos/volumes")
    suspend fun createOrGetPhotosVolume(@Body body: CreatePhotosVolumeRequest): CreatePhotosVolumeResponse

    @GET("drive/volumes/{volumeId}/shares")
    suspend fun getShares(@Path("volumeId") volumeId: String): SharesResponse

    @GET("drive/v2/shares/photos")
    suspend fun getPhotosShare(): PhotosShareResponse

    @GET("drive/v2/shares/{shareId}")
    suspend fun getShareById(@Path("shareId") shareId: String): ShareDetailsResponse

    // v1 share-bootstrap endpoint — returns a flat Share object (Key, Passphrase, AddressID,
    // LinkID, VolumeID at the top level, no wrapper). Use this for shares that the v2 path
    // 404s on — newly created album/photo shares fall into that bucket; the v2 endpoint
    // appears to only work for already-migrated main shares.
    @GET("drive/shares/{shareId}")
    suspend fun getShareBootstrap(@Path("shareId") shareId: String): eu.akoos.photos.data.api.dto.ShareBootstrapResponse

    @GET("drive/photos/volumes/{volumeId}/albums")
    suspend fun getAlbums(
        @Path("volumeId") volumeId: String,
        @Query("AnchorID") anchorId: String? = null,
    ): AlbumsResponse

    @GET("drive/photos/volumes/{volumeId}/albums/{albumLinkId}/children")
    suspend fun getAlbumChildren(
        @Path("volumeId") volumeId: String,
        @Path("albumLinkId") albumLinkId: String,
        @Query("AnchorID") anchorId: String? = null,
    ): AlbumChildrenResponse

    @POST("drive/photos/volumes/{volumeId}/links")
    suspend fun batchGetLinks(
        @Path("volumeId") volumeId: String,
        @Body request: BatchLinksRequest,
    ): BatchLinksResponse

    @POST("drive/photos/volumes/{volumeId}/albums")
    suspend fun createAlbum(
        @Path("volumeId") volumeId: String,
        @Body request: CreateAlbumRequest,
    ): CreateAlbumResponse

    /**
     * Server-side photo / link copy. The endpoint takes the SOURCE volume + link in
     * the path (we copy from a shared volume into the caller's own volume), the
     * TARGET volume + parent link inside the body, and re-encrypted metadata —
     * node passphrase, link name, hash — so the backend can rewrap the existing
     * encrypted blob under the new target tree without ever returning the bytes.
     * Powers "Save shared album to my library" on the recipient side.
     */
    @POST("drive/volumes/{volumeId}/links/{linkId}/copy")
    suspend fun copyLink(
        @Path("volumeId") volumeId: String,
        @Path("linkId") linkId: String,
        @Body request: eu.akoos.photos.data.api.dto.CopyLinkRequest,
    ): eu.akoos.photos.data.api.dto.CopyLinkResponse

    @POST("drive/photos/volumes/{volumeId}/albums/{albumLinkId}/add-multiple")
    suspend fun addPhotosToAlbum(
        @Path("volumeId") volumeId: String,
        @Path("albumLinkId") albumLinkId: String,
        @Body request: AddAlbumMultipleRequest,
    ): eu.akoos.photos.data.api.dto.AddAlbumMultipleResponse

    // POST /drive/photos/volumes/{volumeId}/albums/{albumLinkId}/remove-multiple
    // Removes the album-reference for each listed linkId. Photos stay in Photos root.
    @POST("drive/photos/volumes/{volumeId}/albums/{albumLinkId}/remove-multiple")
    suspend fun removePhotosFromAlbum(
        @Path("volumeId") volumeId: String,
        @Path("albumLinkId") albumLinkId: String,
        @Body request: eu.akoos.photos.data.api.dto.RemoveFromAlbumRequest,
    ): BaseResponse

    // PUT /drive/photos/volumes/{volumeId}/albums/{albumLinkId}
    // Updates the album: rename (via Link.Name + Hash + …), set CoverLinkID, or both.
    @PUT("drive/photos/volumes/{volumeId}/albums/{albumLinkId}")
    suspend fun updateAlbum(
        @Path("volumeId") volumeId: String,
        @Path("albumLinkId") albumLinkId: String,
        @Body request: eu.akoos.photos.data.api.dto.UpdateAlbumRequest,
    ): BaseResponse

    // DELETE /drive/photos/volumes/{volumeId}/albums/{albumLinkId}?DeleteAlbumPhotos=0
    // Deletes the album; photos are NOT deleted when DeleteAlbumPhotos=0.
    @DELETE("drive/photos/volumes/{volumeId}/albums/{albumLinkId}")
    suspend fun deleteAlbum(
        @Path("volumeId") volumeId: String,
        @Path("albumLinkId") albumLinkId: String,
        @Query("DeleteAlbumPhotos") deleteAlbumPhotos: Int = 0,
    ): BaseResponse

    // POST /drive/v2/volumes/{volumeId}/trash_multiple
    // Moves cloud photos to server-side trash (recoverable from recently deleted).
    @POST("drive/v2/volumes/{volumeId}/trash_multiple")
    suspend fun trashPhotos(
        @Path("volumeId") volumeId: String,
        @Body request: DeleteLinksRequest,
    ): BaseResponse

    // ── Favorites / Tags ──────────────────────────────────────────────────────
    // POST /drive/photos/volumes/{volumeID}/links/{linkID}/favorite
    // Marks a photo as Favorite. PhotoData may be null for the simple case (server
    // handles the re-encryption to the automatic Favorites collection); when set, it
    // carries the re-encrypted passphrase/name/hash. Match the official Drive Android API.
    @POST("drive/photos/volumes/{volumeId}/links/{linkId}/favorite")
    suspend fun addFavorite(
        @Path("volumeId") volumeId: String,
        @Path("linkId") linkId: String,
        @Body request: eu.akoos.photos.data.api.dto.FavoriteRequest,
    ): BaseResponse

    // POST /drive/photos/volumes/{volumeID}/links/{linkID}/tags
    @POST("drive/photos/volumes/{volumeId}/links/{linkId}/tags")
    suspend fun addPhotoTags(
        @Path("volumeId") volumeId: String,
        @Path("linkId") linkId: String,
        @Body request: eu.akoos.photos.data.api.dto.TagRequest,
    ): BaseResponse

    // DELETE /drive/photos/volumes/{volumeID}/links/{linkID}/tags (with body)
    // Used to remove the favorite tag (id 0) from a photo when un-favoriting.
    @retrofit2.http.HTTP(method = "DELETE", path = "drive/photos/volumes/{volumeId}/links/{linkId}/tags", hasBody = true)
    suspend fun deletePhotoTags(
        @Path("volumeId") volumeId: String,
        @Path("linkId") linkId: String,
        @Body request: eu.akoos.photos.data.api.dto.TagRequest,
    ): BaseResponse

    // GET drive/volumes/{volumeId}/trash — full Drive trash (all link types), page-based.
    // Web client confirmed path: /api/drive/volumes/{id}/trash?Page=N (0-indexed, no "v2").
    // Filter client-side for image/* / video/* to show only photos/videos.
    @GET("drive/volumes/{volumeId}/trash")
    suspend fun getVolumeTrash(
        @Path("volumeId") volumeId: String,
        @Query("Page") page: Int = 0,
    ): VolumeTrashResponse

    // PUT drive/v2/volumes/{volumeId}/trash/restore_multiple — move trashed items back to Photos stream.
    // Verb + path confirmed via ProtonDriveApps/android-drive DriveTrashApi.
    @PUT("drive/v2/volumes/{volumeId}/trash/restore_multiple")
    suspend fun restoreFromTrash(
        @Path("volumeId") volumeId: String,
        @Body request: DeleteLinksRequest,
    ): eu.akoos.photos.data.api.dto.TrashActionResponse

    // POST drive/v2/volumes/{volumeId}/trash/delete_multiple — permanently delete trashed items.
    // Path confirmed via ProtonDriveApps/android-drive DriveTrashApi (the prior
    // .../delete_multiple path returned 200 but silently no-op'd).
    @POST("drive/v2/volumes/{volumeId}/trash/delete_multiple")
    suspend fun deleteForever(
        @Path("volumeId") volumeId: String,
        @Body request: DeleteLinksRequest,
    ): eu.akoos.photos.data.api.dto.TrashActionResponse

    // Correct endpoint confirmed from HAR: drive/volumes/{id}/photos (no "photos/" prefix).
    // drive/photos/volumes/{id}/photos returns 404 on most accounts.
    @GET("drive/volumes/{volumeId}/photos")
    suspend fun getPhotoLinks(
        @Path("volumeId") volumeId: String,
        @Query("PreviousPageLastLinkID") anchorId: String? = null,
        @Query("Limit") limit: Int = 500,
    ): PhotoLinksResponse

    // Batch fetch thumbnail download URLs by ThumbnailID.
    // POST /drive/volumes/{volumeId}/thumbnails
    // Request: {"ThumbnailIDs": [...]}  Response: {"Thumbnails": [{ThumbnailID, BareURL, Token}]}
    @POST("drive/volumes/{volumeId}/thumbnails")
    suspend fun getThumbnailUrls(
        @Path("volumeId") volumeId: String,
        @Body request: ThumbnailBatchRequest,
    ): ThumbnailBatchResponse

    // Legacy single-revision thumbnail endpoint (kept for possible fallback)
    @GET("drive/v2/shares/{shareId}/files/{linkId}/revisions/{revisionId}/thumbnail")
    suspend fun getThumbnailLegacy(
        @Path("shareId") shareId: String,
        @Path("linkId") linkId: String,
        @Path("revisionId") revisionId: String,
    ): ThumbnailResponse

    @GET("drive/v2/shares/{shareId}/links/{linkId}")
    suspend fun getLinkDetails(
        @Path("shareId") shareId: String,
        @Path("linkId") linkId: String,
    ): LinkDetailsResponse

    // Canonical single-link endpoint per official Proton Drive Android SDK:
    // GET "drive/shares/{shareId}/links/{linkId}" — NO "v2" prefix.
    // This returns LinkDto with FileProperties.ContentKeyPacket.
    @GET("drive/shares/{shareId}/links/{linkId}")
    suspend fun getFullLinkDetails(
        @Path("shareId") shareId: String,
        @Path("linkId") linkId: String,
    ): FullLinkResponse

    // Batch link metadata — official SDK path (non-Photos, regular Drive shares).
    // Returns links directly (not wrapped) with full FileProperties.ContentKeyPacket.
    @POST("drive/shares/{shareId}/links/fetch_metadata")
    suspend fun fetchLinkMetadata(
        @Path("shareId") shareId: String,
        @Body request: BatchLinksRequest,
    ): eu.akoos.photos.data.api.dto.FetchLinksResponse

    @GET("drive/v2/volumes/{volumeId}/events/latest")
    suspend fun getLatestEventAnchor(
        @Path("volumeId") volumeId: String,
    ): EventAnchorResponse

    @GET("drive/v2/volumes/{volumeId}/events/{anchorId}")
    suspend fun getEvents(
        @Path("volumeId") volumeId: String,
        @Path("anchorId") anchorId: String,
    ): DriveEventsResponse

    @GET("drive/v2/shares/{shareId}/files/{linkId}/revisions")
    suspend fun listRevisions(
        @Path("shareId") shareId: String,
        @Path("linkId") linkId: String,
    ): RevisionListResponse

    @GET("drive/v2/shares/{shareId}/files/{linkId}/revisions/{revisionId}")
    suspend fun getRevision(
        @Path("shareId") shareId: String,
        @Path("linkId") linkId: String,
        @Path("revisionId") revisionId: String,
    ): RevisionResponse

    @GET("drive/v2/volumes/{volumeId}/files/{linkId}/revisions/{revisionId}")
    suspend fun getRevisionByVolume(
        @Path("volumeId") volumeId: String,
        @Path("linkId") linkId: String,
        @Path("revisionId") revisionId: String,
    ): RevisionResponse

    @GET("drive/v2/shares/{shareId}/files/{linkId}/revisions/{revisionId}/blocks/{blockIndex}")
    suspend fun downloadBlock(
        @Path("shareId") shareId: String,
        @Path("linkId") linkId: String,
        @Path("revisionId") revisionId: String,
        @Path("blockIndex") blockIndex: Int,
    ): okhttp3.ResponseBody

    @POST("drive/v2/shares/{shareId}/files")
    suspend fun createFile(
        @Path("shareId") shareId: String,
        @Body request: CreateFileRequest,
    ): CreateFileResponse

    // Photos volume photo creation — try all known API variants in fallback order.
    // Primary: drive/photos/volumes/{volumeId}/photos (stream-enabled accounts)
    @POST("drive/photos/volumes/{volumeId}/photos")
    suspend fun createPhoto(
        @Path("volumeId") volumeId: String,
        @Body request: CreatePhotoRequest,
    ): CreatePhotoResponse

    // Fallback: standard volume-based file creation (v2 volume path)
    @POST("drive/v2/volumes/{volumeId}/files")
    suspend fun createFileByVolume(
        @Path("volumeId") volumeId: String,
        @Body request: CreateFileRequest,
    ): CreateFileResponse

    @POST("drive/v2/shares/{shareId}/files/{linkId}/revisions")
    suspend fun createRevision(
        @Path("shareId") shareId: String,
        @Path("linkId") linkId: String,
    ): CreateRevisionResponse

    // GET drive/shares/{shareId}/links/{linkId}/revisions/{revisionId}/verification
    // Returns a VerificationCode token that must be included in each block's Verifier.Token field.
    @GET("drive/shares/{shareId}/links/{linkId}/revisions/{revisionId}/verification")
    suspend fun getVerificationData(
        @Path("shareId") shareId: String,
        @Path("linkId") linkId: String,
        @Path("revisionId") revisionId: String,
    ): VerificationDataResponse

    // Upload a block to CDN bare URL (dynamic URL, multipart POST).
    // Routing through ApiProvider ensures all Proton auth headers (Authorization, x-pm-appversion) are added.
    @Multipart
    @POST
    suspend fun uploadBlockCdn(
        @Url url: String,
        @Part block: MultipartBody.Part,
        @Header("pm-storage-token") storageToken: String,
    ): BaseResponse

    // POST drive/blocks — official Proton Drive blocks upload endpoint.
    // All file context (ShareID, LinkID, RevisionID, AddressID) is passed in the request body.
    @POST("drive/blocks")
    suspend fun requestUploadLinks(
        @Body request: UploadBlockRequest,
    ): UploadBlockResponse

    // Official Proton Drive endpoint for committing a revision (no v2 prefix).
    @PUT("drive/shares/{shareId}/files/{linkId}/revisions/{revisionId}")
    suspend fun commitRevision(
        @Path("shareId") shareId: String,
        @Path("linkId") linkId: String,
        @Path("revisionId") revisionId: String,
        @Body request: CommitRevisionRequest,
    ): BaseResponse

    // v2 volume-based commit — matches what the web client uses for Photos uploads.
    // Thumbnails are committed implicitly (no ThumbnailList needed) because the CDN
    // already associates them with the revision via RevisionID in POST drive/blocks.
    @PUT("drive/v2/volumes/{volumeId}/files/{linkId}/revisions/{revisionId}")
    suspend fun commitRevisionByVolume(
        @Path("volumeId") volumeId: String,
        @Path("linkId") linkId: String,
        @Path("revisionId") revisionId: String,
        @Body request: CommitRevisionV2Request,
    ): BaseResponse

    @POST("drive/v2/shares/{shareId}/links/delete_multiple")
    suspend fun deleteLinks(
        @Path("shareId") shareId: String,
        @Body request: DeleteLinksRequest,
    ): BaseResponse

    // Album sharing — create a brand-new Drive share rooted at [linkId] (the album).
    // Endpoint and body shape match ProtonDriveApps/android-drive ShareApi exactly. The
    // legacy "drive/v2/shares/{shareId}/links/{linkId}/share" path returns 404 "Path not found"
    // — the server only accepts share creation through the volume-level POST.
    @POST("drive/volumes/{volumeId}/shares")
    suspend fun createVolumeShare(
        @Path("volumeId") volumeId: String,
        @Body request: eu.akoos.photos.data.api.dto.CreateShareRequest,
    ): eu.akoos.photos.data.api.dto.CreateShareResponse

    // Create a public share URL from a share ID
    @POST("drive/shares/{shareId}/urls")
    suspend fun createShareUrl(
        @Path("shareId") shareId: String,
        @Body request: CreateShareUrlRequest,
    ): CreateShareUrlResponse

    // List URLs for a share
    @GET("drive/shares/{shareId}/urls")
    suspend fun getShareUrls(
        @Path("shareId") shareId: String,
    ): ShareUrlsResponse

    // Disable a single public share URL without touching member shares.
    // Path matches the official Drive Android client (drive/share-url/.../ShareUrlApi.kt).
    @DELETE("drive/shares/{shareId}/urls/{shareUrlId}")
    suspend fun deleteShareUrl(
        @Path("shareId") shareId: String,
        @Path("shareUrlId") shareUrlId: String,
    ): BaseResponse

    // Delete a share
    @DELETE("drive/shares/{shareId}")
    suspend fun deleteShare(
        @Path("shareId") shareId: String,
    ): BaseResponse

    // Change a member's permission bitmap on a share (viewer 4 ↔ editor 6).
    // Path matches the official Drive Android client (drive/share-user/.../ShareMemberApi.kt).
    @PUT("drive/v2/shares/{shareId}/members/{shareMemberId}")
    suspend fun updateShareMember(
        @Path("shareId") shareId: String,
        @Path("shareMemberId") shareMemberId: String,
        @Body request: eu.akoos.photos.data.api.dto.UpdateShareMemberRequest,
    ): BaseResponse

    // Invite a Proton user to an album share. The body carries the share session key
    // re-encrypted to the invitee + a detached signature; see [DrivePhotoRepositoryImpl.inviteToAlbum]
    // for the crypto pipeline.
    @POST("drive/v2/shares/{shareId}/invitations")
    suspend fun inviteToShare(
        @Path("shareId") shareId: String,
        @Body request: eu.akoos.photos.data.api.dto.CreateInvitationRequest,
    ): BaseResponse

    // Change a PENDING invitation's permission bitmap before the invitee accepts
    // (viewer 4 ↔ editor 6). Same body shape as the member update; path matches the
    // official Drive Android client (drive/share-user/.../ShareInvitationApi.kt).
    @PUT("drive/v2/shares/{shareId}/invitations/{invitationId}")
    suspend fun updateShareInvitation(
        @Path("shareId") shareId: String,
        @Path("invitationId") invitationId: String,
        @Body request: eu.akoos.photos.data.api.dto.UpdateShareMemberRequest,
    ): BaseResponse

    @GET("drive/photos/albums/shared-with-me")
    suspend fun getSharedWithMeAlbums(
        @Query("AnchorID") anchorId: String? = null,
    ): AlbumsResponse

    /** All items shared with me (files, folders, albums). Filter client-side by ShareTargetType. */
    @GET("drive/v2/sharedwithme")
    suspend fun getSharedWithMe(
        @Query("AnchorID") anchorId: String? = null,
    ): SharedWithMeResponse

    /** Global pending invitations for the current user (invitee-side). */
    @GET("drive/v2/shares/invitations")
    suspend fun getPendingInvitations(
        @Query("AnchorID") anchorId: String? = null,
    ): GlobalInvitationsResponse

    /** Detailed single invitation (includes InviterEmail, KeyPacket, etc.). */
    @GET("drive/v2/shares/invitations/{invitationId}")
    suspend fun getInvitationDetail(
        @Path("invitationId") invitationId: String,
    ): InvitationDetailResponse

    /**
     * Decline (reject) a pending invitation. The Drive API only allows GET on the bare
     * `/invitations/{id}` URL — DELETE returns "Method Not Allowed". The rejection lives at
     * the `/reject` suffix with a POST.
     */
    @POST("drive/v2/shares/invitations/{invitationId}/reject")
    suspend fun declineInvitation(
        @Path("invitationId") invitationId: String,
    ): BaseResponse

    /**
     * Accept a pending album-share invitation.
     * Body: SessionKeySignature = base64-unarmored detached signature of the decrypted
     * share session key, signed by the invitee's primary address key.
     */
    @POST("drive/v2/shares/invitations/{invitationId}/accept")
    suspend fun acceptInvitation(
        @Path("invitationId") invitationId: String,
        @Body request: eu.akoos.photos.data.api.dto.AcceptInvitationRequest,
    ): BaseResponse

    @GET("drive/v2/shares/{shareId}/invitations")
    suspend fun listShareInvitations(
        @Path("shareId") shareId: String,
    ): ShareInvitationsResponse

    @DELETE("drive/v2/shares/{shareId}/invitations/{invitationId}")
    suspend fun revokeInvitation(
        @Path("shareId") shareId: String,
        @Path("invitationId") invitationId: String,
    ): BaseResponse

    /** Accepted members for a share. */
    @GET("drive/v2/shares/{shareId}/members")
    suspend fun getShareMembers(
        @Path("shareId") shareId: String,
    ): ShareMembersResponse

    /** Revoke (remove) an accepted member from a share. */
    @DELETE("drive/v2/shares/{shareId}/members/{memberId}")
    suspend fun removeShareMember(
        @Path("shareId") shareId: String,
        @Path("memberId") memberId: String,
    ): BaseResponse
}
