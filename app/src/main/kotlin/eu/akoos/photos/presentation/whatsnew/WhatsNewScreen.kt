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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.activity.compose.BackHandler
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
import eu.akoos.photos.presentation.common.FloatingHeader
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

/**
 * Rough on-screen height of one [WhatsNewCard] (a 40dp chip against a title + two-ish body lines,
 * inside 14dp padding). Only used to decide how many whole cards a page can hold; the estimate is a
 * touch generous so a page never packs more than it can show, and each page is independently
 * scrollable as a final guard so an unusually long line can never clip.
 */
private val WhatsNewCardEstimate: Dp = 112.dp

/**
 * Splits [count] feature cards into contiguous pages that are evenly balanced. It first finds how
 * many whole cards a page of [pageHeight] can hold (each [cardHeight] tall with [spacing] between,
 * always at least one), then uses the fewest pages that fit and spreads the cards evenly across them.
 * So six cards on a tall screen read as two pages of three, not one crammed page of five and a lonely
 * page of one. Cards keep their order, so each page is a contiguous index range.
 */
internal fun packFeaturePages(count: Int, pageHeight: Dp, cardHeight: Dp, spacing: Dp): List<IntRange> {
    if (count <= 0) return emptyList()
    // The most whole cards that fit in one page: one card is cardHeight, each further card adds
    // spacing + cardHeight. At least one, so a very short screen still shows a card (the page's own
    // scroll is the final guard against clipping).
    var perPage = 1
    while (cardHeight + (spacing + cardHeight) * perPage <= pageHeight) perPage++
    val pageCount = (count + perPage - 1) / perPage
    val base = count / pageCount
    val remainder = count % pageCount
    val pages = mutableListOf<IntRange>()
    var start = 0
    for (page in 0 until pageCount) {
        // The first `remainder` pages take one extra card, so the split is as even as it can be.
        val size = base + if (page < remainder) 1 else 0
        pages.add(start until start + size)
        start += size
    }
    return pages
}

/** Rough on-screen height of a section label plus the gap to the first card under it, subtracted from
 *  a page before its cards are packed so a labeled page holds one fewer card than a bare one. */
private val WhatsNewSectionHeaderHeight: Dp = 40.dp

/** One packed page of feature cards: the section label to draw above them (null when the release is
 *  not split into sections), and the cards on that page. */
internal class WhatsNewFeaturePage(val headerRes: Int?, val features: List<WhatsNewFeature>)

/**
 * Lays a release's feature cards out into pages. A release whose cards span more than one category is
 * split into labeled sections (New first, then Improved), each packed on its own so a section starts a
 * fresh page and never shares one with the next; the label's [headerHeight] is taken off the page
 * first. A single-category release packs as one unlabeled run, exactly as older entries did.
 */
internal fun categoryFeaturePages(
    release: WhatsNewRelease,
    pageHeight: Dp,
    cardHeight: Dp,
    spacing: Dp,
    headerHeight: Dp,
): List<WhatsNewFeaturePage> {
    val categories = release.features.map { it.category }.distinct()
    if (categories.size <= 1) {
        return packFeaturePages(release.features.size, pageHeight, cardHeight, spacing)
            .map { range -> WhatsNewFeaturePage(null, release.features.slice(range)) }
    }
    val pages = mutableListOf<WhatsNewFeaturePage>()
    for (category in categories) {
        val items = release.features.filter { it.category == category }
        val available = (pageHeight - headerHeight).coerceAtLeast(cardHeight)
        for (range in packFeaturePages(items.size, available, cardHeight, spacing)) {
            pages.add(WhatsNewFeaturePage(category.titleRes, items.slice(range)))
        }
    }
    return pages
}

/**
 * One-time post-update highlights screen. Shown once after an upgrade (see the gate in NavGraph) on
 * top of the Gallery: a floating-pill header over a size-aware horizontal pager. Feature cards are
 * packed so each page shows as many whole cards as the screen's height fits, with the page dots and
 * the primary dismiss button pinned at the bottom on every page (no scrolling to reach it). A
 * release's headline card, where it has one, takes the first page alone.
 *
 * Shows ONE release: [release] defaults to the newest, which is what the post-update gate wants;
 * Settings passes an older one to re-read it. Any exit marks the current version seen via
 * [WhatsNewViewModel.markSeen] so the post-update screen does not return until the next release,
 * which is correct for a browse too (the user has now read it).
 */
@Composable
fun WhatsNewScreen(
    onDone: () -> Unit,
    version: String? = null,
    viewModel: WhatsNewViewModel = hiltViewModel(),
) {
    val colors = AppColors.current
    val release = remember(version) { whatsNewReleaseFor(version) }

    // A system back gesture pops this screen through the host's own handler, which never reaches the
    // header's onBack, so the version stayed unseen and the screen returned on every launch. Marking
    // it here first makes the gesture settle the gate exactly as the button and the arrow do.
    BackHandler {
        viewModel.markSeen()
        onDone()
    }

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
            val featurePages = remember(pageHeight, release) {
                categoryFeaturePages(release, pageHeight, WhatsNewCardEstimate, 12.dp, WhatsNewSectionHeaderHeight)
            }
            // A headline card, where the release has one, takes page 0 on its own; the feature
            // pages follow. A release without one starts straight at its features.
            val heroPages = if (release.hero != null) 1 else 0
            val pageCount = heroPages + featurePages.size
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
                    val pageScroll = rememberScrollState()
                    Box(modifier = Modifier.fillMaxSize()) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(pageScroll)
                                .padding(horizontal = 16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            if (heroPages == 1 && page == 0) {
                                when (release.hero) {
                                    WhatsNewHero.Hide -> WhatsNewHideCard()
                                    WhatsNewHero.AlbumOrder -> WhatsNewAlbumOrderCard()
                                    null -> Unit
                                }
                            } else {
                                val featurePage = featurePages[page - heroPages]
                                featurePage.headerRes?.let { headerRes ->
                                    WhatsNewSectionHeader(stringResource(headerRes))
                                }
                                for (feature in featurePage.features) {
                                    WhatsNewCard(
                                        icon = feature.icon,
                                        title = stringResource(feature.titleRes),
                                        body = stringResource(feature.bodyRes),
                                    )
                                }
                            }
                            // The closing line comes from the release being read, not from the app, so
                            // an older release cannot advertise changes that shipped after it.
                            val moreRes = release.moreRes
                            if (moreRes != null && page == pageCount - 1) {
                                Text(
                                    stringResource(moreRes),
                                    color = colors.fgMute, fontSize = 13.sp,
                                    modifier = Modifier.padding(top = 4.dp, start = 4.dp, end = 4.dp),
                                )
                            }
                            // A little breathing room so the last card clears the scroll hint.
                            Spacer(Modifier.height(20.dp))
                        }
                        // A downward chevron while this page can still scroll down, so a page taller
                        // than the screen (a long card, or a large font scale) reads as scrollable
                        // instead of looking cut off. It disappears once the bottom is reached.
                        if (pageScroll.canScrollForward) {
                            WhatsNewScrollHint(Modifier.align(Alignment.BottomCenter))
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
        FloatingHeader(
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

/** The "there is more below" affordance: a downward chevron in a soft accent chip, shown at the
 *  bottom of a page only while it can still scroll down. */
@Composable
private fun WhatsNewScrollHint(modifier: Modifier = Modifier) {
    val colors = AppColors.current
    Box(
        modifier = modifier
            .padding(bottom = 6.dp)
            .size(28.dp)
            .clip(CircleShape)
            .background(colors.accent.copy(alpha = 0.16f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Default.KeyboardArrowDown,
            contentDescription = null,
            tint = colors.accent,
            modifier = Modifier.size(20.dp),
        )
    }
}

/** A section label above a group of cards ("New" / "Improved"), in the accent colour so it reads as a
 *  divider between the release's brand-new features and its improvements, not as another card. */
@Composable
private fun WhatsNewSectionHeader(text: String) {
    val colors = AppColors.current
    Text(
        text,
        color = colors.accent,
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(start = 4.dp, top = 2.dp),
    )
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

/**
 * The album ordering highlight. The four option names are pulled from the Albums tab's OWN menu
 * strings, so this card can never name an option differently from the menu it is describing.
 */
@Composable
private fun WhatsNewAlbumOrderCard() {
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
                Icon(Icons.AutoMirrored.Filled.Sort, null, tint = colors.accent, modifier = Modifier.size(22.dp))
            }
            Text(
                stringResource(R.string.whats_new_albums_title),
                color = colors.fgPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
            )
        }
        Text(stringResource(R.string.whats_new_albums_intro), color = colors.fgDim, fontSize = 13.sp)
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            AlbumSortOptionRow(stringResource(R.string.albums_sort_name))
            AlbumSortOptionRow(stringResource(R.string.albums_sort_activity))
            AlbumSortOptionRow(stringResource(R.string.albums_sort_count))
            AlbumSortOptionRow(stringResource(R.string.albums_sort_custom))
        }
        Text(stringResource(R.string.whats_new_albums_drag), color = colors.fgDim, fontSize = 13.sp)
    }
}

/** One sort option, named exactly as the Albums tab's own menu names it. */
@Composable
private fun AlbumSortOptionRow(label: String) {
    val colors = AppColors.current
    Row(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(colors.accent),
        )
        Text(label, color = colors.fgPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
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
