/*
 * Photos for Proton
 * Copyright (C) 2026 Akoos <https://akoos.eu>
 *
 * Source:  https://github.com/gitakoos/proton-photos
 * Website: https://photos.akoos.eu
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eu.akoos.photos.presentation.theme.AppColors

/**
 * Drop in replacement for Material 3 [SnackbarHost] that styles the popup with
 * the app's own [AppColors] tokens instead of Material's `inverseSurface` /
 * `inverseOnSurface` defaults. The default ColorScheme paints the snackbar with
 * an inverted surface so in our dark theme it surfaces as a *light* (white-ish)
 * banner — visually broken and out of place with the rest of the dark UI. This
 * wrapper forces it to use our card background + primary foreground tokens so
 * snackbars look like the rest of the app on every theme + palette combo.
 *
 * Call site change is just `SnackbarHost(...)` → `ThemedSnackbarHost(...)` —
 * same args.
 */
@Composable
fun ThemedSnackbarHost(
    hostState: SnackbarHostState,
    modifier: Modifier = Modifier,
) {
    val colors = AppColors.current
    SnackbarHost(
        hostState = hostState,
        modifier = modifier,
        snackbar = { data ->
            Snackbar(
                snackbarData = data,
                containerColor = colors.cardBg,
                contentColor = colors.fgPrimary,
                actionColor = colors.accent,
                actionContentColor = colors.accent,
                dismissActionContentColor = colors.fgDim,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.padding(horizontal = 12.dp),
            )
        },
    )
}
