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

package eu.akoos.photos.presentation.common

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import eu.akoos.photos.R

/**
 * The count wording the folder screen uses, "8 photos, 1 video": photos and videos counted apart,
 * a kind with none left out, and the photo count alone when there are no videos.
 */
@Composable
fun photoVideoCountText(photoCount: Int, videoCount: Int): String = joinPhotoVideoCounts(
    photoCount = photoCount,
    videoCount = videoCount,
    photosText = pluralStringResource(R.plurals.count_photos_plural, photoCount, photoCount),
    videosText = pluralStringResource(R.plurals.count_videos_plural, videoCount, videoCount),
)

/** Joins the already resolved photo and video texts the way [photoVideoCountText] shows them. */
internal fun joinPhotoVideoCounts(photoCount: Int, videoCount: Int, photosText: String, videosText: String): String =
    when {
        photoCount > 0 && videoCount > 0 -> "$photosText, $videosText"
        videoCount > 0 -> videosText
        else -> photosText
    }
