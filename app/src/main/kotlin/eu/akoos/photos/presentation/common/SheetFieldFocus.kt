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

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay

private const val SETTLE_MS = 500L

/**
 * Whether the text fields of a sheet or full-screen dialog may take focus yet. Such a surface is a
 * window of its own, and when it opens Compose hands the first focusable field the focus at once,
 * before the surface has slid in and the keyboard is up. That put the cursor in a field nobody had
 * chosen, and a password manager placed its suggestion at the field's still-unmoved position. Fields
 * stay unfocusable until the surface has settled (`Modifier.focusProperties { canFocus = ready }`), so
 * nothing is focused until a field is tapped.
 */
@Composable
fun rememberSheetFieldsReady(): Boolean {
    var ready by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(SETTLE_MS)
        ready = true
    }
    return ready
}
