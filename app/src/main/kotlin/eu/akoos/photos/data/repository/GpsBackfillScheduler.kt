/*
 * Photos for Proton
 * Copyright (C) 2026 Akoos <https://akoos.eu>
 *
 * Source:  https://github.com/gitakoos/proton-photos
 * Website: https://photos.akoos.eu
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

package eu.akoos.photos.data.repository

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import me.proton.core.domain.entity.UserId
import eu.akoos.photos.data.db.dao.PhotoLocationDao
import eu.akoos.photos.data.db.entity.PhotoLocationEntity
import eu.akoos.photos.domain.entity.LocalMediaItem
import eu.akoos.photos.domain.repository.LocalMediaRepository
import eu.akoos.photos.util.ExifHelper
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "GpsBackfillSched"

/**
 * Fills the persisted [PhotoLocationEntity] table with the GPS fix of every on-device photo, so
 * the map view has coordinates to plot. The same source the tag scan uses ([LocalMediaRepository])
 * supplies the items; this reads EXIF GPS for the ones not yet located and upserts the result.
 *
 * EXIF reads touch disk, so [backfillAll] runs them on a small bounded pool ([WORKER_COUNT]
 * permits) the same way the tag and thumbnail schedulers do — a whole-library walk that never
 * floods I/O. It is resumable and idempotent: already-located URIs are skipped via a single
 * [PhotoLocationDao.idsForUser] read, and an in-process [inFlight] set collapses overlapping
 * calls so a second invocation while one is running re-scans nothing twice.
 *
 * On Android 10+ MediaStore redacts EXIF location unless the original is requested, which needs
 * [Manifest.permission.ACCESS_MEDIA_LOCATION]. Without that grant — or on any read error — the
 * photo is skipped, never crashing the walk.
 */
@Singleton
class GpsBackfillScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val localMediaRepository: LocalMediaRepository,
    private val photoLocationDao: PhotoLocationDao,
) {
    /** Concurrency bound on in-flight EXIF reads — a handful keeps disk I/O off any hot path. */
    private val semaphore = Semaphore(WORKER_COUNT)

    /** URIs currently being read, so overlapping [backfillAll] calls don't double-read one file. */
    private val inFlight = ConcurrentHashMap.newKeySet<String>()

    /** Serialises the skip-set read + enqueue so two concurrent walks compute the remainder once. */
    private val walkLock = Mutex()

    /**
     * Read and persist the GPS fix for every device photo not already located for [userId].
     * Suspends until the whole remaining set has been processed. A no-op once the table is
     * complete (the skip read leaves nothing to do). Safe to call repeatedly.
     */
    suspend fun backfillAll(userId: UserId) {
        if (!hasMediaLocationPermission()) {
            Log.i(TAG, "ACCESS_MEDIA_LOCATION not granted; skipping GPS backfill")
            return
        }
        val items = runCatching { localMediaRepository.observeLocalMedia().first() }
            .getOrDefault(emptyList())
        if (items.isEmpty()) return

        val pending = walkLock.withLock {
            val located = runCatching { photoLocationDao.idsForUser(userId.id).toHashSet() }
                .getOrDefault(hashSetOf())
            items.filter { it.uri !in located && inFlight.add(it.uri) }
        }
        if (pending.isEmpty()) return

        // The reservation above runs under walkLock so a concurrent walk skips these URIs, but its
        // removal must cover EVERY exit — including a cancellation that lands after enqueue but
        // before a child's try. Clearing the whole reserved set in a finally on the walk guarantees
        // no URI is stranded in inFlight for the process lifetime.
        try {
            coroutineScope {
                pending.forEach { item ->
                    launch {
                        try {
                            semaphore.withPermit { locateAndStore(item, userId) }
                        } catch (e: Throwable) {
                            if (e is kotlinx.coroutines.CancellationException) throw e
                            Log.w(TAG, "gps read ${item.uri} failed: ${e.message}")
                        }
                    }
                }
            }
        } finally {
            pending.forEach { inFlight.remove(it.uri) }
        }
    }

    /** Read the original GPS for one item — EXIF for an image, the ISO 6709 container atom for a
     *  video — and upsert it when both coordinates are present. */
    private suspend fun locateAndStore(item: LocalMediaItem, userId: UserId) {
        val original = requireOriginalUri(item.uri)
        val coords = if (item.mimeType.startsWith("video/")) {
            videoGps(original)
        } else {
            val meta = ExifHelper.readMetadata(context, original)
            val la = meta.gpsLatitude
            val lo = meta.gpsLongitude
            if (la != null && lo != null) la to lo else null
        } ?: return
        val entity = PhotoLocationEntity(
            id = item.uri,
            userId = userId.id,
            latitude = coords.first,
            longitude = coords.second,
        )
        runCatching { photoLocationDao.upsert(listOf(entity)) }
            .onFailure { Log.w(TAG, "upsert ${item.uri} failed: ${it.message}") }
    }

    /** A video keeps GPS in the container (ISO 6709 location atom), not EXIF, so it's read off the
     *  retriever and the first two signed numbers are taken as lat/lng. Reads the original URI so
     *  Android 10+ doesn't hand back a location-redacted stream (caller already holds the grant). */
    private fun videoGps(uri: String): Pair<Double, Double>? {
        val retriever = android.media.MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, android.net.Uri.parse(uri))
            val loc = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_LOCATION)
                ?: return null
            val m = Regex("""([+-]\d+(?:\.\d+)?)([+-]\d+(?:\.\d+)?)""").find(loc) ?: return null
            val la = m.groupValues[1].toDoubleOrNull() ?: return null
            val lo = m.groupValues[2].toDoubleOrNull() ?: return null
            la to lo
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    /**
     * On Android 10+ EXIF GPS is stripped from the MediaStore stream unless the ORIGINAL is
     * requested via [MediaStore.setRequireOriginal] (gated by ACCESS_MEDIA_LOCATION). Returns the
     * original-tagged URI string; falls back to the plain URI on older OS versions or on failure.
     */
    private fun requireOriginalUri(uri: String): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return uri
        return runCatching {
            MediaStore.setRequireOriginal(Uri.parse(uri)).toString()
        }.getOrDefault(uri)
    }

    private fun hasMediaLocationPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return true
        return ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_MEDIA_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
    }

    private companion object {
        /** EXIF reads are light; a small pool clears a large library without disturbing scroll. */
        const val WORKER_COUNT = 4
    }
}
