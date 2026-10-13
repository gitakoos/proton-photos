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

package eu.akoos.photos.presentation.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp

/**
 * A colour picker: a square of saturation across and brightness down for the hue below it, and a rainbow bar to pick
 * that hue. [argb] is shown and [onChange] gets each colour the finger lands on, always opaque; [onFinished] ends a
 * press or a drag, so a caller can fold one drag into one undo entry. A colour that arrives from outside, an undo for
 * one, moves the pickers to it. A grey keeps the hue it had, so the bar does not jump to red as the square is dragged
 * across the grey side.
 */
@Composable
internal fun ColorPicker(
    argb: Int,
    onChange: (Int) -> Unit,
    onFinished: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val opaque = argb or (0xFF shl 24)
    val start = remember { FloatArray(3).also { android.graphics.Color.colorToHSV(opaque, it) } }
    var hue by remember { mutableFloatStateOf(start[0]) }
    var sat by remember { mutableFloatStateOf(start[1]) }
    var bright by remember { mutableFloatStateOf(start[2]) }
    LaunchedEffect(opaque) {
        if (android.graphics.Color.HSVToColor(floatArrayOf(hue, sat, bright)) != opaque) {
            val hsv = FloatArray(3)
            android.graphics.Color.colorToHSV(opaque, hsv)
            if (hsv[1] > 0.001f && hsv[2] > 0.001f) hue = hsv[0]
            sat = hsv[1]
            bright = hsv[2]
        }
    }
    val latestOnChange = rememberUpdatedState(onChange)
    val latestOnFinished = rememberUpdatedState(onFinished)
    fun emit() = latestOnChange.value(android.graphics.Color.HSVToColor(floatArrayOf(hue, sat, bright)))
    fun squareAt(p: Offset, w: Float, h: Float) {
        sat = (p.x / w.coerceAtLeast(1f)).coerceIn(0f, 1f)
        bright = 1f - (p.y / h.coerceAtLeast(1f)).coerceIn(0f, 1f)
        emit()
    }
    fun hueAt(p: Offset, w: Float) {
        hue = (p.x / w.coerceAtLeast(1f)).coerceIn(0f, 1f) * 360f
        emit()
    }
    val current = Color.hsv(hue, sat, bright)
    val rainbow = remember { (0..6).map { Color.hsv(it * 60f, 1f, 1f) } }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(104.dp)
                .clip(RoundedCornerShape(12.dp))
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { squareAt(it, size.width.toFloat(), size.height.toFloat()) },
                        onDrag = { change, _ ->
                            squareAt(change.position, size.width.toFloat(), size.height.toFloat())
                            change.consume()
                        },
                        onDragEnd = { latestOnFinished.value() },
                        onDragCancel = { latestOnFinished.value() },
                    )
                }
                .pointerInput(Unit) {
                    detectTapGestures(onTap = {
                        squareAt(it, size.width.toFloat(), size.height.toFloat())
                        latestOnFinished.value()
                    })
                },
        ) {
            drawRect(Brush.horizontalGradient(listOf(Color.White, Color.hsv(hue, 1f, 1f))))
            drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.Black)))
            val c = Offset(sat * size.width, (1f - bright) * size.height)
            drawCircle(current, radius = 9.dp.toPx(), center = c)
            drawCircle(Color.White, radius = 9.dp.toPx(), center = c, style = Stroke(2.5.dp.toPx()))
        }
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(26.dp)
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { hueAt(it, size.width.toFloat()) },
                        onDrag = { change, _ ->
                            hueAt(change.position, size.width.toFloat())
                            change.consume()
                        },
                        onDragEnd = { latestOnFinished.value() },
                        onDragCancel = { latestOnFinished.value() },
                    )
                }
                .pointerInput(Unit) {
                    detectTapGestures(onTap = {
                        hueAt(it, size.width.toFloat())
                        latestOnFinished.value()
                    })
                },
        ) {
            val bar = 12.dp.toPx()
            val top = (size.height - bar) / 2f
            drawRoundRect(
                Brush.horizontalGradient(rainbow),
                topLeft = Offset(0f, top),
                size = androidx.compose.ui.geometry.Size(size.width, bar),
                cornerRadius = CornerRadius(bar / 2f),
            )
            val x = (hue / 360f) * size.width
            drawCircle(Color.hsv(hue, 1f, 1f), radius = 11.dp.toPx(), center = Offset(x, size.height / 2f))
            drawCircle(Color.White, radius = 11.dp.toPx(), center = Offset(x, size.height / 2f), style = Stroke(2.5.dp.toPx()))
        }
    }
}
