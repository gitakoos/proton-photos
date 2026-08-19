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
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Panorama
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.delay
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import android.os.Build
import androidx.compose.foundation.layout.Spacer
import androidx.compose.ui.draw.blur
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BrokenImage
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.PrivacyTip
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MotionPhotosOn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import eu.akoos.photos.R
import eu.akoos.photos.presentation.common.ConfirmDialog
import eu.akoos.photos.presentation.common.anyMetadataEditable
import eu.akoos.photos.presentation.gallery.MetadataStripPickerDialog
import eu.akoos.photos.presentation.common.SecureScreenEffect
import eu.akoos.photos.presentation.common.UndoAction
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import coil.compose.AsyncImagePainter
import coil.request.ImageRequest
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.OfflinePin
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.LibraryAdd
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.RemoveCircleOutline
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.outlined.OfflinePin
import eu.akoos.photos.data.hidden.VaultMove
import eu.akoos.photos.data.image.ULTRA_HDR_TAG
import eu.akoos.photos.data.image.decodeUltraHdr
import eu.akoos.photos.data.ocr.OcrModelPreparation
import eu.akoos.photos.domain.entity.Album
import eu.akoos.photos.domain.entity.GalleryItem
import eu.akoos.photos.domain.usecase.DeletePhotoUseCase
import eu.akoos.photos.presentation.gallery.LocalThumbnailUrls
import eu.akoos.photos.presentation.theme.Accent
import eu.akoos.photos.presentation.theme.Bg0
import eu.akoos.photos.presentation.theme.Bg2
import eu.akoos.photos.presentation.theme.CardBg
import eu.akoos.photos.presentation.theme.CardBorder
import eu.akoos.photos.presentation.theme.DeleteTint
import eu.akoos.photos.presentation.theme.ErrorColor
import eu.akoos.photos.presentation.theme.FgDim
import eu.akoos.photos.presentation.theme.FgMute
import eu.akoos.photos.presentation.theme.FgPrimary
import eu.akoos.photos.presentation.theme.Line2
import eu.akoos.photos.presentation.theme.PanelChip
import eu.akoos.photos.presentation.theme.PillBg
import eu.akoos.photos.presentation.theme.PillBorder
import eu.akoos.photos.presentation.util.findActivity
import eu.akoos.photos.presentation.util.formatVideoTime
import eu.akoos.photos.util.MetadataStripConfig
import eu.akoos.photos.util.PhotoMetadata
import eu.akoos.photos.util.copySensitiveText
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal val bubbleShape = CircleShape
internal val infoPillShape = RoundedCornerShape(999.dp)

/** Local-side URI of an item that carries one (LocalOnly / Synced), else null. Used to re-pair a
 *  snapshot LocalOnly with the Synced twin it became once its upload landed. */
private fun GalleryItem.localUriOrNull(): String? = when (this) {
    is GalleryItem.LocalOnly -> local.uri
    is GalleryItem.Synced    -> local.uri
    is GalleryItem.CloudOnly -> null
}

/**
 * Reconcile the static [snapshot] handed to the viewer against the [live] timeline so the open
 * viewer reflects a photo finishing upload (LocalOnly → Synced) and any metadata refresh, without
 * losing the user's place.
 *
 * Each snapshot item is re-resolved against [live] by its stable id, or — for a snapshot
 * `LocalOnly` whose upload landed and turned it into a `Synced` (new stable id) — by a local-uri
 * match. The resolved live version is swapped in; an item absent from [live] is kept as-is. The
 * list shape and order are preserved (same size, same positions), so this is safe for every
 * surface the viewer opens from: the caller already hands a filtered list (the gallery strips
 * hidden / timeline-excluded items, albums hand their own membership), and reconciliation never
 * injects an item that wasn't in that list. Returns [snapshot] unchanged until [live] first emits.
 */
internal fun reconcileViewerItems(
    snapshot: List<GalleryItem>,
    live: List<GalleryItem>,
): List<GalleryItem> {
    if (live.isEmpty() || snapshot.isEmpty()) return snapshot
    val byStableId = live.associateBy { it.stableId }
    val byLocalUri = live.asSequence()
        .mapNotNull { item -> item.localUriOrNull()?.let { it to item } }
        .toMap()

    fun resolve(item: GalleryItem): GalleryItem =
        byStableId[item.stableId]
            ?: item.localUriOrNull()?.let { byLocalUri[it] }
            ?: item

    val resolved = snapshot.map(::resolve)
    // Keep a STABLE list identity across no-op re-emits. The timeline feed backing the
    // gallery viewer re-emits many times a second during the cold listing / thumbnail
    // decrypt burst, yet those ticks rarely change any item the viewer is currently showing.
    // Returning a fresh list every time would retrigger the page-resync effect in
    // PhotoViewerScreen and can yank an in-flight swipe back to the previous photo. Hand back
    // the original instance when reconciliation changed nothing.
    return if (resolved == snapshot) snapshot else resolved
}

/**
 * [items] with every vaulted photo the vault has [moves]d pointed at the file's new path, under the
 * name it now carries.
 *
 * A rename and a capture-date edit both move a vault file, because a vaulted photo keeps its name and
 * its date IN that name. The uri is therefore not stable for these photos the way a MediaStore one is,
 * and the reconciliation above cannot help: it re-resolves each item against the live timeline, which
 * leaves vaulted photos out. Without this the page goes on naming a file that is gone, and every action
 * offered from it — share, reveal, delete, another edit — lands on that dead path.
 *
 * Returns [items] unchanged when nothing on the page moved, keeping the stable list identity
 * [reconcileViewerItems] goes out of its way to preserve.
 */
internal fun applyVaultMoves(
    items: List<GalleryItem>,
    moves: Map<String, VaultMove>,
): List<GalleryItem> {
    if (moves.isEmpty() || items.isEmpty()) return items
    val moved = items.map { item ->
        // A vaulted photo reaches the viewer as a device-only item whatever it was before it was
        // hidden: vaulting takes its MediaStore row away, so nothing pairs it with a cloud half here.
        // A cloud photo has no device file at all, and its own hide leaves that file where it is.
        if (item !is GalleryItem.LocalOnly) return@map item
        val move = moves[item.local.uri] ?: return@map item
        GalleryItem.LocalOnly(
            item.local.copy(uri = move.uri, displayName = move.displayName ?: item.local.displayName),
        )
    }
    return if (moved == items) items else moved
}

/** One press's outcome for the viewer's hand-rolled tap detector: a lift (a tap), a hold past the
 *  long-press time, or a cancel (a move past touch slop, or a second finger for a pinch). */
private sealed interface ViewerTapOutcome {
    data class Lifted(val change: androidx.compose.ui.input.pointer.PointerInputChange) : ViewerTapOutcome
    object HeldLong : ViewerTapOutcome
    object Cancelled : ViewerTapOutcome
}

/** Waits out one press on [pointerId]. The caller reads the down with requireUnconsumed = false, so a
 *  first press whose down a lower node consumed (the pager arresting its snap as a page lands) is
 *  still seen; consumption is ignored from here so that settle is not mistaken for a cancel. */
private suspend fun androidx.compose.ui.input.pointer.AwaitPointerEventScope.awaitViewerTapOutcome(
    pointerId: androidx.compose.ui.input.pointer.PointerId,
    downPosition: Offset,
    slop: Float,
    longPressMs: Long,
): ViewerTapOutcome {
    var lifted: androidx.compose.ui.input.pointer.PointerInputChange? = null
    val cancelled = withTimeoutOrNull(longPressMs) {
        var result: Boolean? = null
        while (result == null) {
            val event = awaitPointerEvent()
            val change = event.changes.firstOrNull { it.id == pointerId }
            result = when {
                event.changes.count { it.pressed } > 1 -> true
                change == null -> true
                !change.pressed -> { lifted = change; false }
                (change.position - downPosition).getDistance() > slop -> true
                else -> null
            }
        }
        result
    }
    return when (cancelled) {
        null -> ViewerTapOutcome.HeldLong
        true -> ViewerTapOutcome.Cancelled
        else -> ViewerTapOutcome.Lifted(lifted!!)
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun PhotoViewerScreen(
    items: List<GalleryItem>,
    initialIndex: Int,
    /** Closes the viewer, carrying the photo it settled on so the grid underneath can put the user
     *  back on it. Null when there is nothing to return to: the viewer never opened, the photo was
     *  deleted, or the user left on the very photo they came in on and the grid is already there. */
    onBack: (settledKey: String?) -> Unit,
    sourceAlbumLinkId: String? = null,
    /** True for a shared-with-me album: suppresses every mutating affordance (delete, set cover,
     *  rename, edit) since the backend rejects them from the wrong share id. Save + Info stay. */
    isReadOnlyAlbum: Boolean = false,
    /** Whether this user may take the photo back out of [sourceAlbumLinkId]. Removing is an edit, so
     *  it follows the album's write grant rather than plain ownership, which makes it a separate
     *  question from [isReadOnlyAlbum]: an editor on a shared album keeps this one affordance while
     *  the rest of the mutating menu stays hidden. */
    canRemoveFromAlbum: Boolean = false,
    /** Editor pop-back timestamp, keyed into the page-load effect so the viewer drops its bitmap
     *  cache and re-reads fresh bytes after a save. */
    editedAt: Long = 0L,
    /** Cloud linkIds whose local twin is in the Hidden vault; those items get a blur + "Hidden"
     *  overlay over the full-res surface. */
    hiddenCloudLinkIds: Set<String> = emptySet(),
    /** Hidden-vault session: marks the viewer window FLAG_SECURE so full-res hidden photos stay
     *  out of screenshots and the recent-apps preview, like the vault grid itself. */
    secure: Boolean = false,
    /** Opens straight into the slideshow, for an entry point that asked for one (an album's menu).
     *  Only seeds the initial value — the play/pause pill owns it from there. */
    startSlideshow: Boolean = false,
    onEditItem: (GalleryItem) -> Unit = {},
    /** Opens the date + place metadata editor for the current item. Suppressed for a shared-with-me
     *  album (the sheet hides the affordance), mirroring the rename gate. */
    onEditMetadata: (GalleryItem) -> Unit = {},
    /** Opens the page of the person the face index grouped, from the "people in this photo" bar. */
    onOpenPerson: (Long) -> Unit = {},
    viewModel: PhotoViewerViewModel = hiltViewModel(),
) {
    if (items.isEmpty()) { onBack(null); return }

    if (secure) SecureScreenEffect()

    // Live reconciliation: the caller hands a static snapshot captured at click time, so a photo
    // finishing upload (LocalOnly → Synced) or any metadata refresh would never reflect here. Swap
    // each snapshot item for its live version in place (same order + size), keeping items not in
    // the live merge as-is. See [reconcileViewerItems].
    val liveItems by viewModel.liveItems.collectAsStateWithLifecycle()
    // A vaulted photo keeps its name and its date in its file name, so both edits MOVE the file and
    // change the uri the snapshot holds it under. The live merge leaves vaulted photos out, so the
    // vault reports its own moves and they are applied on top. See [applyVaultMoves].
    val vaultMoves by viewModel.vaultMoves.collectAsStateWithLifecycle()
    val reconciled = remember(items, liveItems, vaultMoves) {
        applyVaultMoves(reconcileViewerItems(items, liveItems), vaultMoves)
    }
    // Photos deleted from inside the viewer. The snapshot the caller handed us cannot shrink on its
    // own (reconciliation swaps items in place and deliberately keeps ones the live merge no longer
    // carries, since an album view legitimately holds photos the timeline does not), so a delete has
    // to drop its photo here. Filtering rather than closing is what lets the user keep paging: the
    // pager is keyed by stableId, so removing the item at the current index leaves that index
    // holding the NEXT photo, with no scroll to perform.
    val removedIds = remember { mutableStateListOf<String>() }
    val visible = remember(reconciled, removedIds.size) {
        // Hand back the same instance while nothing is removed, preserving the stable list identity
        // reconcileViewerItems goes out of its way to keep.
        if (removedIds.isEmpty()) reconciled else reconciled.filterNot { it.stableId in removedIds }
    }
    // Render off the reconciled list; every per-page lookup below reads from `items` so alias it.
    @Suppress("NAME_SHADOWING") val items = visible
    // Changes whenever the set of dropped photos does, or a vaulted photo moves to a new path. Each
    // per-page effect below keys on the page INDEX, and neither event changes that index: a removal
    // deliberately leaves the next photo in the deleted one's slot, and a move leaves the same photo in
    // its own. So on their own neither changes anything those effects can see, and the viewer would
    // keep the state it built for a file that is no longer there: the photo would be drawn, but never
    // loaded at full resolution, never zoomable, and a video would never start.
    val pageGeneration = removedIds.size + vaultMoves.size
    // An undone delete or album removal puts its photo back where it was. Drop exactly the ids that
    // undo restored, so a photo deleted earlier in this session, whose own undo window has since
    // been taken over, stays gone.
    LaunchedEffect(Unit) {
        viewModel.undoRestored.collect { action ->
            when (action) {
                is UndoAction.Delete ->
                    removedIds.removeAll((action.cloudLinkIds + action.localTrashedUris).toSet())
                // Inert when the removal targeted an album this viewer is not showing, because no
                // id was dropped for it in the first place.
                is UndoAction.AlbumRemove -> removedIds.removeAll(action.photoLinkIds.toSet())
                else -> Unit
            }
        }
    }

    val clampedInitial = initialIndex.coerceIn(0, items.lastIndex)
    val pagerState = rememberPagerState(initialPage = clampedInitial) { items.size }
    // Identity of the page the user is settled on, remembered so that — should a future live
    // change ever alter the list length — we can re-find the photo by key and keep the user on it.
    // With the in-place swap the index is stable, so the scroll-back below is normally inert; the
    // pager `key` (further down) is what makes a LocalOnly → Synced swap rebind cleanly.
    var anchorKey by remember { mutableStateOf(items.getOrNull(clampedInitial)?.stableId) }
    // The photo the viewer opened on. The grid underneath restores itself to this one on its own,
    // so only a different photo at exit is worth reporting back.
    val openedKey = remember { items.getOrNull(clampedInitial)?.stableId }
    LaunchedEffect(pagerState.settledPage, pageGeneration) {
        items.getOrNull(pagerState.settledPage)?.stableId?.let { anchorKey = it }
    }
    LaunchedEffect(items) {
        // Never re-sync mid-gesture: a live re-emit that lands during a swipe must not cancel
        // the in-flight fling (that is the "snaps back to the previous photo" timeline bug). The
        // settle handler above updates anchorKey once the gesture finishes, so a genuine list
        // change still re-anchors on the next settled frame.
        if (pagerState.isScrollInProgress) return@LaunchedEffect
        val key = anchorKey ?: return@LaunchedEffect
        val newIndex = items.indexOfFirst { it.stableId == key }
        if (newIndex >= 0 && newIndex != pagerState.currentPage) {
            pagerState.scrollToPage(newIndex)
        }
    }
    val state by viewModel.state.collectAsStateWithLifecycle()
    // Master AI-features gate, still required by the people-in-this-photo action alongside the
    // per-feature face switch below.
    val aiFeaturesEnabled by viewModel.aiFeaturesEnabled.collectAsStateWithLifecycle()
    // Copy-text gate: the master switch and the per-feature Copy text opt-in together. With it off the
    // long press to read is inert, so no OCR model is ever fetched from the viewer.
    val copyTextEnabled by viewModel.copyTextEnabled.collectAsStateWithLifecycle()
    // Per-feature face gate: the people-in-this-photo action needs this AND the master switch.
    val faceEnabled by viewModel.faceEnabled.collectAsStateWithLifecycle()
    // The people the face index found on the settled photo, and whether their name tags are pinned over
    // the faces. Reset on every page settle so tags never carry a previous photo's faces.
    val peopleInPhoto by viewModel.peopleInPhoto.collectAsStateWithLifecycle()
    var facesMode by remember { mutableStateOf(false) }
    // The settled photo's decoded pixel size, shared by the tags and the long-press face hit-test.
    var settledImageSize by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }
    val noFacesFoundMsg = stringResource(R.string.viewer_face_none_found)
    val isDownloading by viewModel.isDownloading.collectAsStateWithLifecycle()
    val downloadProgress by viewModel.downloadProgress.collectAsStateWithLifecycle()
    val fullResBlockedByMetered by viewModel.fullResBlockedByMetered.collectAsStateWithLifecycle()
    // The metered "full quality" hint is dismissable per viewer session — once X'd it stays gone
    // while the user keeps browsing on the same metered link.
    var meteredHintDismissed by remember { mutableStateOf(false) }
    val metadata by viewModel.metadata.collectAsStateWithLifecycle()
    val detailsPlace by viewModel.detailsPlace.collectAsStateWithLifecycle()
    val detailsGps by viewModel.detailsGps.collectAsStateWithLifecycle()
    val detailsAlbums by viewModel.detailsAlbums.collectAsStateWithLifecycle()
    val cloudVideoMeta by viewModel.cloudVideoMeta.collectAsStateWithLifecycle()
    val cloudFullResSize by viewModel.cloudFullResSize.collectAsStateWithLifecycle()
    val isStrippingMetadata by viewModel.isStrippingMetadata.collectAsStateWithLifecycle()
    val photoTags by viewModel.currentPhotoTags.collectAsStateWithLifecycle()
    val isHidden by viewModel.isHidden.collectAsStateWithLifecycle()
    val isFavorite by viewModel.isFavorite.collectAsStateWithLifecycle()
    val isOffline by viewModel.isOffline.collectAsStateWithLifecycle()
    val isMotionPhoto by viewModel.isMotionPhoto.collectAsStateWithLifecycle()
    val motionVideoFile by viewModel.motionVideoFile.collectAsStateWithLifecycle()
    val isExtractingMotion by viewModel.isExtractingMotion.collectAsStateWithLifecycle()
    val isPanorama by viewModel.isPanorama.collectAsStateWithLifecycle()
    val isPanoramaMode by viewModel.isPanoramaMode.collectAsStateWithLifecycle()
    val albums by viewModel.albums.collectAsStateWithLifecycle()
    val isAddingToAlbum by viewModel.isAddingToAlbum.collectAsStateWithLifecycle()
    val isSavingToDevice by viewModel.isSavingToDevice.collectAsStateWithLifecycle()
    val isSharing by viewModel.isSharing.collectAsStateWithLifecycle()
    // Live cloud→device twin map: flips the bottom badge to "synced" once a download persists a
    // SyncState, which the static `items` snapshot can't reflect.
    val localUriByLinkId by viewModel.localUriByLinkId.collectAsStateWithLifecycle()
    // Which vaulted photos kept a Drive copy, for the status badge on the info pill. A vaulted photo
    // opens here as LocalOnly however it was reached, so its own type cannot answer this.
    val pairedVaultUris by viewModel.pairedVaultUris.collectAsStateWithLifecycle()

    LaunchedEffect(pagerState.settledPage, pageGeneration) {
        val item = items.getOrNull(pagerState.settledPage)
        if (item != null) {
            // Item-aware so a hidden cloud photo (hidden by linkId, no device uri) also resolves as
            // hidden and the menu offers Unhide instead of Hide.
            viewModel.checkIfHidden(item)
            viewModel.checkIfFavorite(item)
        }
    }

    // Whether the photo on screen is one the vault holds, and what that leaves it able to do. Read
    // off the item itself rather than off the surface the viewer was opened from, so the answer is
    // the same however the photo was reached. See [PhotoViewerVaultGate].
    val gatedItem = items.getOrNull(pagerState.settledPage)
    val isVaultedItem = remember(gatedItem) {
        PhotoViewerVaultGate.vaultUriOf(gatedItem, viewModel::isVaultUri) != null
    }
    val outbound = remember(gatedItem, isVaultedItem) {
        PhotoViewerVaultGate.outboundActions(gatedItem, isVaultedItem)
    }

    LaunchedEffect(Unit) { viewModel.loadAlbums() }
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    val transientError by viewModel.transientError.collectAsStateWithLifecycle()
    LaunchedEffect(transientError) {
        val msg = transientError ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(msg)
        viewModel.clearTransientError()
    }
    // These collectors share one repeatOnLifecycle(STARTED) wrap so they pause together when the
    // viewer is backgrounded (otherwise a mid-background emit snackbars against a hidden host).
    val addedToAlbumTemplate = stringResource(R.string.viewer_added_to_album)
    val coverUpdatedMessage = stringResource(R.string.album_cover_updated)
    val shareChooserTitle = stringResource(R.string.share_chooser_title)
    val shareContext = LocalContext.current
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    LaunchedEffect(Unit) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
            launch {
                viewModel.addToAlbumDone.collect { albumName ->
                    snackbarHostState.showSnackbar(String.format(addedToAlbumTemplate, albumName))
                }
            }
            launch {
                viewModel.setCoverDone.collect {
                    snackbarHostState.showSnackbar(coverUpdatedMessage)
                }
            }
            launch {
                viewModel.offlineMessage.collect { msg ->
                    snackbarHostState.showSnackbar(msg)
                }
            }
            // In the STARTED block so a backgrounded viewer doesn't pop the chooser over another screen.
            launch {
                viewModel.shareIntent.collect { intent ->
                    shareContext.startActivity(Intent.createChooser(intent, shareChooserTitle))
                }
            }
        }
    }

    LaunchedEffect(pagerState.settledPage, pageGeneration, editedAt) {
        // editedAt bumps after an editor save, re-running this effect to re-read fresh bytes
        // (the URI's Coil memory cache was nuked in invalidateImageCache, so this reads from disk).
        when (val item = items.getOrNull(pagerState.settledPage)) {
            is GalleryItem.LocalOnly  -> viewModel.loadLocal(item.local.uri, item.local.mimeType)
            is GalleryItem.Synced     -> viewModel.loadLocal(item.local.uri, item.local.mimeType)
            is GalleryItem.CloudOnly  -> viewModel.loadCloud(item.cloud)
            null -> {}
        }
    }

    // People-in-this-photo bar: fold it away on every settle, then read the settled photo's grouped
    // faces. The read is a no-op with AI off, so nothing is queried for a user who never opted in.
    LaunchedEffect(pagerState.settledPage, pageGeneration) {
        facesMode = false
        settledImageSize = androidx.compose.ui.unit.IntSize.Zero
        items.getOrNull(pagerState.settledPage)?.let { viewModel.loadPeopleInPhoto(it) }
    }

    // Keyed on `state` (not just the page) so detection runs once the still is actually showing —
    // a cloud image only becomes a file:// after the full-res download lands.
    LaunchedEffect(state, pagerState.settledPage, pageGeneration) {
        val item = items.getOrNull(pagerState.settledPage) ?: return@LaunchedEffect
        val s = state as? PhotoViewerViewModel.ViewerState.ShowImage ?: return@LaunchedEffect
        val mime = when (item) {
            is GalleryItem.LocalOnly -> item.local.mimeType
            is GalleryItem.Synced    -> item.local.mimeType
            is GalleryItem.CloudOnly -> item.cloud.mimeType
        }
        if (!mime.startsWith("image/")) return@LaunchedEffect
        // Only a real on-disk/content URI is probeable — the CDN thumbnail URL (a remote
        // String model) isn't a motion-photo source, so skip until the full-res lands.
        val model = s.model
        if (model is Uri) {
            viewModel.detectMotionPhoto(
                uri = model.toString(),
                itemKey = s.itemKey,
            )
        }
    }
    // Stop inline motion playback (and delete the extracted temp) when the viewer leaves the
    // composition, so a clip can't outlive the screen.
    DisposableEffect(Unit) { onDispose { viewModel.stopMotionPhoto() } }

    // Panorama probe (image items only); the VM scans off-thread, guarded against a mid-scan swipe.
    LaunchedEffect(pagerState.settledPage, pageGeneration) {
        val item = items.getOrNull(pagerState.settledPage)
        val (uri, itemKey, isImage) = when (item) {
            is GalleryItem.LocalOnly -> Triple(
                Uri.parse(item.local.uri), item.local.uri, item.local.mimeType.startsWith("image/"),
            )
            is GalleryItem.Synced -> Triple(
                Uri.parse(item.local.uri), item.local.uri, item.local.mimeType.startsWith("image/"),
            )
            is GalleryItem.CloudOnly -> Triple(
                null, item.cloud.linkId, item.cloud.mimeType.startsWith("image/"),
            )
            null -> Triple(null, null, false)
        }
        // For a cloud-only photo there's no local Uri to scan; detectPanorama still inspects
        // the Panoramas server tag in that branch.
        if (isImage) viewModel.detectPanorama(item, uri, itemKey)
    }

    var scale  by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var containerSize by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }
    // Pan distance accumulated past the image edge while zoomed; crossing the threshold pages.
    var edgeOverpan by remember { mutableFloatStateOf(0f) }
    // The zoom and pan the page was sitting at when text mode took it over. Text mode moves the
    // photo for its own reasons, so leaving it hands back what the user had rather than the
    // fit the mode chose. Null whenever there is nothing owed back.
    var textZoomBefore by remember { mutableStateOf<ViewerZoom?>(null) }
    LaunchedEffect(pagerState.settledPage, pageGeneration) { scale = 1f; offset = Offset.Zero; edgeOverpan = 0f }

    val transformState = rememberTransformableState { zoomChange, panChange, _ ->
        // The user's own fingers on the photo outrank anything text mode set up: from here the page
        // is theirs and there is no earlier zoom left to restore.
        textZoomBefore = null
        scale = (scale * zoomChange).coerceIn(1f, 6f)
        if (scale > 1f) {
            // graphicsLayer scales around center, so the reachable pan is ±(viewport*(scale-1)/2).
            val maxX = (containerSize.width  * (scale - 1f)) / 2f
            val maxY = (containerSize.height * (scale - 1f)) / 2f
            val unclamped = offset + panChange
            offset = Offset(
                unclamped.x.coerceIn(-maxX, maxX),
                unclamped.y.coerceIn(-maxY, maxY),
            )
            // Edge-paging: a pure pan (no active pinch) pushing past the horizontal
            // bound accumulates; crossing the threshold advances the pager the same
            // direction the finger travels. Any in-bounds pan resets the accumulator
            // so casual panning never triggers it.
            if (kotlin.math.abs(zoomChange - 1f) < 0.001f) {
                when {
                    unclamped.x < -maxX -> edgeOverpan += (-maxX - unclamped.x)
                    unclamped.x >  maxX -> edgeOverpan -= (unclamped.x - maxX)
                    else                -> edgeOverpan = 0f
                }
                val threshold = 140f
                if (edgeOverpan > threshold && pagerState.currentPage < items.lastIndex) {
                    edgeOverpan = 0f
                    scope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) }
                } else if (edgeOverpan < -threshold && pagerState.currentPage > 0) {
                    edgeOverpan = 0f
                    scope.launch { pagerState.animateScrollToPage(pagerState.currentPage - 1) }
                }
            }
        } else {
            offset = Offset.Zero
        }
    }

    // Per-page full-res image cache: keeps the last loaded image so non-settled pages
    // don't visually drop quality to thumbnail while the exit animation is still playing.
    val pageImageCache = remember { mutableMapOf<Int, Any>() }
    // When the editor pops back after a save, drop everything we cached for the touched
    // page — otherwise the bitmap held in this map is the stale one rendered by Coil
    // BEFORE the cache wipe and the user sees the pre-edit version until they page away.
    LaunchedEffect(editedAt) {
        if (editedAt != 0L) {
            pageImageCache.remove(pagerState.settledPage)
        }
    }
    LaunchedEffect(state, pagerState.settledPage, pageGeneration) {
        if (state is PhotoViewerViewModel.ViewerState.ShowImage) {
            val page = pagerState.settledPage
            pageImageCache[page] = (state as PhotoViewerViewModel.ViewerState.ShowImage).model
            // Bound the cache to a small window around the current page so paging a large album
            // doesn't retain one full-res model per visited page for the whole viewer session.
            pageImageCache.keys.retainAll { it in (page - 2)..(page + 2) }
        }
    }

    // Video state — reset when page changes
    var videoStarted  by remember { mutableStateOf(false) }
    var currentPlayer by remember { mutableStateOf<ExoPlayer?>(null) }
    var isVideoPlaying by remember { mutableStateOf(false) }
    // Flipped true the instant a back is initiated, BEFORE the pop animation runs, so the live
    // PlayerView surface is torn out of the composition immediately and the already-drawn
    // thumbnail/background fades instead of a lingering video frame. The route's popExitTransition
    // would otherwise keep the surface alive through its 180ms fade.
    var exiting by remember { mutableStateOf(false) }
    // Pause the current player and drop the video surface a frame before the back navigation, so
    // the fade animates over a still rather than a playing surface.
    val startExit = {
        currentPlayer?.let { runCatching { it.playWhenReady = false } }
        exiting = true
        onBack(anchorKey.takeIf { it != openedKey })
    }
    // Route the system/gesture back through the same teardown so a hardware back doesn't leave the
    // playing surface to linger through the pop fade either.
    androidx.activity.compose.BackHandler(enabled = !exiting) { startExit() }

    // ── Read the text on the photo ────────────────────────────────────────────
    var textState by remember { mutableStateOf<ViewerTextState>(ViewerTextState.Idle) }
    var textJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    // Whether the user has any of the read words picked out, filled in by the selectable layer. A tap
    // means one thing while something is picked and another while nothing is, and the two are told
    // apart by what is actually selected rather than by counting taps, so a photo the user has
    // selected nothing in still leaves on the first one.
    val textSelection = remember { ViewerTextSelection() }
    // Handing the container its focus back is what the platform itself treats as the end of a
    // selection: the handles and the floating toolbar go with it, and the words stay up.
    val textFocus = androidx.compose.ui.platform.LocalFocusManager.current
    // Registered after the exit handler above on purpose: back dispatch runs handlers in reverse
    // registration order, so this one gets first refusal while highlights are up and back still
    // closes the viewer at every other moment.
    androidx.activity.compose.BackHandler(enabled = textState !is ViewerTextState.Idle) {
        textJob?.cancel()
        textJob = null
        textState = ViewerTextState.Idle
    }
    // Highlights belong to the photo they were read from, so a swipe drops them and any read still
    // in flight with them.
    LaunchedEffect(pagerState.settledPage, pageGeneration) {
        textJob?.cancel()
        textJob = null
        textState = ViewerTextState.Idle
        // A page change puts the page back at fit-to-screen on its own, so there is no earlier zoom
        // left to give back and nothing to animate towards.
        textZoomBefore = null
    }
    val textRecognizer = rememberTextRecognizer()
    val showingText = textState as? ViewerTextState.Showing
    // Raised when the detection model is not on the device yet: the fetch is several megabytes and
    // is never started without an answer.
    var askForTextModel by remember { mutableStateOf(false) }
    val textContext = LocalContext.current
    val textHaptics = androidx.compose.ui.platform.LocalHapticFeedback.current
    val textNoneMsg = stringResource(R.string.viewer_text_none)
    val textNeedsFullSizeMsg = stringResource(R.string.viewer_text_needs_full_size)
    val textNotImageMsg = stringResource(R.string.viewer_text_not_image)
    val textUnavailableMsg = stringResource(R.string.viewer_text_unavailable)
    val textTooLargeMsg = stringResource(R.string.viewer_text_too_large)
    val textModelFailedMsg = stringResource(R.string.viewer_text_model_failed)
    // What the bars actually take on this device, asked of the platform rather than assumed: a
    // cutout, a three-button navigation bar and a gesture pill all give different answers, and the
    // squeeze is only exact if it is the device's own numbers.
    val textInsets = WindowInsets.systemBars.union(WindowInsets.displayCutout)
    val textInsetDensity = LocalDensity.current
    val textInsetDirection = androidx.compose.ui.platform.LocalLayoutDirection.current
    val textInsetLeft = textInsets.getLeft(textInsetDensity, textInsetDirection).toFloat()
    val textInsetTop = textInsets.getTop(textInsetDensity).toFloat()
    val textInsetRight = textInsets.getRight(textInsetDensity, textInsetDirection).toFloat()
    // The mode pill sits at the foot of the page on top of the navigation bar, so the squeeze has to
    // give up its band too. Without it the pill covers the photo's last line, which on a screenshot
    // is exactly the text the squeeze exists to bring into view.
    val textInsetBottom = textInsets.getBottom(textInsetDensity).toFloat() +
        with(textInsetDensity) { ViewerTextPillReserve.toPx() }
    // A viewer page runs edge to edge, so at fit-to-screen the top line of a screenshot sits behind
    // the status bar and its bottom behind the gesture area. Entering text mode squeezes the photo
    // into what the bars leave alone so every run the reader found can actually be read, and leaving
    // walks the page back to whatever zoom and pan the user had before it.
    LaunchedEffect(showingText) {
        // Every way out of text mode passes through here, and the layer that owns the answer is on
        // its way off screen, so this is where a stale pick is dropped.
        if (showingText == null) textSelection.active = false
        val live = { ViewerZoom(scale, offset.x, offset.y) }
        val onFrame: (ViewerZoom) -> Unit = { zoom ->
            scale = zoom.scale
            offset = Offset(zoom.offsetX, zoom.offsetY)
        }
        if (showingText != null) {
            if (containerSize.width <= 0 || containerSize.height <= 0) return@LaunchedEffect
            textZoomBefore = live()
            animateViewerZoom(
                from = live(),
                to = fitPhotoInsideInsets(
                    imageWidth = showingText.imageWidth,
                    imageHeight = showingText.imageHeight,
                    containerW = containerSize.width.toFloat(),
                    containerH = containerSize.height.toFloat(),
                    insetLeft = textInsetLeft,
                    insetTop = textInsetTop,
                    insetRight = textInsetRight,
                    insetBottom = textInsetBottom,
                ),
                onFrame = onFrame,
            )
        } else {
            val restore = textZoomBefore ?: return@LaunchedEffect
            textZoomBefore = null
            animateViewerZoom(from = live(), to = restore, onFrame = onFrame)
        }
    }
    val readTextOnPhoto = {
        val settled = items.getOrNull(pagerState.settledPage)
        val settledLinkId = when (settled) {
            is GalleryItem.CloudOnly -> settled.cloud.linkId
            is GalleryItem.Synced    -> settled.cloud.linkId
            else -> null
        }
        // A panorama and an inline motion clip each put their own frame on screen under their own
        // placement, so the fit the overlay works from would not be the one the user is looking at.
        // A photo the device hide keeps behind a blur is not one to lift words off either.
        val blocked = isPanoramaMode ||
            motionVideoFile != null ||
            (settledLinkId != null && settledLinkId in hiddenCloudLinkIds)
        if (!blocked) {
            textHaptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
            textJob?.cancel()
            textJob = scope.launch {
                // The models come first, and only once per install. Their own stages on the pill: a
                // twenty megabyte fetch announced as "reading" looks like a hung read.
                textState = ViewerTextState.Working(ViewerTextStage.PreparingModel)
                val prepared = textRecognizer.prepare {
                    textState = ViewerTextState.Working(ViewerTextStage.DownloadingModel)
                }
                if (prepared != OcrModelPreparation.Ready) {
                    textState = ViewerTextState.Idle
                    if (prepared == OcrModelPreparation.NeedsConsent) {
                        askForTextModel = true
                    } else {
                        snackbarHostState.showSnackbar(textModelFailedMsg)
                    }
                    return@launch
                }
                textState = ViewerTextState.Working(ViewerTextStage.Detecting)
                val failure = try {
                    val pixels = ViewerPixels.capture(textContext, state)
                    when (pixels) {
                        is ViewerPixels.Ok -> {
                            // Read before the recycle below: a recycled bitmap refuses to report its
                            // own size, and the blocks mean nothing without the frame they index.
                            val frameW = pixels.width
                            val frameH = pixels.height
                            val found = try {
                                textRecognizer.recognize(pixels.bitmap) { stage ->
                                    textState = ViewerTextState.Working(stage.asViewerStage())
                                }
                            } finally {
                                // A 4096px frame as ARGB_8888 is tens of megabytes, on top of the
                                // full-res bitmap and the cached neighbours this viewer already
                                // holds. Nothing needs it once the blocks are out.
                                pixels.bitmap.recycle()
                            }
                            if (found.isEmpty()) {
                                textNoneMsg
                            } else {
                                textState = ViewerTextState.Showing(found, frameW, frameH)
                                null
                            }
                        }
                        ViewerPixels.NoFullResolution -> textNeedsFullSizeMsg
                        ViewerPixels.NotAnImage -> textNotImageMsg
                        ViewerPixels.Unavailable -> textUnavailableMsg
                        ViewerPixels.OutOfMemory -> textTooLargeMsg
                    }
                } catch (_: OutOfMemoryError) {
                    textTooLargeMsg
                }
                if (failure != null) {
                    textState = ViewerTextState.Idle
                    snackbarHostState.showSnackbar(failure)
                }
            }
        }
    }
    if (askForTextModel) {
        ViewerTextModelDialog(
            downloadBytes = textRecognizer.downloadBytes,
            onConfirm = {
                askForTextModel = false
                // The recognizer takes the answer before it is written to storage, so the read this
                // starts cannot outrun the write and ask a second time. Re-checked so a switch flipped
                // off while the consent dialog was up cannot start a read.
                textRecognizer.allowModelDownload()
                if (copyTextEnabled) readTextOnPhoto()
            },
            onDismiss = { askForTextModel = false },
        )
    }

    // Latches true the first time ExoPlayer reports it's actually playing on this page,
    // so the loading badge keeps showing through download + prepare + first-paint and
    // then disappears for good (ordinary pause/resume after that doesn't bring it back).
    var videoEverPlayed by remember { mutableStateOf(false) }
    LaunchedEffect(pagerState.settledPage, pageGeneration) {
        // Reset playback flags but DO NOT null currentPlayer here. The composable for the
        // new page builds a fresh ExoPlayer in its own remember(uri) block and the previous
        // page's player is released by its DisposableEffect onDispose — nulling here added
        // a third source-of-truth race where the new page would re-bind to `null` after the
        // remember had already set it to the new player, leaving the control pill with no
        // handle and the surface stuck on the previous frame.
        videoStarted  = false
        isVideoPlaying = false
        videoEverPlayed = false
    }
    LaunchedEffect(isVideoPlaying) {
        if (isVideoPlaying) videoEverPlayed = true
    }
    // Auto-start playback once the full-res video URI arrives — matches native gallery
    // behavior where a tapped video begins playing immediately instead of asking for a
    // second tap on a play overlay. Without this, tapping the play pill leaves a black
    // frame because the player is still in the paused first-frame state. The
    // pause/resume toggle still works after auto-start.
    LaunchedEffect(state) {
        if (state is PhotoViewerViewModel.ViewerState.ShowVideo) {
            videoStarted = true
        }
    }
    // Poll the actual ExoPlayer isPlaying flag so the bottom bar reacts to pause/resume.
    LaunchedEffect(currentPlayer) {
        val p = currentPlayer
        if (p == null) { isVideoPlaying = false; return@LaunchedEffect }
        while (true) {
            isVideoPlaying = p.isPlaying
            delay(200)
        }
    }

    // Slideshow play/pause — saved across rotation so the user doesn't lose their state.
    var isPlaying by rememberSaveable { mutableStateOf(startSlideshow) }

    // Overlay visibility: tap image to toggle. Deliberately NOT reset on page change —
    // once the user hides the chrome they stay in immersive browsing across swipes
    // until they tap again.
    var showOverlays by remember { mutableStateOf(true) }

    // Reading the text on a photo is a focused mode, so the bars step out of the way for it: the
    // photo runs on behind them, which is exactly where a highlight near the edge would otherwise
    // land, and a filmstrip competes with the words the user asked to see. Derived from the same
    // flag the tap gesture toggles rather than written into it, so leaving text mode gives back
    // whatever the user had before it.
    val showChrome = showOverlays && textState !is ViewerTextState.Showing

    // Pause if the user single-taps anywhere on a photo (the same gesture that
    // toggles chrome). We do this by observing [showOverlays] — when it flips from
    // hidden to visible while playing, the user just tapped, so stop the slideshow.
    // (Overlay visibility persists across page changes, so only a real tap can flip
    //  it to visible while the slideshow runs.)
    LaunchedEffect(showOverlays, isPlaying) {
        if (isPlaying && showOverlays) {
            // Give the user 1 second to see the chrome before re-hiding it. If they
            // tap to pause the slideshow, isPlaying flips to false first and this
            // effect cancels before the delay completes — chrome stays visible.
            delay(1000)
            if (isPlaying) showOverlays = false
        }
    }

    // Tick: every 4 seconds, advance to the next page. Loops back to 0 from the end.
    // For VIDEOS we hold the advance until either the clip ends (player flips out of
    // playing state on its own) or the player hasn't started playing within the first
    // 8 seconds (defensive timeout — covers a stuck-buffering edge case so a single
    // broken file can't freeze the slideshow). Photos still advance on the steady
    // 4-second tick. Key on [settledPage] (not currentPage) so the timer doesn't
    // restart mid-animation when currentPage briefly flips as the pager crosses the
    // threshold.
    LaunchedEffect(isPlaying, pagerState.settledPage, pageGeneration) {
        if (!isPlaying) return@LaunchedEffect
        val settledItem = items.getOrNull(pagerState.settledPage)
        val isVideoItem = when (settledItem) {
            is GalleryItem.LocalOnly -> settledItem.local.mimeType.startsWith("video/")
            is GalleryItem.Synced    -> settledItem.local.mimeType.startsWith("video/")
            is GalleryItem.CloudOnly -> settledItem.cloud.mimeType.startsWith("video/")
            null -> false
        }
        if (isVideoItem) {
            // Give the player up to 8 seconds to start; once playing, wait for it to
            // actually stop (clip ended, or buffer underrun → not-playing). Avoids the
            // 4-second forced-skip mid-clip.
            val startDeadline = System.currentTimeMillis() + 8_000L
            while (isPlaying && !isVideoPlaying && System.currentTimeMillis() < startDeadline) {
                delay(200)
            }
            // Wait while the video keeps playing — exits when isVideoPlaying drops to
            // false (natural end of clip OR REPEAT_MODE_ONE loop boundary; the latter
            // would otherwise lock the slideshow forever, so we bail after a max-watch
            // ceiling tied to slideshow_video_max_secs below).
            val maxWatchMs = 60_000L
            val watchDeadline = System.currentTimeMillis() + maxWatchMs
            while (isPlaying && isVideoPlaying && System.currentTimeMillis() < watchDeadline) {
                delay(200)
            }
        } else {
            delay(4000)
        }
        if (isPlaying) {
            val next = (pagerState.settledPage + 1) % items.size
            pagerState.animateScrollToPage(next)
        }
    }

    var showMetadata by remember { mutableStateOf(false) }
    var showStripPicker by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }
    val renameState by viewModel.renameState.collectAsStateWithLifecycle()
    val metadataSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    var showDeleteSheet by remember { mutableStateOf(false) }
    // stableId of the photo the running delete will take off the screen, or null when the running
    // delete only converts it (see DeletePhotoUseCase.removesFromGallery). Captured when the sheet's
    // button is pressed rather than read back when the delete finishes, so nothing depends on where
    // the pager happens to sit by then.
    var pendingRemoval by remember { mutableStateOf<String?>(null) }
    var showAddToAlbumSheet by remember { mutableStateOf(false) }
    val addToAlbumSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    // Set when "New album" is tapped in the add-to-album sheet: holds the photo to drop into the
    // album the create dialog makes.
    var newAlbumForPhoto by remember { mutableStateOf<GalleryItem?>(null) }
    val deleteState by viewModel.deleteState.collectAsStateWithLifecycle()

    // Unified single-photo share drawer (Send to app / Share with people / Public link).
    var showShareSheet by remember { mutableStateOf(false) }
    val shareSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    // The dedicated public-link management sheet, opened from the drawer's "Public link" row.
    var showManageLinkSheet by remember { mutableStateOf(false) }
    val manageLinkSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val publicLinkState by viewModel.publicLinkState.collectAsStateWithLifecycle()
    val linkCopiedMsg = stringResource(R.string.share_link_copied)
    val passwordSetMsg = stringResource(R.string.share_password_set)
    val passwordRemovedMsg = stringResource(R.string.share_password_removed)

    // Android 11+ system trash dialog launcher
    val deletePermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            viewModel.onDeletePermissionGranted()
        } else {
            // Declining leaves the photo where it was, so the id noted for its removal has to go
            // with the decision. This lands on Idle, which the state handler passes over, so
            // clearing it there would never happen and the next hide would take this photo instead.
            pendingRemoval = null
            viewModel.resetDeleteState()
        }
    }

    // Android 10+ write-permission dialog launcher for stripping metadata from a
    // non-app-owned file (mirrors the delete launcher above).
    val stripState by viewModel.stripState.collectAsStateWithLifecycle()
    val stripPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) viewModel.retryPendingStrip()
        else viewModel.resetStripState()
    }
    // Android 11+ write-permission dialog for renaming a non-app-owned file in place.
    val renamePermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) viewModel.retryPendingRename()
        else viewModel.resetRenameState()
    }
    LaunchedEffect(stripState) {
        val ss = stripState
        if (ss is PhotoViewerViewModel.StripState.NeedsPermission) {
            // An OEM that throws on the system sender, or a sender already spent across a
            // configuration change, would otherwise take the app down here.
            runCatching {
                stripPermissionLauncher.launch(
                    IntentSenderRequest.Builder(ss.pendingIntent.intentSender).build()
                )
            }.onFailure { viewModel.resetStripState() }
        }
    }

    // Handle delete state changes
    LaunchedEffect(deleteState) {
        when (val ds = deleteState) {
            is PhotoViewerViewModel.DeleteState.Done -> {
                viewModel.resetDeleteState()
                val removed = pendingRemoval
                pendingRemoval = null
                when {
                    // Nothing left to look at, and the photo that was on screen is the one just
                    // deleted, so there is nowhere to send the grid back to.
                    removed != null && items.size <= 1 -> onBack(null)
                    removed != null -> {
                        // Cached full-res images are keyed by page index, and every index past the
                        // deleted one is about to shift down, so a stale entry would paint the
                        // deleted photo over its successor.
                        pageImageCache.clear()
                        // Bumps pageGeneration, which re-runs every per-page effect against the
                        // photo that now holds this index (including the one that re-anchors).
                        removedIds.add(removed)
                    }
                    // A delete that only converted the photo (freeing its device copy, or trashing
                    // its cloud copy) leaves it on screen in its new form. Stay on it.
                    else -> Unit
                }
            }
            is PhotoViewerViewModel.DeleteState.NeedsPermission -> {
                // Launch the Android system "Move to trash" dialog
                runCatching {
                    deletePermissionLauncher.launch(
                        IntentSenderRequest.Builder(ds.pendingIntent.intentSender).build()
                    )
                }.onFailure { pendingRemoval = null; viewModel.resetDeleteState() }
            }
            is PhotoViewerViewModel.DeleteState.Failed -> {
                // Surface the failure as a themed snackbar (same in-app pattern as the
                // other viewer feedback) and reset the state so the delete-overlay
                // spinner doesn't get pinned to the screen forever (the "Not signed in"
                // case in particular ended up with a permanent Failed overlay that the
                // user couldn't dismiss). The reset moves us back to Idle so the next
                // user action can proceed.
                // The photo is still there, so the id noted for its removal must go: hiding drives
                // the same Done state without noting one of its own, and it would have read this
                // stale id and taken the wrong photo out of the pager.
                pendingRemoval = null
                snackbarHostState.showSnackbar(ds.message)
                viewModel.resetDeleteState()
            }
            else -> {}
        }
    }

    // A photo taken out of the album this viewer is showing leaves the pager the same way a deleted
    // one does. Read through rememberUpdatedState because the collector below is started once and
    // would otherwise measure the list as it stood at first composition.
    val currentItems by rememberUpdatedState(items)
    // Not lifecycle-gated, unlike the snackbar collectors: the signal carries no replay, so a viewer
    // paused mid-removal would miss it and keep showing a photo the album no longer holds.
    LaunchedEffect(sourceAlbumLinkId) {
        val albumLinkId = sourceAlbumLinkId ?: return@LaunchedEffect
        viewModel.removeFromAlbumDone.collect { removal ->
            // The add-to-album sheet can remove from any album the photo belongs to, and only the
            // one the viewer was opened from decides what this pager holds.
            if (removal.albumLinkId != albumLinkId) return@collect
            if (currentItems.size <= 1) {
                // Nothing left to look at, and the photo on screen is the one just taken out, so
                // there is nowhere to send the grid back to.
                onBack(null)
            } else {
                // Cached full-res images are keyed by page index, and every index past the removed
                // one is about to shift down, so a stale entry would paint the removed photo over
                // its successor.
                pageImageCache.clear()
                // Bumps pageGeneration, which re-runs every per-page effect against the photo that
                // now holds this index (including the one that re-anchors).
                removedIds.add(removal.photoLinkId)
            }
        }
    }

    // Every viewer gesture is detected here on the stable root box, not on the per-page box inside
    // the pager. On a fresh open (and the instant a swipe settles) the pager rebuilds the current
    // page's box as it lands, so a first tap or long press arriving in that window is lost on a
    // per-page detector. This root box is not rebuilt by that settle, so it catches the very first
    // press. The pager and the image transform sit below and take horizontal swipes and pinch first;
    // a tap, double tap, long press, or upward drag they do not consume lands here.
    val rootReadText   by rememberUpdatedState(readTextOnPhoto)
    val rootCopyTextOn by rememberUpdatedState(copyTextEnabled)
    val rootTextState  by rememberUpdatedState(textState)
    val rootMetaShown  by rememberUpdatedState(showMetadata)
    val rootScale      by rememberUpdatedState(scale)
    val rootSlideshow  by rememberUpdatedState(isPlaying)
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Bg0)
            .onSizeChanged { containerSize = it }
            .pointerInput("viewer-taps") {
                // Hand-rolled tap, double tap and long press. The down is read even if a lower node
                // consumed it (so the first press on a freshly landed page is not lost), and a tap
                // acts only when its up was not consumed, so a chrome button keeps its own tap.
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val slop = viewConfiguration.touchSlop
                    val longPressMs = viewConfiguration.longPressTimeoutMillis
                    val readIfAllowed = {
                        if (rootCopyTextOn && rootTextState !is ViewerTextState.Showing && !rootMetaShown) rootReadText()
                    }
                    val toggleChrome = {
                        if (rootTextState is ViewerTextState.Showing) {
                            when (viewerTextTap(textSelection.active)) {
                                ViewerTextTap.ClearSelection -> textFocus.clearFocus()
                                ViewerTextTap.LeaveTextMode -> textState = ViewerTextState.Idle
                            }
                        } else if (rootSlideshow) {
                            isPlaying = false
                            showOverlays = true
                        } else {
                            showOverlays = !showOverlays
                        }
                    }
                    val zoomToward = { p: Offset ->
                        textZoomBefore = null
                        if (rootScale > 1f) {
                            scale = 1f
                            offset = Offset.Zero
                        } else {
                            val newScale = 2.5f
                            val cx = containerSize.width / 2f
                            val cy = containerSize.height / 2f
                            val maxX = (containerSize.width * (newScale - 1f)) / 2f
                            val maxY = (containerSize.height * (newScale - 1f)) / 2f
                            scale = newScale
                            offset = Offset(
                                ((cx - p.x) * (newScale - 1f)).coerceIn(-maxX, maxX),
                                ((cy - p.y) * (newScale - 1f)).coerceIn(-maxY, maxY),
                            )
                        }
                    }
                    when (val first = awaitViewerTapOutcome(down.id, down.position, slop, longPressMs)) {
                        ViewerTapOutcome.HeldLong -> readIfAllowed()
                        ViewerTapOutcome.Cancelled -> Unit
                        // A tap whose up a control consumed (a chrome button) belongs to that control,
                        // so tapping the top or bottom bar uses the button without also hiding the bar.
                        is ViewerTapOutcome.Lifted -> if (!first.change.isConsumed) {
                            val second = withTimeoutOrNull(viewConfiguration.doubleTapTimeoutMillis) {
                                awaitFirstDown(requireUnconsumed = false)
                            }
                            if (second == null) {
                                toggleChrome()
                            } else when (awaitViewerTapOutcome(second.id, second.position, slop, longPressMs)) {
                                is ViewerTapOutcome.Lifted -> zoomToward(second.position)
                                ViewerTapOutcome.HeldLong -> { toggleChrome(); readIfAllowed() }
                                ViewerTapOutcome.Cancelled -> toggleChrome()
                            }
                        }
                    }
                }
            }
            .pointerInput("viewer-swipe-up") {
                // Swipe up opens details when not zoomed and not reading text. Its own detector so it
                // does not disturb the tap gesture above.
                detectVerticalDragGestures { _, dragAmount ->
                    if (rootScale <= 1f && !rootMetaShown && dragAmount < -40f &&
                        rootTextState !is ViewerTextState.Showing
                    ) {
                        showMetadata = true
                        showOverlays = true
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        // ── Pager ──────────────────────────────────────────────────────────────
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
            // Key each page slot to its item's stable identity so a live reconcile rebinds per-page
            // state (video flags, painted-thumb gate) to the photo rather than the position. When a
            // LocalOnly is swapped for the Synced it became, its stable id changes (uri → linkId)
            // and that one page rebuilds cleanly against the now-backed-up item while the user stays
            // on it.
            key = { page -> items.getOrNull(page)?.stableId ?: page },
            // Page-swipe is suppressed while zoomed (scale > 1f), while panorama mode is active so its
            // own horizontal drag doesn't fight the pager, and while recognised text is up: a selection
            // is dragged horizontally, so paging there would swap the photo out from under the words the
            // user is picking.
            userScrollEnabled = scale <= 1f && !isPanoramaMode && textState !is ViewerTextState.Showing,
        ) { page ->
            val item      = items.getOrNull(page)
            val isSettled = page == pagerState.settledPage

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    // Theme-reactive page background: pure black viewer chrome in dark
                    // mode, white in light mode. Bg0 also covers the one-frame window
                    // between the thumb hiding and the full-res draw, so no off-theme
                    // flash can show through.
                    .background(Bg0),
                contentAlignment = Alignment.Center,
            ) {
                // Videos: Coil's VideoFrameDecoder grabs a frame via MediaMetadataRetriever, which
                // ignores the MP4 rotation atom → a sideways full-screen poster flash before the
                // player paints. Use the correctly-oriented cloud thumbnail when there is one
                // (Synced/CloudOnly); for a not-yet-uploaded local video draw no poster at all (the
                // themed background covers the brief pre-first-frame gap) rather than flash the
                // rotation-broken frame. Photos keep their local-URI poster (Coil honours EXIF).
                // The timeline projection no longer carries a cloud row's thumbnail URL, so resolve it
                // from the shared store (falling back to any URL still on the item, e.g. an album row).
                val thumbUrls = LocalThumbnailUrls.current.value
                val thumbModel: Any? = when (item) {
                    is GalleryItem.LocalOnly ->
                        if (item.local.mimeType.startsWith("video/")) null
                        else Uri.parse(item.local.uri)
                    is GalleryItem.Synced ->
                        if (item.local.mimeType.startsWith("video/"))
                            thumbUrls[item.cloud.linkId] ?: item.cloud.thumbnailUrl
                        else Uri.parse(item.local.uri)
                    is GalleryItem.CloudOnly -> thumbUrls[item.cloud.linkId] ?: item.cloud.thumbnailUrl
                    null -> null
                }
                // Skip the AsyncImage poster for local videos once the user has tapped Play.
                // Reason: for video URIs Coil uses VideoFrameDecoder → MediaMetadataRetriever
                // which spins up a hardware decoder instance for poster extraction. That
                // contends with ExoPlayer's MediaCodec init at the exact moment we want a
                // clean first-frame paint, causing visible startup stutter on real devices.
                // While videoStarted is still false we keep the poster (user is staring at
                // a frozen still); once playback begins PlayerView's surface paints over it
                // anyway so the AsyncImage was just dead memory + decoder contention.
                val isVideoItemThumb = when (item) {
                    is GalleryItem.LocalOnly -> item.local.mimeType.startsWith("video/")
                    is GalleryItem.Synced    -> item.local.mimeType.startsWith("video/")
                    is GalleryItem.CloudOnly -> item.cloud.mimeType.startsWith("video/")
                    null -> false
                }
                // Keep the thumbnail drawn UNDER the player until the first decoded frame
                // actually paints (videoEverPlayed latches on the first reported isPlaying).
                // The default SurfaceView is opaque black and punches a hole through the Compose
                // layer, so on a light theme — and on any cold open — there's a black flash
                // through download → prepare → first-paint; the poster covers it. Suppressing the
                // moment ShowVideo lands (the old behaviour) re-opened that gap, so gate on the
                // painted-a-frame signal instead.
                val suppressThumbForVideo = isSettled && isVideoItemThumb && videoEverPlayed
                // Identity of the item currently bound to this page (same expression the
                // settled-block uses below for stateMatchesPage). Lifted here so the thumb
                // gate can check whether the full-res image is actually rendering for *this*
                // page before we hide the placeholder.
                val currentItemKey: String? = when (item) {
                    is GalleryItem.LocalOnly -> item.local.uri
                    is GalleryItem.Synced    -> item.local.uri
                    is GalleryItem.CloudOnly -> item.cloud.linkId
                    null -> null
                }
                val stateMatchesPage = state.itemKey == null || state.itemKey == currentItemKey
                // True once the settled full-res image has actually painted for this page.
                // Re-armed per item so each new photo holds its thumb underneath until the
                // full-res frame is up. Keeps the thumb drawn through the full-res decode +
                // crossfade so the Bg0 background never shows through on a cold open.
                var fullResPainted by remember(currentItemKey) { mutableStateOf(false) }
                // Hide the thumb once the full-res image has painted for this settled page so
                // it stops peeking through at the edges when the user pinch-zooms and pans —
                // the full-res layer is graphicsLayer-translated, the thumb is not, and at
                // any non-centered scale>1f the thumb would otherwise show through behind.
                val suppressThumbForLoadedImage = isSettled &&
                    state is PhotoViewerViewModel.ViewerState.ShowImage &&
                    stateMatchesPage &&
                    fullResPainted
                if (thumbModel != null && !suppressThumbForVideo && !suppressThumbForLoadedImage) {
                    AsyncImage(
                        model = thumbModel,
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                // Non-settled pages: show cached full-res so they don't visually
                // downgrade to thumbnail during the exit swipe animation.
                if (!isSettled) {
                    val cached = pageImageCache[page]
                    if (cached != null) {
                        AsyncImage(
                            model = cached,
                            contentDescription = null,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
                if (isSettled) {
                    // currentItemKey + stateMatchesPage are hoisted above the thumb block so
                    // both the placeholder gate and the full-res renderer use the same
                    // page-identity check. The stateMatchesPage guard handles the one-frame
                    // window during a swipe A → B where settledPage flips to B but `state`
                    // still references A's loaded image — without it, B would flash A.
                    when (val s = state) {
                        is PhotoViewerViewModel.ViewerState.ShowImage ->
                            if (stateMatchesPage) {
                                val motionFile = motionVideoFile
                                if (isMotionPhoto && motionFile != null) {
                                    // Inline motion-photo playback: the extracted embedded clip
                                    // plays once over the still through the shared VideoPlayer,
                                    // then onEnded drops us back to the image.
                                    VideoPlayer(
                                        uri = Uri.fromFile(motionFile),
                                        autoPlay = true,
                                        onEnded = { viewModel.stopMotionPhoto() },
                                        modifier = Modifier.fillMaxSize(),
                                    )
                                } else if (isPanoramaMode) {
                                    // Immersive panorama: the still fills viewport height and
                                    // overflows horizontally (FillHeight crops width), and a
                                    // horizontal drag scrolls along the strip. Kept on a dedicated
                                    // offset + gesture so the normal pinch-zoom transform (with its
                                    // edge-paging) is left untouched.
                                    PanoramaPager(
                                        model = s.model,
                                        modifier = Modifier.fillMaxSize(),
                                    )
                                } else {
                                    // Fade the full-res in over the thumb (which stays drawn
                                    // until onState reports Success) so a cold open never shows
                                    // the background through a one-frame gap. The transform stays
                                    // on this element so pinch-zoom is unaffected.
                                    val imageContext = LocalContext.current
                                    val fullResRequest = remember(s.model) {
                                        ImageRequest.Builder(imageContext)
                                            .data(s.model)
                                            .crossfade(true)
                                            // The one request in the app that asks for the gain map
                                            // to survive the decode. An HDR bitmap costs the base
                                            // image plus a gain map plane, so it stays scoped to the
                                            // photo actually filling the screen; the thumb underlay
                                            // and the off-page render above keep the plain decode.
                                            .decodeUltraHdr()
                                            .build()
                                    }
                                    // A corrupt or unsupported file gives Coil nothing to decode, so
                                    // the viewer would sit on the bare background with no cue that a
                                    // file is even there. Track the Error state and draw a muted
                                    // broken-image glyph instead. Re-armed per item so each photo
                                    // starts clean.
                                    var fullResFailed by remember(currentItemKey) { mutableStateOf(false) }
                                    // Whether the bitmap Coil just handed back carries a gain map,
                                    // read off the decoded drawable rather than by re-opening the
                                    // file. Re-armed per item so each photo decides for itself.
                                    var fullResIsHdr by remember(currentItemKey) { mutableStateOf(false) }
                                    AsyncImage(
                                        model = fullResRequest,
                                        contentDescription = null,
                                        contentScale = ContentScale.Fit,
                                        onState = { st ->
                                            when (st) {
                                                is AsyncImagePainter.State.Success -> {
                                                    fullResPainted = true
                                                    fullResFailed = false
                                                    fullResIsHdr = st.result.drawable.hasGainMap()
                                                    // Only the settled page feeds the shared size the
                                                    // face tags + long-press hit-test read.
                                                    if (isSettled) settledImageSize =
                                                        androidx.compose.ui.unit.IntSize(
                                                            st.result.drawable.intrinsicWidth,
                                                            st.result.drawable.intrinsicHeight,
                                                        )
                                                }
                                                is AsyncImagePainter.State.Error -> fullResFailed = true
                                                else -> Unit
                                            }
                                        },
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .transformable(state = transformState, canPan = { scale > 1f })
                                            .graphicsLayer(
                                                scaleX = scale, scaleY = scale,
                                                translationX = offset.x, translationY = offset.y,
                                            ),
                                    )
                                    // A gain map only means anything once the window itself asks
                                    // for HDR. This effect lives inside the settled page's branch,
                                    // so it follows the settled photo: paging away disposes it,
                                    // and so does a back gesture or leaving the viewer at all.
                                    HdrWindowColorMode(enabled = fullResIsHdr)
                                    if (fullResFailed) {
                                        // Centered over the viewer background, a touch larger than
                                        // the grid tile's placeholder but the same muted treatment.
                                        Icon(
                                            Icons.Default.BrokenImage,
                                            contentDescription = null,
                                            tint = FgMute.copy(alpha = 0.55f),
                                            modifier = Modifier
                                                .align(Alignment.Center)
                                                .size(64.dp),
                                        )
                                    }
                                    // Face name tags over the settled photo when the menu has them on,
                                    // placed through the same fit and zoom the image rides so each tag
                                    // stays on its face while panning and pinching. A tap opens the
                                    // person.
                                    if (facesMode && stateMatchesPage && peopleInPhoto.isNotEmpty() &&
                                        settledImageSize.width > 0 && settledImageSize.height > 0
                                    ) {
                                        ViewerFaceTags(
                                            imageSize = settledImageSize,
                                            containerSize = containerSize,
                                            scale = scale,
                                            offset = offset,
                                            people = peopleInPhoto,
                                            onPersonClick = { personId ->
                                                facesMode = false
                                                onOpenPerson(personId)
                                            },
                                        )
                                    }
                                }
                            }
                        is PhotoViewerViewModel.ViewerState.ShowVideo -> {
                            // Drop the player the frame a back starts (`exiting`) so the live
                            // surface swaps out for the already-drawn thumbnail/background before
                            // the route's pop fade runs — otherwise the playing surface lingers
                            // through the 180ms animation.
                            if (stateMatchesPage && !exiting) {
                                // Render the player as soon as a full-res video URI is
                                // available — gating on `videoStarted` meant the ExoPlayer
                                // wasn't built until the user tapped the pill, but because
                                // that tap fires onPlay → videoStarted=true → only THEN
                                // does the player initialise, the press feels dead for the
                                // second it takes to prepare the MediaSource. Build the
                                // player upfront with playWhenReady gated on videoStarted
                                // so the first frame appears immediately and the play tap
                                // just flips the play/pause state on an already-prepared
                                // source. Also covers downloaded cloud videos that never
                                // started — they were waiting on a tap that the play
                                // overlay never surfaced.
                                VideoPlayer(
                                    uri = s.uri,
                                    autoPlay = videoStarted,
                                    // After an Overwrite-save the URI string is unchanged, so
                                    // a uri-only remember would reuse the ExoPlayer holding
                                    // the pre-edit MediaItem (cached buffers + indexes). Mix
                                    // editedAt into the player's identity so a save forces a
                                    // fresh prepare() against the freshly-written bytes.
                                    reloadKey = editedAt,
                                    onPlayerReady = { currentPlayer = it },
                                    // Keep the screen awake while the clip actually plays; the
                                    // polled flag drops to false on pause/stop/close so it clears.
                                    keepOn = isVideoPlaying,
                                    // Same pinch-zoom transform the still uses: the shared per-page
                                    // scale/offset (reset on settledPage change) with edge-paging,
                                    // panning only once zoomed in. The TextureView surface scales
                                    // with graphicsLayer.
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .transformable(state = transformState, canPan = { scale > 1f })
                                        .graphicsLayer(
                                            scaleX = scale, scaleY = scale,
                                            translationX = offset.x, translationY = offset.y,
                                        ),
                                )
                            }
                            // No play overlay — play button is in the VideoControlPill below filmstrip
                        }
                        is PhotoViewerViewModel.ViewerState.Loading ->
                            if (thumbModel == null)
                                CircularProgressIndicator(color = FgDim, strokeWidth = 2.dp)
                        is PhotoViewerViewModel.ViewerState.Error ->
                            if (stateMatchesPage) Text(s.message ?: stringResource(R.string.viewer_error_loading_photo), color = ErrorColor, fontSize = 14.sp)
                    }

                    // Recognised text sits over the still, and ahead of the device-hide blur below,
                    // so a hidden photo can never end up with readable words drawn on top of it.
                    // The dim and the lit words first, then the invisible layer the platform's own
                    // selection is taken from, which has to be nearest the finger to get the long
                    // press before anything under it does. Carrying the pinch as well, because the
                    // layer covers the picture and a gesture that stops at it would never reach the
                    // image's own transform.
                    if (showingText != null && stateMatchesPage) {
                        val textTransform = viewerTransform(containerSize, scale, offset)
                        ViewerTextOverlay(
                            showing = showingText,
                            transform = textTransform,
                        )
                        ViewerTextSelectionLayer(
                            showing = showingText,
                            transform = textTransform,
                            selection = textSelection,
                            modifier = Modifier
                                .transformable(state = transformState, canPan = { scale > 1f }),
                        )
                    }
                    (textState as? ViewerTextState.Working)?.let { ViewerTextProgress(it.stage) }

                    // Video-only download badge. Shown from "download starts" all the way
                    // through "ExoPlayer prepares + first frame paints" — anything in between
                    // looks like a dead player to the user. videoEverPlayed flips true the
                    // first time the player reports isPlaying after a settle; after that the
                    // pill stays hidden through ordinary pause/resume cycles. Without this
                    // continuity the pill disappears at 100% but the video takes another
                    // second to start, leaving an empty black frame in between.
                    val isVideoLoading = isVideoItemThumb && (
                        isDownloading ||
                        (state is PhotoViewerViewModel.ViewerState.ShowVideo && !videoEverPlayed)
                    )
                    // Hidden-on-device overlay — when the currently rendered cloud photo's
                    // linkId is in [hiddenCloudLinkIds], blur the full-res surface and
                    // show a localized "Hidden on this device" label so the user can't
                    // peek at the content from the regular viewer either. Mirrors the
                    // gallery cell treatment, scaled up for the full-screen pager.
                    val pageCloudLinkId: String? = when (item) {
                        is GalleryItem.CloudOnly -> item.cloud.linkId
                        is GalleryItem.Synced -> item.cloud.linkId
                        else -> null
                    }
                    if (pageCloudLinkId != null && pageCloudLinkId in hiddenCloudLinkIds) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .then(
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                                        Modifier.blur(56.dp)
                                    else
                                        Modifier,
                                )
                                .background(Color.Black.copy(alpha = 0.78f)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(
                                    Icons.Default.VisibilityOff,
                                    contentDescription = null,
                                    tint = FgPrimary,
                                    modifier = Modifier.size(40.dp),
                                )
                                Spacer(Modifier.height(12.dp))
                                Text(
                                    stringResource(R.string.viewer_hidden_label),
                                    color = FgPrimary,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.SemiBold,
                                )
                            }
                        }
                    }

                    if (isVideoLoading) {
                        val pct = downloadProgress?.let { p ->
                            if (p.totalBytes > 0L) (p.doneBytes * 100 / p.totalBytes).toInt() else null
                        }
                        Row(
                            modifier = Modifier
                                .background(PillBg, RoundedCornerShape(999.dp))
                                .border(0.5.dp, PillBorder, RoundedCornerShape(999.dp))
                                .padding(horizontal = 14.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            CircularProgressIndicator(
                                color = FgPrimary,
                                strokeWidth = 2.dp,
                                modifier = Modifier.size(14.dp),
                            )
                            // "Downloading…" only when there's a cloud download in flight;
                            // otherwise it's the player decoding a local file, where the right
                            // word is "Loading". Otherwise the user sees a Downloading flash
                            // on every device-local video which is misleading.
                            val statusText = when {
                                pct != null -> stringResource(R.string.viewer_downloading_pct, pct)
                                isDownloading -> stringResource(R.string.viewer_downloading)
                                else -> stringResource(R.string.viewer_loading)
                            }
                            Text(
                                statusText,
                                color = FgPrimary, fontSize = 12.sp,
                            )
                        }
                    }

                    // Motion-photo "play" and panorama "view" affordances live in the bottom
                    // control pills (next to the video pill) so the open still stays
                    // unobstructed while browsing. Only the panorama exit chip stays
                    // top-anchored here, so it is reachable while panning with the bottom
                    // chrome hidden.
                    if (isPanoramaMode && stateMatchesPage) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .statusBarsPadding()
                                .padding(top = 64.dp),
                            contentAlignment = Alignment.TopCenter,
                        ) {
                            Row(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(999.dp))
                                    .background(PillBg, RoundedCornerShape(999.dp))
                                    .border(0.5.dp, PillBorder, RoundedCornerShape(999.dp))
                                    .clickable { viewModel.exitPanorama() }
                                    .padding(horizontal = 14.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Icon(
                                    Icons.Default.Close,
                                    contentDescription = stringResource(R.string.cd_exit_panorama),
                                    tint = FgPrimary,
                                    modifier = Modifier.size(16.dp),
                                )
                                Text(
                                    stringResource(R.string.viewer_exit_panorama),
                                    color = FgPrimary, fontSize = 12.sp,
                                )
                            }
                        }
                    }
                }
            }
        }

        // ── Top bar (fades with overlays) ─────────────────────────────────────
        AnimatedVisibility(
            visible = showChrome,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopStart),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 12.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ViewerBubble(onClick = startExit) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.onboarding_back),
                        tint = FgPrimary, modifier = Modifier.size(20.dp))
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val isDeleting = deleteState is PhotoViewerViewModel.DeleteState.Working
                    val settledItem = items.getOrNull(pagerState.settledPage)

                    // Favorite button — hidden for shared-with-me viewers because the
                    // favorite flag is a node-level tag on the OWNER's photo, not a
                    // private bookmark on the recipient side. Writing it from the
                    // recipient would either fail or mutate the owner's library.
                    if (settledItem != null && !isReadOnlyAlbum) {
                        ViewerBubble(onClick = { viewModel.toggleFavorite(settledItem) }) {
                            Icon(
                                if (isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                                stringResource(
                                    if (isFavorite) R.string.cd_favorite_remove
                                    else R.string.cd_favorite_add,
                                ),
                                tint = if (isFavorite) Color(0xFFFF3B30) else FgDim,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }

                    // Make available offline — cloud-only photos only. A Synced/LocalOnly item
                    // already has its bytes on the device, so there's nothing to pin. The label
                    // names the press rather than the state, so a pinned photo announces the
                    // removal the tap would do, the way the selection drawer's row does.
                    if (settledItem is GalleryItem.CloudOnly && !isReadOnlyAlbum) {
                        ViewerBubble(onClick = { viewModel.toggleOfflinePin(settledItem) }) {
                            Icon(
                                if (isOffline) Icons.Filled.OfflinePin else Icons.Outlined.OfflinePin,
                                stringResource(
                                    if (isOffline) R.string.offline_remove
                                    else R.string.offline_make_available,
                                ),
                                tint = if (isOffline) Accent else FgDim,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }

                    // Add to album — available for any item with at least a local OR cloud
                    // representation. Cloud / Synced → cloud album add (addPhotosToAlbum API).
                    // LocalOnly / Synced → local virtual-album membership (DataStore write,
                    // no file move so DATE_TAKEN survives Android Q+ MediaProvider restrictions).
                    // Add to album — hidden for shared-with-me viewers. The recipient
                    // could in theory pin someone else's photo into one of their own
                    // albums, but the underlying call needs cloud-side share access
                    // and our path doesn't bridge across the recipient/owner volume
                    // boundary. A vaulted photo is left out too: a Drive album add uploads the
                    // device-only file first, which is the one thing the vault exists to prevent.
                    if (settledItem != null && !isReadOnlyAlbum && outbound.addToAlbum) {
                        ViewerBubble(onClick = { showAddToAlbumSheet = true }) {
                            if (isAddingToAlbum) {
                                CircularProgressIndicator(color = Accent, strokeWidth = 2.dp,
                                    modifier = Modifier.size(16.dp))
                            } else {
                                Icon(Icons.Default.LibraryAdd, stringResource(R.string.gallery_add_to_album),
                                    tint = FgDim, modifier = Modifier.size(18.dp))
                            }
                        }
                    }

                    // Edit button — images go to PhotoEditor, videos to VideoEditor. NavGraph
                    // routes based on mimeType at click time, so we just need an editable media item.
                    val isEditable = when (settledItem) {
                        is GalleryItem.LocalOnly -> {
                            val m = settledItem.local.mimeType
                            m.startsWith("image/") || m.startsWith("video/")
                        }
                        is GalleryItem.Synced -> {
                            val m = settledItem.local.mimeType
                            m.startsWith("image/") || m.startsWith("video/")
                        }
                        is GalleryItem.CloudOnly -> {
                            val m = settledItem.cloud.mimeType
                            m.startsWith("image/") || m.startsWith("video/")
                        }
                        null -> false
                    }
                    if (settledItem != null && isEditable && !isReadOnlyAlbum) {
                        ViewerBubble(onClick = { onEditItem(settledItem) }) {
                            Icon(Icons.Default.Edit, stringResource(R.string.cd_viewer_edit),
                                tint = FgPrimary, modifier = Modifier.size(18.dp))
                        }
                    }

                    Box {
                        val appColors = eu.akoos.photos.presentation.theme.AppColors.current
                        var menuExpanded by remember { mutableStateOf(false) }
                        ViewerBubble(onClick = { menuExpanded = true }) {
                            val anyInFlight = isDeleting || isSavingToDevice || isSharing
                            if (anyInFlight) {
                                CircularProgressIndicator(color = Accent, strokeWidth = 2.dp,
                                    modifier = Modifier.size(16.dp))
                            } else {
                                Icon(Icons.Default.MoreVert, stringResource(R.string.viewer_menu_more),
                                    tint = FgPrimary, modifier = Modifier.size(20.dp))
                            }
                        }
                        androidx.compose.material3.DropdownMenu(
                            expanded = menuExpanded,
                            onDismissRequest = { menuExpanded = false },
                            shape = RoundedCornerShape(18.dp),
                            containerColor = appColors.cardBg,
                            border = androidx.compose.foundation.BorderStroke(0.5.dp, appColors.pillBorder),
                        ) {
                            // Menu order runs from the most-reached actions to the destructive ones:
                            // share, then the metadata cluster (details, edit, strip), then the album
                            // and storage actions, then playback, and finally hide and delete at the
                            // bottom where an accidental tap is least likely.

                            // Synced / CloudOnly carry a Drive linkId, LocalOnly does not, so the
                            // album actions below need a cloud-backed item.
                            val isCloudItem = settledItem is GalleryItem.Synced ||
                                settledItem is GalleryItem.CloudOnly

                            // Share opens the unified share drawer (Send to another app, Share
                            // with people, Public link) instead of jumping straight to the OS
                            // sheet, and opening it kicks off the public-link lookup. A guest in
                            // someone else's album keeps the item; the drawer itself narrows
                            // which rows it offers there. Dropped entirely once the vault has
                            // taken every row the drawer would have drawn.
                            if (settledItem != null && outbound.anyShareRoute) {
                                androidx.compose.material3.DropdownMenuItem(
                                    text = { Text(stringResource(R.string.share_action),
                                        color = FgPrimary) },
                                    leadingIcon = { Icon(Icons.Default.Share, null,
                                        tint = Accent, modifier = Modifier.size(20.dp)) },
                                    onClick = {
                                        menuExpanded = false
                                        viewModel.loadPublicLink(settledItem)
                                        showShareSheet = true
                                    },
                                )
                            }
                            // People in this photo: detect the faces on this photo (if not already) and
                            // pin their name tags, or take them down. The reliable path when a long
                            // press is awkward, and the only one on a photo not yet scanned.
                            if (aiFeaturesEnabled && faceEnabled) {
                                androidx.compose.material3.DropdownMenuItem(
                                    text = { Text(stringResource(R.string.viewer_people_in_photo),
                                        color = FgPrimary) },
                                    leadingIcon = { Icon(Icons.Default.Face, null,
                                        tint = Accent, modifier = Modifier.size(20.dp)) },
                                    onClick = {
                                        menuExpanded = false
                                        if (facesMode) {
                                            facesMode = false
                                        } else {
                                            val s = items.getOrNull(pagerState.settledPage)
                                            if (s != null) {
                                                facesMode = true
                                                scope.launch {
                                                    val found = viewModel.detectFacesNow(s)
                                                    if (found.isEmpty()) {
                                                        facesMode = false
                                                        snackbarHostState.showSnackbar(noFacesFoundMsg)
                                                    }
                                                }
                                            }
                                        }
                                    },
                                )
                            }
                            androidx.compose.material3.DropdownMenuItem(
                                text = { Text(stringResource(R.string.viewer_menu_details),
                                    color = FgPrimary) },
                                leadingIcon = { Icon(Icons.Default.Info, null,
                                    tint = Accent, modifier = Modifier.size(20.dp)) },
                                onClick = {
                                    menuExpanded = false
                                    showMetadata = true
                                },
                            )
                            // Edit the capture date and place. Offered when the item is editable (a
                            // device photo, or a cloud or backed-up image the corrected-copy replace can
                            // rewrite) outside a shared-with-me album; a cloud or synced video, which the
                            // editor cannot change, is left out, matching the multi-select gate.
                            val editItem = settledItem
                            if (editItem != null && !isReadOnlyAlbum &&
                                anyMetadataEditable(listOf(editItem))
                            ) {
                                androidx.compose.material3.DropdownMenuItem(
                                    text = { Text(stringResource(R.string.metadata_editor_edit_metadata),
                                        color = FgPrimary) },
                                    leadingIcon = { Icon(Icons.Default.EditNote, null,
                                        tint = Accent, modifier = Modifier.size(20.dp)) },
                                    onClick = {
                                        menuExpanded = false
                                        settledItem?.let { onEditMetadata(it) }
                                    },
                                )
                            }
                            // Strip metadata needs the device bytes, so it is offered only for a photo
                            // that is on this device (LocalOnly, or the local side of a Synced pair).
                            if (settledItem is GalleryItem.LocalOnly || settledItem is GalleryItem.Synced) {
                                androidx.compose.material3.DropdownMenuItem(
                                    text = { Text(stringResource(R.string.gallery_strip_metadata),
                                        color = FgPrimary) },
                                    leadingIcon = { Icon(Icons.Default.PrivacyTip, null,
                                        tint = Accent, modifier = Modifier.size(20.dp)) },
                                    onClick = {
                                        menuExpanded = false
                                        showStripPicker = true
                                    },
                                )
                            }
                            // "Set as album cover" is offered only when the viewer was opened from
                            // an album. No-op when the user owns the album but the cover is
                            // unchanged, because setAlbumCover is idempotent server-side.
                            if (sourceAlbumLinkId != null && isCloudItem && !isReadOnlyAlbum) {
                                androidx.compose.material3.DropdownMenuItem(
                                    text = { Text(stringResource(R.string.album_set_as_album_cover),
                                        color = FgPrimary) },
                                    leadingIcon = { Icon(
                                        Icons.Default.PhotoLibrary,
                                        null,
                                        tint = Accent,
                                        modifier = Modifier.size(20.dp),
                                    ) },
                                    onClick = {
                                        menuExpanded = false
                                        viewModel.setCurrentAsAlbumCover(settledItem, sourceAlbumLinkId)
                                    },
                                )
                            }
                            // Taking the photo back out of the album it was opened from, the one
                            // route that does not mean backing out to the grid and long-pressing.
                            // Gated on the album's write grant rather than ownership, so an editor
                            // on a shared album keeps this while the rest of the mutating menu
                            // stays hidden.
                            if (sourceAlbumLinkId != null && canRemoveFromAlbum && isCloudItem) {
                                androidx.compose.material3.DropdownMenuItem(
                                    text = { Text(stringResource(R.string.album_remove_from_album),
                                        color = ErrorColor) },
                                    leadingIcon = { Icon(Icons.Default.RemoveCircleOutline, null,
                                        tint = ErrorColor, modifier = Modifier.size(20.dp)) },
                                    enabled = !isAddingToAlbum,
                                    onClick = {
                                        menuExpanded = false
                                        val item = settledItem ?: return@DropdownMenuItem
                                        viewModel.removeFromAlbum(sourceAlbumLinkId, item)
                                    },
                                )
                            }
                            // A photo inside an album someone else shared is not copied out to the
                            // device from here; saving that album into your own library is the route
                            // to a copy. Matches the album's own dock and the offline bubble above.
                            if (settledItem is GalleryItem.CloudOnly && !isReadOnlyAlbum) {
                                androidx.compose.material3.DropdownMenuItem(
                                    text = { Text(stringResource(R.string.viewer_menu_save_to_device),
                                        color = FgPrimary) },
                                    leadingIcon = { Icon(Icons.Default.FileDownload, null,
                                        tint = Accent, modifier = Modifier.size(20.dp)) },
                                    enabled = !isSavingToDevice,
                                    onClick = {
                                        menuExpanded = false
                                        viewModel.downloadToDevice(settledItem)
                                    },
                                )
                            }
                            // Back up: force-upload a not-yet-backed-up local photo. Only
                            // LocalOnly qualifies; Synced / CloudOnly are already on Drive.
                            // A vaulted photo never does: uploading it undoes the hide. Same
                            // wording the selection drawers give the same action.
                            if (settledItem is GalleryItem.LocalOnly && outbound.backUpToDrive) {
                                androidx.compose.material3.DropdownMenuItem(
                                    text = { Text(stringResource(R.string.sel_label_back_up),
                                        color = FgPrimary) },
                                    leadingIcon = { Icon(Icons.Default.CloudUpload, null,
                                        tint = Accent, modifier = Modifier.size(20.dp)) },
                                    onClick = {
                                        menuExpanded = false
                                        viewModel.backUpItem(settledItem)
                                    },
                                )
                            }
                            if (items.size > 1) {
                                androidx.compose.material3.DropdownMenuItem(
                                    text = { Text(stringResource(
                                        if (isPlaying) R.string.viewer_pause_slideshow
                                        else R.string.viewer_play_slideshow,
                                    ), color = FgPrimary) },
                                    leadingIcon = { Icon(
                                        if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                        null,
                                        tint = Accent,
                                        modifier = Modifier.size(20.dp),
                                    ) },
                                    onClick = {
                                        menuExpanded = false
                                        isPlaying = !isPlaying
                                    },
                                )
                            }
                            // Hide is offered for any settled item: a photo with a device file moves
                            // into the vault, a cloud-only one hides client-side by linkId. Unhide is
                            // offered for any already-hidden item, device or cloud; [unhideItem] picks
                            // the reveal path (drop the linkId, or restore the vaulted file) per item
                            // kind. Both rows name the destination rather than the bare verb, in the
                            // wording the selection drawers carry, since what "hidden" costs a photo
                            // is that it leaves the device gallery for Hidden Photos.
                            //
                            // Not offered inside someone else's album. Hiding is a filter over this
                            // user's own library, so applying it to a photo that only exists in a
                            // shared album writes a device-global entry that hides nothing the user
                            // can see anyway. It reads as a moderation action it is not.
                            if (settledItem != null && !isReadOnlyAlbum) {
                                androidx.compose.material3.DropdownMenuItem(
                                    text = { Text(stringResource(
                                        if (isHidden) R.string.sel_label_unhide
                                        else R.string.sel_label_hide,
                                    ), color = FgPrimary) },
                                    leadingIcon = { Icon(Icons.Default.VisibilityOff, null,
                                        tint = Accent,
                                        modifier = Modifier.size(20.dp)) },
                                    onClick = {
                                        menuExpanded = false
                                        val item = settledItem ?: return@DropdownMenuItem
                                        if (isHidden) {
                                            viewModel.unhideItem(item)
                                            // Same teardown as any other way out, so the video
                                            // surface goes with it and the grid gets told where
                                            // the user ended up.
                                            startExit()
                                        } else {
                                            viewModel.hideItem(item)
                                        }
                                    },
                                )
                            }
                            // Destroying someone else's photo is not a guest's call whatever album
                            // rights they hold, and Drive rejects it, so a shared album offers no
                            // delete. Taking the photo out of the album, above, is the editor's route.
                            if (!isReadOnlyAlbum) {
                                androidx.compose.material3.DropdownMenuItem(
                                    text = { Text(stringResource(R.string.viewer_menu_delete),
                                        color = ErrorColor) },
                                    leadingIcon = { Icon(Icons.Default.DeleteOutline, null,
                                        tint = ErrorColor, modifier = Modifier.size(20.dp)) },
                                    enabled = !isDeleting,
                                    onClick = {
                                        menuExpanded = false
                                        showDeleteSheet = true
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }

        // ── Bottom section — hidden when overlays are off ────────────────────
        AnimatedVisibility(
            visible = showChrome,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(bottom = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // Full-quality-paused hint — sits with the bottom controls and is dismissable, shown
            // only while the Wi-Fi-only-for-fullres preference skipped the auto-download on a
            // metered link. The VM flag already tracks the settled item.
            if (fullResBlockedByMetered && !meteredHintDismissed) {
                Row(
                    modifier = Modifier
                        .background(PillBg, RoundedCornerShape(999.dp))
                        .border(0.5.dp, PillBorder, RoundedCornerShape(999.dp))
                        .padding(start = 14.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        stringResource(R.string.viewer_wifi_for_full_quality),
                        color = FgPrimary, fontSize = 12.sp,
                    )
                    Icon(
                        Icons.Default.Close,
                        contentDescription = stringResource(R.string.close),
                        tint = FgPrimary.copy(alpha = 0.6f),
                        modifier = Modifier
                            .size(18.dp)
                            .clickable { meteredHintDismissed = true },
                    )
                }
            }

            // Video control pill — above filmstrip, visible as soon as item is a video
            val settledItem = items.getOrNull(pagerState.settledPage)
            val isVideoItem = when (settledItem) {
                is GalleryItem.LocalOnly -> settledItem.local.mimeType.startsWith("video/")
                is GalleryItem.Synced    -> settledItem.local.mimeType.startsWith("video/")
                is GalleryItem.CloudOnly -> settledItem.cloud.mimeType.startsWith("video/")
                null -> false
            }
            // Shared across the pill and the reel filmstrip so a drag on either hides the pill's
            // paused-only frame-step buttons (a seek briefly reports the player as not playing).
            var isScrubbing by remember { mutableStateOf(false) }
            if (isVideoItem) {
                VideoControlPill(
                    player       = currentPlayer,
                    videoStarted = videoStarted,
                    isScrubbing  = isScrubbing,
                    onScrubbingChange = { isScrubbing = it },
                    onPlay       = { videoStarted = true },
                )
            }

            // Motion-photo control pill — play or stop the embedded clip from the bottom
            // chrome (mirrors the video pill) so the open still stays unobstructed while
            // browsing. Fades with the chrome like every other bottom affordance.
            if (isMotionPhoto && !isVideoItem &&
                state is PhotoViewerViewModel.ViewerState.ShowImage) {
                val motionKey = when (settledItem) {
                    is GalleryItem.CloudOnly -> settledItem.cloud.linkId
                    is GalleryItem.Synced    -> settledItem.cloud.linkId
                    is GalleryItem.LocalOnly -> settledItem.local.displayName
                    null -> ""
                }
                val motionPlaying = motionVideoFile != null
                Row(
                    modifier = Modifier
                        .background(PillBg, infoPillShape)
                        .border(0.5.dp, PillBorder, infoPillShape)
                        .clickable(enabled = !isExtractingMotion) {
                            if (motionPlaying) viewModel.stopMotionPhoto()
                            else viewModel.playMotionPhoto(motionKey)
                        }
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (isExtractingMotion) {
                        CircularProgressIndicator(
                            color = FgPrimary,
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(16.dp),
                        )
                    } else {
                        Icon(
                            Icons.Default.MotionPhotosOn,
                            contentDescription = null,
                            tint = if (motionPlaying) Accent else FgPrimary,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                    Text(
                        stringResource(
                            if (motionPlaying) R.string.cd_stop_motion_photo
                            else R.string.cd_play_motion_photo,
                        ),
                        color = FgPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                    )
                }
            }

            // Panorama control pill — enter the immersive pan view from the bottom chrome.
            if (isPanorama && !isMotionPhoto && !isPanoramaMode && !isVideoItem &&
                state is PhotoViewerViewModel.ViewerState.ShowImage) {
                Row(
                    modifier = Modifier
                        .background(PillBg, infoPillShape)
                        .border(0.5.dp, PillBorder, infoPillShape)
                        .clickable { viewModel.enterPanorama() }
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        Icons.Default.Panorama,
                        contentDescription = null,
                        tint = FgPrimary,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(
                        stringResource(R.string.viewer_view_panorama),
                        color = FgPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                    )
                }
            }

            // Filmstrip reel — the active video's slot widens into a frame scrubber in place.
            // Detect the current item straight from the pager index (not the settled player) so a
            // page change flips the slot immediately: the frames come from the current item's own
            // URI, so the previous video never flashes through and a photo drops back to a plain
            // thumbnail at once. A cloud video has no local item URI until it downloads, so it falls
            // back to the player's media URI.
            val reelItem = items.getOrNull(pagerState.currentPage)
            val reelVideoUri: Uri? = when (reelItem) {
                is GalleryItem.LocalOnly ->
                    if (reelItem.local.mimeType.startsWith("video/")) Uri.parse(reelItem.local.uri) else null
                is GalleryItem.Synced ->
                    if (reelItem.local.mimeType.startsWith("video/")) Uri.parse(reelItem.local.uri) else null
                is GalleryItem.CloudOnly ->
                    if (reelItem.cloud.mimeType.startsWith("video/")) currentPlayer?.currentMediaItem?.localConfiguration?.uri else null
                null -> null
            }
            Filmstrip(
                items = items,
                currentPage = pagerState.currentPage,
                player = if (reelVideoUri != null) currentPlayer else null,
                videoUri = reelVideoUri,
                onScrubbingChange = { isScrubbing = it },
                onThumbnailClick = { idx ->
                    scope.launch { pagerState.animateScrollToPage(idx) }
                },
            )

            // Info pill — always, unchanged
            val currentItem = items.getOrNull(pagerState.currentPage)
            Row(
                modifier = Modifier
                    .background(PillBg, infoPillShape)
                    .border(0.5.dp, PillBorder, infoPillShape)
                    .clickable { showMetadata = true }
                    .padding(horizontal = 18.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // Cloud/device badge — sits to the LEFT of the position counter, with a thin
                // separator. Mirrors the gallery cell badges so the user can tell at a glance
                // whether the currently viewed photo is in cloud + on device (green cloud),
                // cloud-only (white cloud), or device-only (no badge — no point showing
                // anything since the user is obviously looking at it).
                // Upgrade a stale CloudOnly to "synced" once we know the cloud linkId is
                // mirrored to a device file — happens after the user downloads it from this
                // screen but the static `items` snapshot can't reflect it.
                // A vaulted photo is upgraded the same way from the other side: vaulting removes the
                // MediaStore row that made it a Synced item, so it opens here as LocalOnly however it
                // was reached, and the vault's cloud-id records are the only thing left that can say
                // its Drive copy is still there.
                val effectiveSynced = currentItem is GalleryItem.Synced ||
                    (currentItem is GalleryItem.CloudOnly &&
                        localUriByLinkId.containsKey(currentItem.cloud.linkId)) ||
                    (currentItem is GalleryItem.LocalOnly &&
                        currentItem.local.uri in pairedVaultUris)
                when {
                    effectiveSynced -> {
                        Icon(
                            Icons.Default.Cloud,
                            contentDescription = stringResource(R.string.cd_status_backed_up_device),
                            tint = Color(0xFF30D158),
                            modifier = Modifier.size(13.dp),
                        )
                        Text("·", color = FgMute, fontSize = 13.sp)
                    }
                    currentItem is GalleryItem.CloudOnly -> {
                        Icon(
                            Icons.Default.Cloud,
                            contentDescription = stringResource(R.string.cd_status_cloud_only),
                            // Theme-reactive tint: the pill background turns near-white in
                            // light mode, where a fixed white glyph disappears entirely.
                            tint = FgPrimary,
                            modifier = Modifier.size(13.dp),
                        )
                        Text("·", color = FgMute, fontSize = 13.sp)
                    }
                    else -> { /* LocalOnly — no badge */ }
                }
                Text(
                    "${pagerState.currentPage + 1} / ${items.size}",
                    color = FgPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                )
                if (currentItem != null) {
                    Text("·", color = FgMute, fontSize = 13.sp)
                    Text(formatItemDate(currentItem), color = FgDim, fontSize = 13.sp)
                }
                Text("›", color = FgMute, fontSize = 15.sp)
            }
        }
        } // AnimatedVisibility

        // Text mode takes the viewer's own chrome down, so this is the only thing on screen that
        // names the mode and the only control it needs; everything the user does with the words
        // themselves is the platform's, off the selectable layer over the photo.
        //
        // Low and centred, where the viewer's other floating controls sit. The device's own
        // selection toolbar goes above the words it belongs to whenever there is room, so the foot
        // of the screen is the one band it rarely reaches for.
        if (showingText != null) {
            ViewerTextModePill(
                onDismiss = {
                    textJob?.cancel()
                    textJob = null
                    textState = ViewerTextState.Idle
                },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(horizontal = 24.dp, vertical = ViewerTextPillPadding),
            )
        }

        // Bottom-anchored themed snackbar — keeps add-to-album / set-as-cover
        // confirmations + delete-failure errors inside the app's visual language
        // instead of the OS Toast popup that ignored our theme + sat below the
        // navigation bar.
        eu.akoos.photos.presentation.common.ThemedSnackbarHost(
            snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 96.dp),
        )
    }

    // ── Metadata sheet ─────────────────────────────────────────────────────────
    if (showStripPicker) {
        val stripItem = items.getOrNull(pagerState.settledPage)
        MetadataStripPickerDialog(
            onConfirm = { config ->
                showStripPicker = false
                val uri = when (stripItem) {
                    is GalleryItem.LocalOnly -> stripItem.local.uri
                    is GalleryItem.Synced -> stripItem.local.uri
                    else -> null
                }
                if (uri != null) viewModel.stripMetadataFromLocal(uri, config)
            },
            onDismiss = { showStripPicker = false },
        )
    }

    if (showMetadata) {
        val item = items.getOrNull(pagerState.settledPage)
        LaunchedEffect(item) {
            if (item != null) {
                viewModel.loadDetailsPlace(item)
                viewModel.loadDetailsAlbums(item)
            }
        }
        ModalBottomSheet(
            onDismissRequest = { showMetadata = false },
            sheetState = metadataSheetState,
            containerColor = Bg2,
            scrimColor = Color.Black.copy(alpha = 0.5f),
        ) {
            PhotoMetadataSheet(
                item = item,
                exif = metadata,
                place = detailsPlace,
                resolvedGps = detailsGps,
                localFolder = detailsAlbums.localFolder,
                cloudAlbums = detailsAlbums.cloudAlbums,
                cloudVideoMeta = cloudVideoMeta,
                isStripping = isStrippingMetadata,
                cloudSizeFallback = cloudFullResSize,
                photoTags = photoTags,
                hasCloudCopy = item is GalleryItem.LocalOnly && item.local.uri in pairedVaultUris,
                onToggleTag = { tagId, add -> item?.let { viewModel.setPhotoTag(it, tagId, add) } },
                onStripFields = { config ->
                    val uri = when (item) {
                        is GalleryItem.LocalOnly -> item.local.uri
                        is GalleryItem.Synced -> item.local.uri
                        else -> null
                    }
                    if (uri != null) viewModel.stripMetadataFromLocal(uri, config)
                },
                onRenameClick = {
                    // Rename is a node-level mutation we don't grant guests of a
                    // shared-with-me album. The metadata sheet's button is hidden
                    // when no callback fires, and this stays a no-op on that path.
                    if (!isReadOnlyAlbum) {
                        showMetadata = false
                        showRenameDialog = true
                    }
                },
                // Edit date + place. Null on a shared-with-me album, and null for an item the editor
                // cannot change (a cloud or synced video), so the row hides exactly where the
                // multi-select entry would also be absent.
                onEditMetadata = if (isReadOnlyAlbum || item == null ||
                    !anyMetadataEditable(listOf(item))
                ) null else {
                    { showMetadata = false; onEditMetadata(item) }
                },
            )
        }
    }

    // ── Rename dialog ─────────────────────────────────────────────────────────
    if (showRenameDialog) {
        val item = items.getOrNull(pagerState.settledPage)
        if (item != null) {
            val currentName = when (item) {
                is GalleryItem.LocalOnly -> item.local.displayName
                is GalleryItem.Synced    -> item.local.displayName
                is GalleryItem.CloudOnly -> item.cloud.displayName
            }
            RenameDialog(
                currentName = currentName,
                isCloud = item is GalleryItem.CloudOnly,
                isVaulted = isVaultedItem,
                isWorking = renameState is PhotoViewerViewModel.RenameState.Working,
                errorMessage = (renameState as? PhotoViewerViewModel.RenameState.Failed)?.message,
                onDismiss = {
                    showRenameDialog = false
                    viewModel.resetRenameState()
                },
                onConfirm = { newName, replaceOriginal ->
                    viewModel.renameItem(item, newName, replaceOriginal, sourceAlbumLinkId)
                },
            )
        }
    }

    // Close the dialog and reset state when the rename finishes. Drop the renamed item's
    // cached bytes so the stale original (old name / trashed cloud copy) stops showing
    // immediately — same invalidation the editor does after a save.
    LaunchedEffect(renameState) {
        val rs = renameState
        if (rs is PhotoViewerViewModel.RenameState.Done) {
            items.getOrNull(pagerState.settledPage)?.let { viewModel.invalidateAfterRename(it) }
            showRenameDialog = false
            viewModel.resetRenameState()
        } else if (rs is PhotoViewerViewModel.RenameState.NeedsPermission) {
            runCatching {
                renamePermissionLauncher.launch(
                    IntentSenderRequest.Builder(rs.pendingIntent.intentSender).build()
                )
            }.onFailure { viewModel.resetRenameState() }
        }
    }

    // ── Add to Album sheet ────────────────────────────────────────────────────────
    if (showAddToAlbumSheet) {
        val settledItem = items.getOrNull(pagerState.settledPage)
        val currentPhotoAlbumIds by viewModel.currentPhotoAlbumIds.collectAsStateWithLifecycle()
        val hasCloud = settledItem is GalleryItem.Synced || settledItem is GalleryItem.CloudOnly
        // Refresh membership for the current photo every time the sheet opens — fast on cache
        // hit (20-min TTL in AlbumService) and self-heals if the user removed the photo from an
        // album on Drive web between sheet opens.
        LaunchedEffect(showAddToAlbumSheet, settledItem) {
            if (settledItem != null && hasCloud) viewModel.loadCurrentPhotoAlbumIds(settledItem)
        }
        AddToAlbumSheet(
            sheetState = addToAlbumSheetState,
            cloudAlbums = albums,
            currentPhotoAlbumIds = currentPhotoAlbumIds,
            onDismiss = { showAddToAlbumSheet = false },
            onCreateNew = {
                newAlbumForPhoto = settledItem
                showAddToAlbumSheet = false
            },
            onCloudAlbumPicked = { albumLinkId ->
                if (settledItem != null) {
                    // Tap-to-remove when the photo is already in this album, otherwise add.
                    if (albumLinkId in currentPhotoAlbumIds) {
                        viewModel.removeFromAlbum(albumLinkId, settledItem)
                    } else {
                        viewModel.addToAlbum(albumLinkId, settledItem)
                    }
                }
                showAddToAlbumSheet = false
            },
        )
    }

    // New album from the add-to-album sheet: create it and drop the photo in (same dialog the
    // gallery uses).
    newAlbumForPhoto?.let { photo ->
        eu.akoos.photos.presentation.gallery.GalleryNewAlbumDialog(
            onDismiss = { newAlbumForPhoto = null },
            onCreate = { name ->
                viewModel.createCloudAlbumAndAdd(name, photo)
                newAlbumForPhoto = null
            },
        )
    }

    // ── Share drawer ────────────────────────────────────────────────────────────
    if (showShareSheet) {
        val settledItem = items.getOrNull(pagerState.settledPage)
        // Public link only exists for cloud-backed photos; a LocalOnly item shows the
        // "back up first" note instead of opening the manage-link sheet.
        val canCreateLink = settledItem is GalleryItem.Synced ||
            settledItem is GalleryItem.CloudOnly
        PhotoShareSheet(
            sheetState = shareSheetState,
            canCreateLink = canCreateLink,
            localUploadEnabled = outbound.publicLink,
            // Inside an album someone else shared, only "Send to another app" survives: it moves
            // bytes the viewer can already see and grants nobody access to the album. Creating a
            // public link or inviting people publishes someone else's photo, which is the album
            // owner's call, and Drive refuses it from a guest. The album's own selection dock
            // draws the same line. A vaulted photo comes down to the same one row for a different
            // reason: both of the others upload it before they can share it at all.
            showPublicLink = !isReadOnlyAlbum && outbound.publicLink,
            showShareWithPeople = !isReadOnlyAlbum && outbound.shareWithPeople,
            onDismiss = { showShareSheet = false },
            onSendToApp = {
                showShareSheet = false
                settledItem?.let { viewModel.shareItem(it) }
            },
            onShareWithPeople = {
                // Proton shares photos with people by adding them to a shared album, so
                // this hands off to the viewer's existing add-to-album sheet.
                showShareSheet = false
                showAddToAlbumSheet = true
            },
            onManagePublicLink = {
                // Hand off to the dedicated manage-link sheet. The public-link lookup was
                // already kicked off when the drawer opened (loadPublicLink), so the manage
                // sheet renders the current state immediately.
                showShareSheet = false
                showManageLinkSheet = true
            },
        )
    }

    // ── Manage public link sheet ─────────────────────────────────────────────────
    if (showManageLinkSheet) {
        val settledItem = items.getOrNull(pagerState.settledPage)
        ManagePublicLinkSheet(
            sheetState = manageLinkSheetState,
            publicLinkState = publicLinkState,
            onDismiss = { showManageLinkSheet = false },
            onCreateLink = { viewModel.createPublicLink() },
            needsUpload = settledItem is GalleryItem.LocalOnly,
            onUploadAndCreate = { settledItem?.let { viewModel.uploadAndCreateViewedLink(it) } },
            onCopyLink = {
                viewModel.currentPublicLinkUrl()?.let { url ->
                    copySensitiveText(shareContext, "Photo link", url)
                    scope.launch { snackbarHostState.showSnackbar(linkCopiedMsg) }
                }
            },
            onRemoveLink = { viewModel.revokePublicLink() },
            onSetPassword = { password ->
                viewModel.setLinkPassword(password)
                // Confirm the change; a failure still surfaces in the sheet's Error state.
                val msg = if (password.isNullOrBlank()) passwordRemovedMsg else passwordSetMsg
                scope.launch { snackbarHostState.showSnackbar(msg) }
            },
        )
    }

    // ── Delete confirmation sheet ───────────────────────────────────────────────
    if (showDeleteSheet) {
        val item = items.getOrNull(pagerState.settledPage)
        if (item != null) {
            val deleteSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
            ModalBottomSheet(
                onDismissRequest = { showDeleteSheet = false },
                sheetState = deleteSheetState,
                containerColor = Bg2,
                scrimColor = Color.Black.copy(alpha = 0.5f),
            ) {
                DeleteConfirmSheet(
                    item    = item,
                    onDismiss = { showDeleteSheet = false },
                    onDelete  = { freeUpSpace, deleteFromCloud ->
                        showDeleteSheet = false
                        if (isVaultedItem) {
                            // The vault file is the whole photo, so its delete always takes the
                            // item off the screen and never has a cloud side to weigh.
                            pendingRemoval = item.stableId
                            viewModel.deleteVaultedItem(item)
                        } else {
                            pendingRemoval = item.stableId.takeIf {
                                DeletePhotoUseCase.removesFromGallery(item, freeUpSpace, deleteFromCloud)
                            }
                            viewModel.deleteItem(item, freeUpSpace, deleteFromCloud)
                        }
                    },
                    isVaulted = isVaultedItem,
                )
            }
        }
    }
}

/**
 * True when this decoded drawable is a bitmap carrying an Ultra HDR gain map. Reads the result Coil
 * already handed over, so nothing re-opens the file to answer it.
 */
private fun Drawable.hasGainMap(): Boolean =
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
        (this as? BitmapDrawable)?.bitmap?.hasGainmap() == true

/**
 * Puts the hosting window into HDR while [enabled], and back to the default color mode the moment it
 * is not. Without this a gain-map bitmap still renders SDR, since the window never asked for the
 * extra headroom.
 *
 * The restore runs from `onDispose`, so every exit path is covered by construction: paging to a
 * photo without a gain map, the back gesture, the system back, and the viewer leaving the tree.
 */
@Composable
private fun HdrWindowColorMode(enabled: Boolean) {
    val context = LocalContext.current
    DisposableEffect(context, enabled) {
        val window = context.findActivity()?.window
        window?.colorMode =
            if (enabled) ActivityInfo.COLOR_MODE_HDR else ActivityInfo.COLOR_MODE_DEFAULT
        android.util.Log.d(ULTRA_HDR_TAG, "window color mode hdr=$enabled")
        onDispose { window?.colorMode = ActivityInfo.COLOR_MODE_DEFAULT }
    }
}
