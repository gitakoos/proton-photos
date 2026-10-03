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
import androidx.compose.foundation.layout.widthIn
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
    // What already narrows the results, shown as removable pills ahead of the text; Backspace on an
    // empty field takes the last one away.
    terms: List<SearchFieldTerm> = emptyList(),
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
                if (query.isNotEmpty() || terms.isNotEmpty()) colors.accent else colors.line,
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
        if (terms.isNotEmpty()) {
            Row(
                modifier = Modifier.widthIn(max = 200.dp).horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                terms.forEach { term -> SearchTermPill(term) }
            }
        }
        Box(modifier = Modifier.weight(1f).widthIn(min = 48.dp), contentAlignment = Alignment.CenterStart) {
            if (query.isEmpty() && terms.isEmpty()) {
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
                            query.isEmpty() && terms.isNotEmpty()
                        ) {
                            terms.last().onRemove()
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

/** One thing that narrows a search, shown as a pill in the field: its [label] and what removes it. */
internal data class SearchFieldTerm(val label: String, val onRemove: () -> Unit)

@Composable
private fun SearchTermPill(term: SearchFieldTerm) {
    val colors = AppColors.current
    Row(
        modifier = Modifier
            .height(30.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(colors.accent.copy(alpha = 0.18f))
            .clickable { term.onRemove() }
            .padding(start = 10.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(term.label, color = colors.accent, fontSize = 13.sp, maxLines = 1)
        Icon(Icons.Filled.Close, contentDescription = null, tint = colors.accent, modifier = Modifier.size(14.dp))
    }
}
