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

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
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
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.PhotoAlbum
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Share
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import eu.akoos.photos.R
import eu.akoos.photos.domain.entity.GalleryItem
import eu.akoos.photos.presentation.common.rememberAddLabelsAction
import eu.akoos.photos.presentation.common.ConfirmDialog
import eu.akoos.photos.presentation.common.EditFieldSheet
import eu.akoos.photos.presentation.common.IconBubble
import eu.akoos.photos.presentation.common.PrimarySlot
import eu.akoos.photos.presentation.common.SelectionAction
import eu.akoos.photos.presentation.common.SelectionDrawer
import eu.akoos.photos.presentation.common.SelectionShareState
import eu.akoos.photos.presentation.common.addToAlbumResultText
import eu.akoos.photos.presentation.gallery.GalleryAddToAlbumDialog
import eu.akoos.photos.presentation.gallery.GalleryMultiDeleteDialog
import eu.akoos.photos.presentation.gallery.GalleryNewAlbumDialog
import eu.akoos.photos.presentation.gallery.selectAllCountFor
import eu.akoos.photos.presentation.gallery.selectionGridBottomPadding
import eu.akoos.photos.presentation.lock.LocalAppLock
import eu.akoos.photos.presentation.lock.runWhenOpen
import eu.akoos.photos.presentation.people.AddToPersonFaceSheet
import eu.akoos.photos.presentation.common.floatingHeaderContentTopPadding
import eu.akoos.photos.presentation.gallery.PersonTile
import eu.akoos.photos.presentation.gallery.PersonUi
import eu.akoos.photos.presentation.gallery.PhotoCell
import eu.akoos.photos.presentation.gallery.photoCellInputsFor
import eu.akoos.photos.presentation.people.NameOrMergeSheet
import eu.akoos.photos.presentation.people.NameOrMergeSubject
import eu.akoos.photos.presentation.people.PersonPickerSheet
import eu.akoos.photos.presentation.people.rememberGroupSubject
import eu.akoos.photos.presentation.common.FloatingHeader
import eu.akoos.photos.presentation.theme.Accent
import eu.akoos.photos.presentation.theme.AppColors
import eu.akoos.photos.presentation.theme.FgMute

/**
 * Full-screen detail for one clustered person: a photo grid over every item they appear in, built
 * from the same [PhotoCell] + [photoCellInputsFor] path the other detail grids use, so a tap opens
 * the viewer with the right synced / cloud state. The floating pill header carries a back button and
 * an overflow menu: a named person is renamed, merged, or given more photos there, and an unnamed group
 * is named or merged into someone through the shared [NameOrMergeSheet], the same sheet every other
 * surface opens for it. Long-pressing a photo enters selection, where the header switches to a count
 * and a remove action that takes the picked photos off this person for good.
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
    /** Drops the selected Unsorted faces from People for good (the "This is not a person" action). */
    onMarkSelectionNotPerson: (Set<String>) -> Unit = {},
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
    /** Opens the suggested candidate's own grid, so the user can look past the tiny face crop before
     *  deciding merge vs. different person. */
    onPreviewCandidate: (Long) -> Unit = {},
    /** Leaves this page once its unnamed group was merged into the person with the given id, so that
     *  person's page takes its place. */
    onMergedInto: (Long) -> Unit = {},
    /** Opens the sweep that looks for more photos of this person among the ones no face was found on. */
    onFindMore: () -> Unit = {},
    viewModel: PersonDetailViewModel = hiltViewModel(),
) {
    val appColors = AppColors.current
    val gridState = rememberLazyGridState()
    // The merge suggestion is the grid's first item and arrives async, so it can insert above the
    // current scroll offset and render clipped at the top edge. When one appears while the grid is at
    // (or near) the top, pull it fully into view; if the user has scrolled down into the photos leave
    // their position alone (the banner is off-screen above anyway).
    LaunchedEffect(mergeSuggestion != null) {
        if (mergeSuggestion != null && gridState.firstVisibleItemIndex <= 1) {
            gridState.animateScrollToItem(0)
        }
    }
    var showRename by remember { mutableStateOf(false) }
    var showRemoveConfirm by remember { mutableStateOf(false) }
    var showIgnoreConfirm by remember { mutableStateOf(false) }
    var showNotPersonConfirm by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var showMergePicker by remember { mutableStateOf(false) }
    var showNameOrMerge by remember { mutableStateOf(false) }
    var showMoveSelectionPicker by remember { mutableStateOf(false) }
    var pendingCover by remember { mutableStateOf<String?>(null) }
    var pendingMerge by remember { mutableStateOf<PersonUi?>(null) }
    // A rename whose name matches another person, and a move of a selection onto an existing person, both
    // reassign faces in a way that is not cleanly undone, so each confirms first (like the merge picker).
    var pendingRenameMerge by remember { mutableStateOf<String?>(null) }
    var pendingMove by remember { mutableStateOf<PersonUi?>(null) }
    // The picked photos live in the shared selection controller, so the drawer's share, add-to-album,
    // add-to-person and delete act on them; the page reads them as stable ids.
    val selectedItems by viewModel.selectedItems.collectAsStateWithLifecycle()
    val selection: Set<String> = remember(selectedItems) { selectedItems.mapTo(HashSet()) { it.stableId } }
    val selecting = selection.isNotEmpty()
    fun setSelection(ids: Set<String>) = viewModel.setSelection(state.items.filter { it.stableId in ids }.toSet())
    fun clearSelection() = viewModel.clearSelection()

    fun toggle(key: String) {
        setSelection(if (key in selection) selection - key else selection + key)
    }
    val isSignedIn by viewModel.isSignedIn.collectAsStateWithLifecycle()
    val isDeleting by viewModel.isDeleting.collectAsStateWithLifecycle()
    val cloudAlbums by viewModel.albums.collectAsStateWithLifecycle()
    val shareState by viewModel.sel.shareState.collectAsStateWithLifecycle()
    val pendingDeleteIntent by viewModel.pendingDeleteIntent.collectAsStateWithLifecycle()
    var showAddToAlbumSheet by remember { mutableStateOf(false) }
    var showCreateAlbumInline by remember { mutableStateOf(false) }
    var showAddToPersonSheet by remember { mutableStateOf(false) }
    var showDeleteSheet by remember { mutableStateOf(false) }

    // Curation can empty a person: its last photos moved to someone else, removed, or marked not a
    // person, and the Unsorted bucket empties the same way once every face is placed. An emptied grid
    // is a dead end once photos have shown, so hand back to the caller's back target the moment it goes
    // empty. A person still loading or opened already empty never pops, and neither does a page that one
    // of its own actions is already taking away, or that the name-or-merge sheet is open over.
    var hadPhotos by remember { mutableStateOf(false) }
    var leaving by remember { mutableStateOf(false) }
    // Load the other named people up front (not only when a picker opens), so renaming this person onto
    // an existing name can be recognised as a merge and confirmed rather than folding the two silently.
    LaunchedEffect(Unit) { onLoadMergeCandidates() }
    LaunchedEffect(state.isLoading, state.items.isEmpty(), showNameOrMerge) {
        if (state.items.isNotEmpty()) hadPhotos = true
        if (leavesEmptiedPage(state.items.isEmpty(), hadPhotos, state.isLoading, leaving, showNameOrMerge)) {
            leaving = true
            onBack()
        }
    }

    BackHandler(enabled = selecting) { clearSelection() }

    // Bulk actions for the current photo selection. They ride in the top header while selecting (the same
    // place the review list puts its whole-cluster actions), instead of a separate bottom bar.
    val context = androidx.compose.ui.platform.LocalContext.current
    val addLabelsAction = rememberAddLabelsAction(
        items = remember(state.items, selection) { state.items.filter { it.stableId in selection } },
        onDone = { msg ->
            android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_SHORT).show()
            clearSelection()
        },
    )
    val personSelectionActions = buildList {
        val sharing = shareState as? SelectionShareState.Working
        add(
            SelectionAction(
                icon = Icons.Default.Share,
                label = stringResource(R.string.sel_label_share),
                working = sharing != null,
                progress = sharing?.let { if (it.total > 0) it.done.toFloat() / it.total else 0f },
                enabled = sharing == null,
                onClick = { viewModel.shareSelected() },
                slot = PrimarySlot.Share,
            ),
        )
        // Add-to-album needs a Drive destination, so it stays behind a signed-in session.
        if (isSignedIn) {
            add(
                SelectionAction(
                    icon = Icons.Default.PhotoAlbum,
                    label = stringResource(R.string.gallery_add_to_album),
                    onClick = { showAddToAlbumSheet = true },
                    slot = PrimarySlot.Organize,
                ),
            )
        }
        // Adding the photos to another person runs on-device, so a guest has it too.
        add(
            SelectionAction(
                icon = Icons.Default.Person,
                label = stringResource(R.string.gallery_add_to_person),
                onClick = { showAddToPersonSheet = true },
            ),
        )
        // Choosing a cover needs exactly one photo picked AND a named person (the cover is stored against
        // the name); an unnamed cluster has nowhere to keep it.
        if (selection.size == 1 && state.personName != null) {
            add(
                SelectionAction(
                    icon = Icons.Default.AccountCircle,
                    label = stringResource(R.string.person_set_cover),
                    onClick = { pendingCover = selection.first() },
                ),
            )
        }
        // Merge the picked photos into another person (an existing one, or a new name to split them off):
        // the photo-level twin of the whole-cluster merge, so it shares that icon and wording rather than
        // reading as a separate "move".
        add(
            SelectionAction(
                icon = Icons.Default.CallMerge,
                label = stringResource(R.string.person_merge_action),
                onClick = {
                    onLoadMergeCandidates()
                    showMoveSelectionPicker = true
                },
            ),
        )
        addLabelsAction?.let(::add)
        // Removing photos only makes sense for a named person; an unnamed cluster has no person to keep
        // the photos off of.
        if (state.personName != null) {
            add(
                SelectionAction(
                    icon = Icons.Default.PersonRemove,
                    label = stringResource(R.string.person_remove_from),
                    onClick = { showRemoveConfirm = true },
                ),
            )
        }
        // Dropping the picked faces from People for good is offered on the Unsorted pile and any unnamed
        // cluster, so junk faces can be rejected in place; a named person keeps "Remove from person".
        if (state.isOther || state.personName == null) {
            add(
                SelectionAction(
                    icon = Icons.Default.PersonOff,
                    label = stringResource(R.string.person_not_a_person),
                    onClick = { showNotPersonConfirm = true },
                ),
            )
        }
        add(
            SelectionAction(
                icon = Icons.Default.DeleteOutline,
                label = stringResource(R.string.sel_label_delete),
                tint = eu.akoos.photos.presentation.theme.ErrorColor,
                enabled = !isDeleting,
                onClick = { showDeleteSheet = true },
                slot = PrimarySlot.Delete,
            ),
        )
    }

    // The system share sheet, once the selection's files are ready; it waits for the app lock.
    val shareChooserTitle = stringResource(R.string.share_chooser_title)
    val appLock = LocalAppLock.current
    LaunchedEffect(Unit) {
        viewModel.shareIntent.collect { intent ->
            appLock.runWhenOpen {
                runCatching { context.startActivity(android.content.Intent.createChooser(intent, shareChooserTitle)) }
            }
        }
    }
    // An action that did not do all it said says so; the message already reads for the user.
    LaunchedEffect(Unit) {
        viewModel.actionFailure.collect {
            android.widget.Toast.makeText(context, it, android.widget.Toast.LENGTH_LONG).show()
        }
    }
    // The system dialog a delete of other apps' files needs (Android 11+), once the controller asks for it.
    val deletePermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) viewModel.onDeletePermissionGranted()
        else viewModel.clearPendingDeleteIntent()
    }
    LaunchedEffect(pendingDeleteIntent) {
        val pi = pendingDeleteIntent ?: return@LaunchedEffect
        appLock.runWhenOpen {
            runCatching {
                deletePermissionLauncher.launch(IntentSenderRequest.Builder(pi.intentSender).build())
            }.onFailure { viewModel.clearPendingDeleteIntent() }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(appColors.pageBg),
    ) {
        when {
            // An action taking the page away empties the grid before the page leaves; until then it
            // reads as working rather than as a person with no photos.
            state.isLoading || (leaving && state.items.isEmpty()) -> CircularProgressIndicator(
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
                // Long-press a photo then drag to sweep a range, the way the timeline and albums select. The
                // hint and merge rows at the top carry no photo key, so they are never part of a sweep.
                val selectableIds = remember(state.items) { state.items.map { it.stableId } }
                val idToIndex = remember(selectableIds) { selectableIds.withIndex().associate { (i, id) -> id to i } }
                // Armed at the long-press anchor so the cell's release-tap does not toggle it straight back off.
                val tapGuard = remember { mutableStateOf(false) }
                val dragSelectModifier = eu.akoos.photos.presentation.gallery.rememberDragMultiSelectModifier(
                    gridState = gridState,
                    items = selectableIds,
                    indexByKey = idToIndex,
                    selected = selection,
                    onSelectionChange = { setSelection(it) },
                    tapGuard = tapGuard,
                )
                LazyVerticalGrid(
                    columns = GridCells.Fixed(cols),
                    state = gridState,
                    // Match the timeline grid: same default columns, 20.dp side inset and 6.dp gap,
                    // so person photos render at the same size as the Photos page. Edge-to-edge drops
                    // the side inset and rounding and tightens the gap. The top clears the floating
                    // header rather than a solid bar.
                    contentPadding = PaddingValues(
                        start = if (seamless) 0.dp else 20.dp,
                        end = if (seamless) 0.dp else 20.dp,
                        top = floatingHeaderContentTopPadding(),
                        bottom = selectionGridBottomPadding(24.dp, selecting),
                    ),
                    horizontalArrangement = Arrangement.spacedBy(if (seamless) 2.dp else 6.dp),
                    verticalArrangement = Arrangement.spacedBy(if (seamless) 2.dp else 6.dp),
                    modifier = Modifier.fillMaxSize().then(dragSelectModifier),
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
                                onPreview = { onPreviewCandidate(mergeSuggestion.personId) },
                                onMerge = { onAcceptSuggestion(mergeSuggestion.personId) },
                                onDismiss = { onDismissSuggestion(mergeSuggestion.personId) },
                                onLeave = onLeaveSuggestion,
                                modifier = Modifier.animateItem(),
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
                            focus = state.focus[item.stableId],
                            onClick = {
                                // Skip the release-tap that follows a long-press select; it would
                                // otherwise toggle the just-anchored photo back off.
                                if (tapGuard.value) tapGuard.value = false
                                else if (selecting) toggle(item.stableId)
                                else onOpenPhoto(item)
                            },
                            // Long-press and drag are handled by the grid-level drag-select.
                            onLongClick = null,
                        )
                    }
                }
            }
        }

        // The picked photos' actions are the shared pull-up drawer, as on the timeline, search and a place's
        // page, with this page's own (cover, merge, remove, not a person) beside them.
        val selectAllCount = selectAllCountFor(total = state.items.size, selected = selection.size)
        SelectionDrawer(
            visible = selecting,
            items = remember(selectedItems) { selectedItems.toList() },
            actions = personSelectionActions,
            onDismiss = { clearSelection() },
            // Scrolling the grid collapses the drawer, so carrying on through the photos needs no pull first.
            contentScrolling = gridState.isScrollInProgress,
            modifier = Modifier.align(Alignment.BottomCenter),
            selectAllCount = selectAllCount,
            onSelectAll = if (selectAllCount != null) {
                { viewModel.selectAllPhotos() }
            } else {
                null
            },
        )

        // Selection puts its bulk actions in the top header (like the review list); browse mode shows the
        // person's own add + edit + overflow there instead. The two never show at once.
        if (selecting) {
            FloatingHeader(
                title = stringResource(R.string.album_picker_selected, selection.size),
                onBack = { clearSelection() },
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
                        // Add photos, name / rename, and the rest all live inside the overflow menu below,
                        // so a long name up top never collides with a row of buttons. The Unsorted bucket
                        // is never renamed or whole-merged and has no manual add, so it shows no overflow.
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
                                // Renames a named person. For an unnamed group it is the one entry that
                                // names it or merges it into someone: the name-or-merge sheet, the same
                                // one every other surface opens for a nameless group.
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            if (state.personName == null) {
                                                stringResource(R.string.person_add_name)
                                            } else {
                                                stringResource(R.string.person_rename_title)
                                            },
                                            color = appColors.fgPrimary,
                                        )
                                    },
                                    leadingIcon = {
                                        Icon(
                                            Icons.Default.Edit,
                                            contentDescription = null,
                                            tint = appColors.fgDim,
                                        )
                                    },
                                    onClick = {
                                        menuOpen = false
                                        if (state.personName == null) {
                                            // Re-reads the group's face tile, the face the sheet shows.
                                            onLoadMergeCandidates()
                                            showNameOrMerge = state.personId != null
                                        } else {
                                            showRename = true
                                        }
                                    },
                                )
                                // Add photos: named only, since a manual add is stored against the name so
                                // it survives a rescan.
                                if (state.personName != null) {
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                stringResource(R.string.person_add_photos),
                                                color = appColors.fgPrimary,
                                            )
                                        },
                                        leadingIcon = {
                                            Icon(
                                                Icons.Default.Add,
                                                contentDescription = null,
                                                tint = appColors.fgDim,
                                            )
                                        },
                                        onClick = {
                                            menuOpen = false
                                            onAddPhotos()
                                        },
                                    )
                                }
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
                                // Named only: an unnamed group merges through the name-or-merge sheet.
                                if (state.personName != null) {
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
                                }
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
            onSave = { entered ->
                // Renaming onto a name another person holds is a merge (same name = same person), so it
                // confirms first instead of silently folding two people together. A new name just renames.
                val trimmed = entered.trim()
                if (trimmed.isNotEmpty() && mergeCandidates.any { it.displayName == trimmed }) {
                    pendingRenameMerge = trimmed
                } else {
                    onRename(entered)
                }
            },
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
                clearSelection()
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
                clearSelection()
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

    // An unnamed group is named, or merged into someone, through the shared sheet. It asks about the
    // group's face as the people lists draw it (a person glyph until that is read) with the photo count
    // the header shows. "See photos" just closes it, since this page is those photos. A new name keeps
    // the group's id and the page follows it in place; a merge leaves no group behind, so the person it
    // joined takes this page's place.
    val groupId = state.personId
    if (showNameOrMerge && groupId != null) {
        val tile = state.tile
        val subject = if (tile != null) {
            rememberGroupSubject(tile.copy(faceCount = state.items.size))
        } else {
            NameOrMergeSubject.Group(
                personId = groupId,
                photoCount = state.items.size,
                coverSource = null,
                faceBox = null,
            )
        }
        NameOrMergeSheet(
            subject = subject,
            onDismiss = { showNameOrMerge = false },
            onDone = { resultId ->
                val mergedInto = pageAfterGroupNamed(groupId, resultId)
                if (mergedInto != null && !leaving) {
                    leaving = true
                    onMergedInto(mergedInto)
                }
            },
        )
    }

    // Bulk delete reuses the gallery's dialog, so the options and the wording stay the same.
    if (showDeleteSheet && selectedItems.isNotEmpty()) {
        GalleryMultiDeleteDialog(
            selectedItems = selectedItems,
            onDismiss = { showDeleteSheet = false },
            onDelete = { freeUpSpace, deleteFromCloud ->
                showDeleteSheet = false
                viewModel.deleteSelected(freeUpSpace, deleteFromCloud)
            },
        )
    }
    // Add to album reuses the gallery's picker: cloud-backed photos join now, device-only ones upload first.
    if (showAddToAlbumSheet && selectedItems.isNotEmpty()) {
        GalleryAddToAlbumDialog(
            selectedItems = selectedItems,
            cloudAlbums = cloudAlbums,
            onCreateNew = {
                showAddToAlbumSheet = false
                showCreateAlbumInline = true
            },
            onCloudAlbumSelected = { album ->
                showAddToAlbumSheet = false
                viewModel.addSelectedToAlbum(album.linkId) { joined, queued ->
                    android.widget.Toast.makeText(
                        context, addToAlbumResultText(context.resources, joined, queued, album.name),
                        android.widget.Toast.LENGTH_SHORT,
                    ).show()
                }
            },
            onDismiss = { showAddToAlbumSheet = false },
        )
    }
    if (showCreateAlbumInline) {
        GalleryNewAlbumDialog(
            onDismiss = { showCreateAlbumInline = false },
            onCreate = { name ->
                showCreateAlbumInline = false
                viewModel.createAlbumThenAddSelected(name) { joined, queued, error ->
                    android.widget.Toast.makeText(
                        context, error ?: addToAlbumResultText(context.resources, joined, queued, name),
                        android.widget.Toast.LENGTH_SHORT,
                    ).show()
                }
            },
        )
    }
    // Add to person runs the same face-first flow as the timeline, on-device.
    if (showAddToPersonSheet && selectedItems.isNotEmpty()) {
        val personPhotos = remember(selectedItems) { selectedItems.toList() }
        AddToPersonFaceSheet(
            photos = personPhotos,
            onDone = {
                showAddToPersonSheet = false
                clearSelection()
            },
            onDismiss = { showAddToPersonSheet = false },
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
                pendingMove = picked
            },
            onCreateNew = { name ->
                showMoveSelectionPicker = false
                onMoveSelectionToPerson(toMove, name)
                clearSelection()
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

    pendingRenameMerge?.let { name ->
        ConfirmDialog(
            title = stringResource(R.string.person_rename_merge_confirm_title),
            message = stringResource(R.string.person_rename_merge_confirm_body, name),
            confirmLabel = stringResource(R.string.person_merge_confirm),
            dismissLabel = stringResource(R.string.cancel),
            onConfirm = {
                // The merge empties this grid and then leaves the page by itself, so the emptied grid
                // must not leave it a second time.
                leaving = true
                onRename(name)
                pendingRenameMerge = null
            },
            onDismiss = { pendingRenameMerge = null },
        )
    }

    pendingMove?.let { target ->
        ConfirmDialog(
            title = stringResource(R.string.person_move_confirm_title),
            message = stringResource(R.string.person_move_confirm_body, target.displayName ?: ""),
            confirmLabel = stringResource(R.string.person_move_confirm),
            dismissLabel = stringResource(R.string.cancel),
            onConfirm = {
                onMoveSelectionToPerson(selection, target.displayName ?: "")
                clearSelection()
                pendingMove = null
            },
            onDismiss = { pendingMove = null },
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
                // Dismissing the group empties this grid and then leaves the page by itself once the
                // whole dismissal is written, so the emptied grid must not leave it first.
                leaving = true
                onIgnorePerson()
            },
            onDismiss = { showIgnoreConfirm = false },
        )
    }

    // The selection-mode "This is not a person" for the Unsorted bucket or an unnamed cluster: drops just
    // the picked faces, sharing the same confirm copy as the whole-cluster dismissal above.
    if (showNotPersonConfirm) {
        ConfirmDialog(
            title = stringResource(R.string.person_not_a_person_confirm_title),
            message = stringResource(R.string.person_not_a_person_confirm_body),
            confirmLabel = stringResource(R.string.person_not_a_person_confirm),
            dismissLabel = stringResource(R.string.cancel),
            destructive = true,
            onConfirm = {
                onMarkSelectionNotPerson(selection)
                clearSelection()
                showNotPersonConfirm = false
            },
            onDismiss = { showNotPersonConfirm = false },
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
    onPreview: () -> Unit,
    onMerge: () -> Unit,
    onDismiss: () -> Unit,
    onLeave: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = AppColors.current
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = 12.dp)
            // A soft lift so the prompt reads as a card resting on the grid, not a flat inset panel.
            .shadow(6.dp, RoundedCornerShape(22.dp), clip = false)
            .clip(RoundedCornerShape(22.dp))
            .background(colors.cardBg)
            .border(0.5.dp, colors.cardBorder, RoundedCornerShape(22.dp))
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // Tapping the face opens the candidate's grid to look closer, rather than merging on a
            // single tap of a tiny crop; the explicit Merge button below is the merge action.
            PersonTile(person = candidate, selected = false, onClick = onPreview)
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.person_merge_suggest_title),
                    color = colors.fgPrimary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    lineHeight = 19.sp,
                )
                candidate.displayName?.takeIf { it.isNotBlank() }?.let {
                    Spacer(Modifier.height(3.dp))
                    Text(it, color = colors.fgMute, fontSize = 12.5.sp)
                }
            }
        }
        Spacer(Modifier.height(14.dp))
        // The two real decisions share the width evenly; "Different person" needs the room, so it is never
        // squeezed into a third next to "Merge".
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            BannerAction(
                stringResource(R.string.person_merge_suggest_merge),
                accent = true,
                onClick = onMerge,
                modifier = Modifier.weight(1f),
            )
            BannerAction(
                stringResource(R.string.person_merge_suggest_dismiss),
                accent = false,
                onClick = onDismiss,
                modifier = Modifier.weight(1f),
            )
        }
        Spacer(Modifier.height(8.dp))
        // "Not now" is the low-stakes escape, so it sits below as a quiet full-width action rather than
        // competing with the two decisions above.
        Text(
            stringResource(R.string.person_merge_suggest_later),
            color = colors.fgMute,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(999.dp))
                .clickable(onClick = onLeave)
                .padding(vertical = 8.dp),
        )
    }
}

@Composable
private fun BannerAction(
    label: String,
    accent: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = AppColors.current
    Text(
        label,
        color = if (accent) Color.White else colors.fgPrimary,
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        textAlign = TextAlign.Center,
        maxLines = 1,
        modifier = modifier
            .clip(RoundedCornerShape(999.dp))
            .background(if (accent) colors.accent else colors.pillBg)
            .then(
                if (accent) Modifier
                else Modifier.border(0.5.dp, colors.pillBorder, RoundedCornerShape(999.dp)),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    )
}

/**
 * Whether the page hands back to its caller because its grid is empty: only once photos have shown and
 * the load has settled, and not when the page is [leaving] already through an action that navigates
 * away by itself, nor while the name-or-merge sheet is open over it ([sheetOpen]), whose merge empties
 * the grid on the way to opening the person the group joined.
 */
internal fun leavesEmptiedPage(
    isEmpty: Boolean,
    hadPhotos: Boolean,
    isLoading: Boolean,
    leaving: Boolean,
    sheetOpen: Boolean,
): Boolean = isEmpty && hadPhotos && !isLoading && !leaving && !sheetOpen

/**
 * The person whose page replaces this one once the name-or-merge sheet has placed the page's group
 * ([pagePersonId]) with [resultPersonId]: that person when the group was merged into them, or null when
 * the group kept its own id (it was only named), so the page stays.
 */
internal fun pageAfterGroupNamed(pagePersonId: Long, resultPersonId: Long): Long? =
    resultPersonId.takeIf { it >= 0L && it != pagePersonId }

/**
 * Whether a group's page, left for the person it was merged into ([targetPersonId]), opens that person's
 * page: not when the page it goes back to is theirs already ([revealedPersonId], null when that page is
 * not a person's), as when the group was opened from that person's merge suggestion.
 */
internal fun opensMergedPerson(revealedPersonId: Long?, targetPersonId: Long): Boolean =
    revealedPersonId != targetPersonId
