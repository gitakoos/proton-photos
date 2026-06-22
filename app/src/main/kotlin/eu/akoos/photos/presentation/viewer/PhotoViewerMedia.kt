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

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.Uri
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.layout.ContentScale
import coil.compose.AsyncImage
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
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
import eu.akoos.photos.R
import eu.akoos.photos.presentation.theme.FgPrimary
import eu.akoos.photos.presentation.theme.PillBg
import eu.akoos.photos.presentation.theme.PillBorder
import eu.akoos.photos.presentation.util.formatVideoTime
import kotlinx.coroutines.channels.Channel
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
    /** Mixed into the player's `remember` key so an Overwrite-save (same URI, fresh bytes) builds
     *  a new ExoPlayer instead of reusing one whose MediaSource points at the pre-edit state. */
    reloadKey: Any = Unit,
    /** When false the player prepares (first frame visible) but doesn't play; the pill flips this
     *  true. Lets the surface fade in immediately and tap-to-play feel instant. */
    autoPlay: Boolean = true,
    /** Non-null = play once and call this on natural end (inline motion-photo); null = loop. */
    onEnded: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val loop = onEnded == null
    val exoPlayer = remember(uri, reloadKey) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(uri))
            prepare()
            playWhenReady = autoPlay
            repeatMode = if (loop) ExoPlayer.REPEAT_MODE_ONE else ExoPlayer.REPEAT_MODE_OFF
        }.also { onPlayerReady(it) }
    }
    LaunchedEffect(exoPlayer, autoPlay) {
        exoPlayer.playWhenReady = autoPlay
    }
    // rememberUpdatedState keeps the STATE_ENDED listener pinned to the latest lambda without
    // re-registering on every recomposition.
    val currentOnEnded by androidx.compose.runtime.rememberUpdatedState(onEnded)
    DisposableEffect(exoPlayer) {
        val listener = object : androidx.media3.common.Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == androidx.media3.common.Player.STATE_ENDED) {
                    currentOnEnded?.invoke()
                }
            }
            // A video that downloads and decrypts fine but whose codec/container ExoPlayer can't
            // decode would otherwise sit on a silent black surface forever. Surface the failure.
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                android.widget.Toast.makeText(
                    context,
                    context.getString(R.string.viewer_video_play_failed),
                    android.widget.Toast.LENGTH_LONG,
                ).show()
            }
        }
        exoPlayer.addListener(listener)
        onDispose {
            exoPlayer.removeListener(listener)
            exoPlayer.release()
        }
    }
    // Pause when the app leaves the foreground so a video never keeps playing in the background.
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(exoPlayer, lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_PAUSE) exoPlayer.playWhenReady = false
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    AndroidView(
        factory = { ctx ->
            PlayerView(ctx).apply {
                // Disable the controller BEFORE attaching the player: setting `player` first lets
                // PlayerView paint a one-frame control bar on some devices before useController
                // takes effect.
                useController = false
                controllerAutoShow = false
                controllerHideOnTouch = false
                controllerShowTimeoutMs = 0
                // Our own download/loading pill is the source of truth, so suppress ExoPlayer's
                // buffering spinner.
                setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
                hideController()
                // Transparent shutter so it doesn't look like an overlay during pause / seek.
                setShutterBackgroundColor(android.graphics.Color.TRANSPARENT)
                useArtwork = false
                setDefaultArtwork(null)
                player = exoPlayer
                // Keep the last frame on reset to avoid a transparent flicker; safe since the
                // cached video reuses the same ExoPlayer instance (no stale-frame hazard).
                setKeepContentOnPlayerReset(true)
            }
        },
        update = { view ->
            view.player = exoPlayer
            // Keep the controller suppressed in case anything flipped it back on.
            view.useController = false
        },
        modifier = modifier,
    )
}

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

    Row(
        modifier = Modifier
            .background(PillBg, infoPillShape)
            .border(0.5.dp, PillBorder, infoPillShape)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
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
                // FgPrimary (not white): the pill goes near-white in light mode.
                null, tint = FgPrimary, modifier = Modifier.size(20.dp),
            )
        }

        // Canvas draw lambdas are not composable scope — resolve the reactive color here.
        val seekColor = FgPrimary

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

        Text(
            if (durationMs > 0) "${formatVideoTime(currentMs)} / ${formatVideoTime(durationMs)}"
            else "0:00",
            color = FgPrimary, fontSize = 12.sp, fontWeight = FontWeight.Medium,
        )

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

/** Phone rotation through this angle (~110°) sweeps the whole strip end to end, so the gyro pan
 *  scales to the strip's width; a larger angle = gentler pan per unit of physical rotation. */
private const val PANO_SWEEP_RAD = 1.9f

/**
 * Immersive horizontal-pan view for a panorama still. [ContentScale.FillHeight] +
 * [wrapContentWidth(unbounded = true)] sizes the image from height alone, so a wide panorama
 * overflows the width and the scroll container pans across it (gyro tilt drives the same scroll).
 */
@Composable
internal fun PanoramaPager(
    model: Any,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scrollState = rememberScrollState()

    // The sensor callback runs off the main thread, so it only posts the raw yaw delta onto a
    // channel; the consumer maps it to pixels and drives the same scroll state as the drag.
    val rotationDeltas = remember { Channel<Float>(Channel.UNLIMITED) }
    DisposableEffect(Unit) {
        val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        val gyro = sensorManager?.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        var lastTs = 0L
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                if (lastTs != 0L) {
                    val dt = (event.timestamp - lastTs) * 1e-9f
                    // values[1] = rotation rate (rad/s) around the device's vertical axis (yaw).
                    rotationDeltas.trySend(event.values[1] * dt)
                }
                lastTs = event.timestamp
            }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }
        if (gyro != null) {
            sensorManager.registerListener(listener, gyro, SensorManager.SENSOR_DELAY_GAME)
        }
        onDispose { sensorManager?.unregisterListener(listener) }
    }
    LaunchedEffect(Unit) {
        for (radians in rotationDeltas) {
            // Yield to an active finger drag / fling so the two inputs never fight.
            if (scrollState.isScrollInProgress) continue
            val range = scrollState.maxValue
            if (range <= 0) continue
            // Turn left → reveal the left of the strip (scroll toward the start); turn right → right.
            scrollState.scrollBy(-radians * (range / PANO_SWEEP_RAD))
        }
    }

    // Side-arrow hint: a brief outward nudge on both edges telling the user to move the device
    // (or drag) to look around. Fades out after a few seconds so it never lingers over the photo.
    var showHint by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) { delay(4500); showHint = false }
    val hintAlpha by animateFloatAsState(
        targetValue = if (showHint) 1f else 0f,
        animationSpec = tween(400),
        label = "panoHintAlpha",
    )
    val hintTransition = rememberInfiniteTransition(label = "panoHint")
    val nudge by hintTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(850, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "panoHintNudge",
    )

    Box(modifier = modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .horizontalScroll(scrollState),
            contentAlignment = Alignment.Center,
        ) {
            AsyncImage(
                model = model,
                contentDescription = null,
                contentScale = ContentScale.FillHeight,
                modifier = Modifier
                    .fillMaxHeight()
                    .wrapContentWidth(unbounded = true),
            )
        }

        if (hintAlpha > 0.01f) {
            PanoramaHintArrow(
                icon = Icons.Default.ChevronLeft,
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = 12.dp)
                    .offset(x = (nudge * -5).dp)
                    .alpha(hintAlpha),
            )
            PanoramaHintArrow(
                icon = Icons.Default.ChevronRight,
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 12.dp)
                    .offset(x = (nudge * 5).dp)
                    .alpha(hintAlpha),
            )
        }
    }
}

/** One edge arrow of the panorama "move to look around" hint — a chevron in a soft pill disc. */
@Composable
private fun PanoramaHintArrow(
    icon: ImageVector,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .size(38.dp)
            .clip(CircleShape)
            .background(PillBg)
            .border(0.5.dp, PillBorder, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = FgPrimary,
            modifier = Modifier.size(24.dp),
        )
    }
}
