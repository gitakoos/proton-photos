package eu.akoos.photos.presentation.hidden

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
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.ui.res.stringResource
import eu.akoos.photos.R
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import eu.akoos.photos.domain.entity.LocalMediaItem
import eu.akoos.photos.presentation.theme.Accent
import eu.akoos.photos.presentation.theme.Bg0
import eu.akoos.photos.presentation.theme.Bg2
import eu.akoos.photos.presentation.theme.ErrorColor
import eu.akoos.photos.presentation.theme.FgDim
import eu.akoos.photos.presentation.theme.FgMute
import eu.akoos.photos.presentation.theme.FgPrimary
import eu.akoos.photos.presentation.theme.PillBg
import eu.akoos.photos.presentation.theme.PillBorder

@Composable
fun HiddenAlbumScreen(
    onBack: () -> Unit,
    onPhotoClick: (items: List<LocalMediaItem>, index: Int) -> Unit = { _, _ -> },
    viewModel: HiddenAlbumViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(state.error) {
        state.error?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearError()
        }
    }

    // Trigger biometric on first composition
    LaunchedEffect(Unit) {
        if (!state.isAuthenticated) {
            showBiometricPrompt(
                activity = context as FragmentActivity,
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
                Spacer(Modifier.height(32.dp))
                Box(
                    modifier = Modifier
                        .background(Accent.copy(alpha = 0.12f), RoundedCornerShape(14.dp))
                        .border(0.5.dp, Accent.copy(alpha = 0.3f), RoundedCornerShape(14.dp))
                        .clickable {
                            showBiometricPrompt(
                                activity = context as FragmentActivity,
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
            // Content — authenticated
            Column(modifier = Modifier.fillMaxSize()) {
                // Header
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .background(PillBg, CircleShape)
                            .border(0.5.dp, PillBorder, CircleShape)
                            .clickable(onClick = onBack),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack, "Back",
                            tint = FgPrimary, modifier = Modifier.size(20.dp),
                        )
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "Hidden Photos",
                            color = FgPrimary, fontSize = 20.sp, fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            "${state.items.size} photo${if (state.items.size != 1) "s" else ""}",
                            color = FgMute, fontSize = 12.sp,
                        )
                    }
                    Icon(
                        Icons.Default.VisibilityOff, null,
                        tint = FgDim, modifier = Modifier.size(20.dp),
                    )
                }

                when {
                    state.isLoading -> LazyVerticalGrid(
                        columns = GridCells.Fixed(3),
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(2.dp),
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        items(15) {
                            eu.akoos.photos.presentation.common.ShimmerSquare(
                                modifier = Modifier.fillMaxWidth(),
                                cornerRadius = 4.dp,
                            )
                        }
                    }
                    state.items.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                Icons.Default.VisibilityOff, null,
                                tint = FgMute, modifier = Modifier.size(40.dp),
                            )
                            Spacer(Modifier.height(16.dp))
                            Text("No hidden photos", color = FgPrimary, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "Long-press a photo in the gallery and choose \"Hide\"",
                                color = FgDim, fontSize = 14.sp,
                            )
                        }
                    }
                    else -> LazyVerticalGrid(
                        columns = GridCells.Fixed(3),
                        contentPadding = PaddingValues(8.dp, 4.dp, 8.dp, 100.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        itemsIndexed(state.items, key = { _, item -> item.uri }) { index, item ->
                            HiddenPhotoCell(
                                item = item,
                                hasCloudCounterpart = item.uri in state.backedUpUris,
                                onClick = { onPhotoClick(state.items, index) },
                                onUnhide = { viewModel.unhidePhoto(item.uri) },
                            )
                        }
                    }
                }
            }
        }

        SnackbarHost(
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
    onClick: () -> Unit,
    onUnhide: () -> Unit,
) {
    var showOptions by remember { mutableStateOf(false) }
    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(8.dp))
            .background(Bg2)
            .clickable { onClick() },
    ) {
        AsyncImage(
            model = android.net.Uri.parse(item.uri),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
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
                    contentDescription = "Backed up",
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
        onSuccess()
        return
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
    prompt.authenticate(
        BiometricPrompt.PromptInfo.Builder()
            .setTitle("Hidden Photos")
            .setDescription("Authenticate to view your hidden photos")
            .setAllowedAuthenticators(
                BiometricManager.Authenticators.BIOMETRIC_STRONG or
                    BiometricManager.Authenticators.DEVICE_CREDENTIAL,
            )
            .build(),
    )
}
