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
import androidx.work.WorkManager
import coil.imageLoader
import eu.akoos.photos.data.importer.ImportScanCache
import eu.akoos.photos.domain.usecase.StageImportUseCase
import eu.akoos.photos.service.BackgroundSyncService
import eu.akoos.photos.worker.AlbumDownloadWorker
import eu.akoos.photos.worker.ImportWorker
import eu.akoos.photos.worker.MetadataEditWorker
import eu.akoos.photos.worker.SyncWorker
import java.io.File

/**
 * The parts of leaving an account that are plain files and scheduled work, in one place both ways out
 * of a session reach: the Settings sign-out and the handler a session ended by the server falls into.
 *
 * While these lived inside the Settings screen, only the deliberate sign-out ran them. A session the
 * server ended left the decrypted thumbnails, the full-resolution cache and the staged import
 * thumbnails on disk, and left the backup, import and metadata-edit work scheduled against an account
 * that was gone.
 */
object SignOutCleanup {

    /** The cache subdirectories that hold decrypted photo content of the account that is leaving. */
    val CACHE_DIRS: List<String> = listOf(
        "thumbnails",
        "fullres",
        "fullres-session",
        "editor",
        "video_editor",
        "motion",
        StageImportUseCase.THUMB_DIR,
        ImportScanCache.DIR_NAME,
    )

    /** Stop everything scheduled against the account: backup, album downloads, an import, queued
     *  metadata edits and the service that keeps the process alive. */
    fun stopBackgroundWork(context: Context, workManager: WorkManager) {
        runCatching {
            SyncWorker.cancel(workManager)
            workManager.cancelUniqueWork(SyncWorker.NAME_ONESHOT)
            workManager.cancelUniqueWork(SyncWorker.NAME_CONTENT_OBSERVER)
            workManager.cancelAllWorkByTag(AlbumDownloadWorker.TAG)
            workManager.cancelAllWorkByTag(ImportWorker.TAG)
            workManager.cancelUniqueWork(MetadataEditWorker.UNIQUE_NAME)
            BackgroundSyncService.stop(context)
        }
    }

    /** Delete the decrypted photo content cached on disk and in the image loader. Blocking file work,
     *  so a caller runs it off the main thread. */
    fun clearDecryptedCaches(context: Context) {
        CACHE_DIRS.forEach { File(context.cacheDir, it).deleteRecursively() }
        context.cacheDir.listFiles { f -> f.name.startsWith("album_dl_") }?.forEach { it.delete() }
        val loader = context.imageLoader
        loader.memoryCache?.clear()
        loader.diskCache?.clear()
    }
}
