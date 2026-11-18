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
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Label
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.akoos.photos.R
import eu.akoos.photos.data.db.dao.LabelUsage
import eu.akoos.photos.presentation.common.AppSearchField
import eu.akoos.photos.presentation.common.PrimaryButton
import eu.akoos.photos.presentation.settings.LabelRow
import eu.akoos.photos.presentation.settings.filterLabelsForManager
import eu.akoos.photos.presentation.theme.AppColors
import eu.akoos.photos.presentation.viewer.DetailsPageHeader

/** The leading mark of a label chip or pill. */
@Composable
internal fun LabelChipIcon(tint: Color) {
    Icon(Icons.AutoMirrored.Filled.Label, contentDescription = null, tint = tint, modifier = Modifier.size(15.dp))
}

/**
 * The filter sheet's Labels section: the [LabelFilter.SECTION_TOP] most used labels and any picked beyond them
 * as chips, a More chip that opens the sheet's Labels page, and, once two or more are picked, whether a photo
 * needs any of them or all.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun LabelFilterSection(
    usage: List<LabelUsage>,
    picked: Set<String>,
    matchAll: Boolean,
    onToggle: (String) -> Unit,
    onMatchAllChange: (Boolean) -> Unit,
    onMore: () -> Unit,
) {
    FilterSectionLabel(stringResource(R.string.label_section_title))
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        LabelFilter.shownInSection(usage, picked).forEach { label ->
            FilterChip(
                label = label.label,
                selected = label.labelKey in picked,
                onClick = { onToggle(label.labelKey) },
                leadingIcon = { tint -> LabelChipIcon(tint) },
                count = label.count,
            )
        }
        FilterChip(label = stringResource(R.string.filter_labels_more), selected = false, onClick = onMore)
        // Keeps the last choice while the switch eases out, since dropping to one label resets it to Any.
        val heldMatchAll = remember { object { var value = matchAll } }
        if (picked.size >= 2) heldMatchAll.value = matchAll
        AnimatedVisibility(
            visible = picked.size >= 2,
            enter = expandVertically(tween(CALENDAR_EASE_MS)) + fadeIn(tween(CALENDAR_EASE_MS)),
            exit = shrinkVertically(tween(CALENDAR_EASE_MS)) + fadeOut(tween(CALENDAR_EASE_MS)),
        ) {
            LabelMatchSwitch(matchAll = heldMatchAll.value, onChange = onMatchAllChange)
        }
    }
}

/** Any / All for two or more picked labels, as a small two-part switch. */
@Composable
private fun LabelMatchSwitch(matchAll: Boolean, onChange: (Boolean) -> Unit) {
    val colors = AppColors.current
    val shape = RoundedCornerShape(10.dp)
    Row(
        modifier = Modifier
            .height(36.dp)
            .clip(shape)
            .background(colors.chipUnselectedBg)
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        listOf(false to R.string.filter_labels_any, true to R.string.filter_labels_all).forEach { (all, text) ->
            val on = all == matchAll
            val segmentBg by animateColorAsState(
                if (on) colors.accent.copy(alpha = 0.22f) else Color.Transparent, label = "matchSegmentBg")
            val segmentFg by animateColorAsState(if (on) colors.accent else colors.fgDim, label = "matchSegmentFg")
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(segmentBg)
                    .clickable { if (!on) onChange(all) }
                    .padding(horizontal = 12.dp)
                    .height(30.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    stringResource(text),
                    color = segmentFg,
                    fontSize = 13.sp,
                    fontWeight = if (on) FontWeight.Medium else FontWeight.Normal,
                )
            }
        }
    }
}

/**
 * The filter sheet's Labels page: every label with its photo count, a search over them, and a button that
 * applies the picked ones and steps back to the filters. Nothing is applied until that button.
 */
@Composable
internal fun LabelFilterPage(
    usage: List<LabelUsage>,
    picked: Set<String>,
    onBack: () -> Unit,
    onShow: (Set<String>) -> Unit,
) {
    var chosen by remember { mutableStateOf(picked) }
    var query by remember { mutableStateOf("") }
    val shown = remember(usage, query) { filterLabelsForManager(usage, query) }
    val maxHeight = (LocalConfiguration.current.screenHeightDp * 0.85f).dp
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = maxHeight)
            .navigationBarsPadding()
            .padding(bottom = 20.dp),
    ) {
        Column(modifier = Modifier.padding(horizontal = labelPageGutter)) {
            DetailsPageHeader(title = stringResource(R.string.label_section_title), onBack = onBack)
            Spacer(Modifier.height(8.dp))
            AppSearchField(
                query = query,
                onQueryChange = { query = it },
                placeholder = stringResource(R.string.filter_labels_search),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
        }
        LazyColumn(modifier = Modifier.weight(1f, fill = false)) {
            items(shown, key = { it.labelKey }) { label ->
                LabelRow(
                    usage = label,
                    locked = false,
                    selecting = true,
                    selected = label.labelKey in chosen,
                    onClick = { chosen = if (label.labelKey in chosen) chosen - label.labelKey else chosen + label.labelKey },
                    horizontalPadding = labelPageGutter,
                )
            }
        }
        PrimaryButton(
            label = if (chosen.isEmpty()) stringResource(R.string.filter_labels_show_all)
                else pluralStringResource(R.plurals.filter_labels_show, chosen.size, chosen.size),
            onClick = { onShow(chosen) },
            modifier = Modifier.fillMaxWidth().padding(horizontal = labelPageGutter).padding(top = 12.dp),
        )
    }
}

/**
 * The row under the search field while the label keyword is typed: the labels it offers as chips with their
 * photo counts, most used first, and a More chip for the full list. Styled like the date suggestions.
 */
@Composable
internal fun LabelSuggestionRow(labels: List<LabelUsage>, onPick: (String) -> Unit, onMore: () -> Unit) {
    Spacer(modifier = Modifier.height(8.dp))
    LazyRow(
        contentPadding = PaddingValues(horizontal = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(labels.take(SUGGESTED_LABELS), key = { "label:${it.labelKey}" }) { label ->
            FilterChip(
                label = label.label,
                selected = false,
                onClick = { onPick(label.labelKey) },
                leadingIcon = { tint -> LabelChipIcon(tint) },
                count = label.count,
            )
        }
        item(key = "more") {
            FilterChip(label = stringResource(R.string.filter_labels_more), selected = false, onClick = onMore)
        }
    }
}

/** The Labels page's side margin, the filter sheet's own, shared by the search field, the rows and the button. */
private val labelPageGutter = 20.dp

/** How many labels the typed keyword's row offers before its More chip. */
private const val SUGGESTED_LABELS = 12
