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

package eu.akoos.photos.presentation.viewer

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Icon
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import eu.akoos.photos.R
import eu.akoos.photos.domain.entity.GalleryItem
import eu.akoos.photos.presentation.common.AddLabelsViewModel
import eu.akoos.photos.presentation.common.LabelsEditor
import eu.akoos.photos.presentation.theme.Accent
import eu.akoos.photos.presentation.theme.ErrorColor
import eu.akoos.photos.presentation.theme.FgMute
import eu.akoos.photos.presentation.theme.FgPrimary
import eu.akoos.photos.util.LabelNormalizer

/**
 * The details sheet's Labels section for [item], fed from [viewModel]: the photo's labels as chips, with
 * Edit and Add opening the Labels page through [onEdit]. While the labels are locked (saved to or restored
 * from Drive) neither shows, and the note under the chips says why.
 */
@Composable
internal fun DetailsLabels(
    viewModel: PhotoViewerViewModel,
    item: GalleryItem,
    onEdit: () -> Unit,
) {
    LaunchedEffect(item) { viewModel.loadLabels(item) }
    val labels by viewModel.currentLabels.collectAsStateWithLifecycle()
    val showNote by viewModel.showLabelsNote.collectAsStateWithLifecycle()
    val locked by viewModel.labelsLocked.collectAsStateWithLifecycle()
    DetailsChipSection(
        title = stringResource(R.string.label_section_title),
        chips = labels,
        onEdit = if (locked) null else onEdit,
        addEnabled = labels.size < LabelNormalizer.MAX_LABELS,
    ) {
        val note = when {
            locked -> R.string.label_locked
            showNote -> R.string.label_first_use_note
            else -> null
        }
        note?.let {
            Text(stringResource(it), color = FgMute, fontSize = 11.5.sp, modifier = Modifier.padding(top = 8.dp))
        }
    }
}

/**
 * The Labels page: the shared pill field over the labels already in use, opened on what the photo carries
 * now. Done saves the whole list and steps back; the section on the main page then reads it again.
 */
@Composable
internal fun DetailsLabelsPage(
    viewModel: PhotoViewerViewModel,
    item: GalleryItem,
    onClose: () -> Unit,
) {
    val labels: AddLabelsViewModel = hiltViewModel()
    var initial by remember(item) { mutableStateOf<List<String>?>(null) }
    var failed by remember { mutableStateOf(false) }
    LaunchedEffect(item) { initial = labels.labelsOf(item) ?: emptyList() }
    val locked by labels.locked.collectAsStateWithLifecycle()
    val start = initial
    if (start == null) {
        DetailsPageHeader(stringResource(R.string.label_section_title), onBack = onClose)
        return
    }
    LabelsEditor(
        initial = start,
        editing = true,
        viewModel = labels,
        onConfirm = { picked ->
            failed = false
            labels.replace(item, picked) { ok ->
                viewModel.loadLabels(item)
                if (ok) onClose() else failed = true
            }
        },
        header = { canConfirm, confirm ->
            DetailsPageHeader(
                title = stringResource(R.string.label_section_title),
                onBack = onClose,
                action = stringResource(R.string.metadata_editor_done),
                actionEnabled = canConfirm && !locked,
                onAction = confirm,
            )
        },
        footer = { _, _ ->
            if (locked) Text(stringResource(R.string.label_locked), color = FgMute, fontSize = 12.sp)
            if (failed) Text(stringResource(R.string.label_bulk_failed), color = ErrorColor, fontSize = 12.sp)
        },
    )
}

/**
 * A titled row of chips on the details sheet's main page (labels, categories): the title with Edit on the
 * right, the chips, and a dashed Add chip after them. A null [onEdit] leaves both actions out.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun DetailsChipSection(
    title: String,
    chips: List<String>,
    onEdit: (() -> Unit)?,
    addEnabled: Boolean = true,
    footer: @Composable () -> Unit = {},
) {
    Column {
        Row(modifier = Modifier.fillMaxWidth().height(28.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                title.uppercase(),
                color = FgMute, fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.8.sp,
                modifier = Modifier.weight(1f),
            )
            if (onEdit != null) {
                Text(
                    stringResource(R.string.details_edit),
                    color = Accent, fontSize = 12.5.sp, fontWeight = FontWeight.Medium,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable(onClick = onEdit)
                        .padding(horizontal = 6.dp, vertical = 3.dp),
                )
            }
        }
        if (chips.isNotEmpty() || onEdit != null) {
            FlowRow(
                modifier = Modifier.padding(top = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                chips.forEach { chip ->
                    Text(
                        chip,
                        color = FgPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .clip(RoundedCornerShape(20.dp))
                            .background(Accent.copy(alpha = 0.18f))
                            .border(0.5.dp, Accent, RoundedCornerShape(20.dp))
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                }
                if (onEdit != null && addEnabled) {
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(20.dp))
                            .dashedOutline(Accent.copy(alpha = 0.7f), 20.dp)
                            .clickable(onClick = onEdit)
                            .padding(start = 10.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null, tint = Accent, modifier = Modifier.size(15.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(R.string.details_add), color = Accent, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    }
                }
            }
        }
        footer()
    }
}

/** A dashed rounded outline, the mark of a chip that adds rather than one that is set. */
private fun Modifier.dashedOutline(color: Color, radius: Dp): Modifier = drawBehind {
    val stroke = Stroke(
        width = 1.dp.toPx(),
        pathEffect = PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 4.dp.toPx())),
    )
    val inset = stroke.width / 2
    drawRoundRect(
        color = color,
        topLeft = androidx.compose.ui.geometry.Offset(inset, inset),
        size = androidx.compose.ui.geometry.Size(size.width - stroke.width, size.height - stroke.width),
        cornerRadius = CornerRadius(radius.toPx()),
        style = stroke,
    )
}
