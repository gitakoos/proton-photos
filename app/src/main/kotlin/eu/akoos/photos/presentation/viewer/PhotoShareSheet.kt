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

package eu.akoos.photos.presentation.viewer

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.akoos.photos.R
import eu.akoos.photos.presentation.theme.Accent
import eu.akoos.photos.presentation.theme.Bg2
import eu.akoos.photos.presentation.theme.FgMute
import eu.akoos.photos.presentation.theme.FgPrimary
import eu.akoos.photos.presentation.theme.PillBg
import eu.akoos.photos.presentation.theme.PillBorder

/**
 * Single-photo share drawer: Send to another app (OS sheet), Share with people (→ shared album),
 * and Public link (→ the dedicated manage-link sheet).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PhotoShareSheet(
    sheetState: SheetState,
    /** False for a not-yet-backed-up local photo — the "Public link" row then shows a short
     *  "back it up first" note instead of opening the manage-link sheet. */
    canCreateLink: Boolean,
    onDismiss: () -> Unit,
    onSendToApp: () -> Unit,
    onShareWithPeople: () -> Unit,
    onManagePublicLink: () -> Unit,
    /** Whether the "Public link" row is offered at all — hidden for a multi-photo or local-only
     *  selection. The viewer leaves this true and relies on [canCreateLink] for the local note. */
    showPublicLink: Boolean = true,
    /** Whether the "Share with people" row is shown — hidden where adding to a shared album doesn't
     *  apply, e.g. a selection already inside an album. */
    showShareWithPeople: Boolean = true,
    /** When true, the Public link row stays tappable for a not-yet-backed-up local photo so it can
     *  offer "upload first, then create the link" rather than only showing the back-up note. */
    localUploadEnabled: Boolean = false,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Bg2,
        scrimColor = Color.Black.copy(alpha = 0.5f),
    ) {
        // Height-capped + scroll so a short device never clips the bottom row.
        val maxSheetHeight = (LocalConfiguration.current.screenHeightDp * 0.7f).dp
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = maxSheetHeight)
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 8.dp),
        ) {
            Text(
                stringResource(R.string.share_viewer_sheet_title),
                color = FgPrimary,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(bottom = 16.dp),
            )

            ShareActionRow(
                icon = Icons.Default.Share,
                title = stringResource(R.string.share_send_to_app),
                onClick = onSendToApp,
            )
            if (showShareWithPeople) {
                Spacer(Modifier.height(8.dp))
                ShareActionRow(
                    icon = Icons.Default.PersonAdd,
                    title = stringResource(R.string.share_with_people),
                    subtitle = stringResource(R.string.share_with_people_desc),
                    onClick = onShareWithPeople,
                )
            }
            if (showPublicLink) {
                Spacer(Modifier.height(8.dp))
                ShareActionRow(
                    icon = Icons.Default.Link,
                    title = stringResource(R.string.share_public_link),
                    subtitle = if (canCreateLink) {
                        stringResource(R.string.share_public_link_desc)
                    } else {
                        stringResource(R.string.share_link_local_only_note)
                    },
                    onClick = onManagePublicLink,
                    enabled = canCreateLink || localUploadEnabled,
                    showChevron = canCreateLink || localUploadEnabled,
                )
            }

            Spacer(Modifier.height(16.dp))
        }
    }
}

/** Full-width tappable row — leading icon, title, optional subtitle, optional trailing
 *  chevron. Mirrors the pill rows used across the app's other ModalBottomSheets. */
@Composable
private fun ShareActionRow(
    icon: ImageVector,
    title: String,
    onClick: () -> Unit,
    subtitle: String? = null,
    enabled: Boolean = true,
    showChevron: Boolean = false,
) {
    val alpha = if (enabled) 1f else 0.5f
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(PillBg, RoundedCornerShape(12.dp))
            .border(0.5.dp, PillBorder, RoundedCornerShape(12.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Icon(icon, null, tint = Accent.copy(alpha = alpha), modifier = Modifier.size(22.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = FgPrimary.copy(alpha = alpha), fontSize = 15.sp, fontWeight = FontWeight.Medium)
            if (subtitle != null) {
                Text(subtitle, color = FgMute.copy(alpha = alpha), fontSize = 12.sp)
            }
        }
        if (showChevron) {
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight, null,
                tint = FgMute, modifier = Modifier.size(20.dp),
            )
        }
    }
}
