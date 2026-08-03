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

package eu.akoos.photos.presentation.folders

/**
 * What a folder screen is being opened to do, beyond showing the folder.
 *
 * The Albums grid holds a cover and a count per folder, never its photos, so an action that needs
 * the folder's whole list in hand travels as one of these instead of running on the grid. The folder
 * screen owns the list and the player, and carries the intent out on arrival rather than raising its
 * drawer: the grid opens that same drawer, so raising it would swap a sheet for its twin.
 */
enum class DeviceFolderOpenAction {
    /** Back every photo in the folder up to the Drive timeline. */
    BackUpToTimeline,

    /** The same back-up, with the folder opted in to its own Drive album first. */
    BackUpAndMirror,

    /** Open the viewer on the folder's first photo with the slideshow already running. */
    Slideshow,
}

/** The rule that decides when a [DeviceFolderOpenAction] handed to a folder screen runs. */
object DeviceFolderOpenIntent {

    /**
     * True when [action] should be carried out now.
     *
     * Every action runs on the folder's photos, which arrive a frame or more after the screen
     * mounts, so an intent waits for a non-empty [photoCount] rather than firing on an empty list
     * and reporting there was nothing to back up. [alreadyRun] is the within-composition half of the
     * one-shot: the host clears the intent the moment it runs, and this covers the frames before
     * that clear is read back.
     */
    fun shouldRun(action: DeviceFolderOpenAction?, photoCount: Int, alreadyRun: Boolean): Boolean =
        action != null && photoCount > 0 && !alreadyRun
}
