/*
 * Photos for Proton
 * Copyright (C) 2026 Akoos <https://akoos.eu>
 *
 * Source:  https://github.com/gitakoos/proton-photos
 * Website: https://www.photosforproton.eu
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

package eu.akoos.photos.presentation.gallery

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import eu.akoos.photos.R
import eu.akoos.photos.data.hidden.HiddenFolderProgress
import eu.akoos.photos.presentation.common.OperationProgress
import eu.akoos.photos.presentation.common.OperationProgressPill
import kotlinx.coroutines.flow.StateFlow

/**
 * The timeline's progress pill: one surface for its own long work, matching the device-folder back-up
 * and album bulk actions. Sits at the top edge, clear of the selection drawer, which stays visible
 * while these run. A multi-download is not here: it registers with the TransferCenter, so the Activity
 * screen lists its photos and cancels the batch, and a second copy would just be a pill with no cancel
 * of its own.
 *
 * The back-up ticks and the folder-hide progress are collected here, so a tick recomposes this pill and
 * nothing around it.
 */
@Composable
internal fun BoxScope.GalleryOperationPill(
    progress: StateFlow<GalleryProgress>,
    folderHideProgress: StateFlow<HiddenFolderProgress?>,
    shareState: MultiShareState,
    visible: Boolean,
    topPadding: Dp,
    onCancelFolderHide: () -> Unit,
) {
    val opSharingTpl = stringResource(R.string.op_sharing_fmt)
    val opBackingUpTpl = stringResource(R.string.op_backing_up_fmt)
    val folderHidingTpl = stringResource(R.string.device_folder_hiding_fmt)
    val backup by progress.collectAsStateWithLifecycle()
    val hide by folderHideProgress.collectAsStateWithLifecycle()
    // Background back-up surfaces as the same pill as the share, so the user sees progress (and
    // can cancel) in-app instead of only from the notification.
    val uploadActive = backup.isSyncing && backup.uploadTotalCount > 0
    // The pill's X hides the back-up pill for the rest of this run; it never stops the back-up, whose
    // progress also shows by the avatar and whose cancel is in the notification and on Activity.
    var uploadPillHidden by remember { mutableStateOf(false) }
    LaunchedEffect(uploadActive) { if (!uploadActive) uploadPillHidden = false }
    // Hiding a device folder from the Albums tab reports here too: it copies file by file and a
    // folder can hold thousands, so it takes the pill's count and cancel rather than a blocking
    // sheet the user would have to sit through.
    val fh = hide
    val galleryOpProgress = when {
        shareState is MultiShareState.Working ->
            OperationProgress(shareState.done, shareState.total, opSharingTpl.format(shareState.done, shareState.total))
        fh != null ->
            OperationProgress(fh.done, fh.total, folderHidingTpl.format(fh.done, fh.total))
        uploadActive && !uploadPillHidden ->
            OperationProgress(
                backup.uploadDoneIdx, backup.uploadTotalCount,
                opBackingUpTpl.format(backup.uploadDoneIdx, backup.uploadTotalCount))
        else -> null
    }
    // Only the folder hide and the back-up are cancellable from the pill (a share finishes
    // quickly and has its own controls). The branches mirror the chain above, so the X always
    // belongs to the operation on show. Mirrors the upload notification's cancel.
    val galleryOpCancel: (() -> Unit)? = when {
        shareState is MultiShareState.Working -> null
        fh != null -> onCancelFolderHide
        uploadActive -> { { uploadPillHidden = true } }
        else -> null
    }
    // Hold the last non-null progress so the pill can animate OUT cleanly when the work finishes,
    // instead of vanishing the instant it goes null.
    var lastOpProgress by remember { mutableStateOf(galleryOpProgress) }
    if (galleryOpProgress != null) lastOpProgress = galleryOpProgress
    // Rides the same scroll-hide signal as the header and dock, so it never sits orphaned over the
    // grid once the rest of the chrome slides away (issue #98). Kept during selection mode.
    AnimatedVisibility(
        visible = visible && galleryOpProgress != null,
        enter = fadeIn() + slideInVertically { -it },
        exit = fadeOut() + slideOutVertically { -it },
        modifier = Modifier
            .align(Alignment.TopCenter)
            .padding(top = topPadding),
    ) {
        OperationProgressPill(
            progress = lastOpProgress,
            onCancel = galleryOpCancel,
            hidesOnly = uploadActive && fh == null,
        )
    }
}
