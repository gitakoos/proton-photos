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

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TimeInput
import androidx.compose.material3.TimePickerDefaults
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import eu.akoos.photos.R
import eu.akoos.photos.domain.entity.GalleryItem
import eu.akoos.photos.presentation.common.cardFill
import eu.akoos.photos.presentation.lock.LocalAppLock
import eu.akoos.photos.presentation.lock.runWhenOpen
import eu.akoos.photos.presentation.metadata.DateSource
import eu.akoos.photos.presentation.metadata.InlineLocationPicker
import eu.akoos.photos.presentation.metadata.LockNote
import eu.akoos.photos.presentation.metadata.MetadataEditorEvent
import eu.akoos.photos.presentation.metadata.MetadataEditorViewModel
import eu.akoos.photos.presentation.metadata.PickerCenter
import eu.akoos.photos.presentation.metadata.combineDateTime
import eu.akoos.photos.presentation.metadata.toUtcMidnight
import eu.akoos.photos.presentation.theme.Accent
import eu.akoos.photos.presentation.theme.CardBg
import eu.akoos.photos.presentation.theme.ErrorColor
import eu.akoos.photos.presentation.theme.FgDim
import eu.akoos.photos.presentation.theme.FgMute
import eu.akoos.photos.presentation.theme.FgPrimary
import eu.akoos.photos.presentation.theme.Line2
import eu.akoos.photos.presentation.util.LocalIs24Hour
import eu.akoos.photos.presentation.util.formatDateTimeMinute
import java.time.Instant
import java.time.ZoneId

/**
 * The metadata editor behind a details page, bound to [item] alone: it writes the date or the place the
 * same way the full editor does, asks the OS for consent on a file the app does not own, and reports back
 * through [onFinished] once the write landed or [onFailed] with the message to show when it did not.
 */
@Composable
private fun detailsEditor(
    item: GalleryItem,
    isReadOnlyAlbum: Boolean,
    onFinished: () -> Unit,
    onFailed: (String) -> Unit,
): MetadataEditorViewModel {
    val key = when (item) {
        is GalleryItem.LocalOnly -> item.local.uri
        is GalleryItem.Synced -> item.local.uri
        is GalleryItem.CloudOnly -> item.cloud.linkId
    }
    val viewModel: MetadataEditorViewModel = hiltViewModel(key = "details-editor-$key")
    LaunchedEffect(viewModel) { viewModel.load(listOf(item), isReadOnlyAlbum) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    // The system consent opens above the app lock, so it waits for the unlock, as the full editor's does.
    val appLock = LocalAppLock.current
    val consent = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) viewModel.onPermissionGranted() else viewModel.clearPendingWriteIntent()
    }
    LaunchedEffect(state.pendingWriteIntent) {
        val sender = state.pendingWriteIntent ?: return@LaunchedEffect
        appLock.runWhenOpen { consent.launch(IntentSenderRequest.Builder(sender).build()) }
    }
    val context = LocalContext.current
    val finished by rememberUpdatedState(onFinished)
    val failed by rememberUpdatedState(onFailed)
    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                MetadataEditorEvent.Finished -> finished()
                MetadataEditorEvent.Failed -> failed(context.getString(R.string.metadata_editor_write_failed))
                is MetadataEditorEvent.PartlySaved -> failed(
                    context.getString(R.string.metadata_editor_saved_partial, event.saved, event.failed),
                )
                MetadataEditorEvent.Saved -> Unit
            }
        }
    }
    return viewModel
}

/**
 * The Date and time page: the calendar in the page, the time as one field under it, the date the file name
 * records offered when it differs. Done writes the picked moment and steps back; a cloud photo says first
 * that saving it uploads a corrected copy.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DetailsDatePage(
    item: GalleryItem,
    isReadOnlyAlbum: Boolean,
    onBack: () -> Unit,
    onSaved: () -> Unit,
) {
    var error by remember { mutableStateOf<String?>(null) }
    val viewModel = detailsEditor(item, isReadOnlyAlbum, onFinished = onSaved, onFailed = { error = it })
    val state by viewModel.state.collectAsStateWithLifecycle()
    var pickedDay by remember(state.loaded) { mutableStateOf(if (state.loaded) toUtcMidnight(state.captureDateMs) else null) }
    val seed = remember(state.loaded, state.captureDateMs) {
        Instant.ofEpochMilli(state.captureDateMs).atZone(ZoneId.systemDefault())
    }
    val timeState = key(state.loaded) {
        rememberTimePickerState(initialHour = seed.hour, initialMinute = seed.minute, is24Hour = LocalIs24Hour.current)
    }
    var timeOpen by remember { mutableStateOf(false) }
    val chosenMs = pickedDay?.let { combineDateTime(it, timeState.hour, timeState.minute) }
    val editable = state.loaded && state.dateLock == null
    DetailsPageFrame {
        DetailsPageHeader(
            title = stringResource(R.string.viewer_meta_section_datetime),
            onBack = onBack,
            action = if (editable) stringResource(R.string.metadata_editor_done) else null,
            // The page edits to the minute, so a moment that only differs in its seconds is no change.
            actionEnabled = chosenMs != null && chosenMs / 60_000 != state.captureDateMs / 60_000,
            busy = state.isSaving,
            onAction = { chosenMs?.let { error = null; viewModel.commitDate(it) } },
        )
        when {
            !state.loaded -> DetailsPageSpinner()
            state.dateLock != null -> state.dateLock?.let { LockNote(it) }
            else -> {
                Text(
                    remember(chosenMs) { chosenMs?.let { formatDateTimeMinute(it) }.orEmpty() },
                    color = FgPrimary, fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center, maxLines = 1,
                    modifier = Modifier.fillMaxWidth(),
                )
                // The calendar is 360 wide at the least, wider than a card between the sheet's margins on a
                // narrow phone, so it runs to the sheet's edges and keeps its own inner margin.
                Box(modifier = Modifier.fullBleed(20.dp)) {
                    val dateState = key(state.loaded) { rememberDatePickerState(initialSelectedDateMillis = pickedDay) }
                    LaunchedEffect(dateState.selectedDateMillis) { dateState.selectedDateMillis?.let { pickedDay = it } }
                    DatePicker(
                        state = dateState,
                        title = null,
                        headline = null,
                        showModeToggle = false,
                        colors = DatePickerDefaults.colors(
                            containerColor = Color.Transparent,
                            navigationContentColor = FgPrimary,
                            subheadContentColor = FgDim,
                            weekdayContentColor = FgMute,
                            yearContentColor = FgPrimary,
                            currentYearContentColor = Accent,
                            selectedYearContainerColor = Accent,
                            dayContentColor = FgPrimary,
                            selectedDayContainerColor = Accent,
                            todayContentColor = Accent,
                            todayDateBorderColor = Accent,
                        ),
                    )
                }
                DetailsCard {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp)
                            .clickable { timeOpen = !timeOpen }
                            .padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(stringResource(R.string.details_time), color = FgPrimary, fontSize = 14.sp, modifier = Modifier.weight(1f))
                        Text(
                            remember(chosenMs) {
                                chosenMs?.let { eu.akoos.photos.presentation.util.formatTimeOfDay(it, java.util.Locale.getDefault(), timeState.is24hour) }.orEmpty()
                            },
                            color = FgPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(FgPrimary.copy(alpha = 0.08f))
                                .padding(horizontal = 10.dp, vertical = 3.dp),
                        )
                    }
                    if (timeOpen) {
                        DetailsDivider()
                        Box(modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
                            TimeInput(
                                state = timeState,
                                colors = TimePickerDefaults.colors(
                                    timeSelectorSelectedContainerColor = Accent.copy(alpha = 0.22f),
                                    timeSelectorUnselectedContainerColor = cardFill(CardBg),
                                    timeSelectorSelectedContentColor = FgPrimary,
                                    timeSelectorUnselectedContentColor = FgPrimary,
                                    periodSelectorBorderColor = Line2,
                                    periodSelectorSelectedContainerColor = Accent.copy(alpha = 0.22f),
                                    periodSelectorSelectedContentColor = FgPrimary,
                                    periodSelectorUnselectedContentColor = FgDim,
                                ),
                            )
                        }
                    }
                }
                val nameDateMs = state.filenameDateSingleMs
                if (nameDateMs != null && nameDateMs != state.captureDateMs) {
                    DetailsCard {
                        Row(
                            modifier = Modifier.fillMaxWidth().height(52.dp).padding(start = 16.dp, end = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                stringResource(R.string.metadata_editor_filename_single, remember(nameDateMs) { formatDateTimeMinute(nameDateMs) }),
                                color = FgPrimary, fontSize = 13.sp, maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                stringResource(R.string.metadata_editor_filename_use),
                                color = Accent, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable(enabled = !state.isSaving) { error = null; viewModel.commitDate(nameDateMs) }
                                    .padding(horizontal = 8.dp, vertical = 6.dp),
                            )
                        }
                    }
                }
                if (state.dateSource == DateSource.CLOUD) {
                    Text(stringResource(R.string.metadata_editor_cloud_replace_note), color = FgMute, fontSize = 12.sp)
                }
                error?.let { Text(it, color = ErrorColor, fontSize = 12.sp) }
            }
        }
    }
}

/**
 * The Location page: the map with the pin fixed in the middle and the search over it, the place under the
 * pin named on the map. Done writes that spot and steps back; Remove location takes the fix off.
 */
@Composable
internal fun DetailsPlacePage(
    item: GalleryItem,
    isReadOnlyAlbum: Boolean,
    fallbackLatitude: Double?,
    fallbackLongitude: Double?,
    onBack: () -> Unit,
    onSaved: () -> Unit,
) {
    var error by remember { mutableStateOf<String?>(null) }
    val viewModel = detailsEditor(item, isReadOnlyAlbum, onFinished = onSaved, onFailed = { error = it })
    val state by viewModel.state.collectAsStateWithLifecycle()
    val center = remember { PickerCenter() }
    val editable = state.loaded && state.placeLock == null
    DetailsPageFrame(scroll = false) {
        DetailsPageHeader(
            title = stringResource(R.string.viewer_meta_section_location),
            onBack = onBack,
            action = if (editable) stringResource(R.string.metadata_editor_done) else null,
            busy = state.isSaving,
            onAction = { center.center()?.let { (lat, lon) -> error = null; viewModel.commitPoint(lat, lon) } },
        )
        when {
            !state.loaded -> DetailsPageSpinner()
            state.placeLock != null -> state.placeLock?.let { LockNote(it) }
            else -> {
                InlineLocationPicker(
                    initialLatitude = state.latitude ?: fallbackLatitude,
                    initialLongitude = state.longitude ?: fallbackLongitude,
                    center = center,
                    hint = stringResource(R.string.details_place_hint),
                )
                if (state.hasLocation) {
                    DetailsDangerButton(
                        label = stringResource(R.string.metadata_editor_place_remove),
                        enabled = !state.isSaving,
                        onClick = { error = null; viewModel.commitRemoveLocation() },
                    )
                }
                if (state.dateSource == DateSource.CLOUD) {
                    Text(stringResource(R.string.metadata_editor_cloud_replace_note), color = FgMute, fontSize = 12.sp)
                }
                error?.let { Text(it, color = ErrorColor, fontSize = 12.sp) }
            }
        }
    }
}

/** Lets a child reach [margin] past each side of its parent's padding, to the sheet's edges. */
private fun Modifier.fullBleed(margin: androidx.compose.ui.unit.Dp): Modifier = layout { measurable, constraints ->
    val extra = (margin * 2).roundToPx()
    val placeable = measurable.measure(
        constraints.copy(minWidth = constraints.maxWidth + extra, maxWidth = constraints.maxWidth + extra),
    )
    layout(constraints.maxWidth, placeable.height) { placeable.place(-extra / 2, 0) }
}

/** The page's place holder while the editor reads the photo. */
@Composable
private fun DetailsPageSpinner() {
    Box(modifier = Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = Accent, strokeWidth = 2.dp, modifier = Modifier.size(22.dp))
    }
}
