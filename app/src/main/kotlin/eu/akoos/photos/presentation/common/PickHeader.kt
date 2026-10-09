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

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.akoos.photos.domain.entity.GalleryItem
import eu.akoos.photos.presentation.theme.AppColors
import eu.akoos.photos.presentation.theme.PillBg
import eu.akoos.photos.presentation.theme.PillBorder

/**
 * The header every photo-picking screen shares, fixed at the top as a system gallery's picker has it: a bar
 * with what is being picked for ([title]) at the start and an optional [trailing] action at the end, then the
 * picked photos as a strip over a rule ([SelectedPhotosStrip]), with the page laid out below the rule.
 *
 * At the top of a page the title is plain text; once the page scrolls ([scrolled]) it lifts off as the design
 * system's header pill (`PillBg` with a hairline `PillBorder`, the floating header's title chip), and
 * [trailing] is handed the same 0..1 amount to follow it. The strip folds away while [expanded] is false.
 */
@Composable
fun PickHeader(
    title: String,
    scrolled: Boolean,
    items: List<GalleryItem>,
    expanded: Boolean,
    emptyLabel: String,
    onItemClick: (GalleryItem) -> Unit,
    onToggleStrip: () -> Unit,
    modifier: Modifier = Modifier,
    trailing: (@Composable (pill: Float) -> Unit)? = null,
) {
    val colors = AppColors.current
    val pill by animateFloatAsState(if (scrolled) 1f else 0f, tween(180), label = "pickHeaderPill")
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(start = 4.dp, end = 12.dp, top = 8.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // The chip's own padding is there at rest too, so the name does not move as the chip appears.
            Box(modifier = Modifier.weight(1f)) {
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .height(40.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .background(PillBg.copy(alpha = PillBg.alpha * pill), RoundedCornerShape(20.dp))
                        .border(0.5.dp, PillBorder.copy(alpha = pill), RoundedCornerShape(20.dp))
                        .padding(horizontal = 16.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    Text(
                        title,
                        color = colors.fgPrimary,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            trailing?.invoke(pill)
        }
        SelectedPhotosStrip(
            items = items,
            emptyLabel = emptyLabel,
            expanded = expanded,
            onToggle = onToggleStrip,
            onItemClick = onItemClick,
        )
    }
}

/**
 * Folds the picked-photos strip while the page below scrolls down and brings it back as it scrolls up.
 * Attach [connection] to the page's container (`Modifier.nestedScroll`) and hand [expanded] to
 * [PickHeader]. A short run of movement is ignored, so a nudge does not flicker it.
 */
class StripCollapse {
    var expanded by mutableStateOf(true)

    private var run = 0f

    val connection = object : NestedScrollConnection {
        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
            val dy = available.y
            if (dy == 0f) return Offset.Zero
            // Movement in one direction adds up, a reversal starts the count again.
            run = if (run * dy < 0f) dy else run + dy
            if (run < -RUN_PX) expanded = false else if (run > RUN_PX) expanded = true
            return Offset.Zero
        }
    }

    private companion object {
        const val RUN_PX = 48f
    }
}

@Composable
fun rememberStripCollapse(): StripCollapse = remember { StripCollapse() }
