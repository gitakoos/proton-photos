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

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.HowToReg
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
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
import eu.akoos.photos.domain.usecase.isNameableCluster
import eu.akoos.photos.presentation.common.IconBubble
import eu.akoos.photos.presentation.common.SecondaryButton
import eu.akoos.photos.presentation.common.ShimmerBox
import eu.akoos.photos.presentation.common.ShimmerTextLine
import eu.akoos.photos.presentation.common.floatingHeaderContentTopPadding
import eu.akoos.photos.presentation.albums.AlbumRowCoverSize
import eu.akoos.photos.presentation.albums.AlbumRowGap
import eu.akoos.photos.presentation.albums.AlbumRowPadding
import eu.akoos.photos.presentation.common.glassSource
import eu.akoos.photos.presentation.common.rememberGlassBackdrop
import eu.akoos.photos.presentation.gallery.AlbumsViewMode
import eu.akoos.photos.presentation.gallery.PersonCard
import eu.akoos.photos.presentation.gallery.PersonRow
import eu.akoos.photos.presentation.common.FloatingHeader
import eu.akoos.photos.presentation.theme.AppColors

/**
 * Full-screen list of every clustered person who appears in enough photos, most-photographed first,
 * as a grid of the same album-style [PersonCard] the Collection preview uses, or as a list of
 * [PersonRow]s, laid out by [rememberPeopleDisplay]. Reached from the People header in the Collection;
 * a tap opens that person's detail. The floating pill header carries a back button and a three-dot
 * overflow menu with the page's display settings and the AI settings, so the on-device people features
 * stay reachable from where the people are shown. The one-off detections the preview hides are
 * filtered here too, so the two surfaces list the same people.
 */
@Composable
fun PeopleScreen(
    onBack: () -> Unit,
    onPersonClick: (Long) -> Unit,
    onOpenAiSettings: () -> Unit,
    onReviewSuggestions: () -> Unit,
    onOpenExcluded: () -> Unit,
    onOpenDisplaySettings: () -> Unit,
    viewModel: PeopleViewModel = hiltViewModel(),
) {
    val colors = AppColors.current
    val display = rememberPeopleDisplay()
    val listView = display.viewMode == AlbumsViewMode.List
    // A list row spans the width and pads itself, as an Albums list row does; grid cards sit on margins.
    val contentPadding = if (listView) {
        PaddingValues(top = floatingHeaderContentTopPadding(), bottom = 24.dp)
    } else {
        PaddingValues(start = 14.dp, end = 14.dp, top = floatingHeaderContentTopPadding(), bottom = 24.dp)
    }
    val columns = GridCells.Fixed(if (listView) 1 else display.columns)
    val rowSpacing = if (listView) 0.dp else 10.dp
    val people by viewModel.people.collectAsStateWithLifecycle()
    // A null value is the pre-first-emission load; once resolved it is a (possibly empty) list.
    val loading = people == null
    val resolved = people.orEmpty()
    // The People grid shows only NAMED people, so it stays a clean list of who has been named. Every
    // unnamed cluster is curated and named on the review screen instead (reached from the overflow menu
    // or, when nothing is named yet, the empty state), which keeps naming in one place and off this grid.
    val named = resolved.filter { !it.displayName.isNullOrBlank() }
    val shown = named
    // Unnamed clusters still waiting for a name, surfaced as a count on the review entry. Gated on the
    // exact same rule as the grid cards above, so the badge matches the number of unnamed cards the user
    // actually sees instead of counting held-back one-off detections or the Unsorted pile.
    val suggestionCount = resolved.count { isNameableCluster(it.displayName, it.isOther, it.faceCount) }
    var menuOpen by remember { mutableStateOf(false) }
    var showSearch by remember { mutableStateOf(false) }

    // The page as its floating header frosts it, when the frosted pills are on.
    val glass = rememberGlassBackdrop(colors.pageBg)
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.pageBg),
    ) {
        // Loading / empty / content phase, cross-faded so the first Room emission never flashes the
        // empty state before the named people arrive.
        val phase = when {
            loading -> 0
            shown.isEmpty() -> 1
            else -> 2
        }
        Box(Modifier.fillMaxSize().glassSource(glass)) {
            Crossfade(targetState = phase, label = "peopleContent", modifier = Modifier.fillMaxSize()) { p ->
                when (p) {
                    0 ->
                        // Skeleton in the same layout as the real cards or rows, so there is no jump when
                        // they arrive.
                        LazyVerticalGrid(
                            columns = columns,
                            contentPadding = contentPadding,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalArrangement = Arrangement.spacedBy(rowSpacing),
                            modifier = Modifier.fillMaxSize(),
                        ) {
                            items(8) {
                                if (listView) {
                                    PersonRowShimmer()
                                } else {
                                    ShimmerBox(
                                        modifier = Modifier.fillMaxWidth().aspectRatio(132f / 168f),
                                        cornerRadius = 16.dp,
                                    )
                                }
                            }
                        }
                    1 ->
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Column(
                                modifier = Modifier.padding(horizontal = 40.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                Text(
                                    text = stringResource(R.string.people_none_named),
                                    color = colors.fgMute,
                                    fontSize = 14.sp,
                                    textAlign = TextAlign.Center,
                                )
                                Spacer(Modifier.height(14.dp))
                                // Surface the count so a guest with unnamed clusters sees groups exist
                                // and can tap through to name them, instead of a bare empty message.
                                SecondaryButton(
                                    label = if (suggestionCount > 0) {
                                        stringResource(R.string.person_suggestions_review_count, suggestionCount)
                                    } else {
                                        stringResource(R.string.person_suggestions_review)
                                    },
                                    onClick = onReviewSuggestions,
                                )
                            }
                        }
                    else ->
                        LazyVerticalGrid(
                            columns = columns,
                            contentPadding = contentPadding,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalArrangement = Arrangement.spacedBy(rowSpacing),
                            modifier = Modifier.fillMaxSize(),
                        ) {
                            items(items = shown, key = { it.personId }) { person ->
                                if (listView) {
                                    PersonRow(person = person, onClick = { onPersonClick(person.personId) })
                                } else {
                                    PersonCard(person = person, onClick = { onPersonClick(person.personId) })
                                }
                            }
                        }
                }
            }
        }

        // Floating pill header (back + title), matching the app's other detail surfaces, with a
        // three-dot overflow that jumps straight to the AI settings.
        FloatingHeader(
            backdrop = glass,
            title = stringResource(R.string.gallery_category_people),
            onBack = onBack,
            trailing = {
                // Search the named people with the same drawer the merge picker uses.
                if (named.isNotEmpty()) {
                    IconBubble(
                        icon = Icons.Default.Search,
                        contentDescription = stringResource(R.string.people_search_hint),
                        onClick = { showSearch = true },
                        diameter = 40.dp,
                        iconSize = 18.dp,
                        background = colors.pillBg,
                        borderColor = colors.pillBorder,
                        tint = colors.fgPrimary,
                        modifier = Modifier.padding(end = 8.dp),
                    )
                }
                Box {
                    IconBubble(
                        icon = Icons.Default.MoreVert,
                        contentDescription = stringResource(R.string.more_options),
                        onClick = { menuOpen = true },
                        diameter = 40.dp,
                        iconSize = 16.dp,
                        background = colors.pillBg,
                        borderColor = colors.pillBorder,
                        tint = colors.fgPrimary,
                    )
                    DropdownMenu(
                        expanded = menuOpen,
                        onDismissRequest = { menuOpen = false },
                        shape = RoundedCornerShape(16.dp),
                        containerColor = colors.cardBg,
                        border = BorderStroke(0.5.dp, colors.pillBorder),
                    ) {
                        DropdownMenuItem(
                            text = {
                                Text(
                                    if (suggestionCount > 0)
                                        stringResource(
                                            R.string.person_suggestions_review_count,
                                            suggestionCount,
                                        )
                                    else stringResource(R.string.person_suggestions_review),
                                    color = colors.fgPrimary,
                                )
                            },
                            leadingIcon = {
                                Icon(
                                    Icons.Default.HowToReg,
                                    contentDescription = null,
                                    tint = colors.fgDim,
                                )
                            },
                            onClick = {
                                menuOpen = false
                                onReviewSuggestions()
                            },
                        )
                        DropdownMenuItem(
                            text = {
                                Text(
                                    stringResource(R.string.settings_face_excluded),
                                    color = colors.fgPrimary,
                                )
                            },
                            leadingIcon = {
                                Icon(
                                    Icons.Default.Block,
                                    contentDescription = null,
                                    tint = colors.fgDim,
                                )
                            },
                            onClick = {
                                menuOpen = false
                                onOpenExcluded()
                            },
                        )
                        DropdownMenuItem(
                            text = {
                                Text(
                                    stringResource(R.string.people_display_settings),
                                    color = colors.fgPrimary,
                                )
                            },
                            leadingIcon = {
                                Icon(
                                    Icons.Default.GridView,
                                    contentDescription = null,
                                    tint = colors.fgDim,
                                )
                            },
                            onClick = {
                                menuOpen = false
                                onOpenDisplaySettings()
                            },
                        )
                        DropdownMenuItem(
                            text = {
                                Text(
                                    stringResource(R.string.settings_ai_section),
                                    color = colors.fgPrimary,
                                )
                            },
                            leadingIcon = {
                                Icon(
                                    Icons.Default.AutoAwesome,
                                    contentDescription = null,
                                    tint = colors.fgDim,
                                )
                            },
                            onClick = {
                                menuOpen = false
                                onOpenAiSettings()
                            },
                        )
                    }
                }
            },
        )

        if (showSearch) {
            PersonPickerSheet(
                title = stringResource(R.string.gallery_category_people),
                people = named,
                onPick = { showSearch = false; onPersonClick(it.personId) },
                onDismiss = { showSearch = false },
                searchPlaceholder = stringResource(R.string.people_search_hint),
            )
        }
    }
}

/** Loading placeholder for [PersonRow], on the same insets so the rows land where it stood. */
@Composable
private fun PersonRowShimmer() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(AlbumRowPadding),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AlbumRowGap),
    ) {
        ShimmerBox(modifier = Modifier.size(AlbumRowCoverSize), cornerRadius = AlbumRowCoverSize / 2)
        Column(modifier = Modifier.weight(1f)) {
            ShimmerTextLine(widthFraction = 0.5f, height = 14.dp)
            Spacer(Modifier.height(4.dp))
            ShimmerTextLine(widthFraction = 0.25f, height = 12.dp)
        }
    }
}
