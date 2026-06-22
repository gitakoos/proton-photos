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

package eu.akoos.photos.presentation.settings

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import eu.akoos.photos.BuildConfig
import eu.akoos.photos.R
import eu.akoos.photos.presentation.common.IconBubble
import eu.akoos.photos.presentation.settings.components.RowDivider
import eu.akoos.photos.presentation.settings.components.SectionLabel
import eu.akoos.photos.presentation.theme.AppColors

@Composable
fun AboutScreen(
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val colors = AppColors.current

    fun openUrl(url: String) {
        val intent = Intent(Intent.ACTION_VIEW, url.toUri())
        runCatching { context.startActivity(intent) }
    }

    Box(modifier = Modifier.fillMaxSize().background(colors.pageBg)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp),
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 24.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                IconBubble(
                    icon = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.onboarding_back),
                    onClick = onBack,
                    diameter = 40.dp,
                    iconSize = 16.dp,
                    background = colors.surfaceWeak,
                    borderColor = colors.pillBorder,
                    tint = colors.fgDim,
                )
                Text(
                    stringResource(R.string.about_title),
                    color = colors.fgPrimary,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }

            // App identity
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(colors.cardBg, RoundedCornerShape(12.dp))
                    .border(0.5.dp, colors.cardBorder, RoundedCornerShape(12.dp))
                    .padding(horizontal = 16.dp, vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // Quoted "Proton" mirrors the sign-in screen — visual cue that this
                // is a third-party client even when seen out of context (e.g. a screenshot).
                Text(
                    stringResource(R.string.about_app_title_quoted),
                    color = colors.fgPrimary,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    stringResource(
                        R.string.about_version,
                        BuildConfig.VERSION_NAME,
                        BuildConfig.VERSION_CODE,
                    ),
                    color = colors.fgDim,
                    fontSize = 13.sp,
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    stringResource(R.string.about_description),
                    color = colors.fgDim,
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                )
            }

            Spacer(Modifier.height(24.dp))

            // Author
            SectionLabel(stringResource(R.string.about_section_author))
            Spacer(Modifier.height(8.dp))
            LinkCard(
                colors = colors,
                title = "Akoos",
                subtitle = stringResource(R.string.about_author_subtitle),
                onClick = { openUrl("https://akoos.eu") },
            )

            Spacer(Modifier.height(24.dp))

            // Source code
            SectionLabel(stringResource(R.string.about_section_source))
            Spacer(Modifier.height(8.dp))
            LinkCard(
                colors = colors,
                title = "gitakoos/proton-photos",
                subtitle = stringResource(R.string.about_source_subtitle),
                onClick = { openUrl("https://github.com/gitakoos/proton-photos") },
            )

            Spacer(Modifier.height(24.dp))

            // License
            SectionLabel(stringResource(R.string.about_section_license))
            Spacer(Modifier.height(8.dp))
            LinkCard(
                colors = colors,
                title = stringResource(R.string.about_license_title),
                subtitle = stringResource(R.string.about_license_subtitle),
                onClick = { openUrl("https://github.com/gitakoos/proton-photos/blob/main/LICENSE") },
            )

            Spacer(Modifier.height(24.dp))

            // Built with
            SectionLabel(stringResource(R.string.about_section_built_with))
            Spacer(Modifier.height(8.dp))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(colors.cardBg, RoundedCornerShape(12.dp))
                    .border(0.5.dp, colors.cardBorder, RoundedCornerShape(12.dp)),
            ) {
                LinkRow(
                    colors = colors,
                    title = "ProtonDriveApps/android-drive",
                    subtitle = stringResource(R.string.about_lib_android_drive),
                    onClick = { openUrl("https://github.com/ProtonDriveApps/android-drive") },
                )
                RowDivider()
                LinkRow(
                    colors = colors,
                    title = "ProtonMail/protoncore_android",
                    subtitle = stringResource(R.string.about_lib_protoncore),
                    onClick = { openUrl("https://github.com/ProtonMail/protoncore_android") },
                )
                RowDivider()
                LinkRow(
                    colors = colors,
                    title = "Jetpack Compose",
                    subtitle = stringResource(R.string.about_lib_compose),
                    onClick = { openUrl("https://developer.android.com/jetpack/compose") },
                )
                RowDivider()
                LinkRow(
                    colors = colors,
                    title = "Hilt",
                    subtitle = stringResource(R.string.about_lib_hilt),
                    onClick = { openUrl("https://dagger.dev/hilt/") },
                )
                RowDivider()
                LinkRow(
                    colors = colors,
                    title = "Coil",
                    subtitle = stringResource(R.string.about_lib_coil),
                    onClick = { openUrl("https://coil-kt.github.io/coil/") },
                )
                RowDivider()
                LinkRow(
                    colors = colors,
                    title = "Room",
                    subtitle = stringResource(R.string.about_lib_room),
                    onClick = { openUrl("https://developer.android.com/training/data-storage/room") },
                )
                RowDivider()
                LinkRow(
                    colors = colors,
                    title = "GoOpenPGP",
                    subtitle = stringResource(R.string.about_lib_gopenpgp),
                    onClick = { openUrl("https://github.com/ProtonMail/gopenpgp") },
                )
                RowDivider()
                LinkRow(
                    colors = colors,
                    title = "GeoNames",
                    subtitle = stringResource(R.string.about_lib_geonames),
                    onClick = { openUrl("https://www.geonames.org/") },
                )
                RowDivider()
                LinkRow(
                    colors = colors,
                    title = "osmdroid",
                    subtitle = stringResource(R.string.about_lib_osmdroid),
                    onClick = { openUrl("https://github.com/osmdroid/osmdroid") },
                )
                RowDivider()
                LinkRow(
                    colors = colors,
                    title = "OpenStreetMap",
                    subtitle = stringResource(R.string.about_lib_osm),
                    onClick = { openUrl("https://www.openstreetmap.org/copyright") },
                )
            }

            Spacer(Modifier.height(24.dp))

            // Disclaimer
            SectionLabel(stringResource(R.string.about_section_disclaimer))
            Spacer(Modifier.height(8.dp))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(colors.cardBg, RoundedCornerShape(12.dp))
                    .border(0.5.dp, colors.cardBorder, RoundedCornerShape(12.dp))
                    .padding(horizontal = 16.dp, vertical = 14.dp),
            ) {
                Text(
                    stringResource(R.string.about_disclaimer),
                    color = colors.fgDim,
                    fontSize = 12.5.sp,
                    lineHeight = 17.sp,
                )
            }
        }
    }
}

@Composable
private fun LinkCard(
    colors: eu.akoos.photos.presentation.theme.AppColorsTokens,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.cardBg, RoundedCornerShape(12.dp))
            .border(0.5.dp, colors.cardBorder, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = colors.fgPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Text(subtitle, color = colors.fgMute, fontSize = 12.sp)
        }
        Icon(
            Icons.AutoMirrored.Filled.OpenInNew,
            null,
            tint = colors.fgMute,
            modifier = Modifier.size(16.dp),
        )
    }
}

@Composable
private fun LinkRow(
    colors: eu.akoos.photos.presentation.theme.AppColorsTokens,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = colors.fgPrimary, fontSize = 13.5.sp, fontWeight = FontWeight.Medium)
            Text(subtitle, color = colors.fgMute, fontSize = 11.5.sp)
        }
        Icon(
            Icons.AutoMirrored.Filled.OpenInNew,
            null,
            tint = colors.fgMute,
            modifier = Modifier.size(14.dp),
        )
    }
}
