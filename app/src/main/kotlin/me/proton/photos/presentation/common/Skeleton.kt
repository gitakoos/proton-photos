package me.proton.photos.presentation.common

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import me.proton.photos.presentation.theme.AppColors

/**
 * A reusable animated shimmer rectangle — used everywhere CircularProgressIndicator was
 * showing while content loads (gallery grid, album cards, photo grid inside an album, etc.).
 * Looks like a soft tinted block with a slow diagonal highlight sweeping across.
 *
 * Theme-aware: in dark mode renders as soft purple-grey block with lighter sweep; in light
 * mode renders as warm grey block with brighter sweep.
 */
@Composable
fun ShimmerBox(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 8.dp,
) {
    val colors = AppColors.current
    val baseColor = if (colors.isLight) Color(0xFFE6E6EA) else Color(0xFF1E1B26)
    val highlightColor = if (colors.isLight) Color(0xFFF2F2F5) else Color(0xFF2B2735)

    val transition = rememberInfiniteTransition(label = "shimmer")
    val translate by transition.animateFloat(
        initialValue = -2f,
        targetValue = 2f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1400, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "shimmer-offset",
    )

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(cornerRadius))
            .background(baseColor)
            .drawBehind {
                val gradient = Brush.linearGradient(
                    colors = listOf(baseColor, highlightColor, baseColor),
                    start = Offset(size.width * translate, 0f),
                    end = Offset(size.width * (translate + 1f), size.height),
                )
                drawRect(brush = gradient)
            },
    )
}

/** A 1:1 album card skeleton — fits the same slot as [AsyncImage] inside cards. */
@Composable
fun ShimmerSquare(modifier: Modifier = Modifier, cornerRadius: Dp = 14.dp) {
    ShimmerBox(modifier = modifier.aspectRatio(1f), cornerRadius = cornerRadius)
}

/** A line of text skeleton — width is a percentage of available space. */
@Composable
fun ShimmerTextLine(
    widthFraction: Float = 0.6f,
    height: Dp = 14.dp,
    modifier: Modifier = Modifier,
) {
    ShimmerBox(
        modifier = modifier.fillMaxWidth(widthFraction).height(height),
        cornerRadius = 4.dp,
    )
}

/** Quick helper for an album/photo grid loading state — N squares with title + meta lines. */
@Composable
fun ShimmerAlbumCard(modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxWidth()) {
        androidx.compose.foundation.layout.Column(modifier = Modifier.fillMaxWidth()) {
            ShimmerSquare(modifier = Modifier.fillMaxWidth())
            androidx.compose.foundation.layout.Spacer(Modifier.height(8.dp))
            ShimmerTextLine(widthFraction = 0.7f, height = 14.dp)
            androidx.compose.foundation.layout.Spacer(Modifier.height(4.dp))
            ShimmerTextLine(widthFraction = 0.4f, height = 12.dp)
        }
    }
}
