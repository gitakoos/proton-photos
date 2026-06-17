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

package eu.akoos.photos.data.api.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class VolumesResponse(
    @SerialName("Volumes") val volumes: List<VolumeDto>,
    @SerialName("Code") val code: Int,
)

/**
 * Type: 1 = REGULAR, 2 = PHOTO. State: 1 = active. [share] (ShareID + root LinkID) is how
 * `ensurePhotosVolumeReady` recovers the Photos root link when `createOrGetPhotosVolume` returns
 * ALREADY_EXISTS (2500) before `getPhotosShare` surfaces the link ID.
 */
@Serializable
data class VolumeDto(
    @SerialName("VolumeID") val volumeId: String,
    @SerialName("Type") val type: Int,
    @SerialName("State") val state: Int,
    @SerialName("Share") val share: VolumeShareDto? = null,
)

/** Only ShareID + LinkID nest here; share Key / Passphrase live on `getPhotosShare` (see [PhotosShareDto]). */
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

// Pagination cursor is the last photo's LinkID, sent as the PreviousPageLastLinkID query param.
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

// PhotoData may be null for the basic call; set it (re-encrypted passphrase/name/hash, like
// AddAlbumMultipleEntry) only when the server demands the advanced flow.
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

// Tag values follow the Drive PhotoTag enum. The server rejects 0 (Favorite) via addTags, so
// tag 0 is only ever sent through deletePhotoTags.
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
    // CKP at the Link level maps to FileProperties.ContentKeyPacket (the web client's location).
    @SerialName("ContentKeyPacket") val contentKeyPacket: String? = null,
    @SerialName("ContentKeyPacketSignature") val contentKeyPacketSignature: String? = null,
)

@Serializable
data class CreatePhotoMetadata(
    @SerialName("CaptureTime") val captureTime: Long,
    @SerialName("Tags") val tags: List<Int> = emptyList(),
    // CKP here is stored at Photo.ContentKeyPacket (the location clients read on decrypt).
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

// Send ONLY {"Type": N} — adding Hash/Size/Index/EncSignature makes the server reject the
// request and return no ThumbnailLink.
@Serializable
data class ThumbnailUploadInfoDto(
    @SerialName("Type") val type: Int,
)

@Serializable
data class CommitThumbnailDto(
    @SerialName("Index") val index: Int,
    @SerialName("Hash") val hash: String,
    @SerialName("Token") val token: String,
    @SerialName("Type") val type: Int,
)

// Uses ShareID (not VolumeID) so the server can correlate blocks with the share-based
// verification token from getVerificationData — required for a valid manifest signature.
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

// ThumbnailType: 1=small, 2=large. Sending ThumbnailList [{Type:1},{Type:2}] yields one entry per type.
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
    // CKP at revision level → Revision.ContentKeyPacket, a third fallback when FileProperties + Photo are null.
    @SerialName("ContentKeyPacket") val contentKeyPacket: String? = null,
    @SerialName("ContentKeyPacketSignature") val contentKeyPacketSignature: String? = null,
    @SerialName("ThumbnailList") val thumbnailList: List<CommitThumbnailDto> = emptyList(),
)

// v2 commit: no BlockList / ThumbnailList — blocks and thumbnails commit implicitly since the CDN
// already knows their revisionId (set in POST drive/blocks).
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

/**
 * `Photo` block inside a CommitRevision. Two non-obvious wire requirements:
 *   - [contentHash] is HMAC-SHA256(rootNodeHashKey, sha-hex) — NOT a bare hash; Drive web verifies
 *     it against the photos root NodeHashKey or rejects with "Cannot build photo payload…".
 *   - [tags] must be present even when empty — Drive web parses positionally and bails before the
 *     content-hash check if Tags is missing (surfacing the same error).
 */
@Serializable
data class PhotoMetaDto(
    @SerialName("CaptureTime") val captureTime: Long,
    @SerialName("ContentHash") val contentHash: String? = null,
    @SerialName("Tags") val tags: List<Long> = emptyList(),
)

@Serializable
data class DeleteLinksRequest(
    @SerialName("LinkIDs") val linkIds: List<String>,
)

typealias TrashLinksRequest = DeleteLinksRequest

// Photos-only trash response (may 404 — see VolumeTrashResponse for the full-trash path).
@Serializable
data class PhotoTrashResponse(
    @SerialName("Photos") val photos: List<PhotoLinkDto> = emptyList(),
    @SerialName("More") val more: Boolean = false,
    @SerialName("AnchorID") val anchorId: String? = null,
    @SerialName("Code") val code: Int,
)

// Full Drive trash (all types), grouped by ShareID with bare LinkIDs only — metadata (mime,
// thumbnails, crypto material) must be hydrated via a follow-up link-detail fetch. Empty Trash
// list signals the end of pagination.
@Serializable
data class VolumeTrashResponse(
    @SerialName("Trash") val trash: List<VolumeTrashGroupDto> = emptyList(),
    @SerialName("Code") val code: Int,
)

/** One bucket of trashed items grouped by share + parent. Link IDs only — full details need a per-link fetch. */
@Serializable
data class VolumeTrashGroupDto(
    @SerialName("ShareID") val shareId: String,
    @SerialName("LinkIDs") val linkIds: List<String> = emptyList(),
    @SerialName("ParentIDs") val parentIds: List<String> = emptyList(),
)

@Serializable
data class BaseResponse(
    @SerialName("Code") val code: Int,
)

/**
 * The top-level [code] only means the batch was accepted, NOT that every link succeeded — read
 * each [responses] entry's per-link code (1000 = ok). Trusting the top-level code alone would
 * report success while some links silently stayed in trash.
 */
@Serializable
data class TrashActionResponse(
    @SerialName("Code") val code: Int,
    @SerialName("Responses") val responses: List<TrashActionOutcomeEntry> = emptyList(),
)

@Serializable
data class TrashActionOutcomeEntry(
    @SerialName("LinkID") val linkId: String,
    @SerialName("Response") val response: TrashActionOutcome,
)

@Serializable
data class TrashActionOutcome(
    @SerialName("Code") val code: Int,
    @SerialName("Error") val error: String? = null,
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

/** Unlike [BatchLinksResponse], each item IS the link directly (no "Link" wrapper). */
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
    // Server-canonical name hash. Pass it back verbatim as OriginalHash on rename/move — a
    // locally recomputed value fails the optimistic-concurrency check with "out of date".
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
    // Separate from SignatureEmail because the name and node-passphrase signatures can be issued by
    // different addresses (photo uploaded by one user, renamed by another).
    @SerialName("NameSignatureEmail") val nameSignatureEmail: String? = null,
    @SerialName("State") val state: Int? = null,
    // Canonical ContentKeyPacket location for file links — NOT ActiveRevision.ContentKeyPacket.
    @SerialName("FileProperties") val fileProperties: LinkFilePropertiesDto? = null,
    // Kept for non-file links (albums, folders) and backward-compat.
    @SerialName("ActiveRevision") val activeRevision: ActiveRevisionDto? = null,
)

/** Link.FileProperties: ContentKeyPacket is the PKESK for the content-block session key. */
@Serializable
data class LinkFilePropertiesDto(
    @SerialName("ContentKeyPacket") val contentKeyPacket: String? = null,
    @SerialName("ContentKeyPacketSignature") val contentKeyPacketSignature: String? = null,
    @SerialName("ActiveRevision") val activeRevision: LinkFileActiveRevisionDto? = null,
)

/** ActiveRevision under FileProperties. Uses "ID" (not "RevisionID"); CKP lives in FileProperties. */
@Serializable
data class LinkFileActiveRevisionDto(
    @SerialName("ID") val id: String? = null,
    @SerialName("State") val state: Int? = null,
    @SerialName("ManifestSignature") val manifestSignature: String? = null,
    @SerialName("Thumbnails") val thumbnails: List<ThumbnailEntryDto>? = null,
    // The share endpoint surfaces each photo's ContentHash here (the volume endpoint uses a
    // top-level `Photo` block); carried so Save-to-library reads it without a second round-trip.
    @SerialName("Photo") val photo: LinkPhotoDto? = null,
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

// Key is "RevisionID" here, NOT "ID" (which the file/revisions listing endpoint uses).
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
    // Primary CKP location for photos — FileProperties is null for photo links.
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

/** v1 share-bootstrap: all share fields at the top level (v2 [ShareDetailsResponse] wraps them in `Share`). */
@Serializable
data class ShareBootstrapResponse(
    @SerialName("Code") val code: Int,
    @SerialName("ShareID") val shareId: String,
    @SerialName("Type") val type: Int? = null,
    @SerialName("LinkID") val linkId: String? = null,
    @SerialName("VolumeID") val volumeId: String? = null,
    @SerialName("Key") val key: String? = null,
    @SerialName("Passphrase") val passphrase: String? = null,
    @SerialName("PassphraseSignature") val passphraseSignature: String? = null,
    @SerialName("AddressID") val addressId: String? = null,
    @SerialName("Creator") val creator: String? = null,
    // The caller's own membership rows — how a non-admin recipient learns their MemberID
    // (the admin-only /members listing 403s for them).
    @SerialName("Memberships") val memberships: List<ShareMembershipDto> = emptyList(),
)

@Serializable
data class ShareMembershipDto(
    @SerialName("MemberID") val memberId: String,
    @SerialName("ShareID") val shareId: String? = null,
    @SerialName("AddressID") val addressId: String? = null,
    @SerialName("Inviter") val inviter: String? = null,
    @SerialName("Permissions") val permissions: Long? = null,
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

// Album link fields nest under a "Link" object in the create-album body.
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
    /** Detached signature over the node passphrase. Recipients verify it against [signatureEmail]'s
     *  keys before unlocking; without it a shared-album recipient sees placeholder thumbnails. */
    @SerialName("NodePassphraseSignature") val nodePassphraseSignature: String? = null,
    /** Address that produced [nodePassphraseSignature] — missing here fails verification on strict clients. */
    @SerialName("SignatureEmail") val signatureEmail: String? = null,
)

@Serializable
data class AddAlbumMultipleRequest(
    @SerialName("AlbumData") val albumData: List<AddAlbumMultipleEntry>,
)

/**
 * The top-level [code] means the batch was processed, NOT that every photo succeeded — read each
 * [responses] entry's per-photo code (1000 = in the album). Trusting the top-level code alone would
 * report "added 6/6" while none actually landed.
 */
@Serializable
data class AddAlbumMultipleResponse(
    @SerialName("Code") val code: Int,
    @SerialName("Responses") val responses: List<PhotoAddOutcomeEntry> = emptyList(),
)

@Serializable
data class PhotoAddOutcomeEntry(
    @SerialName("LinkID") val linkId: String,
    @SerialName("Response") val response: PhotoAddOutcome,
)

@Serializable
data class PhotoAddOutcome(
    @SerialName("Code") val code: Int,
    @SerialName("Error") val error: String? = null,
    @SerialName("Details") val details: PhotoAddDetails? = null,
)

@Serializable
data class PhotoAddDetails(
    @SerialName("NewLinkID") val newLinkId: String? = null,
)

/** Removes album references for the listed linkIds; the photos stay in the Photos root. */
@Serializable
data class RemoveFromAlbumRequest(
    @SerialName("LinkIDs") val linkIds: List<String>,
)

/** Omitted fields are kept as-is. A rename sends Name + Hash + NameSignatureEmail + OriginalHash. */
@Serializable
data class UpdateAlbumLinkData(
    @SerialName("Name") val name: String? = null,
    @SerialName("Hash") val hash: String? = null,
    @SerialName("NameSignatureEmail") val nameSignatureEmail: String? = null,
    @SerialName("OriginalHash") val originalHash: String? = null,
    @SerialName("XAttr") val xAttr: String? = null,
)

/** Rename ([link]), set cover ([coverLinkId]), or both — each field is independently optional. */
@Serializable
data class UpdateAlbumRequest(
    @SerialName("CoverLinkID") val coverLinkId: String? = null,
    @SerialName("Link") val link: UpdateAlbumLinkData? = null,
)

/**
 * Idempotent only when the FULL key material is supplied — an empty body returns "Missing required
 * attributes Share,Link". On repeat calls the server ignores the body and returns the existing IDs,
 * but the body must still parse. Field names are case-sensitive.
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
    // Thumbnails are signed INTO the manifest (prepended to content block hashes), so the
    // download-side manifest verify needs to read them back. The batch link endpoint omits
    // this list; the revision endpoint includes it — capture it here so PhotoDownloadService
    // can prepend the bytes before calling verifyDetachedSignature.
    @SerialName("Thumbnails") val thumbnails: List<ThumbnailEntryDto>? = null,
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
    // Nullable: some rows omit ShareTargetType and a non-nullable Int would crash the whole parse.
    @SerialName("ShareTargetType") val shareTargetType: Int? = null,
)

/** "Shared by me" feed — link type is NOT in this listing, so callers resolve each LinkID. */
@Serializable
data class SharedByMeResponse(
    @SerialName("Links") val links: List<SharedByMeLinkDto> = emptyList(),
    @SerialName("AnchorID") val anchorId: String? = null,
    @SerialName("More") val more: Boolean = false,
    @SerialName("Code") val code: Int = 1000,
)

@Serializable
data class SharedByMeLinkDto(
    // The hosting share — for Photos, the volume's main photos share.
    @SerialName("ContextShareID") val contextShareId: String,
    // The sub-share created for this individual link (carries the public URL).
    @SerialName("ShareID") val shareId: String,
    @SerialName("LinkID") val linkId: String,
)

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

/** Permissions bitmap: 4 = viewer (read), 6 = editor (read + write). */
@Serializable
data class UpdateShareMemberRequest(
    @SerialName("Permissions") val permissions: Long,
)

/** Creates a new Drive share for an existing link (e.g. an album); see DriveApiService.createVolumeShare. */
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

/**
 * Mint a public share-URL. The backend rejects the request if ANY of the SRP/password fields below
 * is omitted — they drive the random-URL-password scheme a recipient uses to unlock without an account.
 * Permissions: 4 = read (only allowed value). Flags: 1 = custom password, 2 = client-random (default).
 */
@Serializable
data class CreateShareUrlRequest(
    /** UNIX timestamp when the URL stops working. Null = no absolute expiration. */
    @SerialName("ExpirationTime") val expirationTime: Long? = null,
    /** Relative TTL in seconds (max 90 days). Null = no expiration window. */
    @SerialName("ExpirationDuration") val expirationDuration: Long? = null,
    /** Max times the URL can be opened. 0 / null = unlimited. */
    @SerialName("MaxAccesses") val maxAccesses: Long? = null,
    /** Address email that minted this share — required for server-side display. */
    @SerialName("CreatorEmail") val creatorEmail: String,
    /** Permissions bitmap. 4 = read, 2 = write. Only 4 is currently allowed. */
    @SerialName("Permissions") val permissions: Long,
    /** Base64 random salt used in SRP password verification of the URL recipient. */
    @SerialName("UrlPasswordSalt") val urlPasswordSalt: String,
    /** Base64 salt for the salted-password used to re-encrypt the share passphrase. */
    @SerialName("SharePasswordSalt") val sharePasswordSalt: String,
    /** Base64 SRP verifier computed from the random URL password and the modulus. */
    @SerialName("SRPVerifier") val srpVerifier: String,
    /** SRP modulus ID returned by `randomModulus(sessionId)`. */
    @SerialName("SRPModulusID") val srpModulusId: String,
    /** Password flags. 2 = random URL password generated by the client. */
    @SerialName("Flags") val flags: Long,
    /** Base64 PKESK encrypting the share session key under the salted URL password. */
    @SerialName("SharePassphraseKeyPacket") val sharePassphraseKeyPacket: String,
    /** Armored PGP message: random URL password encrypted to the creator's address pub key. */
    @SerialName("Password") val encryptedUrlPassword: String,
    /** Optional armored encrypted display name. Drive web shows this in the public link UI. */
    @SerialName("Name") val name: String? = null,
)

@Serializable
data class ShareUrlDto(
    // Must be "ShareURLID" (capital URL) — "ShareUrlID" deserializes to null, breaking revoke +
    // then deleteShare (2005 "remove the public link first").
    @SerialName("ShareURLID") val shareUrlId: String? = null,
    @SerialName("Token") val token: String,
    @SerialName("PublicUrl") val publicUrl: String? = null,
    @SerialName("ShareID") val shareId: String? = null,
    /** Password flags. 0 = legacy (no random URL password); 2 = client-generated random password. */
    @SerialName("Flags") val flags: Long? = null,
    /** Creator's address email — whose address key the [encryptedUrlPassword] is encrypted to. */
    @SerialName("CreatorEmail") val creatorEmail: String? = null,
    /** Armored PGP message: the random URL password encrypted to the creator's address pub key. */
    @SerialName("Password") val encryptedUrlPassword: String? = null,
)

/**
 * Change a public share-URL's password. All eight SRP/password fields below are required again
 * (the backend re-derives the recipient's SRP challenge from the new password). Flags: 1 = custom
 * password (recipient types it), 2 = random (carried in the fragment, sent when clearing a custom one).
 */
@Serializable
data class UpdateShareUrlRequest(
    /** Password flags. 1 = custom (recipient types it), 2 = random (carried in the fragment). */
    @SerialName("Flags") val flags: Long,
    /** Base64 PKESK encrypting the share session key under the salted (new) URL password. */
    @SerialName("SharePassphraseKeyPacket") val sharePassphraseKeyPacket: String,
    /** Armored PGP message: the new URL password encrypted to the creator's address pub key. */
    @SerialName("Password") val encryptedUrlPassword: String,
    /** Base64 random salt used in SRP password verification of the URL recipient. */
    @SerialName("UrlPasswordSalt") val urlPasswordSalt: String,
    /** Base64 salt for the salted-password used to re-encrypt the share passphrase. */
    @SerialName("SharePasswordSalt") val sharePasswordSalt: String,
    /** Base64 SRP verifier computed from the new URL password and the modulus. */
    @SerialName("SRPVerifier") val srpVerifier: String,
    /** SRP modulus ID returned by `randomModulus(sessionId)`. */
    @SerialName("SRPModulusID") val srpModulusId: String,
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

/** Carries a re-encrypted KeyPacket + detached signature so only the invitee can derive the share key. */
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


/**
 * Server-side photo copy for "Save to my library" on shared-with-me albums. The backend keeps the
 * encrypted blob and rewraps the metadata we send (re-encrypted node passphrase + name + a fresh
 * HMAC under the target album's NodeHashKey), so the recipient never downloads + reuploads the bytes.
 */
@Serializable
data class CopyLinkRequest(
    @SerialName("Name") val name: String,
    @SerialName("Hash") val hash: String,
    @SerialName("TargetVolumeID") val targetVolumeId: String,
    @SerialName("TargetParentLinkID") val targetParentLinkId: String,
    @SerialName("NodePassphrase") val nodePassphrase: String,
    @SerialName("NameSignatureEmail") val nameSignatureEmail: String,
    @SerialName("NodePassphraseSignature") val nodePassphraseSignature: String? = null,
    @SerialName("SignatureEmail") val signatureEmail: String? = null,
    @SerialName("Photos") val photos: PhotosCopyData? = null,
) {
    @Serializable
    data class PhotosCopyData(
        @SerialName("ContentHash") val contentHash: String,
        @SerialName("RelatedPhotos") val relatedPhotos: List<RelatedPhotoCopyData> = emptyList(),
    )

    @Serializable
    data class RelatedPhotoCopyData(
        @SerialName("LinkID") val linkId: String,
        @SerialName("Name") val name: String,
        @SerialName("Hash") val hash: String,
        @SerialName("NodePassphrase") val nodePassphrase: String,
        @SerialName("ContentHash") val contentHash: String,
    )
}

@Serializable
data class CopyLinkResponse(
    @SerialName("Code") val code: Int,
    @SerialName("LinkID") val linkId: String? = null,
)
