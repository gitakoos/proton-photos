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

package eu.akoos.photos.presentation.settings.appicon

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.AdaptiveIconDrawable
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.graphics.createBitmap
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import eu.akoos.photos.R
import eu.akoos.photos.presentation.common.ConfirmSheet
import eu.akoos.photos.presentation.common.ThemedSnackbarHost
import eu.akoos.photos.presentation.settings.components.RowDivider
import eu.akoos.photos.presentation.settings.components.SectionLabel
import eu.akoos.photos.presentation.settings.components.SettingsCard
import eu.akoos.photos.presentation.settings.components.SettingsSubPageScaffold
import eu.akoos.photos.presentation.settings.components.ToggleRow
import eu.akoos.photos.presentation.theme.AppColors

private val TileIconSize = 56.dp
private const val DISGUISE_TILES_PER_ROW = 3
private const val VARIANT_TILES_PER_ROW = 4

/**
 * Settings, Appearance, App icon: pick the launcher icon. Three disguises (Clock, Calculator, Notes)
 * that also rename the app on the home screen, then the app's own icon in its default and colour
 * variants. A tap opens a confirmation sheet, the change is applied on confirm, and a snackbar says
 * the launcher may need a moment. No account is needed, so guest mode sees the same page.
 */
@Composable
fun AppIconSettingsScreen(
    onBack: () -> Unit,
    viewModel: AppIconViewModel = hiltViewModel(),
) {
    val colors = AppColors.current
    val current by viewModel.current.collectAsStateWithLifecycle()
    val cutEntries by viewModel.cutEntries.collectAsStateWithLifecycle()
    var pending by remember { mutableStateOf<AppIcon?>(null) }
    val snackbarHost = remember { SnackbarHostState() }
    val changedMessage = stringResource(R.string.app_icon_changed)
    val failedMessage = stringResource(R.string.app_icon_failed)

    LaunchedEffect(viewModel) {
        viewModel.changed.collect { ok ->
            snackbarHost.showSnackbar(if (ok) changedMessage else failedMessage)
        }
    }

    Box(Modifier.fillMaxSize()) {
        SettingsSubPageScaffold(title = stringResource(R.string.settings_app_icon), onBack = onBack) {
            SectionLabel(stringResource(R.string.app_icon_section_preview))
            Spacer(Modifier.height(8.dp))
            PreviewCard(current)

            Spacer(Modifier.height(20.dp))
            SectionLabel(stringResource(R.string.app_icon_section_disguise))
            Spacer(Modifier.height(8.dp))
            SettingsCard {
                Text(
                    stringResource(R.string.app_icon_disguise_intro),
                    color = colors.fgDim,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp),
                )
                TileRow(AppIcon.disguises, DISGUISE_TILES_PER_ROW, current) { pending = it }
                RowDivider()
                ToggleRow(
                    label = stringResource(R.string.app_icon_cut_entries),
                    description = stringResource(R.string.app_icon_cut_entries_desc),
                    checked = cutEntries,
                    onCheckedChange = viewModel::setCutEntries,
                )
            }
            Spacer(Modifier.height(10.dp))
            Text(
                stringResource(R.string.app_icon_disguise_hint),
                color = colors.fgMute,
                fontSize = 12.sp,
                modifier = Modifier.padding(horizontal = 4.dp),
            )

            Spacer(Modifier.height(20.dp))
            SectionLabel(stringResource(R.string.app_name))
            Spacer(Modifier.height(8.dp))
            SettingsCard {
                AppIcon.variants.chunked(VARIANT_TILES_PER_ROW).forEach { row -> TileRow(row, VARIANT_TILES_PER_ROW, current) { pending = it } }
            }
            Spacer(Modifier.height(10.dp))
            Text(
                stringResource(R.string.app_icon_variants_hint),
                color = colors.fgMute,
                fontSize = 12.sp,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }

        ThemedSnackbarHost(
            snackbarHost,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 16.dp),
        )
    }

    pending?.let { target ->
        val name = stringResource(target.tileNameRes)
        val label = stringResource(target.labelRes)
        val appName = stringResource(R.string.app_name)
        val body = buildString {
            append(
                if (target.isDisguise) stringResource(R.string.app_icon_confirm_disguise, label, appName)
                else stringResource(R.string.app_icon_confirm_variant, name, appName),
            )
            append("\n\n").append(stringResource(R.string.app_icon_confirm_delay))
            append(' ').append(stringResource(R.string.app_icon_readd_hint))
            if (target.isDisguise) append("\n\n").append(stringResource(R.string.app_icon_confirm_still_visible, appName))
        }
        ConfirmSheet(
            title = stringResource(R.string.app_icon_confirm_title, name),
            message = body,
            confirmLabel = stringResource(R.string.app_icon_confirm_action),
            dismissLabel = stringResource(R.string.cancel),
            onConfirm = {
                pending = null
                viewModel.select(target)
            },
            onDismiss = { pending = null },
        )
    }
}

@Composable
private fun PreviewCard(icon: AppIcon) {
    val colors = AppColors.current
    SettingsCard {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            IconImage(icon, TileIconSize)
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    stringResource(icon.labelRes),
                    color = colors.fgPrimary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                val sub = when {
                    icon.isDisguise -> stringResource(R.string.app_icon_preview_disguise, stringResource(icon.tileNameRes))
                    icon == AppIcon.DEFAULT -> stringResource(R.string.app_icon_preview_default)
                    else -> stringResource(R.string.app_icon_preview_variant, stringResource(icon.tileNameRes))
                }
                Text(sub, color = colors.fgDim, fontSize = 12.5.sp)
            }
        }
    }
}

@Composable
private fun TileRow(icons: List<AppIcon>, columns: Int, current: AppIcon, onPick: (AppIcon) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        icons.forEach { icon ->
            Box(Modifier.weight(1f), contentAlignment = Alignment.TopCenter) {
                IconTile(icon, selected = icon == current, onClick = { if (icon != current) onPick(icon) })
            }
        }
        // A short last row keeps its tiles at the same width as the full rows above it.
        repeat((columns - icons.size).coerceAtLeast(0)) { Spacer(Modifier.weight(1f)) }
    }
}

@Composable
private fun IconTile(icon: AppIcon, selected: Boolean, onClick: () -> Unit) {
    val colors = AppColors.current
    val name = stringResource(icon.tileNameRes)
    val description = when {
        icon.isDisguise && selected -> stringResource(R.string.app_icon_a11y_disguise_selected, name)
        icon.isDisguise -> stringResource(R.string.app_icon_a11y_disguise, name)
        selected -> stringResource(R.string.app_icon_a11y_selected, name)
        else -> name
    }
    Column(
        modifier = Modifier
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp)
            .clearAndSetSemantics {
                contentDescription = description
                role = Role.Button
            },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(TileIconSize + 8.dp)) {
            Box(
                modifier = Modifier
                    .size(TileIconSize + 8.dp)
                    .border(
                        width = 2.dp,
                        color = if (selected) colors.accent else Color.Transparent,
                        shape = RoundedCornerShape(22.dp),
                    ),
            )
            IconImage(icon, TileIconSize)
            if (selected) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .size(20.dp)
                        .background(colors.accent, CircleShape)
                        .border(2.dp, colors.cardBg, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Default.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(11.dp))
                }
            }
        }
        Text(
            name,
            color = if (selected) colors.fgPrimary else colors.fgDim,
            fontSize = 11.5.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            textAlign = TextAlign.Center,
            maxLines = 2,
            modifier = Modifier.padding(horizontal = 2.dp),
        )
    }
}

/** The launcher icon as a rounded square, drawn from the same adaptive resource the launcher reads. */
@Composable
private fun IconImage(icon: AppIcon, size: Dp) {
    val context = LocalContext.current
    val px = with(LocalDensity.current) { size.roundToPx() }
    val bitmap = remember(icon, px) { renderAdaptiveIcon(context, icon.iconRes, px) }
    if (bitmap != null) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = null,
            modifier = Modifier.size(size).clip(RoundedCornerShape(size * 0.3f)),
        )
    } else {
        Box(Modifier.size(size).clip(RoundedCornerShape(size * 0.3f)).background(AppColors.current.line2))
    }
}

/**
 * Draws an adaptive icon's two layers on the full 108dp canvas and keeps the central 72dp, which is
 * what any launcher mask shows. The layers are drawn directly rather than through the drawable so the
 * tile is one rounded square for every icon instead of the device's own mask shape.
 */
private fun renderAdaptiveIcon(context: Context, res: Int, sizePx: Int): Bitmap? {
    val drawable = ContextCompat.getDrawable(context, res) as? AdaptiveIconDrawable ?: return null
    val full = (sizePx * 108f / 72f).toInt()
    val canvasBitmap = createBitmap(full, full)
    val canvas = Canvas(canvasBitmap)
    drawable.background?.apply { setBounds(0, 0, full, full); draw(canvas) }
    drawable.foreground?.apply { setBounds(0, 0, full, full); draw(canvas) }
    val offset = (full - sizePx) / 2
    return Bitmap.createBitmap(canvasBitmap, offset, offset, sizePx, sizePx)
}
