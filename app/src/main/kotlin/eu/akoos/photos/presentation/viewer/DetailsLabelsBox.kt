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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import eu.akoos.photos.R
import eu.akoos.photos.domain.entity.GalleryItem
import eu.akoos.photos.presentation.theme.Accent
import eu.akoos.photos.presentation.theme.CardBg
import eu.akoos.photos.presentation.theme.FgDim
import eu.akoos.photos.presentation.theme.FgMute
import eu.akoos.photos.presentation.theme.FgPrimary
import eu.akoos.photos.presentation.theme.Line2
import eu.akoos.photos.util.LabelNormalizer

/**
 * The details panel's Labels section for [item], fed from [viewModel]. The title opens the Labels
 * settings page through [onOpenSettings]; the Add label chip hands over to the shared Add labels sheet
 * through [onAddLabels], and is left out when that is null.
 */
@Composable
internal fun DetailsLabels(
    viewModel: PhotoViewerViewModel,
    item: GalleryItem,
    onAddLabels: (() -> Unit)?,
    onOpenSettings: () -> Unit,
) {
    LaunchedEffect(item) { viewModel.loadLabels(item) }
    val labels by viewModel.currentLabels.collectAsStateWithLifecycle()
    val showNote by viewModel.showLabelsNote.collectAsStateWithLifecycle()
    val locked by viewModel.labelsLocked.collectAsStateWithLifecycle()
    LabelsChips(
        labels = labels,
        showNote = showNote,
        locked = locked,
        onAddLabels = onAddLabels,
        onOpenSettings = onOpenSettings,
        onRemove = { label -> viewModel.removeLabel(item, label) },
    )
}

/**
 * The photo's labels as chips that each come off with their x, then one Add label chip, greyed out
 * once the photo carries [LabelNormalizer.MAX_LABELS]. While the labels are [locked] (saved to or restored
 * from Drive) none of them can be taken off or added to, and the note under them says why.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LabelsChips(
    labels: List<String>,
    showNote: Boolean,
    locked: Boolean,
    onAddLabels: (() -> Unit)?,
    onOpenSettings: () -> Unit,
    onRemove: (String) -> Unit,
) {
    Column {
        Row(
            modifier = Modifier
                .padding(bottom = 6.dp)
                .clip(RoundedCornerShape(6.dp))
                .clickable(onClick = onOpenSettings)
                .padding(vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.label_section_title).uppercase(),
                color = FgMute, fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.8.sp,
            )
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = FgMute,
                modifier = Modifier.size(16.dp),
            )
        }
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            labels.forEach { label ->
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .background(Accent.copy(alpha = 0.18f))
                        .border(0.5.dp, Accent, RoundedCornerShape(20.dp))
                        .padding(start = 12.dp, end = 6.dp, top = 5.dp, bottom = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(label, color = FgPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.width(4.dp))
                    Icon(
                        Icons.Default.Close,
                        contentDescription = stringResource(R.string.label_remove_cd, label),
                        tint = FgDim,
                        modifier = Modifier
                            .size(22.dp)
                            .alpha(if (locked) 0.4f else 1f)
                            .clip(CircleShape)
                            .clickable(enabled = !locked) { onRemove(label) }
                            .padding(4.dp),
                    )
                }
            }
            if (onAddLabels != null) {
                val canAdd = labels.size < LabelNormalizer.MAX_LABELS && !locked
                Row(
                    modifier = Modifier
                        .alpha(if (canAdd) 1f else 0.4f)
                        .clip(RoundedCornerShape(20.dp))
                        .background(CardBg)
                        .border(0.5.dp, Line2, RoundedCornerShape(20.dp))
                        .clickable(enabled = canAdd, onClick = onAddLabels)
                        .padding(start = 10.dp, end = 12.dp, top = 7.dp, bottom = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, tint = Accent, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(
                        stringResource(R.string.label_add_chip),
                        color = Accent, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                    )
                }
            }
        }
        if (locked) {
            Text(
                stringResource(R.string.label_locked),
                color = FgMute, fontSize = 11.5.sp,
                modifier = Modifier.padding(top = 8.dp),
            )
        } else if (showNote) {
            Text(
                stringResource(R.string.label_first_use_note),
                color = FgMute, fontSize = 11.5.sp,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}
