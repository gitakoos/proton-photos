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

import eu.akoos.photos.domain.entity.GalleryItem
import eu.akoos.photos.domain.usecase.DeletePhotoUseCase

/**
 * Single source of truth for the Undo payload of a just-completed delete, so every surface (the
 * timeline, search, album detail, device folders) offers the identical thing instead of each
 * re-deriving the cloud/local/relink split inline. Returns null when nothing about the delete can be
 * reversed, which is also the signal not to raise the Undo bar at all.
 *
 * [items], [freeUpSpace] and [deleteFromCloud] are the same inputs [DeletePhotoUseCase] ran on, so the
 * cloud linkIds and device URIs produced here are exactly the set it trashed:
 * [DeletePhotoUseCase.computeDeleteTargets] is the shared decision. [localRecoverable] is the one fact
 * a pure function cannot see for itself: whether the device copies went to the Android 11+ system trash
 * (recoverable for about 30 days) or were removed for good. A permanent removal (a pre-Android-11
 * delete, or a [hide] flow's MediaStore delete) passes false so no false Undo is offered for a file that
 * cannot come back; only the Android 11+ trash path passes true. Pure: no Context, no ContentResolver,
 * no coroutines.
 */
fun buildDeleteUndoAction(
    items: List<GalleryItem>,
    freeUpSpace: Boolean,
    deleteFromCloud: Boolean,
    hide: Boolean,
    localRecoverable: Boolean,
): UndoAction.Delete? {
    // A hide keeps the file in the app-private vault and removes the MediaStore copy permanently, and
    // never trashes the cloud copy, so there is no delete to reverse; the hide raises its own restore.
    if (hide) return null
    val targets = DeletePhotoUseCase.computeDeleteTargets(items, freeUpSpace, deleteFromCloud)
    val localTrashedUris = if (localRecoverable) targets.localUriStrings else emptyList()
    val trashed = localTrashedUris.toHashSet()
    val syncedRelinks = items.mapNotNull { item ->
        (item as? GalleryItem.Synced)?.takeIf { it.local.uri in trashed }?.let {
            UndoAction.Delete.Relink(it.local.uri, it.cloud.linkId, it.cloud.sizeBytes)
        }
    }
    return if (targets.cloudLinkIds.isNotEmpty() || localTrashedUris.isNotEmpty()) {
        UndoAction.Delete(
            cloudLinkIds = targets.cloudLinkIds,
            localTrashedUris = localTrashedUris,
            syncedRelinks = syncedRelinks,
        )
    } else {
        null
    }
}

/**
 * Single source of truth for the Undo payload of a just-completed hide, so every surface (the
 * timeline, search, the viewer, device folders) offers the identical restore instead of each deciding
 * inline whether a hide can be undone. Returns null when the hide vaulted nothing, which is also the
 * signal not to raise the Undo bar at all.
 *
 * [hiddenUris] are the app-private vault copies the hide created and committed, so restoring them puts
 * exactly those files back in MediaStore. A hide vaults only on-device copies (a cloud-only item has no
 * local file and is dropped before anything is stored), and it moves the bytes into the vault before it
 * removes the MediaStore entry, so the restore does not depend on the Android 11+ system trash the way a
 * delete does: any non-empty vault set is offerable on every surface.
 *
 * [cloudLinkIds] are the backed-up and cloud-only photos the same hide filtered out client-side. They
 * cost one preference write to reverse and nothing on Drive was touched, so they are always offerable
 * too, and carrying them here is what makes the Hide button behave the same way whichever kind of photo
 * it was pressed on. Pure: no Context, no ContentResolver, no coroutines.
 */
fun buildHideUndoAction(
    hiddenUris: List<String>,
    cloudLinkIds: List<String> = emptyList(),
): UndoAction.Hide? =
    if (hiddenUris.isEmpty() && cloudLinkIds.isEmpty()) null
    else UndoAction.Hide(hiddenUris, cloudLinkIds)
