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

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.akoos.photos.R
import eu.akoos.photos.data.hidden.HiddenStorageManager
import eu.akoos.photos.data.offline.OfflineStorageManager
import eu.akoos.photos.data.repository.drive.PhotoDownloadService
import eu.akoos.photos.domain.entity.CloudPhoto
import eu.akoos.photos.domain.entity.GalleryItem
import eu.akoos.photos.domain.entity.LocalMediaItem
import eu.akoos.photos.domain.repository.DrivePhotoRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import me.proton.core.accountmanager.domain.AccountManager
import java.io.File
import javax.inject.Inject

/**
 * The uri another app reads a photo through, for the paths that hand one photo out of the app: a
 * share, "Set as", and the picker other apps open. A device photo goes out under its MediaStore uri
 * and a cloud-only one is decrypted to the fullres cache first; both of the others go through the
 * share provider, which grants the receiver that one file. With strip-on-share on, the receiver gets
 * the stripped copy.
 */
class OutgoingPhotoUris @Inject constructor(
    @ApplicationContext private val context: Context,
    private val cloudRepo: DrivePhotoRepository,
    private val accountManager: AccountManager,
    private val hiddenStorage: HiddenStorageManager,
    private val offlineStore: OfflineStorageManager,
) {

    /** The uri for [item], stripped per [stripConfig] (null passes the original through). */
    suspend fun uriFor(item: GalleryItem, stripConfig: MetadataStripConfig?): Uri {
        val uri = when (item) {
            is GalleryItem.LocalOnly -> localUri(item.local)
            is GalleryItem.Synced    -> localUri(item.local)
            is GalleryItem.CloudOnly -> cloudUri(item.cloud)
        }
        val (mime, name) = ShareIntentBuilder.shareMimeAndName(item)
        return stripForShareOrOriginal(context, uri, mime, name, stripConfig)
    }

    /**
     * A cloud photo's decrypted original, as a share-provider uri named after the photo rather than
     * its linkId. A pinned offline blob already holds the bytes, so it is copied into the fullres path
     * the provider exposes (a local, instant copy) instead of being downloaded again.
     */
    suspend fun cloudUri(photo: CloudPhoto): Uri {
        val file = withContext(Dispatchers.IO) {
            offlineStore.findBlob(photo.linkId)
                ?.takeIf { it.length() > 0 }
                ?.let { blob ->
                    PhotoDownloadService.fullResFile(context, photo)?.also { dest ->
                        if (!dest.exists() || dest.length() == 0L) {
                            dest.parentFile?.mkdirs()
                            blob.copyTo(dest, overwrite = true)
                        }
                    }
                }
        } ?: run {
            val userId = accountManager.getPrimaryUserId().first()
                ?: error(context.getString(R.string.viewer_not_signed_in))
            cloudRepo.downloadFullResPhoto(userId, photo)
        }
        return providerUri(file, photo.displayName)
    }

    /**
     * The uri for a device-backed photo. A MediaStore photo already has one. A vaulted photo does not:
     * it is an app-private `file://` the platform refuses to put in an intent, so it goes out through
     * the share provider, which serves the bytes in place and puts no part of the vault's location in
     * the uri. The vault names its files by a private code, so the receiver is told the name the photo
     * was hidden under.
     */
    fun localUri(local: LocalMediaItem): Uri {
        val parsed = Uri.parse(local.uri)
        if (!hiddenStorage.isHiddenUri(local.uri)) return parsed
        val file = parsed.path?.let { File(it) }?.takeIf { it.exists() }
            ?: error(context.getString(R.string.viewer_unknown_error))
        return providerUri(file, local.displayName)
    }

    private fun providerUri(file: File, displayName: String): Uri =
        FileProvider.getUriForFile(context, "${context.packageName}.share.fileprovider", file).also {
            ShareFileProvider.putDisplayName(it, displayName)
        }
}
