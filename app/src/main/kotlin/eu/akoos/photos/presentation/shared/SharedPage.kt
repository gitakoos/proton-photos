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
 */

package eu.akoos.photos.presentation.shared

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import eu.akoos.photos.R
import eu.akoos.photos.domain.entity.Album
import eu.akoos.photos.presentation.common.FloatingHeader
import eu.akoos.photos.presentation.common.IconBubble
import eu.akoos.photos.presentation.common.floatingHeaderContentTopPadding
import eu.akoos.photos.presentation.gallery.SharedFilter
import eu.akoos.photos.presentation.theme.AppColors

/**
 * What others shared with the user and what the user shared, as a page of its own with a back arrow,
 * opened from the Shared tile on the Albums tab. The filter button in the header opens a drawer with
 * the scope switch and, for what others shared, the senders.
 */
@Composable
fun SharedPage(
    onBack: () -> Unit,
    onAlbumClick: (Album) -> Unit,
    viewModel: SharedViewModel = hiltViewModel(),
) {
    val colors = AppColors.current
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    // Saved, so coming back from a shared album returns to the scope and the sender it showed.
    var filter by rememberSaveable { mutableStateOf(SharedFilter.SharedWithMe) }
    var activeEmailFilter by rememberSaveable { mutableStateOf<String?>(null) }
    var showFilterSheet by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxSize().background(colors.pageBg)) {
        Box(modifier = Modifier.fillMaxSize().padding(top = floatingHeaderContentTopPadding())) {
            SharedScreen(
                filter = filter,
                activeEmailFilter = activeEmailFilter,
                onAlbumClick = onAlbumClick,
                viewModel = viewModel,
            )
        }
        // The same floating header every other page wears: back button left, title centred, and the
        // filter at the right end.
        FloatingHeader(
            title = stringResource(R.string.gallery_tab_shared),
            onBack = onBack,
            modifier = Modifier.align(Alignment.TopCenter),
            trailing = {
                IconBubble(
                    icon = Icons.Default.FilterList,
                    contentDescription = stringResource(R.string.share_filter_account),
                    onClick = { showFilterSheet = true },
                    background = if (activeEmailFilter != null) colors.accent.copy(alpha = 0.15f) else colors.pillBg,
                    borderColor = if (activeEmailFilter != null) colors.accent else colors.pillBorder,
                    tint = if (activeEmailFilter != null) colors.accent else colors.fgPrimary,
                )
            },
        )
    }

    if (showFilterSheet) {
        SharedFilterSheet(
            scope = filter,
            onScopeChange = { picked ->
                filter = picked
                activeEmailFilter = null
            },
            people = state.availableEmails,
            activeEmail = activeEmailFilter,
            onEmailChange = { picked ->
                activeEmailFilter = picked
                showFilterSheet = false
            },
            onDismiss = { showFilterSheet = false },
        )
    }
}
