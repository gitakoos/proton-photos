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

package eu.akoos.photos.widget

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import dagger.hilt.android.AndroidEntryPoint
import eu.akoos.photos.R
import eu.akoos.photos.data.preferences.SettingsKeys
import eu.akoos.photos.data.preferences.settingsDataStore
import eu.akoos.photos.domain.entity.LocalAlbum
import eu.akoos.photos.presentation.settings.ThemeMode
import eu.akoos.photos.presentation.settings.ThemePalette
import eu.akoos.photos.presentation.theme.AppColors
import eu.akoos.photos.presentation.theme.ProtonPhotosTheme
import kotlinx.coroutines.flow.map

@AndroidEntryPoint
class PhotoWidgetConfigActivity : ComponentActivity() {

    private var appWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Default to CANCELED so pressing Back doesn't add the widget.
        setResult(Activity.RESULT_CANCELED)

        appWidgetId = intent
            ?.extras
            ?.getInt(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
            ?: AppWidgetManager.INVALID_APPWIDGET_ID

        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }

        setContent {
            // Mirror MainActivity: respect the user's THEME_MODE + THEME_PALETTE so the
            // widget config screen renders in the same theme/accent as the rest of the app.
            val themeKeyFlow = remember {
                settingsDataStore.data.map { prefs ->
                    prefs[SettingsKeys.THEME_MODE]
                        ?: when (prefs[SettingsKeys.DARK_MODE]) {
                            true  -> "dark"
                            false -> "light"
                            null  -> "dark"
                        }
                }
            }
            val themeKey by themeKeyFlow.collectAsState(initial = "dark")
            val themeMode = ThemeMode.fromKey(themeKey)
            val systemDark = isSystemInDarkTheme()
            val useDark = when (themeMode) {
                ThemeMode.System -> systemDark
                ThemeMode.Light  -> false
                ThemeMode.Dark   -> true
            }

            val paletteFlow = remember {
                settingsDataStore.data.map { it[SettingsKeys.THEME_PALETTE] }
            }
            val paletteKey by paletteFlow.collectAsState(initial = null)
            val palette = ThemePalette.fromKey(paletteKey)

            ProtonPhotosTheme(darkTheme = useDark, palette = palette) {
                val viewModel: PhotoWidgetConfigViewModel = hiltViewModel()
                val state by viewModel.state.collectAsStateWithLifecycle()

                // Pre-fill the form from the widget's existing Glance state. Lets the
                // user re-edit a placed widget instead of remove + re-add.
                LaunchedEffect(appWidgetId) { viewModel.loadFor(appWidgetId) }

                LaunchedEffect(state.saved) {
                    if (state.saved) {
                        setResult(
                            Activity.RESULT_OK,
                            Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId),
                        )
                        finish()
                    }
                }

                WidgetConfigScreen(
                    state    = state,
                    onMode   = viewModel::setMode,
                    onInterval = viewModel::setInterval,
                    onUris   = viewModel::setSelectedUris,
                    onAlbum  = viewModel::setAlbum,
                    onCloudSelection = viewModel::setSelectedLinkIds,
                    onRequestCloudThumb = viewModel::requestCloudThumbnailDecrypt,
                    onSave   = { viewModel.save(appWidgetId) },
                    onCancel = { finish() },
                )
            }
        }
    }
}

// ── Config screen ─────────────────────────────────────────────────────────────

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WidgetConfigScreen(
    state: WidgetConfigUiState,
    onMode: (WidgetMode) -> Unit,
    onInterval: (WidgetInterval) -> Unit,
    onUris: (List<String>) -> Unit,
    onAlbum: (String) -> Unit,
    onCloudSelection: (List<String>) -> Unit,
    onRequestCloudThumb: (eu.akoos.photos.data.db.entity.PhotoListingEntity) -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
) {
    val colors = AppColors.current
    // Two-step wizard: step 0 picks the source type, step 1 picks the matching content and the
    // refresh interval. Keeping the (potentially huge) photo / album lists on their own page with
    // a fixed bottom bar means the user never has to scroll past everything to reach the actions.
    var step by remember { mutableStateOf(0) }

    // Photo picker launcher (system picker — used by SELECTED mode).
    val photoPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetMultipleContents(),
    ) { uris ->
        if (uris.isNotEmpty()) onUris(uris.map { it.toString() })
    }

    val canSave = when (state.mode) {
        WidgetMode.ALL_PHOTOS     -> true
        WidgetMode.SELECTED       -> state.selectedUris.isNotEmpty()
        WidgetMode.ALBUM          -> state.selectedAlbum != null
        WidgetMode.CLOUD_SELECTED -> state.selectedLinkIds.isNotEmpty()
    }
    // Leave room at the bottom of every scroll area so the floating pill never covers content.
    val bottomInset = 112.dp

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.pageBg),
    ) {
        if (step == 0) {
            // ── STEP 1 — pick the source type ──────────────────────────────
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding(),
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 24.dp, bottom = bottomInset),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                item {
                    Text(
                        stringResource(R.string.widget_setup_title),
                        color = colors.fgPrimary,
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        stringResource(R.string.widget_setup_subtitle),
                        color = colors.fgDim,
                        fontSize = 14.sp,
                    )
                }
                item {
                    SectionLabel(stringResource(R.string.widget_mode_section))
                    Spacer(Modifier.height(10.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        ModeOption(
                            title       = stringResource(R.string.widget_mode_all),
                            description = stringResource(R.string.widget_mode_all_desc),
                            selected    = state.mode == WidgetMode.ALL_PHOTOS,
                            onClick     = { onMode(WidgetMode.ALL_PHOTOS) },
                        )
                        ModeOption(
                            title       = stringResource(R.string.widget_mode_selected),
                            description = stringResource(R.string.widget_mode_selected_desc),
                            selected    = state.mode == WidgetMode.SELECTED,
                            onClick     = { onMode(WidgetMode.SELECTED) },
                        )
                        ModeOption(
                            title       = stringResource(R.string.widget_mode_album),
                            description = stringResource(R.string.widget_mode_album_desc),
                            selected    = state.mode == WidgetMode.ALBUM,
                            onClick     = { onMode(WidgetMode.ALBUM) },
                        )
                        ModeOption(
                            title       = stringResource(R.string.widget_mode_cloud),
                            description = stringResource(R.string.widget_mode_cloud_desc),
                            selected    = state.mode == WidgetMode.CLOUD_SELECTED,
                            onClick     = { onMode(WidgetMode.CLOUD_SELECTED) },
                        )
                    }
                }
            }
        } else {
            // ── STEP 2 — pick content + interval for the chosen type ───────
            Column(modifier = Modifier.fillMaxSize()) {
                // Fixed header: interval picker + the section heading for this mode.
                Column(
                    modifier = Modifier
                        .statusBarsPadding()
                        .padding(horizontal = 20.dp)
                        .padding(top = 20.dp, bottom = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    SectionLabel(stringResource(R.string.widget_interval_section))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement   = Arrangement.spacedBy(8.dp),
                    ) {
                        WidgetInterval.entries.forEach { interval ->
                            IntervalChip(
                                label    = stringResource(interval.labelRes),
                                selected = state.interval == interval,
                                onClick  = { onInterval(interval) },
                            )
                        }
                    }
                    when (state.mode) {
                        WidgetMode.CLOUD_SELECTED -> {
                            Spacer(Modifier.height(4.dp))
                            SectionLabel(stringResource(R.string.widget_section_choose_cloud_photos))
                            if (state.selectedLinkIds.isNotEmpty()) {
                                Text(
                                    stringResource(R.string.widget_cloud_photos_selected, state.selectedLinkIds.size),
                                    color = colors.fgDim, fontSize = 13.sp,
                                )
                            }
                        }
                        WidgetMode.ALBUM -> {
                            Spacer(Modifier.height(4.dp))
                            SectionLabel(stringResource(R.string.widget_section_choose_album))
                        }
                        WidgetMode.SELECTED -> {
                            Spacer(Modifier.height(4.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                SectionLabel(stringResource(R.string.widget_mode_selected))
                                Box(
                                    modifier = Modifier
                                        .background(colors.surfaceWeak, RoundedCornerShape(8.dp))
                                        .border(0.5.dp, colors.pillBorder, RoundedCornerShape(8.dp))
                                        .clickable { photoPicker.launch("image/*") }
                                        .padding(horizontal = 14.dp, vertical = 8.dp),
                                ) {
                                    Text(
                                        if (state.selectedUris.isEmpty())
                                            stringResource(R.string.widget_select_photos)
                                        else
                                            stringResource(R.string.widget_change_selection),
                                        color = colors.accent, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                                    )
                                }
                            }
                            if (state.selectedUris.isNotEmpty()) {
                                Text(
                                    stringResource(R.string.widget_photos_selected, state.selectedUris.size),
                                    color = colors.fgDim, fontSize = 13.sp,
                                )
                            }
                        }
                        WidgetMode.ALL_PHOTOS -> {}
                    }
                }

                // Scrollable, virtualised content for this mode — fills the space above the pill.
                when (state.mode) {
                    WidgetMode.ALL_PHOTOS -> {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth()
                                .padding(horizontal = 32.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                stringResource(R.string.widget_mode_all_desc),
                                color = colors.fgMute, fontSize = 14.sp,
                            )
                        }
                    }
                    WidgetMode.CLOUD_SELECTED -> {
                        if (state.cloudPhotos.isEmpty()) {
                            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                                Text(
                                    stringResource(R.string.widget_no_cloud_photos),
                                    color = colors.fgMute, fontSize = 14.sp,
                                )
                            }
                        } else {
                            LazyVerticalGrid(
                                columns = GridCells.Fixed(4),
                                modifier = Modifier.weight(1f).fillMaxWidth(),
                                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = bottomInset),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                gridItems(state.cloudPhotos, key = { it.linkId }) { photo ->
                                    val isSelected = state.selectedLinkIds.contains(photo.linkId)
                                    Box(
                                        modifier = Modifier
                                            .aspectRatio(1f)
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(colors.bg2)
                                            .border(
                                                width = if (isSelected) 2.dp else 0.dp,
                                                color = if (isSelected) colors.accent else Color.Transparent,
                                                shape = RoundedCornerShape(8.dp),
                                            )
                                            .clickable {
                                                val newSelection = if (isSelected) {
                                                    state.selectedLinkIds - photo.linkId
                                                } else {
                                                    state.selectedLinkIds + photo.linkId
                                                }
                                                onCloudSelection(newSelection)
                                            },
                                    ) {
                                        if (photo.thumbnailUrl != null) {
                                            AsyncImage(
                                                model = photo.thumbnailUrl,
                                                contentDescription = null,
                                                contentScale = ContentScale.Crop,
                                                modifier = Modifier.fillMaxSize(),
                                            )
                                        } else {
                                            LaunchedEffect(photo.linkId) { onRequestCloudThumb(photo) }
                                        }
                                        if (isSelected) {
                                            Box(
                                                modifier = Modifier
                                                    .fillMaxSize()
                                                    .background(colors.accent.copy(alpha = 0.25f)),
                                            )
                                            Icon(
                                                Icons.Default.CheckCircle,
                                                contentDescription = null,
                                                tint = colors.accent,
                                                modifier = Modifier
                                                    .align(Alignment.TopEnd)
                                                    .padding(4.dp)
                                                    .size(18.dp),
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                    WidgetMode.SELECTED -> {
                        if (state.selectedUris.isEmpty()) {
                            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                                Text(
                                    stringResource(R.string.widget_select_photos),
                                    color = colors.fgMute, fontSize = 14.sp,
                                )
                            }
                        } else {
                            LazyVerticalGrid(
                                columns = GridCells.Fixed(4),
                                modifier = Modifier.weight(1f).fillMaxWidth(),
                                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = bottomInset),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                gridItems(state.selectedUris, key = { it }) { uri ->
                                    AsyncImage(
                                        model = Uri.parse(uri),
                                        contentDescription = null,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier
                                            .aspectRatio(1f)
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(colors.bg2),
                                    )
                                }
                            }
                        }
                    }
                    WidgetMode.ALBUM -> {
                        if (state.albums.isEmpty()) {
                            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                                Text(
                                    stringResource(R.string.widget_no_photos),
                                    color = colors.fgMute, fontSize = 14.sp,
                                )
                            }
                        } else {
                            LazyColumn(
                                modifier = Modifier.weight(1f).fillMaxWidth(),
                                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = bottomInset),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                items(state.albums, key = { it.name }) { album ->
                                    AlbumRow(
                                        album = album,
                                        selected = state.selectedAlbum == album.name,
                                        onClick = { onAlbum(album.name) },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // ── Floating navigation pill (Back / Next-or-Add) ──────────────────
        WidgetWizardBar(
            isFirstStep = step == 0,
            canProceed = step == 0 || canSave,
            isSaving = state.isSaving,
            onBack = { if (step == 0) onCancel() else step = 0 },
            onForward = { if (step == 0) step = 1 else onSave() },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 24.dp),
        )
    }
}

/** Floating bottom pill for the widget wizard — mirrors the gallery's bottom dock: an opaque
 *  rounded bar with a Back / Cancel button and a Next / Add Widget button. */
@Composable
private fun WidgetWizardBar(
    isFirstStep: Boolean,
    canProceed: Boolean,
    isSaving: Boolean,
    onBack: () -> Unit,
    onForward: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = AppColors.current
    Row(
        modifier = modifier
            .background(colors.cardBg, RoundedCornerShape(999.dp))
            .border(0.5.dp, colors.pillBorder, RoundedCornerShape(999.dp))
            .padding(6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(999.dp))
                .clickable(onClick = onBack)
                .padding(horizontal = 22.dp, vertical = 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                stringResource(if (isFirstStep) R.string.cancel else R.string.onboarding_back),
                color = colors.fgDim, fontSize = 15.sp, fontWeight = FontWeight.Medium,
            )
        }
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(999.dp))
                .background(if (canProceed) colors.accent else colors.surfaceWeak)
                .clickable(enabled = canProceed && !isSaving, onClick = onForward)
                .padding(horizontal = 26.dp, vertical = 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (isSaving) {
                CircularProgressIndicator(
                    color = Color.White, strokeWidth = 2.dp, modifier = Modifier.size(18.dp),
                )
            } else {
                Text(
                    stringResource(if (isFirstStep) R.string.onboarding_continue else R.string.widget_add_button),
                    color = if (canProceed) Color.White else colors.fgMute,
                    fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

// ── Small composables ─────────────────────────────────────────────────────────

@Composable
private fun SectionLabel(text: String) {
    val colors = AppColors.current
    Text(
        text       = text.uppercase(),
        color      = colors.fgMute,
        fontSize   = 11.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.8.sp,
    )
}

@Composable
private fun ModeOption(
    title: String,
    description: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val colors = AppColors.current
    val borderColor = if (selected) colors.accent else colors.cardBorder
    val bgColor     = if (selected) colors.accent.copy(alpha = 0.12f) else colors.cardBg

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(bgColor, RoundedCornerShape(12.dp))
            .border(1.dp, borderColor, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Radio dot
        Box(
            modifier = Modifier
                .size(18.dp)
                .border(
                    width = if (selected) 5.dp else 1.5.dp,
                    color = if (selected) colors.accent else colors.fgMute,
                    shape = CircleShape,
                ),
        )
        Column {
            Text(title, color = colors.fgPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Text(description, color = colors.fgMute, fontSize = 12.sp)
        }
    }
}

@Composable
private fun AlbumRow(
    album: LocalAlbum,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val colors = AppColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (selected) colors.accent.copy(alpha = 0.12f) else colors.cardBg,
                RoundedCornerShape(10.dp),
            )
            .border(
                1.dp,
                if (selected) colors.accent else colors.cardBorder,
                RoundedCornerShape(10.dp),
            )
            .clickable(onClick = onClick)
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (album.coverUri != null) {
            AsyncImage(
                model              = Uri.parse(album.coverUri),
                contentDescription = null,
                contentScale       = ContentScale.Crop,
                modifier           = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(colors.bg2),
            )
        } else {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(colors.bg2),
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(album.name, color = colors.fgPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Text(
                stringResource(R.string.sync_photo_count, album.itemCount),
                color = colors.fgMute,
                fontSize = 12.sp,
            )
        }
        if (selected) {
            Icon(
                Icons.Default.CheckCircle,
                contentDescription = null,
                tint = colors.accent,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@Composable
private fun IntervalChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val colors = AppColors.current
    Box(
        modifier = Modifier
            .background(
                if (selected) colors.accent.copy(alpha = 0.18f) else colors.surfaceWeak,
                RoundedCornerShape(8.dp),
            )
            .border(
                0.5.dp,
                if (selected) colors.accent else colors.pillBorder,
                RoundedCornerShape(8.dp),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Text(
            label,
            color      = if (selected) colors.accent else colors.fgDim,
            fontSize   = 12.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}
