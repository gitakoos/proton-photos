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
import android.util.Log
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import eu.akoos.photos.data.preferences.SettingsKeys
import eu.akoos.photos.data.preferences.settingsDataStore
import eu.akoos.photos.domain.repository.DrivePhotoRepository
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.proton.core.domain.entity.UserId

/**
 * Mirrors uploads into a Drive album named after their device folder, for the folders the user opted
 * into. [prepare] reads the albums once per batch and cleans the stored folder-to-album map; [mirror]
 * adds one uploaded photo, creating the folder's album on first use.
 */
internal class BucketAlbumMirror(
    private val cloudRepo: DrivePhotoRepository,
    private val context: Context,
) {

    /** One batch's album state: the Drive albums by lower-cased name, the folder-to-album cache, and its lock. */
    class Batch(
        val existingAlbumsByName: MutableMap<String, String>,
        val albumCache: MutableMap<String, String>,
        val albumCacheMutex: Mutex,
    )

    suspend fun prepare(userId: UserId, prefs: Preferences): Batch {
        // Build name→linkId map from existing Drive albums (source of truth from cloud).
        // MUTABLE: virtual-album-driven cloud creates below need to merge into this so a
        // second upload to the same virtual album doesn't try to recreate the cloud counterpart.
        val existingAlbumsByName: MutableMap<String, String> = try {
            cloudRepo.loadAlbums(userId).associate { it.name.lowercase() to it.linkId }
                .toMutableMap()
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.w(UPLOAD_TAG, "Could not load existing albums for name matching: ${e.message}")
            mutableMapOf()
        }

        // Rebuild the in-memory bucket→albumLinkId cache from DataStore, but validate each entry
        // against the cloud state. Stale entries (from deleted albums) are dropped so we re-create
        // instead of endlessly trying to add photos to a non-existent album.
        val albumCache = mutableMapOf<String, String>().apply {
            (prefs[SettingsKeys.ALBUM_BUCKET_MAP] ?: emptySet()).forEach { entry ->
                val idx = entry.indexOf('=')
                if (idx > 0) {
                    val bucket = entry.substring(0, idx)
                    val storedLinkId = entry.substring(idx + 1)
                    // Only keep if the cloud still has an album with this name AND its linkId matches
                    val cloudLinkId = existingAlbumsByName[bucket.lowercase()]
                    if (cloudLinkId != null) {
                        put(bucket, cloudLinkId)  // use cloud linkId (authoritative)
                    } else {
                        Log.d(UPLOAD_TAG, "albumCache: dropping stale entry '$bucket'=$storedLinkId (album deleted)")
                    }
                }
            }
        }
        // Persist the cleaned cache immediately so stale entries don't survive app restarts
        context.settingsDataStore.edit { p ->
            p[SettingsKeys.ALBUM_BUCKET_MAP] = albumCache.entries.map { "${it.key}=${it.value}" }.toSet()
        }
        // Album-cache mutations (read-check-create-publish) race when multiple uploads target
        // the same new album. Guard cache reads/writes + the DataStore persist with this mutex
        // so two parallel uploads of "Camera" photos don't both call createDriveAlbum.
        val albumCacheMutex = Mutex()
        return Batch(existingAlbumsByName, albumCache, albumCacheMutex)
    }

    suspend fun mirror(
        userId: UserId,
        batch: Batch,
        bucketName: String?,
        albumOptInFolders: eu.akoos.photos.data.preferences.AlbumMirrorFolders,
        cloudId: String,
    ) {
        val existingAlbumsByName = batch.existingAlbumsByName
        val albumCache = batch.albumCache
        val albumCacheMutex = batch.albumCacheMutex
        // Auto-sync to the bucket-name Drive album (Camera, Screenshots, …) for folders
        // the user opted into mirroring. Bucket-name-album mirror is opt-in per folder;
        // photos still upload either way — only the album mirror step is gated.
        val targetAlbumNames = buildSet {
            bucketName?.takeIf { it in albumOptInFolders }?.let { add(it) }
        }
        for (albumName in targetAlbumNames) {
            try {
                // Resolve-or-create the album linkId under a mutex so two parallel
                // uploads to the same new album don't both call createDriveAlbum.
                val albumLinkId = albumCacheMutex.withLock {
                    albumCache[albumName]
                        ?: existingAlbumsByName[albumName.lowercase()]?.also { linkId ->
                            // Found an existing album — add to cache so we skip the lookup next time
                            albumCache[albumName] = linkId
                            context.settingsDataStore.edit { p ->
                                p[SettingsKeys.ALBUM_BUCKET_MAP] = albumCache.entries.map { "${it.key}=${it.value}" }.toSet()
                            }
                            Log.d(UPLOAD_TAG, "Matched existing album '$albumName' → $linkId")
                        }
                        ?: run {
                            val newAlbum = cloudRepo.createDriveAlbum(userId, albumName)
                            albumCache[albumName] = newAlbum.linkId
                            // Keep the in-memory name→linkId index in sync so later iterations
                            // in this same batch don't try to re-create the album.
                            existingAlbumsByName[albumName.lowercase()] = newAlbum.linkId
                            context.settingsDataStore.edit { p ->
                                p[SettingsKeys.ALBUM_BUCKET_MAP] = albumCache.entries.map { "${it.key}=${it.value}" }.toSet()
                            }
                            Log.d(UPLOAD_TAG, "Created new album '$albumName' → ${newAlbum.linkId}")
                            newAlbum.linkId
                        }
                }
                runCatching { cloudRepo.addPhotosToAlbum(userId, albumLinkId, listOf(cloudId)) }
                    .onSuccess { Log.d(UPLOAD_TAG, "Added $cloudId to album '$albumName' ($albumLinkId)") }
                    .onFailure { e -> Log.w(UPLOAD_TAG, "addPhotosToAlbum failed for '$albumName': ${e.message}") }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.w(UPLOAD_TAG, "Album sync failed for '$albumName': ${e.message}")
                // Non-fatal: photo is uploaded, album sync is best-effort
            }
        }
    }
}
