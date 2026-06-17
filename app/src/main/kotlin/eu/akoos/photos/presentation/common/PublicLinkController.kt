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

package eu.akoos.photos.presentation.common

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.akoos.photos.R
import eu.akoos.photos.domain.entity.SyncStatus
import eu.akoos.photos.domain.repository.DrivePhotoRepository
import eu.akoos.photos.domain.repository.SyncStateRepository
import eu.akoos.photos.domain.usecase.ForceUploadLocalUrisUseCase
import eu.akoos.photos.presentation.viewer.PublicLinkState
import eu.akoos.photos.util.NetworkObserver
import eu.akoos.photos.util.friendlyNetworkError
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import me.proton.core.accountmanager.domain.AccountManager
import javax.inject.Inject

/**
 * Shared state machine for a single photo's public share link, surfaced in
 * [eu.akoos.photos.presentation.viewer.ManagePublicLinkSheet]. Drives the gallery's single-select
 * link row and the viewer's per-photo link sheet from one place so both behave identically: the
 * same [PublicLinkState], the same repo calls, and a captured [photoLinkId] guard so a selection /
 * page change mid-request can't apply a link to the wrong photo. Only share metadata is touched —
 * never photo content.
 *
 * Not a singleton: each owning ViewModel gets its own instance (its link state is per-screen), and
 * every method takes the ViewModel's [CoroutineScope] so the work is cancelled with that ViewModel.
 */
class PublicLinkController @Inject constructor(
    private val cloudRepo: DrivePhotoRepository,
    private val accountManager: AccountManager,
    private val syncStateRepo: SyncStateRepository,
    private val forceUploadLocalUris: ForceUploadLocalUrisUseCase,
    private val networkObserver: NetworkObserver,
    @ApplicationContext private val context: Context,
) {

    private val _state = MutableStateFlow<PublicLinkState>(PublicLinkState.None)
    val state: StateFlow<PublicLinkState> = _state.asStateFlow()

    /** Cloud linkId of the photo the link sheet currently acts on, captured when the sheet opens.
     *  Guards every link operation against the selection/page changing mid-request, and lets the
     *  copy handler hand the live URL to the clipboard. */
    private var photoLinkId: String? = null

    /**
     * Look up any existing public link for [linkId] (the single selected/viewed cloud photo) and
     * seed [state]. A failed lookup falls back to None so the user can still create one instead of
     * getting stuck on an error.
     *
     * [setLoading] mirrors the two original call sites exactly: the gallery flips to Loading
     * immediately while the lookup runs, the viewer leaves the state as-is (None) so the sheet
     * shows the create affordance without a spinner flash.
     */
    fun load(scope: CoroutineScope, linkId: String?, setLoading: Boolean) {
        photoLinkId = linkId
        if (linkId == null) {
            _state.value = PublicLinkState.None
            return
        }
        if (setLoading) _state.value = PublicLinkState.Loading
        scope.launch {
            val userId = accountManager.getPrimaryUserId().first() ?: run {
                if (photoLinkId == linkId) _state.value = PublicLinkState.None
                return@launch
            }
            runCatching { cloudRepo.getPhotoShareLink(userId, linkId) }
                .onSuccess { url ->
                    // Drop the result if the user swiped/changed selection to another photo while
                    // we were fetching — the captured linkId is the source of truth.
                    if (photoLinkId != linkId) return@onSuccess
                    _state.value = if (url != null) activeLinkState(url) else PublicLinkState.None
                }
                .onFailure {
                    if (photoLinkId == linkId) _state.value = PublicLinkState.None
                }
        }
    }

    /** Mint a public link for the photo the sheet is acting on. */
    fun create(scope: CoroutineScope) {
        val linkId = photoLinkId ?: return
        _state.value = PublicLinkState.Loading
        scope.launch {
            val userId = accountManager.getPrimaryUserId().first()
            if (userId == null) {
                _state.value = PublicLinkState.Error(context.getString(R.string.viewer_not_signed_in))
                return@launch
            }
            runCatching { cloudRepo.createPhotoShareLink(userId, linkId) }
                .onSuccess { url ->
                    if (photoLinkId != linkId) return@onSuccess
                    _state.value = activeLinkState(url)
                }
                .onFailure { e ->
                    if (photoLinkId != linkId) return@onFailure
                    val friendly = friendlyNetworkError(e, networkObserver.isOnline.value, context)
                    _state.value = PublicLinkState.Error(
                        friendly ?: context.getString(R.string.share_link_failed),
                    )
                }
        }
    }

    /**
     * Upload [localUri] (a not-yet-backed-up local photo), wait for its cloud id, then mint a public
     * link. The sheet shows the shared Loading spinner throughout, then the live link.
     */
    fun uploadAndCreate(scope: CoroutineScope, localUri: String) {
        photoLinkId = null
        _state.value = PublicLinkState.Loading
        scope.launch {
            val userId = accountManager.getPrimaryUserId().first()
            if (userId == null) {
                _state.value = PublicLinkState.Error(context.getString(R.string.viewer_not_signed_in))
                return@launch
            }
            runCatching { forceUploadLocalUris.forceUpload(userId, listOf(localUri)) }
            // Poll the sync state until the upload pipeline assigns a cloud id. A failed upload is
            // demoted back to LOCAL_ONLY (no cloud id), so once we've seen it start a return to
            // LOCAL_ONLY means it failed — stop waiting instead of spinning out the timeout.
            val linkId = withTimeoutOrNull(5 * 60_000L) {
                var sawUploading = false
                var st = syncStateRepo.getByUri(localUri)
                while (st?.cloudFileId == null) {
                    if (st?.status == SyncStatus.UPLOADING) sawUploading = true
                    else if (sawUploading && st?.status == SyncStatus.LOCAL_ONLY) break
                    delay(1_000)
                    st = syncStateRepo.getByUri(localUri)
                }
                st?.cloudFileId
            }
            if (linkId == null) {
                _state.value = PublicLinkState.Error(context.getString(R.string.share_link_failed))
                return@launch
            }
            photoLinkId = linkId
            runCatching { cloudRepo.createPhotoShareLink(userId, linkId) }
                .onSuccess { url -> _state.value = activeLinkState(url) }
                .onFailure { e ->
                    val friendly = friendlyNetworkError(e, networkObserver.isOnline.value, context)
                    _state.value = PublicLinkState.Error(friendly ?: context.getString(R.string.share_link_failed))
                }
        }
    }

    /** Revoke the photo's public link. Re-fetches to confirm the delete stuck before reporting
     *  None, so a silently-failed revoke keeps showing the live link rather than lying. */
    fun revoke(scope: CoroutineScope) {
        val linkId = photoLinkId ?: return
        _state.value = PublicLinkState.Loading
        scope.launch {
            val userId = accountManager.getPrimaryUserId().first()
            if (userId == null) {
                _state.value = PublicLinkState.Error(context.getString(R.string.viewer_not_signed_in))
                return@launch
            }
            runCatching { cloudRepo.revokePhotoShareLink(userId, linkId) }
                .onSuccess {
                    val still = runCatching { cloudRepo.getPhotoShareLink(userId, linkId) }.getOrNull()
                    if (photoLinkId == linkId) {
                        _state.value = if (still != null) activeLinkState(still) else PublicLinkState.None
                    }
                }
                .onFailure { e ->
                    if (photoLinkId != linkId) return@onFailure
                    val friendly = friendlyNetworkError(e, networkObserver.isOnline.value, context)
                    _state.value = PublicLinkState.Error(
                        friendly ?: context.getString(R.string.share_link_failed),
                    )
                }
        }
    }

    /** Set ([password] non-blank) or clear ([password] null/blank → random anyone-with-the-link)
     *  the custom password on the photo's public link. No-op if no link exists yet. */
    fun setPassword(scope: CoroutineScope, password: String?) {
        val linkId = photoLinkId ?: return
        _state.value = PublicLinkState.Loading
        scope.launch {
            val userId = accountManager.getPrimaryUserId().first()
            if (userId == null) {
                _state.value = PublicLinkState.Error(context.getString(R.string.viewer_not_signed_in))
                return@launch
            }
            runCatching { cloudRepo.setPhotoLinkPassword(userId, linkId, password) }
                .onSuccess { url ->
                    if (photoLinkId != linkId) return@onSuccess
                    _state.value = activeLinkState(url)
                }
                .onFailure { e ->
                    if (photoLinkId != linkId) return@onFailure
                    val friendly = friendlyNetworkError(e, networkObserver.isOnline.value, context)
                    _state.value = PublicLinkState.Error(
                        friendly ?: context.getString(R.string.share_password_failed),
                    )
                }
        }
    }

    /** The live public-link URL if one is currently active, for the screen's copy-to-clipboard. */
    fun currentUrl(): String? = (_state.value as? PublicLinkState.Active)?.url

    /** Clear any per-photo public-link state so a stale link can't show on the next photo. */
    fun reset() {
        photoLinkId = null
        _state.value = PublicLinkState.None
    }

    /** Build a [PublicLinkState.Active] for [url], inferring the password requirement from the
     *  presence of a `#fragment` (random anyone-with-the-link URLs carry the password there; a
     *  custom-password URL is bare and the recipient types it). */
    private fun activeLinkState(url: String): PublicLinkState.Active =
        PublicLinkState.Active(url = url, hasPassword = !url.contains('#'))
}
