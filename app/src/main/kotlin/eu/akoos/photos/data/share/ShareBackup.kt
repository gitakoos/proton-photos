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

package eu.akoos.photos.data.share

import android.content.Context
import android.net.Uri
import eu.akoos.photos.R
import eu.akoos.photos.domain.repository.SyncStateRepository
import eu.akoos.photos.domain.usecase.ForceUploadLocalUrisUseCase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.proton.core.domain.entity.UserId

/**
 * Backs up photos another app handed over (a share, or "Open with" from a mail or chat app): each is
 * matched to the photo the phone holds, or saved into the camera folder while the sender's read grant
 * lasts, then queued on the manual back-up on [appScope] so the upload outlives the screen. Returns the
 * line to show the user.
 */
suspend fun backUpSharedMedia(
    context: Context,
    userId: UserId,
    shared: List<Uri>,
    typeHint: String?,
    syncStateRepo: SyncStateRepository,
    forceUpload: ForceUploadLocalUrisUseCase,
    appScope: CoroutineScope,
): String {
    val resolved = withContext(Dispatchers.IO) { SharedMediaResolver(context.contentResolver, context.packageName).resolve(shared, typeHint) }
    val onDrive = if (resolved.uris.isEmpty()) 0 else withContext(Dispatchers.IO) {
        resolved.uris.chunked(500).sumOf { slice -> syncStateRepo.getByUris(slice).count { it.cloudFileId != null } }
    }
    if (resolved.uris.size > onDrive) appScope.launch { forceUpload.forceUpload(userId, resolved.uris) }
    return when {
        resolved.uris.isEmpty() -> context.getString(R.string.share_backup_unreadable)
        resolved.unreadable > 0 -> context.getString(R.string.share_backup_some_unreadable, resolved.unreadable, shared.size)
        onDrive == resolved.uris.size -> context.getString(R.string.device_folder_already_backed_up)
        resolved.saved > 0 -> context.getString(R.string.share_backup_saved_to_phone)
        else -> context.getString(R.string.backup_started)
    }
}
