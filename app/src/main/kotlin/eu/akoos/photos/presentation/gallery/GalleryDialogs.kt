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

package eu.akoos.photos.presentation.gallery

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.akoos.photos.R
import eu.akoos.photos.domain.entity.Album
import eu.akoos.photos.domain.entity.GalleryItem
import eu.akoos.photos.presentation.theme.Accent
import eu.akoos.photos.presentation.theme.AppColors
import eu.akoos.photos.presentation.theme.Bg2
import eu.akoos.photos.presentation.theme.FgMute
import eu.akoos.photos.presentation.theme.FgPrimary
import eu.akoos.photos.presentation.theme.Line2
import eu.akoos.photos.presentation.theme.PillBg

// ── Content filter bottom sheet ───────────────────────────────────────────────
//
// Hosts the [ContentFilterSheet] inside a ModalBottomSheet. The mount gate
// (`if (showFilterSheet)`) stays at the call-site so the open/dismiss flow
// remains visible there.

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun GalleryContentFilterDialog(
    currentFilter: ContentFilter,
    currentCategory: GalleryFilter,
    sheetState: SheetState,
    onApply: (ContentFilter) -> Unit,
    onCategorySelected: (GalleryFilter) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Bg2,
        scrimColor = Color.Black.copy(alpha = 0.5f),
    ) {
        ContentFilterSheet(
            currentFilter = currentFilter,
            currentCategory = currentCategory,
            onApply = onApply,
            onCategorySelected = onCategorySelected,
            onDismiss = onDismiss,
        )
    }
}

// ── Shared email filter bottom sheet ──────────────────────────────────────────
//
// Picker for the Shared tab's "Filter by person" affordance. The caller owns
// the [activeEmailFilter] state — `onEmailSelected(null)` resets to "All".

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun GallerySharedEmailFilterDialog(
    availableEmails: List<String>,
    activeEmailFilter: String?,
    onEmailSelected: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Bg2,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 8.dp),
        ) {
            Text(
                stringResource(R.string.share_filter_by_person), color = FgPrimary,
                fontSize = 18.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(bottom = 12.dp),
            )
            if (availableEmails.isEmpty()) {
                Text(
                    stringResource(R.string.share_no_shared), color = FgMute, fontSize = 14.sp,
                    modifier = Modifier.padding(vertical = 16.dp),
                )
            } else {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            if (activeEmailFilter == null) Accent.copy(0.15f) else PillBg,
                            RoundedCornerShape(10.dp),
                        )
                        .clickable { onEmailSelected(null) }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        stringResource(R.string.share_all),
                        color = if (activeEmailFilter == null) Accent else FgPrimary,
                        fontSize = 15.sp, fontWeight = FontWeight.Medium,
                    )
                    if (activeEmailFilter == null) {
                        Icon(Icons.Default.Check, null, tint = Accent, modifier = Modifier.size(16.dp))
                    }
                }
                Spacer(Modifier.height(4.dp))
                availableEmails.forEach { email ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                if (activeEmailFilter == email) Accent.copy(0.15f) else PillBg,
                                RoundedCornerShape(10.dp),
                            )
                            .clickable { onEmailSelected(email) }
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            email,
                            color = if (activeEmailFilter == email) Accent else FgPrimary,
                            fontSize = 15.sp,
                        )
                        if (activeEmailFilter == email) {
                            Icon(Icons.Default.Check, null, tint = Accent, modifier = Modifier.size(16.dp))
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

// ── Multi-select delete bottom sheet ──────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun GalleryMultiDeleteDialog(
    selectedItems: Set<GalleryItem>,
    onDismiss: () -> Unit,
    onDelete: (freeUpSpace: Boolean, deleteFromCloud: Boolean) -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Bg2,
    ) {
        MultiDeleteSheet(
            selectedItems = selectedItems,
            onDismiss = onDismiss,
            onDelete = onDelete,
        )
    }
}

// ── Add-to-album picker bottom sheet ──────────────────────────────────────────
//
// Hosts [GalleryAddToAlbumPickerSheet] inside a ModalBottomSheet. The picker
// itself stays in GalleryScreen.kt — this wrapper only owns the sheet chrome.

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun GalleryAddToAlbumDialog(
    selectedItems: Set<GalleryItem>,
    cloudAlbums: List<Album>,
    sheetState: SheetState,
    onCreateNew: () -> Unit,
    onCloudAlbumSelected: (Album) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Bg2,
        scrimColor = Color.Black.copy(alpha = 0.5f),
    ) {
        // Photos join an album as references on Drive; only cloud-backed items in the
        // selection carry a linkId, so the picker offers cloud albums + a New album row.
        val anyCloudBacked = selectedItems.any {
            it is GalleryItem.Synced || it is GalleryItem.CloudOnly
        }
        GalleryAddToAlbumPickerSheet(
            cloudAlbums = cloudAlbums,
            selectionHasCloud = anyCloudBacked,
            onCreateNew = onCreateNew,
            onCloudAlbumSelected = onCloudAlbumSelected,
            onDismiss = onDismiss,
        )
    }
}

// ── New-album inline create bottom sheet ──────────────────────────────────────
//
// Launched from the picker's "+ New album" row. Owns the text-field state
// internally; calls back with the trimmed-but-not-empty name on commit.

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun GalleryNewAlbumDialog(
    onDismiss: () -> Unit,
    onCreate: (String) -> Unit,
) {
    var newAlbumName by remember { mutableStateOf("") }
    val appColors = AppColors.current
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = appColors.cardBg,
        scrimColor = Color.Black.copy(alpha = 0.5f),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 24.dp)
                .padding(bottom = 36.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                stringResource(R.string.albums_new_album), color = appColors.fgPrimary,
                fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
            )
            OutlinedTextField(
                value = newAlbumName,
                onValueChange = { newAlbumName = it },
                placeholder = { Text(stringResource(R.string.albums_create_album_hint), color = FgMute) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor   = Accent,
                    unfocusedBorderColor = Line2,
                    focusedTextColor     = appColors.fgPrimary,
                    unfocusedTextColor   = appColors.fgPrimary,
                    cursorColor          = Accent,
                ),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = {
                    if (newAlbumName.trim().isNotEmpty()) {
                        onCreate(newAlbumName)
                    }
                }),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.cancel), color = appColors.fgDim)
                }
                TextButton(
                    enabled = newAlbumName.trim().isNotEmpty(),
                    onClick = { onCreate(newAlbumName) },
                ) {
                    Text(
                        stringResource(R.string.albums_create_album), color = Accent,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
    }
}
