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

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.akoos.photos.R
import eu.akoos.photos.data.label.LabelCount
import eu.akoos.photos.data.label.LabelSavedCopy
import eu.akoos.photos.presentation.common.PrimaryButton
import eu.akoos.photos.presentation.common.SecondaryButton
import eu.akoos.photos.presentation.theme.Accent
import eu.akoos.photos.presentation.theme.AppColors
import eu.akoos.photos.presentation.theme.FgMute
import eu.akoos.photos.presentation.theme.FgPrimary
import eu.akoos.photos.presentation.theme.PillBg
import eu.akoos.photos.presentation.theme.PillBorder
import eu.akoos.photos.presentation.theme.SheetBg
import eu.akoos.photos.presentation.util.LocalIs24Hour
import eu.akoos.photos.presentation.util.appLocale
import eu.akoos.photos.presentation.util.formatDateTimeMinute
import kotlinx.coroutines.launch

/**
 * The drawer Restore from Drive opens: the saved copies of the labels, one per install that saved, newest first,
 * each with when it was saved. The picked copy opens downwards onto the labels it holds and how many photos each
 * is on, so the user sees what comes back before it does; the newest copy is picked up front. Restore brings the
 * picked copy back. One drawer holds every stage, so the list replaces the spinner in place.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LabelRestoreDrawer(
    sheet: LabelRestoreSheet,
    onSelect: (String) -> Unit,
    onRestore: () -> Unit,
    onClose: () -> Unit,
) {
    val colors = AppColors.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    var listAllOf by remember { mutableStateOf<LabelSavedCopy?>(null) }
    // Slide the drawer away before the action lands, as the app's other action sheets do.
    fun close(action: () -> Unit) {
        scope.launch { sheetState.hide() }.invokeOnCompletion { action() }
    }

    listAllOf?.let { AllLabelsSheet(it, onClose = { listAllOf = null }) }

    ModalBottomSheet(
        onDismissRequest = onClose,
        sheetState = sheetState,
        containerColor = SheetBg,
        scrimColor = Color.Black.copy(alpha = 0.5f),
    ) {
        // Height-capped, so a long label list on a short screen never pushes the buttons off it.
        val maxSheetHeight = (LocalConfiguration.current.screenHeightDp * 0.85f).dp
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = maxSheetHeight)
                .navigationBarsPadding()
                .padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                stringResource(R.string.settings_ml_drive_restore),
                color = colors.fgPrimary,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
            )
            when (sheet) {
                is LabelRestoreSheet.Pick -> {
                    Text(stringResource(R.string.label_backup_restore_note), color = colors.fgDim, fontSize = 13.sp)
                    LazyColumn(
                        modifier = Modifier.weight(1f, fill = false),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        items(sheet.copies, key = { it.deviceId }) { copy ->
                            SavedCopyCard(
                                copy = copy,
                                picked = copy.deviceId == sheet.selectedId,
                                onPick = { onSelect(copy.deviceId) },
                                onShowAll = { listAllOf = copy },
                            )
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        SecondaryButton(
                            label = stringResource(R.string.cancel),
                            onClick = { close(onClose) },
                            modifier = Modifier.weight(1f),
                        )
                        PrimaryButton(
                            label = stringResource(R.string.settings_ml_drive_restore_confirm),
                            onClick = { close(onRestore) },
                            modifier = Modifier.weight(1f),
                            enabled = sheet.selectedId != null,
                        )
                    }
                }
                LabelRestoreSheet.Empty, LabelRestoreSheet.Failed -> {
                    Text(
                        stringResource(
                            if (sheet == LabelRestoreSheet.Empty) {
                                R.string.label_backup_restore_empty
                            } else {
                                R.string.settings_ml_drive_restore_list_failed
                            },
                        ),
                        color = colors.fgDim,
                        fontSize = 14.sp,
                    )
                    SecondaryButton(
                        label = stringResource(R.string.close),
                        onClick = { close(onClose) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                else -> Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = colors.accent)
                    Text(stringResource(R.string.label_backup_step_looking), color = colors.fgDim, fontSize = 14.sp)
                }
            }
        }
    }
}

/**
 * One saved copy: which install saved it and when. Picking it opens the card onto its first labels
 * ([labelCopyPreview]), each with the number of photos it is on, and a Show all row for a copy that has more.
 */
@Composable
private fun SavedCopyCard(copy: LabelSavedCopy, picked: Boolean, onPick: () -> Unit, onShowAll: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    val locale = appLocale()
    val is24Hour = LocalIs24Hour.current
    val savedAt = remember(copy.savedAtMillis, locale, is24Hour) { formatDateTimeMinute(copy.savedAtMillis, locale, is24Hour) }
    val name = when {
        copy.isThisDevice -> stringResource(R.string.settings_ml_drive_this_device)
        copy.deviceLabel.isNotBlank() -> copy.deviceLabel
        else -> stringResource(R.string.settings_ml_drive_another_device)
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(PillBg, shape)
            .border(0.5.dp, if (picked) Accent else PillBorder, shape)
            .animateContentSize(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onPick)
                .semantics { selected = picked }
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Icon(
                if (copy.isThisDevice) Icons.Default.PhoneAndroid else Icons.Default.Devices,
                contentDescription = null,
                tint = Accent,
                modifier = Modifier.size(22.dp),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    name,
                    color = FgPrimary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(stringResource(R.string.settings_ml_drive_saved_at, savedAt), color = FgMute, fontSize = 12.sp)
            }
            if (picked) {
                Icon(Icons.Default.Check, contentDescription = null, tint = Accent, modifier = Modifier.size(18.dp))
            }
        }
        if (picked) {
            val preview = remember(copy.labels) { labelCopyPreview(copy.labels) }
            preview.shown.forEach { entry -> CopyLabelRow(entry, Modifier.padding(start = 52.dp, end = 16.dp)) }
            if (preview.hasMore) {
                Text(
                    stringResource(R.string.label_backup_show_all, preview.total),
                    color = Accent,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onShowAll)
                        .padding(start = 52.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
                )
            }
            Spacer(Modifier.size(8.dp))
        }
    }
}

/** One label of a saved copy and how many photos it is on. */
@Composable
private fun CopyLabelRow(entry: LabelCount, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            entry.label,
            color = FgPrimary,
            fontSize = 14.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(12.dp))
        Text(
            if (entry.photos > 0) {
                pluralStringResource(R.plurals.count_photos_plural, entry.photos, entry.photos)
            } else {
                stringResource(R.string.label_no_photos)
            },
            color = FgMute,
            fontSize = 12.5.sp,
        )
    }
}

/**
 * Every label of a saved copy in a tall drawer of its own over the restore drawer, scrolling, for a copy whose
 * labels do not fit the five the card shows.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AllLabelsSheet(copy: LabelSavedCopy, onClose: () -> Unit) {
    val colors = AppColors.current
    val locale = appLocale()
    val is24Hour = LocalIs24Hour.current
    val savedAt = remember(copy.savedAtMillis, locale, is24Hour) { formatDateTimeMinute(copy.savedAtMillis, locale, is24Hour) }
    ModalBottomSheet(
        onDismissRequest = onClose,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = SheetBg,
        scrimColor = Color.Black.copy(alpha = 0.5f),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.92f)
                .navigationBarsPadding()
                .padding(horizontal = 20.dp)
                .padding(top = 4.dp, bottom = 16.dp),
        ) {
            Text(
                stringResource(R.string.label_section_title),
                color = colors.fgPrimary,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                stringResource(R.string.settings_ml_drive_saved_at, savedAt),
                color = colors.fgMute,
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 2.dp, bottom = 12.dp),
            )
            LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f)) {
                items(copy.labels, key = { it.label }) { entry -> CopyLabelRow(entry) }
            }
        }
    }
}
