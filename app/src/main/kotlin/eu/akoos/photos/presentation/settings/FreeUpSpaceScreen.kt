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

package eu.akoos.photos.presentation.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BrokenImage
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import coil.compose.AsyncImagePainter
import eu.akoos.photos.R
import eu.akoos.photos.presentation.common.ConfirmSheet
import eu.akoos.photos.presentation.common.ErrorPopup
import eu.akoos.photos.presentation.common.FloatingHeader
import eu.akoos.photos.presentation.common.floatingHeaderContentTopPadding
import eu.akoos.photos.presentation.gallery.SyncedCloudBadge
import eu.akoos.photos.presentation.settings.components.SettingsCard
import eu.akoos.photos.presentation.theme.AppColors
import eu.akoos.photos.presentation.util.formatBytes

/**
 * Shows reclaimable photos and enqueues/cancels the manual Free-up pipeline worker. Progress and
 * reclaim run under WorkManager; permanent-delete consent (strict prepareBatch + launch-failure
 * recovery) is handled by [FreeUpPermanentDeleteHandler] mounted on this route.
 */
@Composable
fun FreeUpSpaceScreen(
    onBack: () -> Unit,
    viewModel: FreeUpSpaceViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val colors = AppColors.current
    var showConfirm by remember { mutableStateOf(false) }
    val busy = state.phase == FreeUpSpacePhase.Checking ||
        state.phase == FreeUpSpacePhase.Freeing

    state.message?.let { message ->
        ErrorPopup(
            title = stringResource(R.string.settings_storage_free_up),
            message = message,
            onDismiss = viewModel::clearMessage,
        )
    }

    Box(modifier = Modifier.fillMaxSize().background(colors.pageBg)) {
        val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        val contentTop = floatingHeaderContentTopPadding()

        LazyVerticalGrid(
            columns = GridCells.Fixed(4),
            modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp),
            contentPadding = PaddingValues(top = contentTop, bottom = navBottom + 24.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column {
                    SettingsCard {
                        Column(modifier = Modifier.padding(16.dp)) {
                            when {
                                state.loading -> Text(
                                    stringResource(R.string.free_up_checking),
                                    color = colors.fgDim,
                                    fontSize = 13.sp,
                                )
                                state.isEmpty -> Text(
                                    stringResource(R.string.settings_free_up_none),
                                    color = colors.fgDim,
                                    fontSize = 13.sp,
                                )
                                else -> {
                                    Text(
                                        pluralStringResource(
                                            R.plurals.free_up_summary,
                                            state.candidates.size,
                                            state.candidates.size,
                                            formatBytes(state.reclaimableBytes),
                                        ),
                                        color = colors.fgPrimary,
                                        fontSize = 14.5.sp,
                                        fontWeight = FontWeight.Medium,
                                    )
                                    Spacer(Modifier.height(6.dp))
                                    Text(
                                        stringResource(R.string.free_up_explainer),
                                        color = colors.fgDim,
                                        fontSize = 12.5.sp,
                                    )
                                }
                            }
                            when (state.phase) {
                                FreeUpSpacePhase.Checking -> {
                                    FreeUpProgressSection(
                                        label = if (state.checkTotal > 0) {
                                            stringResource(
                                                R.string.free_up_verify_progress,
                                                state.checked,
                                                state.checkTotal,
                                            )
                                        } else {
                                            stringResource(R.string.free_up_verifying)
                                        },
                                        progress = if (state.checkTotal > 0) {
                                            state.checked.toFloat() / state.checkTotal
                                        } else {
                                            null
                                        },
                                    )
                                }
                                FreeUpSpacePhase.Freeing -> {
                                    FreeUpProgressSection(
                                        label = stringResource(
                                            R.string.free_up_progress,
                                            state.processed,
                                            state.deletionTotal,
                                            state.removed,
                                        ),
                                        progress = if (state.deletionTotal > 0) {
                                            state.processed.toFloat() / state.deletionTotal
                                        } else {
                                            0f
                                        },
                                    )
                                }
                                FreeUpSpacePhase.Stopped -> {
                                    Spacer(Modifier.height(14.dp))
                                    Text(
                                        if (state.awaitingConsentCount > 0) {
                                            pluralStringResource(
                                                R.plurals.free_up_awaiting_consent_notification_text,
                                                state.awaitingConsentCount,
                                                state.awaitingConsentCount,
                                            )
                                        } else {
                                            stringResource(R.string.free_up_stopped)
                                        },
                                        color = colors.fgDim,
                                        fontSize = 12.5.sp,
                                    )
                                }
                                else -> Unit
                            }
                        }
                    }
                    if (!state.loading && (!state.isEmpty || state.phase == FreeUpSpacePhase.Stopped)) {
                        Spacer(Modifier.height(12.dp))
                        SettingsCard {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        when (state.phase) {
                                            FreeUpSpacePhase.Checking,
                                            FreeUpSpacePhase.Freeing,
                                            -> viewModel.stop()
                                            FreeUpSpacePhase.Stopped -> viewModel.continueWork()
                                            else -> showConfirm = true
                                        }
                                    }
                                    .padding(horizontal = 16.dp, vertical = 14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(
                                    stringResource(
                                        when (state.phase) {
                                            FreeUpSpacePhase.Checking -> R.string.free_up_stop_checking
                                            FreeUpSpacePhase.Freeing -> R.string.free_up_stop_freeing
                                            FreeUpSpacePhase.Stopped -> R.string.free_up_continue
                                            else -> R.string.settings_storage_free_up
                                        },
                                    ),
                                    color = colors.accent,
                                    fontSize = 14.5.sp,
                                    fontWeight = FontWeight.Medium,
                                )
                                if (busy) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(18.dp),
                                        strokeWidth = 2.dp,
                                        color = colors.accent,
                                    )
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                }
            }

            items(state.candidates, key = { it.localUri }) { row ->
                // Dead localUri (already pruned from MediaStore) leaves Coil with nothing to
                // draw — track Error and show a muted broken-image glyph over cardBg so the
                // tile is not an unexplained black square.
                var decodeFailed by remember(row.localUri) { mutableStateOf(false) }
                Box(
                    modifier = Modifier
                        .aspectRatio(1f)
                        .clip(RoundedCornerShape(10.dp))
                        .background(colors.cardBg),
                ) {
                    AsyncImage(
                        model = row.localUri,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        onState = { st ->
                            when (st) {
                                is AsyncImagePainter.State.Success -> decodeFailed = false
                                is AsyncImagePainter.State.Error -> decodeFailed = true
                                else -> Unit
                            }
                        },
                        modifier = Modifier.fillMaxSize(),
                    )
                    if (decodeFailed) {
                        Icon(
                            Icons.Default.BrokenImage,
                            contentDescription = null,
                            tint = colors.fgDim.copy(alpha = 0.45f),
                            modifier = Modifier
                                .align(Alignment.Center)
                                .size(28.dp),
                        )
                    }
                    SyncedCloudBadge()
                }
            }
        }

        FloatingHeader(title = stringResource(R.string.settings_storage_free_up), onBack = onBack)
    }

    if (showConfirm) {
        ConfirmSheet(
            title = stringResource(R.string.settings_storage_free_up),
            message = pluralStringResource(
                R.plurals.free_up_confirm,
                state.candidates.size,
                state.candidates.size,
                formatBytes(state.reclaimableBytes),
            ),
            confirmLabel = stringResource(R.string.settings_storage_free_up),
            dismissLabel = stringResource(R.string.cancel),
            onConfirm = { showConfirm = false; viewModel.start() },
            onDismiss = { showConfirm = false },
        )
    }
}

/** Shared Checking / Freeing progress: label + determinate or indeterminate bar. */
@Composable
private fun FreeUpProgressSection(
    label: String,
    progress: Float?,
) {
    val colors = AppColors.current
    Spacer(Modifier.height(14.dp))
    Text(
        label,
        color = colors.fgDim,
        fontSize = 12.5.sp,
    )
    Spacer(Modifier.height(8.dp))
    if (progress != null) {
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier.fillMaxWidth(),
            color = colors.accent,
        )
    } else {
        LinearProgressIndicator(
            modifier = Modifier.fillMaxWidth(),
            color = colors.accent,
        )
    }
}
