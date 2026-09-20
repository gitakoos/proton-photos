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

import androidx.annotation.StringRes
import eu.akoos.photos.R

/**
 * The video editor's colour filters, the same looks the photo editor offers so a clip and a still read
 * alike. Each preset is a 4x5 Android-style colour matrix (rows R,G,B,A; the fifth column is a 0..255
 * offset), matching [eu.akoos.photos.presentation.editor.FilterPreset] value for value. The matrix is
 * consumed two ways: live in the preview as a Media3 [RgbMatrix] effect, and baked at save time as the
 * GL fragment shader's colour matrix. Both want the same 4x4 column-major form, so the conversion lives
 * here once.
 */
enum class VideoFilter(@StringRes val labelRes: Int, private val m: FloatArray?) {
    None(R.string.editor_filter_original, null),
    BlackWhite(
        R.string.editor_filter_bw,
        floatArrayOf(
            0.213f, 0.715f, 0.072f, 0f, 0f,
            0.213f, 0.715f, 0.072f, 0f, 0f,
            0.213f, 0.715f, 0.072f, 0f, 0f,
            0f, 0f, 0f, 1f, 0f,
        ),
    ),
    Sepia(
        R.string.editor_filter_sepia,
        floatArrayOf(
            0.393f, 0.769f, 0.189f, 0f, 0f,
            0.349f, 0.686f, 0.168f, 0f, 0f,
            0.272f, 0.534f, 0.131f, 0f, 0f,
            0f, 0f, 0f, 1f, 0f,
        ),
    ),
    Vintage(
        R.string.editor_filter_vintage,
        floatArrayOf(
            0.9f, 0.1f, 0.1f, 0f, 20f,
            0.1f, 0.85f, 0.1f, 0f, 10f,
            0.1f, 0.2f, 0.7f, 0f, 0f,
            0f, 0f, 0f, 1f, 0f,
        ),
    ),
    Vivid(
        R.string.editor_filter_vivid,
        floatArrayOf(
            1.3f, -0.1f, -0.1f, 0f, 0f,
            -0.1f, 1.3f, -0.1f, 0f, 0f,
            -0.1f, -0.1f, 1.3f, 0f, 0f,
            0f, 0f, 0f, 1f, 0f,
        ),
    ),
    Cool(
        R.string.editor_filter_cool,
        floatArrayOf(
            0.9f, 0f, 0.1f, 0f, 0f,
            0f, 1f, 0f, 0f, 0f,
            0.1f, 0f, 1.1f, 0f, 10f,
            0f, 0f, 0f, 1f, 0f,
        ),
    ),
    Warm(
        R.string.editor_filter_warm,
        floatArrayOf(
            1.1f, 0f, 0f, 0f, 10f,
            0f, 1.0f, 0f, 0f, 5f,
            0f, 0f, 0.9f, 0f, 0f,
            0f, 0f, 0f, 1f, 0f,
        ),
    ),
    Noir(
        R.string.editor_filter_noir,
        floatArrayOf(
            0.389f, 0.763f, 0.148f, 0f, -35f,
            0.389f, 0.763f, 0.148f, 0f, -35f,
            0.389f, 0.763f, 0.148f, 0f, -35f,
            0f, 0f, 0f, 1f, 0f,
        ),
    ),
    Chrome(
        R.string.editor_filter_chrome,
        floatArrayOf(
            1.18f, 0f, 0f, 0f, -8f,
            0f, 1.16f, 0f, 0f, -6f,
            0f, 0f, 1.22f, 0f, 2f,
            0f, 0f, 0f, 1f, 0f,
        ),
    ),
    Matte(
        R.string.editor_filter_matte,
        floatArrayOf(
            0.88f, 0f, 0f, 0f, 22f,
            0f, 0.88f, 0f, 0f, 20f,
            0f, 0f, 0.86f, 0f, 16f,
            0f, 0f, 0f, 1f, 0f,
        ),
    ),
    Dramatic(
        R.string.editor_filter_dramatic,
        floatArrayOf(
            1.25f, -0.06f, -0.06f, 0f, -20f,
            -0.06f, 1.25f, -0.06f, 0f, -20f,
            -0.06f, -0.06f, 1.25f, 0f, -20f,
            0f, 0f, 0f, 1f, 0f,
        ),
    ),
    Fresh(
        R.string.editor_filter_fresh,
        floatArrayOf(
            1.06f, 0f, 0f, 0f, 8f,
            0f, 1.1f, 0f, 0f, 12f,
            0f, 0f, 1.08f, 0f, 12f,
            0f, 0f, 0f, 1f, 0f,
        ),
    ),
    ;

    /**
     * The filter as a 4x4 COLUMN-MAJOR matrix (float[16]) applied to a (R,G,B,1) colour with components
     * in 0..1: the RGB 3x3 block plus the offsets folded into the last column (÷255, since input alpha is
     * 1). GLSL's `mat4 * vec4` and Media3's [RgbMatrix] both read column-major and multiply this way, so
     * one array serves both the live preview and the baked export. Identity for [None].
     */
    fun colorMatrix4x4(): FloatArray {
        val s = m ?: return floatArrayOf(
            1f, 0f, 0f, 0f,
            0f, 1f, 0f, 0f,
            0f, 0f, 1f, 0f,
            0f, 0f, 0f, 1f,
        )
        // s rows: [0..4]=R, [5..9]=G, [10..14]=B; column 4 (indices 4/9/14) is the 0..255 offset.
        return floatArrayOf(
            s[0], s[5], s[10], 0f, // col 0 (input R)
            s[1], s[6], s[11], 0f, // col 1 (input G)
            s[2], s[7], s[12], 0f, // col 2 (input B)
            s[4] / 255f, s[9] / 255f, s[14] / 255f, 1f, // col 3 (offset via input A = 1)
        )
    }

    val isIdentity: Boolean get() = m == null

    /** The raw 4x5 Android colour matrix (20 floats), or null for [None]. Feeds both the chip thumbnail's
     *  Compose `ColorFilter` and the live-preview `RenderEffect` colour filter. */
    fun matrix4x5(): FloatArray? = m
}
