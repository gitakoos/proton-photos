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

package eu.akoos.photos.presentation.hidden

import android.app.KeyguardManager
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
import androidx.compose.material3.Icon
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.ui.res.stringResource
import eu.akoos.photos.R
import eu.akoos.photos.presentation.common.IconBubble
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
import eu.akoos.photos.domain.entity.LocalMediaItem
import eu.akoos.photos.presentation.theme.Accent
import eu.akoos.photos.presentation.theme.Bg0
import eu.akoos.photos.presentation.theme.Bg2
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

    // Re-lock whenever the screen leaves the foreground. The authenticated flag lives in
    // the ViewModel (so list state survives configuration changes), which means without
    // this it would also survive backgrounding the app or navigating away — leaving the
    // vault unlocked. Resetting on ON_STOP forces a fresh biometric/credential check on
    // the next return, and clears promptShown so the entry prompt re-arms.
    // ON_STOP also fires during a configuration change (rotation recreates the
    // activity) — skip those, otherwise every rotation forces a re-auth.
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_STOP) {
                val changingConfig = context.findFragmentActivity()?.isChangingConfigurations == true
                if (!changingConfig) {
                    viewModel.lock()
                    promptShown = false
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
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
                    IconBubble(
                        icon = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.onboarding_back),
                        onClick = onBack,
                        diameter = 40.dp,
                        iconSize = 20.dp,
                        background = PillBg,
                        borderColor = PillBorder,
                        tint = FgPrimary,
                    )
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
            .setTitle(activity.getString(R.string.hidden_auth_title))
            .setDescription(activity.getString(R.string.hidden_auth_description))
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
