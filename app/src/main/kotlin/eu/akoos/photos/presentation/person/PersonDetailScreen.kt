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

package eu.akoos.photos.presentation.person

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CallMerge
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.PersonOff
import androidx.compose.material.icons.filled.PersonRemove
import androidx.compose.material.icons.filled.PersonSearch
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import eu.akoos.photos.R
import eu.akoos.photos.domain.entity.GalleryItem
import eu.akoos.photos.presentation.common.ConfirmDialog
import eu.akoos.photos.presentation.common.EditFieldSheet
import eu.akoos.photos.presentation.common.IconBubble
import eu.akoos.photos.presentation.common.floatingHeaderContentTopPadding
import eu.akoos.photos.presentation.gallery.PersonTile
import eu.akoos.photos.presentation.gallery.PersonUi
import eu.akoos.photos.presentation.gallery.PhotoCell
import eu.akoos.photos.presentation.gallery.photoCellInputsFor
import eu.akoos.photos.presentation.people.PersonPickerSheet
import eu.akoos.photos.presentation.common.FloatingHeader
import eu.akoos.photos.presentation.theme.Accent
import eu.akoos.photos.presentation.theme.AppColors
import eu.akoos.photos.presentation.theme.FgMute

/**
 * Full-screen detail for one clustered person: a photo grid over every item they appear in, built
 * from the same [PhotoCell] + [photoCellInputsFor] path the other detail grids use, so a tap opens
 * the viewer with the right synced / cloud state. The floating pill header carries a back button, an
 * add action (once the person is named) that opens a picker to attach missing photos, and an edit
 * action that renames the person. Long-pressing a photo enters selection, where the header switches to
 * a count and a remove action that takes the picked photos off this person for good.
 */
@Composable
fun PersonDetailScreen(
    state: PersonDetailUiState,
    onBack: () -> Unit,
    onOpenPhoto: (GalleryItem) -> Unit,
    onRename: (String) -> Unit,
    onAddPhotos: () -> Unit,
    onRemovePhotos: (Set<String>) -> Unit,
    onMoveSelectionToPerson: (Set<String>, String) -> Unit = { _, _ -> },
    onSetCover: (String) -> Unit = {},
    mergeCandidates: List<PersonUi> = emptyList(),
    onLoadMergeCandidates: () -> Unit = {},
    onMergeName: (String) -> Unit = {},
    onIgnorePerson: () -> Unit = {},
    /** The app's own "might be the same person" prompt, or null when there is nothing to suggest. */
    mergeSuggestion: PersonUi? = null,
    onAcceptSuggestion: (Long) -> Unit = {},
    onDismissSuggestion: (Long) -> Unit = {},
    onLeaveSuggestion: () -> Unit = {},
    /** Opens the sweep that looks for more photos of this person among the ones no face was found on. */
    onFindMore: () -> Unit = {},
) {
    val appColors = AppColors.current
    var showRename by remember { mutableStateOf(false) }
    var showRemoveConfirm by remember { mutableStateOf(false) }
    var showIgnoreConfirm by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var showMergePicker by remember { mutableStateOf(false) }
    var showMoveSelectionPicker by remember { mutableStateOf(false) }
    var pendingCover by remember { mutableStateOf<String?>(null) }
    var pendingMerge by remember { mutableStateOf<PersonUi?>(null) }
    var selection by remember { mutableStateOf(emptySet<String>()) }
    val selecting = selection.isNotEmpty()

    fun toggle(key: String) {
        selection = if (key in selection) selection - key else selection + key
    }

    // Curation can empty a person: its last photos moved to someone else, removed, or marked not a
    // person, and the Unsorted bucket empties the same way once every face is placed. An emptied grid
    // is a dead end once photos have shown, so hand back to the caller's back target the moment it goes
    // empty. A person still loading or opened already empty never pops.
    var hadPhotos by remember { mutableStateOf(false) }
    var leftOnEmpty by remember { mutableStateOf(false) }
    LaunchedEffect(state.isLoading, state.items.isEmpty()) {
        if (state.items.isNotEmpty()) {
            hadPhotos = true
        } else if (hadPhotos && !state.isLoading && !leftOnEmpty) {
            leftOnEmpty = true
            onBack()
        }
    }

    BackHandler(enabled = selecting) { selection = emptySet() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(appColors.pageBg),
    ) {
        when {
            state.isLoading -> CircularProgressIndicator(
                color = Accent,
                modifier = Modifier.align(Alignment.Center),
            )
            state.items.isEmpty() -> Text(
                stringResource(R.string.albums_no_photos),
                color = FgMute,
                fontSize = 14.sp,
                modifier = Modifier.align(Alignment.Center),
            )
            else -> {
                val cols = eu.akoos.photos.presentation.gallery.rememberDefaultGridColumns()
                val seamless = eu.akoos.photos.presentation.gallery.rememberSeamlessGrid()
                LazyVerticalGrid(
                    columns = GridCells.Fixed(cols),
                    // Match the timeline grid: same default columns, 20.dp side inset and 6.dp gap,
                    // so person photos render at the same size as the Photos page. Edge-to-edge drops
                    // the side inset and rounding and tightens the gap. The top clears the floating
                    // header rather than a solid bar.
                    contentPadding = PaddingValues(
                        start = if (seamless) 0.dp else 20.dp,
                        end = if (seamless) 0.dp else 20.dp,
                        top = floatingHeaderContentTopPadding(),
                        bottom = 24.dp,
                    ),
                    horizontalArrangement = Arrangement.spacedBy(if (seamless) 2.dp else 6.dp),
                    verticalArrangement = Arrangement.spacedBy(if (seamless) 2.dp else 6.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    // The Unsorted bucket explains itself at the top of its grid, so it is never mistaken
                    // for a person the app thinks it recognised.
                    if (state.isOther && !selecting) {
                        item(span = { GridItemSpan(maxLineSpan) }, key = "unsorted_hint") {
                            Text(
                                text = stringResource(R.string.person_unsorted_hint),
                                color = appColors.fgDim,
                                fontSize = 13.sp,
                                lineHeight = 18.sp,
                                modifier = Modifier
                                    .padding(horizontal = if (seamless) 20.dp else 0.dp)
                                    .padding(top = 2.dp, bottom = 10.dp),
                            )
                        }
                    }
                    // The app's own merge prompt rides at the top of the grid as a full-width row, so it
                    // scrolls with the photos rather than covering them. Only shown in browse mode.
                    if (mergeSuggestion != null && !selecting) {
                        item(span = { GridItemSpan(maxLineSpan) }, key = "merge_suggestion") {
                            MergeSuggestionBanner(
                                candidate = mergeSuggestion,
                                onMerge = { onAcceptSuggestion(mergeSuggestion.personId) },
                                onDismiss = { onDismissSuggestion(mergeSuggestion.personId) },
                                onLeave = onLeaveSuggestion,
                            )
                        }
                    }
                    items(items = state.items, key = { it.stableId }) { item ->
                        val inputs = remember(item) { photoCellInputsFor(item) }
                        PhotoCell(
                            imageData = inputs.imageData,
                            stableKey = inputs.stableKey,
                            isVideo = inputs.isVideo,
                            isLocalVideo = inputs.isLocalVideo,
                            durationMs = inputs.durationMs,
                            isPlaceholder = inputs.isPlaceholder,
                            selected = item.stableId in selection,
                            isSelectionMode = selecting,
                            showCloudBadge = inputs.showCloudBadge,
                            showSyncedBadge = inputs.showSyncedBadge,
                            isFavorite = inputs.isFavorite,
                            typeBadgeRes = inputs.typeBadgeRes,
                            typeBadgeCdRes = inputs.typeBadgeCdRes,
                            columns = cols,
                            cornerRadius = if (seamless) 0.dp else 10.dp,
                            onClick = { if (selecting) toggle(item.stableId) else onOpenPhoto(item) },
                            onLongClick = { toggle(item.stableId) },
                        )
                    }
                }
            }
        }

        // Floating pill header: browse mode carries add + edit; selection mode carries a count and a
        // remove action, and its back arrow clears the selection.
        if (selecting) {
            FloatingHeader(
                title = stringResource(R.string.album_picker_selected, selection.size),
                onBack = { selection = emptySet() },
                trailing = {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        // Choosing a cover needs exactly one photo picked AND a named person (the cover
                        // is stored against the name); an unnamed cluster has nowhere to keep it, so the
                        // button would silently do nothing there.
                        if (selection.size == 1 && state.personName != null) {
                            IconBubble(
                                icon = Icons.Default.AccountCircle,
                                contentDescription = stringResource(R.string.person_set_cover),
                                onClick = { pendingCover = selection.first() },
                                diameter = 40.dp,
                                iconSize = 18.dp,
                                background = appColors.pillBg,
                                borderColor = appColors.pillBorder,
                                tint = appColors.fgPrimary,
                            )
                        }
                        // Reassign the picked photos to another person, or split them into a new one.
                        IconBubble(
                            icon = Icons.Default.PersonAdd,
                            contentDescription = stringResource(R.string.person_move_to_title),
                            onClick = {
                                onLoadMergeCandidates()
                                showMoveSelectionPicker = true
                            },
                            diameter = 40.dp,
                            iconSize = 16.dp,
                            background = appColors.pillBg,
                            borderColor = appColors.pillBorder,
                            tint = appColors.fgPrimary,
                        )
                        // Removing photos from a person only makes sense for a named one; an unnamed
                        // cluster has no person to keep the photos off of.
                        if (state.personName != null) {
                            IconBubble(
                                icon = Icons.Default.PersonRemove,
                                contentDescription = stringResource(R.string.person_remove_from),
                                onClick = { showRemoveConfirm = true },
                                diameter = 40.dp,
                                iconSize = 16.dp,
                                background = appColors.pillBg,
                                borderColor = appColors.pillBorder,
                                tint = appColors.fgPrimary,
                            )
                        }
                    }
                },
            )
        } else {
            // Show the person's photo tally beside the name so the header states how many photos this holds.
            val photoCount = state.items.size
            val baseTitle = when {
                state.isOther -> stringResource(R.string.person_unsorted)
                else -> state.personName ?: stringResource(R.string.person_detail_unnamed)
            }
            val headerTitle = if (photoCount > 0) {
                baseTitle + " · " + pluralStringResource(
                    R.plurals.count_photos_plural, photoCount, photoCount,
                )
            } else {
                baseTitle
            }
            FloatingHeader(
                title = headerTitle,
                onBack = onBack,
                trailing = {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        // Add is only meaningful once the person is named, since a manual add is stored
                        // against the name so it survives a rescan.
                        if (state.personName != null) {
                            IconBubble(
                                icon = Icons.Default.Add,
                                contentDescription = stringResource(R.string.person_add_photos),
                                onClick = onAddPhotos,
                                diameter = 40.dp,
                                iconSize = 16.dp,
                                background = appColors.pillBg,
                                borderColor = appColors.pillBorder,
                                tint = appColors.fgPrimary,
                            )
                        }
                        // The Unsorted bucket is never renamed (that would turn the junk pile into a
                        // person) and never whole-merged: it is curated by selecting faces to move out or
                        // reject, so neither the rename button nor the overflow is offered for it.
                        if (!state.isOther) {
                            IconBubble(
                                icon = Icons.Default.Edit,
                                contentDescription = stringResource(R.string.person_rename_title),
                                onClick = { showRename = true },
                                diameter = 40.dp,
                                iconSize = 16.dp,
                                background = appColors.pillBg,
                                borderColor = appColors.pillBorder,
                                tint = appColors.fgPrimary,
                            )
                        }
                        if (!state.isOther) Box {
                            IconBubble(
                                icon = Icons.Default.MoreVert,
                                contentDescription = stringResource(R.string.more_options),
                                onClick = { menuOpen = true },
                                diameter = 40.dp,
                                iconSize = 16.dp,
                                background = appColors.pillBg,
                                borderColor = appColors.pillBorder,
                                tint = appColors.fgPrimary,
                            )
                            DropdownMenu(
                                expanded = menuOpen,
                                onDismissRequest = { menuOpen = false },
                                shape = RoundedCornerShape(16.dp),
                                containerColor = appColors.cardBg,
                                border = BorderStroke(0.5.dp, appColors.pillBorder),
                            ) {
                                // Only for a named person: the sweep matches against this person's mean
                                // face, which needs a name to keep what it adds across a rebuild.
                                if (state.personName != null) {
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                stringResource(R.string.find_more),
                                                color = appColors.fgPrimary,
                                            )
                                        },
                                        leadingIcon = {
                                            Icon(
                                                Icons.Default.PersonSearch,
                                                contentDescription = null,
                                                tint = appColors.fgDim,
                                            )
                                        },
                                        onClick = {
                                            menuOpen = false
                                            onFindMore()
                                        },
                                    )
                                }
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            stringResource(R.string.person_merge),
                                            color = appColors.fgPrimary,
                                        )
                                    },
                                    leadingIcon = {
                                        Icon(
                                            Icons.Default.CallMerge,
                                            contentDescription = null,
                                            tint = appColors.fgDim,
                                        )
                                    },
                                    onClick = {
                                        menuOpen = false
                                        onLoadMergeCandidates()
                                        showMergePicker = true
                                    },
                                )
                                // Only for an unnamed cluster: dismissing a whole named, confirmed person
                                // as "not a person" makes no sense. A named person's stray photos are
                                // taken off through "Remove from this person" in selection mode instead.
                                if (state.personName == null) {
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                stringResource(R.string.person_not_a_person),
                                                color = appColors.fgPrimary,
                                            )
                                        },
                                        leadingIcon = {
                                            Icon(
                                                Icons.Default.PersonOff,
                                                contentDescription = null,
                                                tint = appColors.fgDim,
                                            )
                                        },
                                        onClick = {
                                            menuOpen = false
                                            showIgnoreConfirm = true
                                        },
                                    )
                                }
                            }
                        }
                    }
                },
            )
        }
    }

    // Rename as a bottom drawer (the app's shared single-field editor), so it reads like every other
    // sheet rather than a centred dialog.
    if (showRename) {
        EditFieldSheet(
            title = stringResource(R.string.person_rename_title),
            hint = stringResource(R.string.person_rename_hint),
            initialValue = state.personName ?: "",
            singleLine = true,
            confirmLabel = stringResource(R.string.action_save),
            onDismiss = { showRename = false },
            onSave = { onRename(it) },
        )
    }

    if (showRemoveConfirm) {
        ConfirmDialog(
            title = stringResource(R.string.person_remove_confirm_title),
            message = stringResource(R.string.person_remove_confirm_body),
            confirmLabel = stringResource(R.string.person_remove_confirm),
            dismissLabel = stringResource(R.string.cancel),
            destructive = true,
            onConfirm = {
                onRemovePhotos(selection)
                selection = emptySet()
                showRemoveConfirm = false
            },
            onDismiss = { showRemoveConfirm = false },
        )
    }

    pendingCover?.let { key ->
        ConfirmDialog(
            title = stringResource(R.string.person_set_cover_confirm_title),
            message = stringResource(R.string.person_set_cover_confirm_body),
            confirmLabel = stringResource(R.string.person_set_cover),
            dismissLabel = stringResource(R.string.cancel),
            onConfirm = {
                onSetCover(key)
                pendingCover = null
                selection = emptySet()
            },
            onDismiss = { pendingCover = null },
        )
    }

    // Merge: pick another named person to fold THIS person into (that fold confirms first, since it
    // cannot be split back apart). Typing a new name only renames this person, which is reversible, so
    // it commits at once the way a rename does elsewhere in the app.
    if (showMergePicker) {
        PersonPickerSheet(
            title = stringResource(R.string.person_merge_pick_title),
            people = mergeCandidates,
            emptyText = stringResource(R.string.person_merge_empty),
            onPick = { picked ->
                showMergePicker = false
                pendingMerge = picked
            },
            onCreateNew = { name ->
                showMergePicker = false
                onMergeName(name)
            },
            onDismiss = { showMergePicker = false },
        )
    }

    // Move a selection onto another person: pick an existing name to reassign those faces, or type a
    // new name to split them off into a new person.
    if (showMoveSelectionPicker) {
        val toMove = selection
        PersonPickerSheet(
            title = stringResource(R.string.person_move_to_title),
            people = mergeCandidates,
            onPick = { picked ->
                showMoveSelectionPicker = false
                onMoveSelectionToPerson(toMove, picked.displayName ?: "")
                selection = emptySet()
            },
            onCreateNew = { name ->
                showMoveSelectionPicker = false
                onMoveSelectionToPerson(toMove, name)
                selection = emptySet()
            },
            onDismiss = { showMoveSelectionPicker = false },
        )
    }

    pendingMerge?.let { target ->
        ConfirmDialog(
            title = stringResource(R.string.person_merge_confirm_title),
            message = stringResource(R.string.person_merge_confirm_body),
            confirmLabel = stringResource(R.string.person_merge_confirm),
            dismissLabel = stringResource(R.string.cancel),
            onConfirm = {
                target.displayName?.let { onMergeName(it) }
                pendingMerge = null
            },
            onDismiss = { pendingMerge = null },
        )
    }

    if (showIgnoreConfirm) {
        ConfirmDialog(
            title = stringResource(R.string.person_not_a_person_confirm_title),
            message = stringResource(R.string.person_not_a_person_confirm_body),
            confirmLabel = stringResource(R.string.person_not_a_person_confirm),
            dismissLabel = stringResource(R.string.cancel),
            destructive = true,
            onConfirm = {
                showIgnoreConfirm = false
                onIgnorePerson()
            },
            onDismiss = { showIgnoreConfirm = false },
        )
    }
}

/**
 * The "might also be this person" prompt at the top of a named person's grid: the candidate's face and
 * three plain choices. Merge folds them in, Different person records they are not the same (so it is
 * never offered again), and Not now leaves it for next time.
 */
@Composable
private fun MergeSuggestionBanner(
    candidate: PersonUi,
    onMerge: () -> Unit,
    onDismiss: () -> Unit,
    onLeave: () -> Unit,
) {
    val colors = AppColors.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 10.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(colors.surfaceWeak)
            .border(0.5.dp, colors.pillBorder, RoundedCornerShape(18.dp))
            .padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PersonTile(person = candidate, selected = false, onClick = onMerge)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.person_merge_suggest_title),
                    color = colors.fgPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                candidate.displayName?.takeIf { it.isNotBlank() }?.let {
                    Spacer(Modifier.height(2.dp))
                    Text(it, color = colors.fgMute, fontSize = 12.5.sp)
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BannerAction(stringResource(R.string.person_merge_suggest_merge), accent = true, onClick = onMerge)
            BannerAction(stringResource(R.string.person_merge_suggest_dismiss), accent = false, onClick = onDismiss)
            BannerAction(stringResource(R.string.person_merge_suggest_later), accent = false, onClick = onLeave)
        }
    }
}

@Composable
private fun BannerAction(label: String, accent: Boolean, onClick: () -> Unit) {
    val colors = AppColors.current
    Text(
        label,
        color = if (accent) Color.White else colors.fgPrimary,
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(if (accent) colors.accent else colors.surfaceWeak)
            .then(
                if (accent) Modifier
                else Modifier.border(0.5.dp, colors.pillBorder, RoundedCornerShape(999.dp)),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    )
}
