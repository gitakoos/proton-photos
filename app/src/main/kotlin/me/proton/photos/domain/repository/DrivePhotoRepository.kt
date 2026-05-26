package me.proton.photos.domain.repository

import kotlinx.coroutines.flow.Flow
import me.proton.core.domain.entity.UserId
import me.proton.photos.domain.entity.Album
import me.proton.photos.domain.entity.AlbumChild
import me.proton.photos.domain.entity.CloudPhoto
import me.proton.photos.domain.entity.PendingInvitation
import me.proton.photos.domain.entity.ShareInvitation
import me.proton.photos.domain.entity.ShareMember
import me.proton.photos.domain.entity.CloudTrashItem
import me.proton.photos.domain.entity.LocalMediaItem
import java.io.File

interface DrivePhotoRepository {
    suspend fun getVolumeId(userId: UserId): String
    suspend fun getShareId(userId: UserId, volumeId: String): String
    fun observeCloudPhotos(userId: UserId): Flow<List<CloudPhoto>>
    fun observePhotosByLinkIds(linkIds: List<String>): Flow<List<CloudPhoto>>
    suspend fun refreshCloudPhotos(userId: UserId)
    suspend fun refreshCloudPhotosIncremental(userId: UserId)
    suspend fun loadAlbums(userId: UserId): List<Album>

    /**
     * Returns a `photoLinkId → primary album name` map across every album the user owns.
     * Photos belonging to multiple albums get the alphabetically-first album's name.
     * Photos that aren't in any album are absent from the map. Used by the download flow
     * to route album-bound photos into per-album folders without the caller having to
     * know the album membership upfront. 5-min cache; safe to call on every download.
     */
    suspend fun getAlbumMemberships(userId: UserId): Map<String, String>
    suspend fun createDriveAlbum(userId: UserId, name: String): Album
    suspend fun loadAlbumChildren(userId: UserId, albumLinkId: String): List<AlbumChild>
    suspend fun loadAlbumPhotos(userId: UserId, albumLinkId: String, volumeId: String? = null): List<CloudPhoto>
    /**
     * Downloads the full-resolution bytes for [photo] into the on-disk cache.
     *
     * [onProgress] (optional) receives `(doneBytes, totalBytes)` while blocks decrypt — debounced
     * to ~250 ms by the implementation, plus a guaranteed final emission at 100%. Used by the
     * photo viewer to render a percentage instead of an opaque spinner on multi-MB videos.
     */
    suspend fun downloadFullResPhoto(
        userId: UserId,
        photo: CloudPhoto,
        onProgress: ((doneBytes: Long, totalBytes: Long) -> Unit)? = null,
    ): File
    /** [uploadUri] overrides [item].uri — used when metadata has been stripped to a temp file. */
    suspend fun uploadFile(userId: UserId, item: LocalMediaItem, hash: String, uploadUri: String = item.uri): String
    /**
     * Adds photos to an album. Per-photo crypto failures used to be silently swallowed; the
     * caller now receives an explicit breakdown so the UI can warn the user when some entries
     * are missing. If *every* entry fails (and the input was non-empty), the implementation
     * throws so the surrounding runCatching surfaces the error as a hard failure.
     */
    suspend fun addPhotosToAlbum(
        userId: UserId,
        albumLinkId: String,
        photoLinkIds: List<String>,
    ): AddPhotosToAlbumResult

    data class AddPhotosToAlbumResult(
        val succeededLinkIds: List<String>,
        val failedLinkIds: List<String>,
    )
    suspend fun deleteFiles(userId: UserId, linkIds: List<String>)

    /**
     * Renames a cloud photo. Since the Drive Photos API does not expose a server-side rename
     * endpoint, this is implemented as download-then-reupload-as-[newName]. When [trashOriginal]
     * is true (rename-in-place semantics), the source [photo].linkId is moved to Recently Deleted
     * after the new upload succeeds; otherwise the original stays as well (save-as-copy).
     *
     * Returns the new linkId.
     */
    suspend fun renameOrCopyCloudPhoto(
        userId: UserId,
        photo: CloudPhoto,
        newName: String,
        trashOriginal: Boolean,
    ): String

    /**
     * Sets / clears the server-side Favorite tag (PhotoTag id = 0) on a cloud-backed photo.
     *
     * - [favorite] = true  → POST /favorite (PhotoData = null, basic API). The server attaches
     *   the photo to its automatic Favorites collection.
     * - [favorite] = false → DELETE /tags with Tags=[0].
     *
     * Returns true on success. Logs and returns false on any API/network error so the UI
     * can still apply local optimistic state.
     */
    suspend fun setCloudFavorite(userId: UserId, photo: CloudPhoto, favorite: Boolean): Boolean
    suspend fun deleteAlbum(userId: UserId, albumLinkId: String)

    /**
     * Removes the album reference for each [photoLinkIds] without touching the underlying
     * photos. Returns the linkIds the server confirmed removed; partial failures (network
     * blip on one chunk) are logged but don't fail the whole call.
     *
     * The photo stays in Photos root regardless of how many albums it was in.
     */
    suspend fun removePhotosFromAlbum(
        userId: UserId,
        albumLinkId: String,
        photoLinkIds: List<String>,
    ): List<String>

    /**
     * Renames an album. Re-encrypts the new name to the parent (root) link key, computes a
     * fresh HMAC-SHA256 hash with the root NodeHashKey, and supplies the previous hash for
     * server-side optimistic-concurrency validation. Throws if anything in the crypto chain
     * fails so the caller can present the actual error.
     */
    suspend fun renameAlbum(userId: UserId, albumLinkId: String, newName: String)

    /**
     * Sets the cover photo for an album. [coverPhotoLinkId] must be the linkId of a photo
     * that's already a member of the album — Drive doesn't validate this server-side, but
     * a non-member cover would render as a broken tile in the album list.
     */
    suspend fun setAlbumCover(userId: UserId, albumLinkId: String, coverPhotoLinkId: String)
    /** Returns all photos currently in the Drive server-side trash. */
    suspend fun getCloudTrash(userId: UserId): List<CloudTrashItem>
    /** Moves trashed photos back to the Photos stream (un-delete). */
    suspend fun restoreFromCloudTrash(userId: UserId, linkIds: List<String>)
    /** Permanently removes photos from the server (cannot be undone). */
    suspend fun deleteFromCloudForever(userId: UserId, linkIds: List<String>)
    /** Creates a public share link for an album; returns the public URL. */
    suspend fun createAlbumShareLink(userId: UserId, albumLinkId: String): String
    /** Invites a Proton user (by email) to an album with read permissions. */
    suspend fun inviteToAlbum(userId: UserId, albumLinkId: String, email: String)
    /** Deletes an album share. */
    suspend fun deleteShare(userId: UserId, shareId: String)
    /** Returns albums that other users have shared with the current user. */
    suspend fun loadSharedWithMeAlbums(userId: UserId): List<Album>
    /** Returns the list of users invited to a share. */
    suspend fun loadShareInvitations(userId: UserId, shareId: String): List<ShareInvitation>
    /** Revokes a specific invitation from a share. */
    suspend fun revokeShareInvitation(userId: UserId, shareId: String, invitationId: String)
    /** Returns accepted members of a share. */
    suspend fun loadShareMembers(userId: UserId, shareId: String): List<ShareMember>

    /** Removes an accepted member from a share (revoke access). */
    suspend fun removeShareMember(userId: UserId, shareId: String, memberId: String)
    /** Returns pending album-share invitations addressed to the current user. */
    suspend fun loadPendingInvitations(userId: UserId): List<PendingInvitation>
    /** Declines (removes) a pending invitation. */
    suspend fun declineInvitation(userId: UserId, invitationId: String)

    /**
     * Accepts a pending album-share invitation. Fetches the invitation detail (to obtain the
     * KeyPacket), decrypts the share session key, signs it with the user's primary address,
     * and POSTs the signature to `/accept`. Throws on failure so the caller can surface the
     * actual server / crypto error to the user instead of a generic "failed" message.
     */
    suspend fun acceptInvitation(userId: UserId, invitationId: String)

    /**
     * Wipes every plaintext crypto secret (share key, root link key, node hash key) from memory.
     * Call before [me.proton.core.accountmanager.domain.AccountManager.disableAccount] or when a
     * user switch is detected, so heap inspection cannot recover key material across sessions.
     */
    fun clearCacheForSignOut()
}
