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

package eu.akoos.photos.presentation.map

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.util.LruCache
import androidx.core.graphics.drawable.toBitmap
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import eu.akoos.photos.domain.entity.GalleryItem
import eu.akoos.photos.presentation.gallery.photoCellInputsFor

/**
 * Renders the Google-Photos-style map marker: a rounded-rectangle thumbnail with a white border, a
 * soft drop shadow, and a downward-pointing triangle whose tip sits on the photo's coordinate (the
 * caller anchors the marker at ANCHOR_CENTER / ANCHOR_BOTTOM so the tip lands on the fix).
 *
 * Everything is sized in pixels off the screen density so the pin stays crisp on 2x/3x displays.
 * The shadow lives in a transparent margin around the body, so the returned bitmap is a little
 * larger than the visible pin on every side.
 */
internal object ThumbnailPin {

    // Visible body is a ~52dp square; the pointer hangs below it and a small margin all round holds
    // the blur radius of the drop shadow so it isn't clipped at the bitmap edge.
    private const val BODY_DP = 52f
    private const val CORNER_DP = 10f
    private const val BORDER_DP = 2.5f
    private const val POINTER_W_DP = 14f
    private const val POINTER_H_DP = 9f
    private const val SHADOW_BLUR_DP = 5f
    private const val SHADOW_DY_DP = 2f
    private const val MARGIN_DP = 6f
    private const val DOT_TOUCH_DP = 28f
    private const val DOT_RING_DP = 5f
    private const val DOT_CORE_DP = 3.4f

    /** The right edge of the visible pin body, in pixels from the bitmap's left edge. */
    fun bodyRight(density: Float): Float = (MARGIN_DP + BODY_DP) * density

    /** The top edge of the visible pin body, in pixels from the bitmap's top edge. */
    fun bodyTop(density: Float): Float = MARGIN_DP * density

    /** Photo pin: the [thumb] cover-cropped into the rounded body. */
    fun build(thumb: Bitmap, density: Float): Bitmap =
        render(density) { canvas, body, paint ->
            drawCoverCroppedThumb(canvas, thumb, body, CORNER_DP * density)
        }

    /**
     * The small dot a pin shrinks to when another pin has the room: a white ring around an [accent] centre, like
     * the world map's dots. The bitmap is a larger transparent square so the dot is easy to tap.
     */
    fun dot(density: Float, accent: Int): Bitmap {
        val size = (DOT_TOUCH_DP * density).toInt()
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val centre = size / 2f
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = Color.argb(70, 0, 0, 0)
        canvas.drawCircle(centre, centre + 1f * density, DOT_RING_DP * density, paint)
        paint.color = Color.WHITE
        canvas.drawCircle(centre, centre, DOT_RING_DP * density, paint)
        paint.color = accent
        canvas.drawCircle(centre, centre, DOT_CORE_DP * density, paint)
        return out
    }

    /** Neutral fallback pin (no thumbnail yet, or a non-local source): a flat tile + photo glyph. */
    fun placeholder(density: Float): Bitmap =
        render(density) { canvas, body, paint ->
            val r = CORNER_DP * density
            paint.color = Color.parseColor("#E2E5EA")
            canvas.drawRoundRect(body, r, r, paint)
            drawPhotoGlyph(canvas, body, density)
        }

    private inline fun render(
        density: Float,
        drawBody: (canvas: Canvas, body: RectF, paint: Paint) -> Unit,
    ): Bitmap {
        val body = BODY_DP * density
        val pointerW = POINTER_W_DP * density
        val pointerH = POINTER_H_DP * density
        val margin = MARGIN_DP * density
        val border = BORDER_DP * density

        val width = (body + margin * 2).toInt()
        val height = (body + pointerH + margin * 2).toInt()
        val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)

        val left = margin
        val top = margin
        val bodyRect = RectF(left, top, left + body, top + body)
        val corner = CORNER_DP * density

        // Outline that the white border + shadow trace: the rounded body plus the pointer wedge as
        // one silhouette, so the shadow reads as a single shape rather than a card with a tab.
        val silhouette = Path().apply {
            addRoundRect(bodyRect, corner, corner, Path.Direction.CW)
            val cx = bodyRect.centerX()
            val tipY = bodyRect.bottom + pointerH
            moveTo(cx - pointerW / 2f, bodyRect.bottom - 1f)
            lineTo(cx + pointerW / 2f, bodyRect.bottom - 1f)
            lineTo(cx, tipY)
            close()
        }

        // 1) Drop shadow + white fill of the silhouette. The shadow layer is attached to this fill
        //    paint, so the white backing casts the soft shadow for the whole pin in one pass.
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            setShadowLayer(
                SHADOW_BLUR_DP * density,
                0f,
                SHADOW_DY_DP * density,
                Color.argb(70, 0, 0, 0),
            )
        }
        canvas.drawPath(silhouette, fill)

        // 2) Photo content / placeholder, clipped to the body's rounded rect (inset by the border so
        //    the white frame stays visible all the way round).
        val inset = bodyRect.run { RectF(left + border, top + border, right - border, bottom - border) }
        val save = canvas.save()
        val clip = Path().apply { addRoundRect(inset, corner - border, corner - border, Path.Direction.CW) }
        canvas.clipPath(clip)
        drawBody(canvas, inset, Paint(Paint.ANTI_ALIAS_FLAG))
        canvas.restoreToCount(save)

        return out
    }

    /** Scale-crop [thumb] to fill [dst] (center-crop), masked to a rounded rect of radius [radius]. */
    private fun drawCoverCroppedThumb(canvas: Canvas, thumb: Bitmap, dst: RectF, radius: Float) {
        val dstW = dst.width()
        val dstH = dst.height()
        val scale = maxOf(dstW / thumb.width, dstH / thumb.height)
        val srcW = (dstW / scale).toInt().coerceIn(1, thumb.width)
        val srcH = (dstH / scale).toInt().coerceIn(1, thumb.height)
        val srcLeft = ((thumb.width - srcW) / 2).coerceAtLeast(0)
        val srcTop = ((thumb.height - srcH) / 2).coerceAtLeast(0)
        val src = Rect(srcLeft, srcTop, srcLeft + srcW, srcTop + srcH)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { isFilterBitmap = true }
        canvas.drawBitmap(thumb, src, dst, paint)
    }

    private fun drawPhotoGlyph(canvas: Canvas, body: RectF, density: Float) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#8A93A0")
            style = Paint.Style.STROKE
            strokeWidth = 2f * density
        }
        val pad = body.width() * 0.28f
        val frame = RectF(body.left + pad, body.top + pad, body.right - pad, body.bottom - pad)
        canvas.drawRoundRect(frame, 3f * density, 3f * density, paint)
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#8A93A0") }
        canvas.drawCircle(
            frame.left + frame.width() * 0.32f,
            frame.top + frame.height() * 0.34f,
            frame.width() * 0.1f,
            fill,
        )
        val mountain = Path().apply {
            moveTo(frame.left, frame.bottom)
            lineTo(frame.left + frame.width() * 0.4f, frame.top + frame.height() * 0.5f)
            lineTo(frame.left + frame.width() * 0.72f, frame.bottom)
            close()
        }
        canvas.drawPath(mountain, fill)
    }
}

/** How many decoded pin thumbnails a map keeps, by cover: a screen holds a few dozen pins, so a pan back finds its own. */
internal const val PIN_THUMB_CACHE_ENTRIES = 128

// Coil decode target for the pin thumbnail: small, since the visible body is ~52dp. Cover-crop
// happens when the bitmap is composited into the rounded body.
private const val PIN_THUMB_PX = 120

/** The decoded pin thumbnails of one map, keyed by the cover photo's id, bounded to [PIN_THUMB_CACHE_ENTRIES]. */
internal fun pinThumbnailCache(): LruCache<String, Bitmap> = LruCache(PIN_THUMB_CACHE_ENTRIES)

/**
 * The finished pin bitmap for [item]: its thumbnail, from the same source the gallery cell uses (a local
 * content uri, or a cloud thumbnail with the store's freshly decrypted URL out of [thumbnailUrls]), decoded
 * through Coil and framed by [ThumbnailPin.build]. Null when there is no source yet or the decode fails.
 */
internal suspend fun loadPinBitmap(
    context: Context,
    item: GalleryItem,
    thumbnailUrls: Map<String, String>,
    density: Float,
): Bitmap? {
    val storeUrl = (item as? GalleryItem.CloudOnly)?.let { thumbnailUrls[it.cloud.linkId] }
    val imageData = photoCellInputsFor(item, cloudThumbnailUrl = storeUrl).imageData ?: return null
    return runCatching {
        val request = ImageRequest.Builder(context)
            .data(imageData)
            .size(PIN_THUMB_PX)
            // Software bitmap so it can be composited onto the pin Canvas.
            .allowHardware(false)
            .build()
        // Not recycled: Coil may hand back a memory-cache-owned bitmap, and the composite reads from it.
        val source = (context.imageLoader.execute(request) as? SuccessResult)?.drawable?.toBitmap()
            ?: return@runCatching null
        ThumbnailPin.build(source, density)
    }.getOrNull()
}

/**
 * The photo count over the top-right corner of a pin, as the desktop map draws it: an accent capsule with the
 * number in white, 22dp tall, reaching 7dp past the corner of the pin body.
 */
internal class PinCountBadge(private val density: Float, textSizePx: Float, accent: Int) {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = accent
        setShadowLayer(4f * density, 0f, 1f * density, Color.argb(102, 0, 0, 0))
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = textSizePx
        textAlign = Paint.Align.CENTER
        typeface = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, 600, false)
        } else {
            android.graphics.Typeface.DEFAULT_BOLD
        }
    }
    private val box = RectF()

    /** Draws [count]'s badge on a pin bitmap whose top-left corner is at ([pinLeft], [pinTop]); one photo draws nothing. */
    fun draw(canvas: Canvas, pinLeft: Float, pinTop: Float, count: Int) {
        val label = pinCountLabel(count) ?: return
        val height = 22f * density
        val width = maxOf(height, text.measureText(label) + 12f * density)
        val right = pinLeft + ThumbnailPin.bodyRight(density) + 7f * density
        val top = pinTop + ThumbnailPin.bodyTop(density) - 7f * density
        box.set(right - width, top, right, top + height)
        canvas.drawRoundRect(box, height / 2f, height / 2f, fill)
        val baseline = box.centerY() - (text.descent() + text.ascent()) / 2f
        canvas.drawText(label, box.centerX(), baseline, text)
    }
}

/**
 * A pin bitmap with the [count] badge of its photos over it, for an OSM marker. The badge reaches past the
 * bitmap's corner; the marker does not clip its icon, so it shows whole.
 */
internal class CountedPinDrawable(
    private val bitmap: Bitmap,
    private val count: Int,
    private val badge: PinCountBadge,
) : android.graphics.drawable.Drawable() {
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)

    override fun getIntrinsicWidth(): Int = bitmap.width

    override fun getIntrinsicHeight(): Int = bitmap.height

    override fun draw(canvas: Canvas) {
        canvas.drawBitmap(bitmap, null, bounds, paint)
        badge.draw(canvas, bounds.left.toFloat(), bounds.top.toFloat(), count)
    }

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
    }

    override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {
        paint.colorFilter = colorFilter
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSLUCENT
}
