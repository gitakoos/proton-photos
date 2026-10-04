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

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material3.IconButton
import androidx.compose.runtime.LaunchedEffect
import eu.akoos.photos.presentation.common.SearchFieldTerm
import eu.akoos.photos.presentation.common.SearchTermPill
import eu.akoos.photos.presentation.settings.components.ActionRow
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import eu.akoos.photos.R
import eu.akoos.photos.data.db.dao.LabelUsage
import eu.akoos.photos.presentation.common.ActionSheetRow
import eu.akoos.photos.presentation.common.AppSearchField
import eu.akoos.photos.presentation.common.ConfirmDialog
import eu.akoos.photos.presentation.common.ConfirmSheet
import eu.akoos.photos.presentation.common.FloatingHeader
import eu.akoos.photos.presentation.common.floatingHeaderContentTopPadding
import eu.akoos.photos.presentation.settings.components.RowDivider
import eu.akoos.photos.presentation.settings.components.SectionLabel
import eu.akoos.photos.presentation.theme.Accent
import eu.akoos.photos.presentation.theme.AppColors
import eu.akoos.photos.presentation.theme.ErrorColor
import eu.akoos.photos.presentation.theme.FgMute
import eu.akoos.photos.presentation.theme.Line2
import eu.akoos.photos.presentation.theme.SheetBg
import eu.akoos.photos.util.LabelNormalizer

/** The manager list's order: most used first, then by the folded name, then by spelling. */
internal fun sortLabelsForManager(usage: List<LabelUsage>): List<LabelUsage> =
    usage.sortedWith(
        compareByDescending<LabelUsage> { it.count }.thenBy { it.labelKey }.thenBy { it.label },
    )

/** The labels whose name holds the [query], ignoring case and accents; every label for a blank query. */
internal fun filterLabelsForManager(usage: List<LabelUsage>, query: String): List<LabelUsage> {
    val key = LabelNormalizer.fold(query.trim())
    return if (key.isEmpty()) usage else usage.filter { key in it.labelKey }
}

/**
 * The label the [typed] text would create: its cleaned form, or null when it is no valid label or the account
 * already has a label of that name, whatever its case or accents, among [usage].
 */
internal fun labelToCreate(typed: String, usage: List<LabelUsage>): String? {
    val clean = LabelNormalizer.clean(typed) ?: return null
    val key = LabelNormalizer.fold(clean)
    return clean.takeIf { usage.none { it.labelKey == key } }
}

/** What saving the rename field would do to the label being edited. */
internal sealed interface LabelRenamePlan {
    /** Nothing to save: the field is blank or still holds the current spelling. */
    data object None : LabelRenamePlan

    /** The label takes the name [to], a new one or a new spelling of itself. */
    data class Rename(val to: String) : LabelRenamePlan

    /** [to] names the existing label [into], so the two become one. */
    data class Merge(val to: String, val into: LabelUsage) : LabelRenamePlan
}

/** The plan for renaming [current] to the [typed] text, against the account's other labels in [usage]. */
internal fun labelRenamePlan(current: LabelUsage, typed: String, usage: List<LabelUsage>): LabelRenamePlan {
    val clean = LabelNormalizer.clean(typed) ?: return LabelRenamePlan.None
    if (clean == current.label) return LabelRenamePlan.None
    val key = LabelNormalizer.fold(clean)
    if (key == current.labelKey) return LabelRenamePlan.Rename(clean)
    val existing = usage.firstOrNull { it.labelKey == key }
    return if (existing != null) LabelRenamePlan.Merge(clean, existing) else LabelRenamePlan.Rename(clean)
}

/** The rename field's text: no separator, since one label cannot hold one, and at most [LabelNormalizer.MAX_LENGTH] characters. */
internal fun renameFieldText(text: String): String {
    val plain = text.replace(LabelNormalizer.SEPARATOR.toString(), "")
    return if (plain.codePointCount(0, plain.length) <= LabelNormalizer.MAX_LENGTH) plain
    else plain.substring(0, plain.offsetByCodePoints(0, LabelNormalizer.MAX_LENGTH))
}

/**
 * Settings, Labels: one card holding the Labels heading, an input that searches the labels and, for a name
 * nobody has yet, offers to create it (as the person picker does), and the labels with the number of photos each
 * is on, scrolling inside the card. Tapping a label opens the sheet that renames, merges, deletes it or adds photos
 * to it ([onAddPhotos] opens the photo picker for it). The Drive section sits below the card at the foot of the
 * page. While a save or restore runs, the labels cannot be edited.
 */
@Composable
fun LabelsSettingsScreen(
    onBack: () -> Unit,
    onAddPhotos: (List<String>) -> Unit,
    viewModel: LabelsSettingsViewModel = hiltViewModel(),
) {
    val labels by viewModel.labels.collectAsStateWithLifecycle()
    val locked by viewModel.locked.collectAsStateWithLifecycle()
    val colors = AppColors.current
    val context = LocalContext.current
    var editing by remember { mutableStateOf<LabelUsage?>(null) }
    var deleting by remember { mutableStateOf<LabelUsage?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    var picked by rememberSaveable { mutableStateOf(listOf<String>()) }
    var deletingPicked by remember { mutableStateOf(false) }
    val selecting = picked.isNotEmpty()
    val shown = remember(labels, query) { labels?.let { filterLabelsForManager(it, query) } }
    // Creating is left out while labels are picked, so the list only offers what can be picked.
    val toCreate = remember(labels, query, selecting) { if (selecting) null else labels?.let { labelToCreate(query, it) } }
    // A picked label that is deleted or renamed meanwhile leaves the selection.
    LaunchedEffect(labels) { labels?.let { all -> picked = prunedLabelSelection(picked, all) } }
    // Back, by the header or by the system, asks before it drops a selection.
    var confirmLeave by remember { mutableStateOf(false) }
    BackHandler(enabled = selecting) { confirmLeave = true }
    val whenLocked = { Toast.makeText(context, R.string.label_locked, Toast.LENGTH_SHORT).show() }
    val create: (String) -> Unit = { text ->
        if (locked) whenLocked() else viewModel.create(text) { result -> if (result != null) query = "" }
    }

    Box(modifier = Modifier.fillMaxSize().background(colors.pageBg)) {
        val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(start = 20.dp, end = 20.dp, top = floatingHeaderContentTopPadding(), bottom = 16.dp + navBottom),
        ) {
            LabelsCard(
                labels = shown,
                query = query,
                onQueryChange = { query = renameFieldText(it) },
                toCreate = toCreate,
                locked = locked,
                onCreate = create,
                picked = picked,
                onClick = { usage ->
                    when {
                        locked -> whenLocked()
                        selecting -> picked = toggledLabelSelection(picked, usage.labelKey)
                        else -> editing = usage
                    }
                },
                onLongClick = { usage ->
                    if (locked) whenLocked() else picked = toggledLabelSelection(picked, usage.labelKey)
                },
                modifier = Modifier.weight(1f),
            )
            if (selecting) {
                LabelSelectionPanel(
                    labels = selectedLabels(picked, labels.orEmpty()),
                    locked = locked,
                    onDeselect = { key -> picked = toggledLabelSelection(picked, key) },
                    onAddPhotos = {
                        val names = selectedLabels(picked, labels.orEmpty()).map { it.label }
                        picked = emptyList()
                        onAddPhotos(names)
                    },
                    onDelete = { deletingPicked = true },
                    onClose = { picked = emptyList() },
                )
            } else {
                LabelBackupSection(viewModel)
            }
        }

        FloatingHeader(
            title = stringResource(R.string.label_section_title),
            onBack = { if (selecting) confirmLeave = true else onBack() },
        )
    }

    editing?.let { usage ->
        RenameLabelSheet(
            current = usage,
            usage = labels.orEmpty(),
            locked = locked,
            onDismiss = { editing = null },
            onSave = { to ->
                editing = null
                viewModel.rename(usage.label, to)
            },
            onDelete = {
                editing = null
                deleting = usage
            },
            onAddPhotos = {
                editing = null
                onAddPhotos(listOf(usage.label))
            },
        )
    }

    if (confirmLeave) {
        ConfirmSheet(
            title = stringResource(R.string.label_leave_selection_title),
            message = stringResource(R.string.label_leave_selection_message),
            confirmLabel = stringResource(R.string.label_leave_selection_confirm),
            dismissLabel = stringResource(R.string.cancel),
            onConfirm = {
                confirmLeave = false
                picked = emptyList()
                onBack()
            },
            onDismiss = { confirmLeave = false },
        )
    }

    if (deletingPicked) {
        ConfirmDialog(
            title = stringResource(R.string.label_delete_selected_title),
            message = stringResource(R.string.label_delete_selected_message),
            confirmLabel = stringResource(R.string.label_delete_confirm),
            dismissLabel = stringResource(R.string.cancel),
            onConfirm = {
                deletingPicked = false
                viewModel.deleteMany(selectedLabels(picked, labels.orEmpty()).map { it.label })
                picked = emptyList()
            },
            onDismiss = { deletingPicked = false },
            destructive = true,
        )
    }

    deleting?.let { usage ->
        ConfirmDialog(
            title = pluralStringResource(R.plurals.label_delete_confirm_title, usage.count, usage.count),
            message = null,
            confirmLabel = stringResource(R.string.label_delete_confirm),
            dismissLabel = stringResource(R.string.cancel),
            onConfirm = {
                deleting = null
                viewModel.delete(usage.label)
            },
            onDismiss = { deleting = null },
            destructive = true,
        )
    }
}

/**
 * The card: heading, the always-visible input, then the list. [labels] is null until read. The input's Done key
 * and the Create row do the same, and a name that already exists shows as its own row instead.
 */
@Composable
private fun LabelsCard(
    labels: List<LabelUsage>?,
    query: String,
    onQueryChange: (String) -> Unit,
    toCreate: String?,
    locked: Boolean,
    onCreate: (String) -> Unit,
    picked: List<String>,
    onClick: (LabelUsage) -> Unit,
    onLongClick: (LabelUsage) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = AppColors.current
    val shape = RoundedCornerShape(12.dp)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(colors.cardBg, shape)
            .border(0.5.dp, colors.cardBorder, shape),
    ) {
        Box(modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 10.dp)) {
            SectionLabel(stringResource(R.string.label_section_title))
        }
        AppSearchField(
            query = query,
            onQueryChange = onQueryChange,
            placeholder = stringResource(R.string.label_input_hint),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            // The keyboard's Done key creates the typed label the way the Create row does, then closes the keyboard.
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = {
                toCreate?.let(onCreate)
                defaultKeyboardAction(ImeAction.Done)
            }),
        )
        Spacer(Modifier.height(10.dp))
        RowDivider()
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            when {
                labels == null -> CircularProgressIndicator(
                    color = Accent, strokeWidth = 2.dp, modifier = Modifier.align(Alignment.Center),
                )
                labels.isEmpty() && toCreate == null -> Text(
                    stringResource(R.string.label_settings_empty),
                    color = colors.fgMute,
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.align(Alignment.Center).padding(horizontal = 24.dp),
                )
                else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                    if (toCreate != null) {
                        item(key = "create") {
                            CreateRow(name = toCreate, locked = locked, onClick = { onCreate(toCreate) })
                            if (labels.isNotEmpty()) RowDivider()
                        }
                    }
                    items(labels, key = { it.labelKey }) { usage ->
                        LabelRow(
                            usage = usage,
                            locked = locked,
                            selecting = picked.isNotEmpty(),
                            selected = usage.labelKey in picked,
                            onClick = { onClick(usage) },
                            onLongClick = { onLongClick(usage) },
                        )
                        if (usage !== labels.last()) RowDivider()
                    }
                }
            }
        }
    }
}

/** Offered for a typed name nobody has yet; a name that exists shows as its own row instead. */
@Composable
private fun CreateRow(name: String, locked: Boolean, onClick: () -> Unit) {
    val colors = AppColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (locked) 0.5f else 1f)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.size(36.dp).clip(CircleShape).background(colors.surfaceWeak),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Default.Add, contentDescription = null, tint = colors.accent)
        }
        Text(
            stringResource(R.string.label_create_row, name),
            color = colors.fgPrimary,
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 12.dp),
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LabelRow(
    usage: LabelUsage,
    locked: Boolean,
    selecting: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val colors = AppColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (locked) 0.5f else 1f)
            .background(if (selected) colors.accent.copy(alpha = 0.12f) else Color.Transparent)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 16.dp, vertical = 15.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selecting) {
            Icon(
                if (selected) Icons.Filled.CheckCircle else Icons.Outlined.RadioButtonUnchecked,
                contentDescription = null,
                tint = if (selected) colors.accent else colors.fgMute,
                modifier = Modifier.size(22.dp),
            )
            Spacer(Modifier.width(12.dp))
        }
        Text(
            usage.label,
            color = colors.fgPrimary,
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(12.dp))
        Text(
            if (usage.count > 0) {
                pluralStringResource(R.plurals.count_photos_plural, usage.count, usage.count)
            } else {
                stringResource(R.string.label_no_photos)
            },
            color = colors.fgMute,
            fontSize = 12.5.sp,
        )
    }
}

/**
 * The panel that takes the Drive section's place while labels are picked: the picked labels as pills in one field
 * (a tap on a pill takes that label out of the selection), then Add photos and Delete labels for all of them, and a
 * close at the end of the field. It sits under the list rather than over it, so the list stays in reach to pick
 * more. The pills scroll when many are picked.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LabelSelectionPanel(
    labels: List<LabelUsage>,
    locked: Boolean,
    onDeselect: (String) -> Unit,
    onAddPhotos: () -> Unit,
    onDelete: () -> Unit,
    onClose: () -> Unit,
) {
    val colors = AppColors.current
    val shape = RoundedCornerShape(12.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp)
            .background(colors.cardBg, shape)
            .border(0.5.dp, colors.cardBorder, shape),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 10.dp, bottom = 6.dp),
            verticalAlignment = Alignment.Top,
        ) {
            FlowRow(
                modifier = Modifier.weight(1f).heightIn(max = 112.dp).verticalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                labels.forEach { usage ->
                    SearchTermPill(
                        SearchFieldTerm(
                            label = usage.label,
                            onRemove = { onDeselect(usage.labelKey) },
                            removeDescription = stringResource(R.string.label_remove_cd, usage.label),
                        ),
                    )
                }
            }
            IconButton(onClick = onClose) {
                Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.close), tint = colors.fgDim)
            }
        }
        RowDivider()
        ActionRow(
            label = stringResource(R.string.label_add_photos),
            icon = Icons.Default.AddPhotoAlternate,
            onClick = onAddPhotos,
            enabled = !locked,
        )
        RowDivider()
        ActionRow(
            label = stringResource(R.string.label_delete_selected),
            icon = Icons.Default.Delete,
            onClick = onDelete,
            destructive = true,
            enabled = !locked,
        )
    }
}

/**
 * Renames [current], which also merges and deletes it, or adds photos to it. A name another label already has
 * merges the two, which the sheet says before the save; Delete hands over to the confirmation that names how many
 * photos lose the label. While the labels are [locked] none of it can be done.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RenameLabelSheet(
    current: LabelUsage,
    usage: List<LabelUsage>,
    locked: Boolean,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
    onDelete: () -> Unit,
    onAddPhotos: () -> Unit,
) {
    val colors = AppColors.current
    var field by remember(current.labelKey) {
        mutableStateOf(TextFieldValue(current.label, TextRange(current.label.length)))
    }
    val plan = remember(field.text, usage) { labelRenamePlan(current, field.text, usage) }
    val canSave = plan != LabelRenamePlan.None && !locked
    val save = {
        when (val p = plan) {
            is LabelRenamePlan.Rename -> onSave(p.to)
            is LabelRenamePlan.Merge -> onSave(p.to)
            LabelRenamePlan.None -> Unit
        }
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
                stringResource(R.string.label_rename_title), color = colors.fgPrimary,
                fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
            )
            OutlinedTextField(
                value = field,
                onValueChange = { value ->
                    val text = renameFieldText(value.text)
                    field = if (text == value.text) value else TextFieldValue(text, TextRange(text.length))
                },
                singleLine = true,
                enabled = !locked,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor   = Accent,
                    unfocusedBorderColor = Line2,
                    focusedTextColor     = colors.fgPrimary,
                    unfocusedTextColor   = colors.fgPrimary,
                    cursorColor          = Accent,
                ),
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.None,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(onDone = { if (canSave) save() }),
            )
            val merge = plan as? LabelRenamePlan.Merge
            if (merge != null) {
                Text(
                    stringResource(R.string.label_rename_merge_note, merge.into.label),
                    color = FgMute, fontSize = 12.5.sp, lineHeight = 17.sp,
                )
            }
            ActionSheetRow(
                icon = Icons.Default.AddPhotoAlternate,
                title = stringResource(R.string.label_add_photos),
                onClick = onAddPhotos,
                enabled = !locked,
            )
            if (locked) {
                Text(stringResource(R.string.label_locked), color = FgMute, fontSize = 12.5.sp, lineHeight = 17.sp)
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(enabled = !locked, onClick = onDelete) {
                    Text(
                        stringResource(R.string.label_delete_action),
                        color = if (locked) colors.fgDim else ErrorColor,
                    )
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.cancel), color = colors.fgDim)
                }
                TextButton(enabled = canSave, onClick = save) {
                    Text(
                        stringResource(if (merge != null) R.string.label_rename_merge else R.string.label_rename_save),
                        color = if (canSave) Accent else colors.fgDim,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
    }
}
