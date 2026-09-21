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

package eu.akoos.photos.presentation.whatsnew

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.LocalCafe
import androidx.compose.material.icons.filled.Public
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import eu.akoos.photos.R
import eu.akoos.photos.presentation.theme.AppColors
import eu.akoos.photos.presentation.theme.AppColorsTokens

// Discord brand blurple, kept as a literal so the mark reads as Discord regardless of the app palette.
private val DiscordBlurple = Color(0xFF9098F8)

/**
 * The one-time 2.5.0 thank-you popup: a heart mark, a short personal letter, links to Discord and the
 * website, and a Buy-me-a-coffee button. Dismissible by the X, a back press, or a tap outside.
 * [onOpenUrl] opens a link in the browser; the URLs live here, matching the About screen.
 */
@Composable
fun ThankYouDialog(
    onDismiss: () -> Unit,
    onOpenUrl: (String) -> Unit,
) {
    val colors = AppColors.current
    // The card grows with its text, up to most of the screen, so the letter shows in full where it fits.
    val maxCardHeight = (LocalConfiguration.current.screenHeightDp * 0.9f).dp
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnClickOutside = true,
            dismissOnBackPress = true,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .heightIn(max = maxCardHeight)
                .background(colors.cardBg, RoundedCornerShape(26.dp))
                .border(1.dp, colors.cardBorder, RoundedCornerShape(26.dp))
                .padding(start = 22.dp, end = 22.dp, top = 8.dp, bottom = 22.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Top-right dismiss.
            Box(modifier = Modifier.fillMaxWidth()) {
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.align(Alignment.CenterEnd),
                ) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = stringResource(R.string.thanks_2_5_0_dismiss),
                        tint = colors.fgMute,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }

            // Heart mark.
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .background(colors.accent.copy(alpha = 0.16f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Default.Favorite,
                    contentDescription = null,
                    tint = colors.accent,
                    modifier = Modifier.size(26.dp),
                )
            }

            Spacer(Modifier.height(14.dp))
            Text(
                stringResource(R.string.thanks_2_5_0_title),
                color = colors.fgPrimary,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(14.dp))

            // The letter scrolls on its own when the card hits its height cap; a soft bottom fade shows
            // while there is more to read, so it reads as scrollable without a scrollbar.
            val letterScroll = rememberScrollState()
            Box(modifier = Modifier.weight(1f, fill = false)) {
                Column(
                    modifier = Modifier.verticalScroll(letterScroll),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        stringResource(R.string.thanks_2_5_0_greeting),
                        color = colors.fgPrimary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center,
                        lineHeight = 21.sp,
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        stringResource(R.string.thanks_2_5_0_body),
                        color = colors.fgDim,
                        fontSize = 13.5.sp,
                        textAlign = TextAlign.Center,
                        lineHeight = 20.sp,
                    )
                }
                if (letterScroll.canScrollForward) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .height(28.dp)
                            .background(
                                Brush.verticalGradient(
                                    listOf(Color.Transparent, colors.cardBg),
                                ),
                            ),
                    )
                }
            }

            Spacer(Modifier.height(18.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(9.dp),
            ) {
                CompactActionButton(
                    modifier = Modifier.weight(1f),
                    colors = colors,
                    label = stringResource(R.string.thanks_2_5_0_discord),
                    onClick = { onOpenUrl("https://discord.gg/9dBfxpVENq") },
                ) {
                    Icon(
                        painterResource(R.drawable.ic_discord),
                        contentDescription = null,
                        tint = DiscordBlurple,
                        modifier = Modifier.size(18.dp),
                    )
                }
                CompactActionButton(
                    modifier = Modifier.weight(1f),
                    colors = colors,
                    label = stringResource(R.string.thanks_2_5_0_website),
                    onClick = { onOpenUrl("https://www.photosforproton.eu") },
                ) {
                    Icon(
                        Icons.Default.Public,
                        contentDescription = null,
                        tint = colors.fgDim,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }

            Spacer(Modifier.height(9.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(colors.accent.copy(alpha = 0.16f), RoundedCornerShape(15.dp))
                    .border(1.dp, colors.accent, RoundedCornerShape(15.dp))
                    .clickable { onOpenUrl("https://www.buymeacoffee.com/akoos") }
                    .padding(vertical = 13.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Default.LocalCafe,
                    contentDescription = null,
                    tint = colors.accent,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    stringResource(R.string.thanks_2_5_0_coffee),
                    color = colors.accent,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

/** One compact link button: a leading [icon] and a [label] in a subtle rounded surface. */
@Composable
private fun CompactActionButton(
    modifier: Modifier,
    colors: AppColorsTokens,
    label: String,
    onClick: () -> Unit,
    icon: @Composable () -> Unit,
) {
    Row(
        modifier = modifier
            .background(colors.cardBorder, RoundedCornerShape(13.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 11.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        icon()
        Spacer(Modifier.width(7.dp))
        Text(
            label,
            color = colors.fgPrimary,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}
