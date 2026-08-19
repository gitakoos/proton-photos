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

package eu.akoos.photos.presentation.person

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.akoos.photos.R
import eu.akoos.photos.presentation.common.IconBubble
import eu.akoos.photos.presentation.common.floatingHeaderContentTopPadding
import eu.akoos.photos.presentation.gallery.PhotoCell
import eu.akoos.photos.presentation.gallery.photoCellInputsFor
import eu.akoos.photos.presentation.settings.components.SettingsPillHeader
import eu.akoos.photos.presentation.theme.Accent
import eu.akoos.photos.presentation.theme.AppColors
import eu.akoos.photos.presentation.theme.FgMute

/**
 * The review grid for "find more photos of this person": each photo the sweep matched, selected by
 * default so a quick tap on the add action takes them all, with a tap toggling any the user judges
 * wrong. A progress line runs while the sweep is still going, and the empty state names the outcome
 * once it finishes with nothing to show.
 */
@Composable
fun FindMorePhotosScreen(
    state: FindMoreUiState,
    onBack: () -> Unit,
    onAdd: (Set<String>) -> Unit,
) {
    val colors = AppColors.current
    var selected by remember { mutableStateOf(emptySet<String>()) }
    var deselected by remember { mutableStateOf(emptySet<String>()) }
    // Newly found photos start selected; the user's own deselections stick.
    LaunchedEffect(state.found.size) {
        selected = state.found.map { it.faceId }.filterNot { it in deselected }.toSet()
    }

    fun toggle(id: String) {
        if (id in selected) { selected = selected - id; deselected = deselected + id }
        else { selected = selected + id; deselected = deselected - id }
    }

    Box(
        modifier = Modifier.fillMaxSize().background(colors.pageBg),
    ) {
        if (state.found.isEmpty() && !state.running) {
            Text(
                stringResource(R.string.find_more_empty),
                color = FgMute,
                fontSize = 14.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.align(Alignment.Center).padding(horizontal = 40.dp),
            )
        } else {
            val cols = eu.akoos.photos.presentation.gallery.rememberDefaultGridColumns()
            val seamless = eu.akoos.photos.presentation.gallery.rememberSeamlessGrid()
            LazyVerticalGrid(
                columns = GridCells.Fixed(cols),
                contentPadding = PaddingValues(
                    start = if (seamless) 0.dp else 20.dp,
                    end = if (seamless) 0.dp else 20.dp,
                    top = floatingHeaderContentTopPadding(),
                    bottom = 24.dp,
                ),
                horizontalArrangement = Arrangement.spacedBy(if (seamless) 2.dp else 6.dp),
                verticalArrangement = Arrangement.spacedBy(if (seamless) 2.dp else 6.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                // The progress rides as a full-width row at the top, so found photos sit BELOW it as
                // they stream in rather than sliding under a floating bar.
                if (state.running && state.total > 0) {
                    item(span = { GridItemSpan(maxLineSpan) }, key = "sweep_progress") {
                        Column(modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp)) {
                            Text(
                                stringResource(R.string.find_more_searching, state.done, state.total),
                                color = colors.fgMute,
                                fontSize = 12.5.sp,
                            )
                            Spacer(Modifier.height(6.dp))
                            LinearProgressIndicator(
                                progress = { if (state.total > 0) state.done.toFloat() / state.total else 0f },
                                modifier = Modifier.fillMaxWidth(),
                                color = colors.accent,
                            )
                        }
                    }
                }
                items(items = state.found, key = { it.faceId }) { found ->
                    val inputs = remember(found.item) { photoCellInputsFor(found.item) }
                    PhotoCell(
                        imageData = inputs.imageData,
                        stableKey = inputs.stableKey,
                        isVideo = inputs.isVideo,
                        isLocalVideo = inputs.isLocalVideo,
                        durationMs = inputs.durationMs,
                        isPlaceholder = inputs.isPlaceholder,
                        selected = found.faceId in selected,
                        isSelectionMode = true,
                        showCloudBadge = inputs.showCloudBadge,
                        showSyncedBadge = inputs.showSyncedBadge,
                        isFavorite = inputs.isFavorite,
                        typeBadgeRes = inputs.typeBadgeRes,
                        typeBadgeCdRes = inputs.typeBadgeCdRes,
                        columns = cols,
                        cornerRadius = if (seamless) 0.dp else 10.dp,
                        onClick = { toggle(found.faceId) },
                        onLongClick = { toggle(found.faceId) },
                    )
                }
            }
        }

        SettingsPillHeader(
            title = state.personName?.let { stringResource(R.string.find_more_title_named, it) }
                ?: stringResource(R.string.find_more_title),
            onBack = onBack,
            trailing = {
                if (selected.isNotEmpty()) {
                    IconBubble(
                        icon = Icons.Default.Check,
                        contentDescription = stringResource(R.string.find_more_add, selected.size),
                        onClick = { onAdd(selected) },
                        diameter = 40.dp,
                        iconSize = 18.dp,
                        background = colors.accent,
                        borderColor = colors.accent,
                        tint = androidx.compose.ui.graphics.Color.White,
                    )
                }
            },
        )

        // A small spinner in the corner while searching, so an empty grid still reads as "working".
        if (state.running && state.found.isEmpty() && state.total == 0) {
            CircularProgressIndicator(
                color = Accent,
                modifier = Modifier.align(Alignment.Center),
            )
        }
    }
}
