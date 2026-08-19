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

@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package eu.akoos.photos.presentation.people

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.akoos.photos.R
import eu.akoos.photos.presentation.gallery.PersonTile
import eu.akoos.photos.presentation.gallery.PersonUi
import eu.akoos.photos.presentation.theme.AppColors

/**
 * A tall bottom drawer for choosing a person by name, shared by BOTH merge entry points (a single
 * person's "merge with", and the review screen's bulk merge) so they look and behave the same. It
 * carries a search field that filters the named people and, when the typed text matches nobody, offers
 * to create a new person with that name; the people fill a scrollable grid of face tiles. [onPick]
 * returns the chosen name, existing or new.
 */
@Composable
fun PersonPickerSheet(
    title: String,
    people: List<PersonUi>,
    onPick: (PersonUi) -> Unit,
    onDismiss: () -> Unit,
    onCreateNew: ((String) -> Unit)? = null,
    searchPlaceholder: String? = null,
) {
    val colors = AppColors.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var query by remember { mutableStateOf("") }
    // Pure-search callers (no create) pass their own hint so the field does not read "or type a new
    // name" when there is nothing to create; merge callers keep the default.
    val placeholderText = searchPlaceholder ?: stringResource(R.string.person_picker_search)

    val q = query.trim()
    val filtered = remember(people, query) {
        if (q.isEmpty()) people
        else people.filter { it.displayName?.contains(q, ignoreCase = true) == true }
    }
    val exactExists = people.any { it.displayName.equals(q, ignoreCase = true) }

    ModalBottomSheet(
        sheetState = sheetState,
        onDismissRequest = onDismiss,
        containerColor = colors.bg2,
        scrimColor = Color.Black.copy(alpha = 0.5f),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.92f)
                .navigationBarsPadding()
                .padding(horizontal = 20.dp)
                .padding(top = 4.dp, bottom = 16.dp),
        ) {
            Text(
                title,
                color = colors.fgPrimary,
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(bottom = 12.dp),
            )
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text(placeholderText, color = colors.fgMute) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = colors.accent,
                    unfocusedBorderColor = colors.line2,
                    focusedTextColor = colors.fgPrimary,
                    unfocusedTextColor = colors.fgPrimary,
                    cursorColor = colors.accent,
                ),
            )
            Spacer(Modifier.height(12.dp))

            if (onCreateNew != null && q.isNotEmpty() && !exactExists) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { onCreateNew(q) }
                        .padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier.size(40.dp).clip(CircleShape).background(colors.surfaceWeak),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Default.PersonAdd, contentDescription = null, tint = colors.accent)
                    }
                    Text(
                        stringResource(R.string.person_picker_create, q),
                        color = colors.fgPrimary,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(start = 12.dp),
                    )
                }
                Spacer(Modifier.height(4.dp))
            }

            if (filtered.isEmpty() && q.isEmpty()) {
                Text(
                    stringResource(R.string.person_picker_empty),
                    color = colors.fgMute,
                    fontSize = 14.sp,
                )
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(72.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxWidth().weight(1f),
                ) {
                    items(items = filtered, key = { it.personId }) { person ->
                        PersonTile(
                            person = person,
                            selected = false,
                            onClick = { onPick(person) },
                        )
                    }
                }
            }
        }
    }
}
