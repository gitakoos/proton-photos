/*
 * Photos for Proton
 * Copyright (C) 2026 Akoos <https://akoos.eu>
 *
 * Source:  https://github.com/gitakoos/proton-photos
 * Website: https://photos.akoos.eu
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

package eu.akoos.photos.navigation

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import eu.akoos.photos.presentation.theme.AppColors
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import android.content.Context
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import me.proton.core.accountmanager.domain.AccountManager
import eu.akoos.photos.data.preferences.SettingsKeys
import eu.akoos.photos.data.preferences.settingsDataStore
import eu.akoos.photos.domain.entity.Album
import eu.akoos.photos.domain.entity.CloudPhoto
import eu.akoos.photos.domain.entity.GalleryItem
import eu.akoos.photos.domain.entity.LocalMediaItem
import androidx.compose.runtime.mutableIntStateOf
import eu.akoos.photos.presentation.albums.AlbumDetailScreen
import eu.akoos.photos.presentation.auth.SignInScreen
import eu.akoos.photos.presentation.calendar.CalendarScreen
import eu.akoos.photos.presentation.calendar.DayDetailScreen
import eu.akoos.photos.presentation.editor.PhotoEditorScreen
import eu.akoos.photos.presentation.editor.VideoEditorScreen
import eu.akoos.photos.presentation.folders.DeviceFolderDetailScreen
import eu.akoos.photos.presentation.gallery.GalleryScreen
import eu.akoos.photos.presentation.hidden.HiddenAlbumScreen
import eu.akoos.photos.presentation.onboarding.OnboardingScreen
import eu.akoos.photos.presentation.settings.AboutScreen
import eu.akoos.photos.presentation.settings.AccountScreen
import eu.akoos.photos.presentation.settings.AppearanceSettingsScreen
import eu.akoos.photos.presentation.settings.LanguageSettingsScreen
import eu.akoos.photos.presentation.settings.PendingDeleteHandler
import eu.akoos.photos.presentation.settings.PrivacySettingsScreen
import eu.akoos.photos.presentation.settings.SecuritySettingsScreen
import eu.akoos.photos.presentation.settings.SettingsScreen
import eu.akoos.photos.presentation.settings.ExcludedFoldersScreen
import eu.akoos.photos.presentation.settings.SyncFoldersScreen
import eu.akoos.photos.presentation.settings.TimelineFilterScreen
import eu.akoos.photos.presentation.settings.SyncSettingsScreen
import eu.akoos.photos.presentation.search.SearchScreen
import eu.akoos.photos.presentation.settings.TrashScreen
import eu.akoos.photos.presentation.viewer.PhotoViewerScreen
import javax.inject.Inject

sealed class Screen(val route: String) {
    data object Gallery : Screen("gallery")
    data object Settings : Screen("settings")
    data object SyncSettings : Screen("sync_settings")
    data object StorageSettings : Screen("storage_settings")
    data object PrivacySettings : Screen("privacy_settings")
    data object SecuritySettings : Screen("security_settings")
    data object Viewer : Screen("viewer")
    data object AlbumDetail : Screen("album_detail")
    data object SyncFolders : Screen("sync_folders")
    data object DeviceFolderDetail : Screen("device_folder_detail")
    data object ExcludedFolders : Screen("excluded_folders")
    data object AlbumMirrorFolders : Screen("album_mirror_folders")
    data object Trash : Screen("trash")
    data object HiddenAlbum : Screen("hidden_album")
    data object PhotoEditor : Screen("photo_editor")
    data object Loading : Screen("loading")
    data object Login : Screen("login")
    data object About : Screen("about")
    data object Account : Screen("account_settings")
    data object Onboarding : Screen("onboarding")
    data object AppearanceSettings : Screen("appearance_settings")
    data object LanguageSettings : Screen("language_settings")
    data object TimelineFilter : Screen("timeline_filter")
    data object Search : Screen("search")
    data object Calendar : Screen("calendar")
    data object DayDetail : Screen("day_detail")
}

enum class StartupRoute { Unknown, NotLoggedIn, NeedsOnboarding, Ready }

@HiltViewModel
class NavViewModel @Inject constructor(
    accountManager: AccountManager,
    @ApplicationContext context: Context,
) : ViewModel() {
    val isLoggedIn: StateFlow<Boolean?> = accountManager.getPrimaryUserId()
        .map { it != null }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /**
     * Combined router signal: whether the user has authenticated AND finished the
     * post login onboarding wizard. NavGraph reads this once to land them on the
     * right destination after the Loading splash. `Unknown` is the cold start
     * value before either signal has emitted, so the LaunchedEffect can wait
     * before issuing the first navigate.
     */
    val startupRoute: StateFlow<StartupRoute> = combine(
        accountManager.getPrimaryUserId().map { it != null },
        context.settingsDataStore.data.map { it[SettingsKeys.ONBOARDING_COMPLETE] == true },
    ) { loggedIn, onboarded ->
        when {
            !loggedIn -> StartupRoute.NotLoggedIn
            !onboarded -> StartupRoute.NeedsOnboarding
            else -> StartupRoute.Ready
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, StartupRoute.Unknown)
}

@Composable
fun NavGraph(
    onStartLogin: () -> Unit = {},
    /** Set by MainActivity when the user taps the home-screen photo widget. The Gallery
     *  composable receives this and (once the items flow has populated) navigates straight
     *  to the viewer for the matching photo. Null on regular cold starts. */
    widgetPhotoUri: String? = null,
    onWidgetPhotoConsumed: () -> Unit = {},
    /** Forwarded to [SettingsScreen]'s "Check for updates" row. MainActivity owns the
     *  call into the singleton UpdateOrchestrator + the Toast feedback so the UI layer
     *  doesn't need to hold an Activity reference. */
    onCheckForUpdates: () -> Unit = {},
    /** Set by MainActivity when the user opens an external image/video via the system
     *  "Open with" / "Edit with" chooser. After we reach the Ready startup state, the
     *  request is captured into Nav-scope state and the editor route is pushed on top
     *  of Gallery so the back press lands the user on their gallery. */
    externalEditRequest: ExternalEditRequest? = null,
    onExternalEditConsumed: () -> Unit = {},
    navViewModel: NavViewModel = hiltViewModel(),
) {
    val navController = rememberNavController()
    val appColors = AppColors.current
    var selectedViewerItems by remember { mutableStateOf<List<GalleryItem>>(emptyList()) }
    var selectedViewerIndex by remember { mutableIntStateOf(0) }
    // Cloud linkIds whose local-side photo is in the Hidden vault — captured from the
    // gallery state at viewer-open time so the viewer can blur + label them too. Without
    // this, opening a hidden cloud counterpart from the photos page showed the un-blurred
    // full-res image (a leak the user explicitly called out).
    var selectedViewerHiddenLinkIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var selectedAlbum by remember { mutableStateOf<Album?>(null) }
    // True when the viewer was opened from an album detail (not from the main gallery).
    // Suppresses the per-photo "Save to device" button — the album has its own "Download all".
    var viewerFromAlbum by remember { mutableStateOf(false) }

    // Selected item handed to the editor. Carries local URI + display name OR a CloudPhoto.
    var editorItem by remember { mutableStateOf<GalleryItem?>(null) }

    // Captured from the MainActivity-owned request once we reach the Ready startup state.
    // Held in Nav scope so the PhotoEditor composable can read it without piping the value
    // through every screen on the back stack.
    var pendingExternalEdit by remember { mutableStateOf<ExternalEditRequest?>(null) }

    // ISO date string (yyyy-MM-dd) handed from Calendar to DayDetail. Held here so the
    // composable-level rememberSaveable inside DayDetailScreen isn't the source of truth
    // (nav between days from the calendar root needs to push fresh dates onto this state).
    var selectedDayDate by remember { mutableStateOf<String?>(null) }
    // The device folder (MediaStore bucket name) the user tapped on the device-folder browser.
    var selectedDeviceFolder by remember { mutableStateOf<String?>(null) }

    val isLoggedIn = navViewModel.isLoggedIn

    // Combined Loading → (Login | Onboarding | Gallery) router. Waits for both the
    // session and onboarding flags to emit before navigating so we don't briefly
    // flash the Gallery for users who still need the wizard. popUpTo Loading with
    // inclusive=true keeps the back stack clean — pressing back from Login /
    // Onboarding / Gallery cannot land on the Loading spinner.
    LaunchedEffect(Unit) {
        navViewModel.startupRoute.collect { route ->
            when (route) {
                StartupRoute.Unknown -> return@collect
                StartupRoute.NotLoggedIn -> {
                    navController.navigate(Screen.Login.route) {
                        popUpTo(Screen.Loading.route) { inclusive = true }
                    }
                }
                StartupRoute.NeedsOnboarding -> {
                    navController.navigate(Screen.Onboarding.route) {
                        popUpTo(Screen.Loading.route) { inclusive = true }
                    }
                }
                StartupRoute.Ready -> {
                    navController.navigate(Screen.Gallery.route) {
                        popUpTo(Screen.Loading.route) { inclusive = true }
                    }
                }
            }
        }
    }

    // Mirror the MainActivity-held request into Nav scope, then clear the Activity-side
    // reference so a re-creation (config change) doesn't replay it.
    LaunchedEffect(externalEditRequest) {
        val req = externalEditRequest ?: return@LaunchedEffect
        pendingExternalEdit = req
        onExternalEditConsumed()
    }

    // External deep route: once the startup gate clears (Ready) and a request is pending,
    // push the right destination on top of Gallery. Gallery stays on the back stack so a
    // back press from viewer/editor lands the user on their gallery rather than dropping
    // them out of the app. Branch:
    //   - ACTION_VIEW (system "Open with" chooser, file managers, gallery apps) → push
    //     the photo viewer with a synthetic single-item list wrapping the foreign URI.
    //   - ACTION_EDIT ("Edit with" chooser, edit affordances in other apps) → push the
    //     editor, which handles the foreign URI and saves any edits as a new copy.
    val startupRouteForExternal by navViewModel.startupRoute.collectAsStateWithLifecycle()
    LaunchedEffect(pendingExternalEdit, startupRouteForExternal) {
        val req = pendingExternalEdit ?: return@LaunchedEffect
        if (startupRouteForExternal != StartupRoute.Ready) return@LaunchedEffect
        if (req.isViewOnly) {
            // Synthesise a one-item LocalOnly GalleryItem from the foreign URI so the
            // viewer can render it through its existing local-uri path. The dateTaken
            // defaults to "now" — we never see the original EXIF capture time on a
            // content:// URI we don't own, and the viewer's info pill falls back to a
            // sensible label rather than crashing on a missing field.
            selectedViewerItems = listOf(
                GalleryItem.LocalOnly(
                    LocalMediaItem(
                        uri = req.uri,
                        dateTaken = System.currentTimeMillis(),
                        displayName = req.displayName,
                        mimeType = req.mimeType,
                        sizeBytes = 0L,
                        bucketName = null,
                    ),
                ),
            )
            selectedViewerIndex = 0
            selectedViewerHiddenLinkIds = emptySet()
            viewerFromAlbum = false
            navController.navigate(Screen.Viewer.route)
            // Viewer reads its data from `selectedViewerItems`, not pendingExternalEdit,
            // so we can drop the request right here. Doing so keeps the LaunchedEffect
            // from re-firing on a future recomposition that happens to land before the
            // viewer is fully on the back stack.
            pendingExternalEdit = null
        } else {
            navController.navigate(Screen.PhotoEditor.route)
        }
    }

    NavHost(
        navController = navController,
        startDestination = Screen.Loading.route,
        modifier = Modifier.fillMaxSize().background(appColors.bg0),
        enterTransition = { fadeIn(tween(180)) },
        exitTransition = { fadeOut(tween(180)) },
        popEnterTransition = { fadeIn(tween(180)) },
        popExitTransition = { fadeOut(tween(180)) },
    ) {
        composable(Screen.Loading.route) {
            Box(Modifier.fillMaxSize().background(appColors.bg0), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = appColors.accent)
            }
        }

        composable(Screen.Login.route) {
            SignInScreen(onSignInClick = onStartLogin)
        }

        composable(Screen.Onboarding.route) {
            OnboardingScreen(
                onComplete = {
                    // ONBOARDING_COMPLETE write inside the wizard re-fires the
                    // startupRoute combine downstream, which navigates to Gallery
                    // automatically via the top-level LaunchedEffect. We still
                    // popUpTo here as belt-and-suspenders for the rare case where
                    // the user back-presses out of Gallery — they should not land
                    // back on the wizard.
                    navController.navigate(Screen.Gallery.route) {
                        popUpTo(Screen.Onboarding.route) { inclusive = true }
                    }
                },
            )
        }

        composable(Screen.Gallery.route) {
            // Foreground drain for the upload worker's pending delete queue. Mounted
            // here on the Gallery route so it lives for the entire authenticated
            // session and renders nothing of its own until there is actually a queue
            // to surface to the user.
            PendingDeleteHandler()
            GalleryScreen(
                onPhotoClick = { items, index, hiddenCloudLinkIds ->
                    selectedViewerItems = items
                    selectedViewerIndex = index
                    selectedViewerHiddenLinkIds = hiddenCloudLinkIds
                    viewerFromAlbum = false
                    navController.navigate(Screen.Viewer.route)
                },
                onAlbumClick = { album ->
                    selectedAlbum = album
                    navController.navigate(Screen.AlbumDetail.route)
                },
                onDeviceFolderClick = { bucketName ->
                    selectedDeviceFolder = bucketName
                    navController.navigate(Screen.DeviceFolderDetail.route)
                },
                onSettingsClick = { navController.navigate(Screen.Settings.route) },
                onHiddenAlbumClick = { navController.navigate(Screen.HiddenAlbum.route) },
                onSearchClick = { navController.navigate(Screen.Search.route) },
                onCalendarClick = { navController.navigate(Screen.Calendar.route) },
                onOpenTimelineFilter = { navController.navigate(Screen.TimelineFilter.route) },
                pendingWidgetPhotoUri = widgetPhotoUri,
                onPendingWidgetPhotoConsumed = onWidgetPhotoConsumed,
            )
        }

        composable(Screen.Calendar.route) {
            CalendarScreen(
                onBack = { navController.popBackStack() },
                onDayClick = { date ->
                    selectedDayDate = date
                    navController.navigate(Screen.DayDetail.route)
                },
            )
        }

        composable(Screen.DayDetail.route) {
            val date = selectedDayDate
            if (date == null) {
                navController.popBackStack()
            } else {
                DayDetailScreen(
                    date = date,
                    onBack = { navController.popBackStack() },
                    onPhotoClick = { items, idx ->
                        selectedViewerItems = items
                        selectedViewerIndex = idx
                        selectedViewerHiddenLinkIds = emptySet()
                        viewerFromAlbum = false
                        navController.navigate(Screen.Viewer.route)
                    },
                )
            }
        }

        composable(Screen.Viewer.route) { backStackEntry ->
            // When the viewer was opened from an album, propagate the album linkId to the
            // viewer + editor so edited/renamed copies land back in the same album.
            val sourceAlbumLinkId = if (viewerFromAlbum) selectedAlbum?.linkId else null
            // Editor writes a timestamp to this entry's savedStateHandle on save; we observe
            // it so the viewer can force a re-load when we pop back into it.
            val editedAt by backStackEntry.savedStateHandle
                .getStateFlow("photoEditedAt", 0L)
                .collectAsStateWithLifecycle()
            PhotoViewerScreen(
                items = selectedViewerItems,
                initialIndex = selectedViewerIndex,
                onBack = { navController.popBackStack() },
                showSaveToDevice = !viewerFromAlbum,
                sourceAlbumLinkId = sourceAlbumLinkId,
                // A non-null `sharedByEmail` on the album means the user is a guest
                // on someone else's album — every mutating affordance in the viewer
                // (delete / set-as-cover / favorite / rename / add-to-album / edit)
                // collapses into a no-op + hides itself behind this flag.
                isReadOnlyAlbum = viewerFromAlbum && selectedAlbum?.sharedByEmail != null,
                editedAt = editedAt,
                hiddenCloudLinkIds = selectedViewerHiddenLinkIds,
                onEditItem = { item ->
                    editorItem = item
                    navController.navigate(Screen.PhotoEditor.route)
                },
            )
        }

        composable(Screen.PhotoEditor.route) {
            val external = pendingExternalEdit
            if (external != null) {
                // External entry: route to the appropriate editor based on the request's mime
                // type. Save flow inside the editor always lands as a new copy under
                // Pictures/Photos for Proton (never overwrites the foreign URI).
                //
                // We deliberately do NOT clear pendingExternalEdit inside onBack / onSaved.
                // Doing so triggers a recomposition that hits the editorItem == null branch
                // below BEFORE NavHost finishes the back navigation, double-popping the back
                // stack and leaving the user on an empty screen. Instead the DisposableEffect
                // at the end of this branch clears the request once the composable actually
                // leaves the back stack, so the next internal navigation (Gallery → Viewer →
                // Edit) sees a clean state.
                if (external.isVideo) {
                    VideoEditorScreen(
                        localUri         = external.uri,
                        localDisplayName = external.displayName,
                        localMimeType    = external.mimeType,
                        externalRequest  = external,
                        onBack           = { navController.popBackStack() },
                        onSaved          = { navController.popBackStack() },
                    )
                } else {
                    PhotoEditorScreen(
                        localUri          = external.uri,
                        localDisplayName  = external.displayName,
                        localMimeType     = external.mimeType,
                        cloudPhoto        = null,
                        sourceAlbumLinkId = null,
                        externalRequest   = external,
                        onBack            = { navController.popBackStack() },
                        onSaved           = { navController.popBackStack() },
                    )
                }
                androidx.compose.runtime.DisposableEffect(Unit) {
                    onDispose { pendingExternalEdit = null }
                }
                return@composable
            }
            val item = editorItem
            val sourceAlbumLinkId = if (viewerFromAlbum) selectedAlbum?.linkId else null
            when (item) {
                is GalleryItem.LocalOnly -> {
                    val isVideo = (item.local.mimeType).startsWith("video/")
                    if (isVideo) {
                        VideoEditorScreen(
                            localUri         = item.local.uri,
                            localDisplayName = item.local.displayName,
                            localMimeType    = item.local.mimeType,
                            onBack           = { navController.popBackStack() },
                            onSaved          = {
                                // Tell the viewer behind us to drop its bitmap cache + reload —
                                // otherwise Coil's still serving the pre-edit bytes by URI key.
                                navController.previousBackStackEntry
                                    ?.savedStateHandle
                                    ?.set("photoEditedAt", System.currentTimeMillis())
                                navController.popBackStack()
                            },
                        )
                    } else {
                        PhotoEditorScreen(
                            localUri         = item.local.uri,
                            localDisplayName = item.local.displayName,
                            localMimeType    = item.local.mimeType,
                            cloudPhoto       = null,
                            sourceAlbumLinkId = sourceAlbumLinkId,
                            onBack           = { navController.popBackStack() },
                            onSaved          = {
                                // Tell the viewer behind us to drop its bitmap cache + reload —
                                // otherwise Coil's still serving the pre-edit bytes by URI key.
                                navController.previousBackStackEntry
                                    ?.savedStateHandle
                                    ?.set("photoEditedAt", System.currentTimeMillis())
                                navController.popBackStack()
                            },
                        )
                    }
                }
                is GalleryItem.Synced -> {
                    val isVideo = (item.local.mimeType).startsWith("video/")
                    if (isVideo) {
                        VideoEditorScreen(
                            localUri         = item.local.uri,
                            localDisplayName = item.local.displayName,
                            localMimeType    = item.local.mimeType,
                            // Edit source is the device file — cloudPhoto stays null so
                            // bytes come from MediaStore, NOT a fresh download. The cloud
                            // counterpart is wired separately so save() also propagates
                            // the edit to Drive.
                            cloudPhoto       = null,
                            sourceAlbumLinkId = sourceAlbumLinkId,
                            syncedCloudCounterpart = item.cloud,
                            onBack           = { navController.popBackStack() },
                            onSaved          = {
                                // Tell the viewer behind us to drop its bitmap cache + reload —
                                // otherwise Coil's still serving the pre-edit bytes by URI key.
                                navController.previousBackStackEntry
                                    ?.savedStateHandle
                                    ?.set("photoEditedAt", System.currentTimeMillis())
                                navController.popBackStack()
                            },
                        )
                    } else {
                        PhotoEditorScreen(
                            localUri         = item.local.uri,
                            localDisplayName = item.local.displayName,
                            localMimeType    = item.local.mimeType,
                            // Edit source is the device file — cloudPhoto stays null so the
                            // bytes come from MediaStore, NOT a fresh download. The cloud
                            // counterpart is wired separately so the save also propagates
                            // the edit to Drive.
                            cloudPhoto       = null,
                            syncedCloudCounterpart = item.cloud,
                            sourceAlbumLinkId = sourceAlbumLinkId,
                            onBack           = { navController.popBackStack() },
                            onSaved          = {
                                // Tell the viewer behind us to drop its bitmap cache + reload —
                                // otherwise Coil's still serving the pre-edit bytes by URI key.
                                navController.previousBackStackEntry
                                    ?.savedStateHandle
                                    ?.set("photoEditedAt", System.currentTimeMillis())
                                navController.popBackStack()
                            },
                        )
                    }
                }
                is GalleryItem.CloudOnly -> {
                    val isVideo = item.cloud.mimeType.startsWith("video/")
                    if (isVideo) {
                        // Cloud video flow: VideoEditorScreen handles download → edit →
                        // re-upload internally. The localUri stays null but cloudPhoto is
                        // set so loadCloud picks the cloud path.
                        VideoEditorScreen(
                            localUri         = null,
                            localDisplayName = item.cloud.displayName,
                            localMimeType    = item.cloud.mimeType,
                            cloudPhoto       = item.cloud,
                            sourceAlbumLinkId = sourceAlbumLinkId,
                            onBack           = { navController.popBackStack() },
                            onSaved          = {
                                // Tell the viewer behind us to drop its bitmap cache + reload —
                                // otherwise Coil's still serving the pre-edit bytes by URI key.
                                navController.previousBackStackEntry
                                    ?.savedStateHandle
                                    ?.set("photoEditedAt", System.currentTimeMillis())
                                navController.popBackStack()
                            },
                        )
                    } else {
                        PhotoEditorScreen(
                            localUri         = null,
                            localDisplayName = null,
                            localMimeType    = null,
                            cloudPhoto       = item.cloud,
                            sourceAlbumLinkId = sourceAlbumLinkId,
                            onBack           = { navController.popBackStack() },
                            onSaved          = {
                                // Tell the viewer behind us to drop its bitmap cache + reload —
                                // otherwise Coil's still serving the pre-edit bytes by URI key.
                                navController.previousBackStackEntry
                                    ?.savedStateHandle
                                    ?.set("photoEditedAt", System.currentTimeMillis())
                                navController.popBackStack()
                            },
                        )
                    }
                }
                null -> { navController.popBackStack() }
            }
        }

        composable(Screen.AlbumDetail.route) {
            val album = selectedAlbum
            if (album != null) {
                AlbumDetailScreen(
                    albumLinkId = album.linkId,
                    albumName = album.name,
                    shareId = album.sharingShareId,
                    sharedByEmail = album.sharedByEmail,
                    volumeId = album.volumeId,
                    coverThumbnailUrl = album.coverThumbnailUrl,
                    onPhotoClick = { photos, index ->
                        selectedViewerItems = photos.map { GalleryItem.CloudOnly(it) }
                        selectedViewerIndex = index
                        // Album views don't carry the hidden-cloud overlay — clear the
                        // set in case the user just came from the photos page where it
                        // was populated.
                        selectedViewerHiddenLinkIds = emptySet()
                        viewerFromAlbum = true
                        navController.navigate(Screen.Viewer.route)
                    },
                    onBack = { navController.popBackStack() },
                )
            }
        }

        composable(Screen.Settings.route) {
            SettingsScreen(
                onBack                    = { navController.popBackStack() },
                onSyncSettingsClick       = { navController.navigate(Screen.SyncSettings.route) },
                onStorageClick            = { navController.navigate(Screen.StorageSettings.route) },
                onPrivacySettingsClick    = { navController.navigate(Screen.PrivacySettings.route) },
                onSecuritySettingsClick   = { navController.navigate(Screen.SecuritySettings.route) },
                onRecentlyDeletedClick    = { navController.navigate(Screen.Trash.route) },
                onAppearanceClick         = { navController.navigate(Screen.AppearanceSettings.route) },
                onLanguageClick           = { navController.navigate(Screen.LanguageSettings.route) },
                onAboutClick              = { navController.navigate(Screen.About.route) },
                onAccountClick            = { navController.navigate(Screen.Account.route) },
                onCheckForUpdatesClick    = onCheckForUpdates,
            )
        }

        composable(Screen.About.route) {
            AboutScreen(onBack = { navController.popBackStack() })
        }

        composable(Screen.AppearanceSettings.route) {
            AppearanceSettingsScreen(
                onBack = { navController.popBackStack() },
                onTimelineFilterClick = { navController.navigate(Screen.TimelineFilter.route) },
            )
        }

        composable(Screen.LanguageSettings.route) {
            LanguageSettingsScreen(
                onBack = { navController.popBackStack() },
                onTimelineFilterClick = { navController.navigate(Screen.TimelineFilter.route) },
            )
        }

        composable(Screen.SyncSettings.route) {
            SyncSettingsScreen(
                onBack                       = { navController.popBackStack() },
                onBackupFoldersClick         = { navController.navigate(Screen.SyncFolders.route) },
                onExcludedFoldersClick       = { navController.navigate(Screen.ExcludedFolders.route) },
                onAlbumMirrorFoldersClick    = { navController.navigate(Screen.AlbumMirrorFolders.route) },
                onRecentlyDeletedClick       = { navController.navigate(Screen.Trash.route) },
            )
        }

        composable(Screen.AlbumMirrorFolders.route) {
            eu.akoos.photos.presentation.settings.AlbumMirrorFoldersScreen(
                onBack = { navController.popBackStack() },
            )
        }

        composable(Screen.StorageSettings.route) {
            eu.akoos.photos.presentation.settings.StorageSettingsScreen(
                onBack                 = { navController.popBackStack() },
                onRecentlyDeletedClick = { navController.navigate(Screen.Trash.route) },
            )
        }

        composable(Screen.PrivacySettings.route) {
            PrivacySettingsScreen(
                onBack = { navController.popBackStack() },
                onHiddenAlbumClick = { navController.navigate(Screen.HiddenAlbum.route) },
            )
        }

        composable(Screen.Account.route) {
            val settingsVm: eu.akoos.photos.presentation.settings.SettingsViewModel = hiltViewModel()
            AccountScreen(
                onBack = { navController.popBackStack() },
                onSignOut = { settingsVm.signOut() },
                viewModel = settingsVm,
            )
        }

        composable(Screen.SecuritySettings.route) {
            SecuritySettingsScreen(
                onBack             = { navController.popBackStack() },
                onHiddenAlbumClick = { navController.navigate(Screen.HiddenAlbum.route) },
            )
        }

        composable(Screen.HiddenAlbum.route) {
            HiddenAlbumScreen(
                onBack = { navController.popBackStack() },
                onPhotoClick = { items, index ->
                    // Pass the entire hidden list to the viewer so the user can swipe between
                    // hidden photos like in the main gallery. Each item is wrapped as a
                    // LocalOnly GalleryItem because hidden photos live as file:// URIs in
                    // app-private storage, not as MediaStore content URIs.
                    selectedViewerItems = items.map { eu.akoos.photos.domain.entity.GalleryItem.LocalOnly(it) }
                    selectedViewerIndex = index
                    selectedViewerHiddenLinkIds = emptySet()
                    navController.navigate(Screen.Viewer.route)
                },
            )
        }

        composable(Screen.SyncFolders.route) {
            SyncFoldersScreen(onBack = { navController.popBackStack() })
        }

        composable(Screen.DeviceFolderDetail.route) {
            val bucketName = selectedDeviceFolder
            if (bucketName == null) {
                navController.popBackStack()
            } else {
                DeviceFolderDetailScreen(
                    bucketName = bucketName,
                    onPhotoClick = { items, index ->
                        selectedViewerItems = items
                        selectedViewerIndex = index
                        selectedViewerHiddenLinkIds = emptySet()
                        viewerFromAlbum = false
                        navController.navigate(Screen.Viewer.route)
                    },
                    onBack = { navController.popBackStack() },
                )
            }
        }

        composable(Screen.ExcludedFolders.route) {
            ExcludedFoldersScreen(onBack = { navController.popBackStack() })
        }

        composable(Screen.TimelineFilter.route) {
            TimelineFilterScreen(onBack = { navController.popBackStack() })
        }

        composable(Screen.Trash.route) {
            TrashScreen(onBack = { navController.popBackStack() })
        }

        composable(Screen.Search.route) {
            SearchScreen(
                onBack = { navController.popBackStack() },
                onPhotoClick = { items, index ->
                    selectedViewerItems = items
                    selectedViewerIndex = index
                    selectedViewerHiddenLinkIds = emptySet()
                    viewerFromAlbum = false
                    navController.navigate(Screen.Viewer.route)
                },
            )
        }

    }
}
