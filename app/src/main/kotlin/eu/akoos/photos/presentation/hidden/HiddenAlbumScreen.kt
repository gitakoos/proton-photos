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

package eu.akoos.photos.presentation.hidden

import android.app.KeyguardManager
import androidx.activity.compose.BackHandler
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import eu.akoos.photos.R
import eu.akoos.photos.presentation.common.CloudPhotoCell
import eu.akoos.photos.presentation.common.ConfirmSheet
import eu.akoos.photos.presentation.common.IconBubble
import eu.akoos.photos.presentation.common.ReturnToViewerPhoto
import eu.akoos.photos.presentation.common.SecureScreenEffect
import eu.akoos.photos.presentation.common.SelectionAction
import eu.akoos.photos.presentation.common.SelectionCheckPop
import eu.akoos.photos.presentation.common.SelectionDrawer
import eu.akoos.photos.presentation.common.floatingHeaderContentTopPadding
import eu.akoos.photos.presentation.common.selectPressScale
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import eu.akoos.photos.presentation.util.findFragmentActivity
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import coil.request.CachePolicy
import coil.request.ImageRequest
import eu.akoos.photos.domain.entity.Album
import eu.akoos.photos.domain.entity.CloudPhoto
import eu.akoos.photos.domain.entity.GalleryItem
import eu.akoos.photos.domain.entity.LocalMediaItem
import eu.akoos.photos.presentation.albums.AlbumCloudBadge
import eu.akoos.photos.presentation.albums.DeviceFolder
import eu.akoos.photos.presentation.albums.UnifiedAlbumCard
import eu.akoos.photos.presentation.theme.Accent
import eu.akoos.photos.presentation.theme.Bg0
import eu.akoos.photos.presentation.theme.Bg2
import eu.akoos.photos.presentation.theme.ErrorColor
import eu.akoos.photos.presentation.theme.FgDim
import eu.akoos.photos.presentation.theme.FgMute
import eu.akoos.photos.presentation.theme.FgPrimary
import eu.akoos.photos.presentation.theme.PillBg
import eu.akoos.photos.presentation.theme.PillBgOpaque
import eu.akoos.photos.presentation.theme.PillBorder

@Composable
fun HiddenAlbumScreen(
    onBack: () -> Unit,
    onPhotoClick: (items: List<LocalMediaItem>, index: Int) -> Unit = { _, _ -> },
    onCloudPhotoClick: (items: List<GalleryItem>, index: Int) -> Unit = { _, _ -> },
    onOpenAlbum: (Album) -> Unit = {},
    onOpenFolder: (bucketName: String) -> Unit = {},
    viewModel: HiddenAlbumViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    SecureScreenEffect()

    // In selection mode the back button cancels the selection instead of leaving the screen —
    // mirrors the gallery, album-detail and device-folder behaviour.
    BackHandler(enabled = state.isSelectionMode) { viewModel.clearSelection() }

    // When the device has no screen lock at all, the vault opens ungated (there is nothing
    // to authenticate against). Surface that on the lock screen so the user understands the
    // hidden area isn't actually protected on this device, rather than assuming biometrics
    // silently passed.
    val deviceHasNoLock = remember {
        context.getSystemService(KeyguardManager::class.java)?.isDeviceSecure != true
    }

    LaunchedEffect(state.error) {
        state.error?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearError()
        }
    }

    // Trigger biometric on first composition. Guarded by `promptShown` so a rapid
    // re-enter (user cancels the prompt → onBack pops the screen → user immediately
    // taps Hidden again) doesn't double-fire two prompts that race each other and
    // either stack or fail one after the other.
    var promptShown by remember { mutableStateOf(false) }

    // Re-lock only when the whole app goes to the background — NOT when navigating to the
    // in-app photo viewer (which stops THIS screen but keeps the process foregrounded) or on a
    // configuration change. The authenticated flag lives in the ViewModel so the list survives;
    // the app-level ON_STOP forces a fresh biometric/credential check on the next return and
    // re-arms the entry prompt. Using the screen lifecycle here would re-lock on every photo
    // the user opens from the vault.
    val processLifecycle = androidx.lifecycle.ProcessLifecycleOwner.get()
    DisposableEffect(processLifecycle) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_STOP) {
                viewModel.lock()
                promptShown = false
            }
        }
        processLifecycle.lifecycle.addObserver(observer)
        onDispose { processLifecycle.lifecycle.removeObserver(observer) }
    }

    // Keyed on the authenticated flag so that when the screen re-locks on returning from
    // the background (ON_STOP cleared it), the prompt fires again automatically instead of
    // leaving the user on a static lock screen.
    LaunchedEffect(state.isAuthenticated) {
        if (!state.isAuthenticated && !promptShown) {
            // LocaleOverride wraps LocalContext in a ContextWrapper, so a direct cast
            // to FragmentActivity throws ClassCastException. findFragmentActivity()
            // walks the baseContext chain to reach the underlying MainActivity.
            val fragmentActivity = context.findFragmentActivity()
            if (fragmentActivity == null) {
                onBack()
                return@LaunchedEffect
            }
            promptShown = true
            showBiometricPrompt(
                activity = fragmentActivity,
                onSuccess = { viewModel.onAuthenticationSuccess() },
                onError = { onBack() },
            )
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Bg0),
    ) {
        if (!state.isAuthenticated) {
            // Lock screen — shown while authenticating
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    modifier = Modifier
                        .size(72.dp)
                        .background(PillBg, CircleShape)
                        .border(0.5.dp, PillBorder, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Default.LockOpen,
                        contentDescription = null,
                        tint = Accent,
                        modifier = Modifier.size(32.dp),
                    )
                }
                Spacer(Modifier.height(24.dp))
                Text(
                    stringResource(R.string.hidden_photos_title),
                    color = FgPrimary, fontSize = 22.sp, fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.hidden_photos_auth_prompt),
                    color = FgDim, fontSize = 14.sp,
                )
                if (deviceHasNoLock) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        stringResource(R.string.hidden_no_device_lock_notice),
                        color = FgMute, fontSize = 13.sp,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 32.dp),
                    )
                }
                Spacer(Modifier.height(32.dp))
                Box(
                    modifier = Modifier
                        .background(Accent.copy(alpha = 0.12f), RoundedCornerShape(14.dp))
                        .border(0.5.dp, Accent.copy(alpha = 0.3f), RoundedCornerShape(14.dp))
                        .clickable {
                            // LocaleOverride wraps LocalContext in a ContextWrapper, so a
                            // direct cast to FragmentActivity throws ClassCastException.
                            // findFragmentActivity() walks the baseContext chain instead.
                            val fragmentActivity = context.findFragmentActivity() ?: return@clickable
                            showBiometricPrompt(
                                activity = fragmentActivity,
                                onSuccess = { viewModel.onAuthenticationSuccess() },
                                onError = { onBack() },
                            )
                        }
                        .padding(horizontal = 28.dp, vertical = 14.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        stringResource(R.string.hidden_photos_unlock),
                        color = Accent, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        } else {
            // Content — authenticated. Overlay layout, matching the Offline screen and the
            // duplicate finder: the grid fills and scrolls under a floating pill header, and the
            // bulk actions sit in the shared selection drawer. The grid's top content padding
            // clears the header.
            val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
            val contentTopPad = floatingHeaderContentTopPadding()
            val cols = eu.akoos.photos.presentation.gallery.rememberDefaultGridColumns()
            val seamless = eu.akoos.photos.presentation.gallery.rememberSeamlessGrid()
            // Held above the branch so the selection drawer can watch the same scroll the photo
            // grid reports, and so the vault keeps its place when the list fills in.
            val gridState = rememberLazyGridState()
            when {
                state.isLoading && state.hiddenAlbums.isEmpty() && state.hiddenFolders.isEmpty() &&
                    state.items.isEmpty() && state.hiddenCloudPhotos.isEmpty() -> LazyVerticalGrid(
                    columns = GridCells.Fixed(cols),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = if (seamless) 0.dp else 8.dp,
                        top = contentTopPad,
                        end = if (seamless) 0.dp else 8.dp,
                        bottom = 100.dp + navBottom,
                    ),
                    horizontalArrangement = Arrangement.spacedBy(if (seamless) 2.dp else 4.dp),
                    verticalArrangement = Arrangement.spacedBy(if (seamless) 2.dp else 4.dp),
                ) {
                    items(15) {
                        eu.akoos.photos.presentation.common.ShimmerSquare(
                            modifier = Modifier.fillMaxWidth(),
                            cornerRadius = 4.dp,
                        )
                    }
                }
                state.hiddenAlbums.isEmpty() && state.hiddenFolders.isEmpty() &&
                    state.items.isEmpty() && state.hiddenCloudPhotos.isEmpty() -> Box(
                    Modifier.fillMaxSize().padding(top = contentTopPad),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Default.VisibilityOff, null,
                            tint = FgMute, modifier = Modifier.size(40.dp),
                        )
                        Spacer(Modifier.height(16.dp))
                        Text(stringResource(R.string.hidden_empty_title), color = FgPrimary, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(8.dp))
                        Text(
                            stringResource(R.string.hidden_empty_subtitle),
                            color = FgDim, fontSize = 14.sp,
                        )
                    }
                }
                else -> {
                    // Drag-to-select: long-press a cell then sweep a range (shares the timeline
                    // gesture). The hit-test reads the grid's own viewportStartOffset, so the large top
                    // content padding under the floating header is handled without a manual offset.
                    // Selectable keys in grid order: the hidden cloud photos (prefixed) come first, then
                    // the device photos (keyed by uri), matching the two groups' layout below, so a
                    // drag sweep runs contiguously across both. Album rows + headers stay out of the map.
                    val selectableKeys = remember(state.hiddenCloudPhotos, state.items) {
                        state.hiddenCloudPhotos.map { HIDDEN_CLOUD_SELECTION_PREFIX + it.linkId } +
                            state.items.map { it.uri }
                    }
                    val keyToIndex = remember(selectableKeys) {
                        selectableKeys.mapIndexed { i, k -> k to i }.toMap()
                    }
                    // The one selection the drag-select reads: device uris plus the prefixed cloud keys,
                    // so a sweep extends across both groups. setSelectionFromKeys splits it back into the
                    // two VM sets on every change.
                    val selectionKeys = remember(state.selectedUris, state.selectedCloudLinkIds) {
                        state.selectedUris + state.selectedCloudLinkIds.map { HIDDEN_CLOUD_SELECTION_PREFIX + it }
                    }
                    // Armed at the long-press anchor so the cell's release-tap skips toggling the
                    // just-selected cell back off (otherwise a stationary long-press would select
                    // then immediately deselect).
                    val tapGuard = remember { mutableStateOf(false) }
                    val dragMod = eu.akoos.photos.presentation.gallery.rememberDragMultiSelectModifier(
                        gridState = gridState,
                        items = selectableKeys,
                        indexByKey = keyToIndex,
                        selected = selectionKeys,
                        onSelectionChange = viewModel::setSelectionFromKeys,
                        tapGuard = tapGuard,
                    )
                    // Land back on the photo the viewer closed on. The album block and the folder
                    // block lead the grid ahead of every photo, then the cloud group and the device
                    // group each carry one header. An empty card block is dropped rather than passed
                    // through: its header is only emitted alongside it, and counting a phantom one
                    // would push the device photos a slot out. The two groups hand the viewer
                    // different lists (CloudOnly for the cloud photos, LocalOnly for the device
                    // ones), so each is reduced here to the key it reports back.
                    val returnLeadingSlots = remember(
                        state.hiddenAlbums, state.hiddenFolders, state.looseDeviceCopyLinkIds,
                    ) {
                        // The notice is emitted above the albums, so it holds a slot of its own here.
                        // Every full-span row ahead of the photos has to be counted or the return
                        // lands the grid one row off for each one that is not.
                        val noticeSlots = if (state.looseDeviceCopyLinkIds.isEmpty()) 0 else 1
                        val albumSlots =
                            if (state.hiddenAlbums.isEmpty()) 0 else 1 + state.hiddenAlbums.chunked(2).size
                        val folderSlots =
                            if (state.hiddenFolders.isEmpty()) 0 else 1 + state.hiddenFolders.chunked(2).size
                        noticeSlots + albumSlots + folderSlots
                    }
                    val returnGroups = remember(state.hiddenCloudPhotos, state.items) {
                        val devicePhotos = state.items.map { it.uri }
                        if (state.hiddenCloudPhotos.isEmpty()) listOf(devicePhotos)
                        else listOf(state.hiddenCloudPhotos.map { it.linkId }, devicePhotos)
                    }
                    // The device group's sub-heading is emitted only when a group sits above it, so
                    // "one header each" holds exactly when there are cloud photos, albums or folders
                    // to lead.
                    val returnHasHeaders = state.hiddenCloudPhotos.isNotEmpty() ||
                        state.hiddenAlbums.isNotEmpty() || state.hiddenFolders.isNotEmpty()
                    ReturnToViewerPhoto(
                        gridState = gridState,
                        groups = returnGroups,
                        headerPerGroup = returnHasHeaders,
                        leadingSlots = returnLeadingSlots,
                        keyOf = { it },
                    )
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(cols),
                        state = gridState,
                        contentPadding = PaddingValues(
                            start = if (seamless) 0.dp else 8.dp,
                            top = contentTopPad,
                            end = if (seamless) 0.dp else 8.dp,
                            bottom = 100.dp + navBottom,
                        ),
                        horizontalArrangement = Arrangement.spacedBy(if (seamless) 2.dp else 4.dp),
                        verticalArrangement = Arrangement.spacedBy(if (seamless) 2.dp else 4.dp),
                        modifier = Modifier.fillMaxSize().then(dragMod),
                    ) {
                        // Photos hidden before the vault covered backed-up ones keep their device
                        // file in the phone's gallery, so they are hidden here and visible in every
                        // other gallery app. Both kinds sit in this grid looking identical, which is
                        // the one thing worth saying out loud on a screen whose whole promise is that
                        // what it holds is out of sight.
                        if (state.looseDeviceCopyLinkIds.isNotEmpty()) {
                            item(span = { GridItemSpan(maxLineSpan) }, key = "loose_device_copies") {
                                Row(
                                    verticalAlignment = Alignment.Top,
                                    modifier = Modifier
                                        .padding(horizontal = 8.dp, vertical = 10.dp)
                                        .background(PillBg, RoundedCornerShape(12.dp))
                                        .padding(horizontal = 14.dp, vertical = 12.dp),
                                ) {
                                    Icon(
                                        Icons.Filled.Info,
                                        contentDescription = null,
                                        tint = FgMute,
                                        modifier = Modifier.size(18.dp),
                                    )
                                    Spacer(Modifier.width(12.dp))
                                    Text(
                                        pluralStringResource(
                                            R.plurals.hidden_loose_device_copies,
                                            state.looseDeviceCopyLinkIds.size,
                                            state.looseDeviceCopyLinkIds.size,
                                        ),
                                        color = FgDim,
                                        fontSize = 12.5.sp,
                                    )
                                }
                            }
                        }
                        // Hidden cloud albums lead the grid as full-span rows above the photos.
                        // Their keys aren't in keyToIndex, so the drag-select gesture skips them.
                        if (state.hiddenAlbums.isNotEmpty()) {
                            item(span = { GridItemSpan(maxLineSpan) }, key = "hidden_albums_header") {
                                Text(
                                    stringResource(R.string.hidden_albums_section),
                                    color = FgPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
                                )
                            }
                            items(
                                state.hiddenAlbums.chunked(2),
                                span = { GridItemSpan(maxLineSpan) },
                                key = { row -> "hidden_album_row_" + row.joinToString("_") { it.linkId } },
                            ) { row ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 8.dp, vertical = 4.dp),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                ) {
                                    row.forEach { album ->
                                        HiddenAlbumCard(
                                            album = album,
                                            onOpen = { onOpenAlbum(album) },
                                            onUnhide = { viewModel.unhideAlbum(album.linkId) },
                                            modifier = Modifier.weight(1f),
                                        )
                                    }
                                    if (row.size == 1) Spacer(Modifier.weight(1f))
                                }
                            }
                        }
                        // Device folders whose card is off the Albums grid, in their own labelled
                        // block below the albums. These are the same cards that grid draws, and each
                        // one holds the folder's own vaulted photos, which is why they are not in the
                        // flat group below. Their keys aren't in keyToIndex either, so the
                        // drag-select skips them the same way.
                        if (state.hiddenFolders.isNotEmpty()) {
                            item(span = { GridItemSpan(maxLineSpan) }, key = "hidden_folders_header") {
                                Text(
                                    stringResource(R.string.hidden_folders_section),
                                    color = FgPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
                                )
                            }
                            items(
                                state.hiddenFolders.chunked(2),
                                span = { GridItemSpan(maxLineSpan) },
                                key = { row -> "hidden_folder_row_" + row.joinToString("_") { it.name } },
                            ) { row ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 8.dp, vertical = 4.dp),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                ) {
                                    row.forEach { folder ->
                                        HiddenFolderCard(
                                            folder = folder,
                                            onOpen = { onOpenFolder(folder.name) },
                                            onUnhide = { viewModel.unhideFolder(folder.name) },
                                            modifier = Modifier.weight(1f),
                                        )
                                    }
                                    if (row.size == 1) Spacer(Modifier.weight(1f))
                                }
                            }
                        }
                        // Individually-hidden cloud photos as their own labelled group. Each cell
                        // decrypts its thumbnail on demand (CloudPhotoCell queues the decrypt on
                        // compose, cancels on dispose) and reads the decrypted url from the shared
                        // thumbnail store. Their keys are in keyToIndex, so the grid drag-select picks
                        // them exactly like the device tiles; tapping opens the viewer, and a long-press
                        // (or a tap in selection mode) selects for a batch reveal from the drawer.
                        if (state.hiddenCloudPhotos.isNotEmpty()) {
                            item(span = { GridItemSpan(maxLineSpan) }, key = "hidden_cloud_photos_header") {
                                Text(
                                    stringResource(R.string.hidden_cloud_photos_section),
                                    color = FgPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
                                )
                            }
                            itemsIndexed(
                                state.hiddenCloudPhotos,
                                key = { _, photo -> HIDDEN_CLOUD_SELECTION_PREFIX + photo.linkId },
                            ) { index, photo ->
                                HiddenCloudPhotoCell(
                                    photo = photo,
                                    seamless = seamless,
                                    isSelectionMode = state.isSelectionMode,
                                    isSelected = photo.linkId in state.selectedCloudLinkIds,
                                    onClick = {
                                        // Skip the release-tap that follows a long-press select; it
                                        // would otherwise toggle the just-anchored cell back off.
                                        if (tapGuard.value) tapGuard.value = false
                                        else if (state.isSelectionMode) viewModel.toggleCloudSelection(photo.linkId)
                                        else {
                                            val viewerItems = state.hiddenCloudPhotos.map { GalleryItem.CloudOnly(it) }
                                            onCloudPhotoClick(viewerItems, index)
                                        }
                                    },
                                    onRequestThumbnail = viewModel::requestCloudThumbnailDecrypt,
                                    onCancelThumbnail = viewModel::cancelCloudThumbnailDecrypt,
                                )
                            }
                        }
                        // A short "Hidden Photos" sub-heading only when a cloud group sits above the
                        // device photos, so the device grid reads as its own group.
                        if (state.items.isNotEmpty() &&
                            (
                                state.hiddenAlbums.isNotEmpty() || state.hiddenFolders.isNotEmpty() ||
                                    state.hiddenCloudPhotos.isNotEmpty()
                                )
                        ) {
                            item(span = { GridItemSpan(maxLineSpan) }, key = "hidden_photos_header") {
                                Text(
                                    stringResource(R.string.hidden_photos_title),
                                    color = FgPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
                                )
                            }
                        }
                        itemsIndexed(state.items, key = { _, item -> item.uri }) { index, item ->
                            HiddenPhotoCell(
                                item = item,
                                hasCloudCounterpart = item.uri in state.backedUpUris,
                                isSelectionMode = state.isSelectionMode,
                                isSelected = item.uri in state.selectedUris,
                                seamless = seamless,
                                onClick = {
                                    // Skip the release-tap that follows a long-press select; it
                                    // would otherwise toggle the just-anchored cell back off.
                                    if (tapGuard.value) tapGuard.value = false
                                    else if (state.isSelectionMode) viewModel.toggleSelection(item.uri)
                                    else onPhotoClick(state.items, index)
                                },
                            )
                        }
                    }
                }
            }

            // Floating pill header (matches Search / Map / the duplicate finder). Hidden while
            // selecting, so the screen belongs to the selection alone and its back arrow cannot
            // leave the vault mid-selection.
            if (!state.isSelectionMode) {
                eu.akoos.photos.presentation.memories.FloatingMemoriesHeader(
                    title = stringResource(R.string.hidden_photos_title),
                    onBack = onBack,
                )
            }

            // The strip carries exactly what the grid holds selected, in the order the grid lists
            // it: the hidden cloud photos first, then the vaulted device ones, so a sweep across
            // both groups reads here the way it looked there.
            val selectedItems = remember(
                state.hiddenCloudPhotos, state.items,
                state.selectedCloudLinkIds, state.selectedUris,
            ) {
                buildList<GalleryItem> {
                    state.hiddenCloudPhotos.forEach {
                        if (it.linkId in state.selectedCloudLinkIds) add(GalleryItem.CloudOnly(it))
                    }
                    state.items.forEach {
                        if (it.uri in state.selectedUris) add(GalleryItem.LocalOnly(it))
                    }
                }
            }
            val hiddenSelectionActions = buildList {
                // Takes in both groups at once: every device tile and every hidden cloud one.
                add(
                    SelectionAction(
                        icon = Icons.Default.SelectAll,
                        label = stringResource(
                            if (state.allSelected) R.string.gallery_deselect_all else R.string.select_all,
                        ),
                        onClick = { viewModel.toggleSelectAll() },
                    )
                )
                // Put the selection back where it came from, in the wording every other surface's
                // reveal row carries.
                add(
                    SelectionAction(
                        icon = Icons.Default.Visibility,
                        label = stringResource(R.string.sel_label_unhide),
                        onClick = { viewModel.unhideSelected() },
                    )
                )
                // Delete acts on the device tiles alone: a hidden cloud photo is a filter over a
                // file that is still on Drive, so there is nothing here to destroy for it.
                if (state.selectedUris.isNotEmpty()) {
                    add(
                        SelectionAction(
                            icon = Icons.Default.DeleteOutline,
                            label = stringResource(R.string.sel_label_delete),
                            tint = ErrorColor,
                            onClick = { showDeleteConfirm = true },
                        )
                    )
                }
            }
            // Selection drawer: the shared surface every multi-select uses, so the vault's bulk
            // actions sit in one list, in the order every other surface lists them.
            SelectionDrawer(
                visible = state.isSelectionMode,
                items = selectedItems,
                actions = hiddenSelectionActions,
                onDismiss = { viewModel.clearSelection() },
                // Scrolling the vault grid collapses the drawer, so reaching past it to carry on
                // through the photos needs no deliberate pull or tap first.
                contentScrolling = gridState.isScrollInProgress,
            // The vault's own cells decode fresh and cache nothing, so a hidden photo cannot be
            // rebuilt from a cache by its uri. The strip carries the same photos and holds the line.
            cacheThumbnails = false,
                modifier = Modifier.align(Alignment.BottomCenter),
            )

            // Confirm before destroying vault copies. The hide took the device original with it, so
            // there is no trash and no second copy to fall back on, and the sheet says so plainly.
            if (showDeleteConfirm && state.selectedUris.isNotEmpty()) {
                val deleteCount = state.selectedUris.size
                ConfirmSheet(
                    title = pluralStringResource(
                        R.plurals.trash_delete_forever_title, deleteCount, deleteCount,
                    ),
                    message = stringResource(R.string.hidden_delete_selected_body),
                    confirmLabel = stringResource(R.string.trash_delete_forever_confirm),
                    dismissLabel = stringResource(R.string.cancel),
                    onConfirm = {
                        showDeleteConfirm = false
                        viewModel.deleteSelectedVaulted()
                    },
                    onDismiss = { showDeleteConfirm = false },
                )
            }
        }

        // Progress of a folder being returned to the device, in the same pill every other screen
        // uses for bulk work. A folder can hold thousands, so the count is watchable and the cancel
        // travels with it — what is already back stays back, and the rest stays in the vault. A
        // restore outlives the vault locking behind it, so the count is withheld until it is open
        // again rather than telling the lock screen how much is in there.
        val restoringTpl = stringResource(R.string.device_folder_restoring_fmt)
        eu.akoos.photos.presentation.common.OperationProgressPill(
            progress = state.folderRestore?.takeIf { state.isAuthenticated }?.let { fr ->
                eu.akoos.photos.presentation.common.OperationProgress(
                    fr.done, fr.total, restoringTpl.format(fr.done, fr.total),
                )
            },
            onCancel = viewModel::cancelFolderRestore,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(top = 8.dp),
        )

        eu.akoos.photos.presentation.common.ThemedSnackbarHost(
            snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding(),
        )
    }
}

@Composable
private fun HiddenPhotoCell(
    item: LocalMediaItem,
    hasCloudCounterpart: Boolean,
    isSelectionMode: Boolean,
    isSelected: Boolean,
    onClick: () -> Unit,
    // Edge-to-edge grid: square corners on the tile clip and the selection border.
    seamless: Boolean = false,
) {
    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .selectPressScale(isSelected)
            .clip(RoundedCornerShape(if (seamless) 0.dp else 8.dp))
            .background(Bg2)
            // The grid owns long-press at its level (drag-to-select), so the cell stays tap-only:
            // in selection mode a tap toggles this cell, otherwise it opens the viewer.
            .clickable { onClick() }
            .then(
                if (isSelected) Modifier.border(2.5.dp, Accent, RoundedCornerShape(if (seamless) 0.dp else 8.dp))
                else Modifier,
            ),
    ) {
        AsyncImage(
            // Hidden previews must never persist in Coil's caches: a cached thumbnail
            // would let any other surface (or a cache dump) reconstruct a hidden image
            // by URI. Decode straight from the source on every draw instead.
            model = ImageRequest.Builder(LocalContext.current)
                .data(android.net.Uri.parse(item.uri))
                .memoryCachePolicy(CachePolicy.DISABLED)
                .diskCachePolicy(CachePolicy.DISABLED)
                .build(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
                .then(if (isSelected) Modifier.background(Accent.copy(alpha = 0.15f)) else Modifier),
        )
        // Green cloud badge in the bottom-end corner — matches the photos-grid styling
        // so the user can tell at a glance which hidden items are also backed up to
        // Drive (safe to remove from device) vs which only exist on this phone.
        if (hasCloudCounterpart) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(5.dp)
                    .size(20.dp)
                    .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(4.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    androidx.compose.material.icons.Icons.Default.Cloud,
                    contentDescription = stringResource(R.string.cd_status_backed_up),
                    // Same green as the gallery's SyncedCloudBadge so the visual language
                    // for "this photo is on Drive" stays consistent between Hidden and the
                    // regular timeline. Using the brand Accent (purple) here instead would
                    // read as a third badge colour and imply the hidden item lives on a
                    // different kind of cloud than the regular Synced ones.
                    tint = Color(0xFF30D158),
                    modifier = Modifier.size(12.dp),
                )
            }
        }
        // Selection circle — top-start, the same accent-filled check / dark-outlined idiom the
        // timeline and album cells use, so selection reads identically across the app.
        if (isSelectionMode) {
            Box(
                modifier = Modifier
                    .padding(5.dp)
                    .size(22.dp)
                    .align(Alignment.TopStart),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.3f), CircleShape)
                        .border(1.5.dp, Color.White.copy(alpha = 0.8f), CircleShape),
                )
                SelectionCheckPop(isSelected) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Accent, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Default.Check,
                            contentDescription = stringResource(R.string.cd_status_selected),
                            tint = Color.White,
                            modifier = Modifier.size(14.dp),
                        )
                    }
                }
            }
        }
    }
}

/**
 * A hidden cloud-photo tile. The thumbnail decrypts on demand through [CloudPhotoCell]'s own
 * request/cancel wiring plus the shared thumbnail store, so nothing is decrypted up front. Tap-only:
 * the grid-level drag-select owns the long-press, so it behaves exactly like the device hidden tiles.
 * Tapping opens the viewer over the whole hidden-cloud list; a long-press (or a tap in selection mode)
 * selects the photo for a batch reveal from the selection drawer.
 */
@Composable
private fun HiddenCloudPhotoCell(
    photo: CloudPhoto,
    isSelectionMode: Boolean,
    isSelected: Boolean,
    onClick: () -> Unit,
    onRequestThumbnail: (String) -> Unit,
    onCancelThumbnail: (String) -> Unit,
    // Edge-to-edge grid: square corners on the tile clip.
    seamless: Boolean = false,
) {
    CloudPhotoCell(
        localUri = null,
        cloudThumbnailUrl = photo.thumbnailUrl,
        cloudLinkId = photo.linkId,
        isVideo = photo.mimeType.startsWith("video/"),
        isSelectionMode = isSelectionMode,
        isSelected = isSelected,
        // Tap-only: the grid drag-select owns the long-press, matching the device hidden tiles.
        onLongClick = null,
        onClick = onClick,
        onRequestThumbnail = onRequestThumbnail,
        onCancelThumbnail = onCancelThumbnail,
        cornerRadiusDp = if (seamless) 0.dp else 8.dp,
    )
}

/**
 * A hidden cloud-album card. Tapping the card opens the album (its photos still resolve in
 * AlbumDetail, which queries membership directly). The corner bubble reveals the album by
 * dropping it from the hidden set, and a long-press does the same as a secondary path.
 */
@Composable
private fun HiddenAlbumCard(
    album: Album,
    onOpen: () -> Unit,
    onUnhide: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier) {
        UnifiedAlbumCard(
            coverModel = album.coverThumbnailUrl,
            title = album.name,
            metaText = pluralStringResource(
                R.plurals.count_items_plural, album.photoCount, album.photoCount,
            ),
            cloudBadge = AlbumCloudBadge.Cloud,
            onClick = onOpen,
            onLongClick = onUnhide,
        )
        IconBubble(
            icon = Icons.Default.Visibility,
            contentDescription = stringResource(R.string.albums_unhide_album),
            onClick = onUnhide,
            diameter = 32.dp,
            iconSize = 17.dp,
            background = PillBgOpaque,
            borderColor = PillBorder,
            tint = Accent,
            modifier = Modifier.align(Alignment.TopEnd).padding(6.dp),
        )
    }
}

/**
 * A hidden device-folder card. Tapping it opens the folder, which the hide leaves fully working:
 * only its card is off the Albums grid. The corner bubble puts that card back, and a long-press does
 * the same as a secondary path, matching [HiddenAlbumCard] exactly.
 */
@Composable
private fun HiddenFolderCard(
    folder: DeviceFolder,
    onOpen: () -> Unit,
    onUnhide: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier) {
        UnifiedAlbumCard(
            coverModel = folder.coverUri?.let(android.net.Uri::parse),
            title = folder.name,
            metaText = pluralStringResource(
                R.plurals.count_photos_plural, folder.itemCount, folder.itemCount,
            ),
            isDeviceFolder = true,
            onClick = onOpen,
            onLongClick = onUnhide,
        )
        IconBubble(
            icon = Icons.Default.Visibility,
            contentDescription = stringResource(R.string.device_folder_unhide),
            onClick = onUnhide,
            diameter = 32.dp,
            iconSize = 17.dp,
            background = PillBgOpaque,
            borderColor = PillBorder,
            tint = Accent,
            modifier = Modifier.align(Alignment.TopEnd).padding(6.dp),
        )
    }
}

private fun showBiometricPrompt(
    activity: FragmentActivity,
    onSuccess: () -> Unit,
    onError: () -> Unit,
) {
    val manager = BiometricManager.from(activity)
    val canAuth = manager.canAuthenticate(
        BiometricManager.Authenticators.BIOMETRIC_STRONG or
            BiometricManager.Authenticators.DEVICE_CREDENTIAL,
    )
    if (canAuth != BiometricManager.BIOMETRIC_SUCCESS) {
        // No biometric/credential authenticator is currently usable. Only open the vault
        // ungated when the device has no screen lock at all — there is simply nothing to
        // authenticate against, and refusing would lock the user out of their own files
        // with no recovery. If the device IS secured (PIN/pattern/password) but biometrics
        // are merely unavailable or unenrolled, fall through to the prompt below, which
        // allows DEVICE_CREDENTIAL and so can still gate on the device lock.
        val keyguard = activity.getSystemService(KeyguardManager::class.java)
        val deviceSecure = keyguard?.isDeviceSecure == true
        if (!deviceSecure) {
            onSuccess()
            return
        }
    }
    val prompt = BiometricPrompt(
        activity,
        ContextCompat.getMainExecutor(activity),
        object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                onSuccess()
            }
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                onError()
            }
            override fun onAuthenticationFailed() {
                // Stay locked — user can retry
            }
        },
    )
    // Building/authenticating can throw if the platform rejects the authenticator
    // combination. Fail closed (stay locked) rather than open in that case — the user can
    // retry or back out.
    runCatching {
        val builder = BiometricPrompt.PromptInfo.Builder()
            .setTitle(activity.getString(R.string.hidden_photos_title))
            .setDescription(activity.getString(R.string.hidden_photos_auth_prompt))
        // BIOMETRIC_STRONG | DEVICE_CREDENTIAL is rejected by PromptInfo.build() on
        // API 28-29 — the combined-authenticators API only exists from 30. The
        // deprecated setDeviceCredentialAllowed is the supported pre-30 mechanism
        // for the same PIN/pattern fallback.
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            builder.setAllowedAuthenticators(
                BiometricManager.Authenticators.BIOMETRIC_STRONG or
                    BiometricManager.Authenticators.DEVICE_CREDENTIAL,
            )
        } else {
            @Suppress("DEPRECATION")
            builder.setDeviceCredentialAllowed(true)
        }
        prompt.authenticate(builder.build())
    }.onFailure { onError() }
}
