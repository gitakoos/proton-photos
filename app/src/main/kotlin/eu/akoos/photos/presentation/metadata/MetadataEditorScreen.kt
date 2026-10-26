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
import android.text.format.DateUtils
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import eu.akoos.photos.R
import eu.akoos.photos.domain.entity.GalleryItem
import eu.akoos.photos.domain.usecase.ExifAsciiText
import eu.akoos.photos.presentation.common.AppModalSheet
import eu.akoos.photos.presentation.common.ConfirmSheet
import eu.akoos.photos.presentation.common.IconBubble
import androidx.activity.compose.BackHandler
import eu.akoos.photos.presentation.common.PrimaryButton
import eu.akoos.photos.presentation.common.ThemedSnackbarHost
import eu.akoos.photos.presentation.common.cardFill
import eu.akoos.photos.presentation.common.floatingHeaderContentTopPadding
import eu.akoos.photos.presentation.common.glassSource
import eu.akoos.photos.presentation.common.rememberGlassBackdrop
import eu.akoos.photos.presentation.gallery.LocalThumbnailUrls
import eu.akoos.photos.presentation.common.FloatingHeader
import eu.akoos.photos.presentation.lock.LocalAppLock
import eu.akoos.photos.presentation.lock.runWhenOpen
import eu.akoos.photos.presentation.theme.AppColors
import eu.akoos.photos.presentation.theme.SheetBg
import eu.akoos.photos.presentation.util.LocalIs24Hour
import eu.akoos.photos.presentation.util.appLocale
import eu.akoos.photos.presentation.util.formatDateTimeMinute
import eu.akoos.photos.presentation.viewer.DetailsDangerButton
import eu.akoos.photos.presentation.viewer.DetailsPageFrame
import eu.akoos.photos.presentation.viewer.DetailsPageHeader
import eu.akoos.photos.util.cityCountryLabel
import eu.akoos.photos.util.countryName
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.coroutines.flow.first

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
    // and, on approval, retry the queued write. Same recipe the album delete flow uses. The system
    // dialog would open above the app lock, so the launch waits for the unlock.
    val appLock = LocalAppLock.current
    val writeConsentLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) viewModel.onPermissionGranted()
        else viewModel.clearPendingWriteIntent()
    }
    LaunchedEffect(state.pendingWriteIntent) {
        val sender = state.pendingWriteIntent ?: return@LaunchedEffect
        appLock.runWhenOpen { writeConsentLauncher.launch(IntentSenderRequest.Builder(sender).build()) }
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

    // The page as its floating header frosts it, when the frosted pills are on.
    val glass = rememberGlassBackdrop(colors.pageBg)
    Box(modifier = Modifier.fillMaxSize().background(colors.pageBg)) {
        val contentTopPad = floatingHeaderContentTopPadding()
        val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

        Box(Modifier.fillMaxSize().glassSource(glass)) {
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
                EditedPhotos(items = items, state = state)
                Spacer(Modifier.height(20.dp))
                Column(
                    modifier = Modifier.padding(horizontal = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    DateCard(
                        state = state,
                        onPickDate = viewModel::setDate,
                        onShiftDates = viewModel::shiftDatesTo,
                        onFixDatesFromName = viewModel::fixDatesFromName,
                    )
                    PlaceAndTextCard(
                        state = state,
                        onPickPoint = viewModel::pickPoint,
                        onRemoveLocation = viewModel::removeLocation,
                        onText = viewModel::setText,
                    )
                    // A cloud edit waits for the checkmark, so a staged change is called out: leaving by the
                    // back arrow instead would drop it.
                    if (state.stagedCloudCount > 0) {
                        Text(
                            stringResource(R.string.metadata_editor_cloud_staged_hint),
                            color = colors.accent,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                }
                Spacer(Modifier.height(32.dp + navBottom))
            }
        }

        FloatingHeader(
            backdrop = glass,
            title = if (state.bulk) {
                pluralStringResource(R.plurals.metadata_editor_bulk_title, state.editableCount, state.editableCount)
            } else {
                stringResource(R.string.metadata_editor_title)
            },
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

// ── The photos being edited ──────────────────────────────────────────────────

/** The photos as a small stack (up to four, overlapping), how many the edit lands on, and the dates they
 *  span. Coil reads each from its thumbnail, never full-res: the gallery has documented OOM limits. */
@Composable
private fun EditedPhotos(items: List<GalleryItem>, state: MetadataEditorUiState) {
    val colors = AppColors.current
    val thumbUrls = LocalThumbnailUrls.current.value
    val shown = items.take(4)
    Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(modifier = Modifier.size(width = 52.dp + 30.dp * (shown.size - 1).coerceAtLeast(0), height = 52.dp)) {
            shown.forEachIndexed { index, listItem ->
                val model: Any? = when (listItem) {
                    is GalleryItem.LocalOnly -> Uri.parse(listItem.local.uri)
                    is GalleryItem.Synced -> Uri.parse(listItem.local.uri)
                    is GalleryItem.CloudOnly -> thumbUrls[listItem.cloud.linkId] ?: listItem.cloud.thumbnailUrl
                }
                Box(
                    modifier = Modifier
                        .offset(x = 30.dp * index)
                        .size(52.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(colors.cardBg)
                        .border(2.dp, colors.pageBg, RoundedCornerShape(12.dp)),
                ) {
                    if (model != null) {
                        AsyncImage(
                            model = model,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }
        }
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            // The title already counts the photos, so a selection reads the days it spans; a single photo
            // its name and its moment.
            val context = LocalContext.current
            val is24Hour = LocalIs24Hour.current
            val headline = when {
                !state.bulk -> items.firstOrNull()?.let { fileNameOf(it) }.orEmpty()
                state.dateShiftAvailable -> remember(state.dateShiftEarliestMs, state.dateShiftLatestMs) {
                    dateRangeText(context, state.dateShiftEarliestMs, state.dateShiftLatestMs, withTime = false, is24Hour)
                }
                else -> stringResource(R.string.metadata_editor_bulk_summary, state.editableCount)
            }
            Text(
                headline,
                color = colors.fgPrimary, fontSize = 15.sp, fontWeight = FontWeight.Medium,
                maxLines = 1, overflow = TextOverflow.MiddleEllipsis,
            )
            val second = when {
                state.skippedCount > 0 -> stringResource(
                    R.string.metadata_editor_bulk_summary_skipped, state.editableCount, state.skippedCount,
                )
                !state.bulk && state.loaded -> remember(state.captureDateMs) { formatDateTime(state.captureDateMs) }
                else -> null
            }
            second?.let {
                Text(it, color = colors.fgMute, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** The span from [startMs] to [endMs] the platform's own way for the locale, short enough for one line:
 *  "Oct 20 - 26, 2026", or with the times. */
private fun dateRangeText(context: android.content.Context, startMs: Long, endMs: Long, withTime: Boolean, is24Hour: Boolean): String {
    var flags = DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_YEAR or DateUtils.FORMAT_ABBREV_MONTH
    if (withTime) flags = flags or DateUtils.FORMAT_SHOW_TIME or (if (is24Hour) DateUtils.FORMAT_24HOUR else DateUtils.FORMAT_12HOUR)
    return DateUtils.formatDateRange(context, startMs, endMs, flags)
}

private fun fileNameOf(item: GalleryItem): String = when (item) {
    is GalleryItem.LocalOnly -> item.local.displayName
    is GalleryItem.Synced -> item.local.displayName
    is GalleryItem.CloudOnly -> item.cloud.displayName
}

// ── Date card ────────────────────────────────────────────────────────────────

/** The date's three ways for a selection: one moment for all, a shift that keeps the gaps, the date each
 *  file's own name records. A selection neither of the last two can say anything about shows none. */
private enum class DateMode { SAME, SHIFT, FILENAME }

@Composable
private fun DateCard(
    state: MetadataEditorUiState,
    onPickDate: (Long) -> Unit,
    onShiftDates: (Long) -> Unit,
    onFixDatesFromName: () -> Unit,
) {
    val colors = AppColors.current
    var mode by remember { mutableStateOf(DateMode.SAME) }
    var picking by remember { mutableStateOf(false) }
    val shiftOffered = state.bulk && state.dateShiftAvailable
    val filenameOffered = state.bulk && state.filenameDateCount > 0
    if (mode == DateMode.SHIFT && !shiftOffered || mode == DateMode.FILENAME && !filenameOffered) mode = DateMode.SAME
    val shiftSpan = state.dateShiftSpan
    // The instant the user named for the OLDEST photo, dropped once a write moves the span, so the preview
    // then reads where the files really are.
    var pickedShiftMs by remember(state.dateShiftEarliestMs, state.dateShiftLatestMs) { mutableStateOf<Long?>(null) }
    var shiftTooLate by remember { mutableStateOf(false) }
    val editable = state.dateLock == null && !state.isSaving

    EditorCard {
        val labelRes = when {
            mode == DateMode.SHIFT -> R.string.metadata_editor_shift_label
            state.bulk -> R.string.metadata_editor_bulk_date_label
            else -> R.string.metadata_editor_date_label
        }
        AnimatedContent(targetState = labelRes, transitionSpec = { textSwap() }, label = "dateLabel") { res ->
            EditorCardLabel(stringResource(res))
        }
        if (shiftOffered || filenameOffered) {
            val modes = listOfNotNull(
                DateMode.SAME to stringResource(R.string.metadata_editor_date_mode_same),
                if (shiftOffered) DateMode.SHIFT to stringResource(R.string.metadata_editor_date_mode_shift) else null,
                if (filenameOffered) DateMode.FILENAME to stringResource(R.string.metadata_editor_date_mode_filename) else null,
            )
            Segmented(options = modes, selected = mode, onSelect = { mode = it; shiftTooLate = false })
        }
        AnimatedContent(
            targetState = mode,
            transitionSpec = { textSwap().using(SizeTransform(clip = false) { _, _ -> spring(stiffness = Spring.StiffnessMediumLow) }) },
            label = "dateMode",
        ) { shownMode ->
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        when (shownMode) {
            DateMode.FILENAME -> {
                Text(stringResource(R.string.metadata_editor_filename_hint), color = colors.fgMute, fontSize = 13.sp)
                Text(
                    pluralStringResource(
                        R.plurals.metadata_editor_filename_count,
                        state.filenameDateCount, state.filenameDateCount, state.editableCount,
                    ),
                    color = colors.fgPrimary, fontSize = 15.sp, fontWeight = FontWeight.Medium,
                )
                PrimaryButton(
                    label = stringResource(R.string.metadata_editor_filename_apply),
                    onClick = onFixDatesFromName,
                    enabled = !state.isSaving,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            DateMode.SHIFT -> if (shiftSpan != null) {
                Text(stringResource(R.string.metadata_editor_shift_hint), color = colors.fgMute, fontSize = 13.sp)
                val preview = remember(shiftSpan, pickedShiftMs) {
                    val picked = pickedShiftMs
                    if (picked == null) shiftSpan else DateShift.shifted(shiftSpan, DateShift.deltaFor(shiftSpan, picked))
                }
                // Where the oldest and the newest photo stand: now, or once a pick moved them (in the accent).
                val context = LocalContext.current
                val is24Hour = LocalIs24Hour.current
                ValueRow(
                    text = remember(preview, is24Hour) {
                        dateRangeText(context, preview.earliestMs, preview.latestMs, withTime = true, is24Hour)
                    },
                    accent = pickedShiftMs != null,
                    enabled = editable,
                    onClick = { picking = true },
                )
                if (state.dateShiftSkippedCount > 0) {
                    Text(
                        pluralStringResource(R.plurals.metadata_editor_shift_skipped, state.dateShiftSkippedCount, state.dateShiftSkippedCount),
                        color = colors.fgMute, fontSize = 12.sp,
                    )
                }
                if (shiftTooLate) {
                    Text(stringResource(R.string.metadata_editor_shift_too_late), color = colors.errorColor, fontSize = 12.sp)
                }
            }
            DateMode.SAME -> {
                val notChosen = state.bulk && !state.dateChosen
                ValueRow(
                    text = if (notChosen) stringResource(R.string.metadata_editor_bulk_apply_hint)
                    else remember(state.captureDateMs) { formatDateTime(state.captureDateMs) },
                    muted = notChosen,
                    enabled = editable,
                    onClick = { picking = true },
                )
                if (!state.bulk) {
                    Text(
                        stringResource(
                            if (state.dateSource == DateSource.CLOUD) R.string.metadata_editor_source_cloud
                            else R.string.metadata_editor_source_device,
                        ),
                        color = colors.fgMute, fontSize = 12.sp,
                    )
                }
            }
        }
        }
        }
        state.dateLock?.let { LockNote(it) }
        if (!state.bulk && state.dateSource == DateSource.CLOUD && state.dateLock == null) {
            Text(stringResource(R.string.metadata_editor_cloud_replace_note), color = colors.fgMute, fontSize = 12.sp)
        }
        // A single photo whose own file name records another day: that day in one tap.
        val nameDateMs = state.filenameDateSingleMs
        if (!state.bulk && nameDateMs != null && nameDateMs != state.captureDateMs && editable) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.metadata_editor_filename_single, remember(nameDateMs) { formatDateTime(nameDateMs) }),
                    color = colors.fgPrimary, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    stringResource(R.string.metadata_editor_filename_use),
                    color = colors.accent, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { onPickDate(nameDateMs) }
                        .padding(horizontal = 8.dp, vertical = 8.dp),
                )
            }
        }
    }

    if (picking) {
        val shifting = mode == DateMode.SHIFT
        DateTimeSheet(
            title = stringResource(if (shifting) R.string.metadata_editor_shift_picker_title else R.string.viewer_meta_section_datetime),
            seedMs = if (shifting) state.dateShiftEarliestMs else state.captureDateMs,
            latestMs = if (shifting) state.dateShiftMaxEarliestMs else null,
            onDismiss = { picking = false },
            onDone = { chosenMs ->
                picking = false
                when {
                    !shifting -> onPickDate(chosenMs)
                    // The calendar refuses a whole day, never an hour in one, so a time past the ceiling is
                    // explained here instead of written.
                    chosenMs > state.dateShiftMaxEarliestMs -> shiftTooLate = true
                    else -> {
                        shiftTooLate = false
                        pickedShiftMs = chosenMs
                        onShiftDates(chosenMs)
                    }
                }
            },
        )
    }
}

/** The calendar and time in a sheet over the editor, Done handing the picked moment back. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DateTimeSheet(
    title: String,
    seedMs: Long,
    latestMs: Long?,
    onDismiss: () -> Unit,
    onDone: (Long) -> Unit,
) {
    var chosen by remember { mutableStateOf(seedMs) }
    AppModalSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = SheetBg,
        scrimColor = Color.Black.copy(alpha = 0.5f),
    ) {
        DetailsPageFrame {
            DetailsPageHeader(
                title = title,
                action = stringResource(R.string.metadata_editor_done),
                onAction = { onDone(chosen) },
            )
            DateTimeFields(seedMs = seedMs, latestMs = latestMs, onChange = { chosen = it })
        }
    }
}

// ── Place and text rows ──────────────────────────────────────────────────────

/** The place and the three text tags as rows; each opens a sheet of its own. What a lock keeps from being
 *  changed is said under the card, once per reason. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlaceAndTextCard(
    state: MetadataEditorUiState,
    onPickPoint: (Double, Double) -> Unit,
    onRemoveLocation: () -> Unit,
    onText: (MetadataTextTag, String) -> Unit,
) {
    val colors = AppColors.current
    val locale = appLocale()
    val placeName = remember(state.place, locale) {
        state.place?.let { cityCountryLabel(it.city, it.countryCode, it.countryName, locale) }
    }
    var placing by remember { mutableStateOf(false) }
    var typing by remember { mutableStateOf<MetadataTextTag?>(null) }
    val none = stringResource(R.string.metadata_editor_text_none)

    EditorCard(padded = false) {
        EditorRow(
            label = stringResource(R.string.metadata_editor_place_label),
            value = when {
                state.bulk && !state.placeChosen -> ""
                else -> placeName ?: stringResource(R.string.metadata_editor_place_none)
            },
            enabled = state.placeLock == null && !state.isSaving,
            onClick = { placing = true },
        )
        MetadataTextTag.entries.forEach { tag ->
            val field = state.textField(tag)
            EditorDivider()
            EditorRow(
                label = stringResource(
                    when (tag) {
                        MetadataTextTag.DESCRIPTION -> R.string.viewer_meta_row_description
                        MetadataTextTag.ARTIST -> R.string.viewer_meta_row_artist
                        MetadataTextTag.COPYRIGHT -> R.string.viewer_meta_row_copyright
                    },
                ),
                value = when {
                    field.dirty -> field.value
                    field.stored.isNotEmpty() -> field.stored
                    state.bulk && !field.chosen -> ""
                    else -> none
                },
                pending = field.dirty,
                enabled = field.lock == null && !state.isSaving,
                onClick = { typing = tag },
            )
        }
    }
    val locks = (listOfNotNull(state.placeLock) + MetadataTextTag.entries.mapNotNull { state.textField(it).lock }).distinct()
    locks.forEach { LockNote(it) }
    if (!state.bulk && state.dateSource == DateSource.CLOUD && state.placeLock == null) {
        Text(stringResource(R.string.metadata_editor_cloud_replace_note), color = colors.fgMute, fontSize = 12.sp)
    }

    if (placing) {
        val center = remember { PickerCenter() }
        AppModalSheet(
            onDismissRequest = { placing = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = SheetBg,
            scrimColor = Color.Black.copy(alpha = 0.5f),
        ) {
            DetailsPageFrame(scroll = false) {
                DetailsPageHeader(
                    title = stringResource(R.string.viewer_meta_section_location),
                    action = stringResource(R.string.metadata_editor_done),
                    onAction = {
                        center.center()?.let { (lat, lon) -> onPickPoint(lat, lon) }
                        placing = false
                    },
                )
                InlineLocationPicker(
                    initialLatitude = state.latitude.takeIf { state.hasLocation },
                    initialLongitude = state.longitude.takeIf { state.hasLocation },
                    center = center,
                    hint = stringResource(R.string.details_place_hint),
                )
                // A selection may each carry a different place, so removing is offered before one is picked.
                if (state.bulk || state.hasLocation) {
                    DetailsDangerButton(
                        label = stringResource(R.string.metadata_editor_place_remove),
                        enabled = !state.isSaving,
                        onClick = {
                            onRemoveLocation()
                            placing = false
                        },
                    )
                }
            }
        }
    }

    typing?.let { tag ->
        val field = state.textField(tag)
        val focus = remember { FocusRequester() }
        val textSheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        val label = stringResource(
            when (tag) {
                MetadataTextTag.DESCRIPTION -> R.string.viewer_meta_row_description
                MetadataTextTag.ARTIST -> if (state.bulk) R.string.metadata_editor_bulk_artist_label else R.string.viewer_meta_row_artist
                MetadataTextTag.COPYRIGHT -> if (state.bulk) R.string.metadata_editor_bulk_copyright_label else R.string.viewer_meta_row_copyright
            },
        )
        AppModalSheet(
            onDismissRequest = { typing = null },
            sheetState = textSheet,
            containerColor = SheetBg,
            scrimColor = Color.Black.copy(alpha = 0.5f),
        ) {
            DetailsPageFrame {
                // The typed text is held and written with everything else by the editor's checkmark, so
                // Done here only closes the sheet.
                DetailsPageHeader(
                    title = label,
                    action = stringResource(R.string.metadata_editor_done),
                    onAction = { typing = null },
                )
                OutlinedTextField(
                    value = field.value,
                    onValueChange = { onText(tag, it) },
                    shape = RoundedCornerShape(14.dp),
                    singleLine = tag != MetadataTextTag.DESCRIPTION,
                    maxLines = if (tag == MetadataTextTag.DESCRIPTION) 3 else 1,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { typing = null }),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = colors.fgPrimary,
                        unfocusedTextColor = colors.fgPrimary,
                        focusedBorderColor = colors.accent,
                        unfocusedBorderColor = colors.line2,
                        cursorColor = colors.accent,
                    ),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                )
                AsciiPreview(typed = field.value)
            }
        }
        // The keyboard is asked for once the sheet has arrived: asked for while the sheet still slides in, it
        // covers the sheet, which then jumps above it only when its own slide ends.
        LaunchedEffect(tag) {
            snapshotFlow { textSheet.currentValue == SheetValue.Expanded && textSheet.targetValue == SheetValue.Expanded }
                .first { it }
            runCatching { focus.requestFocus() }
        }
    }
}

// ── Shared chrome ────────────────────────────────────────────────────────────

@Composable
private fun EditorCard(padded: Boolean = true, content: @Composable () -> Unit) {
    val colors = AppColors.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(cardShape)
            .background(cardFill(colors.cardBg), cardShape)
            .border(0.5.dp, colors.cardBorder, cardShape)
            .animateContentSize(spring(stiffness = Spring.StiffnessMediumLow))
            .then(if (padded) Modifier.padding(16.dp) else Modifier),
        verticalArrangement = Arrangement.spacedBy(if (padded) 10.dp else 0.dp),
    ) { content() }
}

@Composable
private fun EditorCardLabel(label: String) {
    Text(
        label.uppercase(),
        color = AppColors.current.fgMute,
        fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.8.sp,
        maxLines = 2, overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun EditorDivider() {
    Box(Modifier.fillMaxWidth().height(0.5.dp).background(AppColors.current.line2))
}

/** A row of the place-and-text card: the name, what it holds now on one line, and the way into it. */
@Composable
private fun EditorRow(label: String, value: String, enabled: Boolean, onClick: () -> Unit, pending: Boolean = false) {
    val colors = AppColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = colors.fgPrimary, fontSize = 14.sp, maxLines = 1)
        Spacer(Modifier.width(12.dp))
        val tint by animateColorAsState(if (pending) colors.accent else colors.fgDim, label = "rowTint")
        AnimatedContent(targetState = value, transitionSpec = { textSwap() }, modifier = Modifier.weight(1f), label = "rowValue") { shown ->
            Text(
                shown,
                color = tint,
                fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.End,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (enabled) {
            Spacer(Modifier.width(4.dp))
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = colors.fgMute, modifier = Modifier.size(20.dp))
        }
    }
}

/** The value the date card sets, as a field that opens the picker. */
@Composable
private fun ValueRow(text: String, enabled: Boolean, onClick: () -> Unit, muted: Boolean = false, accent: Boolean = false) {
    val colors = AppColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(colors.fgPrimary.copy(alpha = 0.06f))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val tint by animateColorAsState(
            when {
                muted -> colors.fgMute
                accent -> colors.accent
                else -> colors.fgPrimary
            },
            label = "valueTint",
        )
        AnimatedContent(targetState = text, transitionSpec = { textSwap() }, modifier = Modifier.weight(1f), label = "value") { shown ->
            Text(
                shown,
                color = tint,
                fontSize = 14.sp, fontWeight = if (muted) FontWeight.Normal else FontWeight.Medium,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        if (enabled) Icon(Icons.Default.Edit, contentDescription = null, tint = colors.accent, modifier = Modifier.size(16.dp))
    }
}

/** A row of choices as one control. The highlight slides to the picked one on a spring and the labels'
 *  colours follow it, so a switch reads as one movement. Labels may take two lines. */
@Composable
private fun <T> Segmented(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    val colors = AppColors.current
    val index = options.indexOfFirst { it.first == selected }.coerceAtLeast(0)
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .height(44.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(colors.fgPrimary.copy(alpha = 0.07f))
            .padding(3.dp),
    ) {
        val segment = maxWidth / options.size
        val offset by animateDpAsState(
            targetValue = segment * index,
            animationSpec = spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessMediumLow),
            label = "segment",
        )
        Box(
            modifier = Modifier
                .offset(x = offset)
                .width(segment)
                .fillMaxHeight()
                .clip(RoundedCornerShape(9.dp))
                .background(colors.accent.copy(alpha = 0.22f)),
        )
        Row(modifier = Modifier.fillMaxSize()) {
            options.forEachIndexed { i, (value, label) ->
                val on = i == index
                val tint by animateColorAsState(if (on) colors.fgPrimary else colors.fgDim, label = "segmentTint")
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(9.dp))
                        .clickable { onSelect(value) }
                        .padding(horizontal = 6.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        label,
                        color = tint,
                        fontSize = 12.sp, fontWeight = FontWeight.Medium,
                        maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                        lineHeight = 14.sp,
                    )
                }
            }
        }
    }
}

/** How a line of text gives way to the next: the old one fades out as the new one fades in, rising a little. */
private fun <S> AnimatedContentTransitionScope<S>.textSwap(): ContentTransform =
    (fadeIn(tween(220, delayMillis = 60)) + slideInVertically(tween(260)) { it / 4 })
        .togetherWith(fadeOut(tween(120)))

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

@Composable
internal fun LockNote(lock: EditLock) {
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
private fun formatDateTime(ms: Long): String = formatDateTimeMinute(ms)

/** UTC midnight of the day [ms] falls on in the device zone. This is the seed a Material date picker
 *  expects, since its calendar reads the selected value in UTC. */
internal fun toUtcMidnight(ms: Long): Long {
    val localDate = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).toLocalDate()
    return localDate.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
}

/** The calendar rule a date shift needs: no day later than the one [maxEarliestMs] falls on, so the
 *  chosen day alone can never put the newest photo of the selection in the future. A Material calendar
 *  reads its own values as UTC midnights, so the ceiling is compared on that basis. */
@OptIn(ExperimentalMaterial3Api::class)
internal fun daysUpTo(maxEarliestMs: Long): SelectableDates {
    val lastDayUtcMs = toUtcMidnight(maxEarliestMs)
    val lastYear = Instant.ofEpochMilli(maxEarliestMs).atZone(ZoneId.systemDefault()).year
    return object : SelectableDates {
        override fun isSelectableDate(utcTimeMillis: Long): Boolean = utcTimeMillis <= lastDayUtcMs

        override fun isSelectableYear(year: Int): Boolean = year <= lastYear
    }
}

/** Combines a picker day (UTC midnight) with a wall-clock [hour]/[minute] in the device zone. Reading
 *  the day back in UTC keeps the calendar date the user tapped from drifting across the zone offset. */
internal fun combineDateTime(dateUtcMs: Long, hour: Int, minute: Int): Long {
    val localDate = Instant.ofEpochMilli(dateUtcMs).atZone(ZoneOffset.UTC).toLocalDate()
    return localDate.atTime(hour, minute)
        .atZone(ZoneId.systemDefault())
        .toInstant()
        .toEpochMilli()
}
