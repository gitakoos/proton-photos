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

package eu.akoos.photos.presentation.people

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CallMerge
import androidx.compose.material.icons.filled.PersonOff
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import eu.akoos.photos.R
import eu.akoos.photos.presentation.common.ConfirmDialog
import eu.akoos.photos.presentation.common.IconBubble
import eu.akoos.photos.presentation.common.floatingHeaderContentTopPadding
import eu.akoos.photos.presentation.gallery.PersonCard
import eu.akoos.photos.presentation.settings.components.SettingsPillHeader
import eu.akoos.photos.presentation.theme.AppColors

/**
 * Review screen: every cluster the app found but the user has not named, as a grid. Tap one to open
 * and name it; long-press to start a multi-selection, then MERGE the selected clusters into one named
 * person (folds a face split across a few clusters back together) or mark them NOT A PERSON (drops
 * statues, posters and false detections for good).
 */
@Composable
fun ReviewSuggestionsScreen(
    onBack: () -> Unit,
    onOpenCluster: (Long) -> Unit,
    viewModel: ReviewSuggestionsViewModel = hiltViewModel(),
) {
    val colors = AppColors.current
    val clusters by viewModel.clusters.collectAsStateWithLifecycle()
    val namedPeople by viewModel.namedPeople.collectAsStateWithLifecycle()
    var selection by remember { mutableStateOf(emptySet<Long>()) }
    var showMerge by remember { mutableStateOf(false) }
    var showNotPerson by remember { mutableStateOf(false) }
    val selecting = selection.isNotEmpty()

    fun toggle(id: Long) {
        selection = if (id in selection) selection - id else selection + id
    }

    BackHandler(enabled = selecting) { selection = emptySet() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.pageBg),
    ) {
        if (clusters.isEmpty()) {
            Text(
                text = stringResource(R.string.people_review_empty),
                color = colors.fgMute,
                fontSize = 14.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.align(Alignment.Center).padding(horizontal = 40.dp),
            )
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                contentPadding = PaddingValues(
                    start = 14.dp,
                    end = 14.dp,
                    top = floatingHeaderContentTopPadding(),
                    bottom = 24.dp,
                ),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(items = clusters, key = { it.personId }) { person ->
                    PersonCard(
                        person = person,
                        selected = person.personId in selection,
                        onClick = {
                            if (selecting) toggle(person.personId) else onOpenCluster(person.personId)
                        },
                        onLongClick = { toggle(person.personId) },
                    )
                }
            }
        }

        if (selecting) {
            SettingsPillHeader(
                title = stringResource(R.string.album_picker_selected, selection.size),
                onBack = { selection = emptySet() },
                trailing = {
                    IconBubble(
                        icon = Icons.Default.CallMerge,
                        contentDescription = stringResource(R.string.person_merge_action),
                        onClick = { showMerge = true },
                        diameter = 40.dp,
                        iconSize = 18.dp,
                        background = colors.surfaceWeak,
                        borderColor = colors.pillBorder,
                        tint = colors.fgPrimary,
                    )
                    IconBubble(
                        icon = Icons.Default.PersonOff,
                        contentDescription = stringResource(R.string.person_not_a_person),
                        onClick = { showNotPerson = true },
                        diameter = 40.dp,
                        iconSize = 18.dp,
                        background = colors.surfaceWeak,
                        borderColor = colors.pillBorder,
                        tint = colors.fgDim,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                },
            )
        } else {
            SettingsPillHeader(
                title = stringResource(R.string.people_review_title),
                onBack = onBack,
            )
        }
    }

    if (showMerge) {
        val sel = selection
        PersonPickerSheet(
            title = stringResource(R.string.person_merge_name_title),
            people = namedPeople,
            onPick = { person ->
                person.displayName?.let { viewModel.bulkMerge(sel, it) }
                selection = emptySet()
                showMerge = false
            },
            onCreateNew = { name ->
                viewModel.bulkMerge(sel, name)
                selection = emptySet()
                showMerge = false
            },
            onDismiss = { showMerge = false },
        )
    }

    if (showNotPerson) {
        ConfirmDialog(
            title = stringResource(R.string.person_not_a_person_title),
            message = stringResource(R.string.person_not_a_person_body),
            confirmLabel = stringResource(R.string.person_not_a_person_confirm),
            dismissLabel = stringResource(R.string.cancel),
            onConfirm = {
                viewModel.bulkNotPerson(selection)
                selection = emptySet()
                showNotPerson = false
            },
            onDismiss = { showNotPerson = false },
            destructive = true,
        )
    }
}
