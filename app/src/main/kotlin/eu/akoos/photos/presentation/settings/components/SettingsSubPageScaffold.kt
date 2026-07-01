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

package eu.akoos.photos.presentation.settings.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.akoos.photos.R
import eu.akoos.photos.presentation.common.FloatingHeaderScrim
import eu.akoos.photos.presentation.common.IconBubble
import eu.akoos.photos.presentation.common.floatingHeaderContentTopPadding
import eu.akoos.photos.presentation.theme.AppColors

private val pillShape = RoundedCornerShape(20.dp)

/**
 * Shared scaffold for every Settings sub-page (Account, Appearance, Language, Sync,
 * Storage, Privacy, App lock, Permissions, …). Draws the same floating pill header the app's other
 * content pages use (see [SettingsPillHeader]) with the content scrolling UNDER it.
 *
 * Use this for sub-pages whose body is a simple scrolling column. Pages that need their own
 * LazyColumn / grid overlay [SettingsPillHeader] directly and reserve [floatingHeaderContentTopPadding]
 * as their content's top padding.
 *
 * Centralising the header here keeps the look byte-identical across every leaf Settings screen.
 */
@Composable
internal fun SettingsSubPageScaffold(
    title: String,
    onBack: () -> Unit,
    content: @Composable () -> Unit,
) {
    val colors = AppColors.current
    Box(modifier = Modifier.fillMaxSize().background(colors.pageBg)) {
        val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        val contentTopPad = floatingHeaderContentTopPadding()

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
        ) {
            Spacer(Modifier.height(contentTopPad))
            content()
            Spacer(Modifier.height(32.dp + navBottom))
        }

        SettingsPillHeader(title = title, onBack = onBack)
    }
}

/**
 * The standard Settings header: a circular back button pinned left and the page title centered in a
 * pill. Drawn over the page's scrolling content so the body slides underneath. Optional [trailing]
 * controls (e.g. a select-all) sit at the end, replacing the balance spacer that otherwise keeps the
 * title optically centered. Reserve [floatingHeaderContentTopPadding] as the content's top padding.
 */
@Composable
internal fun SettingsPillHeader(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    val colors = AppColors.current
    val debouncedBack = rememberDebouncedAction { onBack() }
    Box(modifier = modifier.fillMaxWidth()) {
        FloatingHeaderScrim()
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(start = 12.dp, end = 12.dp, top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconBubble(
                icon = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = stringResource(R.string.onboarding_back),
                onClick = debouncedBack,
                diameter = 40.dp,
                iconSize = 16.dp,
                background = colors.surfaceWeak,
                borderColor = colors.pillBorder,
                tint = colors.fgDim,
            )
            Spacer(Modifier.weight(1f))
            Box(
                modifier = Modifier
                    .clip(pillShape)
                    .background(colors.surfaceWeak, pillShape)
                    .border(0.5.dp, colors.pillBorder, pillShape)
                    .padding(horizontal = 16.dp, vertical = 9.dp),
            ) {
                Text(title, color = colors.fgPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.weight(1f))
            if (trailing != null) trailing() else Spacer(Modifier.size(40.dp))
        }
    }
}
