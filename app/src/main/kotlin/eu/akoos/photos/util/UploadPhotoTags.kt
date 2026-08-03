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

package eu.akoos.photos.util

/**
 * Pure resolution of the Drive PhotoTag ids one upload commits for a photo.
 *
 * Two sources feed it. [PhotoTagDetector] reads what the file's own bytes prove (a video, a raw
 * frame, a motion photo), and [UserPhotoTags] holds the categories a person picked for that device
 * file. The choice is added to the detection rather than put in its place, so a photo keeps the
 * markers it can only get from its content while gaining the ones only a person can supply, and a
 * file nobody categorised commits exactly what the detector found.
 *
 * Tag 0 (Favorites) is deliberately not committable. The server keeps it on a favourite endpoint of
 * its own and refuses it on the ordinary tag endpoint, so a commit payload carrying it is a commit
 * put at risk, and a rejected commit is a lost upload. The favourite is applied on its own path once
 * the photo exists on Drive.
 *
 * Separated from the upload pipeline so the rule can be verified without a device, a network or a
 * file to encrypt.
 */
object UploadPhotoTags {

    /** Favorites. Carried after the commit through the favourite endpoint, never inside the commit. */
    const val FAVORITE_TAG_ID = 0

    /**
     * The tag ids to send on the commit for a file the detector reports [detected] for and whose
     * owner chose [chosen].
     *
     * The detection leads and the choice follows, each id kept once, so an empty [chosen] yields
     * exactly the list the detector returned. Ids outside the Drive PhotoTag enum are dropped
     * because the server rejects them, and [FAVORITE_TAG_ID] is dropped because it is not a category
     * the commit can carry.
     */
    fun mergeForCommit(detected: Collection<Int>, chosen: Collection<Int>): List<Int> =
        (detected + chosen)
            .filter { it in UserPhotoTags.VALID_IDS && it != FAVORITE_TAG_ID }
            .distinct()
}
