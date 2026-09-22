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

import androidx.annotation.StringRes
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import eu.akoos.photos.R
import eu.akoos.photos.presentation.gallery.FaceBox
import eu.akoos.photos.presentation.gallery.FaceCropTransformation
import eu.akoos.photos.presentation.theme.Bg2
import eu.akoos.photos.presentation.theme.CardBg
import eu.akoos.photos.presentation.theme.FgMute
import eu.akoos.photos.presentation.theme.FgPrimary
import eu.akoos.photos.presentation.theme.Line2
import eu.akoos.photos.presentation.theme.pillShape
import kotlin.math.roundToInt

/**
 * The People box at the head of the details panel: one scrolling row of pills, one per person the face
 * index found on the photo, each with the person's round face crop at its start and the name beside it.
 * A person without a name, whether an unnamed group or a single Unsorted face, reads as Unnamed in the
 * muted colour, so the named people stand out. While the row can scroll on, its trailing edge fades the
 * last visible pill out as the hint. A tap hands the person back to the caller. Emits nothing for a
 * photo with no people, so no empty card shows.
 *
 * [faceSource] is the model the viewer itself loads the photo from, so each crop reads the same image for a
 * device, backed-up or cloud-only item. With no source, or a face whose box leaves nothing to crop, the
 * crop shows a neutral person glyph rather than the whole photo.
 */
@Composable
internal fun DetailsPeopleBox(
    people: List<PhotoViewerViewModel.ViewerPerson>,
    faceSource: Any?,
    onPersonClick: (PhotoViewerViewModel.ViewerPerson) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (people.isEmpty()) return
    val keys = remember(people) { DetailsPeopleRow.keys(people) }
    val listState = rememberLazyListState()
    // Eased rather than switched, so the last pill settles in when the row reaches its end. A row that
    // fits has nowhere to scroll, so it never fades.
    val endFade by animateFloatAsState(
        targetValue = if (listState.canScrollForward) 1f else 0f,
        animationSpec = tween(durationMillis = 180),
        label = "details_people_end_fade",
    )
    Box(modifier = modifier) {
        MetadataSection(stringResource(R.string.gallery_category_people)) {
            LazyRow(
                state = listState,
                modifier = Modifier
                    .fillMaxWidth()
                    // DstIn keeps the row only where the mask is opaque, and a blend like that needs a
                    // layer of its own, or it would cut into the card drawn beneath the row as well.
                    .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
                    .drawWithContent {
                        drawContent()
                        val amount = endFade
                        if (amount <= 0f) return@drawWithContent
                        val span = DetailsPeopleRow.endFadeSpan(
                            widthPx = size.width,
                            edgePx = PeopleRowPaddingH.toPx(),
                            rampPx = PillHeight.toPx(),
                            ltr = layoutDirection == LayoutDirection.Ltr,
                        )
                        drawRect(
                            brush = Brush.horizontalGradient(
                                colors = listOf(Color.Black, Color.Black.copy(alpha = 1f - amount)),
                                startX = span.solidX,
                                endX = span.clearX,
                            ),
                            blendMode = BlendMode.DstIn,
                        )
                    },
                // Padding inside the scroll rather than around it, so a pill runs out to the card's edge.
                contentPadding = PaddingValues(horizontal = PeopleRowPaddingH, vertical = PeopleRowPaddingV),
                horizontalArrangement = Arrangement.spacedBy(PillSpacing),
            ) {
                itemsIndexed(people, key = { index, _ -> keys[index] }) { _, person ->
                    PersonPill(
                        person = person,
                        faceSource = faceSource,
                        onClick = { onPersonClick(person) },
                    )
                }
            }
        }
    }
}

/**
 * One person's pill: the round face crop at its start and the name beside it. Fill, hairline, padding and
 * type follow the Categories chips of this panel, on the full capsule of the viewer's face tags, so the
 * crop sits centred in the pill's rounded start.
 */
@Composable
private fun PersonPill(
    person: PhotoViewerViewModel.ViewerPerson,
    faceSource: Any?,
    onClick: () -> Unit,
) {
    val label = DetailsPeopleRow.pillLabel(person)
    Row(
        modifier = Modifier
            .clip(pillShape)
            .background(CardBg)
            .border(0.5.dp, Line2, pillShape)
            // One clickable merges the crop and the name into the one element a screen reader announces.
            .clickable(role = Role.Button, onClick = onClick)
            .padding(start = FaceInset, top = FaceInset, end = PillPaddingEnd, bottom = FaceInset),
        horizontalArrangement = Arrangement.spacedBy(FaceNameGap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PersonFace(person = person, faceSource = faceSource)
        Text(
            text = when (label) {
                is DetailsPeopleRow.PillLabel.Name -> label.text
                is DetailsPeopleRow.PillLabel.Placeholder -> stringResource(label.res)
            },
            color = if (label is DetailsPeopleRow.PillLabel.Name) FgPrimary else FgMute,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = NameMaxWidth),
        )
    }
}

/**
 * The round face crop at a pill's start, or the neutral glyph where there is nothing to crop it from. The
 * name beside it is what a screen reader reads, so the crop itself carries no description.
 */
@Composable
private fun PersonFace(person: PhotoViewerViewModel.ViewerPerson, faceSource: Any?) {
    val box = remember(person.faceBox) { DetailsPeopleRow.clampedFaceBox(person.faceBox) }
    Box(
        modifier = Modifier
            .size(FaceSize)
            .clip(CircleShape)
            .background(Bg2),
        contentAlignment = Alignment.Center,
    ) {
        if (faceSource != null && box != null) {
            val context = LocalContext.current
            val decodePx = DetailsPeopleRow.decodePx(box, with(LocalDensity.current) { FaceSize.toPx() })
            val request = remember(faceSource, box, decodePx) {
                ImageRequest.Builder(context)
                    .data(faceSource)
                    .size(decodePx)
                    .crossfade(false)
                    .transformations(FaceCropTransformation(box))
                    .build()
            }
            AsyncImage(
                model = request,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Icon(
                Icons.Default.Person,
                contentDescription = null,
                tint = FgMute,
                modifier = Modifier.size(FaceSize * 0.6f),
            )
        }
    }
}

/** Space between the card's edge and the pills, level with the text inset of the other sections' rows. */
private val PeopleRowPaddingH = 16.dp

private val PeopleRowPaddingV = 12.dp

private val PillSpacing = 8.dp

private val FaceSize = 30.dp

/** The pill's inset around the face crop at its start, top and bottom, which sets the pill's height. */
private val FaceInset = 4.dp

private val FaceNameGap = 8.dp

private val PillPaddingEnd = 12.dp

/** A pill's height at the default text size, and the width the end fade ramps over. */
private val PillHeight = FaceSize + FaceInset * 2

/** Widest a name runs before it ellipsizes, so one long name cannot take over the row. */
private val NameMaxWidth = 120.dp

/** Label, fade, decode and keying rules for the People row, kept free of Compose so they are tested on
 *  the JVM. */
internal object DetailsPeopleRow {

    /** Bounds on the decode a face is cropped out of. */
    const val DECODE_MIN_PX = 320
    const val DECODE_MAX_PX = 1024

    /** What a pill says: the person's own name, or a string that stands in, muted, for a missing one. */
    sealed interface PillLabel {
        data class Name(val text: String) : PillLabel
        data class Placeholder(@StringRes val res: Int) : PillLabel
    }

    /**
     * The label on [person]'s pill: the name, or Unnamed for either kind of entry without one, an unnamed
     * group or a single Unsorted face. A blank name counts as none.
     */
    fun pillLabel(person: PhotoViewerViewModel.ViewerPerson): PillLabel {
        val name = person.name?.takeIf { it.isNotBlank() }
        return if (name != null) PillLabel.Name(name) else PillLabel.Placeholder(R.string.person_detail_unnamed)
    }

    /** The end fade's ramp as x positions across the row: the row shows in full at [solidX] and is gone
     *  from [clearX] on. */
    data class FadeSpan(val solidX: Float, val clearX: Float)

    /**
     * Where the end fade ramps on a row [widthPx] wide: across [rampPx], finishing [edgePx] in from the
     * trailing edge, which is the right in a left-to-right layout and the left in a right-to-left one. The
     * last visible pill fades out across the ramp, and anything scrolling through the end padding is gone.
     */
    fun endFadeSpan(widthPx: Float, edgePx: Float, rampPx: Float, ltr: Boolean): FadeSpan {
        val clear = if (ltr) widthPx - edgePx else edgePx
        return FadeSpan(solidX = if (ltr) clear - rampPx else clear + rampPx, clearX = clear)
    }

    /**
     * Decode size, in pixels, for cropping [box] into a face [avatarPx] across. The crop keeps only a
     * square a little larger than the face, so a face taking a smaller share of the frame needs a larger
     * decode to stay sharp: the avatar size over that share, held within [DECODE_MIN_PX]..[DECODE_MAX_PX].
     * An unusable avatar size takes the floor.
     */
    fun decodePx(box: FaceBox, avatarPx: Float): Int {
        val share = maxOf(box.right - box.left, box.bottom - box.top)
        val wanted = if (share > 0f) avatarPx / share else Float.POSITIVE_INFINITY
        return when {
            wanted.isNaN() -> DECODE_MIN_PX
            wanted >= DECODE_MAX_PX -> DECODE_MAX_PX
            else -> wanted.roundToInt().coerceAtLeast(DECODE_MIN_PX)
        }
    }

    /**
     * [box] held inside the frame: its edges put in order and each fraction clamped to 0..1. Null when
     * nothing of the face is left to crop (no area, or an unreadable edge), which the pill shows as the
     * neutral glyph instead of the uncropped photo.
     */
    fun clampedFaceBox(box: FaceBox): FaceBox? {
        val left = minOf(box.left, box.right).coerceIn(0f, 1f)
        val right = maxOf(box.left, box.right).coerceIn(0f, 1f)
        val top = minOf(box.top, box.bottom).coerceIn(0f, 1f)
        val bottom = maxOf(box.top, box.bottom).coerceIn(0f, 1f)
        if (left.isNaN() || right.isNaN() || top.isNaN() || bottom.isNaN()) return null
        if (right <= left || bottom <= top) return null
        return FaceBox(left, top, right, bottom)
    }

    /**
     * One row key per entry: the face's id, or its person's where a face carries none, made unique where
     * two entries would otherwise share one, since a lazy row throws on a repeated key.
     */
    fun keys(people: List<PhotoViewerViewModel.ViewerPerson>): List<String> {
        val used = HashSet<String>(people.size * 2)
        return people.map { person ->
            val base = person.faceId.ifEmpty { "person:${person.personId}" }
            var key = base
            var n = 1
            while (!used.add(key)) key = "$base#${n++}"
            key
        }
    }
}
