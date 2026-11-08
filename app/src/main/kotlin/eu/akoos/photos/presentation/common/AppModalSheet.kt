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

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalBottomSheetProperties
import androidx.compose.material3.SheetState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import eu.akoos.photos.presentation.theme.SheetBg
import kotlinx.coroutines.launch

/**
 * The app's bottom sheet ("drawer"): a [ModalBottomSheet] that turns to glass when the frosted pills are on.
 * The sheet opens in a window of its own over a screen that stands still under its scrim, so it frosts one
 * picture of the whole app taken as it opens ([LocalRootGlass], [GlassBackdrop.freeze]); until that picture
 * is ready, and with the pills plain, it is the solid [containerColor] sheet it always was.
 *
 * The glass is drawn by the sheet's content column, which moves with the sheet, rather than by the sheet's
 * own surface, whose slide in happens inside its modifier chain; the column also carries the drag handle and
 * the window insets, so the glass reaches the handle and the area behind the navigation bar. Its modifier
 * chain stays the same when the picture arrives, which keeps the sheet's show animation running.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppModalSheet(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    sheetState: SheetState = rememberModalBottomSheetState(),
    containerColor: Color = SheetBg,
    scrimColor: Color = BottomSheetDefaults.ScrimColor,
    /** For a sheet with pages: the back gesture asks this first and closes the sheet only when it returns
     *  false. The sheet's own back handling is set when it opens and never changes after, so a sheet that
     *  steps back passes this from the start. */
    onBackStep: (() -> Boolean)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val root = LocalRootGlass.current
    var frozen by remember { mutableStateOf<GlassBackdrop?>(null) }
    LaunchedEffect(root) { frozen = root?.freeze() }
    val shape = BottomSheetDefaults.ExpandedShape
    val scope = rememberCoroutineScope()
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        sheetState = sheetState,
        shape = shape,
        containerColor = Color.Transparent,
        scrimColor = scrimColor,
        dragHandle = null,
        contentWindowInsets = { WindowInsets(0, 0, 0, 0) },
        properties = ModalBottomSheetProperties(shouldDismissOnBackPress = onBackStep == null),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .glassPanel(frozen, containerColor, shape)
                .windowInsetsPadding(BottomSheetDefaults.windowInsets),
        ) {
            if (onBackStep != null) {
                BackHandler {
                    if (!onBackStep()) scope.launch { sheetState.hide() }.invokeOnCompletion { onDismissRequest() }
                }
            }
            BottomSheetDefaults.DragHandle(modifier = Modifier.align(Alignment.CenterHorizontally))
            CompositionLocalProvider(LocalOnGlassSheet provides (frozen != null)) { content() }
        }
    }
}
