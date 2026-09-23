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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import coil.request.ImageRequest
import eu.akoos.photos.R
import eu.akoos.photos.domain.entity.GalleryItem
import eu.akoos.photos.presentation.common.PrimaryButton
import eu.akoos.photos.presentation.common.SecondaryButton
import eu.akoos.photos.presentation.gallery.FaceCropTransformation
import eu.akoos.photos.presentation.gallery.LocalThumbnailUrls
import eu.akoos.photos.presentation.theme.AppColors
import eu.akoos.photos.presentation.theme.SheetBg
import eu.akoos.photos.presentation.theme.pillShape
import eu.akoos.photos.presentation.viewer.DetailsPeopleRow

/**
 * "Add to person" for a selection of photos, face first. A selection of exactly one photo holding
 * exactly one face opens straight on "Who is this?" with that face ([AddToPersonFaces.loneFace]). Any
 * other selection first asks for the faces: one row per photo shows the photo and the faces found on it
 * as round crops, a face already a named person's with that name under it, and a tap chooses the face
 * that is meant, one per photo, with a second tap clearing it. A face starts chosen only where that is
 * safe ([AddToPersonFaces.preselect]). A photo with no face says so and offers a face search for that
 * photo alone, so a large selection is never scanned as a whole.
 *
 * "Next" hands the chosen faces and the photos left without one to [NameOrMergeSheet], the same drawer a
 * nameless face opens from the photo details: a person picked, or a new one typed while a face is chosen,
 * takes the faces and the photos together. [onDone] follows once that lands, for the caller to clear its
 * selection; closing either step closes the whole flow. Owns its view models, so every surface offering
 * "Add to person" gets the same flow by handing over the selected photos.
 */
@Composable
internal fun AddToPersonFaceSheet(
    photos: List<GalleryItem>,
    onDone: () -> Unit,
    onDismiss: () -> Unit,
) {
    val viewModel: AddToPersonFaceViewModel = hiltViewModel()
    val keys = remember(photos) { photos.map { it.stableId } }
    val request = remember(keys) { AddToPersonFaceRequest(photos) }
    LaunchedEffect(request) { viewModel.load(request) }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    // The view model outlives one opening, so a state left by an earlier one reads as still loading.
    val ready = state.request === request && !state.loading
    val singlePhoto = remember(keys) { keys.toSet().size == 1 }
    // Read only when the name step is handed its faces, so the flow does not recompose on every
    // thumbnail decrypt behind it.
    val thumbnailUrls = LocalThumbnailUrls.current
    var subject by remember(request) { mutableStateOf<NameOrMergeSubject.Faces?>(null) }
    val lone = ready && AddToPersonFaces.loneFace(state.rows) != null
    LaunchedEffect(request, lone) {
        if (lone && subject == null) {
            subject = AddToPersonFaces.loneFaceSubject(state.rows) { photoSource(it, thumbnailUrls.value) }
        }
    }
    val nameSubject = subject
    when {
        nameSubject != null -> NameOrMergeSheet(
            subject = nameSubject,
            onDismiss = onDismiss,
            onDone = { onDone() },
        )
        // One photo reads at once, so its step waits for the read, and for a lone face's hand-over, rather
        // than opening a sheet that the name step would replace straight away.
        singlePhoto && (!ready || lone) -> Unit
        else -> ChooseFacesSheet(
            rows = if (ready) state.rows else emptyList(),
            ready = ready,
            onFaceClick = viewModel::toggle,
            onFindFaces = viewModel::findFaces,
            onNext = {
                subject = AddToPersonFaces.nameSubject(state.rows) { photoSource(it, thumbnailUrls.value) }
            },
            onDismiss = onDismiss,
        )
    }
}

/** The face step: "Choose the faces" over one row per photo, then Cancel and Next. Next waits for the
 *  rows and for any face search still running, so a search's result is never left behind. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChooseFacesSheet(
    rows: List<AddToPersonPhotoRow>,
    ready: Boolean,
    onFaceClick: (photoKey: String, faceId: String) -> Unit,
    onFindFaces: (photoKey: String) -> Unit,
    onNext: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = AppColors.current
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = SheetBg,
        scrimColor = Color.Black.copy(alpha = 0.5f),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp)
                .padding(top = 4.dp, bottom = 16.dp),
        ) {
            Text(
                stringResource(R.string.add_to_person_choose_faces),
                color = colors.fgPrimary,
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(bottom = 12.dp),
            )
            if (!ready) {
                Box(
                    modifier = Modifier.fillMaxWidth().height(LoadingHeight),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(color = colors.accent)
                }
            } else {
                LazyColumn(
                    // Sized to its rows up to the room left, so a few photos keep the sheet compact.
                    modifier = Modifier.fillMaxWidth().weight(1f, fill = false),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(rows, key = { it.photoKey }) { row ->
                        PhotoFacesRow(
                            row = row,
                            onFaceClick = { faceId -> onFaceClick(row.photoKey, faceId) },
                            onFindFaces = { onFindFaces(row.photoKey) },
                        )
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                SecondaryButton(stringResource(R.string.cancel), onDismiss, modifier = Modifier.weight(1f))
                PrimaryButton(
                    label = stringResource(R.string.metadata_editor_next),
                    onClick = onNext,
                    modifier = Modifier.weight(1f),
                    enabled = ready && rows.none { it.findingFaces },
                )
            }
        }
    }
}

/** One selected photo: its thumbnail, then its faces to choose from, or a note that none was found
 *  beside a face search for this photo. */
@Composable
private fun PhotoFacesRow(
    row: AddToPersonPhotoRow,
    onFaceClick: (String) -> Unit,
    onFindFaces: () -> Unit,
) {
    val colors = AppColors.current
    val source = photoSource(row.item, LocalThumbnailUrls.current.value)
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PhotoThumb(source)
        Spacer(Modifier.width(12.dp))
        if (row.faces.isNotEmpty()) {
            LazyRow(
                modifier = Modifier.weight(1f).selectableGroup(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(row.faces, key = { it.faceId }) { face ->
                    FaceChoice(
                        face = face,
                        source = source,
                        chosen = face.faceId == row.chosenFaceId,
                        onClick = { onFaceClick(face.faceId) },
                    )
                }
            }
        } else {
            Text(
                stringResource(R.string.add_to_person_no_face),
                color = colors.fgMute,
                fontSize = 13.sp,
                modifier = Modifier.weight(1f).padding(end = 8.dp),
            )
            when {
                row.findingFaces -> CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                    color = colors.accent,
                )
                row.canFindFaces -> FindFacesPill(onClick = onFindFaces)
            }
        }
    }
}

/** The image a row's thumbnail and face crops read, the one the viewer loads for the photo: a device
 *  photo's own file, or a cloud-only photo's decrypted thumbnail from [thumbnailUrls] (its listed
 *  thumbnail until that is ready). */
private fun photoSource(item: GalleryItem, thumbnailUrls: Map<String, String>): Any? = when (item) {
    is GalleryItem.LocalOnly -> item.local.uri
    is GalleryItem.Synced -> item.local.uri
    is GalleryItem.CloudOnly -> thumbnailUrls[item.cloud.linkId] ?: item.cloud.thumbnailUrl
}

@Composable
private fun PhotoThumb(source: Any?) {
    val colors = AppColors.current
    Box(
        modifier = Modifier
            .size(ThumbSize)
            .clip(RoundedCornerShape(10.dp))
            .background(colors.bg2),
        contentAlignment = Alignment.Center,
    ) {
        if (source == null) {
            Icon(Icons.Default.Photo, contentDescription = null, tint = colors.fgMute, modifier = Modifier.size(18.dp))
        } else {
            val context = LocalContext.current
            val request = remember(source) {
                ImageRequest.Builder(context)
                    .data(source)
                    .size(THUMB_PX)
                    .crossfade(false)
                    .build()
            }
            AsyncImage(
                model = request,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/** One face to choose: its round crop, ringed and ticked in the accent while chosen, with the name of the
 *  named person it belongs to under it. A face whose box leaves nothing to crop shows the neutral person
 *  glyph, as the viewer's People box does. */
@Composable
private fun FaceChoice(
    face: AddToPersonFaceOption,
    source: Any?,
    chosen: Boolean,
    onClick: () -> Unit,
) {
    val colors = AppColors.current
    val box = remember(face.box) { DetailsPeopleRow.clampedFaceBox(face.box) }
    Column(
        modifier = Modifier.width(FaceSlotWidth),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(modifier = Modifier.size(FaceSize)) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(CircleShape)
                    .background(colors.bg2)
                    .then(if (chosen) Modifier.border(2.dp, colors.accent, CircleShape) else Modifier)
                    .selectable(selected = chosen, role = Role.RadioButton, onClick = onClick),
                contentAlignment = Alignment.Center,
            ) {
                if (source != null && box != null) {
                    val context = LocalContext.current
                    val decodePx = DetailsPeopleRow.decodePx(box, with(LocalDensity.current) { FaceSize.toPx() })
                    val request = remember(source, box, decodePx) {
                        ImageRequest.Builder(context)
                            .data(source)
                            .size(decodePx)
                            .crossfade(false)
                            .transformations(FaceCropTransformation(box))
                            .build()
                    }
                    AsyncImage(
                        model = request,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Icon(
                        Icons.Default.Person,
                        contentDescription = null,
                        tint = colors.fgMute,
                        modifier = Modifier.size(FaceSize * 0.6f),
                    )
                }
            }
            if (chosen) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .size(CheckSize)
                        .clip(CircleShape)
                        .background(colors.accent)
                        .border(1.5.dp, SheetBg, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Default.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(12.dp))
                }
            }
        }
        face.personName?.let { name ->
            Text(
                name,
                color = colors.fgMute,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
            )
        }
    }
}

/** The per-photo face search, a small pill in the style of the other inline row actions. */
@Composable
private fun FindFacesPill(onClick: () -> Unit) {
    val colors = AppColors.current
    Row(
        modifier = Modifier
            .clip(pillShape)
            .background(colors.pillBg, pillShape)
            .border(0.5.dp, colors.pillBorder, pillShape)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(Icons.Default.Face, contentDescription = null, tint = colors.accent, modifier = Modifier.size(14.dp))
        Text(
            stringResource(R.string.add_to_person_find_faces),
            color = colors.fgPrimary,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

private val ThumbSize = 56.dp

/** Decode width for the row's photo thumbnail, a little above its pixel size for a sharp crop. */
private const val THUMB_PX = 256

private val FaceSize = 48.dp

/** A face's column, a little wider than its crop so a short name under it reads in full. */
private val FaceSlotWidth = 60.dp

private val CheckSize = 18.dp

private val LoadingHeight = 120.dp
