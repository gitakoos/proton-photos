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
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import me.proton.core.accountmanager.domain.AccountManager
import eu.akoos.photos.domain.entity.Album
import eu.akoos.photos.domain.entity.CloudPhoto
import eu.akoos.photos.domain.entity.GalleryItem
import eu.akoos.photos.domain.entity.LocalAlbum
import eu.akoos.photos.presentation.albums.LocalAlbumDetailScreen
import androidx.compose.runtime.mutableIntStateOf
import eu.akoos.photos.presentation.albums.AlbumDetailScreen
import eu.akoos.photos.presentation.auth.SignInScreen
import eu.akoos.photos.presentation.editor.PhotoEditorScreen
import eu.akoos.photos.presentation.editor.VideoEditorScreen
import eu.akoos.photos.presentation.gallery.GalleryScreen
import eu.akoos.photos.presentation.hidden.HiddenAlbumScreen
import eu.akoos.photos.presentation.settings.AboutScreen
import eu.akoos.photos.presentation.settings.AppearanceSettingsScreen
import eu.akoos.photos.presentation.settings.LanguageSettingsScreen
import eu.akoos.photos.presentation.settings.PrivacySettingsScreen
import eu.akoos.photos.presentation.settings.SecuritySettingsScreen
import eu.akoos.photos.presentation.settings.SettingsScreen
import eu.akoos.photos.presentation.settings.SyncFoldersScreen
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
    data object LocalAlbumDetail : Screen("local_album_detail")
    data object SyncFolders : Screen("sync_folders")
    data object Trash : Screen("trash")
    data object HiddenAlbum : Screen("hidden_album")
    data object PhotoEditor : Screen("photo_editor")
    data object Loading : Screen("loading")
    data object Login : Screen("login")
    data object About : Screen("about")
    data object AppearanceSettings : Screen("appearance_settings")
    data object LanguageSettings : Screen("language_settings")
    data object Search : Screen("search")
}

@HiltViewModel
class NavViewModel @Inject constructor(
    accountManager: AccountManager,
) : ViewModel() {
    val isLoggedIn: StateFlow<Boolean?> = accountManager.getPrimaryUserId()
        .map { it != null }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)
}

@Composable
fun NavGraph(
    onStartLogin: () -> Unit = {},
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
    var selectedLocalAlbum by remember { mutableStateOf<LocalAlbum?>(null) }
    // When the user taps a Merged album card (a local bucket whose name matches a Drive
    // album), this holds the matching cloud album's linkId so LocalAlbumDetailScreen can
    // include CloudOnly photos from the Drive album alongside the local items. Cleared
    // whenever the user opens a non-merged album so a stale linkId doesn't leak across.
    var mergedCloudAlbumLinkId by remember { mutableStateOf<String?>(null) }
    // True when the viewer was opened from an album detail (not from the main gallery).
    // Suppresses the per-photo "Save to device" button — the album has its own "Download all".
    var viewerFromAlbum by remember { mutableStateOf(false) }

    // Selected item handed to the editor. Carries local URI + display name OR a CloudPhoto.
    var editorItem by remember { mutableStateOf<GalleryItem?>(null) }

    val isLoggedIn = navViewModel.isLoggedIn

    LaunchedEffect(Unit) {
        isLoggedIn.collect { loggedIn ->
            if (loggedIn == null) return@collect
            if (loggedIn) {
                navController.navigate(Screen.Gallery.route) {
                    popUpTo(Screen.Loading.route) { inclusive = true }
                }
            } else {
                navController.navigate(Screen.Login.route) {
                    popUpTo(Screen.Loading.route) { inclusive = true }
                }
            }
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

        composable(Screen.Gallery.route) {
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
                    mergedCloudAlbumLinkId = null
                    navController.navigate(Screen.AlbumDetail.route)
                },
                onLocalAlbumClick = { album ->
                    selectedLocalAlbum = album
                    mergedCloudAlbumLinkId = null
                    navController.navigate(Screen.LocalAlbumDetail.route)
                },
                onMergedAlbumClick = { local, cloud ->
                    // Merged albums (local bucket + Drive album with matching name) route
                    // through the local-album detail screen because it already speaks
                    // GalleryItem.LocalOnly/Synced/CloudOnly natively. The cloud linkId is
                    // passed alongside so the screen merges in the cloud-only entries too.
                    selectedLocalAlbum = local
                    selectedAlbum = cloud
                    mergedCloudAlbumLinkId = cloud.linkId
                    navController.navigate(Screen.LocalAlbumDetail.route)
                },
                onSettingsClick = { navController.navigate(Screen.Settings.route) },
                onHiddenAlbumClick = { navController.navigate(Screen.HiddenAlbum.route) },
                onSearchClick = { navController.navigate(Screen.Search.route) },
            )
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
                editedAt = editedAt,
                hiddenCloudLinkIds = selectedViewerHiddenLinkIds,
                onEditItem = { item ->
                    editorItem = item
                    navController.navigate(Screen.PhotoEditor.route)
                },
            )
        }

        composable(Screen.PhotoEditor.route) {
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

        composable(Screen.LocalAlbumDetail.route) {
            val album = selectedLocalAlbum
            if (album != null) {
                LocalAlbumDetailScreen(
                    localAlbum = album,
                    onPhotoClick = { items, index ->
                        selectedViewerItems = items
                        selectedViewerIndex = index
                        selectedViewerHiddenLinkIds = emptySet()
                        navController.navigate(Screen.Viewer.route)
                    },
                    onBack = { navController.popBackStack() },
                    cloudAlbumLinkId = mergedCloudAlbumLinkId,
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
            )
        }

        composable(Screen.About.route) {
            AboutScreen(onBack = { navController.popBackStack() })
        }

        composable(Screen.AppearanceSettings.route) {
            AppearanceSettingsScreen(onBack = { navController.popBackStack() })
        }

        composable(Screen.LanguageSettings.route) {
            LanguageSettingsScreen(onBack = { navController.popBackStack() })
        }

        composable(Screen.SyncSettings.route) {
            SyncSettingsScreen(
                onBack               = { navController.popBackStack() },
                onBackupFoldersClick = { navController.navigate(Screen.SyncFolders.route) },
                onRecentlyDeletedClick = { navController.navigate(Screen.Trash.route) },
            )
        }

        composable(Screen.StorageSettings.route) {
            eu.akoos.photos.presentation.settings.StorageSettingsScreen(
                onBack                 = { navController.popBackStack() },
                onRecentlyDeletedClick = { navController.navigate(Screen.Trash.route) },
            )
        }

        composable(Screen.PrivacySettings.route) {
            PrivacySettingsScreen(onBack = { navController.popBackStack() })
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
