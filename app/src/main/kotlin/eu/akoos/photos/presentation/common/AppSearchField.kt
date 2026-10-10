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

package eu.akoos.photos.presentation.common

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.akoos.photos.R
import eu.akoos.photos.presentation.theme.AppColors

/**
 * The app's single search-input recipe, so every search field (the Search screen, the place search
 * drawer, and any other) reads the same: a 46dp rounded row with a leading magnifier, a single-line
 * input, and a clear button that appears once something is typed. The border turns accent while the
 * field is not empty. [modifier] shapes the width (a weight in a row, or fill in a column); [background]
 * defaults to the input surface but a darker host can pass its own.
 */
@Composable
internal fun AppSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    background: Color = AppColors.current.chipUnselectedBg,
    // Icon and text sizing default to the standalone field on the Search screen; the map's places
    // drawer passes the smaller values so it reads 1:1 with the floating bar that raised it.
    iconSize: Dp = 20.dp,
    textSize: TextUnit = TextUnit.Unspecified,
    onFocusChanged: (Boolean) -> Unit = {},
    // A field whose keyboard key does something (a picker committing the typed name) passes its own.
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    // True while something besides the text narrows the results (shown as pills beneath the field), which
    // turns the border to the accent colour like typed text does.
    filtered: Boolean = false,
    // What Backspace does on an empty field, such as taking the last pill away.
    onBackspaceWhenEmpty: (() -> Unit)? = null,
) {
    val colors = AppColors.current
    val focusRequester = remember { FocusRequester() }
    Row(
        modifier = modifier
            .height(46.dp)
            // A tap anywhere on the pill focuses the field, not just the thin text line; no ripple so
            // the surface looks unchanged.
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { focusRequester.requestFocus() }
            .clip(RoundedCornerShape(14.dp))
            .background(background)
            .border(
                1.dp,
                if (query.isNotEmpty() || filtered) colors.accent else colors.line,
                RoundedCornerShape(14.dp),
            )
            .padding(start = 12.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            Icons.Filled.Search,
            contentDescription = null,
            tint = colors.fgDim,
            modifier = Modifier.size(iconSize),
        )
        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (query.isEmpty()) {
                Text(text = placeholder, color = colors.fgMute, maxLines = 1, fontSize = textSize)
            }
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                singleLine = true,
                textStyle = TextStyle(color = colors.fgPrimary, fontSize = textSize),
                cursorBrush = SolidColor(colors.accent),
                keyboardOptions = keyboardOptions,
                keyboardActions = keyboardActions,
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester)
                    .onFocusChanged { onFocusChanged(it.isFocused) }
                    .onPreviewKeyEvent { event ->
                        if (event.type == KeyEventType.KeyDown && event.key == Key.Backspace &&
                            query.isEmpty() && onBackspaceWhenEmpty != null
                        ) {
                            onBackspaceWhenEmpty()
                            true
                        } else {
                            false
                        }
                    },
            )
        }
        if (query.isNotEmpty()) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .clickable { onQueryChange("") },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = stringResource(R.string.cd_clear_search),
                    tint = colors.fgDim,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

/** One thing that narrows a search, shown as a pill beneath the field: its [label] and what removes it. */
internal data class SearchFieldTerm(
    val label: String,
    val removeDescription: String? = null,
    val onRemove: () -> Unit,
)

/** What narrows a search, as a sideways-scrolling row of [SearchTermPill]s beneath the field; nothing at all
 *  while there is none, so the header does not grow. */
@Composable
internal fun SearchTermRow(terms: List<SearchFieldTerm>, modifier: Modifier = Modifier) {
    if (terms.isEmpty()) return
    Row(
        modifier = modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        terms.forEach { SearchTermPill(it, large = true) }
    }
}

/** A [SearchFieldTerm] as a pill; a tap anywhere on it removes it. Shared with the Add labels field. */
@Composable
internal fun SearchTermPill(term: SearchFieldTerm, large: Boolean = false) {
    val colors = AppColors.current
    Row(
        modifier = Modifier
            .height(if (large) 34.dp else 24.dp)
            .clip(RoundedCornerShape(if (large) 14.dp else 8.dp))
            .background(
                if (large) colors.accent.copy(alpha = 0.18f).compositeOver(colors.bg0)
                else colors.accent.copy(alpha = 0.18f),
            )
            .clickable { term.onRemove() }
            .padding(start = if (large) 12.dp else 8.dp, end = if (large) 8.dp else 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(if (large) 4.dp else 2.dp),
    ) {
        Text(term.label, color = colors.accent, fontSize = if (large) 14.sp else 11.sp, maxLines = 1)
        Icon(
            Icons.Filled.Close, contentDescription = term.removeDescription, tint = colors.accent,
            modifier = Modifier.size(if (large) 16.dp else 12.dp),
        )
    }
}
