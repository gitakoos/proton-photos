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

package eu.akoos.photos.data.upload

import android.content.Context
import android.graphics.BitmapFactory
import eu.akoos.photos.util.MotionPhotoUtil
import eu.akoos.photos.util.Mp4OutputVerifier
import java.io.File

/**
 * Decides whether a file the upload produced may replace the on-device original. The mirror overwrite
 * cannot be undone once it commits, so the replacement is read back first and anything that does not
 * open as the right kind of media is refused, leaving the original as it is.
 */
internal object MirrorSourceCheck {

    /** What the replacement must still be. */
    enum class Kind { IMAGE, MOTION_PHOTO, VIDEO }

    fun accepts(context: Context, kind: Kind, replacement: File, deviceUri: String): Boolean {
        if (!replacement.isFile || replacement.length() <= 0L) return false
        return runCatching {
            when (kind) {
                Kind.IMAGE -> decodesAsImage(replacement)
                Kind.MOTION_PHOTO -> decodesAsImage(replacement) && MotionPhotoUtil.detect(replacement) != null
                Kind.VIDEO -> Mp4OutputVerifier.verify(context, deviceUri, replacement, sameTracks = false)
            }
        }.getOrDefault(false)
    }

    private fun decodesAsImage(file: File): Boolean {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        return bounds.outWidth > 0 && bounds.outHeight > 0
    }
}
