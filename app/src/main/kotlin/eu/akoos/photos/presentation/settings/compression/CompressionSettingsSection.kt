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
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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

/**
 * The video codec chips, then a compatibility note for the codec that will actually be produced. An
 * option this phone can't use stays visible, greyed, with the reason on the chip.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CodecPicker(
    selected: VideoCodecChoice,
    capabilities: EncoderCapabilities?,
    onSelect: (VideoCodecChoice) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = AppColors.current
    Column(modifier = modifier) {
        FieldLabel(stringResource(R.string.compression_codec))
        Spacer(Modifier.height(8.dp))
        if (capabilities == null) {
            // The one-off scan has not finished; show the choice without badges for that moment.
            Text(codecChoiceLabel(selected), color = colors.fgDim, fontSize = 13.sp)
            return@Column
        }
        FlowRow(
            modifier = Modifier.selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            CodecOptions.options(capabilities).forEach { option ->
                CodecChip(option = option, selected = option.choice == selected, onClick = { onSelect(option.choice) })
            }
        }
        Spacer(Modifier.height(10.dp))
        val hint = when {
            selected == VideoCodecChoice.KEEP_SOURCE -> stringResource(R.string.compression_hint_keep)
            else -> when (CodecOptions.effectiveCodec(capabilities, selected)) {
                VideoCodec.AVC -> stringResource(R.string.compression_hint_avc)
                VideoCodec.HEVC -> stringResource(R.string.compression_hint_hevc)
                VideoCodec.AV1 -> stringResource(R.string.compression_hint_av1)
            }
        }
        InfoNote(hint)
        if (!CodecOptions.option(capabilities, selected).selectable) {
            // A saved choice this phone can't produce, after a restore or on a new phone.
            InfoNote(
                stringResource(
                    R.string.compression_unavailable_fallback,
                    codecChoiceLabel(selected),
                    codecDisplayName(CodecOptions.effectiveCodec(capabilities, selected)),
                ),
                warning = true,
            )
        }
    }
}

@Composable
private fun CodecChip(option: CodecOption, selected: Boolean, onClick: () -> Unit) {
    val colors = AppColors.current
    val shape = RoundedCornerShape(12.dp)
    val badge = badgeLabel(option)
    Column(
        modifier = Modifier
            .clip(shape)
            .background(if (selected) colors.accent.copy(alpha = 0.16f) else colors.surfaceWeak)
            .border(
                width = if (selected) 1.5.dp else 0.5.dp,
                color = if (selected) colors.accent else colors.line2,
                shape = shape,
            )
            .selectable(selected = selected, enabled = option.selectable, role = Role.RadioButton, onClick = onClick)
            .heightIn(min = 56.dp)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            codecChoiceLabel(option.choice),
            // Only the name dims for an unavailable option; the reason under it stays readable.
            color = if (option.selectable) colors.fgPrimary else colors.fgMute,
            fontSize = 14.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
        )
        if (badge != null) {
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
                            }
                        )
                )
                Spacer(Modifier.width(5.dp))
                Text(badge, color = colors.fgDim, fontSize = 11.5.sp)
            }
        }
    }
}

/** The three quality tiers side by side, each with a one-line trade-off from [descriptionRes]. */
@Composable
fun PresetPicker(
    selected: UploadCompressionTier,
    onSelect: (UploadCompressionTier) -> Unit,
    descriptionRes: (UploadCompressionTier) -> Int,
    modifier: Modifier = Modifier,
) {
    val colors = AppColors.current
    Column(modifier = modifier) {
        FieldLabel(stringResource(R.string.compression_quality))
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min).selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            UploadCompressionTier.entries.forEach { tier ->
                val on = tier == selected
                val shape = RoundedCornerShape(12.dp)
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(shape)
                        .background(if (on) colors.accent.copy(alpha = 0.16f) else colors.surfaceWeak)
                        .border(if (on) 1.5.dp else 0.5.dp, if (on) colors.accent else colors.line2, shape)
                        .selectable(selected = on, role = Role.RadioButton, onClick = { onSelect(tier) })
                        .padding(horizontal = 10.dp, vertical = 10.dp),
                ) {
                    Text(
                        stringResource(tier.labelRes),
                        color = colors.fgPrimary,
                        fontSize = 14.sp,
                        fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium,
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(stringResource(descriptionRes(tier)), color = colors.fgDim, fontSize = 12.sp, lineHeight = 15.sp)
                }
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
private fun FieldLabel(text: String) {
    Text(text, color = AppColors.current.fgPrimary, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold)
}

/** A quiet note with an info glyph; [warning] tints the glyph with the error colour. */
@Composable
private fun InfoNote(text: String, warning: Boolean = false) {
    val colors = AppColors.current
    Row(modifier = Modifier.padding(top = 4.dp), verticalAlignment = Alignment.Top) {
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
