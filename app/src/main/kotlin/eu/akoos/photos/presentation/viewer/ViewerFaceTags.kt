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

package eu.akoos.photos.presentation.viewer

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.akoos.photos.R
import eu.akoos.photos.presentation.theme.Accent
import eu.akoos.photos.util.FitPoint
import eu.akoos.photos.util.fitImageInBox
import kotlin.math.roundToInt

/** A face's box on screen, plus the person it belongs to. */
private class FaceScreenRect(
    val person: PhotoViewerViewModel.ViewerPerson,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    val centreX get() = (left + right) / 2f
    val centreY get() = (top + bottom) / 2f
}

/** Map a fraction face box to its screen rectangle under the current fit and zoom, or null off-screen. */
private fun screenRectOf(
    person: PhotoViewerViewModel.ViewerPerson,
    imageSize: IntSize,
    fit: eu.akoos.photos.util.ImageFit,
    transform: ViewerTransform,
    containerW: Float,
    containerH: Float,
): FaceScreenRect? {
    val b = person.faceBox
    val tl = imagePointToScreen(FitPoint(b.left * imageSize.width, b.top * imageSize.height), fit, transform)
    val br = imagePointToScreen(FitPoint(b.right * imageSize.width, b.bottom * imageSize.height), fit, transform)
    val left = minOf(tl.x, br.x)
    val top = minOf(tl.y, br.y)
    val right = maxOf(tl.x, br.x)
    val bottom = maxOf(tl.y, br.y)
    if (right - left <= 1f || bottom - top <= 1f) return null
    if (right < 0f || bottom < 0f || left > containerW || top > containerH) return null
    return FaceScreenRect(person, left, top, right, bottom)
}

/**
 * Name tags pinned over the grouped faces on the photo now on screen. Each face's stored 0..1 box is
 * mapped to the screen through the same letterbox fit and pinch the image rides ([imagePointToScreen]),
 * so a tag stays on its face while the photo is panned and zoomed. Tapping a tag opens that person; an
 * unnamed face shows an add-name chip that opens its cluster.
 *
 * The name label is placed dynamically around each face (below, above, or to a side) and nudged off any
 * label already placed, so a group shot reads as a set of separate names rather than one overlapping
 * pile. A distant face keeps its full-width name (the pill is free to be wider than the face).
 */
@Composable
fun ViewerFaceTags(
    imageSize: IntSize,
    containerSize: IntSize,
    scale: Float,
    offset: Offset,
    people: List<PhotoViewerViewModel.ViewerPerson>,
    onPersonClick: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (imageSize.width <= 0 || imageSize.height <= 0) return
    if (containerSize.width <= 0 || containerSize.height <= 0) return
    val containerW = containerSize.width.toFloat()
    val containerH = containerSize.height.toFloat()
    val fit = fitImageInBox(imageSize.width.toFloat(), imageSize.height.toFloat(), containerW, containerH)
    val transform = viewerTransform(containerSize, scale, offset)
    val density = LocalDensity.current

    val rects = people.mapNotNull { screenRectOf(it, imageSize, fit, transform, containerW, containerH) }
    if (rects.isEmpty()) return

    val pillH = with(density) { 30.dp.toPx() }
    val gap = with(density) { 6.dp.toPx() }
    val charW = with(density) { 8.dp.toPx() }
    val padW = with(density) { 28.dp.toPx() }
    fun pillW(name: String?): Float = (name?.length ?: 1) * charW + padW

    // Greedy placement: bigger faces first (they anchor the layout), each label taking the first of
    // below / above / right / left that stays on screen and clears the labels already placed.
    val placed = ArrayList<FloatArray>() // l, t, r, b of each chosen pill
    val anchors = HashMap<Long, Offset>() // pill centre per person id
    fun overlaps(a: FloatArray, b: FloatArray) =
        a[0] < b[2] && a[2] > b[0] && a[1] < b[3] && a[3] > b[1]
    for (fr in rects.sortedByDescending { (it.right - it.left) * (it.bottom - it.top) }) {
        val w = pillW(fr.person.name)
        val candidates = listOf(
            Offset(fr.centreX, fr.bottom + gap + pillH / 2f),
            Offset(fr.centreX, fr.top - gap - pillH / 2f),
            Offset(fr.right + gap + w / 2f, fr.centreY),
            Offset(fr.left - gap - w / 2f, fr.centreY),
        )
        var chosen = candidates[0]
        for (c in candidates) {
            val cx = c.x.coerceIn(w / 2f, containerW - w / 2f)
            val cy = c.y.coerceIn(pillH / 2f, containerH - pillH / 2f)
            val rect = floatArrayOf(cx - w / 2f, cy - pillH / 2f, cx + w / 2f, cy + pillH / 2f)
            if (placed.none { overlaps(it, rect) }) { chosen = Offset(cx, cy); break }
            if (c == candidates.last()) chosen = Offset(cx, cy) // all collide: keep the last (clamped)
        }
        val fx = chosen.x.coerceIn(w / 2f, containerW - w / 2f)
        val fy = chosen.y.coerceIn(pillH / 2f, containerH - pillH / 2f)
        placed.add(floatArrayOf(fx - w / 2f, fy - pillH / 2f, fx + w / 2f, fy + pillH / 2f))
        anchors[fr.person.personId] = Offset(fx, fy)
    }

    Box(modifier = modifier.fillMaxSize()) {
        // Rings first, so every label sits over them.
        rects.forEach { fr ->
            Box(
                modifier = Modifier
                    .offset { IntOffset(fr.left.roundToInt(), fr.top.roundToInt()) }
                    .size(with(density) { (fr.right - fr.left).toDp() }, with(density) { (fr.bottom - fr.top).toDp() })
                    .clip(RoundedCornerShape(14.dp))
                    .border(1.5.dp, Color.White.copy(alpha = 0.85f), RoundedCornerShape(14.dp)),
            )
        }
        // Then the name pills, each centred on its computed anchor and free to overflow it.
        rects.forEach { fr ->
            val anchor = anchors[fr.person.personId] ?: return@forEach
            Box(
                modifier = Modifier
                    .offset { IntOffset(anchor.x.roundToInt(), anchor.y.roundToInt()) }
                    .size(0.dp)
                    .wrapContentSize(align = Alignment.Center, unbounded = true),
            ) {
                FaceTagPill(name = fr.person.name, onClick = { onPersonClick(fr.person.personId) })
            }
        }
    }
}

@Composable
private fun FaceTagPill(name: String?, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(999.dp))
            .background(Color.Black.copy(alpha = 0.72f))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (name != null) {
            Text(
                name,
                color = Color.White,
                fontSize = 12.5.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        } else {
            Box(
                modifier = Modifier.size(16.dp).clip(CircleShape).background(Accent),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Default.Add,
                    contentDescription = stringResource(R.string.person_detail_unnamed),
                    tint = Color.White,
                    modifier = Modifier.size(12.dp),
                )
            }
        }
    }
}
