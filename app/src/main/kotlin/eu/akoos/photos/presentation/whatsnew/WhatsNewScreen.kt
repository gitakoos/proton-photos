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

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Compress
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.GroupAdd
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PhotoAlbum
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.edit
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.akoos.photos.BuildConfig
import eu.akoos.photos.R
import eu.akoos.photos.data.preferences.SettingsKeys
import eu.akoos.photos.data.preferences.settingsDataStore
import eu.akoos.photos.presentation.common.floatingHeaderContentTopPadding
import eu.akoos.photos.presentation.memories.FloatingMemoriesHeader
import eu.akoos.photos.presentation.theme.AppColors
import eu.akoos.photos.presentation.theme.PillBg
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Persists the one-time "What's new" dismissal. [markSeen] writes the current versionCode to
 * [SettingsKeys.WHATS_NEW_SEEN_VERSION] so the screen can never reappear for this version. The
 * screen calls it on every exit (Got-it or back), so whichever path the user takes out of the
 * screen settles the gate.
 */
@HiltViewModel
class WhatsNewViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
) : ViewModel() {
    fun markSeen() {
        viewModelScope.launch {
            context.settingsDataStore.edit {
                it[SettingsKeys.WHATS_NEW_SEEN_VERSION] = BuildConfig.VERSION_CODE
            }
        }
    }
}

/** One feature highlight fed into the pager: its icon chip and strings. */
private class WhatsNewFeature(
    val icon: ImageVector,
    val titleRes: Int,
    val bodyRes: Int,
)

/**
 * Rough on-screen height of one [WhatsNewCard] (a 40dp chip against a title + two-ish body lines,
 * inside 14dp padding). Only used to decide how many whole cards a page can hold; the estimate is a
 * touch generous so a page never packs more than it can show, and each page is independently
 * scrollable as a final guard so an unusually long line can never clip.
 */
private val WhatsNewCardEstimate: Dp = 112.dp

/**
 * Greedily packs [count] feature cards onto pages, fitting as many whole cards as [pageHeight]
 * allows: a taller screen takes more per page, a shorter one fewer. Each card is treated as
 * [cardHeight] tall with [spacing] between cards, and a page closes once the next card would
 * overflow. Cards pack in order, so each page is a contiguous index range.
 */
private fun packFeaturePages(count: Int, pageHeight: Dp, cardHeight: Dp, spacing: Dp): List<IntRange> {
    val pages = mutableListOf<IntRange>()
    var start = 0
    var used = 0.dp
    for (i in 0 until count) {
        val add = if (i == start) cardHeight else spacing + cardHeight
        if (i != start && used + add > pageHeight) {
            pages.add(start until i)
            start = i
            used = cardHeight
        } else {
            used += add
        }
    }
    if (start < count) pages.add(start until count)
    return pages
}

/**
 * One-time post-update highlights screen. Shown once after an upgrade (see the gate in NavGraph) on
 * top of the Gallery: a floating-pill header over a size-aware horizontal pager. Feature cards are
 * packed so each page shows as many whole cards as the screen's height fits, with the page dots and
 * the primary dismiss button pinned at the bottom on every page (no scrolling to reach it). The tall
 * Hide card takes the first page alone. Any exit marks the version seen via
 * [WhatsNewViewModel.markSeen] so it does not return until the next release.
 */
@Composable
fun WhatsNewScreen(
    onDone: () -> Unit,
    viewModel: WhatsNewViewModel = hiltViewModel(),
) {
    val colors = AppColors.current

    // Feature cards after Hide, in order.
    val features = listOf(
        WhatsNewFeature(Icons.Default.GroupAdd, R.string.whats_new_shared_add_title, R.string.whats_new_shared_add_body),
        WhatsNewFeature(Icons.Default.Bookmark, R.string.whats_new_viewer_place_title, R.string.whats_new_viewer_place_body),
        WhatsNewFeature(Icons.Default.Shield, R.string.whats_new_album_guard_title, R.string.whats_new_album_guard_body),
        WhatsNewFeature(Icons.Default.CleaningServices, R.string.whats_new_freeup_title, R.string.whats_new_freeup_body),
        WhatsNewFeature(Icons.Default.Compress, R.string.whats_new_compress_title, R.string.whats_new_compress_body),
        WhatsNewFeature(Icons.Default.GridView, R.string.whats_new_seamless_title, R.string.whats_new_seamless_body),
        WhatsNewFeature(Icons.Default.Movie, R.string.whats_new_scrubber_title, R.string.whats_new_scrubber_body),
        WhatsNewFeature(Icons.Default.PhotoAlbum, R.string.whats_new_album_title, R.string.whats_new_album_body),
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.pageBg),
    ) {
        val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        // Room the floating header reserves at the top: the status-bar inset + the pill-row height.
        val contentTopPad = floatingHeaderContentTopPadding()

        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            // The usable page is the screen minus the header inset and the pinned bottom band. The
            // band figure is a slight over-estimate of the real dots + button + nav insets, so the
            // pager it packs against is never taller than the space the cards actually get.
            val bottomBandHeight = 108.dp + navBottom
            val pageHeight = (maxHeight - contentTopPad - bottomBandHeight).coerceAtLeast(160.dp)
            val featurePages = remember(pageHeight) {
                packFeaturePages(features.size, pageHeight, WhatsNewCardEstimate, 12.dp)
            }
            // Page 0 is the tall Hide card on its own; the feature pages follow.
            val pageCount = 1 + featurePages.size
            val pagerState = rememberPagerState(pageCount = { pageCount })

            Column(modifier = Modifier.fillMaxSize()) {
                Spacer(Modifier.height(contentTopPad))
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    verticalAlignment = Alignment.Top,
                ) { page ->
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        if (page == 0) {
                            WhatsNewHideCard()
                        } else {
                            for (idx in featurePages[page - 1]) {
                                val feature = features[idx]
                                WhatsNewCard(
                                    icon = feature.icon,
                                    title = stringResource(feature.titleRes),
                                    body = stringResource(feature.bodyRes),
                                )
                            }
                        }
                        if (page == pageCount - 1) {
                            Text(
                                stringResource(R.string.whats_new_more),
                                color = colors.fgMute, fontSize = 13.sp,
                                modifier = Modifier.padding(top = 4.dp, start = 4.dp, end = 4.dp),
                            )
                        }
                    }
                }

                // Pinned bottom band: page dots then the dismiss button, clear of the nav bar.
                Spacer(Modifier.height(12.dp))
                WhatsNewPagerDots(current = pagerState.currentPage, count = pageCount)
                Spacer(Modifier.height(14.dp))
                Button(
                    onClick = {
                        viewModel.markSeen()
                        onDone()
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = colors.accent,
                        contentColor = colors.activeChipText,
                    ),
                ) {
                    Text(
                        stringResource(R.string.whats_new_done),
                        fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(vertical = 4.dp),
                    )
                }
                Spacer(Modifier.height(navBottom + 12.dp))
            }
        }

        // Floating pill header (matches the other secondary screens): title pill + a back button
        // that dismisses exactly like Got-it. Back marks the version seen too.
        FloatingMemoriesHeader(
            title = stringResource(R.string.whats_new_title),
            onBack = {
                viewModel.markSeen()
                onDone()
            },
        )
    }
}

/** The page-position dots under the pager: a stretched accent pill for the current page, muted dots
 *  for the rest. */
@Composable
private fun WhatsNewPagerDots(current: Int, count: Int) {
    val colors = AppColors.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(count) { i ->
            val active = i == current
            Box(
                modifier = Modifier
                    .padding(horizontal = 3.dp)
                    .size(width = if (active) 18.dp else 6.dp, height = 6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(if (active) colors.accent else colors.line2),
            )
        }
    }
}

/** One feature highlight: a leading icon chip, a bold title, and a short description. */
@Composable
private fun WhatsNewCard(
    icon: ImageVector,
    title: String,
    body: String,
) {
    val colors = AppColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(PillBg)
            .padding(14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(colors.accent.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, null, tint = colors.accent, modifier = Modifier.size(22.dp))
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(title, color = colors.fgPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Text(body, color = colors.fgDim, fontSize = 13.sp)
        }
    }
}

/**
 * The Hide highlight, shown first. Each of the three photo kinds is anchored to the badge the user
 * actually sees on the tile (green cloud = backed up, white cloud = cloud-only, a phone for a photo
 * that is only on this device), so the plain-language line maps to something recognisable instead of
 * to jargon. A Column with even spacing keeps the cases from crowding each other.
 */
@Composable
private fun WhatsNewHideCard() {
    val colors = AppColors.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(PillBg)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(colors.accent.copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Default.VisibilityOff, null, tint = colors.accent, modifier = Modifier.size(22.dp))
            }
            Text(
                stringResource(R.string.whats_new_hide_title),
                color = colors.fgPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
            )
        }
        Text(stringResource(R.string.whats_new_hide_intro), color = colors.fgDim, fontSize = 13.sp)
        HideBadgeRow(Icons.Default.Smartphone, Color.White, stringResource(R.string.whats_new_hide_device))
        HideBadgeRow(Icons.Default.Cloud, Color(0xFF30D158), stringResource(R.string.whats_new_hide_backed))
        HideBadgeRow(Icons.Default.Cloud, Color.White, stringResource(R.string.whats_new_hide_cloud))
        Text(stringResource(R.string.whats_new_hide_albums), color = colors.fgDim, fontSize = 13.sp)
    }
}

/** One Hide case: the tile badge the user recognises, then its plain-language description. */
@Composable
private fun HideBadgeRow(icon: ImageVector, tint: Color, text: String) {
    val colors = AppColors.current
    Row(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            modifier = Modifier
                .size(26.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(Color.Black.copy(alpha = 0.55f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, null, tint = tint, modifier = Modifier.size(15.dp))
        }
        Text(text, color = colors.fgDim, fontSize = 13.sp, modifier = Modifier.weight(1f))
    }
}
