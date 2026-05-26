package me.proton.photos.data.api.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class VolumesResponse(
    @SerialName("Volumes") val volumes: List<VolumeDto>,
    @SerialName("Code") val code: Int,
)

/**
 * Wire schema mirrors the official Proton Drive Android client's
 * `me.proton.core.drive.volume.data.api.entity.VolumeDto`:
 *
 *   VolumeID    — opaque volume identifier
 *   Type        — 1 = REGULAR (main Drive volume), 2 = PHOTO (Photos volume)
 *                 (constants TYPE_REGULAR=1L / TYPE_PHOTO=2L in the official client)
 *   State       — 1 means active per `Volume.isActive` (state == 1L)
 *   Share       — nested object with ShareID + LinkID of the volume's root share.
 *                 This is how `ensurePhotosVolumeReady` recovers the Photos root link
 *                 when `createOrGetPhotosVolume` returns ALREADY_EXISTS (2500) and the
 *                 server-side Photos share already exists with no link ID surfaced by
 *                 `getPhotosShare` yet.
 *
 * Other fields the official client deserialises (`CreateTime`, `UsedSpace`) are
 * intentionally omitted — we don't need them and they'd just bloat the DTO.
 */
@Serializable
data class VolumeDto(
    @SerialName("VolumeID") val volumeId: String,
    @SerialName("Type") val type: Int,
    @SerialName("State") val state: Int,
    @SerialName("Share") val share: VolumeShareDto? = null,
)

/**
 * Mirror of the official `VolumeShare` DTO at
 * `drive/volume/data/.../api/entity/VolumeShare.kt`. The official wire format has only
 * ShareID + LinkID nested here; share Key / Passphrase live on the separate
 * `getPhotosShare` endpoint (see [PhotosShareDto]).
 */
@Serializable
data class VolumeShareDto(
    @SerialName("ShareID") val shareId: String,
    @SerialName("LinkID") val linkId: String? = null,
)

@Serializable
data class SharesResponse(
    @SerialName("Shares") val shares: List<ShareDto>,
    @SerialName("Code") val code: Int,
)

@Serializable
data class ShareDto(
    @SerialName("ShareID") val shareId: String,
    @SerialName("Type") val type: Int,
    @SerialName("LinkID") val linkId: String,
    @SerialName("VolumeID") val volumeId: String,
)

// Note: server omits "More" and "AnchorID" when absent — both default to safe values.
// Pagination cursor is the last photo's LinkID, sent as PreviousPageLastLinkID query param.
@Serializable
data class PhotoLinksResponse(
    @SerialName("Photos") val links: List<PhotoLinkDto>,
    @SerialName("Code") val code: Int,
)

@Serializable
data class PhotoLinkDto(
    @SerialName("LinkID") val linkId: String,
    @SerialName("CaptureTime") val captureTime: Long,
    @SerialName("Hash") val hash: String? = null,
    @SerialName("ContentHash") val contentHash: String? = null,
    @SerialName("Tags") val tags: List<Int> = emptyList(),
    @SerialName("RelatedPhotos") val relatedPhotos: List<String> = emptyList(),
)

// Body for POST /drive/photos/volumes/{volumeID}/links/{linkID}/favorite.
// PhotoData may be null for the basic call. When the server demands the re-encrypted
// passphrase/name/hash (advanced flow), construct a full PhotoData payload similar to
// AddAlbumMultipleEntry.
@Serializable
data class FavoriteRequest(
    @SerialName("PhotoData") val photoData: FavoritePhotoData? = null,
)

@Serializable
data class FavoritePhotoData(
    @SerialName("Hash") val hash: String,
    @SerialName("Name") val name: String,
    @SerialName("NameSignatureEmail") val nameSignatureEmail: String,
    @SerialName("NodePassphrase") val nodePassphrase: String,
    @SerialName("ContentHash") val contentHash: String,
    @SerialName("NodePassphraseSignature") val nodePassphraseSignature: String? = null,
    @SerialName("SignatureEmail") val signatureEmail: String? = null,
)

// Body for POST/DELETE /drive/photos/volumes/{volumeID}/links/{linkID}/tags
// Tag values follow the Drive PhotoTag enum: 0 = Favorite (note: server explicitly
// rejects 0 via the addTags endpoint; we use this only for deletions via deletePhotoTags).
@Serializable
data class TagRequest(
    @SerialName("Tags") val tags: List<Long>,
)

/** Body for POST drive/v2/shares/invitations/{id}/accept */
@Serializable
data class AcceptInvitationRequest(
    @SerialName("SessionKeySignature") val sessionKeySignature: String,
)


@Serializable
data class ThumbnailResponse(
    @SerialName("ThumbnailBareURL") val thumbnailBareUrl: String? = null,
    @SerialName("BareURL") val bareUrl: String? = null,
    @SerialName("Token") val token: String? = null,
    @SerialName("Code") val code: Int,
)

// POST /drive/volumes/{volumeId}/thumbnails — batch thumbnail URL fetch
@Serializable
data class ThumbnailBatchRequest(
    @SerialName("ThumbnailIDs") val thumbnailIds: List<String>,
)

@Serializable
data class ThumbnailUrlDto(
    @SerialName("ThumbnailID") val thumbnailId: String,
    @SerialName("BareURL") val bareUrl: String? = null,
    @SerialName("Token") val token: String? = null,
)

@Serializable
data class ThumbnailBatchResponse(
    @SerialName("Thumbnails") val thumbnails: List<ThumbnailUrlDto> = emptyList(),
    @SerialName("Code") val code: Int,
)

@Serializable
data class LinkDetailsResponse(
    @SerialName("Link") val link: PhotoLinkDto,
    @SerialName("Code") val code: Int,
)

@Serializable
data class FullLinkResponse(
    @SerialName("Link") val link: LinkCoreDto,
    @SerialName("Code") val code: Int,
)

// --- Photos-specific file creation DTOs ---

// POST drive/photos/volumes/{volumeId}/photos
// NOTE: ContentKeyPacket lives in the Photo field, NOT the Link field.
// The batch-link response returns it at Photo.ContentKeyPacket — so that's where the
// server reads it from.  Putting it in Link has no effect and leaves Photo.CKP null.
@Serializable
data class CreatePhotoLinkData(
    @SerialName("Name") val name: String,
    @SerialName("Hash") val hash: String,
    @SerialName("ParentLinkID") val parentLinkId: String,
    @SerialName("MIMEType") val mimeType: String,
    @SerialName("NodeKey") val nodeKey: String,
    @SerialName("NodePassphrase") val nodePassphrase: String,
    @SerialName("NodePassphraseSignature") val nodePassphraseSignature: String,
    @SerialName("SignatureEmail") val signatureEmail: String,
    // Include CKP at the Link level too — server maps this to FileProperties.ContentKeyPacket
    // which is where the web client reads it from (Photo.ContentKeyPacket is for mobile clients).
    @SerialName("ContentKeyPacket") val contentKeyPacket: String? = null,
    @SerialName("ContentKeyPacketSignature") val contentKeyPacketSignature: String? = null,
)

@Serializable
data class CreatePhotoMetadata(
    @SerialName("CaptureTime") val captureTime: Long,
    @SerialName("Tags") val tags: List<Int> = emptyList(),
    // ContentKeyPacket belongs here so the server stores it at Photo.ContentKeyPacket
    // (the location the web client and mobile client read it from when decrypting).
    @SerialName("ContentKeyPacket") val contentKeyPacket: String? = null,
    @SerialName("ContentKeyPacketSignature") val contentKeyPacketSignature: String? = null,
)

@Serializable
data class CreatePhotoRequest(
    @SerialName("Photo") val photo: CreatePhotoMetadata,
    @SerialName("Link") val link: CreatePhotoLinkData,
)

@Serializable
data class CreatedPhotoLinkDto(
    @SerialName("LinkID") val linkId: String,
    @SerialName("RevisionID") val revisionId: String? = null,
)

@Serializable
data class CreatedPhotoWrapperDto(
    @SerialName("Link") val link: CreatedPhotoLinkDto,
)

@Serializable
data class CreatePhotoResponse(
    @SerialName("Photo") val photo: CreatedPhotoWrapperDto,
    @SerialName("Code") val code: Int,
)

// --- Standard Drive file creation DTOs ---

@Serializable
data class CreateFileRequest(
    @SerialName("Name") val name: String,
    @SerialName("Hash") val hash: String,
    @SerialName("ParentLinkID") val parentLinkId: String,
    @SerialName("MIMEType") val mimeType: String,
    @SerialName("NodeKey") val nodeKey: String,
    @SerialName("NodePassphrase") val nodePassphrase: String,
    @SerialName("NodePassphraseSignature") val nodePassphraseSignature: String,
    @SerialName("SignatureAddress") val signatureAddress: String,
    @SerialName("ContentKeyPacket") val contentKeyPacket: String,
    @SerialName("ContentKeyPacketSignature") val contentKeyPacketSignature: String,
)

@Serializable
data class CreateFileResponse(
    @SerialName("File") val file: CreatedFileDto,
    @SerialName("Code") val code: Int,
)

@Serializable
data class CreatedFileDto(
    @SerialName("ID") val id: String,
    @SerialName("RevisionID") val revisionId: String,
)

@Serializable
data class CreateRevisionResponse(
    @SerialName("Revision") val revision: CreatedRevisionDto,
    @SerialName("Code") val code: Int,
)

@Serializable
data class CreatedRevisionDto(
    @SerialName("ID") val id: String,
)

// Thumbnail upload info sent in POST drive/blocks → ThumbnailList.
// The web client only sends {"Type": N} — no Hash, Size, Index or EncSignature.
// Sending extra fields causes the server to reject the request and return no ThumbnailLink.
@Serializable
data class ThumbnailUploadInfoDto(
    @SerialName("Type") val type: Int,
)

// Thumbnail commit entry sent in PUT …/revisions/{id} → ThumbnailList.
// NOTE: no default values — same encodeDefaults=false reason as above.
@Serializable
data class CommitThumbnailDto(
    @SerialName("Index") val index: Int,
    @SerialName("Hash") val hash: String,
    @SerialName("Token") val token: String,
    @SerialName("Type") val type: Int,
)

// POST drive/blocks — all IDs go in the request body (not in the URL path).
// Uses ShareID (not VolumeID) so the server can correlate blocks with the share-based
// verification token returned by getVerificationData — required for valid manifest signature.
@Serializable
data class UploadBlockRequest(
    @SerialName("BlockList") val blockList: List<BlockUploadInfoDto>,
    @SerialName("AddressID") val addressId: String,
    @SerialName("ShareID") val shareId: String,
    @SerialName("LinkID") val linkId: String,
    @SerialName("RevisionID") val revisionId: String,
    @SerialName("ThumbnailList") val thumbnailList: List<ThumbnailUploadInfoDto> = emptyList(),
)

@Serializable
data class VerifierDto(
    @SerialName("Token") val token: String,
)

@Serializable
data class BlockUploadInfoDto(
    @SerialName("Index") val index: Int,
    @SerialName("Hash") val hash: String,
    @SerialName("EncSignature") val encSignature: String,
    @SerialName("Size") val size: Long,
    @SerialName("Verifier") val verifier: VerifierDto? = null,
)

// GET drive/shares/{shareId}/links/{linkId}/revisions/{revisionId}/verification
@Serializable
data class VerificationDataResponse(
    @SerialName("Code") val code: Int,
    @SerialName("VerificationCode") val verificationCode: String,
    @SerialName("ContentKeyPacket") val contentKeyPacket: String? = null,
)

@Serializable
data class UploadBlockResponse(
    @SerialName("UploadLinks") val uploadLinks: List<UploadLinkDto>,
    // ThumbnailLinks use "ThumbnailType" (not "Index") — separate DTO to avoid parse failure.
    @SerialName("ThumbnailLinks") val thumbnailLinks: List<ThumbnailLinkDto> = emptyList(),
    @SerialName("Code") val code: Int,
)

@Serializable
data class UploadLinkDto(
    @SerialName("Index") val index: Int,
    @SerialName("BareURL") val bareUrl: String,
    @SerialName("Token") val token: String,
)

// ThumbnailLinks entries use "ThumbnailType" to identify the thumbnail type (1=small, 2=large).
// The web client sends ThumbnailList: [{"Type": 1}, {"Type": 2}] and gets back one entry per type.
@Serializable
data class ThumbnailLinkDto(
    @SerialName("BareURL") val bareUrl: String,
    @SerialName("Token") val token: String,
    @SerialName("ThumbnailType") val thumbnailType: Int = 0,
)

@Serializable
data class CommitRevisionRequest(
    @SerialName("BlockList") val blockList: List<CommitBlockDto>,
    @SerialName("ManifestSignature") val manifestSignature: String,
    @SerialName("SignatureAddress") val signatureAddress: String,
    @SerialName("XAttr") val xAttr: String? = null,
    @SerialName("Photo") val photo: PhotoMetaDto? = null,
    // CKP sent at revision level so it's stored at Revision.ContentKeyPacket —
    // a third location some clients fall back to when FileProperties and Photo both null.
    @SerialName("ContentKeyPacket") val contentKeyPacket: String? = null,
    @SerialName("ContentKeyPacketSignature") val contentKeyPacketSignature: String? = null,
    // Encrypted thumbnail committed together with the content blocks.
    @SerialName("ThumbnailList") val thumbnailList: List<CommitThumbnailDto> = emptyList(),
)

// v2 commit request — matches web client's PUT /drive/v2/volumes/{volumeId}/files/{linkId}/revisions/{revisionId}.
// No BlockList, no ThumbnailList — blocks and thumbnails are committed implicitly because the CDN
// already knows which revisionId they belong to (set in POST drive/blocks → VolumeID/RevisionID).
@Serializable
data class CommitRevisionV2Request(
    @SerialName("ManifestSignature") val manifestSignature: String,
    @SerialName("SignatureAddress") val signatureAddress: String,
    @SerialName("XAttr") val xAttr: String? = null,
    @SerialName("ChecksumVerified") val checksumVerified: Boolean = false,
    @SerialName("Photo") val photo: PhotoMetaDto? = null,
)

@Serializable
data class CommitBlockDto(
    @SerialName("Index") val index: Int,
    @SerialName("Hash") val hash: String,
    @SerialName("EncSignature") val encSignature: String,
    @SerialName("Token") val token: String? = null,
)

@Serializable
data class PhotoMetaDto(
    @SerialName("CaptureTime") val captureTime: Long,
    @SerialName("ContentHash") val contentHash: String? = null,
)

@Serializable
data class DeleteLinksRequest(
    @SerialName("LinkIDs") val linkIds: List<String>,
)

// Reuse same structure for server-side trash
typealias TrashLinksRequest = DeleteLinksRequest

// Response for GET drive/photos/volumes/{volumeId}/trash (photos-only trash, may 404)
@Serializable
data class PhotoTrashResponse(
    @SerialName("Photos") val photos: List<PhotoLinkDto> = emptyList(),
    @SerialName("More") val more: Boolean = false,
    @SerialName("AnchorID") val anchorId: String? = null,
    @SerialName("Code") val code: Int,
)

// Response for GET drive/volumes/{volumeId}/trash — full Drive trash (all types).
// Web-confirmed path (no v2). Pagination via ?Page=N (1-indexed).
// Server returns empty Links list on the last page.
// Filter client-side for image/* and video/* to show only photos/videos in the UI.
@Serializable
data class VolumeTrashResponse(
    @SerialName("Links") val links: List<LinkCoreDto> = emptyList(),
    @SerialName("Code") val code: Int,
)

@Serializable
data class BaseResponse(
    @SerialName("Code") val code: Int,
)

@Serializable
data class AlbumsResponse(
    @SerialName("Albums") val albums: List<AlbumDto>,
    @SerialName("AnchorID") val anchorId: String? = null,
    @SerialName("More") val more: Boolean = false,
    @SerialName("Code") val code: Int,
)

@Serializable
data class AlbumDto(
    @SerialName("LinkID") val linkId: String,
    @SerialName("VolumeID") val volumeId: String? = null,
    @SerialName("ShareID") val shareId: String? = null,
    @SerialName("Locked") val locked: Boolean = false,
    @SerialName("PhotoCount") val photoCount: Int = 0,
    @SerialName("CoverLinkID") val coverLinkId: String? = null,
    @SerialName("LastActivityTime") val lastActivityTime: Long? = null,
)

@Serializable
data class AlbumChildrenResponse(
    @SerialName("Photos") val photos: List<AlbumChildDto>,
    @SerialName("AnchorID") val anchorId: String? = null,
    @SerialName("More") val more: Boolean = false,
    @SerialName("Code") val code: Int,
)

@Serializable
data class AlbumChildDto(
    @SerialName("LinkID") val linkId: String,
    @SerialName("CaptureTime") val captureTime: Long? = null,
    @SerialName("AddedTime") val addedTime: Long? = null,
)

@Serializable
data class BatchLinksRequest(
    @SerialName("LinkIDs") val linkIds: List<String>,
)

/**
 * Response from POST drive/shares/{shareId}/links/fetch_metadata.
 * Unlike [BatchLinksResponse] (Photos API), each item IS the link directly — no "Link" wrapper.
 * Returns the full [LinkCoreDto] including FileProperties.ContentKeyPacket.
 */
@Serializable
data class FetchLinksResponse(
    @SerialName("Links") val links: List<LinkCoreDto> = emptyList(),
    @SerialName("Code") val code: Int,
)

@Serializable
data class BatchLinksResponse(
    @SerialName("Links") val links: List<BatchLinkDto>,
    @SerialName("Code") val code: Int,
)

@Serializable
data class BatchLinkDto(
    @SerialName("Link") val link: LinkCoreDto,
    @SerialName("Album") val album: AlbumMetaDto? = null,
    @SerialName("Photo") val photo: LinkPhotoDto? = null,
    @SerialName("Folder") val folder: LinkFolderDto? = null,
    @SerialName("Sharing") val sharing: LinkSharingDto? = null,
)

@Serializable
data class LinkCoreDto(
    @SerialName("LinkID") val linkId: String,
    @SerialName("Type") val type: Int,
    @SerialName("Name") val name: String? = null,
    // Server-canonical name hash (HMAC-SHA256 over the plain name with the parent's
    // NodeHashKey). Used as OriginalHash on rename/move — must be the value the server
    // currently stores, NOT a value we recompute locally, otherwise the optimistic
    // concurrency check rejects with "out of date".
    @SerialName("Hash") val hash: String? = null,
    @SerialName("ParentLinkID") val parentLinkId: String? = null,
    @SerialName("CreateTime") val createTime: Long? = null,
    @SerialName("ModifyTime") val modifyTime: Long? = null,
    @SerialName("MIMEType") val mimeType: String? = null,
    @SerialName("Size") val size: Long? = null,
    @SerialName("NodeKey") val nodeKey: String? = null,
    @SerialName("NodePassphrase") val nodePassphrase: String? = null,
    @SerialName("NodePassphraseSignature") val nodePassphraseSignature: String? = null,
    @SerialName("SignatureEmail") val signatureEmail: String? = null,
    @SerialName("State") val state: Int? = null,
    // FileProperties holds ContentKeyPacket for file-type links.
    // It is the canonical location per the official Proton Drive Android SDK
    // (LinkFilePropertiesDto), NOT ActiveRevision.ContentKeyPacket.
    @SerialName("FileProperties") val fileProperties: LinkFilePropertiesDto? = null,
    // ActiveRevision kept for backward-compat / non-file links (albums, folders).
    @SerialName("ActiveRevision") val activeRevision: ActiveRevisionDto? = null,
)

/**
 * Mirrors the official SDK's `LinkFilePropertiesDto`.
 *
 * JSON path: Link.FileProperties
 *   - ContentKeyPacket  — PKESK encrypted to the node's public key; decrypt with
 *                         node private key to get the session key for content blocks.
 *   - ActiveRevision    — the current revision with its ID, thumbnails, etc.
 */
@Serializable
data class LinkFilePropertiesDto(
    @SerialName("ContentKeyPacket") val contentKeyPacket: String? = null,
    @SerialName("ContentKeyPacketSignature") val contentKeyPacketSignature: String? = null,
    @SerialName("ActiveRevision") val activeRevision: LinkFileActiveRevisionDto? = null,
)

/**
 * The ActiveRevision nested inside FileProperties.
 * Key difference from [ActiveRevisionDto]: this uses "ID" (not "RevisionID"),
 * and does not have a top-level ContentKeyPacket (that lives in FileProperties).
 */
@Serializable
data class LinkFileActiveRevisionDto(
    @SerialName("ID") val id: String? = null,
    @SerialName("State") val state: Int? = null,
    @SerialName("ManifestSignature") val manifestSignature: String? = null,
    @SerialName("Thumbnails") val thumbnails: List<ThumbnailEntryDto>? = null,
)

@Serializable
data class ActiveRevisionDto(
    @SerialName("ID") val id: String? = null,
    @SerialName("ContentKeyPacket") val contentKeyPacket: String? = null,
    @SerialName("ContentKeyPacketSignature") val contentKeyPacketSignature: String? = null,
    @SerialName("ManifestSignature") val manifestSignature: String? = null,
    @SerialName("State") val state: Int? = null,
    @SerialName("Thumbnails") val thumbnails: List<ThumbnailEntryDto>? = null,
)

@Serializable
data class ThumbnailEntryDto(
    @SerialName("ThumbnailID") val thumbnailId: String,
    @SerialName("Type") val type: Int = 1,
    @SerialName("Hash") val hash: String? = null,
    @SerialName("Size") val size: Long? = null,
)

@Serializable
data class AlbumMetaDto(
    @SerialName("Hidden") val hidden: Boolean = false,
    @SerialName("Locked") val locked: Boolean = false,
    @SerialName("CoverLinkID") val coverLinkId: String? = null,
    @SerialName("LastActivityTime") val lastActivityTime: Long? = null,
    @SerialName("PhotoCount") val photoCount: Int = 0,
    @SerialName("NodeHashKey") val nodeHashKey: String? = null,
    @SerialName("XAttr") val xAttr: String? = null,
)

// Active revision object nested inside "Photo" in POST /photos/volumes/{volumeId}/links.
// NOTE: key is "RevisionID" here, NOT "ID" (which is used in the file/revisions listing endpoint).
@Serializable
data class PhotoActiveRevisionDto(
    @SerialName("RevisionID") val revisionId: String? = null,
    @SerialName("ContentKeyPacket") val contentKeyPacket: String? = null,
    @SerialName("Thumbnails") val thumbnails: List<ThumbnailEntryDto>? = null,
)

@Serializable
data class LinkPhotoDto(
    @SerialName("CaptureTime") val captureTime: Long? = null,
    @SerialName("ContentHash") val contentHash: String? = null,
    @SerialName("Tags") val tags: List<Int> = emptyList(),
    @SerialName("ActiveRevision") val activeRevision: PhotoActiveRevisionDto? = null,
    // ContentKeyPacket is stored at Photo.ContentKeyPacket in the batch-link response.
    // This is the primary CKP location for photos (FileProperties is null for photo links).
    @SerialName("ContentKeyPacket") val contentKeyPacket: String? = null,
    @SerialName("ContentKeyPacketSignature") val contentKeyPacketSignature: String? = null,
)

@Serializable
data class LinkFolderDto(
    @SerialName("NodeHashKey") val nodeHashKey: String? = null,
    @SerialName("XAttr") val xAttr: String? = null,
)

@Serializable
data class LinkSharingDto(
    @SerialName("ShareID") val shareId: String? = null,
    @SerialName("ShareURLID") val shareUrlId: String? = null,
)

@Serializable
data class PhotosShareResponse(
    @SerialName("Volume") val volume: PhotosVolumeDto,
    @SerialName("Share") val share: PhotosShareDto,
    @SerialName("Code") val code: Int,
)

@Serializable
data class ShareDetailsResponse(
    @SerialName("Share") val share: ShareFullDto,
    @SerialName("Code") val code: Int,
)

@Serializable
data class ShareFullDto(
    @SerialName("ShareID") val shareId: String,
    @SerialName("Type") val type: Int? = null,
    @SerialName("LinkID") val linkId: String? = null,
    @SerialName("VolumeID") val volumeId: String? = null,
    @SerialName("Key") val key: String? = null,
    @SerialName("Passphrase") val passphrase: String? = null,
    @SerialName("PassphraseSignature") val passphraseSignature: String? = null,
    @SerialName("CreatorEmail") val creatorEmail: String? = null,
)

@Serializable
data class PhotosShareDto(
    @SerialName("ShareID") val shareId: String,
    @SerialName("LinkID") val linkId: String? = null,
    @SerialName("CreatorEmail") val creatorEmail: String? = null,
    @SerialName("Key") val key: String? = null,
    @SerialName("Passphrase") val passphrase: String? = null,
    @SerialName("PassphraseSignature") val passphraseSignature: String? = null,
)

// The Photos API wraps all album link fields under a "Link" object.
// POST /drive/photos/volumes/{volumeId}/albums
// Body: {"Locked": false, "Link": {"Name": ..., "Hash": ..., ...}}
@Serializable
data class CreateAlbumLinkData(
    @SerialName("Name") val name: String,
    @SerialName("Hash") val hash: String,
    @SerialName("NodeKey") val nodeKey: String,
    @SerialName("NodePassphrase") val nodePassphrase: String,
    @SerialName("NodePassphraseSignature") val nodePassphraseSignature: String,
    @SerialName("SignatureEmail") val signatureEmail: String,
    @SerialName("NodeHashKey") val nodeHashKey: String,
    @SerialName("XAttr") val xAttr: String? = null,
)

@Serializable
data class CreateAlbumRequest(
    // No default value → always serialized even when encodeDefaults=false
    @SerialName("Locked") val locked: Boolean,
    @SerialName("Link") val link: CreateAlbumLinkData,
)

// Response: {"Album": {"Link": {"LinkID": "..."}}, "Code": 1000}
@Serializable
data class CreatedAlbumLinkDto(
    @SerialName("LinkID") val linkId: String,
)

@Serializable
data class CreatedAlbumWrapperDto(
    @SerialName("Link") val link: CreatedAlbumLinkDto,
)

@Serializable
data class CreateAlbumResponse(
    @SerialName("Album") val album: CreatedAlbumWrapperDto,
    @SerialName("Code") val code: Int,
)

@Serializable
data class AddAlbumMultipleEntry(
    @SerialName("LinkID") val linkId: String,
    @SerialName("Hash") val hash: String,
    @SerialName("Name") val name: String,
    @SerialName("NodePassphrase") val nodePassphrase: String,
    @SerialName("NameSignatureEmail") val nameSignatureEmail: String,
    @SerialName("ContentHash") val contentHash: String,
)

@Serializable
data class AddAlbumMultipleRequest(
    @SerialName("AlbumData") val albumData: List<AddAlbumMultipleEntry>,
)

/**
 * POST /drive/photos/volumes/{volumeId}/albums/{albumLinkId}/remove-multiple
 * Mirror of [AddAlbumMultipleRequest] — removes the album references for the listed
 * photo linkIds. The photos themselves stay in the Photos root.
 */
@Serializable
data class RemoveFromAlbumRequest(
    @SerialName("LinkIDs") val linkIds: List<String>,
)

/**
 * Nested under [UpdateAlbumRequest.link] when renaming an album. Every field is optional;
 * supply only the ones you intend to change. The server treats the omitted fields as
 * "keep as-is". For a rename you typically send Name + Hash + NameSignatureEmail +
 * OriginalHash; XAttr only when you also touch extended attributes.
 */
@Serializable
data class UpdateAlbumLinkData(
    @SerialName("Name") val name: String? = null,
    @SerialName("Hash") val hash: String? = null,
    @SerialName("NameSignatureEmail") val nameSignatureEmail: String? = null,
    @SerialName("OriginalHash") val originalHash: String? = null,
    @SerialName("XAttr") val xAttr: String? = null,
)

/**
 * PUT /drive/photos/volumes/{volumeId}/albums/{albumLinkId}
 * Handles two distinct mutations on the same endpoint:
 *  - Rename: pass `link = UpdateAlbumLinkData(name, hash, …)`
 *  - Set cover photo: pass `coverLinkId = <photo linkId>`
 * Either field is independently optional, so you can send rename, cover, or both at once.
 */
@Serializable
data class UpdateAlbumRequest(
    @SerialName("CoverLinkID") val coverLinkId: String? = null,
    @SerialName("Link") val link: UpdateAlbumLinkData? = null,
)

/**
 * Body for POST `/drive/photos/volumes` — mirrors the official Proton Drive Android client's
 * `me.proton.core.drive.volume.data.api.request.CreatePhotoVolumeRequest`.
 *
 * Sending an empty body returns "Missing required attributes Share,Link"; the server treats this
 * endpoint as idempotent only when the FULL key material is supplied. On second + subsequent
 * calls (volume already materialised) the server ignores the body and returns the existing
 * volume/share IDs — but it MUST parse the body successfully or it rejects the request.
 *
 * Nested objects use the JSON keys `Share` and `Link`. All field names match the official wire
 * format exactly (`AddressID`, `AddressKeyID`, `NodeHashKey`, etc.) — case matters.
 */
@Serializable
data class CreatePhotosVolumeRequest(
    @SerialName("Share") val share: PhotosVolumeShare,
    @SerialName("Link") val link: PhotosVolumeLink,
)

@Serializable
data class PhotosVolumeShare(
    @SerialName("AddressID") val addressId: String,
    @SerialName("Key") val key: String,
    @SerialName("Passphrase") val passphrase: String,
    @SerialName("PassphraseSignature") val passphraseSignature: String,
    @SerialName("AddressKeyID") val addressKeyId: String,
)

@Serializable
data class PhotosVolumeLink(
    @SerialName("NodeKey") val nodeKey: String,
    @SerialName("NodePassphrase") val nodePassphrase: String,
    @SerialName("NodePassphraseSignature") val nodePassphraseSignature: String,
    @SerialName("NodeHashKey") val nodeHashKey: String,
    @SerialName("Name") val name: String,
)

@Serializable
data class CreatePhotosVolumeResponse(
    @SerialName("Volume") val volume: PhotosVolumeDto,
    @SerialName("Code") val code: Int,
)

@Serializable
data class PhotosVolumeDto(
    @SerialName("VolumeID") val volumeId: String,
    @SerialName("ShareID") val shareId: String? = null,
)

@Serializable
data class EventAnchorResponse(
    @SerialName("EventID") val eventId: String,
    @SerialName("Code") val code: Int,
)

@Serializable
data class DriveEventsResponse(
    @SerialName("EventID") val eventId: String,
    @SerialName("More") val more: Int = 0,
    @SerialName("Events") val events: List<DriveEventDto> = emptyList(),
    @SerialName("Code") val code: Int,
)

@Serializable
data class DriveEventDto(
    @SerialName("EventType") val eventType: Int,
    @SerialName("Link") val link: LinkCoreDto? = null,
    @SerialName("LinkID") val linkId: String? = null,
)

@Serializable
data class RevisionResponse(
    @SerialName("Revision") val revision: RevisionWithBlocksDto,
    @SerialName("Code") val code: Int,
)

@Serializable
data class RevisionListResponse(
    @SerialName("Revisions") val revisions: List<RevisionSummaryDto> = emptyList(),
    @SerialName("Code") val code: Int,
)

@Serializable
data class RevisionSummaryDto(
    @SerialName("ID") val id: String,
    @SerialName("State") val state: Int? = null,
    @SerialName("ContentKeyPacket") val contentKeyPacket: String? = null,
)

@Serializable
data class RevisionWithBlocksDto(
    @SerialName("ID") val id: String,
    @SerialName("Blocks") val blocks: List<RevisionBlockDto> = emptyList(),
    @SerialName("State") val state: Int? = null,
    @SerialName("ContentKeyPacket") val contentKeyPacket: String? = null,
    @SerialName("ManifestSignature") val manifestSignature: String? = null,
    @SerialName("SignatureAddress") val signatureAddress: String? = null,
    // Photo revisions returned by the volume endpoint may nest ContentKeyPacket here:
    @SerialName("Photo") val photo: PhotoRevisionInnerDto? = null,
)

@Serializable
data class PhotoRevisionInnerDto(
    @SerialName("ContentKeyPacket") val contentKeyPacket: String? = null,
)

@Serializable
data class RevisionBlockDto(
    @SerialName("Index") val index: Int,
    @SerialName("URL") val url: String? = null,
    @SerialName("BareURL") val bareUrl: String? = null,
    @SerialName("Token") val token: String? = null,
    @SerialName("Hash") val hash: String? = null,
    @SerialName("EncSignature") val encSignature: String? = null,
    @SerialName("Size") val size: Long? = null,
)

// ── SharedWithMe / Invitations DTOs ───────────────────────────────────────────

@Serializable
data class SharedWithMeResponse(
    @SerialName("Links") val links: List<SharedWithMeLinkDto> = emptyList(),
    @SerialName("AnchorID") val anchorId: String? = null,
    @SerialName("More") val more: Boolean = false,
    @SerialName("Code") val code: Int = 1000,
)

@Serializable
data class SharedWithMeLinkDto(
    @SerialName("VolumeID") val volumeId: String,
    @SerialName("ShareID") val shareId: String,
    @SerialName("LinkID") val linkId: String,
    // Nullable + defaulted to match the official Proton Drive Android client
    // (LinkSharedWithMeResponseDto): some rows return without ShareTargetType
    // and a non-nullable Int crashes the entire response parse.
    @SerialName("ShareTargetType") val shareTargetType: Int? = null,
)

/** Global pending invitations (invitee-side): GET drive/v2/shares/invitations */
@Serializable
data class GlobalInvitationsResponse(
    @SerialName("Invitations") val invitations: List<GlobalInvitationDto> = emptyList(),
    @SerialName("AnchorID") val anchorId: String? = null,
    @SerialName("More") val more: Boolean = false,
    @SerialName("Code") val code: Int = 1000,
)

@Serializable
data class GlobalInvitationDto(
    @SerialName("VolumeID") val volumeId: String? = null,
    @SerialName("ShareID") val shareId: String,
    @SerialName("InvitationID") val invitationId: String,
    @SerialName("ShareTargetType") val shareTargetType: Int,
)

/** Detailed invitation: GET drive/v2/shares/invitations/{invitationId} */
@Serializable
data class InvitationDetailResponse(
    @SerialName("Invitation") val invitation: InvitationDetailDto,
    @SerialName("Share") val share: ShareFullDto? = null,
    @SerialName("Code") val code: Int = 1000,
)

@Serializable
data class InvitationDetailDto(
    @SerialName("InvitationID") val invitationId: String,
    @SerialName("InviterEmail") val inviterEmail: String,
    @SerialName("InviteeEmail") val inviteeEmail: String,
    @SerialName("Permissions") val permissions: Int = 6,
    @SerialName("CreateTime") val createTime: Long? = null,
    @SerialName("KeyPacket") val keyPacket: String? = null,
    @SerialName("KeyPacketSignature") val keyPacketSignature: String? = null,
)

/** Accepted members: GET drive/v2/shares/{shareId}/members */
@Serializable
data class ShareMembersResponse(
    @SerialName("Members") val members: List<ShareMemberDto> = emptyList(),
    @SerialName("Code") val code: Int = 1000,
)

@Serializable
data class ShareMemberDto(
    @SerialName("MemberID") val memberId: String,
    @SerialName("Email") val email: String? = null,
    @SerialName("InviteeEmail") val inviteeEmail: String? = null,
    @SerialName("InviterEmail") val inviterEmail: String? = null,
    @SerialName("Permissions") val permissions: Int = 6,
    @SerialName("CreateTime") val createTime: Long? = null,
)

// ── Album sharing DTOs ────────────────────────────────────────────────────────

/**
 * POST /drive/volumes/{volumeId}/shares — create a new Drive share for an existing link
 * (e.g. an album). This is the only correct path on the Photos/Drive backend; the legacy
 * "drive/v2/shares/{shareId}/links/{linkId}/share" path returns "Path not found".
 *
 * Field layout matches ProtonDriveApps/android-drive [CreateShareRequest].
 */
@Serializable
data class CreateShareRequest(
    /** Drive ShareType: 1 = Main, 4 = Standard. Album shares use 4. */
    @SerialName("Type") val type: Int = 4,
    @SerialName("AddressID") val addressId: String,
    /** PGP-encrypted share name (e.g. an armored MESSAGE wrapping the album title). */
    @SerialName("Name") val name: String,
    /** The link being shared — for albums, this is the album linkId. */
    @SerialName("RootLinkID") val rootLinkId: String,
    /** Armored locked private key for the share. */
    @SerialName("ShareKey") val shareKey: String,
    /** Armored PGP message: the share key passphrase, encrypted to the inviter address pub key. */
    @SerialName("SharePassphrase") val sharePassphrase: String,
    /** Detached armored signature over the raw passphrase bytes. */
    @SerialName("SharePassphraseSignature") val sharePassphraseSignature: String,
    /** Base64 PKESK — the key packet portion of [sharePassphrase]. */
    @SerialName("PassphraseKeyPacket") val passphraseKeyPacket: String,
    /** Base64 PKESK — the key packet portion of [name]. */
    @SerialName("NameKeyPacket") val nameKeyPacket: String,
)

@Serializable
data class CreateShareResponse(
    @SerialName("Code") val code: Int,
    @SerialName("Share") val share: CreatedSharePart,
) {
    @Serializable
    data class CreatedSharePart(
        @SerialName("ID") val shareId: String,
    )
}

@Serializable
data class CreateLinkShareResponse(
    @SerialName("Code") val code: Int,
    @SerialName("ShareID") val shareId: String? = null,
)

@Serializable
data class CreateShareUrlRequest(
    @SerialName("CreatorEmail") val creatorEmail: String,
    @SerialName("Password") val password: String? = null,
    @SerialName("MaxAccesses") val maxAccesses: Int? = null,
    @SerialName("ExpirationDuration") val expirationDuration: Long? = null,
)

@Serializable
data class ShareUrlDto(
    @SerialName("Token") val token: String,
    @SerialName("PublicUrl") val publicUrl: String? = null,
    @SerialName("ShareID") val shareId: String? = null,
)

@Serializable
data class CreateShareUrlResponse(
    @SerialName("Code") val code: Int,
    @SerialName("ShareURL") val shareUrl: ShareUrlDto? = null,
)

@Serializable
data class ShareUrlsResponse(
    @SerialName("Code") val code: Int,
    @SerialName("ShareURLs") val shareUrls: List<ShareUrlDto> = emptyList(),
)

@Serializable
data class InviteeDto(
    @SerialName("Email") val email: String,
    @SerialName("Permissions") val permissions: Int = 4,
)

@Serializable
data class ShareInvitationRequest(
    @SerialName("Invitees") val invitees: List<InviteeDto>,
)

/**
 * POST /drive/v2/shares/{shareId}/invitations
 *
 * The server requires a full re-encrypted KeyPacket + a detached signature so the invitee
 * (and only the invitee) can derive the share session key after accepting.
 */
@Serializable
data class CreateInvitationRequest(
    @SerialName("Invitation") val invitation: InvitationBodyDto,
    @SerialName("EmailDetails") val emailDetails: InvitationEmailDetailsDto? = null,
)

@Serializable
data class InvitationBodyDto(
    @SerialName("InviterEmail") val inviterEmail: String,
    @SerialName("InviteeEmail") val inviteeEmail: String,
    @SerialName("Permissions") val permissions: Int,
    /** Base64 PKESK encrypted to the invitee's primary address public key. */
    @SerialName("KeyPacket") val keyPacket: String,
    /** Base64 detached signature over the encrypted PKESK keyPacket bytes, signed by the inviter address. */
    @SerialName("KeyPacketSignature") val keyPacketSignature: String,
    @SerialName("ExternalInvitationID") val externalInvitationId: String? = null,
)

@Serializable
data class InvitationEmailDetailsDto(
    @SerialName("Message") val message: String? = null,
    @SerialName("ItemName") val itemName: String? = null,
)

@Serializable
data class ShareInvitationsResponse(
    @SerialName("Invitations") val invitations: List<ShareInvitationDto> = emptyList(),
    @SerialName("Code") val code: Int,
)

@Serializable
data class ShareInvitationDto(
    @SerialName("InvitationID") val invitationId: String,
    @SerialName("InviteeEmail") val inviteeEmail: String,
    @SerialName("Permissions") val permissions: Int = 4,
    @SerialName("State") val state: Int? = null,
)

