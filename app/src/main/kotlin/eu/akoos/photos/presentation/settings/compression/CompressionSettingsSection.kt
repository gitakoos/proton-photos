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

package eu.akoos.photos.presentation.settings.compression

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.akoos.photos.R
import eu.akoos.photos.domain.entity.UploadCompressionTier
import eu.akoos.photos.domain.entity.compression.EncoderCapabilities
import eu.akoos.photos.domain.entity.compression.VideoCodec
import eu.akoos.photos.domain.entity.compression.VideoCodecChoice
import eu.akoos.photos.domain.entity.compression.VideoCompressionProfile
import eu.akoos.photos.presentation.settings.labelRes
import eu.akoos.photos.presentation.theme.AppColors
import eu.akoos.photos.presentation.util.formatBytes

@Composable
private fun codecChoiceLabel(choice: VideoCodecChoice): String = when (choice) {
    VideoCodecChoice.AUTO -> stringResource(R.string.compression_codec_auto)
    VideoCodecChoice.KEEP_SOURCE -> stringResource(R.string.compression_codec_keep)
    VideoCodecChoice.AVC -> codecDisplayName(VideoCodec.AVC)
    VideoCodecChoice.HEVC -> codecDisplayName(VideoCodec.HEVC)
    VideoCodecChoice.AV1 -> codecDisplayName(VideoCodec.AV1)
}

/**
 * A codec chip's second line: what Auto resolves to, Hardware or Software, or why the option is
 * unavailable. "Same as original" has none, since it depends on each video.
 */
@Composable
private fun badgeLabel(option: CodecOption): String? = when {
    option.choice == VideoCodecChoice.KEEP_SOURCE -> null
    option.choice == VideoCodecChoice.AUTO -> option.resolves?.let { codecDisplayName(it) }
    option.badge == CodecBadge.HARDWARE -> stringResource(R.string.compression_badge_hardware)
    option.badge == CodecBadge.SOFTWARE -> stringResource(R.string.compression_badge_software)
    option.reason == UnavailableReason.NEEDS_HARDWARE -> stringResource(R.string.compression_badge_needs_hardware)
    else -> stringResource(R.string.compression_badge_not_supported)
}

/** Width of one option card and the gap between cards; the next card always peeks in from the edge. */
private val CARD_WIDTH = 232.dp
private val CARD_GAP = 10.dp
private val EDGE = 16.dp

@Composable
private fun codecDescription(choice: VideoCodecChoice): String = stringResource(
    when (choice) {
        VideoCodecChoice.AUTO -> R.string.compression_codec_desc_auto
        VideoCodecChoice.KEEP_SOURCE -> R.string.compression_hint_keep
        VideoCodecChoice.AVC -> R.string.compression_codec_desc_avc
        VideoCodecChoice.HEVC -> R.string.compression_codec_desc_hevc
        VideoCodecChoice.AV1 -> R.string.compression_codec_desc_av1
    },
)

/**
 * A row of option cards that scrolls sideways. It opens with the chosen card in view and one card of
 * context before it, and the cards share the height of the tallest, so a long translation never leaves
 * a ragged row.
 */
@Composable
private fun OptionRow(selectedIndex: Int, content: @Composable RowScope.() -> Unit) {
    val scroll = rememberScrollState()
    val density = LocalDensity.current
    var placed by remember { mutableStateOf(false) }
    LaunchedEffect(scroll.maxValue) {
        if (!placed && scroll.maxValue > 0) {
            placed = true
            val step = with(density) { (CARD_WIDTH + CARD_GAP).roundToPx() }
            scroll.scrollTo(((selectedIndex - 1).coerceAtLeast(0) * step).coerceAtMost(scroll.maxValue))
        }
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(scroll)
            .height(IntrinsicSize.Min)
            .selectableGroup()
            .padding(horizontal = EDGE),
        horizontalArrangement = Arrangement.spacedBy(CARD_GAP),
        content = content,
    )
}

/**
 * One option: a title, a short description and an optional detail line. The chosen card has a heavier
 * accent border, a tinted fill and a check mark; an option that cannot be used stays in the row, dimmed.
 */
@Composable
private fun RowScope.OptionCard(
    selected: Boolean,
    title: String,
    description: String,
    enabled: Boolean = true,
    detail: @Composable (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    val colors = AppColors.current
    val shape = RoundedCornerShape(12.dp)
    Box(
        modifier = Modifier
            .width(CARD_WIDTH)
            .fillMaxHeight()
            .clip(shape)
            .background(if (selected) colors.accent.copy(alpha = 0.16f) else colors.surfaceWeak)
            .border(if (selected) 1.5.dp else 0.5.dp, if (selected) colors.accent else colors.line2, shape)
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick),
    ) {
        Column(modifier = Modifier.padding(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 12.dp)) {
            Text(
                title,
                color = if (enabled) colors.fgPrimary else colors.fgMute,
                fontSize = 15.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                modifier = Modifier.padding(end = 26.dp),
            )
            Spacer(Modifier.height(4.dp))
            Text(
                description,
                color = if (enabled) colors.fgDim else colors.fgMute,
                fontSize = 13.sp,
                lineHeight = 18.sp,
            )
            if (detail != null) {
                Spacer(Modifier.height(8.dp))
                detail()
            }
        }
        if (selected) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(10.dp)
                    .size(20.dp)
                    .background(colors.accent, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Default.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(14.dp))
            }
        }
    }
}

/**
 * The video codec cards, then a compatibility note for the codec that will actually be produced. An
 * option this phone cannot use stays visible, dimmed, with the reason on its card.
 */
@Composable
fun CodecPicker(
    selected: VideoCodecChoice,
    capabilities: EncoderCapabilities?,
    onSelect: (VideoCodecChoice) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = AppColors.current
    Column(modifier = modifier) {
        FieldLabel(stringResource(R.string.compression_codec), Modifier.padding(horizontal = EDGE))
        Spacer(Modifier.height(8.dp))
        if (capabilities == null) {
            // The one-off scan has not finished; show the choice without badges for that moment.
            Text(
                codecChoiceLabel(selected),
                color = colors.fgDim,
                fontSize = 13.sp,
                modifier = Modifier.padding(horizontal = EDGE),
            )
            return@Column
        }
        val options = CodecOptions.options(capabilities)
        OptionRow(selectedIndex = options.indexOfFirst { it.choice == selected }.coerceAtLeast(0)) {
            options.forEach { option ->
                val badge = badgeLabel(option)
                OptionCard(
                    selected = option.choice == selected,
                    title = codecChoiceLabel(option.choice),
                    description = codecDescription(option.choice),
                    enabled = option.selectable,
                    detail = badge?.let { label ->
                        {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    Modifier
                                        .size(6.dp)
                                        .clip(CircleShape)
                                        .background(
                                            when (option.badge) {
                                                CodecBadge.HARDWARE -> colors.accent
                                                CodecBadge.SOFTWARE -> colors.fgMute
                                                CodecBadge.UNAVAILABLE -> colors.line2
                                            },
                                        ),
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(label, color = colors.fgDim, fontSize = 12.sp)
                            }
                        }
                    },
                    onClick = { onSelect(option.choice) },
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        val hint = when {
            selected == VideoCodecChoice.KEEP_SOURCE -> stringResource(R.string.compression_hint_keep)
            else -> when (CodecOptions.effectiveCodec(capabilities, selected)) {
                VideoCodec.AVC -> stringResource(R.string.compression_hint_avc)
                VideoCodec.HEVC -> stringResource(R.string.compression_hint_hevc)
                VideoCodec.AV1 -> stringResource(R.string.compression_hint_av1)
            }
        }
        InfoNote(hint, modifier = Modifier.padding(horizontal = EDGE))
        if (!CodecOptions.option(capabilities, selected).selectable) {
            // A saved choice this phone cannot produce, after a restore or on a new phone.
            InfoNote(
                stringResource(
                    R.string.compression_unavailable_fallback,
                    codecChoiceLabel(selected),
                    codecDisplayName(CodecOptions.effectiveCodec(capabilities, selected)),
                ),
                warning = true,
                modifier = Modifier.padding(horizontal = EDGE),
            )
        }
    }
}

/** The three quality tiers as sideways-scrolling cards, each with a one-line trade-off from [descriptionRes]. */
@Composable
fun PresetPicker(
    selected: UploadCompressionTier,
    onSelect: (UploadCompressionTier) -> Unit,
    descriptionRes: (UploadCompressionTier) -> Int,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        FieldLabel(stringResource(R.string.compression_quality), Modifier.padding(horizontal = EDGE))
        Spacer(Modifier.height(8.dp))
        OptionRow(selectedIndex = UploadCompressionTier.entries.indexOf(selected)) {
            UploadCompressionTier.entries.forEach { tier ->
                OptionCard(
                    selected = tier == selected,
                    title = stringResource(tier.labelRes),
                    description = stringResource(descriptionRes(tier)),
                    onClick = { onSelect(tier) },
                )
            }
        }
    }
}

/** "About 36.5 MB per minute of 1080p video" for [profile]. */
@Composable
fun EstimateLine(capabilities: EncoderCapabilities?, profile: VideoCompressionProfile, modifier: Modifier = Modifier) {
    val estimate = capabilities?.let { CodecOptions.perMinute(it, profile) } ?: return
    Text(
        stringResource(R.string.compression_estimate, formatBytes(estimate.bytes), estimate.shortSide),
        color = AppColors.current.fgDim,
        fontSize = 12.5.sp,
        fontWeight = FontWeight.Medium,
        modifier = modifier,
    )
}

@Composable
private fun FieldLabel(text: String, modifier: Modifier = Modifier) {
    Text(text, color = AppColors.current.fgPrimary, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, modifier = modifier)
}

/** A quiet note with an info glyph; [warning] tints the glyph with the error colour. */
@Composable
private fun InfoNote(text: String, warning: Boolean = false, modifier: Modifier = Modifier) {
    val colors = AppColors.current
    Row(modifier = modifier.padding(top = 4.dp), verticalAlignment = Alignment.Top) {
        Icon(
            Icons.Outlined.Info,
            contentDescription = null,
            tint = if (warning) colors.errorColor else colors.fgMute,
            modifier = Modifier.padding(top = 1.dp).size(14.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(text, color = colors.fgDim, fontSize = 12.sp, lineHeight = 16.sp)
    }
}
