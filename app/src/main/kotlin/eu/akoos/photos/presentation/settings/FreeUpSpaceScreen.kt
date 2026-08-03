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

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import eu.akoos.photos.R
import eu.akoos.photos.presentation.common.ConfirmSheet
import eu.akoos.photos.presentation.common.ErrorPopup
import eu.akoos.photos.presentation.common.floatingHeaderContentTopPadding
import eu.akoos.photos.presentation.gallery.SyncedCloudBadge
import eu.akoos.photos.presentation.settings.components.SettingsCard
import eu.akoos.photos.presentation.settings.components.SettingsPillHeader
import eu.akoos.photos.presentation.theme.AppColors
import eu.akoos.photos.presentation.util.formatBytes

/**
 * Shows exactly which photos will lose their device copy, then reclaims them.
 *
 * The reclaim permanently deletes the local file, often for thousands of photos at once, so the list
 * comes first and the button second. Every cell carries the same green backed-up badge the timeline
 * draws, from the same composable, because the one thing worth checking before agreeing is that each
 * photo really does have a Drive copy to fall back on.
 *
 * Leaving the screen stops the sweep. Nothing is lost by that: each photo is committed on its own,
 * so coming back lists what is left and the count carries on from there.
 *
 * A LazyVerticalGrid with the pill header overlaid, rather than `SettingsSubPageScaffold`, because a
 * library of thousands must not be laid out at once (see that scaffold's own note on the choice).
 */
@Composable
fun FreeUpSpaceScreen(
    onBack: () -> Unit,
    viewModel: FreeUpSpaceViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val colors = AppColors.current
    var showConfirm by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) viewModel.onPermissionGranted()
        else viewModel.onPermissionDenied()
    }
    LaunchedEffect(state.pendingIntent) {
        state.pendingIntent?.let { pi ->
            permissionLauncher.launch(IntentSenderRequest.Builder(pi.intentSender).build())
        }
    }

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
                            if (state.running) {
                                Spacer(Modifier.height(14.dp))
                                Text(
                                    // The Drive check has nothing to count, so it says what it is
                                    // doing rather than showing a bar sitting at zero.
                                    if (state.verifying) {
                                        stringResource(R.string.free_up_verifying)
                                    } else {
                                        stringResource(R.string.free_up_progress, state.done, state.total)
                                    },
                                    color = colors.fgDim,
                                    fontSize = 12.5.sp,
                                )
                                Spacer(Modifier.height(8.dp))
                                if (state.verifying) {
                                    LinearProgressIndicator(
                                        modifier = Modifier.fillMaxWidth(),
                                        color = colors.accent,
                                    )
                                } else {
                                    LinearProgressIndicator(
                                        progress = {
                                            if (state.total > 0) state.done.toFloat() / state.total else 0f
                                        },
                                        modifier = Modifier.fillMaxWidth(),
                                        color = colors.accent,
                                    )
                                }
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    stringResource(R.string.free_up_leaving_stops),
                                    color = colors.fgMute,
                                    fontSize = 11.5.sp,
                                )
                            }
                        }
                    }
                    if (!state.loading && !state.isEmpty) {
                        Spacer(Modifier.height(12.dp))
                        SettingsCard {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable(enabled = !state.running) { showConfirm = true }
                                    .padding(horizontal = 16.dp, vertical = 14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(
                                    stringResource(R.string.settings_storage_free_up),
                                    color = if (state.running) colors.fgMute else colors.accent,
                                    fontSize = 14.5.sp,
                                    fontWeight = FontWeight.Medium,
                                )
                                if (state.running) {
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
                        modifier = Modifier.fillMaxSize(),
                    )
                    // The same badge the timeline draws, from the same composable. The point of this
                    // screen is that the user can see each photo has a Drive copy before its device
                    // copy goes, and a lookalike drawn here could drift from the real one.
                    SyncedCloudBadge()
                }
            }
        }

        SettingsPillHeader(title = stringResource(R.string.settings_storage_free_up), onBack = onBack)
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
