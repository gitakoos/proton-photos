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

/**
 * The colour/light adjustment math shared by the photo editor and the video editor, kept free of
 * android.graphics so it unit-tests on the plain JVM. Everything works on a 4x5 Android colour matrix as a
 * 20-float FloatArray in ColorMatrix layout: row-major rows R, G, B, A, each row five floats, with column
 * index 4 (indices 4, 9, 14, 19) the additive offset on a 0..255 channel.
 *
 * The nine adjustments are the sliders both editors expose (each an Int in -100..100, 0 = no change). The
 * composition order and per-adjustment formulas mirror the photo editor's colour pipeline so the same
 * slider values read identically on a still and on a video frame.
 */

private val IDENTITY_4X5 = floatArrayOf(
    1f, 0f, 0f, 0f, 0f,
    0f, 1f, 0f, 0f, 0f,
    0f, 0f, 1f, 0f, 0f,
    0f, 0f, 0f, 1f, 0f,
)

/**
 * The saturation matrix android.graphics.ColorMatrix.setSaturation produces: the standard luminance
 * weights 0.213 / 0.715 / 0.072, where [sat] 1 is identity and [sat] 0 collapses to greyscale.
 */
fun saturationMatrix4x5(sat: Float): FloatArray {
    val invSat = 1f - sat
    val r = 0.213f * invSat
    val g = 0.715f * invSat
    val b = 0.072f * invSat
    return floatArrayOf(
        r + sat, g, b, 0f, 0f,
        r, g + sat, b, 0f, 0f,
        r, g, b + sat, 0f, 0f,
        0f, 0f, 0f, 1f, 0f,
    )
}

/**
 * Concatenate two 4x5 colour matrices exactly as android.graphics.ColorMatrix.postConcat does: the result
 * applies [inner] first and [outer] after (the matrix product outer x inner), treating each as a 4x4
 * linear block plus a translation column. The argument order matches ColorMatrix.setConcat(outer, inner),
 * so a chain built here is bit-for-bit the chain the platform ColorMatrix builds.
 */
fun postConcat4x5(outer: FloatArray, inner: FloatArray): FloatArray {
    val a = outer
    val b = inner
    val out = FloatArray(20)
    var index = 0
    var j = 0
    while (j < 20) {
        for (i in 0 until 4) {
            out[index++] = a[j] * b[i] + a[j + 1] * b[i + 5] + a[j + 2] * b[i + 10] + a[j + 3] * b[i + 15]
        }
        out[index++] = a[j] * b[4] + a[j + 1] * b[9] + a[j + 2] * b[14] + a[j + 3] * b[19] + a[j + 4]
        j += 5
    }
    return out
}

/**
 * The combined 4x5 colour matrix for the nine adjustments, or null when every argument is 0 (nothing to
 * apply). The order of composition and the formulas match the photo editor's original colour pipeline:
 * saturation, then contrast + brightness, then exposure, highlights, shadows, temperature, tone and fade,
 * each folded in with a postConcat so it applies after the ones before it.
 */
fun colorAdjustmentMatrix(
    brightness: Int,
    exposure: Int,
    contrast: Int,
    highlights: Int,
    shadows: Int,
    saturation: Int,
    temperature: Int,
    tone: Int,
    fade: Int,
): FloatArray? {
    if (brightness == 0 && contrast == 0 && saturation == 0 &&
        exposure == 0 && highlights == 0 && shadows == 0 &&
        temperature == 0 && tone == 0 && fade == 0
    ) {
        return null
    }

    val brightnessF = brightness * 1.5f // -150..150 range on a 0..255 channel
    val contrastF = 1f + contrast / 100f // 0..2 multiplier
    val saturationF = 1f + saturation / 100f // 0..2 multiplier
    val translate = (1f - contrastF) * 128f + brightnessF

    val mAdjust = floatArrayOf(
        contrastF, 0f, 0f, 0f, translate,
        0f, contrastF, 0f, 0f, translate,
        0f, 0f, contrastF, 0f, translate,
        0f, 0f, 0f, 1f, 0f,
    )

    var combined = postConcat4x5(saturationMatrix4x5(saturationF), IDENTITY_4X5)
    combined = postConcat4x5(mAdjust, combined)

    // Exposure: multiplicative RGB gain (1 + exposure/100), proportional unlike additive brightness.
    if (exposure != 0) {
        val expScale = 1f + exposure / 100f
        combined = postConcat4x5(
            floatArrayOf(
                expScale, 0f, 0f, 0f, 0f,
                0f, expScale, 0f, 0f, 0f,
                0f, 0f, expScale, 0f, 0f,
                0f, 0f, 0f, 1f, 0f,
            ),
            combined,
        )
    }

    // Highlights: scale RGB by (1 - h/200) plus a small offset, pulling brights down without crushing
    // midtones, a ColorMatrix approximation of a per-pixel highlight curve.
    if (highlights != 0) {
        val hScale = 1f - highlights / 200f
        val hOffset = -highlights * 0.3f
        combined = postConcat4x5(
            floatArrayOf(
                hScale, 0f, 0f, 0f, hOffset,
                0f, hScale, 0f, 0f, hOffset,
                0f, 0f, hScale, 0f, hOffset,
                0f, 0f, 0f, 1f, 0f,
            ),
            combined,
        )
    }

    // Shadows: the opposite of highlights, a positive scale + offset lifting the dark end.
    if (shadows != 0) {
        val sScale = 1f + shadows / 200f
        val sOffset = shadows * 0.3f
        combined = postConcat4x5(
            floatArrayOf(
                sScale, 0f, 0f, 0f, sOffset,
                0f, sScale, 0f, 0f, sOffset,
                0f, 0f, sScale, 0f, sOffset,
                0f, 0f, 0f, 1f, 0f,
            ),
            combined,
        )
    }

    // Temperature: warm (+) shifts R up / B down, cool (-) the reverse; 0.5 scale (+100 gives +-50).
    if (temperature != 0) {
        val t = temperature * 0.5f
        combined = postConcat4x5(
            floatArrayOf(
                1f, 0f, 0f, 0f, t,
                0f, 1f, 0f, 0f, 0f,
                0f, 0f, 1f, 0f, -t,
                0f, 0f, 0f, 1f, 0f,
            ),
            combined,
        )
    }

    // Tone: green (+) / magenta (-) shifts only G; 0.5 scale to match temperature.
    if (tone != 0) {
        val g = tone * 0.5f
        combined = postConcat4x5(
            floatArrayOf(
                1f, 0f, 0f, 0f, 0f,
                0f, 1f, 0f, 0f, g,
                0f, 0f, 1f, 0f, 0f,
                0f, 0f, 0f, 1f, 0f,
            ),
            combined,
        )
    }

    // Fade / matte: positive lifts the black point and eases contrast for a washed film look; negative
    // deepens blacks and adds contrast (punch). One matrix folds the contrast scale and the lift together.
    if (fade != 0) {
        val f = fade / 100f
        val fadeContrast = 1f - f * 0.2f
        val fadeLift = f * 30f
        combined = postConcat4x5(
            floatArrayOf(
                fadeContrast, 0f, 0f, 0f, fadeLift,
                0f, fadeContrast, 0f, 0f, fadeLift,
                0f, 0f, fadeContrast, 0f, fadeLift,
                0f, 0f, 0f, 1f, 0f,
            ),
            combined,
        )
    }

    return combined
}

/**
 * postConcat [b] after [a] (the filter preset's matrix and the adjustments, respectively): [a] applies
 * first, [b] after. Returns null when both are null, or the single non-null one when only one is set.
 */
fun combineColorMatrices4x5(a: FloatArray?, b: FloatArray?): FloatArray? {
    if (a == null) return b
    if (b == null) return a
    return postConcat4x5(b, a)
}

/**
 * Convert a 4x5 Android colour matrix to the 4x4 COLUMN-MAJOR form (float[16]) a GL shader and Media3's
 * RgbMatrix both consume: the RGB 3x3 block into columns 0-2, the offsets (indices 4, 9, 14) divided by
 * 255 into column 3, and 0, 0, 0, 1 as the last row. Identical to VideoFilter.colorMatrix4x4.
 */
fun colorMatrix4x5To4x4ColumnMajor(m4x5: FloatArray): FloatArray {
    val s = m4x5
    return floatArrayOf(
        s[0], s[5], s[10], 0f,
        s[1], s[6], s[11], 0f,
        s[2], s[7], s[12], 0f,
        s[4] / 255f, s[9] / 255f, s[14] / 255f, 1f,
    )
}
