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

package eu.akoos.photos.presentation.whatsnew

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import eu.akoos.photos.presentation.common.floatingHeaderContentTopPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.edit
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.akoos.photos.BuildConfig
import eu.akoos.photos.R
import eu.akoos.photos.data.preferences.SettingsKeys
import eu.akoos.photos.data.preferences.settingsDataStore
import eu.akoos.photos.presentation.memories.FloatingMemoriesHeader
import eu.akoos.photos.presentation.theme.AppColors
import eu.akoos.photos.presentation.theme.PillBg
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Persists the one-time "What's new" dismissal. [markSeen] writes the current versionCode to
 * [SettingsKeys.WHATS_NEW_SEEN_VERSION] so the screen can never reappear for this version. The
 * screen calls it on every exit (Got-it, a feature Open button, or back), so whichever path the
 * user takes out of the screen settles the gate.
 */
@HiltViewModel
class WhatsNewViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
) : ViewModel() {
    fun markSeen() {
        viewModelScope.launch {
            context.settingsDataStore.edit {
                it[SettingsKeys.WHATS_NEW_SEEN_VERSION] = BuildConfig.VERSION_CODE
            }
        }
    }
}

/**
 * One-time post-update highlights screen. Shown once after an upgrade (see the gate in NavGraph)
 * on top of the Gallery: a floating-pill header, a scrollable list of feature cards each with an
 * optional jump-to-feature action, and a primary dismiss button. Any exit marks the version seen
 * via [WhatsNewViewModel.markSeen] so it does not return until the next release.
 */
@Composable
fun WhatsNewScreen(
    onDone: () -> Unit,
    onOpenDuplicates: () -> Unit,
    onOpenOffline: () -> Unit,
    viewModel: WhatsNewViewModel = hiltViewModel(),
) {
    val colors = AppColors.current

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.pageBg),
    ) {
        // Reserve room for the floating header: the status-bar inset + the pill-row height, so the
        // first card sits clear of the pills and the rest scrolls under them.
        val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        val contentTopPad = floatingHeaderContentTopPadding()

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp, contentTopPad, 16.dp, 24.dp + navBottom),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item("c-dup") {
                WhatsNewCard(
                    icon = Icons.Default.ContentCopy,
                    title = stringResource(R.string.whats_new_dup_title),
                    body = stringResource(R.string.whats_new_dup_body),
                    onOpen = {
                        viewModel.markSeen()
                        onOpenDuplicates()
                    },
                )
            }
            item("c-offline") {
                WhatsNewCard(
                    icon = Icons.Default.CloudDownload,
                    title = stringResource(R.string.whats_new_offline_title),
                    body = stringResource(R.string.whats_new_offline_body),
                    onOpen = {
                        viewModel.markSeen()
                        onOpenOffline()
                    },
                )
            }
            item("c-activity") {
                WhatsNewCard(
                    icon = Icons.Default.SwapVert,
                    title = stringResource(R.string.whats_new_activity_title),
                    body = stringResource(R.string.whats_new_activity_body),
                )
            }
            item("c-motion") {
                WhatsNewCard(
                    icon = Icons.Default.PlayCircle,
                    title = stringResource(R.string.whats_new_motion_title),
                    body = stringResource(R.string.whats_new_motion_body),
                )
            }
            item("more") {
                Text(
                    stringResource(R.string.whats_new_more),
                    color = colors.fgMute, fontSize = 13.sp,
                    modifier = Modifier.padding(top = 4.dp, start = 4.dp, end = 4.dp),
                )
            }
            item("done") {
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = {
                        viewModel.markSeen()
                        onDone()
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = colors.accent,
                        contentColor = colors.activeChipText,
                    ),
                ) {
                    Text(
                        stringResource(R.string.whats_new_done),
                        fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(vertical = 4.dp),
                    )
                }
            }
        }

        // Floating pill header (matches the other secondary screens): title pill + a back button
        // that dismisses exactly like Got-it. Back marks the version seen too.
        FloatingMemoriesHeader(
            title = stringResource(R.string.whats_new_title),
            onBack = {
                viewModel.markSeen()
                onDone()
            },
        )
    }
}

/**
 * One feature highlight: a leading icon chip, a bold title, a short description, and an optional
 * "Open" text button that jumps straight to the feature.
 */
@Composable
private fun WhatsNewCard(
    icon: ImageVector,
    title: String,
    body: String,
    onOpen: (() -> Unit)? = null,
) {
    val colors = AppColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(PillBg)
            .padding(14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(colors.accent.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, null, tint = colors.accent, modifier = Modifier.size(22.dp))
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(title, color = colors.fgPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Text(body, color = colors.fgDim, fontSize = 13.sp)
            if (onOpen != null) {
                TextButton(
                    onClick = onOpen,
                    contentPadding = PaddingValues(horizontal = 0.dp, vertical = 6.dp),
                ) {
                    Text(
                        stringResource(R.string.whats_new_open),
                        color = colors.accent, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                    )
                }
            }
        }
    }
}
