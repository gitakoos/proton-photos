package me.proton.photos.widget

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import dagger.hilt.android.AndroidEntryPoint
import me.proton.photos.domain.entity.LocalAlbum
import me.proton.photos.presentation.theme.Accent
import me.proton.photos.presentation.theme.Bg0
import me.proton.photos.presentation.theme.Bg1
import me.proton.photos.presentation.theme.Bg2
import me.proton.photos.presentation.theme.FgDim
import me.proton.photos.presentation.theme.FgMute
import me.proton.photos.presentation.theme.FgPrimary
import me.proton.photos.presentation.theme.Line2
import me.proton.photos.presentation.theme.PillBorder
import me.proton.photos.presentation.theme.ProtonPhotosTheme

@AndroidEntryPoint
class PhotoWidgetConfigActivity : ComponentActivity() {

    private var appWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Immediately set CANCELED so pressing Back doesn't add the widget
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
            ProtonPhotosTheme {
                val viewModel: PhotoWidgetConfigViewModel = hiltViewModel()
                val state by viewModel.state.collectAsStateWithLifecycle()

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
                    onSave   = { viewModel.save(appWidgetId) },
                    onCancel = { finish() },
                )
            }
        }
    }
}

// ── Config screen ─────────────────────────────────────────────────────────────

@Composable
private fun WidgetConfigScreen(
    state: WidgetConfigUiState,
    onMode: (WidgetMode) -> Unit,
    onInterval: (WidgetInterval) -> Unit,
    onUris: (List<String>) -> Unit,
    onAlbum: (String) -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
) {
    // Photo picker launcher
    val photoPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetMultipleContents(),
    ) { uris ->
        if (uris.isNotEmpty()) onUris(uris.map { it.toString() })
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Bg0),
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding(),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            // ── Title ──────────────────────────────────────────────────────
            item {
                Text(
                    "Photo Widget",
                    color = FgPrimary,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "Configure how photos are displayed on your home screen.",
                    color = FgDim,
                    fontSize = 14.sp,
                )
            }

            // ── Mode selection ─────────────────────────────────────────────
            item {
                SectionLabel("Display mode")
                Spacer(Modifier.height(10.dp))
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ModeOption(
                        title       = "All photos",
                        description = "A random photo picked each cycle",
                        selected    = state.mode == WidgetMode.ALL_PHOTOS,
                        onClick     = { onMode(WidgetMode.ALL_PHOTOS) },
                    )
                    ModeOption(
                        title       = "Selected photos",
                        description = "Cycles through photos you choose",
                        selected    = state.mode == WidgetMode.SELECTED,
                        onClick     = { onMode(WidgetMode.SELECTED) },
                    )
                    ModeOption(
                        title       = "From album",
                        description = "Cycles through a specific album",
                        selected    = state.mode == WidgetMode.ALBUM,
                        onClick     = { onMode(WidgetMode.ALBUM) },
                    )
                }
            }

            // ── SELECTED mode: photo picker ────────────────────────────────
            if (state.mode == WidgetMode.SELECTED) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(
                            modifier            = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment   = Alignment.CenterVertically,
                        ) {
                            SectionLabel("Selected photos")
                            Box(
                                modifier = Modifier
                                    .background(Bg2, RoundedCornerShape(8.dp))
                                    .border(0.5.dp, PillBorder, RoundedCornerShape(8.dp))
                                    .clickable { photoPicker.launch("image/*") }
                                    .padding(horizontal = 14.dp, vertical = 8.dp),
                            ) {
                                Text(
                                    if (state.selectedUris.isEmpty()) "+ Select photos"
                                    else "Change selection",
                                    color    = Accent,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium,
                                )
                            }
                        }
                        if (state.selectedUris.isNotEmpty()) {
                            Text(
                                "${state.selectedUris.size} photos selected",
                                color    = FgDim,
                                fontSize = 13.sp,
                            )
                            // Thumbnail strip of selected photos
                            LazyRow(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                contentPadding = PaddingValues(horizontal = 0.dp),
                            ) {
                                items(state.selectedUris.take(12)) { uri ->
                                    AsyncImage(
                                        model              = Uri.parse(uri),
                                        contentDescription = null,
                                        contentScale       = ContentScale.Crop,
                                        modifier           = Modifier
                                            .size(60.dp)
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(Bg2),
                                    )
                                }
                                if (state.selectedUris.size > 12) {
                                    item {
                                        Box(
                                            modifier = Modifier
                                                .size(60.dp)
                                                .clip(RoundedCornerShape(8.dp))
                                                .background(Bg2),
                                            contentAlignment = Alignment.Center,
                                        ) {
                                            Text(
                                                "+${state.selectedUris.size - 12}",
                                                color    = FgMute,
                                                fontSize = 13.sp,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // ── ALBUM mode: album list ─────────────────────────────────────
            if (state.mode == WidgetMode.ALBUM) {
                item {
                    SectionLabel("Choose album")
                    Spacer(Modifier.height(10.dp))
                    if (state.albums.isEmpty()) {
                        Text("No albums found", color = FgMute, fontSize = 13.sp)
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            state.albums.forEach { album ->
                                AlbumRow(
                                    album    = album,
                                    selected = state.selectedAlbum == album.name,
                                    onClick  = { onAlbum(album.name) },
                                )
                            }
                        }
                    }
                }
            }

            // ── Interval picker ────────────────────────────────────────────
            item {
                SectionLabel("Change photo every")
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    WidgetInterval.entries.forEach { interval ->
                        IntervalChip(
                            label    = interval.label,
                            selected = state.interval == interval,
                            onClick  = { onInterval(interval) },
                        )
                    }
                }
            }

            // ── Action buttons ─────────────────────────────────────────────
            item {
                Row(
                    modifier              = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    // Cancel
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .background(Bg2, RoundedCornerShape(12.dp))
                            .border(0.5.dp, PillBorder, RoundedCornerShape(12.dp))
                            .clickable(onClick = onCancel)
                            .padding(vertical = 14.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("Cancel", color = FgDim, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                    }

                    // Add Widget
                    val canSave = when (state.mode) {
                        WidgetMode.ALL_PHOTOS -> true
                        WidgetMode.SELECTED   -> state.selectedUris.isNotEmpty()
                        WidgetMode.ALBUM      -> state.selectedAlbum != null
                    }
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .background(
                                if (canSave) Accent else Bg2,
                                RoundedCornerShape(12.dp),
                            )
                            .clickable(enabled = canSave && !state.isSaving) { onSave() }
                            .padding(vertical = 14.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (state.isSaving) {
                            CircularProgressIndicator(
                                color       = Color.White,
                                strokeWidth = 2.dp,
                                modifier    = Modifier.size(18.dp),
                            )
                        } else {
                            Text(
                                "Add Widget",
                                color      = if (canSave) Color.White else FgMute,
                                fontSize   = 15.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                }
            }
        }
    }
}

// ── Small composables ─────────────────────────────────────────────────────────

@Composable
private fun SectionLabel(text: String) {
    Text(
        text       = text.uppercase(),
        color      = FgMute,
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
    val borderColor = if (selected) Accent else Color(0xFF2C2C2E)
    val bgColor     = if (selected) Color(0xFF1A1730) else Bg1

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
                    color = if (selected) Accent else FgMute,
                    shape = androidx.compose.foundation.shape.CircleShape,
                ),
        )
        Column {
            Text(title, color = FgPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Text(description, color = FgMute, fontSize = 12.sp)
        }
    }
}

@Composable
private fun AlbumRow(
    album: LocalAlbum,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (selected) Color(0xFF1A1730) else Bg1, RoundedCornerShape(10.dp))
            .border(1.dp, if (selected) Accent else Color(0xFF2C2C2E), RoundedCornerShape(10.dp))
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
                    .background(Bg2),
            )
        } else {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Bg2),
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(album.name, color = FgPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Text("${album.itemCount} photos", color = FgMute, fontSize = 12.sp)
        }
        if (selected) {
            Text("✓", color = Accent, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun IntervalChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .background(
                if (selected) Color(0xFF3A3A3C) else Color(0xFF1C1C1E),
                RoundedCornerShape(8.dp),
            )
            .border(
                0.5.dp,
                if (selected) Color(0xFF3A3A3C) else Color(0xFF2C2C2E),
                RoundedCornerShape(8.dp),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Text(
            label,
            color      = if (selected) FgPrimary else FgDim,
            fontSize   = 12.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}
