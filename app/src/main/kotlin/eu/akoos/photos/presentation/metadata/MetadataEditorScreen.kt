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

package eu.akoos.photos.presentation.metadata

import android.app.Activity
import android.net.Uri
import android.text.format.DateFormat
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.LocationOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDefaults
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import eu.akoos.photos.R
import eu.akoos.photos.domain.entity.GalleryItem
import eu.akoos.photos.domain.usecase.ExifAsciiText
import eu.akoos.photos.presentation.common.ConfirmSheet
import eu.akoos.photos.presentation.common.IconBubble
import androidx.activity.compose.BackHandler
import eu.akoos.photos.presentation.common.PrimaryButton
import eu.akoos.photos.presentation.common.ThemedSnackbarHost
import eu.akoos.photos.presentation.common.floatingHeaderContentTopPadding
import eu.akoos.photos.presentation.gallery.FilterChip
import eu.akoos.photos.presentation.gallery.LocalThumbnailUrls
import eu.akoos.photos.presentation.common.FloatingHeader
import eu.akoos.photos.presentation.theme.AppColors
import eu.akoos.photos.util.OfflineGeocoder
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Date
import java.util.Locale

private val cardShape = RoundedCornerShape(14.dp)

/**
 * Metadata editor (issue #36): edit the capture date/time, the place and the descriptive text tags of
 * device photos, backed by [MetadataEditorViewModel]. Opened for a single photo from the viewer, or
 * for a whole multi-select from the grid; the top strip shows every bound photo. With more than one
 * editable photo the fields start unset and a chosen value applies to all of them at once, except the
 * description, which stays a single-photo field. Cloud-only and shared-with-me photos open read-only
 * with an inline note, because Drive refuses an in-place rewrite.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MetadataEditorScreen(
    items: List<GalleryItem>,
    isReadOnlyAlbum: Boolean,
    onBack: () -> Unit,
    viewModel: MetadataEditorViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val colors = AppColors.current
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(items) { viewModel.load(items, isReadOnlyAlbum) }

    // A write against a file the app doesn't own needs one-shot system consent; launch the request
    // and, on approval, retry the queued write. Same recipe the album delete flow uses.
    val writeConsentLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) viewModel.onPermissionGranted()
        else viewModel.clearPendingWriteIntent()
    }
    LaunchedEffect(state.pendingWriteIntent) {
        val sender = state.pendingWriteIntent ?: return@LaunchedEffect
        writeConsentLauncher.launch(IntentSenderRequest.Builder(sender).build())
    }

    val screenContext = LocalContext.current
    val savedMessage = stringResource(R.string.metadata_editor_saved)
    val failedMessage = stringResource(R.string.metadata_editor_write_failed)
    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                MetadataEditorEvent.Saved -> snackbarHostState.showSnackbar(savedMessage)
                is MetadataEditorEvent.PartlySaved -> snackbarHostState.showSnackbar(
                    screenContext.getString(
                        R.string.metadata_editor_saved_partial, event.saved, event.failed,
                    ),
                )
                MetadataEditorEvent.Failed -> snackbarHostState.showSnackbar(failedMessage)
                // Nothing to upload, so the checkmark just leaves.
                MetadataEditorEvent.Finished -> onBack()
            }
        }
    }

    // Back with unsaved edits asks first rather than dropping them: staged cloud edits, or text typed
    // but not yet committed by the checkmark, are what a stray back would otherwise lose.
    var showDiscardConfirm by remember { mutableStateOf(false) }
    val hasUnsavedChanges = state.stagedCloudCount > 0 ||
        state.description.dirty || state.artist.dirty || state.copyright.dirty
    fun handleBack() {
        if (hasUnsavedChanges) showDiscardConfirm = true else onBack()
    }
    BackHandler { handleBack() }

    Box(modifier = Modifier.fillMaxSize().background(colors.pageBg)) {
        val contentTopPad = floatingHeaderContentTopPadding()
        val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

        Column(
            modifier = Modifier
                .fillMaxSize()
                // The text fields sit at the bottom of a single scrolling column, so the keyboard has
                // to shrink the scroll viewport rather than cover it. Insetting before the scroll
                // modifier is what lets a focused field scroll clear of the keyboard.
                .imePadding()
                .verticalScroll(rememberScrollState()),
        ) {
            Spacer(Modifier.height(contentTopPad))
            // A single photo highlights its thumbnail; a bulk set highlights none (the edit is for all).
            MetadataThumbnailStrip(items = items, selectedIndex = if (items.size == 1) 0 else -1)
            Spacer(Modifier.height(22.dp))
            Column(
                modifier = Modifier.padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                // A multi-select (or any list carrying read-only members) gets a count line, so the
                // user sees how many photos the edit lands on and how many are skipped.
                if (state.bulk || (state.editableCount > 0 && state.skippedCount > 0)) {
                    val summary = if (state.skippedCount > 0) {
                        stringResource(
                            R.string.metadata_editor_bulk_summary_skipped,
                            state.editableCount, state.skippedCount,
                        )
                    } else {
                        stringResource(R.string.metadata_editor_bulk_summary, state.editableCount)
                    }
                    Text(summary, color = colors.fgMute, fontSize = 13.sp)
                }
                // A cloud edit waits for the Done checkmark, so a staged change is called out here:
                // leaving by the back arrow instead would drop it.
                if (state.stagedCloudCount > 0) {
                    Text(
                        stringResource(R.string.metadata_editor_cloud_staged_hint),
                        color = colors.accent,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }
                DateField(
                    state = state,
                    onPickDate = viewModel::setDate,
                    onShiftDates = viewModel::shiftDatesTo,
                    onFixDatesFromName = viewModel::fixDatesFromName,
                )
                PlaceField(
                    state = state,
                    onPickCountry = viewModel::pickCountry,
                    onPickCity = viewModel::pickCity,
                    onRemoveLocation = viewModel::removeLocation,
                )
                // The tag labels are the ones the viewer's details sheet shows, so the same EXIF tag
                // reads the same wherever it appears.
                TextTagField(
                    label = stringResource(R.string.viewer_meta_row_description),
                    field = state.description,
                    bulk = state.bulk,
                    isSaving = state.isSaving,
                    // A caption is a sentence, so it wraps instead of scrolling out of sight sideways.
                    maxLines = 3,
                    onValueChange = { viewModel.setText(MetadataTextTag.DESCRIPTION, it) },
                )
                TextTagField(
                    label = stringResource(
                        if (state.bulk) R.string.metadata_editor_bulk_artist_label
                        else R.string.viewer_meta_row_artist,
                    ),
                    field = state.artist,
                    bulk = state.bulk,
                    isSaving = state.isSaving,
                    onValueChange = { viewModel.setText(MetadataTextTag.ARTIST, it) },
                )
                TextTagField(
                    label = stringResource(
                        if (state.bulk) R.string.metadata_editor_bulk_copyright_label
                        else R.string.viewer_meta_row_copyright,
                    ),
                    field = state.copyright,
                    bulk = state.bulk,
                    isSaving = state.isSaving,
                    onValueChange = { viewModel.setText(MetadataTextTag.COPYRIGHT, it) },
                )
            }
            Spacer(Modifier.height(32.dp + navBottom))
        }

        FloatingHeader(
            title = stringResource(R.string.metadata_editor_title),
            onBack = { handleBack() },
            trailing = {
                if (state.isSaving) {
                    Box(modifier = Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(
                            color = colors.accent,
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                } else {
                    IconBubble(
                        icon = Icons.Default.Check,
                        contentDescription = stringResource(R.string.metadata_editor_done),
                        // The one commit point: applyOrFinish writes any typed text, then uploads the
                        // staged cloud edits in one pass (date, place and text together) or just leaves
                        // when only device files changed. Nothing is written until this checkmark.
                        onClick = { viewModel.applyOrFinish() },
                        diameter = 40.dp,
                        iconSize = 18.dp,
                        background = colors.pillBg,
                        borderColor = colors.pillBorder,
                        tint = colors.accent,
                    )
                }
            },
        )

        ThemedSnackbarHost(
            snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                // One inset for both states: the keyboard when it is up, the navigation bar when it is
                // not, so a save confirmation is never left underneath the keyboard.
                .windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars))
                .padding(bottom = 24.dp),
        )

        // Confirming re-uploads the corrected copies on a background app scope tracked by the transfer
        // center, then closes the editor to the timeline, so progress shows in the Activity transfer UI
        // like any other upload instead of a modal that traps the user here.
        state.pendingCloudConfirm?.let { confirm ->
            ConfirmSheet(
                title = stringResource(R.string.metadata_editor_cloud_confirm_title),
                message = pluralStringResource(
                    R.plurals.metadata_editor_cloud_confirm_body, confirm.count, confirm.count,
                ),
                confirmLabel = stringResource(R.string.metadata_editor_cloud_confirm_action),
                dismissLabel = stringResource(R.string.cancel),
                onConfirm = { viewModel.confirmCloudReplace(); onBack() },
                onDismiss = viewModel::dismissCloudReplace,
            )
        }

        if (showDiscardConfirm) {
            ConfirmSheet(
                title = stringResource(R.string.editor_discard_changes_title),
                message = stringResource(R.string.editor_discard_changes_message),
                confirmLabel = stringResource(R.string.editor_discard_changes_confirm),
                dismissLabel = stringResource(R.string.editor_discard_changes_keep),
                onConfirm = { showDiscardConfirm = false; onBack() },
                onDismiss = { showDiscardConfirm = false },
                destructive = true,
            )
        }
    }
}

// ── Thumbnail strip ──────────────────────────────────────────────────────────

/** A horizontally scrollable strip of the edited photos' thumbnails, the current one outlined. Coil
 *  resolves each from the thumbnail URI (device file or decrypted cloud thumbnail), never full-res:
 *  the gallery has documented OOM limits on large libraries. */
@Composable
private fun MetadataThumbnailStrip(items: List<GalleryItem>, selectedIndex: Int) {
    val colors = AppColors.current
    val thumbUrls = LocalThumbnailUrls.current.value
    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        itemsIndexed(items) { index, listItem ->
            val selected = index == selectedIndex
            val model: Any? = when (listItem) {
                is GalleryItem.LocalOnly -> Uri.parse(listItem.local.uri)
                is GalleryItem.Synced -> Uri.parse(listItem.local.uri)
                is GalleryItem.CloudOnly ->
                    thumbUrls[listItem.cloud.linkId] ?: listItem.cloud.thumbnailUrl
            }
            Box(
                modifier = Modifier
                    .size(76.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(colors.cardBg, RoundedCornerShape(12.dp))
                    .border(
                        width = if (selected) 2.dp else 0.5.dp,
                        color = if (selected) colors.accent else colors.line2,
                        shape = RoundedCornerShape(12.dp),
                    ),
            ) {
                if (model != null) {
                    AsyncImage(
                        model = model,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(12.dp)),
                    )
                }
            }
        }
    }
}

// ── Date field ───────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun DateField(
    state: MetadataEditorUiState,
    onPickDate: (Long) -> Unit,
    onShiftDates: (Long) -> Unit,
    onFixDatesFromName: () -> Unit,
) {
    val context = LocalContext.current
    val colors = AppColors.current
    var showDatePicker by remember { mutableStateOf(false) }
    var showTimePicker by remember { mutableStateOf(false) }
    // The day chosen in the first step, carried into the time step before the combined value is written.
    var pickedDateUtcMs by remember { mutableStateOf<Long?>(null) }
    // Which bulk date mode is active. A selection neither a shift nor a filename read can say anything
    // about never offers the switch, so there the absolute date stays the only mode and the card keeps
    // its usual chrome. The two extra modes are mutually exclusive: picking one clears the other.
    var shiftSelected by remember { mutableStateOf(false) }
    var filenameSelected by remember { mutableStateOf(false) }
    val shiftOffered = state.bulk && state.dateShiftAvailable
    val shiftMode = shiftOffered && shiftSelected
    val filenameOffered = state.bulk && state.filenameDateCount > 0
    val filenameMode = filenameOffered && filenameSelected
    val shiftSpan = state.dateShiftSpan
    // The instant the user named for the OLDEST photo, dropped as soon as a write moves the span: the
    // range line then reads the photos' own dates again, so a batch that only partly landed shows where
    // the files really are rather than where the pick aimed them.
    var pickedShiftMs by remember(state.dateShiftEarliestMs, state.dateShiftLatestMs) {
        mutableStateOf<Long?>(null)
    }
    // True once a pick crossed the ceiling the state carries, which the calendar alone cannot refuse.
    var shiftTooLate by remember { mutableStateOf(false) }

    FieldCard {
        FieldHeaderRow(
            label = stringResource(
                when {
                    shiftMode -> R.string.metadata_editor_shift_label
                    state.bulk -> R.string.metadata_editor_bulk_date_label
                    else -> R.string.metadata_editor_date_label
                },
            ),
            // In filename mode each file's date comes from its own name, so there is no picker to open;
            // the Apply button below is the whole action.
            editable = state.dateLock == null && !state.isSaving && !filenameMode,
            onEdit = { showDatePicker = true },
        )
        // The bulk date modes: one instant written over every photo, one delta added to each photo's
        // own date so the spacing between the shots survives, or the date each file's own name records.
        if (shiftOffered || filenameOffered) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(
                    label = stringResource(R.string.metadata_editor_date_mode_same),
                    selected = !shiftMode && !filenameMode,
                    onClick = { shiftSelected = false; filenameSelected = false; shiftTooLate = false },
                )
                if (shiftOffered) {
                    FilterChip(
                        label = stringResource(R.string.metadata_editor_date_mode_shift),
                        selected = shiftMode,
                        onClick = { shiftSelected = true; filenameSelected = false; shiftTooLate = false },
                    )
                }
                if (filenameOffered) {
                    FilterChip(
                        label = stringResource(R.string.metadata_editor_date_mode_filename),
                        selected = filenameMode,
                        onClick = { filenameSelected = true; shiftSelected = false; shiftTooLate = false },
                    )
                }
            }
        }
        if (filenameMode) {
            Text(
                text = stringResource(R.string.metadata_editor_filename_hint),
                color = colors.fgMute,
                fontSize = 13.sp,
            )
            // How many of the editable photos actually carry a name date; the rest keep their date.
            Text(
                text = pluralStringResource(
                    R.plurals.metadata_editor_filename_count,
                    state.filenameDateCount,
                    state.filenameDateCount,
                    state.editableCount,
                ),
                color = colors.fgPrimary,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.height(4.dp))
            PrimaryButton(
                label = stringResource(R.string.metadata_editor_filename_apply),
                onClick = onFixDatesFromName,
                enabled = !state.isSaving,
                modifier = Modifier.fillMaxWidth(),
            )
        } else if (shiftMode && shiftSpan != null) {
            Text(
                text = stringResource(R.string.metadata_editor_shift_hint),
                color = colors.fgMute,
                fontSize = 13.sp,
            )
            // Where the photos end up: a held pick carried through DateShift, and the dates they carry
            // right now before one is made. Every value comes from the state, so the line invents none.
            val preview = remember(shiftSpan, pickedShiftMs) {
                val picked = pickedShiftMs
                if (picked == null) shiftSpan
                else DateShift.shifted(shiftSpan, DateShift.deltaFor(shiftSpan, picked))
            }
            Text(
                text = stringResource(
                    if (pickedShiftMs != null) R.string.metadata_editor_shift_range_after
                    else R.string.metadata_editor_shift_range_current,
                    remember(preview.earliestMs) { formatDateTime(preview.earliestMs) },
                    remember(preview.latestMs) { formatDateTime(preview.latestMs) },
                ),
                color = colors.fgPrimary,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
            )
            if (state.dateShiftSkippedCount > 0) {
                Text(
                    text = pluralStringResource(
                        R.plurals.metadata_editor_shift_skipped,
                        state.dateShiftSkippedCount,
                        state.dateShiftSkippedCount,
                    ),
                    color = colors.fgMute,
                    fontSize = 12.sp,
                )
            }
            if (shiftTooLate) {
                Text(
                    text = stringResource(R.string.metadata_editor_shift_too_late),
                    color = colors.errorColor,
                    fontSize = 12.sp,
                )
            }
        } else if (state.bulk && !state.dateChosen) {
            Text(
                text = stringResource(R.string.metadata_editor_bulk_apply_hint),
                color = AppColors.current.fgMute,
                fontSize = 13.sp,
            )
        } else {
            Text(
                text = remember(state.captureDateMs) { formatDateTime(state.captureDateMs) },
                color = AppColors.current.fgPrimary,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
            )
            // The source line only makes sense for a single photo; a bulk set is always device files.
            if (!state.bulk) {
                Text(
                    text = stringResource(
                        if (state.dateSource == DateSource.CLOUD) R.string.metadata_editor_source_cloud
                        else R.string.metadata_editor_source_device,
                    ),
                    color = AppColors.current.fgMute,
                    fontSize = 12.sp,
                )
            }
        }
        state.dateLock?.let { LockNote(it) }
        // A single editable cloud photo takes a date edit by being re-uploaded, so the field is open
        // but the note is honest about what applying it does.
        if (!state.bulk && state.dateSource == DateSource.CLOUD && state.dateLock == null) {
            Text(
                stringResource(R.string.metadata_editor_cloud_replace_note),
                color = colors.fgMute,
                fontSize = 12.sp,
            )
        }
        // A single photo whose own file name records a different day than the one it now carries: offer
        // that day in one tap. A screenshot or download often has no date but the one in its name, and a
        // single photo has no use for the bulk "same date for all" chips. Applying runs the same path as
        // picking the date by hand, so the field then shows the new day and this suggestion hides itself.
        val nameDateMs = state.filenameDateSingleMs
        if (!state.bulk && nameDateMs != null && nameDateMs != state.captureDateMs &&
            state.dateLock == null && !state.isSaving
        ) {
            Spacer(Modifier.height(10.dp))
            Text(
                text = stringResource(
                    R.string.metadata_editor_filename_single,
                    remember(nameDateMs) { formatDateTime(nameDateMs) },
                ),
                color = colors.fgMute,
                fontSize = 13.sp,
            )
            Spacer(Modifier.height(8.dp))
            PrimaryButton(
                label = stringResource(R.string.metadata_editor_filename_use),
                onClick = { onPickDate(nameDateMs) },
                enabled = !state.isSaving,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }

    // What the pickers open on: in shift mode the OLDEST photo's own instant, which is the one the user
    // is naming; otherwise the single date the whole selection receives.
    val seedMs = if (shiftMode) state.dateShiftEarliestMs else state.captureDateMs

    if (showDatePicker) {
        val seedDay = remember(seedMs) { toUtcMidnight(seedMs) }
        // A shift may not land the newest photo in the future, so the calendar refuses every day past
        // the one the state's ceiling falls on. The time inside that last day is guarded on confirm.
        val selectableDates = remember(shiftMode, state.dateShiftMaxEarliestMs) {
            if (shiftMode) daysUpTo(state.dateShiftMaxEarliestMs) else DatePickerDefaults.AllDates
        }
        val datePickerState = rememberDatePickerState(
            initialSelectedDateMillis = seedDay,
            selectableDates = selectableDates,
        )
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    pickedDateUtcMs = datePickerState.selectedDateMillis
                    showDatePicker = false
                    if (pickedDateUtcMs != null) showTimePicker = true
                }) { Text(stringResource(R.string.metadata_editor_next)) }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        ) {
            // A shift asks for one photo's real capture time, not a date for the whole set, so the
            // calendar carries a title of its own saying which photo that is.
            if (shiftMode) {
                DatePicker(
                    state = datePickerState,
                    title = {
                        Text(
                            text = stringResource(R.string.metadata_editor_shift_picker_title),
                            modifier = Modifier.padding(start = 24.dp, end = 12.dp, top = 16.dp),
                        )
                    },
                )
            } else {
                DatePicker(state = datePickerState)
            }
        }
    }

    if (showTimePicker) {
        val zoned = remember(seedMs) {
            Instant.ofEpochMilli(seedMs).atZone(ZoneId.systemDefault())
        }
        val timePickerState = rememberTimePickerState(
            initialHour = zoned.hour,
            initialMinute = zoned.minute,
            is24Hour = DateFormat.is24HourFormat(context),
        )
        Dialog(onDismissRequest = { showTimePicker = false }) {
            Surface(shape = RoundedCornerShape(24.dp), color = colors.bg2) {
                Column(modifier = Modifier.padding(20.dp)) {
                    if (shiftMode) {
                        Text(
                            text = stringResource(R.string.metadata_editor_shift_picker_title),
                            color = colors.fgMute,
                            fontSize = 13.sp,
                        )
                        Spacer(Modifier.height(12.dp))
                    }
                    TimePicker(state = timePickerState)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        TextButton(onClick = { showTimePicker = false }) {
                            Text(stringResource(R.string.cancel))
                        }
                        TextButton(onClick = {
                            val day = pickedDateUtcMs
                            if (day != null) {
                                val chosenMs =
                                    combineDateTime(day, timePickerState.hour, timePickerState.minute)
                                when {
                                    !shiftMode -> onPickDate(chosenMs)
                                    // The calendar refuses a whole day, never an hour inside one, so a
                                    // time that crosses the ceiling is explained here instead of written.
                                    chosenMs > state.dateShiftMaxEarliestMs -> shiftTooLate = true
                                    else -> {
                                        shiftTooLate = false
                                        pickedShiftMs = chosenMs
                                        onShiftDates(chosenMs)
                                    }
                                }
                            }
                            showTimePicker = false
                        }) { Text(stringResource(R.string.metadata_editor_set)) }
                    }
                }
            }
        }
    }
}

// ── Place field ──────────────────────────────────────────────────────────────

@Composable
private fun PlaceField(
    state: MetadataEditorUiState,
    onPickCountry: (String) -> Unit,
    onPickCity: (OfflineGeocoder.GeoPlace) -> Unit,
    onRemoveLocation: () -> Unit,
) {
    val colors = AppColors.current
    var expanded by remember { mutableStateOf(false) }

    FieldCard {
        FieldHeaderRow(
            label = stringResource(
                if (state.bulk) R.string.metadata_editor_bulk_place_label
                else R.string.metadata_editor_place_label,
            ),
            editable = state.placeLock == null && !state.isSaving,
            onEdit = { expanded = !expanded },
        )
        val placeText = if (state.bulk && !state.placeChosen) {
            stringResource(R.string.metadata_editor_bulk_apply_hint)
        } else {
            state.placeLabel ?: stringResource(R.string.metadata_editor_place_none)
        }
        Text(
            text = placeText,
            color = if (state.hasLocation) colors.fgPrimary else colors.fgMute,
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
        )
        state.placeLock?.let { LockNote(it) }
        // Same honest note as the date field: a lone cloud photo is editable here through a re-upload.
        if (!state.bulk && state.dateSource == DateSource.CLOUD && state.placeLock == null) {
            Text(
                stringResource(R.string.metadata_editor_cloud_replace_note),
                color = colors.fgMute,
                fontSize = 12.sp,
            )
        }

        if (state.placeLock == null && expanded) {
            Spacer(Modifier.height(4.dp))
            LocationEditor(
                // Bulk photos may each carry a different location, so offer "remove" even before a
                // place is picked: it clears the location across every editable image.
                canRemove = state.bulk || state.hasLocation,
                enabled = !state.isSaving,
                onPickCountry = onPickCountry,
                onPickCity = onPickCity,
                onRemoveLocation = onRemoveLocation,
            )
        }
    }
}

@Composable
private fun LocationEditor(
    canRemove: Boolean,
    enabled: Boolean,
    onPickCountry: (String) -> Unit,
    onPickCity: (OfflineGeocoder.GeoPlace) -> Unit,
    onRemoveLocation: () -> Unit,
) {
    val context = LocalContext.current
    val colors = AppColors.current
    var countries by remember { mutableStateOf<List<OfflineGeocoder.GeoCountry>>(emptyList()) }
    var showCountryPicker by remember { mutableStateOf(false) }
    var selectedCountry by remember { mutableStateOf<OfflineGeocoder.GeoCountry?>(null) }
    var cityQuery by remember { mutableStateOf("") }
    var cityResults by remember { mutableStateOf<List<OfflineGeocoder.GeoPlace>>(emptyList()) }

    LaunchedEffect(Unit) {
        if (countries.isEmpty()) countries = OfflineGeocoder.countries(context)
    }
    // In-memory scan after the first parse, so a per-keystroke search stays cheap; a short query is
    // ignored to keep the result list meaningful.
    LaunchedEffect(cityQuery, selectedCountry) {
        val q = cityQuery.trim()
        cityResults = if (q.length < 2) emptyList()
        else OfflineGeocoder.searchPlaces(context, q, selectedCountry?.code, limit = 30)
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // Country selector: choosing a country places the photo at its center and narrows the search.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(colors.surfaceWeak, RoundedCornerShape(10.dp))
                .border(0.5.dp, colors.line2, RoundedCornerShape(10.dp))
                .clickable(enabled = enabled) { showCountryPicker = true }
                .padding(horizontal = 14.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = selectedCountry?.displayName
                    ?: stringResource(R.string.metadata_editor_place_choose_country),
                color = if (selectedCountry != null) colors.fgPrimary else colors.fgMute,
                fontSize = 14.sp,
            )
        }
        Text(
            text = stringResource(R.string.metadata_editor_place_country_hint),
            color = colors.fgMute,
            fontSize = 11.5.sp,
        )

        // City search, filtered to the selected country when one is chosen.
        OutlinedTextField(
            value = cityQuery,
            onValueChange = { cityQuery = it },
            shape = RoundedCornerShape(14.dp),
            enabled = enabled,
            singleLine = true,
            placeholder = {
                Text(stringResource(R.string.metadata_editor_place_search_city), color = colors.fgMute)
            },
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = colors.fgPrimary,
                unfocusedTextColor = colors.fgPrimary,
                focusedBorderColor = colors.accent,
                unfocusedBorderColor = colors.line2,
                cursorColor = colors.accent,
            ),
            modifier = Modifier.fillMaxWidth(),
        )
        if (cityResults.isNotEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(colors.surfaceWeak, RoundedCornerShape(10.dp))
                    .border(0.5.dp, colors.line2, RoundedCornerShape(10.dp)),
            ) {
                cityResults.forEach { place ->
                    val country = remember(place.countryCode) {
                        Locale("", place.countryCode).getDisplayCountry(Locale.getDefault())
                            .ifBlank { place.countryCode }
                    }
                    Text(
                        text = "${place.name}, $country",
                        color = colors.fgPrimary,
                        fontSize = 14.sp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = enabled) {
                                cityQuery = ""
                                onPickCity(place)
                            }
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                    )
                }
            }
        }

        if (canRemove) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = enabled) { onRemoveLocation() }
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    Icons.Default.LocationOff,
                    contentDescription = null,
                    tint = colors.errorColor,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    stringResource(R.string.metadata_editor_place_remove),
                    color = colors.errorColor,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
    }

    if (showCountryPicker) {
        CountryPickerDialog(
            countries = countries,
            onSelect = { country ->
                selectedCountry = country
                showCountryPicker = false
                onPickCountry(country.code)
            },
            onDismiss = { showCountryPicker = false },
        )
    }
}

@Composable
private fun CountryPickerDialog(
    countries: List<OfflineGeocoder.GeoCountry>,
    onSelect: (OfflineGeocoder.GeoCountry) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = AppColors.current
    var filter by remember { mutableStateOf("") }
    val shown = remember(filter, countries) {
        val f = filter.trim()
        if (f.isEmpty()) countries
        else countries.filter { it.displayName.contains(f, ignoreCase = true) }
    }
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(20.dp), color = colors.bg2) {
            Column(modifier = Modifier.padding(16.dp)) {
                OutlinedTextField(
                    value = filter,
                    onValueChange = { filter = it },
                    shape = RoundedCornerShape(14.dp),
                    singleLine = true,
                    placeholder = {
                        Text(stringResource(R.string.metadata_editor_country_search), color = colors.fgMute)
                    },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = colors.fgPrimary,
                        unfocusedTextColor = colors.fgPrimary,
                        focusedBorderColor = colors.accent,
                        unfocusedBorderColor = colors.line2,
                        cursorColor = colors.accent,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(10.dp))
                LazyColumn(modifier = Modifier.heightIn(max = 360.dp)) {
                    items(shown) { country ->
                        Text(
                            text = country.displayName,
                            color = colors.fgPrimary,
                            fontSize = 15.sp,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSelect(country) }
                                .padding(horizontal = 6.dp, vertical = 13.dp),
                        )
                    }
                }
            }
        }
    }
}

// ── Descriptive text fields ──────────────────────────────────────────────────

/**
 * One descriptive EXIF text field (description, artist, copyright), opened by the same pencil the
 * place field uses. The typed value lives in the ViewModel and is committed by the Done checkmark with
 * the date and place, so the box carries no Save of its own and the keyboard closing never drops it.
 */
@Composable
private fun TextTagField(
    label: String,
    field: MetadataTextFieldState,
    bulk: Boolean,
    isSaving: Boolean,
    onValueChange: (String) -> Unit,
    maxLines: Int = 1,
) {
    val colors = AppColors.current
    val focusManager = LocalFocusManager.current
    var expanded by remember { mutableStateOf(false) }

    FieldCard {
        FieldHeaderRow(
            label = label,
            editable = field.lock == null && !isSaving,
            onEdit = { expanded = !expanded },
        )
        // A locked field with nothing read has no value to state, so the note below carries the whole
        // message rather than a placeholder that would claim the tag is empty.
        val body = when {
            field.lock == null && bulk && !field.chosen ->
                stringResource(R.string.metadata_editor_bulk_apply_hint)
            field.stored.isNotEmpty() -> field.stored
            field.lock == null -> stringResource(R.string.metadata_editor_text_none)
            else -> null
        }
        body?.let {
            Text(
                text = it,
                color = if (field.stored.isNotEmpty()) colors.fgPrimary else colors.fgMute,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
            )
        }
        field.lock?.let { LockNote(it) }

        if (field.lock == null && expanded) {
            Spacer(Modifier.height(4.dp))
            // No per-field Save: the typed value is held here and committed by the final checkmark along
            // with the date and place, in one pass. The ASCII transliteration below previews what that
            // write stores, and it happens automatically on the write, so nothing has to be pre-saved.
            OutlinedTextField(
                value = field.value,
                onValueChange = onValueChange,
                shape = RoundedCornerShape(14.dp),
                enabled = !isSaving,
                singleLine = maxLines == 1,
                maxLines = maxLines,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = colors.fgPrimary,
                    unfocusedTextColor = colors.fgPrimary,
                    focusedBorderColor = colors.accent,
                    unfocusedBorderColor = colors.line2,
                    cursorColor = colors.accent,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
            AsciiPreview(typed = field.value)
        }
    }
}

/**
 * The line that shows what an EXIF text tag will really hold. These tags are ASCII fields, so an
 * accented word reaches the file transliterated ("Nyaralás" is stored as "Nyaralas") and text with no
 * ASCII form at all reaches it as nothing. The line appears only where that differs from the typed
 * text, so a plain ASCII caption never carries a note.
 */
@Composable
private fun AsciiPreview(typed: String) {
    val storedForm = remember(typed) { ExifAsciiText.transliterate(typed) }
    // Compared against the trimmed input: trailing space alone is invisible to the reader, so folding
    // it is not worth a line of its own.
    if (storedForm == typed.trim()) return
    Text(
        text = if (storedForm.isEmpty()) {
            stringResource(R.string.metadata_editor_text_stored_none)
        } else {
            stringResource(R.string.metadata_editor_text_stored_as, storedForm)
        },
        color = AppColors.current.fgMute,
        fontSize = 12.sp,
    )
}

// ── Shared field chrome ──────────────────────────────────────────────────────

@Composable
private fun FieldCard(content: @Composable () -> Unit) {
    val colors = AppColors.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(cardShape)
            .background(colors.cardBg, cardShape)
            .border(0.5.dp, colors.cardBorder, cardShape)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) { content() }
}

@Composable
private fun FieldHeaderRow(label: String, editable: Boolean, onEdit: () -> Unit) {
    val colors = AppColors.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The label takes the remaining width and wraps to at most two lines. Without the weight a
        // long bulk label ("Set a date for these photos", longer still once translated) would eat the
        // whole row and squeeze the Change action to a single character column, wrapping it vertically.
        Text(
            label.uppercase(),
            color = colors.fgMute,
            fontSize = 10.5.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.8.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .padding(end = 8.dp),
        )
        if (editable) {
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .clickable(onClick = onEdit)
                    .padding(horizontal = 4.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Icon(
                    Icons.Default.Edit,
                    contentDescription = stringResource(R.string.metadata_editor_change),
                    tint = colors.accent,
                    modifier = Modifier.size(15.dp),
                )
                // A fixed one-line action so it never wraps per-character when the label is long.
                Text(
                    stringResource(R.string.metadata_editor_change),
                    color = colors.accent,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    softWrap = false,
                )
            }
        }
    }
}

@Composable
private fun LockNote(lock: EditLock) {
    val res = when (lock) {
        EditLock.CLOUD -> R.string.metadata_editor_note_cloud
        EditLock.SHARED -> R.string.metadata_editor_note_shared
        EditLock.VIDEO -> R.string.metadata_editor_note_video_place
        EditLock.PLACE_FORMAT -> R.string.metadata_editor_note_place_format
        EditLock.DATE_FORMAT -> R.string.metadata_editor_note_date_format
        EditLock.FORMAT -> R.string.metadata_editor_note_text_format
        EditLock.BULK -> R.string.metadata_editor_note_description_single
    }
    Text(
        stringResource(res),
        color = AppColors.current.fgMute,
        fontSize = 12.sp,
    )
}

// ── Date/time helpers ────────────────────────────────────────────────────────

/** Locale-aware "medium date, short time" for the shown capture timestamp. */
private fun formatDateTime(ms: Long): String =
    java.text.DateFormat
        .getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT, Locale.getDefault())
        .format(Date(ms))

/** UTC midnight of the day [ms] falls on in the device zone. This is the seed a Material date picker
 *  expects, since its calendar reads the selected value in UTC. */
private fun toUtcMidnight(ms: Long): Long {
    val localDate = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).toLocalDate()
    return localDate.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
}

/** The calendar rule a date shift needs: no day later than the one [maxEarliestMs] falls on, so the
 *  chosen day alone can never put the newest photo of the selection in the future. A Material calendar
 *  reads its own values as UTC midnights, so the ceiling is compared on that basis. */
@OptIn(ExperimentalMaterial3Api::class)
private fun daysUpTo(maxEarliestMs: Long): SelectableDates {
    val lastDayUtcMs = toUtcMidnight(maxEarliestMs)
    val lastYear = Instant.ofEpochMilli(maxEarliestMs).atZone(ZoneId.systemDefault()).year
    return object : SelectableDates {
        override fun isSelectableDate(utcTimeMillis: Long): Boolean = utcTimeMillis <= lastDayUtcMs

        override fun isSelectableYear(year: Int): Boolean = year <= lastYear
    }
}

/** Combines a picker day (UTC midnight) with a wall-clock [hour]/[minute] in the device zone. Reading
 *  the day back in UTC keeps the calendar date the user tapped from drifting across the zone offset. */
private fun combineDateTime(dateUtcMs: Long, hour: Int, minute: Int): Long {
    val localDate = Instant.ofEpochMilli(dateUtcMs).atZone(ZoneOffset.UTC).toLocalDate()
    return localDate.atTime(hour, minute)
        .atZone(ZoneId.systemDefault())
        .toInstant()
        .toEpochMilli()
}
