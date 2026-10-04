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

package eu.akoos.photos.presentation.common

import android.content.Context
import android.content.res.Resources
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActionScope
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Label
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.edit
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.akoos.photos.R
import eu.akoos.photos.data.db.dao.LabelBulkResult
import eu.akoos.photos.data.db.dao.LabelUsage
import eu.akoos.photos.data.label.LabelKeyReconciler
import eu.akoos.photos.data.label.PhotoLabelRepository
import eu.akoos.photos.data.preferences.SettingsKeys
import eu.akoos.photos.data.preferences.settingsDataStore
import eu.akoos.photos.domain.entity.GalleryItem
import eu.akoos.photos.presentation.theme.Accent
import eu.akoos.photos.presentation.theme.AppColors
import eu.akoos.photos.presentation.theme.CardBg
import eu.akoos.photos.presentation.theme.FgMute
import eu.akoos.photos.presentation.theme.Line2
import eu.akoos.photos.presentation.theme.SheetBg
import eu.akoos.photos.presentation.viewer.LabelEditorInput
import eu.akoos.photos.util.LabelNormalizer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** What the surface says once a bulk label add is done. */
internal sealed interface LabelBulkMessage {
    /** [labelled] photos gained a label; [limited] of the selection reached the per-photo cap. */
    data class Added(val labelled: Int, val limited: Int) : LabelBulkMessage

    /** Nothing gained a label, and [limited] photos were already at the cap. */
    data class Limited(val limited: Int) : LabelBulkMessage

    /** Every selected photo already carried every label. */
    data object NothingNew : LabelBulkMessage

    data object Failed : LabelBulkMessage
}

/** The message for a bulk add that ended in [result], null when the write failed. */
internal fun labelBulkMessage(result: LabelBulkResult?): LabelBulkMessage = when {
    result == null -> LabelBulkMessage.Failed
    result.changedPhotos > 0 -> LabelBulkMessage.Added(result.changedPhotos, result.cappedPhotos)
    result.cappedPhotos > 0 -> LabelBulkMessage.Limited(result.cappedPhotos)
    else -> LabelBulkMessage.NothingNew
}

internal fun LabelBulkMessage.resolve(res: Resources): String {
    val cap = LabelNormalizer.MAX_LABELS
    return when (this) {
        is LabelBulkMessage.Added -> {
            val added = res.getQuantityString(R.plurals.label_bulk_added, labelled, labelled)
            if (limited > 0) added + " " + res.getQuantityString(R.plurals.label_bulk_limited, limited, limited, cap)
            else added
        }
        is LabelBulkMessage.Limited -> res.getQuantityString(R.plurals.label_bulk_limited, limited, limited, cap)
        LabelBulkMessage.NothingNew -> res.getString(R.string.label_bulk_nothing_new)
        LabelBulkMessage.Failed -> res.getString(R.string.label_bulk_failed)
    }
}

/** The Add labels field: the labels already turned into [pills] and the [text] still being typed. */
internal data class LabelPillsInput(val pills: List<String>, val text: String)

/**
 * The Add labels field's rules, kept free of Compose. A comma or the keyboard's Done turns what is
 * typed into a pill; [alreadyHas] is what a single target photo carries, which counts toward the cap
 * and is never offered or added again. A selection of several photos passes nothing and the write
 * reports the photos that are full.
 */
internal object BulkLabelInput {

    /** How many labels the sheet may still add: the cap, less what the photo already carries. */
    fun room(alreadyHas: List<String> = emptyList()): Int =
        (LabelNormalizer.MAX_LABELS - LabelNormalizer.normalize(alreadyHas).labels.size).coerceAtLeast(0)

    /** True once the pills fill the [room] left, which takes the text field away. */
    fun isFull(pills: List<String>, alreadyHas: List<String> = emptyList()): Boolean = pills.size >= room(alreadyHas)

    /**
     * [pills] with the comma-separated labels in [raw] after them, each cleaned. One that is already a
     * pill or already on the photo is skipped quietly, and none goes past the [room] left.
     */
    fun commit(pills: List<String>, raw: String, alreadyHas: List<String> = emptyList()): List<String> {
        val room = room(alreadyHas)
        val seen = (pills + alreadyHas).mapTo(HashSet()) { LabelNormalizer.fold(it) }
        val result = pills.toMutableList()
        for (part in raw.split(LabelNormalizer.SEPARATOR)) {
            if (result.size >= room) break
            val clean = LabelNormalizer.clean(part) ?: continue
            if (seen.add(LabelNormalizer.fold(clean))) result += clean
        }
        return result
    }

    /**
     * The field now reads [text]: what comes before its last comma turns into pills and what follows
     * stays in the field, which empties once the pills are full.
     */
    fun onEdit(pills: List<String>, text: String, alreadyHas: List<String> = emptyList()): LabelPillsInput {
        val edit = LabelEditorInput.onEdit(text)
        val next = edit.commit?.let { commit(pills, it, alreadyHas) } ?: pills
        return LabelPillsInput(next, if (isFull(next, alreadyHas)) "" else edit.remainder)
    }

    /** Backspace on the field: an empty field gives up its last pill; null leaves the key to the field. */
    fun onBackspace(pills: List<String>, text: String): List<String>? =
        if (text.isEmpty() && pills.isNotEmpty()) pills.dropLast(1) else null

    /** [pills] without [label]. */
    fun remove(pills: List<String>, label: String): List<String> {
        val key = LabelNormalizer.fold(label)
        return pills.filterNot { LabelNormalizer.fold(it) == key }
    }

    /** What Add applies: the pills and, as a last one, any text still in the field. */
    fun labelsToAdd(input: LabelPillsInput, alreadyHas: List<String> = emptyList()): List<String> =
        commit(input.pills, input.text, alreadyHas)

    /** True when [edited] is not the same labels as [initial], in the same order, ignoring case and accents. */
    fun changed(initial: List<String>, edited: List<String>): Boolean =
        initial.map { LabelNormalizer.fold(it) } != edited.map { LabelNormalizer.fold(it) }

    /** The used labels to offer: not a pill yet, not on the photo, narrowed by what is [typed]. */
    fun suggestions(
        usage: List<LabelUsage>,
        pills: List<String>,
        typed: String,
        alreadyHas: List<String> = emptyList(),
    ): List<String> = LabelEditorInput.suggestions(usage, pills + alreadyHas, typed)
}

/** Backs the Add labels sheet every selection surface shares: the used labels and the bulk write. */
@HiltViewModel
class AddLabelsViewModel @Inject constructor(
    private val labelRepo: PhotoLabelRepository,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    val usage: StateFlow<List<LabelUsage>> = labelRepo.observeLabelUsage()
        .catch { emit(emptyList()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** True while the labels are saved to or restored from Drive, when they cannot be edited. */
    val locked: StateFlow<Boolean> = labelRepo.observeLocked()
        .catch { emit(false) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** The keys of the photos that carry [label], so a picker can leave them out. Empty when they cannot be read. */
    suspend fun photoKeysOf(label: String): Set<String> = try {
        labelRepo.photoKeysOfLabel(label)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "label photos read failed: ${e.message}")
        emptySet()
    }

    /**
     * Adds [labels] to every photo in [items] under its gallery key, moving a backed-up photo's labels
     * still under its device uri onto its linkId first so the cap counts the photo's whole list.
     */
    fun add(items: List<GalleryItem>, labels: List<String>, onDone: (LabelBulkResult?) -> Unit) {
        viewModelScope.launch {
            val result = try {
                labelRepo.reconcileKeys(items)
                labelRepo.addLabels(items.map { LabelKeyReconciler.keysFor(it).first() }, labels)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "bulk label add failed: ${e.message}")
                null
            }
            if (result != null && result.changedPhotos > 0) {
                runCatching { context.settingsDataStore.edit { it[SettingsKeys.LABELS_NOTE_SEEN] = true } }
            }
            onDone(result)
        }
    }

    /**
     * [item]'s labels as they stand, read under both keys a backed-up photo's labels may sit under, or null
     * when they cannot be read. The single-photo editor opens only on a list it has read, since it writes the
     * whole list back.
     */
    suspend fun labelsOf(item: GalleryItem): List<String>? = try {
        labelRepo.labelsFor(item)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "label read failed: ${e.message}")
        null
    }

    /** Sets [item]'s whole label list to [labels], for the single-photo editor. Reports success. */
    fun replace(item: GalleryItem, labels: List<String>, onDone: (Boolean) -> Unit) {
        viewModelScope.launch {
            val ok = try {
                labelRepo.setLabels(item, labels)
                true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "label write failed: ${e.message}")
                false
            }
            if (ok && labels.isNotEmpty()) {
                runCatching { context.settingsDataStore.edit { it[SettingsKeys.LABELS_NOTE_SEEN] = true } }
            }
            onDone(ok)
        }
    }

    private companion object {
        const val TAG = "AddLabelsVM"
    }
}

/** What the Add labels sheet was opened for: the [items] and, for one photo, the [initial] labels it carries. */
private class AddLabelsRequest(val items: List<GalleryItem>, val initial: List<String>)

/**
 * The Add labels row for a selection drawer over [items], null for an empty selection. The surface
 * decides whether its photos may carry labels at all and leaves this out where they may not. The row
 * opens the shared sheet; once the add lands, [onDone] gets the message to show, and the surface
 * shows it and clears its selection. For a single photo the sheet edits its labels: it opens with what
 * the photo carries as pills that can be taken off or added to, and saves the whole list. With several
 * photos the sheet only adds.
 */
@Composable
fun rememberAddLabelsAction(
    items: List<GalleryItem>,
    onDone: (String) -> Unit,
): SelectionAction? {
    val viewModel: AddLabelsViewModel = hiltViewModel()
    val scope = rememberCoroutineScope()
    val locked by viewModel.locked.collectAsStateWithLifecycle()
    var request by remember { mutableStateOf<AddLabelsRequest?>(null) }
    val res = LocalContext.current.resources
    request?.let { open ->
        val single = open.items.singleOrNull()
        AddLabelsSheet(
            initial = open.initial,
            editing = single != null,
            viewModel = viewModel,
            onDismiss = { request = null },
            onConfirm = { labels ->
                request = null
                if (single != null) {
                    viewModel.replace(single, labels) { ok ->
                        if (!ok) onDone(res.getString(R.string.label_bulk_failed))
                    }
                } else if (open.items.isNotEmpty()) {
                    viewModel.add(open.items, labels) { result -> onDone(labelBulkMessage(result).resolve(res)) }
                }
            },
        )
    }
    if (items.isEmpty()) return null
    return SelectionAction(
        icon = Icons.AutoMirrored.Filled.Label,
        label = stringResource(R.string.label_bulk_action),
        onClick = {
            val single = items.singleOrNull()
            if (locked) {
                onDone(res.getString(R.string.label_locked))
            } else if (single == null) {
                request = AddLabelsRequest(items, emptyList())
            } else {
                scope.launch { viewModel.labelsOf(single)?.let { request = AddLabelsRequest(items, it) } }
            }
        },
    )
}

/**
 * The labels to add as pills in one field, over the labels already in use. A comma or Done turns the
 * typed text into a pill, a suggestion joins as a pill, and Backspace on the empty field takes the last
 * pill back. Built like the app's other text-entry sheets (the new-album and edit-field sheets).
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun AddLabelsSheet(
    initial: List<String>,
    editing: Boolean,
    viewModel: AddLabelsViewModel,
    onDismiss: () -> Unit,
    onConfirm: (List<String>) -> Unit,
) {
    val usage by viewModel.usage.collectAsStateWithLifecycle()
    val appColors = AppColors.current
    var input by remember { mutableStateOf(LabelPillsInput(initial, "")) }
    val full = BulkLabelInput.isFull(input.pills)
    val result = BulkLabelInput.labelsToAdd(input)
    val canConfirm = if (editing) BulkLabelInput.changed(initial, result) else result.isNotEmpty()
    val suggestions = remember(usage, input) {
        BulkLabelInput.suggestions(usage, input.pills, input.text)
    }
    val focus = remember { FocusRequester() }
    val confirm = { if (canConfirm) onConfirm(result) }
    val hint = when {
        full -> pluralStringResource(R.plurals.label_feedback_full, LabelNormalizer.MAX_LABELS, LabelNormalizer.MAX_LABELS)
        editing && input.pills.isNotEmpty() ->
            pluralStringResource(
                R.plurals.label_full_hint, LabelNormalizer.MAX_LABELS, input.pills.size, LabelNormalizer.MAX_LABELS,
            )
        else -> null
    }

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
                .padding(bottom = 36.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                stringResource(R.string.label_bulk_action), color = appColors.fgPrimary,
                fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
            )
            Column {
                LabelPillField(
                    input = input,
                    full = full,
                    placeholder = stringResource(
                        if (input.pills.isEmpty()) R.string.label_bulk_hint else R.string.label_add_hint,
                    ),
                    focus = focus,
                    onText = { input = BulkLabelInput.onEdit(input.pills, it) },
                    onRemove = { label -> input = input.copy(pills = BulkLabelInput.remove(input.pills, label)) },
                    onBackspace = {
                        val next = BulkLabelInput.onBackspace(input.pills, input.text)
                        if (next != null) input = input.copy(pills = next)
                        next != null
                    },
                    onDone = {
                        if (input.text.isBlank()) {
                            defaultKeyboardAction(ImeAction.Done)
                        } else {
                            input = LabelPillsInput(BulkLabelInput.commit(input.pills, input.text), "")
                        }
                    },
                )
                if (hint != null) {
                    Text(
                        hint, color = FgMute, fontSize = 12.sp,
                        modifier = Modifier.padding(top = 6.dp, start = 4.dp),
                    )
                }
            }
            if (!full && suggestions.isNotEmpty()) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    suggestions.forEach { label ->
                        Row(
                            modifier = Modifier
                                .clip(RoundedCornerShape(20.dp))
                                .background(CardBg)
                                .border(0.5.dp, Line2, RoundedCornerShape(20.dp))
                                .clickable {
                                    input = LabelPillsInput(BulkLabelInput.commit(input.pills, label), "")
                                }
                                .padding(horizontal = 12.dp, vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Default.Add, contentDescription = null, tint = FgMute, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(label, color = FgMute, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                        }
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.cancel), color = appColors.fgDim)
                }
                TextButton(
                    enabled = canConfirm,
                    onClick = confirm,
                ) {
                    Text(
                        stringResource(if (editing) R.string.metadata_editor_done else R.string.label_bulk_confirm),
                        color = if (canConfirm) Accent else appColors.fgDim,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
    }
}

/**
 * The pills and the text field in one wrapped area drawn like the sheets' outlined fields, the field
 * flowing on after the last pill. A full set of pills leaves the field out.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LabelPillField(
    input: LabelPillsInput,
    full: Boolean,
    placeholder: String,
    focus: FocusRequester,
    onText: (String) -> Unit,
    onRemove: (String) -> Unit,
    onBackspace: () -> Boolean,
    onDone: KeyboardActionScope.() -> Unit,
) {
    val appColors = AppColors.current
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(14.dp)
    FlowRow(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clip(shape)
            .border(if (focused) 2.dp else 1.dp, if (focused) Accent else Line2, shape)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { runCatching { focus.requestFocus() } }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically),
    ) {
        input.pills.forEach { label ->
            SearchTermPill(
                SearchFieldTerm(
                    label = label,
                    onRemove = { onRemove(label) },
                    removeDescription = stringResource(R.string.label_remove_cd, label),
                ),
            )
        }
        if (!full) {
            Box(
                modifier = Modifier.widthIn(min = 96.dp).weight(1f).height(30.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (input.text.isEmpty()) {
                    Text(placeholder, color = FgMute, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                BasicTextField(
                    value = input.text,
                    onValueChange = onText,
                    singleLine = true,
                    textStyle = TextStyle(color = appColors.fgPrimary, fontSize = 16.sp),
                    cursorBrush = SolidColor(Accent),
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.None,
                        imeAction = ImeAction.Done,
                    ),
                    keyboardActions = KeyboardActions(onDone = onDone),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focus)
                        .onFocusChanged { focused = it.isFocused }
                        .onPreviewKeyEvent { event ->
                            event.type == KeyEventType.KeyDown && event.key == Key.Backspace && onBackspace()
                        },
                )
            }
        }
    }
}
