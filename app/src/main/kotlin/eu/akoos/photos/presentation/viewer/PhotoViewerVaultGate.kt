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

import eu.akoos.photos.domain.entity.GalleryItem

/**
 * The viewer actions that send a photo somewhere else. Everything else it offers — favourite, edit,
 * details, metadata, slideshow, reveal, delete — acts on the photo where it already is, so only
 * these five have an answer that turns on the vault.
 */
data class ViewerOutboundActions(
    val backUpToDrive: Boolean,
    val addToAlbum: Boolean,
    val sendToApp: Boolean,
    val shareWithPeople: Boolean,
    val publicLink: Boolean,
) {
    /** Whether the share drawer still has a row to draw, and so whether Share is worth opening. */
    val anyShareRoute: Boolean get() = sendToApp || shareWithPeople || publicLink
}

/**
 * What the viewer may offer for the photo it is showing, once it knows whether that photo lives in
 * the vault.
 *
 * A vaulted photo is an app-private file with no MediaStore row, put there to keep it off the
 * device's other galleries and out of the cloud. Four of the five actions end in an upload — back up,
 * add to album (which uploads a device-only photo first), share with people (which is an album add),
 * and a public link (which uploads before it mints one) — so each of them would put the photo back
 * exactly where it was hidden from. Sending it to another app is the one that does not: it hands over
 * the bytes the user is already looking at, one photo at a time, on request.
 */
object PhotoViewerVaultGate {

    /**
     * The vault file [item] renders from, or null when it is an ordinary photo.
     *
     * Asked of the item rather than of the screen the viewer was opened from, so a vaulted photo is
     * gated wherever it is reached. Only a device-backed item can name one; a cloud photo has no
     * device file, and its own hide is a filter that leaves the photo where it is.
     */
    fun vaultUriOf(item: GalleryItem?, isVaultUri: (String) -> Boolean): String? = when (item) {
        is GalleryItem.LocalOnly -> item.local.uri.takeIf(isVaultUri)
        is GalleryItem.Synced -> item.local.uri.takeIf(isVaultUri)
        is GalleryItem.CloudOnly, null -> null
    }

    /**
     * Which of the outward-bound actions [item] is offered. [vaulted] answers for its device file
     * and comes from [vaultUriOf]; every other gate the viewer applies (a shared-with-me album, a
     * missing cloud id) stays where it is and narrows this further.
     */
    fun outboundActions(item: GalleryItem?, vaulted: Boolean): ViewerOutboundActions = when {
        item == null -> ViewerOutboundActions(
            backUpToDrive = false,
            addToAlbum = false,
            sendToApp = false,
            shareWithPeople = false,
            publicLink = false,
        )
        vaulted -> ViewerOutboundActions(
            backUpToDrive = false,
            addToAlbum = false,
            sendToApp = true,
            shareWithPeople = false,
            publicLink = false,
        )
        else -> ViewerOutboundActions(
            // Only a photo with no cloud copy has anything to back up.
            backUpToDrive = item is GalleryItem.LocalOnly,
            addToAlbum = true,
            sendToApp = true,
            shareWithPeople = true,
            publicLink = true,
        )
    }
}
