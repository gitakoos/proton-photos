package me.proton.photos.navigation

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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import me.proton.photos.presentation.theme.AppColors
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
import me.proton.photos.domain.entity.Album
import me.proton.photos.domain.entity.CloudPhoto
import me.proton.photos.domain.entity.GalleryItem
import me.proton.photos.domain.entity.LocalAlbum
import me.proton.photos.presentation.albums.LocalAlbumDetailScreen
import androidx.compose.runtime.mutableIntStateOf
import me.proton.photos.presentation.albums.AlbumDetailScreen
import me.proton.photos.presentation.auth.SignInScreen
import me.proton.photos.presentation.editor.PhotoEditorScreen
import me.proton.photos.presentation.gallery.GalleryScreen
import me.proton.photos.presentation.hidden.HiddenAlbumScreen
import me.proton.photos.presentation.settings.AboutScreen
import me.proton.photos.presentation.settings.AppearanceSettingsScreen
import me.proton.photos.presentation.settings.LanguageSettingsScreen
import me.proton.photos.presentation.settings.PrivacySettingsScreen
import me.proton.photos.presentation.settings.SecuritySettingsScreen
import me.proton.photos.presentation.settings.SettingsScreen
import me.proton.photos.presentation.settings.SyncFoldersScreen
import me.proton.photos.presentation.settings.SyncSettingsScreen
import me.proton.photos.presentation.settings.TrashScreen
import me.proton.photos.presentation.viewer.PhotoViewerScreen
import javax.inject.Inject

sealed class Screen(val route: String) {
    data object Gallery : Screen("gallery")
    data object Settings : Screen("settings")
    data object SyncSettings : Screen("sync_settings")
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
    var selectedAlbum by remember { mutableStateOf<Album?>(null) }
    var selectedLocalAlbum by remember { mutableStateOf<LocalAlbum?>(null) }
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
                onPhotoClick = { items, index ->
                    selectedViewerItems = items
                    selectedViewerIndex = index
                    viewerFromAlbum = false
                    navController.navigate(Screen.Viewer.route)
                },
                onAlbumClick = { album ->
                    selectedAlbum = album
                    navController.navigate(Screen.AlbumDetail.route)
                },
                onLocalAlbumClick = { album ->
                    selectedLocalAlbum = album
                    navController.navigate(Screen.LocalAlbumDetail.route)
                },
                onSettingsClick = { navController.navigate(Screen.Settings.route) },
                onHiddenAlbumClick = { navController.navigate(Screen.HiddenAlbum.route) },
            )
        }

        composable(Screen.Viewer.route) {
            // When the viewer was opened from an album, propagate the album linkId to the
            // viewer + editor so edited/renamed copies land back in the same album.
            val sourceAlbumLinkId = if (viewerFromAlbum) selectedAlbum?.linkId else null
            PhotoViewerScreen(
                items = selectedViewerItems,
                initialIndex = selectedViewerIndex,
                onBack = { navController.popBackStack() },
                showSaveToDevice = !viewerFromAlbum,
                sourceAlbumLinkId = sourceAlbumLinkId,
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
                is GalleryItem.LocalOnly -> PhotoEditorScreen(
                    localUri         = item.local.uri,
                    localDisplayName = item.local.displayName,
                    localMimeType    = item.local.mimeType,
                    cloudPhoto       = null,
                    sourceAlbumLinkId = sourceAlbumLinkId,
                    onBack           = { navController.popBackStack() },
                    onSaved          = { navController.popBackStack() },
                )
                is GalleryItem.Synced -> PhotoEditorScreen(
                    localUri         = item.local.uri,
                    localDisplayName = item.local.displayName,
                    localMimeType    = item.local.mimeType,
                    cloudPhoto       = null,
                    sourceAlbumLinkId = sourceAlbumLinkId,
                    onBack           = { navController.popBackStack() },
                    onSaved          = { navController.popBackStack() },
                )
                is GalleryItem.CloudOnly -> PhotoEditorScreen(
                    localUri         = null,
                    localDisplayName = null,
                    localMimeType    = null,
                    cloudPhoto       = item.cloud,
                    sourceAlbumLinkId = sourceAlbumLinkId,
                    onBack           = { navController.popBackStack() },
                    onSaved          = { navController.popBackStack() },
                )
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
                    selectedViewerItems = items.map { me.proton.photos.domain.entity.GalleryItem.LocalOnly(it) }
                    selectedViewerIndex = index
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
    }
}
