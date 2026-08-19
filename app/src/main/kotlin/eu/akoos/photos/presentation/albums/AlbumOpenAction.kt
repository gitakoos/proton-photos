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

package eu.akoos.photos.presentation.albums

/**
 * What an album screen is being opened to do, beyond showing the album.
 *
 * The Albums grid holds a cover and a count per album, never its members, so the four actions that
 * need the member list travel as one of these instead of running on the grid. The album screen owns
 * that list, the confirmation, the progress pill and the cancel, and carries the intent out on
 * arrival.
 */
enum class AlbumOpenAction {
    /** Fetch every photo in the album to this device, through the album screen's own confirmation. */
    DownloadAll,

    /** Open the viewer on the album's first photo with the slideshow already running. */
    Slideshow,

    /** Open the picker with the album's current members excluded. */
    AddPhotos,

    /** Open the metadata editor over every photo in the album, each one set for all. */
    EditMetadata,
}

/** The rule that decides when an [AlbumOpenAction] handed to an album screen runs. */
object AlbumOpenIntent {

    /**
     * True when [action] should be carried out now.
     *
     * Every action reads the album's members: three of them act on the photos, and the fourth hands
     * them over as the picker's exclude set, so all four wait for [photosLoaded] rather than firing
     * against the empty list the screen mounts with. Past that point they part ways on [photoCount]:
     * adding is precisely what an empty album is opened for, while downloading, playing or editing
     * nothing is a confirmation with no answer behind it. [alreadyRun] is the within-composition half of the
     * one-shot: the host clears the intent the moment it runs, and this covers the frames before that
     * clear is read back.
     */
    fun shouldRun(
        action: AlbumOpenAction?,
        photoCount: Int,
        photosLoaded: Boolean,
        alreadyRun: Boolean,
    ): Boolean {
        if (action == null || alreadyRun || !photosLoaded) return false
        return when (action) {
            AlbumOpenAction.AddPhotos -> true
            AlbumOpenAction.DownloadAll,
            AlbumOpenAction.Slideshow,
            AlbumOpenAction.EditMetadata -> photoCount > 0
        }
    }
}
