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

package eu.akoos.photos.presentation.gallery

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.OfflinePin
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.akoos.photos.R
import eu.akoos.photos.presentation.search.DateSuggestions
import eu.akoos.photos.presentation.search.RelativeDate
import eu.akoos.photos.presentation.search.RelativeUnit
import eu.akoos.photos.presentation.search.applyTo
import eu.akoos.photos.presentation.util.appLocale
import eu.akoos.photos.presentation.theme.Accent
import eu.akoos.photos.presentation.theme.AppColors
import eu.akoos.photos.presentation.theme.FgDim
import eu.akoos.photos.presentation.theme.FgMute
import eu.akoos.photos.presentation.theme.FgPrimary
import eu.akoos.photos.presentation.theme.PillBorder
import java.util.Calendar

// ── Content filter bottom sheet ───────────────────────────────────────────────

private val filterChipShape = RoundedCornerShape(10.dp)

private const val CALENDAR_EASE_MS = 240

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun ContentFilterSheet(
    currentFilter: ContentFilter,
    currentCategory: GalleryFilter,
    onApply: (ContentFilter) -> Unit,
    onCategorySelected: (GalleryFilter) -> Unit,
    onDismiss: () -> Unit,
    showCategorySection: Boolean = true,
    showMediaTypeSection: Boolean = true,
    showDateSection: Boolean = true,
    // Today, last week, 2 weeks ago and the like as one tap each; the search page only, since the timeline's
    // filter holds no run of days.
    showRelativeDates: Boolean = false,
    // Signed out there is nothing but on-device media, so the backed-up / cloud chips can only ever
    // return an empty grid; hide the whole sync-status section for a guest.
    showSyncStatusSection: Boolean = true,
    onOpenTimelineSettings: (() -> Unit)? = null,
) {
    var mediaType by remember { mutableStateOf(currentFilter.mediaType) }
    var syncStatus by remember { mutableStateOf(currentFilter.syncStatus) }
    var year by remember { mutableStateOf(currentFilter.year) }
    var month by remember { mutableStateOf(currentFilter.month) }
    var day by remember { mutableStateOf(currentFilter.day) }
    var dayEnd by remember { mutableStateOf(currentFilter.dayEnd) }
    var dateFrom by remember { mutableStateOf(currentFilter.dateFrom) }
    var dateTo by remember { mutableStateOf(currentFilter.dateTo) }
    var category by remember { mutableStateOf(currentCategory) }
    // Where the quick dates are offered the calendar waits behind a button, open from the start only when it
    // already holds a date.
    var calendarOpen by remember { mutableStateOf(!showRelativeDates || (currentFilter.year != null && currentFilter.dateFrom == null)) }

    fun applyNow(
        mt: MediaType = mediaType,
        ss: SyncStatusFilter = syncStatus,
        y: Int? = year,
        m: Int? = month,
        d: Int? = day,
        de: Int? = dayEnd,
    ) = onApply(
        ContentFilter(
            mediaType = mt, syncStatus = ss, year = y, month = m, day = d, dayEnd = de,
            dateFrom = dateFrom, dateTo = dateTo,
        ),
    )

    val currentYear = remember { Calendar.getInstance().get(Calendar.YEAR) }
    val years = remember { (currentYear downTo currentYear - 10).toList() }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .navigationBarsPadding()
            .padding(horizontal = 20.dp)
            .padding(bottom = 36.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // Title row — the reset action sits on the right here, shown only when a filter is active,
        // instead of a separate button at the bottom of the sheet.
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.filter_title),
                color = FgPrimary,
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            val hasActiveFilter = mediaType != MediaType.All || syncStatus != SyncStatusFilter.All ||
                year != null || dateFrom != null || category != GalleryFilter.All
            if (hasActiveFilter) {
                Text(
                    text = "✕  ${stringResource(R.string.filter_reset)}",
                    color = Accent,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.clickable {
                        mediaType = MediaType.All; syncStatus = SyncStatusFilter.All
                        year = null; month = null; day = null; dayEnd = null; dateFrom = null; dateTo = null
                        category = GalleryFilter.All
                        onCategorySelected(GalleryFilter.All)
                        applyNow(mt = MediaType.All, ss = SyncStatusFilter.All, y = null, m = null, d = null, de = null)
                    },
                )
            }
        }

        // ── Categories (server-assigned PhotoTag) ────────────────────────────
        // Matches the Drive web Photos page categories: Favorites, Screenshots, Videos,
        // Live Photos, Motion Photos, Selfies, Portraits, Bursts, Panoramas, RAW.
        // These are tag-id 0..9 — server populates them automatically; local-only items
        // (not yet backed up) cannot be filtered by category.
        if (showCategorySection) {
            FilterSectionLabel(stringResource(R.string.gallery_filter_categories))
            val categories = listOf(
                GalleryFilter.All          to stringResource(R.string.gallery_filter_all),
                GalleryFilter.Favorites    to stringResource(R.string.gallery_filter_favorites),
                GalleryFilter.Screenshots  to stringResource(R.string.gallery_filter_screenshots),
                GalleryFilter.Videos       to stringResource(R.string.filter_type_videos),
                GalleryFilter.LivePhotos   to stringResource(R.string.gallery_filter_live_photos),
                GalleryFilter.Selfies      to stringResource(R.string.gallery_filter_selfies),
                GalleryFilter.Portraits    to stringResource(R.string.gallery_filter_portraits),
                GalleryFilter.Bursts       to stringResource(R.string.gallery_filter_bursts),
                GalleryFilter.Panoramas    to stringResource(R.string.gallery_filter_panoramas),
                GalleryFilter.Raw          to stringResource(R.string.gallery_filter_raw),
            )
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                categories.forEach { (cat, label) ->
                    item(key = "cat_${cat.name}") {
                        FilterChip(
                            label = label,
                            selected = category == cat,
                            onClick = {
                                category = cat
                                onCategorySelected(cat)
                            },
                            leadingIcon = if (cat == GalleryFilter.All) null
                                else { tint -> GalleryCategoryIcon(cat, tint) },
                        )
                    }
                }
            }
        }

        // ── Media type ───────────────────────────────────────────────────────
        if (showMediaTypeSection) {
            FilterSectionLabel(stringResource(R.string.filter_type_label))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(
                    MediaType.All to stringResource(R.string.gallery_filter_all),
                    MediaType.PhotosOnly to stringResource(R.string.gallery_tab_photos),
                    MediaType.VideosOnly to stringResource(R.string.filter_type_videos),
                ).forEach { (type, label) ->
                    FilterChip(
                        label = label,
                        selected = mediaType == type,
                        onClick = { mediaType = type; applyNow(mt = type) },
                    )
                }
            }
        }

        // ── Sync status ──────────────────────────────────────────────────────
        // No "All" chip: the chips toggle and span the sheet width, edge to edge with the calendar.
        // The active chip carries its own X, so tapping it (a second press) clears back to All.
        if (showSyncStatusSection) {
            FilterSectionLabel(stringResource(R.string.filter_sync_label))
            val syncOptions = listOf(
                SyncStatusFilter.LocalOnly to stringResource(R.string.filter_sync_local),
                SyncStatusFilter.BackedUp to stringResource(R.string.filter_sync_backedup),
                SyncStatusFilter.CloudOnly to stringResource(R.string.filter_sync_cloud),
                SyncStatusFilter.Offline to stringResource(R.string.filter_offline),
            )
            // Two by two, so four labels in any language keep a readable width.
            syncOptions.chunked(2).forEachIndexed { rowIndex, rowOptions ->
                if (rowIndex > 0) Spacer(Modifier.height(8.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    rowOptions.forEach { (status, label) ->
                        FilterChip(
                            label = label,
                            selected = syncStatus == status,
                            accentWhenSelected = true,
                            trailingClear = true,
                            modifier = Modifier.weight(1f),
                            onClick = {
                                val newStatus = if (syncStatus == status) SyncStatusFilter.All else status
                                syncStatus = newStatus
                                applyNow(ss = newStatus)
                            },
                        )
                    }
                }
            }
        }

        // ── Quick dates ──────────────────────────────────────────────────────
        // The same phrases the search field understands; the lit chip is the one the filter holds, and a
        // second tap on it clears the date.
        if (showRelativeDates) {
            FilterSectionLabel(stringResource(R.string.filter_quick_dates))
            val locale = appLocale()
            val today = remember { Calendar.getInstance(locale) }
            fun titled(text: String) = text.replaceFirstChar { it.titlecase(locale) }
            val weeksAgo = stringResource(R.string.search_ago_weeks).split('|').first { '#' in it }.replace("#", "2")
            val quickDates = listOf(
                titled(stringResource(R.string.search_rel_today).substringBefore('|')) to
                    DateSuggestions.resolveRelative(RelativeDate.Today, today, 1),
                titled(stringResource(R.string.search_rel_yesterday).substringBefore('|')) to
                    DateSuggestions.resolveRelative(RelativeDate.Yesterday, today, 1),
                titled(stringResource(R.string.search_rel_this_week).substringBefore('|')) to
                    DateSuggestions.resolveRelative(RelativeDate.ThisWeek, today, 1),
                titled(stringResource(R.string.search_rel_last_week).substringBefore('|')) to
                    DateSuggestions.resolveRelative(RelativeDate.LastWeek, today, 1),
                titled(weeksAgo) to DateSuggestions.resolveCounted(RelativeUnit.Week, 2, today, 1),
                titled(stringResource(R.string.search_rel_this_month).substringBefore('|')) to
                    DateSuggestions.resolveRelative(RelativeDate.ThisMonth, today, 1),
                titled(stringResource(R.string.search_rel_last_month).substringBefore('|')) to
                    DateSuggestions.resolveRelative(RelativeDate.LastMonth, today, 1),
                titled(stringResource(R.string.search_rel_this_year).substringBefore('|')) to
                    DateSuggestions.resolveRelative(RelativeDate.ThisYear, today, 1),
                titled(stringResource(R.string.search_rel_last_year).substringBefore('|')) to
                    DateSuggestions.resolveRelative(RelativeDate.LastYear, today, 1),
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                quickDates.forEach { (label, term) ->
                    val asFilter = term.applyTo(ContentFilter())
                    val selected = asFilter.year == year && asFilter.month == month && asFilter.day == day &&
                        asFilter.dayEnd == dayEnd && asFilter.dateFrom == dateFrom && asFilter.dateTo == dateTo
                    FilterChip(
                        label = label,
                        selected = selected,
                        onClick = {
                            val next = if (selected) ContentFilter() else asFilter
                            year = next.year; month = next.month; day = next.day; dayEnd = next.dayEnd
                            dateFrom = next.dateFrom; dateTo = next.dateTo
                            applyNow(y = next.year, m = next.month, d = next.day, de = next.dayEnd)
                        },
                    )
                }
            }
        }

        // ── Date (calendar — year, optional month, optional day) ───────────────
        if (showDateSection && showRelativeDates) {
            FilterChip(
                label = stringResource(R.string.filter_pick_date),
                selected = calendarOpen,
                onClick = { calendarOpen = !calendarOpen },
                modifier = Modifier.fillMaxWidth(),
                leadingIcon = { tint -> Icon(Icons.Filled.CalendarMonth, null, tint = tint, modifier = Modifier.size(16.dp)) },
            )
        }
        // The calendar eases open and shut, so the sheet grows with it instead of jumping.
        AnimatedVisibility(
            visible = showDateSection && calendarOpen,
            enter = expandVertically(tween(CALENDAR_EASE_MS)) + fadeIn(tween(CALENDAR_EASE_MS)),
            exit = shrinkVertically(tween(CALENDAR_EASE_MS)) + fadeOut(tween(CALENDAR_EASE_MS)),
        ) {
            DateFilterCalendar(
                year = year,
                month = month,
                day = day,
                dayEnd = dayEnd,
                years = years,
                onPick = { y, m, d, de ->
                    year = y; month = m; day = d; dayEnd = de; dateFrom = null; dateTo = null
                    applyNow(y = y, m = m, d = d, de = de)
                },
            )
        }

        // ── Further settings ──────────────────────────────────────────────────
        // The timeline drawer alone shows this row, a way into the full layout /
        // categories / folders screen. Search leaves the callback null.
        val openTimelineSettings = onOpenTimelineSettings
        if (openTimelineSettings != null) {
            SheetSettingsPageRow(
                label = stringResource(R.string.timeline_filter_more_settings),
                onClick = {
                    onDismiss()
                    openTimelineSettings()
                },
            )
        }
    }
}

/**
 * A drawer's way out to its full settings page: a hairline, then a row with a chevron. Emitted into
 * the drawer's own column, so the column's spacing sits between the two. The timeline drawer and the
 * Albums filter sheet share it, so both ways out read the same.
 */
@Composable
internal fun SheetSettingsPageRow(label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(0.5.dp)
            .background(PillBorder),
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            Icons.Filled.Tune,
            contentDescription = null,
            tint = FgDim,
            modifier = Modifier.size(20.dp),
        )
        Text(
            text = label,
            color = FgPrimary,
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f),
        )
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = FgMute,
            modifier = Modifier.size(20.dp),
        )
    }
}

@Composable
internal fun FilterSectionLabel(text: String) {
    Text(
        text = text.uppercase(),
        color = FgMute,
        fontSize = 11.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 0.6.sp,
    )
}

@Composable
internal fun FilterChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leadingIcon: (@Composable (Color) -> Unit)? = null,
    accentWhenSelected: Boolean = true,
    trailingClear: Boolean = false,
) {
    val colors = AppColors.current
    val useAccent = accentWhenSelected && selected
    val contentColor = when {
        useAccent -> Accent
        selected -> FgPrimary
        else -> FgDim
    }
    Box(
        modifier = modifier
            .background(
                when {
                    useAccent -> Accent.copy(alpha = 0.15f)
                    selected -> colors.chipSelectedBg
                    else -> colors.chipUnselectedBg
                },
                filterChipShape,
            )
            .then(
                when {
                    useAccent -> Modifier.border(1.dp, Accent, filterChipShape)
                    !selected -> Modifier.border(0.5.dp, PillBorder, filterChipShape)
                    else -> Modifier
                }
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 9.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            leadingIcon?.invoke(contentColor)
            Text(
                text = label,
                color = contentColor,
                fontSize = 13.sp,
                fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
            )
            if (trailingClear && selected) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = null,
                    tint = contentColor,
                    modifier = Modifier.size(14.dp),
                )
            }
        }
    }
}

/** Leading icon for a category filter chip, mirroring the Drive web category row: Proton's
 *  own category glyphs for screenshots / videos / live / bursts / panoramas / raw, with the
 *  closest Material icons standing in for favourites / selfies / portraits. */
@Composable
internal fun GalleryCategoryIcon(filter: GalleryFilter, tint: Color) {
    val drawableRes: Int? = when (filter) {
        GalleryFilter.Screenshots  -> R.drawable.ic_screenshot
        GalleryFilter.Videos       -> R.drawable.ic_video_camera
        GalleryFilter.LivePhotos,
        GalleryFilter.MotionPhotos -> R.drawable.ic_live
        GalleryFilter.Bursts       -> R.drawable.ic_image_stacked
        GalleryFilter.Panoramas    -> R.drawable.ic_panorama
        GalleryFilter.Raw          -> R.drawable.ic_raw
        else -> null
    }
    val vector = when (filter) {
        GalleryFilter.Favorites -> Icons.Default.FavoriteBorder
        GalleryFilter.Selfies   -> Icons.Default.Person
        GalleryFilter.Portraits -> Icons.Default.AccountCircle
        GalleryFilter.Offline   -> Icons.Default.OfflinePin
        else -> null
    }
    when {
        drawableRes != null -> Icon(painterResource(drawableRes), null, tint = tint, modifier = Modifier.size(15.dp))
        vector != null      -> Icon(vector, null, tint = tint, modifier = Modifier.size(15.dp))
    }
}
