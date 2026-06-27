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

package eu.akoos.photos.domain.repository

import kotlinx.coroutines.flow.Flow
import me.proton.core.domain.entity.UserId
import eu.akoos.photos.domain.entity.Album
import eu.akoos.photos.domain.entity.AlbumChild
import eu.akoos.photos.domain.entity.CloudPhoto
import eu.akoos.photos.domain.entity.PendingInvitation
import eu.akoos.photos.domain.entity.ShareInvitation
import eu.akoos.photos.domain.entity.ShareMember
import eu.akoos.photos.domain.entity.SharedPhoto
import eu.akoos.photos.domain.entity.CloudTrashItem
import eu.akoos.photos.domain.entity.LocalMediaItem
import java.io.File

interface DrivePhotoRepository {
    suspend fun getVolumeId(userId: UserId): String
    suspend fun getShareId(userId: UserId, volumeId: String): String
    fun observeCloudPhotos(userId: UserId): Flow<List<CloudPhoto>>
    fun observePhotosByLinkIds(linkIds: List<String>): Flow<List<CloudPhoto>>
    suspend fun refreshCloudPhotos(userId: UserId, force: Boolean = false)
    suspend fun refreshCloudPhotosIncremental(userId: UserId)
    suspend fun loadAlbums(userId: UserId): List<Album>

    /**
     * DB-only read of the cached cloud album list. Empty when no successful refresh has ever
     * run on this device, or right after sign-out wiped the cache. Used by AlbumsScreen for
     * cold-launch instant paint (including airplane-mode starts) before the network refresh
     * lands. Cover thumbnail URLs are rehydrated from the local photo DB / disk cache where
     * available; cells with null thumbnailUrl will trigger the lazy decrypt scheduler on view.
     */
    suspend fun loadAlbumsCached(): List<Album>

    /**
     * Background pass that walks each album's children pagination on Drive and persists the
     * `albumLinkId → photoLinkId` rows so a subsequent `loadAlbumPhotos(...)` call hits the
     * local DB instead of a network round-trip — and an offline open of an album the user
     * has never tapped into still shows its photos. Skips albums whose cached row count
     * already matches Drive's photoCount (cheap freshness check). Intended to be
     * fire-and-forget from the AlbumsScreen's onSuccess; failures per album are swallowed
     * so one broken row doesn't poison the rest.
     */
    suspend fun prefetchAlbumsMembership(userId: UserId, albums: List<Album>)

    /**
     * Returns a `photoLinkId → primary album name` map across every album the user owns.
     * Photos belonging to multiple albums get the alphabetically-first album's name.
     * Photos that aren't in any album are absent from the map. Used by the download flow
     * to route album-bound photos into per-album folders without the caller having to
     * know the album membership upfront. 5-min cache; safe to call on every download.
     */
    suspend fun getAlbumMemberships(userId: UserId): Map<String, String>

    /**
     * Returns `photoLinkId → Set<albumLinkId>` across every album the user owns. Photos
     * absent from the map are not in any album. Used by the PhotoViewer's "Add to album"
     * sheet to show which albums the current photo is already in so the user can tap one
     * to REMOVE the photo instead of adding it again.
     */
    suspend fun getAlbumIdsByPhoto(userId: UserId): Map<String, Set<String>>
    suspend fun createDriveAlbum(userId: UserId, name: String): Album
    suspend fun loadAlbumChildren(userId: UserId, albumLinkId: String): List<AlbumChild>
    /**
     * @param onLinkIdsResolved fires once the album's child link IDs are known (cheap initial
     *  paginated call) — BEFORE the heavier per-photo metadata + thumbnail-info batches. Lets
     *  the caller drop its loading skeleton and start observing per-linkId DB rows so chunked
     *  upserts stream into the UI as they land.
     */
    suspend fun loadAlbumPhotos(
        userId: UserId,
        albumLinkId: String,
        volumeId: String? = null,
        sharingShareId: String? = null,
        onLinkIdsResolved: ((List<String>) -> Unit)? = null,
    ): List<CloudPhoto>

    /** DB-only read for an album's cached photos. Empty when nothing is cached yet (album
     *  not yet opened, or pre-migration legacy rows that lack parentLinkId). Cells with
     *  null thumbnailUrl will trigger the lazy decrypt scheduler as they scroll into view. */
    suspend fun loadAlbumPhotosCached(albumLinkId: String): List<CloudPhoto>
    /**
     * Downloads the full-resolution bytes for [photo] into the on-disk cache.
     *
     * [onProgress] (optional) receives `(doneBytes, totalBytes)` while blocks decrypt — debounced
     * to ~250 ms by the implementation, plus a guaranteed final emission at 100%. Used by the
     * photo viewer to render a percentage instead of an opaque spinner on multi-MB videos.
     *
     * Cached output lives in `cacheDir/fullres/` and survives across screens. Eviction is
     * handled by a TTL sweeper ([PhotoDownloadService.pruneStaleFullResCache]) — files older
     * than the TTL are reclaimed when the device is online. The sweeper holds off while
     * offline so cached photos stay viewable until the network returns.
     */
    suspend fun downloadFullResPhoto(
        userId: UserId,
        photo: CloudPhoto,
        preResolvedLinkDetail: eu.akoos.photos.data.api.dto.BatchLinkDto? = null,
        onProgress: ((doneBytes: Long, totalBytes: Long) -> Unit)? = null,
    ): File
    /**
     * [uploadUri] overrides [item].uri — used when metadata has been stripped to a temp file.
     *
     * [onProgress] (optional) receives `(phase, doneBytes, totalBytes)` as bytes flow through
     * the encrypt pipeline and then the CDN PUT pipeline. The implementation debounces emissions
     * to ~250 ms per phase and guarantees a final 100 % emission as each phase transitions.
     * [UploadPendingUseCase] forwards these into its per-file SharedFlow so the Settings progress
     * bar can render a live byte counter instead of a per-file-completion bar.
     */
    suspend fun uploadFile(
        userId: UserId,
        item: LocalMediaItem,
        sha1HexContentDigest: String,
        uploadUri: String = item.uri,
        // Source-derived Camera/Location + rotation-corrected dimensions for the photo xAttr,
        // already gated against the strip config by the caller (see UploadPendingUseCase).
        xAttrMetadata: eu.akoos.photos.data.repository.drive.UploadXAttrMetadata =
            eu.akoos.photos.data.repository.drive.UploadXAttrMetadata(),
        onProgress: ((
            phase: eu.akoos.photos.data.repository.drive.UploadPhase,
            doneBytes: Long,
            totalBytes: Long,
        ) -> Unit)? = null,
    ): String
    /**
     * Adds photos to an album. Returns an explicit breakdown of per-photo crypto failures so
     * the UI can warn when some entries are missing. If *every* entry fails (and the input was
     * non-empty), the implementation throws so the surrounding runCatching surfaces the error
     * as a hard failure.
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

    /**
     * Adds or removes a single Drive PhotoTag category id on a cloud photo (metadata-only, the
     * same harmless write path as [setCloudFavorite] — no content, revision, or re-encryption).
     * Tag 0 (Favorites) routes through the favorite path. Returns true on success.
     */
    suspend fun setCloudTag(userId: UserId, photo: CloudPhoto, tagId: Int, add: Boolean): Boolean
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

    /**
     * The cloud `ContentHash` (`HMAC-SHA256(rootNodeHashKey, sha1Hex)`) for a locally-computed bare
     * SHA-1 hex, or null when the root hash key isn't cached yet. Lets content-hash pairing match a
     * local file to its cloud copy regardless of the (possibly rename-on-upload) cloud displayName.
     */
    fun cloudContentHash(localSha1Hex: String): String?

    /** Returns all photos currently in the Drive server-side trash. */
    suspend fun getCloudTrash(userId: UserId): List<CloudTrashItem>
    /** Moves trashed photos back to the Photos stream (un-delete). Returns the per-link
     *  outcome so callers can keep rejected items selected and flag a stale gallery. */
    suspend fun restoreFromCloudTrash(
        userId: UserId,
        linkIds: List<String>,
    ): eu.akoos.photos.data.repository.drive.CloudRestoreOutcome
    /** Permanently removes photos from the server (cannot be undone). Returns the
     *  per-link outcome so callers can keep rejected items selected. */
    suspend fun deleteFromCloudForever(
        userId: UserId,
        linkIds: List<String>,
    ): eu.akoos.photos.data.repository.drive.CloudDeleteOutcome
    /** Creates a public share link for an album; returns the public URL. */
    suspend fun createAlbumShareLink(userId: UserId, albumLinkId: String): String

    /**
     * Creates (or reuses) a public share link for a single photo; returns the public URL.
     * If the photo is already shared by link the existing URL is returned rather than
     * minting a duplicate. Only share metadata is created — the photo's content is untouched.
     */
    suspend fun createPhotoShareLink(userId: UserId, photoLinkId: String): String

    /** Returns the existing public share-URL for a single photo, or null if it isn't shared. */
    suspend fun getPhotoShareLink(userId: UserId, photoLinkId: String): String?

    /**
     * Stops sharing a single photo by link: deletes the photo's share URL(s) and then the
     * share itself. Tolerant of an already-unshared photo. Only share metadata is removed.
     */
    suspend fun revokePhotoShareLink(userId: UserId, photoLinkId: String)

    /**
     * Sets or clears the custom password on a photo's existing public link, returning the new
     * full link string. A null/blank [password] clears it back to a random "anyone with the
     * link" share (the returned URL carries the password in its `#fragment`); a non-blank
     * [password] makes the link require that typed password (the returned URL is bare).
     * Only share metadata is touched. The photo must already have a public link.
     */
    suspend fun setPhotoLinkPassword(userId: UserId, photoLinkId: String, password: String?): String

    /** Invites a Proton user (by email) to an album with read permissions. */
    suspend fun inviteToAlbum(userId: UserId, albumLinkId: String, email: String)

    /**
     * Server-side photo-copy roll-up: takes every photo in [sourceAlbumLinkId]
     * (a shared-with-me album reached via [sharingShareId]) and copies it into a
     * new album owned by the caller. The backend duplicates the encrypted blobs
     * server-side after the recipient's client rewraps the per-photo metadata.
     *
     * Returns the linkId of the freshly-created owned album plus per-photo
     * counts so the UI can surface a "saved N of M" toast.
     */
    suspend fun saveSharedAlbumToOwnLibrary(
        userId: UserId,
        sharingShareId: String,
        sourceAlbumLinkId: String,
        sourceAlbumDecryptedName: String,
        sourceVolumeId: String,
    ): SaveSharedAlbumOutcome

    data class SaveSharedAlbumOutcome(
        val newAlbumLinkId: String,
        val copiedCount: Int,
        val failedCount: Int,
        val totalRequested: Int,
    )

    /**
     * Fire-and-forget singleton-backed launcher for [saveSharedAlbumToOwnLibrary].
     * The job survives VM destruction so navigating away mid-copy doesn't kill it.
     * Observe [saveSharedAlbumState] for progress + outcome.
     */
    fun startSaveSharedAlbumToOwnLibrary(
        userId: UserId,
        sharingShareId: String,
        sourceAlbumLinkId: String,
        sourceAlbumDecryptedName: String,
        sourceVolumeId: String,
    )

    val saveSharedAlbumState: kotlinx.coroutines.flow.StateFlow<SaveSharedAlbumProgress>

    fun acknowledgeSaveSharedAlbumResult()

    /** Aborts an in-flight save-to-library copy. Safe to call when no copy is
     *  running. Emits a terminal [SaveSharedAlbumProgress.Cancelled] state so the
     *  UI can surface a neutral acknowledgement. */
    fun cancelSaveSharedAlbumToOwnLibrary()

    sealed interface SaveSharedAlbumProgress {
        data object Idle : SaveSharedAlbumProgress
        data class Running(
            val sourceAlbumLinkId: String,
            val copied: Int,
            val total: Int,
        ) : SaveSharedAlbumProgress
        data class Done(
            val sourceAlbumLinkId: String,
            val newAlbumLinkId: String,
            val copiedCount: Int,
            val failedCount: Int,
            val totalRequested: Int,
        ) : SaveSharedAlbumProgress
        data class Failed(
            val sourceAlbumLinkId: String,
            val reason: String?,
        ) : SaveSharedAlbumProgress
        data class Cancelled(
            val sourceAlbumLinkId: String,
            val copied: Int,
            val total: Int,
        ) : SaveSharedAlbumProgress
    }
    /** Deletes an album share. */
    suspend fun deleteShare(userId: UserId, shareId: String)
    /**
     * Recipient-side leave: removes the current user's membership from a
     * shared-with-me album. The owner's copy and other members are unaffected.
     * Wipes the local album row + memberships so the grid updates immediately.
     */
    suspend fun leaveSharedAlbum(userId: UserId, shareId: String, albumLinkId: String)
    /**
     * Disables only the public URL on a share, keeping accepted members + pending
     * invitations intact. Use this when the user wants to stop sharing the link but
     * still let invited Proton accounts open the album.
     */
    suspend fun revokeShareUrlOnly(userId: UserId, shareId: String)
    /**
     * Changes a member's permission bitmap on an album share. Use 4 for viewer (read)
     * or 6 for editor (read + write). Other bitmaps are rejected server-side.
     */
    suspend fun changeMemberPermission(userId: UserId, shareId: String, memberId: String, permissions: Int)
    /**
     * Changes a PENDING invitation's permission bitmap before the invitee accepts.
     * Same 4 = viewer / 6 = editor semantics as [changeMemberPermission].
     */
    suspend fun changeInvitationPermission(userId: UserId, shareId: String, invitationId: String, permissions: Int)
    /** Returns albums that other users have shared with the current user. */
    suspend fun loadSharedWithMeAlbums(userId: UserId): List<Album>
    /** Returns individual library photos the current user has shared via a public link. */
    suspend fun loadSharedByMePhotos(userId: UserId): List<SharedPhoto>

    /**
     * Live view of the given shared-photo [linkIds] from the local listing DB, re-emitting
     * whenever a row's lazily-decrypted `thumbnailUrl` lands so the Shared grid fills in its
     * tiles without a manual refresh. Order follows [linkIds]; rows not (yet) in the DB are
     * dropped. Read-only.
     */
    fun observeSharedByMePhotos(linkIds: List<String>): kotlinx.coroutines.flow.Flow<List<SharedPhoto>>
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
     * Wipes the signed-out user's state so a re-login (same or different account) starts clean:
     * every plaintext crypto secret in memory (share key, root link key, node hash key) PLUS the
     * cached cloud rows for this user (photo_listing, sync_state, day_meta, albums). Call before
     * [me.proton.core.accountmanager.domain.AccountManager.disableAccount]. Decrypted disk caches,
     * Coil caches, and the per-user DataStore anchors are cleared by the caller (it owns the
     * Context + WorkManager). Leaving the cloud rows behind caused stale photos to reappear with
     * black thumbnails and an occasional 404 on re-login (the events anchor pointed at old state).
     */
    suspend fun clearCacheForSignOut(userId: UserId)

    // ── Lazy thumbnail decrypt ────────────────────────────────────────────
    //
    // Cells call [requestThumbnailDecrypt] when they enter the LazyGrid viewport and
    // [cancelThumbnailDecrypt] on dispose. The repository looks up the persisted
    // encrypted material for [linkId] and forwards to ThumbnailDecryptScheduler which
    // bounds concurrent decrypts and dedup's repeat requests. Successful decrypts
    // update the row's `thumbnailUrl`; the existing Flow observation re-emits and the
    // cell rebinds with the new URL.
    /**
     * Requests on-demand thumbnail decrypt for the photo with [linkId]. No-op when the
     * row already has a decrypted thumbnailUrl, is missing the encrypted material, or
     * is already in flight. Safe to call repeatedly from a DisposableEffect / scroll
     * loop — the scheduler dedup's by linkId.
     */
    fun requestThumbnailDecrypt(userId: UserId, linkId: String)

    /**
     * Cancels an in-flight thumbnail decrypt for [linkId]. Wired to PhotoCell's
     * DisposableEffect.onDispose so scrolling past a cell before its turn comes up
     * doesn't spend JNI bandwidth on work the user no longer needs.
     */
    fun cancelThumbnailDecrypt(linkId: String)

    /**
     * Look-ahead decrypt for [linkIds] at prefetch priority. Fed by the gallery scroll
     * state with the rows just beyond the viewport in the scroll direction, so they are
     * already warm by the time the user reaches them. Every entry sorts behind the
     * visible band, so this never delays an on-screen cell. No-op for rows already
     * decrypted, cached, queued, or running.
     */
    fun prefetchThumbnailDecrypt(userId: UserId, linkIds: List<String>)

    /**
     * Nulls every cached `file://` thumbnail path in the DB so the rows fall back to the
     * lazy decrypt path. Call after deleting the decrypted-thumbnail files from disk —
     * otherwise the stored paths point at missing files and the scheduler skips re-decrypt
     * while the column is non-null, leaving tiles blank until a full library refresh.
     */
    suspend fun clearCachedThumbnailUrls()

    /**
     * Decrypt [linkIds] at visible (viewport) priority even though they may sit outside the
     * scrolling grid — used by surfaces that render off-grid thumbnails which would
     * otherwise never trigger a per-cell request (e.g. the "On this day" memories row at
     * the top of the timeline). No-op for rows already decrypted, cached, queued, or running.
     */
    fun requestThumbnailDecrypt(userId: UserId, linkIds: List<String>)

    /**
     * Warm the whole library's thumbnail cache in the background at the lowest priority, so a
     * large account (thousands of photos) fills in without the user scrolling past every row.
     * Runs only while the viewport and prefetch windows are idle and skips anything already
     * decrypted, cached, queued, or running, so it never delays a visible cell.
     */
    fun backfillThumbnails(userId: UserId)

    /**
     * Walk the cloud photos whose encrypted XAttr hasn't been geocoded yet, decrypt each to recover
     * its GPS Location block, and persist the coordinates so the map plots synced photos. Resumable
     * and idempotent — a re-run only touches rows not yet checked. Needs no runtime permission
     * (cloud GPS comes from the photo's own encrypted metadata, not the device MediaStore).
     */
    suspend fun backfillCloudGps(userId: UserId)
}
