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

import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.akoos.photos.R
import eu.akoos.photos.presentation.theme.AppColors

/** A single question/answer pair, both referencing string resources. */
data class FaqEntry(@StringRes val question: Int, @StringRes val answer: Int)

/** Shared FAQ content rendered identically in onboarding and in Settings. */
val faqEntries: List<FaqEntry> = listOf(
    FaqEntry(R.string.faq_q_cloud_icons, R.string.faq_a_cloud_icons),
    FaqEntry(R.string.faq_q_backup, R.string.faq_a_backup),
    FaqEntry(R.string.faq_q_albums, R.string.faq_a_albums),
    FaqEntry(R.string.faq_q_mirror_local, R.string.faq_a_mirror_local),
    FaqEntry(R.string.faq_q_existing, R.string.faq_a_existing),
)

/**
 * Standalone accordion of the shared [faqEntries]. Each row is a tappable question
 * header with a rotating chevron; the answer expands under it via [AnimatedVisibility].
 * Kept free of the internal settings.components composables so onboarding and Settings
 * can both embed it.
 */
@Composable
fun FaqAccordion(modifier: Modifier = Modifier) {
    val colors = AppColors.current
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(colors.cardBg, RoundedCornerShape(14.dp))
            .border(0.5.dp, colors.cardBorder, RoundedCornerShape(14.dp)),
    ) {
        faqEntries.forEachIndexed { index, entry ->
            if (index > 0) {
                Spacer(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .height(0.5.dp)
                        .background(colors.cardBorder),
                )
            }
            FaqRow(entry)
        }
    }
}

@Composable
private fun FaqRow(entry: FaqEntry) {
    val colors = AppColors.current
    var expanded by rememberSaveable(entry.question) { mutableStateOf(false) }
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(entry.question),
                color = colors.fgPrimary,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                lineHeight = 19.sp,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(12.dp))
            Icon(
                imageVector = Icons.Default.ExpandMore,
                contentDescription = null,
                tint = colors.fgMute,
                modifier = Modifier
                    .size(20.dp)
                    .graphicsLayer(rotationZ = if (expanded) 180f else 0f),
            )
        }
        AnimatedVisibility(visible = expanded) {
            Text(
                text = stringResource(entry.answer),
                color = colors.fgMute,
                fontSize = 13.sp,
                lineHeight = 19.sp,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
            )
        }
    }
}
