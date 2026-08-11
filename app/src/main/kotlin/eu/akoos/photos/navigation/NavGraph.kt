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

package eu.akoos.photos.navigation

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
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
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import android.content.Context
import androidx.compose.ui.platform.LocalContext
import eu.akoos.photos.presentation.util.findActivity
import eu.akoos.photos.BuildConfig
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import me.proton.core.accountmanager.domain.AccountManager
import eu.akoos.photos.data.preferences.SettingsKeys
import eu.akoos.photos.data.preferences.settingsDataStore
import eu.akoos.photos.data.repository.drive.ThumbnailUrlStore
import eu.akoos.photos.presentation.common.LocalViewerReturnKey
import eu.akoos.photos.presentation.gallery.LocalThumbnailUrls
import androidx.compose.runtime.CompositionLocalProvider
import eu.akoos.photos.domain.entity.Album
import eu.akoos.photos.domain.entity.CloudPhoto
import eu.akoos.photos.domain.entity.GalleryItem
import eu.akoos.photos.domain.entity.LocalMediaItem
import androidx.compose.runtime.mutableIntStateOf
import eu.akoos.photos.presentation.albums.AlbumDetailScreen
import eu.akoos.photos.presentation.albums.AlbumOpenAction
import eu.akoos.photos.presentation.albums.AlbumPhotoPickerScreen
import eu.akoos.photos.presentation.auth.SignInScreen
import eu.akoos.photos.presentation.calendar.CalendarScreen
import eu.akoos.photos.presentation.calendar.DayDetailScreen
import eu.akoos.photos.presentation.editor.PhotoEditorScreen
import eu.akoos.photos.presentation.editor.VideoEditorScreen
import eu.akoos.photos.presentation.folders.DeviceFolderDetailScreen
import eu.akoos.photos.presentation.folders.DeviceFolderOpenAction
import eu.akoos.photos.presentation.gallery.GalleryScreen
import eu.akoos.photos.presentation.hidden.HiddenAlbumScreen
import eu.akoos.photos.presentation.offline.OfflinePhotosScreen
import eu.akoos.photos.presentation.memories.MemoriesScreen
import eu.akoos.photos.presentation.memories.MemoryCategory
import eu.akoos.photos.presentation.memories.MemoryCategoryScreen
import eu.akoos.photos.presentation.collage.CollageScreen
import eu.akoos.photos.presentation.metadata.MetadataEditorScreen
import eu.akoos.photos.presentation.onboarding.OnboardingScreen
import eu.akoos.photos.presentation.settings.AboutScreen
import eu.akoos.photos.presentation.settings.AccountScreen
import eu.akoos.photos.presentation.settings.FaqScreen
import eu.akoos.photos.presentation.settings.AppearanceSettingsScreen
import eu.akoos.photos.presentation.settings.LandingTabScreen
import eu.akoos.photos.presentation.settings.LanguageSettingsScreen
import eu.akoos.photos.presentation.settings.NotificationSettingsScreen
import eu.akoos.photos.presentation.settings.PendingDeleteHandler
import eu.akoos.photos.presentation.settings.PrivacySettingsScreen
import eu.akoos.photos.presentation.settings.SecuritySettingsScreen
import eu.akoos.photos.presentation.settings.SettingsScreen
import eu.akoos.photos.presentation.settings.ExcludedFoldersScreen
import eu.akoos.photos.presentation.settings.SyncFoldersScreen
import eu.akoos.photos.presentation.settings.TimelineFilterScreen
import eu.akoos.photos.presentation.settings.TimelineLayoutScreen
import eu.akoos.photos.presentation.settings.TimelineCategoriesScreen
import eu.akoos.photos.presentation.settings.TimelineAlbumsScreen
import eu.akoos.photos.presentation.settings.TimelineDeviceFoldersScreen
import eu.akoos.photos.presentation.settings.SyncSettingsScreen
import eu.akoos.photos.presentation.map.MapScreen
import eu.akoos.photos.presentation.search.SearchScreen
import eu.akoos.photos.presentation.settings.TrashScreen
import eu.akoos.photos.presentation.viewer.PhotoViewerScreen
import eu.akoos.photos.presentation.news.NewsScreen
import eu.akoos.photos.presentation.whatsnew.WhatsNewHistoryScreen
import eu.akoos.photos.presentation.whatsnew.WhatsNewScreen
import javax.inject.Inject
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import eu.akoos.photos.R
import eu.akoos.photos.presentation.common.ThemedSnackbarHost
import eu.akoos.photos.presentation.common.UndoAction
import eu.akoos.photos.presentation.common.UndoBarViewModel
import kotlinx.coroutines.withTimeoutOrNull

sealed class Screen(val route: String) {
    data object Gallery : Screen("gallery")
    data object Settings : Screen("settings")
    data object SyncSettings : Screen("sync_settings")
    data object MetadataSettings : Screen("metadata_settings")
    data object UploadFileName : Screen("upload_file_name")
    data object UploadMetadata : Screen("upload_metadata")
    data object UploadQuality : Screen("upload_quality")
    data object Activity : Screen("activity")
    data object BackupContent : Screen("backup_content")
    data object BackupBehavior : Screen("backup_behavior")
    data object BackupNetwork : Screen("backup_network")
    data object StorageSettings : Screen("storage_settings")
    data object FreeUpSpace : Screen("free_up_space")
    data object PrivacySettings : Screen("privacy_settings")
    data object SecuritySettings : Screen("security_settings")
    data object PrivacySecuritySettings : Screen("privacy_security_settings")
    data object Permissions : Screen("permissions")
    data object Viewer : Screen("viewer")
    data object AlbumDetail : Screen("album_detail")
    data object AlbumPhotoPicker : Screen("album_photo_picker")
    data object SyncFolders : Screen("sync_folders")
    data object DeviceFolderDetail : Screen("device_folder_detail")
    data object ExcludedFolders : Screen("excluded_folders")
    data object Trash : Screen("trash")
    data object DuplicateFinder : Screen("duplicate_finder")
    data object HiddenAlbum : Screen("hidden_album")
    data object Offline : Screen("offline_photos")
    data object PhotoEditor : Screen("photo_editor")
    data object MetadataEditor : Screen("metadata_editor")
    data object Collage : Screen("collage")
    data object CollagePhotoPicker : Screen("collage_photo_picker")
    data object Loading : Screen("loading")
    data object Login : Screen("login")
    data object About : Screen("about")
    data object Faq : Screen("faq")
    data object Account : Screen("account_settings")
    data object Onboarding : Screen("onboarding")
    data object WhatsNew : Screen("whats_new")

    /** The update history list, and one release read from it. */
    data object WhatsNewHistory : Screen("whats_new_history")
    data object News : Screen("news")

    data object WhatsNewRelease : Screen("whats_new_release/{version}") {
        fun route(version: String) = "whats_new_release/$version"
    }
    data object AppearanceSettings : Screen("appearance_settings")
    data object LandingTab : Screen("landing_tab")
    data object ThemeSettings : Screen("theme_settings")
    data object LanguageSettings : Screen("language_settings")
    data object NotificationSettings : Screen("notification_settings")
    data object TimelineFilter : Screen("timeline_filter")
    data object TimelineLayout : Screen("timeline_layout")
    data object TimelineCategories : Screen("timeline_categories")
    data object TimelineAlbums : Screen("timeline_albums")
    data object TimelineDeviceFolders : Screen("timeline_device_folders")
    data object Search : Screen("search")
    data object Map : Screen("map")
    data object Calendar : Screen("calendar")
    data object Memories : Screen("memories")
    data object MemoryCategory : Screen("memory_category/{type}") {
        fun create(type: String) = "memory_category/$type"
    }
    data object DayDetail : Screen("day_detail")
}

enum class StartupRoute { Unknown, NotLoggedIn, NeedsOnboarding, Ready }

/**
 * What the metadata editor is bound to, handed over in nav scope because a [GalleryItem] can't be
 * serialized into a nav argument. The photos and their read-only state travel as one value, so the
 * editor always shows the state of the navigation that opened it: a call site that supplies items
 * has to state their read-only state in the same expression, and no earlier navigation can leave a
 * flag behind for the next one to pick up.
 */
private data class MetadataEditorRequest(
    val items: List<GalleryItem>,
    /** True only for a photo reached through an album shared with the user, where every field is
     *  read-only and the screen shows its shared-album note in place of the controls. */
    val isReadOnlyAlbum: Boolean,
)

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

/**
 * Exposes the app-scoped [ThumbnailUrlStore] map to the composition. Obtained once at the nav root
 * so every screen below inherits the live linkId -> thumbnail URL map through [LocalThumbnailUrls];
 * a decrypt that lands mid-scroll then repaints just the affected cell on any of them without each
 * screen wiring the store itself.
 */
@HiltViewModel
class ThumbnailUrlsViewModel @Inject constructor(
    store: ThumbnailUrlStore,
) : ViewModel() {
    val urls: StateFlow<Map<String, String>> = store.urls
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
    // Live linkId -> decrypted thumbnail URL from the app-scoped store, provided once below as a
    // stable State so every screen's gallery cells resolve their cloud thumbnail without threading
    // the store in, and a decrypt mid-scroll rebinds only the changed tiles, not the nav root.
    val thumbnailUrlsViewModel: ThumbnailUrlsViewModel = hiltViewModel()
    val thumbnailUrlsState = thumbnailUrlsViewModel.urls.collectAsStateWithLifecycle()
    var selectedViewerItems by remember { mutableStateOf<List<GalleryItem>>(emptyList()) }
    var selectedViewerIndex by remember { mutableIntStateOf(0) }
    // The return leg of the line above: the photo the viewer closed on, for the grid underneath to
    // land on. Held as the State itself rather than a delegate because it is handed down through
    // [LocalViewerReturnKey], which every photo grid reads and clears once it has acted.
    val viewerReturnKey = remember { mutableStateOf<String?>(null) }
    // Cloud linkIds whose local-side photo is in the Hidden vault — captured from the
    // gallery state at viewer-open time so the viewer can blur + label them too. Without
    // this, opening a hidden cloud counterpart from the photos page showed the un-blurred
    // full-res image — a privacy leak.
    var selectedViewerHiddenLinkIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    // True when the viewer was opened from the Hidden vault, so the viewer window is marked
    // FLAG_SECURE and full-res hidden photos stay out of screenshots and the recent-apps preview.
    var viewerSecure by remember { mutableStateOf(false) }
    var selectedAlbum by remember { mutableStateOf<Album?>(null) }
    // True when the album was opened to be shared rather than browsed, so the album screen raises its
    // share drawer on arrival. Cleared the moment that screen acts on it, so a return from the viewer
    // does not raise the drawer a second time.
    var albumOpenedToShare by remember { mutableStateOf(false) }
    // Set when the album was opened to be downloaded, played or added to rather than browsed, so the
    // album screen carries that action out on arrival. Cleared the moment that screen acts on it, so
    // a return from the viewer does not start a second download.
    var albumOpenAction by remember { mutableStateOf<AlbumOpenAction?>(null) }
    // Cloud linkIds already in the album being added to — handed from AlbumDetail at picker-open
    // time so the picker pre-filters them out (no re-adding duplicates).
    var pickerExcludeLinkIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    // True when the viewer was opened from an album detail (not from the main gallery).
    // Suppresses the per-photo "Save to device" button — the album has its own "Download all".
    var viewerFromAlbum by remember { mutableStateOf(false) }
    // True when the entry point asked for a slideshow rather than a single photo, so the viewer
    // opens already playing. Cleared on the way out, like viewerSecure, so an ordinary photo tap
    // afterwards opens still.
    var viewerAutoplay by remember { mutableStateOf(false) }

    // Selected item handed to the editor. Carries local URI + display name OR a CloudPhoto.
    var editorItem by remember { mutableStateOf<GalleryItem?>(null) }

    // What the metadata (date + place) editor is bound to: one photo from the viewer, or a whole
    // multi-select from the grid. Same nav-scope hand-off as the photo editor above, with the
    // read-only state carried alongside the items so it is always written by the navigation that
    // opens the editor.
    var metadataEditorRequest by remember { mutableStateOf<MetadataEditorRequest?>(null) }
    // The photos handed to the collage editor, in nav scope because a GalleryItem list can't be a
    // nav argument (same hand-off the metadata editor uses).
    var collageItems by remember { mutableStateOf<List<GalleryItem>>(emptyList()) }
    // Photos the in-app picker returned to add to the open collage.
    var collagePicked by remember { mutableStateOf<List<GalleryItem>?>(null) }

    // Captured from the MainActivity-owned request once we reach the Ready startup state.
    // Held in Nav scope so the PhotoEditor composable can read it without piping the value
    // through every screen on the back stack.
    var pendingExternalEdit by remember { mutableStateOf<ExternalEditRequest?>(null) }

    /**
     * Leave the viewer or the editor: step back if there is anywhere to step back to, and otherwise
     * end the activity.
     *
     * The fallback is what an external open needs. A photo handed over by another app opens with
     * nothing of ours underneath it, so `popBackStack` finds no entry, answers false and does
     * nothing; without the finish the close button would be dead and only the system back would
     * work. Ending the activity there is what returns the user to the app that sent them, which is
     * where a back press from a handed-over photo belongs.
     *
     * For an ordinary in-app open there is always an entry below, so this is exactly `popBackStack`
     * and nothing changes. One rule covers both rather than each exit having to know which kind of
     * open it is closing.
     */
    val navContext = LocalContext.current
    val hostActivity = remember(navContext) { navContext.findActivity() }
    val leaveOverlayScreen: () -> Unit = {
        if (!navController.popBackStack()) hostActivity?.finish()
    }

    // ISO date string (yyyy-MM-dd) handed from Calendar to DayDetail. Held here so the
    // composable-level rememberSaveable inside DayDetailScreen isn't the source of truth
    // (nav between days from the calendar root needs to push fresh dates onto this state).
    var selectedDayDate by remember { mutableStateOf<String?>(null) }
    // The device folder (MediaStore bucket name) the user tapped on the device-folder browser.
    var selectedDeviceFolder by remember { mutableStateOf<String?>(null) }
    // Set when the folder was opened to be backed up or played rather than browsed, so the folder
    // screen carries that action out on arrival. Cleared the moment that screen acts on it, so a
    // return from the viewer does not start the slideshow a second time.
    var deviceFolderOpenAction by remember { mutableStateOf<DeviceFolderOpenAction?>(null) }
    // Which card opened that folder. A folder the vault holds only part of has one on the Albums grid
    // and one in the vault, holding disjoint photos, so the screen has to be told which it is showing.
    var deviceFolderFromVault by remember { mutableStateOf(false) }

    // One-shot latch for the post-update "What's new" gate. Flipped the first time the Gallery
    // route mounts so the version check + navigate fires at most once per process, even if the
    // user navigates back to the gallery from a sub-screen. The persisted seen-version pref is
    // the durable guard across launches; this just prevents a re-fire before the async write lands.
    var whatsNewChecked by remember { mutableStateOf(false) }

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
                        // Pop the ENTIRE graph, not just Loading: on a fresh sign-in the
                        // NotLoggedIn step already consumed Loading and left Login on the stack,
                        // so a Loading-only pop leaves Login under Gallery and back lands on it.
                        // Popping the graph root clears Loading / Login / Onboarding uniformly, so
                        // back from Gallery exits the app.
                        popUpTo(navController.graph.id) { inclusive = true }
                        launchSingleTop = true
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

    // External deep route: once the startup gate clears (Ready) and a request is pending, make the
    // right destination the ONLY entry on the back stack, clearing the Gallery the startup route
    // just put there. A photo handed over by another app is that app's photo, so a back press
    // belongs to whoever sent it; leaving Gallery underneath made the first back land in our own
    // timeline and only the second return to the caller. Nothing further is needed to get there:
    // the activity declares no launchMode, so it runs inside the calling task, and emptying our own
    // stack is the whole of it. Branch:
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
            navController.navigate(Screen.Viewer.route) {
                popUpTo(navController.graph.id) { inclusive = true }
            }
            // Viewer reads its data from `selectedViewerItems`, not pendingExternalEdit,
            // so we can drop the request right here. Doing so keeps the LaunchedEffect
            // from re-firing on a future recomposition that happens to land before the
            // viewer is fully on the back stack.
            pendingExternalEdit = null
        } else {
            navController.navigate(Screen.PhotoEditor.route) {
                popUpTo(navController.graph.id) { inclusive = true }
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
    CompositionLocalProvider(
        LocalThumbnailUrls provides thumbnailUrlsState,
        LocalViewerReturnKey provides viewerReturnKey,
    ) {
    NavHost(
        navController = navController,
        startDestination = Screen.Loading.route,
        modifier = Modifier.fillMaxSize().background(appColors.bg0),
        // A gentle directional shared-axis: the incoming screen fades in while sliding a short way from
        // the forward edge, and back reverses it, so navigation reads as motion rather than a flat cut.
        // The slide is small (a sixth of the width) so it also suits full-screen routes like the viewer.
        enterTransition = { fadeIn(tween(220)) + slideInHorizontally(tween(220)) { it / 6 } },
        exitTransition = { fadeOut(tween(220)) + slideOutHorizontally(tween(220)) { -it / 12 } },
        popEnterTransition = { fadeIn(tween(220)) + slideInHorizontally(tween(220)) { -it / 12 } },
        popExitTransition = { fadeOut(tween(220)) + slideOutHorizontally(tween(220)) { it / 6 } },
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

        composable(Screen.WhatsNew.route) {
            // The screen marks the version seen internally (markSeen) before invoking onDone, on
            // the button, the arrow and its own BackHandler, so every exit settles the gate. Without
            // that handler a system back popped this composable here instead, leaving the version
            // unseen and the screen due again on the next launch.
            WhatsNewScreen(onDone = { navController.popBackStack() })
        }

        composable(Screen.WhatsNewHistory.route) {
            WhatsNewHistoryScreen(
                onBack = { navController.popBackStack() },
                onOpenRelease = { version -> navController.navigate(Screen.WhatsNewRelease.route(version)) },
            )
        }

        composable(Screen.News.route) {
            NewsScreen(onBack = { navController.popBackStack() })
        }

        composable(
            Screen.WhatsNewRelease.route,
            arguments = listOf(navArgument("version") { type = NavType.StringType }),
        ) { entry ->
            // An unknown version resolves to the newest rather than showing an empty pager, so a
            // stale link or a removed catalog entry cannot strand the user on a blank screen.
            WhatsNewScreen(
                onDone = { navController.popBackStack() },
                version = entry.arguments?.getString("version"),
            )
        }

        composable(Screen.Gallery.route) {
            // Foreground drain for the upload worker's pending delete queue. Mounted
            // here on the Gallery route so it lives for the entire authenticated
            // session and renders nothing of its own until there is actually a queue
            // to surface to the user.
            PendingDeleteHandler()
            // One-time post-update highlights. The startup router only lands here once the user is
            // fully Ready (logged in AND onboarded), so reaching this composable already implies
            // that gate. We read the seen-version pref once and, if this build's versionCode is
            // newer, push the What's-new screen on top of the gallery. The latch + the pref check
            // together keep it to a single show: markSeen() (called on every exit) raises the
            // stored version so the condition is false on the next launch.
            val whatsNewContext = LocalContext.current
            LaunchedEffect(Unit) {
                if (whatsNewChecked) return@LaunchedEffect
                whatsNewChecked = true
                val seen = whatsNewContext.settingsDataStore.data.first()[SettingsKeys.WHATS_NEW_SEEN_VERSION] ?: 0
                if (seen < BuildConfig.VERSION_CODE) {
                    navController.navigate(Screen.WhatsNew.route)
                }
            }
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
                    albumOpenedToShare = false
                    albumOpenAction = null
                    navController.navigate(Screen.AlbumDetail.route)
                },
                onAlbumShareClick = { album ->
                    selectedAlbum = album
                    albumOpenedToShare = true
                    albumOpenAction = null
                    navController.navigate(Screen.AlbumDetail.route)
                },
                onAlbumActionClick = { album, action ->
                    selectedAlbum = album
                    albumOpenedToShare = false
                    albumOpenAction = action
                    navController.navigate(Screen.AlbumDetail.route)
                },
                onDeviceFolderClick = { bucketName ->
                    selectedDeviceFolder = bucketName
                    deviceFolderOpenAction = null
                    deviceFolderFromVault = false
                    navController.navigate(Screen.DeviceFolderDetail.route)
                },
                onDeviceFolderActionClick = { bucketName, action ->
                    selectedDeviceFolder = bucketName
                    deviceFolderOpenAction = action
                    deviceFolderFromVault = false
                    navController.navigate(Screen.DeviceFolderDetail.route)
                },
                onSettingsClick = { navController.navigate(Screen.Settings.route) },
                onOpenUploads = { navController.navigate("activity?tab=uploads") },
                onOpenDownloads = { navController.navigate("activity?tab=downloads") },
                onHiddenAlbumClick = { navController.navigate(Screen.HiddenAlbum.route) },
                onSearchClick = { navController.navigate(Screen.Search.route) },
                onCalendarClick = { navController.navigate(Screen.Calendar.route) },
                onMemoriesClick = { navController.navigate(Screen.Memories.route) },
                onOpenTimelineFilter = { navController.navigate(Screen.TimelineFilter.route) },
                onEditMetadata = { selection ->
                    // A timeline selection is the user's own library, never an album someone shared
                    // with them, so the editor's own per-item rules decide what is writable.
                    metadataEditorRequest = MetadataEditorRequest(selection, isReadOnlyAlbum = false)
                    navController.navigate(Screen.MetadataEditor.route)
                },
                onCreateCollage = { selection ->
                    collageItems = selection
                    navController.navigate(Screen.Collage.route)
                },
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
                onOpenMap = {
                    navController.navigate(Screen.Map.route) {
                        popUpTo(Screen.Calendar.route) { inclusive = true }
                        launchSingleTop = true
                    }
                },
                onOpenSearch = {
                    navController.navigate(Screen.Search.route) {
                        popUpTo(Screen.Calendar.route) { inclusive = true }
                        launchSingleTop = true
                    }
                },
            )
        }

        composable(Screen.Memories.route) {
            MemoriesScreen(
                onBack = { navController.popBackStack() },
                onPhotoClick = { items, index ->
                    selectedViewerItems = items
                    selectedViewerIndex = index
                    selectedViewerHiddenLinkIds = emptySet()
                    viewerFromAlbum = false
                    navController.navigate(Screen.Viewer.route)
                },
                onSeeAll = { cat ->
                    navController.navigate(
                        Screen.MemoryCategory.create(
                            if (cat == MemoryCategory.ON_THIS_DAY) "onthisday" else "seasons",
                        ),
                    )
                },
            )
        }

        composable(
            Screen.MemoryCategory.route,
            arguments = listOf(navArgument("type") { type = NavType.StringType }),
        ) { backStackEntry ->
            val category = if (backStackEntry.arguments?.getString("type") == "seasons") {
                MemoryCategory.SEASONS
            } else {
                MemoryCategory.ON_THIS_DAY
            }
            MemoryCategoryScreen(
                category = category,
                onBack = { navController.popBackStack() },
                onPhotoClick = { items, index ->
                    selectedViewerItems = items
                    selectedViewerIndex = index
                    selectedViewerHiddenLinkIds = emptySet()
                    viewerFromAlbum = false
                    navController.navigate(Screen.Viewer.route)
                },
                onSwitchCategory = { cat ->
                    val type = if (cat == MemoryCategory.ON_THIS_DAY) "onthisday" else "seasons"
                    navController.navigate(Screen.MemoryCategory.create(type)) {
                        popUpTo(Screen.MemoryCategory.route) { inclusive = true }
                    }
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
            // A non-null `sharedByEmail` on the album means the user is a guest on someone else's
            // album. Held as one value so every screen the viewer opens is handed the same answer.
            val readOnlyAlbum = viewerFromAlbum && selectedAlbum?.sharedByEmail != null
            // Editor writes a timestamp to this entry's savedStateHandle on save; we observe
            // it so the viewer can force a re-load when we pop back into it.
            val editedAt by backStackEntry.savedStateHandle
                .getStateFlow("photoEditedAt", 0L)
                .collectAsStateWithLifecycle()
            PhotoViewerScreen(
                items = selectedViewerItems,
                initialIndex = selectedViewerIndex,
                onBack = { settledKey ->
                    viewerSecure = false
                    viewerAutoplay = false
                    viewerReturnKey.value = settledKey
                    leaveOverlayScreen()
                },
                sourceAlbumLinkId = sourceAlbumLinkId,
                // Every mutating affordance in the viewer (delete / set-as-cover / favorite /
                // rename / add-to-album / edit) collapses into a no-op + hides itself behind
                // this flag.
                isReadOnlyAlbum = readOnlyAlbum,
                // Taking a photo out of an album is an edit, so it follows the same right as
                // adding rather than plain ownership. That makes it a separate question from
                // isReadOnlyAlbum: an editor on a shared album may remove, and is exactly the
                // person with no other route to it.
                canRemoveFromAlbum = viewerFromAlbum && selectedAlbum?.canAddPhotos == true,
                editedAt = editedAt,
                hiddenCloudLinkIds = selectedViewerHiddenLinkIds,
                secure = viewerSecure,
                startSlideshow = viewerAutoplay,
                onEditItem = { item ->
                    editorItem = item
                    navController.navigate(Screen.PhotoEditor.route)
                },
                onEditMetadata = { item ->
                    metadataEditorRequest = MetadataEditorRequest(listOf(item), readOnlyAlbum)
                    navController.navigate(Screen.MetadataEditor.route)
                },
            )
        }

        composable(Screen.PhotoEditor.route) {
            val external = pendingExternalEdit
            if (external != null) {
                // External entry: route to the appropriate editor based on the request's mime
                // type. Save flow inside the editor always lands as a new copy under
                // DCIM/Camera (never overwrites the foreign URI).
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
                        onBack           = leaveOverlayScreen,
                        onSaved          = leaveOverlayScreen,
                    )
                } else {
                    PhotoEditorScreen(
                        localUri          = external.uri,
                        localDisplayName  = external.displayName,
                        localMimeType     = external.mimeType,
                        // A foreign "Open with" file has no known capture time.
                        localCaptureTimeMs = null,
                        cloudPhoto        = null,
                        sourceAlbumLinkId = null,
                        externalRequest   = external,
                        onBack            = leaveOverlayScreen,
                        onSaved           = leaveOverlayScreen,
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
                            // Inherit the original capture time so a Copy sorts next to the original.
                            localCaptureTimeMs = item.local.dateTaken,
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
                            // Inherit the original capture time so a Copy sorts next to the original.
                            localCaptureTimeMs = item.local.dateTaken,
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

        composable(Screen.MetadataEditor.route) {
            val request = metadataEditorRequest
            if (request == null || request.items.isEmpty()) {
                navController.popBackStack()
            } else {
                MetadataEditorScreen(
                    items = request.items,
                    // Mirrors the viewer's guest gate: a photo in an album shared with the user is
                    // fully read-only, so the editor surfaces its date + place without controls.
                    isReadOnlyAlbum = request.isReadOnlyAlbum,
                    onBack = { navController.popBackStack() },
                )
            }
        }

        composable(Screen.Collage.route) {
            val items = collageItems
            if (items.isEmpty()) {
                navController.popBackStack()
            } else {
                CollageScreen(
                    items = items,
                    onClose = { navController.popBackStack() },
                    onRequestAddPhotos = { navController.navigate(Screen.CollagePhotoPicker.route) },
                    pendingAdd = collagePicked ?: emptyList(),
                    onPendingAddConsumed = { collagePicked = null },
                )
            }
        }

        composable(Screen.CollagePhotoPicker.route) {
            // The album picker in "return" mode: it hands the selected photos back to the collage
            // instead of adding them to an album.
            AlbumPhotoPickerScreen(
                onBack = { navController.popBackStack() },
                onPick = { picked ->
                    collagePicked = picked
                    navController.popBackStack()
                },
            )
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
                    openShareSheet = albumOpenedToShare,
                    onShareSheetRequestConsumed = { albumOpenedToShare = false },
                    openAction = albumOpenAction,
                    onOpenActionConsumed = { albumOpenAction = null },
                    onPhotoClick = { items, index ->
                        // Items arrive already typed (Synced when a local copy exists, else
                        // CloudOnly) so the viewer's delete sheet offers the right options.
                        selectedViewerItems = items
                        selectedViewerIndex = index
                        // Album views don't carry the hidden-cloud overlay — clear the
                        // set in case the user just came from the photos page where it
                        // was populated.
                        selectedViewerHiddenLinkIds = emptySet()
                        viewerFromAlbum = true
                        navController.navigate(Screen.Viewer.route)
                    },
                    onSlideshowClick = { items ->
                        // Same viewer, same album context — it just opens on the first photo with
                        // the slideshow already running.
                        selectedViewerItems = items
                        selectedViewerIndex = 0
                        selectedViewerHiddenLinkIds = emptySet()
                        viewerFromAlbum = true
                        viewerAutoplay = true
                        navController.navigate(Screen.Viewer.route)
                    },
                    onAddPhotosClick = { currentLinkIds ->
                        // Capture the album's current members so the picker hides them.
                        pickerExcludeLinkIds = currentLinkIds
                        navController.navigate(Screen.AlbumPhotoPicker.route)
                    },
                    onBack = { navController.popBackStack() },
                )
            }
        }

        composable(Screen.AlbumPhotoPicker.route) {
            val album = selectedAlbum
            if (album == null) {
                navController.popBackStack()
            } else {
                AlbumPhotoPickerScreen(
                    albumLinkId = album.linkId,
                    albumName = album.name,
                    excludeLinkIds = pickerExcludeLinkIds,
                    onBack = { navController.popBackStack() },
                    // The album observes the add via AlbumListEventBus and refreshes itself.
                    onAdded = { navController.popBackStack() },
                )
            }
        }

        composable(Screen.Settings.route) {
            SettingsScreen(
                onBack                    = { navController.popBackStack() },
                onSyncSettingsClick       = { navController.navigate(Screen.SyncSettings.route) },
                onActivityClick           = { navController.navigate(Screen.Activity.route) },
                onStorageClick            = { navController.navigate(Screen.StorageSettings.route) },
                onPrivacySecurityClick    = { navController.navigate(Screen.PrivacySecuritySettings.route) },
                onPermissionsClick        = { navController.navigate(Screen.Permissions.route) },
                onNotificationsClick      = { navController.navigate(Screen.NotificationSettings.route) },
                onRecentlyDeletedClick    = { navController.navigate(Screen.Trash.route) },
                onFindDuplicatesClick     = { navController.navigate(Screen.DuplicateFinder.route) },
                onAppearanceClick         = { navController.navigate(Screen.AppearanceSettings.route) },
                onLanguageClick           = { navController.navigate(Screen.LanguageSettings.route) },
                onAboutClick              = { navController.navigate(Screen.About.route) },
                onWhatsNewClick           = { navController.navigate(Screen.WhatsNewHistory.route) },
                onNewsClick               = { navController.navigate(Screen.News.route) },
                onFaqClick                = { navController.navigate(Screen.Faq.route) },
                onAccountClick            = { navController.navigate(Screen.Account.route) },
                onCheckForUpdatesClick    = onCheckForUpdates,
            )
        }

        composable(Screen.Permissions.route) {
            eu.akoos.photos.presentation.settings.PermissionsScreen(onBack = { navController.popBackStack() })
        }

        composable(Screen.About.route) {
            AboutScreen(onBack = { navController.popBackStack() })
        }

        composable(Screen.Faq.route) {
            FaqScreen(onBack = { navController.popBackStack() })
        }

        composable(Screen.AppearanceSettings.route) {
            AppearanceSettingsScreen(
                onBack = { navController.popBackStack() },
                onThemeClick = { navController.navigate(Screen.ThemeSettings.route) },
                onLanguageClick = { navController.navigate(Screen.LanguageSettings.route) },
                onTimelineFilterClick = { navController.navigate(Screen.TimelineFilter.route) },
            )
        }

        composable(Screen.LandingTab.route) {
            LandingTabScreen(onBack = { navController.popBackStack() })
        }

        composable(Screen.ThemeSettings.route) {
            eu.akoos.photos.presentation.settings.ThemeSettingsScreen(
                onBack = { navController.popBackStack() },
            )
        }

        composable(Screen.LanguageSettings.route) {
            LanguageSettingsScreen(
                onBack = { navController.popBackStack() },
            )
        }

        composable(Screen.SyncSettings.route) {
            SyncSettingsScreen(
                onBack                = { navController.popBackStack() },
                onBackupContentClick  = { navController.navigate(Screen.BackupContent.route) },
                onBackupBehaviorClick = { navController.navigate(Screen.BackupBehavior.route) },
                onNetworkClick        = { navController.navigate(Screen.BackupNetwork.route) },
            )
        }

        composable(Screen.MetadataSettings.route) {
            eu.akoos.photos.presentation.settings.MetadataSettingsScreen(
                onBack         = { navController.popBackStack() },
                onOpenFileName = { navController.navigate(Screen.UploadFileName.route) },
                onOpenMetadata = { navController.navigate(Screen.UploadMetadata.route) },
                onOpenQuality  = { navController.navigate(Screen.UploadQuality.route) },
            )
        }

        composable(Screen.UploadFileName.route) {
            eu.akoos.photos.presentation.settings.UploadFileNameSettingsScreen(
                onBack = { navController.popBackStack() },
            )
        }

        composable(Screen.UploadMetadata.route) {
            eu.akoos.photos.presentation.settings.UploadMetadataSettingsScreen(
                onBack = { navController.popBackStack() },
            )
        }

        composable(Screen.UploadQuality.route) {
            eu.akoos.photos.presentation.settings.UploadQualitySettingsScreen(
                onBack = { navController.popBackStack() },
            )
        }

        composable(
            route = "activity?tab={tab}",
            arguments = listOf(navArgument("tab") { type = NavType.StringType; defaultValue = "uploads" }),
        ) { backStackEntry ->
            val tab = when (backStackEntry.arguments?.getString("tab")) {
                "downloads" -> eu.akoos.photos.presentation.settings.ActivityTab.Downloads
                else -> eu.akoos.photos.presentation.settings.ActivityTab.Uploads
            }
            eu.akoos.photos.presentation.settings.ActivityScreen(
                onBack = { navController.popBackStack() },
                initialTab = tab,
            )
        }

        composable(Screen.BackupContent.route) {
            eu.akoos.photos.presentation.settings.BackupContentSettingsScreen(
                onBack                    = { navController.popBackStack() },
                onBackupFoldersClick      = { navController.navigate(Screen.SyncFolders.route) },
                onExcludedFoldersClick    = { navController.navigate(Screen.ExcludedFolders.route) },
            )
        }

        composable(Screen.BackupBehavior.route) {
            eu.akoos.photos.presentation.settings.BackupBehaviorSettingsScreen(
                onBack                  = { navController.popBackStack() },
                onUploadProcessingClick = { navController.navigate(Screen.MetadataSettings.route) },
            )
        }

        composable(Screen.BackupNetwork.route) {
            eu.akoos.photos.presentation.settings.BackupNetworkSettingsScreen(
                onBack = { navController.popBackStack() },
            )
        }

        composable(Screen.StorageSettings.route) {
            eu.akoos.photos.presentation.settings.StorageSettingsScreen(
                onBack       = { navController.popBackStack() },
                onOpenTrash  = { cloud -> navController.navigate("trash?tab=" + if (cloud) "cloud" else "device") },
                onFreeUpSpace = { navController.navigate(Screen.FreeUpSpace.route) },
            )
        }

        composable(Screen.FreeUpSpace.route) {
            eu.akoos.photos.presentation.settings.FreeUpSpaceScreen(
                onBack = { navController.popBackStack() },
            )
        }

        composable(Screen.PrivacySettings.route) {
            PrivacySettingsScreen(
                onBack = { navController.popBackStack() },
                onOfflinePhotosClick = { navController.navigate(Screen.Offline.route) },
            )
        }

        composable(Screen.PrivacySecuritySettings.route) {
            eu.akoos.photos.presentation.settings.PrivacySecuritySettingsScreen(
                onBack = { navController.popBackStack() },
                onPrivacyClick = { navController.navigate(Screen.PrivacySettings.route) },
                onSecurityClick = { navController.navigate(Screen.SecuritySettings.route) },
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
                onOpenAlbum = { album ->
                    selectedAlbum = album
                    navController.navigate(Screen.AlbumDetail.route)
                },
                onOpenFolder = { bucketName ->
                    // The same route the Albums grid opens a folder with. A hidden folder card is
                    // still a folder, so it opens on its own screen rather than a reduced view — on
                    // the vault's side of it, which is what this card counts and covers.
                    selectedDeviceFolder = bucketName
                    deviceFolderOpenAction = null
                    deviceFolderFromVault = true
                    navController.navigate(Screen.DeviceFolderDetail.route)
                },
                onPhotoClick = { items, index ->
                    // Pass the entire hidden list to the viewer so the user can swipe between
                    // hidden photos like in the main gallery. Each item is wrapped as a
                    // LocalOnly GalleryItem because hidden photos live as file:// URIs in
                    // app-private storage, not as MediaStore content URIs.
                    selectedViewerItems = items.map { eu.akoos.photos.domain.entity.GalleryItem.LocalOnly(it) }
                    selectedViewerIndex = index
                    selectedViewerHiddenLinkIds = emptySet()
                    viewerFromAlbum = false
                    viewerSecure = true
                    navController.navigate(Screen.Viewer.route)
                },
                onCloudPhotoClick = { items, index ->
                    // Revealed cloud photos are already CloudOnly GalleryItems. Open the secure
                    // viewer over the whole hidden-cloud list so the user can swipe between them,
                    // matching the device group's FLAG_SECURE viewer.
                    selectedViewerItems = items
                    selectedViewerIndex = index
                    selectedViewerHiddenLinkIds = emptySet()
                    viewerFromAlbum = false
                    viewerSecure = true
                    navController.navigate(Screen.Viewer.route)
                },
            )
        }

        composable(Screen.Offline.route) {
            OfflinePhotosScreen(
                onBack = { navController.popBackStack() },
                onPhotoClick = { items, index ->
                    // Pinned items are already cloud GalleryItems — open the normal (non-secure)
                    // viewer over the whole offline list so the user can swipe between them.
                    selectedViewerItems = items
                    selectedViewerIndex = index
                    selectedViewerHiddenLinkIds = emptySet()
                    viewerFromAlbum = false
                    viewerSecure = false
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
                    openAction = deviceFolderOpenAction,
                    onOpenActionConsumed = { deviceFolderOpenAction = null },
                    fromVault = deviceFolderFromVault,
                    onPhotoClick = { items, index ->
                        selectedViewerItems = items
                        selectedViewerIndex = index
                        selectedViewerHiddenLinkIds = emptySet()
                        viewerFromAlbum = false
                        // A folder reached from the Hidden area lists vaulted photos, so its viewer
                        // gets the same FLAG_SECURE window the vault grid's own viewer gets. The flag
                        // has to be set on both branches: it survives across navigations, so leaving
                        // it alone would hand a hidden photo whichever value the last viewer used.
                        viewerSecure = deviceFolderFromVault
                        navController.navigate(Screen.Viewer.route)
                    },
                    onEditMetadata = { selection ->
                        // A device folder holds the user's own files, never an album someone shared
                        // with them, so the editor's own per-item rules decide what is writable.
                        metadataEditorRequest = MetadataEditorRequest(selection, isReadOnlyAlbum = false)
                        navController.navigate(Screen.MetadataEditor.route)
                    },
                    onSlideshowClick = { folderItems ->
                        // Same viewer a photo tap opens, on the first photo of the folder's current
                        // order with the slideshow already running. viewerFromAlbum stays false: the
                        // photos are the user's own device files, not an album's members.
                        selectedViewerItems = folderItems
                        selectedViewerIndex = 0
                        selectedViewerHiddenLinkIds = emptySet()
                        viewerFromAlbum = false
                        viewerAutoplay = true
                        viewerSecure = deviceFolderFromVault
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
            TimelineFilterScreen(
                onBack = { navController.popBackStack() },
                onOpenLayout = { navController.navigate(Screen.TimelineLayout.route) },
                onOpenCategories = { navController.navigate(Screen.TimelineCategories.route) },
                onOpenAlbums = { navController.navigate(Screen.TimelineAlbums.route) },
                onOpenDeviceFolders = { navController.navigate(Screen.TimelineDeviceFolders.route) },
                onOpenLandingTab = { navController.navigate(Screen.LandingTab.route) },
            )
        }

        composable(Screen.TimelineLayout.route) {
            TimelineLayoutScreen(onBack = { navController.popBackStack() })
        }

        composable(Screen.TimelineCategories.route) {
            TimelineCategoriesScreen(onBack = { navController.popBackStack() })
        }

        composable(Screen.TimelineAlbums.route) {
            TimelineAlbumsScreen(onBack = { navController.popBackStack() })
        }

        composable(Screen.TimelineDeviceFolders.route) {
            TimelineDeviceFoldersScreen(onBack = { navController.popBackStack() })
        }

        composable(Screen.NotificationSettings.route) {
            NotificationSettingsScreen(onBack = { navController.popBackStack() })
        }

        composable(
            route = "trash?tab={tab}",
            arguments = listOf(navArgument("tab") { type = NavType.StringType; defaultValue = "device" }),
        ) { backStackEntry ->
            val tab = backStackEntry.arguments?.getString("tab")
            TrashScreen(
                onBack = { navController.popBackStack() },
                initialTab = if (tab == "cloud")
                    eu.akoos.photos.presentation.settings.TrashTab.Cloud
                else
                    eu.akoos.photos.presentation.settings.TrashTab.Device,
            )
        }

        composable(Screen.DuplicateFinder.route) {
            eu.akoos.photos.presentation.duplicates.DuplicateFinderScreen(
                onBack = { navController.popBackStack() },
                onOpenViewer = { items, index ->
                    // Open the normal (non-secure) viewer over the tapped group so the user can
                    // compare copies full-screen and swipe between them.
                    selectedViewerItems = items
                    selectedViewerIndex = index
                    selectedViewerHiddenLinkIds = emptySet()
                    viewerFromAlbum = false
                    viewerSecure = false
                    navController.navigate(Screen.Viewer.route)
                },
            )
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
                onOpenMap = { navController.navigate(Screen.Map.route) },
                onOpenCalendar = {
                    navController.navigate(Screen.Calendar.route) {
                        popUpTo(Screen.Search.route) { inclusive = true }
                        launchSingleTop = true
                    }
                },
                onOpenOffline = { navController.navigate(Screen.Offline.route) },
                onEditMetadata = { selection ->
                    // Search runs over the user's own library, never an album someone shared with
                    // them, so the editor's own per-item rules decide what is writable.
                    metadataEditorRequest = MetadataEditorRequest(selection, isReadOnlyAlbum = false)
                    navController.navigate(Screen.MetadataEditor.route)
                },
            )
        }

        composable(Screen.Map.route) {
            MapScreen(
                onBack = { navController.popBackStack() },
                onPhotoClick = { items, i ->
                    selectedViewerItems = items
                    selectedViewerIndex = i
                    selectedViewerHiddenLinkIds = emptySet()
                    viewerFromAlbum = false
                    navController.navigate(Screen.Viewer.route)
                },
                onOpenCalendar = {
                    navController.navigate(Screen.Calendar.route) {
                        popUpTo(Screen.Map.route) { inclusive = true }
                        launchSingleTop = true
                    }
                },
                onOpenSearch = {
                    navController.navigate(Screen.Search.route) {
                        popUpTo(Screen.Map.route) { inclusive = true }
                        launchSingleTop = true
                    }
                },
            )
        }

    }
    }

        // One app-wide undo bar: any screen's reversible action shows here, over whatever route is
        // on top, and a route change consumes it so it never lingers or re-appears elsewhere.
        UndoSnackbarHost(
            navController = navController,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 24.dp),
        )
    }
}

/**
 * The single snackbar host for [UndoController]. Renders the pending undoable action for a ~5s
 * self-dismissing window; tapping Undo runs the restore, and navigating away (or the timeout)
 * clears it. Lives at the nav-graph root so a delete from the viewer or an album is still undoable
 * after that screen closes, and the bar is never bound to one screen's lifecycle.
 */
@Composable
private fun UndoSnackbarHost(
    navController: NavController,
    modifier: Modifier = Modifier,
    undoBarViewModel: UndoBarViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val pending by undoBarViewModel.pending.collectAsStateWithLifecycle()
    val hostState = remember { SnackbarHostState() }

    // A destination change consumes the pending action so a stale bar cannot reappear on another
    // screen when the user comes back.
    DisposableEffect(navController) {
        val listener = NavController.OnDestinationChangedListener { _, _, _ -> undoBarViewModel.dismiss() }
        navController.addOnDestinationChangedListener(listener)
        onDispose { navController.removeOnDestinationChangedListener(listener) }
    }

    LaunchedEffect(pending) {
        val action = pending ?: return@LaunchedEffect
        val message = when (action) {
            is UndoAction.Hide -> context.resources.getQuantityString(
                R.plurals.gallery_hidden_snackbar, action.count, action.count)
            is UndoAction.Delete -> context.resources.getQuantityString(
                R.plurals.gallery_moved_to_trash_snackbar, action.count, action.count)
            is UndoAction.AlbumRemove -> context.resources.getQuantityString(
                R.plurals.album_removed_undo_snackbar, action.count, action.count)
        }
        val result = withTimeoutOrNull(5000L) {
            hostState.showSnackbar(
                message = message,
                actionLabel = context.getString(R.string.gallery_undo),
                duration = SnackbarDuration.Indefinite,
            )
        }
        if (result == SnackbarResult.ActionPerformed) undoBarViewModel.undo() else undoBarViewModel.dismiss()
    }

    // The restore runs after the bar is gone, so a photo that could not come back has no other way
    // to be reported. Collected here rather than per screen: the undo outlives the screen that
    // raised it, and this host is the one surface that is always up.
    LaunchedEffect(Unit) {
        undoBarViewModel.undoFailed.collect { failed ->
            if (failed <= 0) return@collect
            hostState.showSnackbar(
                context.resources.getQuantityString(R.plurals.hidden_restore_failed_some, failed, failed),
            )
        }
    }

    ThemedSnackbarHost(hostState, modifier = modifier)
}
