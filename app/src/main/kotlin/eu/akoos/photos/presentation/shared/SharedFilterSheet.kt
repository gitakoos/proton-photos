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

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.akoos.photos.R
import eu.akoos.photos.presentation.common.ActionSheetRow
import eu.akoos.photos.presentation.common.ActionSheetSectionHeading
import eu.akoos.photos.presentation.gallery.FilterChip
import eu.akoos.photos.presentation.gallery.SharedFilter
import eu.akoos.photos.presentation.theme.FgPrimary
import eu.akoos.photos.presentation.theme.SheetBg

/**
 * The Shared page's filter as a drawer, built from the same pieces as the app's other drawers: the
 * scope switch as two chips across the top, then, for what others shared, the senders as action rows.
 * A scope pick keeps the drawer open so a sender can be chosen next; a sender pick closes it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SharedFilterSheet(
    scope: SharedFilter,
    onScopeChange: (SharedFilter) -> Unit,
    people: List<String>,
    activeEmail: String?,
    onEmailChange: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = SheetBg,
        scrimColor = Color.Black.copy(alpha = 0.5f),
    ) {
        // Height-capped + scroll so a long list of senders never pushes the switch off screen.
        val maxSheetHeight = (LocalConfiguration.current.screenHeightDp * 0.8f).dp
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = maxSheetHeight)
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
        ) {
            Text(
                stringResource(R.string.filter_title),
                color = FgPrimary,
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(bottom = 16.dp),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                listOf(
                    SharedFilter.SharedWithMe to R.string.share_scope_with_me,
                    SharedFilter.SharedByMe to R.string.share_scope_by_me,
                ).forEach { (option, label) ->
                    FilterChip(
                        label = stringResource(label),
                        selected = option == scope,
                        onClick = { if (option != scope) onScopeChange(option) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            // Senders only exist for what others shared; "by me" has no one to pick.
            if (scope == SharedFilter.SharedWithMe && people.isNotEmpty()) {
                ActionSheetSectionHeading(stringResource(R.string.share_filter_by_person))
                ActionSheetRow(
                    icon = Icons.Default.Person,
                    title = stringResource(R.string.share_all),
                    onClick = { onEmailChange(null) },
                    showCheck = activeEmail == null,
                )
                people.forEach { email ->
                    Spacer(Modifier.height(8.dp))
                    ActionSheetRow(
                        icon = Icons.Default.Person,
                        title = email,
                        onClick = { onEmailChange(email) },
                        showCheck = activeEmail == email,
                        titleMaxLines = 1,
                    )
                }
            }
        }
    }
}
