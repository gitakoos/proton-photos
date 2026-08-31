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

package eu.akoos.photos.presentation.people

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import eu.akoos.photos.R
import eu.akoos.photos.presentation.common.FloatingHeader
import eu.akoos.photos.presentation.common.floatingHeaderContentTopPadding
import eu.akoos.photos.presentation.gallery.FaceCropTransformation
import eu.akoos.photos.presentation.gallery.LocalThumbnailUrls
import eu.akoos.photos.presentation.theme.AppColors
import eu.akoos.photos.presentation.theme.pillShape

/**
 * The face-recognition exclusions the user can undo: faces removed from a named person ("Not this
 * person", one section per name) and faces dismissed as "not a person" (one "Ignored" section). Each
 * face shows as a cropped thumbnail with an Undo control that reverses the underlying exclusion so the
 * face is re-evaluated on the next clustering pass. Reuses the People rail's cover resolution
 * ([LocalThumbnailUrls]) and [FaceCropTransformation], so no new decrypt path is introduced.
 */
@Composable
fun FaceExclusionsScreen(
    state: FaceExclusionsUiState,
    onBack: () -> Unit,
    onUndoNotThisPerson: (name: String, faceId: String) -> Unit,
    onUndoIgnored: (faceId: String) -> Unit,
) {
    val colors = AppColors.current
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.pageBg),
    ) {
        if (!state.isLoading && state.isEmpty) {
            Text(
                text = stringResource(R.string.face_excluded_empty),
                color = colors.fgMute,
                fontSize = 14.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(horizontal = 40.dp),
            )
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                contentPadding = PaddingValues(
                    start = 14.dp,
                    end = 14.dp,
                    top = floatingHeaderContentTopPadding(),
                    bottom = 24.dp,
                ),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                state.notThisPerson.forEach { group ->
                    item(span = { GridItemSpan(maxLineSpan) }, key = "np-header-${group.name}") {
                        SectionHeader(stringResource(R.string.face_excluded_not_person_section, group.name))
                    }
                    items(group.faces, key = { "np-${group.name}-${it.faceId}" }) { face ->
                        ExcludedFaceTile(face = face, onUndo = { onUndoNotThisPerson(group.name, face.faceId) })
                    }
                }
                if (state.ignored.isNotEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }, key = "ig-header") {
                        SectionHeader(stringResource(R.string.face_excluded_ignored_section))
                    }
                    items(state.ignored, key = { "ig-${it.faceId}" }) { face ->
                        ExcludedFaceTile(face = face, onUndo = { onUndoIgnored(face.faceId) })
                    }
                }
            }
        }

        FloatingHeader(
            title = stringResource(R.string.settings_face_excluded),
            onBack = onBack,
        )
    }
}

/** Full-width group label above a run of face tiles. */
@Composable
private fun SectionHeader(text: String) {
    val colors = AppColors.current
    Text(
        text = text,
        color = colors.fgPrimary,
        fontSize = 14.sp,
        fontWeight = FontWeight.SemiBold,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 10.dp, bottom = 2.dp),
    )
}

/** One excluded face: its cropped thumbnail over the page card, with an Undo pill below. */
@Composable
private fun ExcludedFaceTile(face: ExcludedFaceUi, onUndo: () -> Unit) {
    val context = LocalContext.current
    val colors = AppColors.current
    val model = LocalThumbnailUrls.current.value[face.photoKey] ?: face.photoKey
    val request = remember(model, face.box) {
        ImageRequest.Builder(context)
            .data(model)
            .size(EXCLUDED_FACE_TILE_PX)
            .crossfade(false)
            .transformations(FaceCropTransformation(face.box))
            .build()
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(14.dp))
                .background(colors.bg2),
        ) {
            AsyncImage(
                model = request,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Spacer(Modifier.height(6.dp))
        Row(
            modifier = Modifier
                .clip(pillShape)
                .background(colors.pillBg, pillShape)
                .border(0.5.dp, colors.pillBorder, pillShape)
                .clickable(onClick = onUndo)
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon(
                Icons.AutoMirrored.Filled.Undo,
                contentDescription = null,
                tint = colors.accent,
                modifier = Modifier.size(14.dp),
            )
            Text(
                stringResource(R.string.face_excluded_undo),
                color = colors.fgPrimary,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

/** Decode width for a face tile. The crop keeps only the face region, so the source is decoded above
 *  the tile's pixel size to leave the cropped face sharp (mirrors the People rail's tile). */
private const val EXCLUDED_FACE_TILE_PX = 320
