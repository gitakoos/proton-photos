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

import eu.akoos.photos.domain.entity.LocalMediaItem

/**
 * The one definition of a device-folder card, and of which side of the hidden line it falls on.
 *
 * Two surfaces draw these cards — the Albums grid and the hidden area that reveals them again — and
 * they describe two different things: the grid card says what the folder still holds on the device
 * ([build] then [visible]), while the hidden card says what the vault holds for it ([hidden]). A
 * folder the vault holds entirely has nothing left on the device for [build] to find at all, which
 * is why the hidden side reads the vault's own records rather than a device scan.
 *
 * A photo is a MediaStore row or a vault file, never both, and each side reads only its own: [build]
 * groups a device listing every vaulted photo has already left, dropping any uri the published vault
 * index still names, while [hidden] lists nothing but that index. One folder can therefore hold a
 * card on each side at once — after a hide, for photos taken since — and the two can never carry the
 * same photo.
 */
object DeviceFolderCards {

    /**
     * Group MediaStore items into one card per bucket: a pinned or newest cover, and a count.
     *
     * [hiddenUris] are the individual photos in the vault; they leave the device's own listings
     * entirely, so they are dropped before grouping rather than counted in a folder they can no
     * longer be opened from. A cover in [pinnedCovers] the folder no longer holds — deleted, hidden,
     * or moved out — falls back to the newest photo rather than leaving the card blank.
     */
    fun build(
        items: List<LocalMediaItem>,
        hiddenUris: Set<String>,
        pinnedCovers: Map<String, String>,
    ): List<DeviceFolder> = items
        .filter { it.bucketName != null && it.uri !in hiddenUris }
        .groupBy { it.bucketName!! }
        .map { (name, groupItems) ->
            val sorted = groupItems.sortedByDescending { it.dateTaken }
            val pinned = pinnedCovers[name]?.takeIf { uri -> sorted.any { it.uri == uri } }
            DeviceFolder(
                name = name,
                coverUri = pinned ?: sorted.firstOrNull()?.uri,
                itemCount = sorted.size,
            )
        }
        .sortedByDescending { it.itemCount }

    /**
     * The cards the Albums grid draws: every folder that still holds a photo on the device.
     *
     * The stored hidden names are deliberately not consulted. A vaulted photo has no MediaStore row,
     * so [build] never counts one and a folder the vault holds entirely produces no card at all — it
     * stays off the grid by construction rather than by name. A folder the vault holds only part of
     * keeps a card for the rest: that is the truth about what is on the device, and the card is the
     * only way those photos can be opened, selected, or hidden in their turn.
     *
     * Filtering on the names instead loses that card the moment a hidden folder gains a photo: the
     * photos reach every listing this grid feeds while the folder holding them is nowhere on it, and
     * the folder-wide hide can then only be reached by revealing the vault first, which drags back
     * out exactly what the user put away.
     */
    fun visible(folders: List<DeviceFolder>): List<DeviceFolder> = folders.filter { it.itemCount > 0 }

    /**
     * The cards the hidden area draws: one per stored name, counted and covered by what the vault
     * actually holds for it.
     *
     * A folder whose photos are all in the vault leaves no MediaStore row carrying its bucket name,
     * so [build] cannot see it at all. Driving this list off the stored names instead means the
     * folder is still there to be revealed, and [vaultedByFolder] (bucket name → its vault uris,
     * newest first, from [eu.akoos.photos.data.hidden.HiddenFolderRecords]) is the one source for
     * both the count and the cover.
     *
     * Reading the device instead would count the wrong photos: once a hide has finished, a photo the
     * folder still lists is one that is NOT hidden — a shot taken after the hide — and a card that
     * counted it would promise the vault holds something it does not. The vault index carries each
     * photo once whether or not the device has caught up with the delete, so a hide still in flight
     * counts right as well.
     *
     * A name with nothing behind it still draws a card: a card with nothing in it is the only way its
     * name can be cleared from the stored set.
     */
    fun hidden(
        hiddenNames: Set<String>,
        vaultedByFolder: Map<String, List<String>> = emptyMap(),
    ): List<DeviceFolder> = hiddenNames
        .map { name ->
            val vaulted = vaultedByFolder[name].orEmpty()
            DeviceFolder(
                name = name,
                coverUri = vaulted.firstOrNull(),
                itemCount = vaulted.size,
            )
        }
        .sortedWith(compareByDescending<DeviceFolder> { it.itemCount }.thenBy { it.name })
}
