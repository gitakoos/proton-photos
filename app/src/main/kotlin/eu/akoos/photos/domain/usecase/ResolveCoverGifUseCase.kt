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

package eu.akoos.photos.domain.usecase

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import me.proton.core.domain.entity.UserId
import eu.akoos.photos.data.db.dao.PhotoListingDao
import eu.akoos.photos.data.preferences.SettingsKeys
import eu.akoos.photos.data.preferences.settingsDataStore
import eu.akoos.photos.data.repository.drive.PhotoDownloadService
import eu.akoos.photos.domain.repository.DrivePhotoRepository
import eu.akoos.photos.util.NetworkObserver
import javax.inject.Inject

/**
 * Resolves an album cover to a playable local GIF file when that cover is a GIF, so the album grid
 * and detail hero can animate it. Returns a `file://` path or null: null whenever the cover is not a
 * GIF, its row is not cached locally, or a metered-network policy holds the full-resolution download
 * back, so the caller keeps the static thumbnail. Only an actual GIF cover ever downloads, so a
 * non-GIF album costs a single local DB read and nothing more.
 *
 * Never throws: every failure resolves to null. Cloud only: a signed-out session cannot fetch the
 * original and always resolves to null, leaving covers static.
 */
class ResolveCoverGifUseCase @Inject constructor(
    @ApplicationContext private val context: Context,
    private val photoListingDao: PhotoListingDao,
    private val driveRepo: DrivePhotoRepository,
    private val networkObserver: NetworkObserver,
) {
    suspend fun resolve(userId: UserId, coverLinkId: String): String? {
        if (coverLinkId.isBlank()) return null
        return withContext(Dispatchers.IO) {
            runCatching {
                val entity = photoListingDao.getByLinkId(coverLinkId) ?: return@runCatching null
                if (!entity.mimeType.equals(GIF_MIME, ignoreCase = true)) return@runCatching null
                val photo = entity.toDomain()
                // Already on disk: reuse it, and skip the Wi-Fi gate since no metered bytes are spent.
                val cached = PhotoDownloadService.fullResFile(context, photo)
                    ?.takeIf { it.exists() && it.length() > 0 }
                if (cached != null) return@runCatching "file://${cached.absolutePath}"
                // Not cached yet: fetch the full original only when the metered-network policy allows.
                if (!fullResDownloadAllowed()) return@runCatching null
                val file = driveRepo.downloadFullResPhoto(userId, photo)
                "file://${file.absolutePath}"
            }.getOrNull()
        }
    }

    /**
     * Mirrors the photo viewer's Wi-Fi-only-for-fullres gate: on a metered link the multi-MB original
     * is held back and the static thumbnail stands in, unless the user turned the preference off.
     */
    private suspend fun fullResDownloadAllowed(): Boolean {
        val wifiOnly = context.settingsDataStore.data
            .map { it[SettingsKeys.FULLRES_WIFI_ONLY] }
            .first() != false
        return !wifiOnly || networkObserver.isUnmetered.value
    }

    companion object {
        private const val GIF_MIME = "image/gif"
    }
}
