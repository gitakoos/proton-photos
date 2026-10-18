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

/** What a long press on the photo does. */
enum class ViewerLongPressAction { ReadText, OpenMenu, None }

/**
 * The long press on the photo. With [opensMenu] it opens the top-right menu, except in trash mode,
 * which has no menu and keeps the text read. A press that lands on the bars around the photo
 * ([onControls]: a button, the seek bar, the filmstrip) never opens the menu, since that press belongs to
 * the control. Either way it does nothing while recognised text is up (a press then belongs to the text
 * selection) or while the details sheet is open.
 */
fun viewerLongPressAction(
    opensMenu: Boolean,
    trashMode: Boolean,
    copyTextEnabled: Boolean,
    textShowing: Boolean,
    metadataShown: Boolean,
    onControls: Boolean = false,
): ViewerLongPressAction = when {
    textShowing || metadataShown -> ViewerLongPressAction.None
    opensMenu && !trashMode -> if (onControls) ViewerLongPressAction.None else ViewerLongPressAction.OpenMenu
    copyTextEnabled -> ViewerLongPressAction.ReadText
    else -> ViewerLongPressAction.None
}

/**
 * Whether reading the text of the photo on screen is turned away: a panorama and an inline motion clip each
 * put their own frame on screen, so the fit the text overlay works from would not be the one the user sees,
 * and a photo the device hide keeps behind a blur is not one to lift words off. The long press and the menu
 * item both ask this.
 */
fun viewerTextReadBlocked(panorama: Boolean, motionClip: Boolean, hiddenBlurred: Boolean): Boolean =
    panorama || motionClip || hiddenBlurred
