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

package eu.akoos.photos.presentation.viewer

import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import eu.akoos.photos.presentation.theme.FgPrimary
import eu.akoos.photos.presentation.theme.PillBg
import eu.akoos.photos.presentation.theme.PillBorder
import eu.akoos.photos.presentation.util.formatVideoTime
import kotlinx.coroutines.delay

// ── Video player ───────────────────────────────────────────────────────────────

/**
 * Plays a video file or content URI using ExoPlayer (Media3).
 * Released automatically when the composable leaves the composition.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
internal fun VideoPlayer(
    uri: Uri,
    onPlayerReady: (ExoPlayer) -> Unit = {},
    modifier: Modifier = Modifier,
    /** Optional bump value mixed into the player's `remember` key — caller passes the
     *  editor's last-save timestamp so an Overwrite (same URI, fresh bytes) builds a new
     *  ExoPlayer instead of reusing the one whose MediaSource still points at the
     *  pre-edit state. */
    reloadKey: Any = Unit,
    /** When false the player is built and prepared (first frame visible) but doesn't
     *  start playback — the pill's play button flips this true via state at the call site.
     *  This split lets the surface fade in immediately on cloud-video download and the
     *  tap-to-play feel instantaneous instead of waiting on prepare(). */
    autoPlay: Boolean = true,
) {
    val context = LocalContext.current
    val exoPlayer = remember(uri, reloadKey) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(uri))
            prepare()
            playWhenReady = autoPlay
            repeatMode = ExoPlayer.REPEAT_MODE_ONE
        }.also { onPlayerReady(it) }
    }
    // Sync playback state when the caller flips autoPlay (user tapped the pill).
    LaunchedEffect(exoPlayer, autoPlay) {
        exoPlayer.playWhenReady = autoPlay
    }
    DisposableEffect(exoPlayer) {
        onDispose { exoPlayer.release() }
    }
    AndroidView(
        factory = { ctx ->
            PlayerView(ctx).apply {
                // Disable the built-in controller BEFORE attaching the player. Setting
                // `player = ...` first lets PlayerView observe the initial Player.STATE_*
                // transition with the default controller still enabled — on some devices
                // that paints a one-frame play/pause/seek bar overlay before our explicit
                // useController=false takes effect. Setting it false first prevents that
                // first-paint leak.
                useController = false
                controllerAutoShow = false
                controllerHideOnTouch = false
                controllerShowTimeoutMs = 0
                // setShowBuffering NEVER also kills the spinner that ExoPlayer briefly
                // shows when buffering — our own download/loading pill is the source of
                // truth for "video is loading".
                setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
                hideController()
                // The PlayerView's "shutter" view is a solid background that covers the
                // surface until the first frame renders — and on some devices it stays
                // up during pause / seek, looking like an unwanted overlay. Make it
                // transparent so users only ever see the video itself.
                setShutterBackgroundColor(android.graphics.Color.TRANSPARENT)
                // The default artwork (a centered placeholder) shows when audio-only
                // metadata is detected — irrelevant for our case but kill it explicitly.
                useArtwork = false
                setDefaultArtwork(null)
                // Player attach AFTER the controller is fully suppressed.
                player = exoPlayer
                // Restored to true once single-flight download per linkId landed and
                // eliminated the swipe-back-rebuilds-player path. The cached video now
                // reuses the same ExoPlayer instance, so there's no "kept old frame"
                // hazard — and keeping content on reset removes a flicker where the
                // surface would briefly clear to transparent and the page background
                // would bleed through.
                setKeepContentOnPlayerReset(true)
            }
        },
        update = { view ->
            view.player = exoPlayer
            // Defensive: if any code path flips useController back on (e.g. another
            // PlayerView util we add later), keep the suppression invariant on update.
            view.useController = false
        },
        modifier = modifier,
    )
}

// ── Video control pill — everything inline in one pill ────────────────────────

@Composable
internal fun VideoControlPill(
    player: ExoPlayer?,
    videoStarted: Boolean,
    onPlay: () -> Unit,
) {
    var isPlaying  by remember { mutableStateOf(false) }
    var isMuted    by remember { mutableStateOf(false) }
    var currentMs  by remember { mutableLongStateOf(0L) }
    var durationMs by remember { mutableLongStateOf(0L) }
    var isSeeking  by remember { mutableStateOf(false) }
    var seekRatio  by remember { mutableStateOf(0f) }
    var trackWidthPx by remember { mutableStateOf(1f) }

    LaunchedEffect(player) {
        if (player == null) return@LaunchedEffect
        while (true) {
            isPlaying  = player.isPlaying
            durationMs = player.duration.coerceAtLeast(0)
            if (!isSeeking) currentMs = player.currentPosition
            delay(200)
        }
    }

    val progress = when {
        isSeeking -> seekRatio
        durationMs > 0 -> (currentMs.toFloat() / durationMs).coerceIn(0f, 1f)
        else -> 0f
    }

    // Single pill: [▶/⏸] [seek bar ~90dp] [time] [🔊]
    Row(
        modifier = Modifier
            .background(PillBg, infoPillShape)
            .border(0.5.dp, PillBorder, infoPillShape)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // Play / Pause
        Box(
            modifier = Modifier.size(28.dp).clip(CircleShape).clickable {
                when {
                    !videoStarted -> onPlay()
                    isPlaying     -> player?.pause()
                    else          -> player?.play()
                }
            },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (videoStarted && isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                // Theme-reactive: the pill goes near-white in light mode where a fixed
                // white glyph would vanish.
                null, tint = FgPrimary, modifier = Modifier.size(20.dp),
            )
        }

        // Canvas draw lambdas are not composable scope — resolve the reactive color here.
        val seekColor = FgPrimary

        // Compact seek bar inside pill
        Canvas(
            modifier = Modifier
                .width(90.dp)
                .height(20.dp)
                .onGloballyPositioned { trackWidthPx = it.size.width.toFloat().coerceAtLeast(1f) }
                .pointerInput(player) {
                    if (player == null) return@pointerInput
                    detectDragGestures(
                        onDragStart = { offset ->
                            isSeeking = true
                            seekRatio = (offset.x / trackWidthPx).coerceIn(0f, 1f)
                        },
                        onDrag = { change, _ ->
                            change.consume()
                            seekRatio = (change.position.x / trackWidthPx).coerceIn(0f, 1f)
                        },
                        onDragEnd = {
                            player.seekTo((seekRatio * durationMs).toLong())
                            isSeeking = false
                        },
                        onDragCancel = { isSeeking = false },
                    )
                }
                .pointerInput(player) {
                    if (player == null) return@pointerInput
                    detectTapGestures { offset ->
                        val ratio = (offset.x / trackWidthPx).coerceIn(0f, 1f)
                        player.seekTo((ratio * durationMs).toLong())
                    }
                },
        ) {
            val cy = size.height / 2f
            drawLine(seekColor.copy(alpha = 0.25f),
                Offset(0f, cy), Offset(size.width, cy),
                strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
            val px = size.width * progress
            if (px > 0f) {
                drawLine(seekColor, Offset(0f, cy), Offset(px, cy),
                    strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
            }
            drawCircle(seekColor, radius = 5.dp.toPx(), center = Offset(px, cy))
        }

        // Time
        Text(
            if (durationMs > 0) "${formatVideoTime(currentMs)} / ${formatVideoTime(durationMs)}"
            else "0:00",
            color = FgPrimary, fontSize = 12.sp, fontWeight = FontWeight.Medium,
        )

        // Mute — always visible
        Box(
            modifier = Modifier.size(28.dp).clip(CircleShape).clickable {
                isMuted = !isMuted
                player?.volume = if (isMuted) 0f else 1f
            },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (isMuted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
                null, tint = FgPrimary.copy(alpha = if (videoStarted) 1f else 0.4f),
                modifier = Modifier.size(18.dp),
            )
        }
    }
}
