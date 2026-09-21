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

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.akoos.photos.presentation.theme.FgMute

/**
 * The label over one group of [ActionSheetRow]s. Every action sheet names its sections through this
 * one element, so the same group cannot read one way in one drawer and another way in the next.
 *
 * A section with nothing in it takes no heading: the caller drops the whole group rather than
 * leaving a label over empty space.
 */
@Composable
internal fun ActionSheetSectionHeading(
    text: String,
    /** Set on the group that opens a drawer, where the title above it already carries the gap. */
    first: Boolean = false,
) {
    Text(
        text,
        color = FgMute,
        fontSize = 12.sp,
        modifier = Modifier.padding(
            start = 4.dp,
            top = if (first) 0.dp else 20.dp,
            bottom = 8.dp,
        ),
    )
}
