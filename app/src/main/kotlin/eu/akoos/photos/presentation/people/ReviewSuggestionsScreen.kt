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

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CallMerge
import androidx.compose.material.icons.filled.PersonOff
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import eu.akoos.photos.R
import eu.akoos.photos.presentation.common.ConfirmDialog
import eu.akoos.photos.presentation.common.IconBubble
import eu.akoos.photos.presentation.common.ShimmerBox
import eu.akoos.photos.presentation.common.floatingHeaderContentTopPadding
import eu.akoos.photos.presentation.gallery.PersonCard
import eu.akoos.photos.presentation.common.FloatingHeader
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
    // Null until the first cluster query returns, so loading is told apart from a genuinely empty set.
    val loading = clusters == null
    val resolvedClusters = clusters.orEmpty()
    val namedPeople by viewModel.namedPeople.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val context = LocalContext.current
    LaunchedEffect(message) {
        message?.let {
            Toast.makeText(context, context.getString(it), Toast.LENGTH_SHORT).show()
            viewModel.clearMessage()
        }
    }
    var selection by remember { mutableStateOf(emptySet<Long>()) }
    var showMerge by remember { mutableStateOf(false) }
    var pendingBulkMerge by remember { mutableStateOf<Pair<Set<Long>, String>?>(null) }
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
        // Loading / empty / content phase, cross-faded so the empty message never flashes before the
        // first cluster query returns.
        val phase = when {
            loading -> 0
            resolvedClusters.isEmpty() -> 1
            else -> 2
        }
        Crossfade(targetState = phase, label = "reviewContent", modifier = Modifier.fillMaxSize()) { p ->
            when (p) {
                0 ->
                    // Skeleton grid matching the 2-column cluster layout so there is no jump when the
                    // real cards arrive.
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
                        items(8) {
                            ShimmerBox(
                                modifier = Modifier.fillMaxWidth().aspectRatio(132f / 168f),
                                cornerRadius = 16.dp,
                            )
                        }
                    }
                1 ->
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            text = stringResource(R.string.people_review_empty),
                            color = colors.fgMute,
                            fontSize = 14.sp,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 40.dp),
                        )
                    }
                else ->
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
                        items(items = resolvedClusters, key = { it.personId }) { person ->
                            val card: @Composable () -> Unit = {
                                PersonCard(
                                    person = person,
                                    selected = person.personId in selection,
                                    // The Unsorted bucket is opened to curate its faces one by one, never bulk-merged
                                    // or bulk-rejected as a whole, so it always opens and cannot join a selection.
                                    onClick = {
                                        if (selecting && !person.isOther) toggle(person.personId)
                                        else onOpenCluster(person.personId)
                                    },
                                    onLongClick = if (person.isOther) null else { { toggle(person.personId) } },
                                )
                            }
                            // Only the Unsorted bucket carries a caption, so it reads as the leftover pile to sort
                            // rather than a person the app recognised.
                            if (person.isOther) {
                                Column {
                                    card()
                                    Text(
                                        text = stringResource(R.string.person_unsorted_caption),
                                        color = colors.fgDim,
                                        fontSize = 11.sp,
                                        lineHeight = 15.sp,
                                        modifier = Modifier.padding(top = 6.dp, start = 2.dp, end = 2.dp),
                                    )
                                }
                            } else {
                                card()
                            }
                        }
                    }
            }
        }

        if (selecting) {
            FloatingHeader(
                title = stringResource(R.string.album_picker_selected, selection.size),
                onBack = { selection = emptySet() },
                trailing = {
                    IconBubble(
                        icon = Icons.Default.CallMerge,
                        contentDescription = stringResource(R.string.person_merge_action),
                        onClick = { showMerge = true },
                        diameter = 40.dp,
                        iconSize = 18.dp,
                        background = colors.pillBg,
                        borderColor = colors.pillBorder,
                        tint = colors.fgPrimary,
                    )
                    IconBubble(
                        icon = Icons.Default.PersonOff,
                        contentDescription = stringResource(R.string.person_not_a_person),
                        onClick = { showNotPerson = true },
                        diameter = 40.dp,
                        iconSize = 18.dp,
                        background = colors.pillBg,
                        borderColor = colors.pillBorder,
                        tint = colors.fgPrimary,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                },
            )
        } else {
            FloatingHeader(
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
            // Folding these clusters into an existing named person cannot be undone automatically, so
            // confirm first. Naming a brand-new person is reversible by renaming, so it commits at once.
            onPick = { person ->
                person.displayName?.let { pendingBulkMerge = sel to it }
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

    pendingBulkMerge?.let { (ids, name) ->
        ConfirmDialog(
            title = stringResource(R.string.person_merge_confirm_title),
            message = stringResource(R.string.person_merge_confirm_body),
            confirmLabel = stringResource(R.string.person_merge_confirm),
            dismissLabel = stringResource(R.string.cancel),
            onConfirm = {
                viewModel.bulkMerge(ids, name)
                selection = emptySet()
                pendingBulkMerge = null
            },
            onDismiss = { pendingBulkMerge = null },
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
