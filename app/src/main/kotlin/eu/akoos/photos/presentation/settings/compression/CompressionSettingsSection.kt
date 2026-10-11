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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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
import eu.akoos.photos.presentation.theme.SheetBg
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

/** Side padding of everything in a picker; the pickers sit flush in their card and pad themselves. */
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

/** The fuller account of a codec, for the info drawer: what it is good for and where it plays. */
@Composable
private fun codecInfo(choice: VideoCodecChoice): String = stringResource(
    when (choice) {
        VideoCodecChoice.AUTO -> R.string.compression_codec_desc_auto
        VideoCodecChoice.KEEP_SOURCE -> R.string.compression_hint_keep
        VideoCodecChoice.AVC -> R.string.compression_hint_avc
        VideoCodecChoice.HEVC -> R.string.compression_hint_hevc
        VideoCodecChoice.AV1 -> R.string.compression_hint_av1
    },
)

/** A bottom drawer in the app's usual sheet style, with a title and a scrollable body. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ChoiceSheet(title: String, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val colors = AppColors.current
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = SheetBg,
        scrimColor = Color.Black.copy(alpha = 0.5f),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 24.dp),
        ) {
            Text(title, color = colors.fgPrimary, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(12.dp))
            content()
        }
    }
}

/** A title, a short description and an optional detail line; the body of both the page card and a list row. */
@Composable
private fun ChoiceText(
    title: String,
    description: String,
    enabled: Boolean,
    emphasised: Boolean,
    detail: @Composable (() -> Unit)?,
) {
    val colors = AppColors.current
    Column {
        Text(
            title,
            color = if (enabled) colors.fgPrimary else colors.fgMute,
            fontSize = 15.sp,
            fontWeight = if (emphasised) FontWeight.SemiBold else FontWeight.Medium,
        )
        Spacer(Modifier.height(2.dp))
        Text(description, color = if (enabled) colors.fgDim else colors.fgMute, fontSize = 13.sp, lineHeight = 18.sp)
        if (detail != null) {
            Spacer(Modifier.height(6.dp))
            detail()
        }
    }
}

/**
 * The current choice, shown on the page: tapping it opens the list. It carries the same title,
 * description and detail a list row does, so the page says what is chosen without opening anything.
 */
@Composable
internal fun CurrentChoice(
    title: String,
    description: String,
    detail: @Composable (() -> Unit)?,
    onClick: () -> Unit,
) {
    val colors = AppColors.current
    val shape = RoundedCornerShape(12.dp)
    Row(
        modifier = Modifier
            .padding(horizontal = EDGE)
            .fillMaxWidth()
            .clip(shape)
            .background(colors.surfaceWeak)
            .border(0.5.dp, colors.line2, shape)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(start = 14.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.weight(1f)) {
            ChoiceText(title, description, enabled = true, emphasised = true, detail = detail)
        }
        Icon(Icons.Default.ChevronRight, contentDescription = null, tint = colors.fgMute, modifier = Modifier.size(22.dp))
    }
}

/**
 * One row of a list drawer. The chosen row sits in an accent card with a check mark; the others are plain
 * rows, and one the phone cannot use stays in the list, dimmed, with its reason.
 */
@Composable
internal fun ChoiceRow(
    selected: Boolean,
    enabled: Boolean,
    title: String,
    description: String,
    detail: @Composable (() -> Unit)?,
    onClick: () -> Unit,
) {
    val colors = AppColors.current
    val shape = RoundedCornerShape(12.dp)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
            .clip(shape)
            .then(
                if (selected) {
                    Modifier
                        .background(colors.accent.copy(alpha = 0.16f))
                        .border(1.5.dp, colors.accent, shape)
                } else {
                    Modifier
                },
            )
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick),
    ) {
        Box(modifier = Modifier.padding(start = 14.dp, end = 44.dp, top = 12.dp, bottom = 12.dp)) {
            ChoiceText(title, description, enabled, emphasised = selected, detail = detail)
        }
        if (selected) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 14.dp)
                    .size(22.dp)
                    .background(colors.accent, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Default.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(15.dp))
            }
        }
    }
}

/** The thin line between two options of a list; left out beside the chosen option, whose card has its own border. */
@Composable
internal fun ChoiceDivider() {
    HorizontalDivider(thickness = 0.5.dp, color = AppColors.current.cardBorder)
}

/** The hardware or software dot and label under a codec, or the reason it is unavailable. */
@Composable
private fun codecDetail(option: CodecOption): @Composable (() -> Unit)? {
    val label = badgeLabel(option) ?: return null
    val colors = AppColors.current
    return {
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
}

/**
 * The video codec: the current choice with an info mark beside the label. Tapping the choice opens a list
 * drawer; the info mark opens a drawer that explains each codec, so the page itself stays short. An option
 * this phone cannot use stays in the list, dimmed, with the reason.
 */
@Composable
fun CodecPicker(
    selected: VideoCodecChoice,
    capabilities: EncoderCapabilities?,
    onSelect: (VideoCodecChoice) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = AppColors.current
    var listOpen by rememberSaveable { mutableStateOf(false) }
    var infoOpen by rememberSaveable { mutableStateOf(false) }
    Column(modifier = modifier) {
        Row(modifier = Modifier.padding(horizontal = EDGE), verticalAlignment = Alignment.CenterVertically) {
            FieldLabel(stringResource(R.string.compression_codec))
            Box(
                modifier = Modifier
                    .padding(start = 2.dp)
                    .size(36.dp)
                    .clip(CircleShape)
                    .clickable(role = Role.Button, onClick = { infoOpen = true }),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Outlined.Info,
                    contentDescription = stringResource(R.string.compression_codec_info_title),
                    tint = colors.fgMute,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
        if (capabilities == null) {
            // The one-off scan has not finished; show the choice without badges for that moment.
            Text(
                codecChoiceLabel(selected),
                color = colors.fgDim,
                fontSize = 13.sp,
                modifier = Modifier.padding(horizontal = EDGE),
            )
        } else {
            val options = CodecOptions.options(capabilities)
            val current = CodecOptions.option(capabilities, selected)
            CurrentChoice(
                title = codecChoiceLabel(selected),
                description = codecDescription(selected),
                detail = codecDetail(current),
                onClick = { listOpen = true },
            )
            if (!current.selectable) {
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
            if (listOpen) {
                ChoiceSheet(title = stringResource(R.string.compression_codec), onDismiss = { listOpen = false }) {
                    Column(modifier = Modifier.selectableGroup()) {
                        options.forEachIndexed { index, option ->
                            ChoiceRow(
                                selected = option.choice == selected,
                                enabled = option.selectable,
                                title = codecChoiceLabel(option.choice),
                                description = codecDescription(option.choice),
                                detail = codecDetail(option),
                                onClick = {
                                    listOpen = false
                                    onSelect(option.choice)
                                },
                            )
                            if (index < options.lastIndex && option.choice != selected && options[index + 1].choice != selected) {
                                ChoiceDivider()
                            }
                        }
                    }
                }
            }
        }
        if (infoOpen) {
            ChoiceSheet(title = stringResource(R.string.compression_codec_info_title), onDismiss = { infoOpen = false }) {
                val choices = listOf(
                    VideoCodecChoice.AUTO,
                    VideoCodecChoice.AVC,
                    VideoCodecChoice.HEVC,
                    VideoCodecChoice.AV1,
                    VideoCodecChoice.KEEP_SOURCE,
                )
                choices.forEachIndexed { index, choice ->
                    Column(modifier = Modifier.padding(vertical = 12.dp)) {
                        Text(
                            codecChoiceLabel(choice),
                            color = colors.fgPrimary,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(codecInfo(choice), color = colors.fgDim, fontSize = 13.5.sp, lineHeight = 19.sp)
                    }
                    if (index < choices.lastIndex) ChoiceDivider()
                }
            }
        }
    }
}

/** The quality tier: the current choice on the page, a list drawer behind it, each with its one-line trade-off. */
@Composable
fun PresetPicker(
    selected: UploadCompressionTier,
    onSelect: (UploadCompressionTier) -> Unit,
    descriptionRes: (UploadCompressionTier) -> Int,
    modifier: Modifier = Modifier,
) {
    var listOpen by rememberSaveable { mutableStateOf(false) }
    Column(modifier = modifier) {
        FieldLabel(stringResource(R.string.compression_quality), Modifier.padding(horizontal = EDGE))
        Spacer(Modifier.height(8.dp))
        CurrentChoice(
            title = stringResource(selected.labelRes),
            description = stringResource(descriptionRes(selected)),
            detail = null,
            onClick = { listOpen = true },
        )
        if (listOpen) {
            ChoiceSheet(title = stringResource(R.string.compression_quality), onDismiss = { listOpen = false }) {
                Column(modifier = Modifier.selectableGroup()) {
                    val tiers = UploadCompressionTier.entries
                    tiers.forEachIndexed { index, tier ->
                        ChoiceRow(
                            selected = tier == selected,
                            enabled = true,
                            title = stringResource(tier.labelRes),
                            description = stringResource(descriptionRes(tier)),
                            detail = null,
                            onClick = {
                                listOpen = false
                                onSelect(tier)
                            },
                        )
                        if (index < tiers.lastIndex && tier != selected && tiers[index + 1] != selected) ChoiceDivider()
                    }
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
private fun FieldLabel(text: String, modifier: Modifier = Modifier) {
    Text(text, color = AppColors.current.fgPrimary, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, modifier = modifier)
}

/** A quiet note with an info glyph; [warning] tints the glyph with the error colour. */
@Composable
private fun InfoNote(text: String, warning: Boolean = false, modifier: Modifier = Modifier) {
    val colors = AppColors.current
    Row(modifier = modifier.padding(top = 6.dp), verticalAlignment = Alignment.Top) {
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
