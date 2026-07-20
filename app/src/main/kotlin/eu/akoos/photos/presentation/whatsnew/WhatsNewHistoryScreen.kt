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

package eu.akoos.photos.presentation.whatsnew

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import eu.akoos.photos.R
import eu.akoos.photos.presentation.common.floatingHeaderContentTopPadding
import eu.akoos.photos.presentation.memories.FloatingMemoriesHeader
import eu.akoos.photos.presentation.settings.components.NavRow
import eu.akoos.photos.presentation.settings.components.RowDivider
import eu.akoos.photos.presentation.settings.components.SettingsCard
import eu.akoos.photos.presentation.theme.AppColors

/**
 * The update history: one row per release the app has highlights for, newest first, each opening
 * that release's [WhatsNewScreen]. Reached from Settings, so the post-update screen is no longer a
 * one-shot the user can lose by tapping past it.
 *
 * The list is [WhatsNewReleases] verbatim, so it grows by adding a catalog entry and nothing here.
 * Releases older than the one the screen was introduced in are simply absent rather than faked.
 */
@Composable
fun WhatsNewHistoryScreen(
    onBack: () -> Unit,
    onOpenRelease: (String) -> Unit,
) {
    val colors = AppColors.current
    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.pageBg),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {
            Spacer(Modifier.height(floatingHeaderContentTopPadding()))
            SettingsCard {
                WhatsNewReleases.forEachIndexed { index, release ->
                    if (index > 0) RowDivider()
                    NavRow(
                        label = stringResource(R.string.whats_new_version, release.version),
                        description = stringResource(release.headlineRes),
                        onClick = { onOpenRelease(release.version) },
                    )
                }
            }
            Spacer(Modifier.height(navBottom + 24.dp))
        }

        FloatingMemoriesHeader(
            title = stringResource(R.string.whats_new_title),
            onBack = onBack,
        )
    }
}
