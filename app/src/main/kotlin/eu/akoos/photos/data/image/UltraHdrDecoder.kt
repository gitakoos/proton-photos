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

package eu.akoos.photos.data.image

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.graphics.drawable.BitmapDrawable
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import coil.ImageLoader
import coil.annotation.ExperimentalCoilApi
import coil.decode.ContentMetadata
import coil.decode.DecodeResult
import coil.decode.DecodeUtils
import coil.decode.Decoder
import coil.decode.ImageSource
import coil.fetch.SourceResult
import coil.request.ImageRequest
import coil.request.Options
import coil.request.Parameters
import coil.size.Dimension
import coil.size.Scale
import coil.size.Size
import coil.size.isOriginal
import coil.size.pxOrElse
import eu.akoos.photos.util.UltraHdrUtil
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runInterruptible

/** Request parameter that opts a single load into the Ultra HDR decode path. */
private const val ULTRA_HDR_PARAMETER = "eu.akoos.photos.ultra_hdr"

internal const val ULTRA_HDR_TAG = "UltraHdr"

/**
 * Opts this one request into [UltraHdrDecoder], so an Ultra HDR still decodes with its gain map
 * attached instead of as a flat SDR frame. Strictly opt-in: an HDR bitmap carries the base image
 * PLUS a gain map plane, so only the full-screen viewer asks for it and every grid, thumbnail,
 * widget and map load keeps the ordinary decode path untouched.
 *
 * The parameter deliberately carries no memory cache key. Coil would otherwise fold it into the key
 * and the viewer's own off-page render of the same photo (which does not opt in) would miss and
 * decode a second full-resolution copy of it, doubling the bytes held for one photo. Sharing the
 * entry is safe because the opted-in load is the one that populates it: an off-page render only
 * draws a model the settled page already requested.
 */
fun ImageRequest.Builder.decodeUltraHdr(): ImageRequest.Builder =
    setParameter(key = ULTRA_HDR_PARAMETER, value = true, memoryCacheKey = null)

/** True when the request carrying these [Parameters] opted into the Ultra HDR decode path. */
private fun Parameters.isUltraHdrRequested(): Boolean = value<Boolean>(ULTRA_HDR_PARAMETER) == true

/**
 * The pure decision behind [UltraHdrDecoder.Factory]: may this request take the gain-map-preserving
 * decode? Held apart from the decoder as a plain value function so the whole branch matrix is
 * pinned by a JVM test with no Android, no Coil pipeline and no real file.
 */
object UltraHdrDecodeGate {

    /**
     * True only when [optedIn] is set, [sdkInt] is at or above the API whose decoder attaches a
     * `Gainmap`, and [hasGainMap] confirms the bytes carry one.
     *
     * [hasGainMap] reads the file's header, and this factory is consulted for every image the app
     * loads, so it is queried LAST: the two cheap conditions decide every grid, thumbnail, widget
     * and map load before the probe can ever run.
     */
    fun handlesUltraHdr(
        sdkInt: Int,
        optedIn: Boolean,
        hasGainMap: () -> Boolean = { false },
    ): Boolean {
        if (!optedIn || sdkInt < GAIN_MAP_DECODE_MIN_SDK) return false
        return hasGainMap()
    }

    /** `BitmapFactory` has no notion of a gain map at any API level, and `ImageDecoder` only
     *  attaches one from API 34. Held as a plain int so the gate stays a pure value decision the
     *  caller drives with its own SDK level. */
    const val GAIN_MAP_DECODE_MIN_SDK = 34
}

/**
 * Decodes an Ultra HDR still through [ImageDecoder] so the decoded [Bitmap] keeps its gain map, the
 * only route by which the full-screen viewer can render the photo as real HDR. Coil's own decoder
 * goes through `BitmapFactory`, which has no gain-map support at all and always yields the flat SDR
 * frame.
 *
 * Only reached through [Factory], which self-gates on the request's opt-in, the platform level and
 * the actual bytes, so nothing else in the app changes decode path.
 */
@RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
class UltraHdrDecoder(
    private val decoderSource: ImageDecoder.Source,
    private val options: Options,
) : Decoder {

    override suspend fun decode(): DecodeResult? = runInterruptible {
        var sampled = false
        val bitmap: Bitmap? = try {
            ImageDecoder.decodeBitmap(decoderSource) { decoder, info, _ ->
                val srcWidth = info.size.width
                val srcHeight = info.size.height
                val dstWidth = options.size.targetWidth(options.scale) { srcWidth }
                val dstHeight = options.size.targetHeight(options.scale) { srcHeight }
                if (srcWidth > 0 && srcHeight > 0 &&
                    (srcWidth != dstWidth || srcHeight != dstHeight)
                ) {
                    val multiplier = DecodeUtils.computeSizeMultiplier(
                        srcWidth = srcWidth,
                        srcHeight = srcHeight,
                        dstWidth = dstWidth,
                        dstHeight = dstHeight,
                        scale = options.scale,
                    )
                    // Honouring the requested size is what keeps a 50 MP still from decoding at full
                    // resolution into a heap that also has to hold the gain map plane.
                    sampled = multiplier < 1
                    if (sampled || !options.allowInexactSize) {
                        decoder.setTargetSize(
                            (multiplier * srcWidth).roundToInt(),
                            (multiplier * srcHeight).roundToInt(),
                        )
                    }
                }
                decoder.isUnpremultipliedRequired = !options.premultipliedAlpha
                // Allocator, memory policy and target color space are all left at the platform
                // default on purpose. Every one of them (a forced software buffer, the low-RAM
                // policy behind allowRgb565, a pinned SDR color space) drops the gain map or the
                // headroom that makes it mean anything, which would leave a decode that costs more
                // and still renders SDR.
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: InterruptedException) {
            throw e
        } catch (e: Exception) {
            // Nothing here consumed the image source: the decode reads the file or the content URI
            // directly. Returning null hands the untouched source to the next factory, so a still
            // this decoder cannot handle still paints through Coil's ordinary path.
            null
        }
        // Coil's engine closes the fetch source itself, in a finally that covers both outcomes, so
        // nothing here has to hand it back or tear it down.
        if (bitmap == null) {
            null
        } else {
            // The one signal that says whether a photo took this path and came out with its gain map,
            // which nothing on screen can tell apart from a bright SDR frame.
            Log.d(ULTRA_HDR_TAG, "decoded ${bitmap.width}x${bitmap.height} gainMap=${bitmap.hasGainmap()}")
            DecodeResult(
                drawable = BitmapDrawable(options.context.resources, bitmap),
                isSampled = sampled,
            )
        }
    }

    class Factory : Decoder.Factory {

        override fun create(
            result: SourceResult,
            options: Options,
            imageLoader: ImageLoader,
        ): Decoder? {
            // Spelled against the platform constant because that is the form the platform-API check
            // reads at the construction site below; [UltraHdrDecodeGate.handlesUltraHdr] carries the
            // same level as a plain int so the decision stays a pure value a JVM test can drive.
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return null
            val handles = UltraHdrDecodeGate.handlesUltraHdr(
                sdkInt = Build.VERSION.SDK_INT,
                optedIn = options.parameters.isUltraHdrRequested(),
            ) { result.source.carriesGainMap() }
            if (!handles) return null

            // Only a source that can be re-opened by path or by content URI qualifies. That keeps
            // the okio source untouched, which is what lets both this factory and a failed decode
            // fall through to Coil's decoder with the bytes still unread.
            val decoderSource = result.source.reopenableDecoderSource(options.context) ?: return null
            return UltraHdrDecoder(decoderSource, options)
        }

        override fun equals(other: Any?) = other is Factory

        override fun hashCode() = javaClass.hashCode()
    }
}

/**
 * True when this source's header advertises a gain map. Reads through [okio.BufferedSource.peek] so
 * the bytes stay available to whichever decoder ends up handling the request, and treats any
 * unreadable or unpeekable source as "no gain map" rather than risk consuming it.
 */
private fun ImageSource.carriesGainMap(): Boolean = try {
    source().peek().inputStream().use { UltraHdrUtil.hasGainMap(it) }
} catch (e: Exception) {
    false
}

/**
 * An [ImageDecoder.Source] for this image source that reads the bytes afresh, or null when the only
 * way to reach them would be to drain the okio source.
 */
@RequiresApi(Build.VERSION_CODES.P)
@OptIn(ExperimentalCoilApi::class)
private fun ImageSource.reopenableDecoderSource(context: Context): ImageDecoder.Source? {
    fileOrNull()?.let { return ImageDecoder.createSource(it.toFile()) }
    (metadata as? ContentMetadata)?.let {
        return ImageDecoder.createSource(context.contentResolver, it.uri)
    }
    return null
}

/** Coil's own pixel resolution of a requested [Size], which is internal to the library. */
private inline fun Size.targetWidth(scale: Scale, original: () -> Int): Int =
    if (isOriginal) original() else width.toTargetPx(scale)

/** @see targetWidth */
private inline fun Size.targetHeight(scale: Scale, original: () -> Int): Int =
    if (isOriginal) original() else height.toTargetPx(scale)

/** An undefined dimension is unbounded, which fills at nothing and fits at everything. */
private fun Dimension.toTargetPx(scale: Scale): Int = pxOrElse {
    when (scale) {
        Scale.FILL -> Int.MIN_VALUE
        Scale.FIT -> Int.MAX_VALUE
    }
}
