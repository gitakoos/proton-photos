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

package eu.akoos.photos.data.repository

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import me.proton.core.domain.entity.UserId
import eu.akoos.photos.data.db.dao.PhotoLocationDao
import eu.akoos.photos.data.db.entity.PhotoLocationEntity
import eu.akoos.photos.data.preferences.SettingsKeys
import eu.akoos.photos.data.preferences.settingsDataStore
import eu.akoos.photos.domain.entity.LocalMediaItem
import eu.akoos.photos.domain.repository.LocalMediaRepository
import eu.akoos.photos.util.CaptureDateOverride
import eu.akoos.photos.util.ExifDateFormat
import eu.akoos.photos.util.ExifHelper
import eu.akoos.photos.util.Mp4CreationTime
import eu.akoos.photos.util.PhotoGpsResolver
import eu.akoos.photos.util.hasMediaLocationGrant
import eu.akoos.photos.util.originalUriForExif
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "LocalExifBackfill"

/**
 * Recovers what only an on-device file itself knows, in ONE walk that reads a file only for the legs
 * it still owes. Two legs run:
 *
 *  - GPS, persisted as a [PhotoLocationEntity] so the map has coordinates to plot. Covers images
 *    (EXIF) and videos (the ISO 6709 container atom). Android 10+ redacts EXIF location unless the
 *    ORIGINAL is requested, which needs ACCESS_MEDIA_LOCATION, so this leg runs only with that grant
 *    and only through [originalUriForExif].
 *  - The capture date, recorded in the override map (SettingsKeys.DOWNLOAD_DATE_OVERRIDES) when the
 *    MediaStore DATE_TAKEN column is missing or disagrees with the date inside the file: an image's
 *    EXIF, a video's mvhd creation time. Android redacts location, not timestamps, so this leg needs
 *    no permission and reads the plain URI.
 *
 * The same source the tag scan uses ([LocalMediaRepository]) supplies the items, and each one is
 * classified into the legs it still owes, so a file needing neither is never opened. EXIF reads
 * touch disk, so [backfillAll] runs them on a small bounded pool ([WORKER_COUNT] permits) the same
 * way the tag and thumbnail schedulers do: a whole-library walk that never floods I/O. It is
 * resumable and idempotent, each leg carrying its own skip set (the located URIs for GPS, the
 * already recorded URIs for the date), and an in-process [inFlight] set collapses overlapping calls
 * so a second invocation while one is running re-reads nothing. The date skip set holds only the
 * entries that still match their file's DATE_MODIFIED: one the file has outgrown is dropped instead,
 * which is what lets an EXIF edit made in another app reach a file the walk has already recorded.
 *
 * A read error on one file skips that file and never aborts the walk.
 */
@Singleton
class LocalExifBackfillScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val localMediaRepository: LocalMediaRepository,
    private val photoLocationDao: PhotoLocationDao,
) {
    /** Concurrency bound on in-flight EXIF reads, a handful keeping disk I/O off any hot path. */
    private val semaphore = Semaphore(WORKER_COUNT)

    /** URIs currently being read, so overlapping [backfillAll] calls don't double-read one file. */
    private val inFlight = ConcurrentHashMap.newKeySet<String>()

    /** Serialises the skip-set read + enqueue so two concurrent walks compute the remainder once. */
    private val walkLock = Mutex()

    /** One file the walk will open, with the legs it still owes, so nothing is read twice. */
    private class Pending(val item: LocalMediaItem, val needsGps: Boolean, val needsDate: Boolean)

    /**
     * Read every device photo that still owes a GPS fix or a capture-date check for [userId] and
     * persist both results. Suspends until the whole remaining set has been processed. A no-op once
     * both skip sets are complete (the reads leave nothing to do). Safe to call repeatedly.
     */
    suspend fun backfillAll(userId: UserId) {
        val canReadLocation = hasMediaLocationGrant(context)
        if (!canReadLocation) {
            Log.i(TAG, "ACCESS_MEDIA_LOCATION not granted; GPS leg skipped, date leg still runs")
        }
        val items = runCatching { localMediaRepository.observeLocalMedia().first() }
            .getOrDefault(emptyList())
        if (items.isEmpty()) return

        // Recorded dates the walk finds void, dropped in the same edit that writes the corrections.
        val voided = HashSet<String>()
        // Entries recorded before the map carried a modified time, taken over rather than dropped.
        // See the walk below for why dropping them cannot be undone.
        val adopted = HashMap<String, CaptureDateOverride.Entry>()
        val pending = walkLock.withLock {
            val located: Set<String> = if (!canReadLocation) emptySet() else
                runCatching { photoLocationDao.idsForUser(userId.id).toHashSet() }
                    .getOrDefault(hashSetOf())
            val dated: Map<String, CaptureDateOverride.Entry> = runCatching {
                CaptureDateOverride.parse(
                    context.settingsDataStore.data.first()[SettingsKeys.DOWNLOAD_DATE_OVERRIDES]
                )
            }.getOrDefault(emptyMap())
            items.mapNotNull { item ->
                val needsGps = canReadLocation && item.uri !in located
                val recorded = dated[item.uri]
                // A recorded date describes the file as it stood when it was written. Once the file's
                // DATE_MODIFIED moves, something outside the app has edited it and the entry is void,
                // so this walk only drops it: the item's own date already reads back THROUGH that
                // entry, and re-deciding against it could confirm nothing but itself. With the entry
                // gone, the refresh the persist fires hands the next walk the date the provider
                // re-derived, which is what a fresh correction has to be measured against.
                if (recorded != null) {
                    if (recorded.modifiedSeconds == null) {
                        // An entry written before this map carried a modified time. It is not stale,
                        // it is just older than the format, and it must not be dropped: the date in
                        // it came from the photo's Drive metadata at download time, while this walk
                        // can only ever re-derive from the file's own header. For the very formats
                        // the map exists for, a file often carries no capture date of its own, so a
                        // drop is permanent and the photo's date falls back to the day it was
                        // downloaded. Stamping the file's current modified time takes the entry over
                        // as it stands, which is exactly what a fresh reading of this file would say.
                        adopted[item.uri] = CaptureDateOverride.Entry(recorded.captureMs, item.dateModified)
                    } else if (!CaptureDateOverride.isCurrent(recorded, item.dateModified)) {
                        voided += item.uri
                    }
                }
                val needsDate = recorded == null
                if (!needsGps && !needsDate) null
                else if (!inFlight.add(item.uri)) null
                else Pending(item, needsGps, needsDate)
            }
        }
        if (pending.isEmpty() && voided.isEmpty() && adopted.isEmpty()) return

        // Every date correction the walk finds lands here and is written in ONE DataStore edit at the
        // end, so a large library costs one edit instead of one per corrected photo.
        val corrections = ConcurrentHashMap<String, CaptureDateOverride.Entry>()
        // Adoptions ride the same single edit as the walk's own findings. A later real correction for
        // the same uri overwrites this one, since the walk fills corrections after this point.
        corrections.putAll(adopted)
        // The reservation above runs under walkLock so a concurrent walk skips these URIs, but its
        // removal must cover EVERY exit, including a cancellation that lands after enqueue but
        // before a child's try. Clearing the whole reserved set in a finally on the walk guarantees
        // no URI is stranded in inFlight for the process lifetime.
        try {
            coroutineScope {
                pending.forEach { entry ->
                    launch {
                        try {
                            semaphore.withPermit { readAndStore(entry, userId, corrections) }
                        } catch (e: Throwable) {
                            if (e is kotlinx.coroutines.CancellationException) throw e
                            Log.w(TAG, "exif read ${entry.item.uri} failed: ${e.message}")
                        }
                    }
                }
            }
        } finally {
            pending.forEach { inFlight.remove(it.item.uri) }
            // NonCancellable so a cancelled walk still banks the corrections it already found:
            // dropping them would only make the next walk re-read the very same files.
            withContext(NonCancellable) { persistDates(corrections, voided) }
        }
    }

    /** Run the legs [entry] still owes, off ONE read of the file where the two share a source, and
     *  store what each produced. */
    private suspend fun readAndStore(
        entry: Pending,
        userId: UserId,
        corrections: MutableMap<String, CaptureDateOverride.Entry>,
    ) {
        val item = entry.item
        if (item.mimeType.startsWith("video/")) {
            // A video keeps neither leg in EXIF: GPS sits in the container's location atom and the
            // capture time in the mvhd, so each leg reads its own source. The GPS leg reads the
            // original-tagged URI, so Android 10+ hands back a stream with the location left in
            // (the caller already holds the grant).
            if (entry.needsGps) {
                PhotoGpsResolver.videoGps(context, originalUriForExif(context, item.uri))
                    ?.let { storeLocation(item.uri, userId, it) }
            }
            if (entry.needsDate) {
                val correction = CaptureDateOverride.captureDateCorrection(
                    rawDateTakenMs = item.dateTaken,
                    exifMs = mvhdCaptureDate(item.uri),
                    // An mvhd instant counts as carrying NO offset. The spec calls the field UTC, but
                    // a large share of cameras write local wall clock into it, so the exact-instant
                    // slack would read a plain zone difference as a wrong date and shift a whole video
                    // library by hours. The two-day slack no zone can reach still catches a date wrong
                    // by months.
                    exifHasOffset = false,
                )
                if (correction != null) {
                    corrections[item.uri] = CaptureDateOverride.Entry(correction, item.dateModified)
                }
            }
            return
        }
        // The original-tagged URI is what the GPS leg needs; the date leg reads the plain URI,
        // because Android redacts location, not timestamps.
        val source = if (entry.needsGps) originalUriForExif(context, item.uri) else item.uri
        val meta = ExifHelper.readMetadata(context, source)
        if (entry.needsGps) {
            val la = meta.gpsLatitude
            val lo = meta.gpsLongitude
            if (la != null && lo != null) storeLocation(item.uri, userId, la to lo)
        }
        if (entry.needsDate) {
            val exifMs = (meta.dateTimeOriginal ?: meta.dateTime)?.let {
                ExifDateFormat.fromExif(it, meta.offsetTimeOriginal, ZoneId.systemDefault())
            }
            val correction = CaptureDateOverride.captureDateCorrection(
                rawDateTakenMs = item.dateTaken,
                exifMs = exifMs,
                exifHasOffset = ExifDateFormat.hasUsableOffset(meta.offsetTimeOriginal),
            )
            // The file's current DATE_MODIFIED rides along, so a later walk can tell this reading
            // apart from the same file after an external edit.
            if (correction != null) {
                corrections[item.uri] = CaptureDateOverride.Entry(correction, item.dateModified)
            }
        }
    }

    /** Upsert one located photo's coordinates; a write failure costs that fix alone, not the walk. */
    private suspend fun storeLocation(uri: String, userId: UserId, coords: Pair<Double, Double>) {
        val entity = PhotoLocationEntity(
            id = uri,
            userId = userId.id,
            latitude = coords.first,
            longitude = coords.second,
        )
        runCatching { photoLocationDao.upsert(listOf(entity)) }
            .onFailure { Log.w(TAG, "upsert $uri failed: ${it.message}") }
    }

    /**
     * Write the whole walk's date findings into the persisted override map in ONE edit: [voided] URIs
     * lose their entry, then [corrections] merge in, a fresh value replacing any entry the map already
     * holds for that URI. Both land together so a large library costs one edit either way. The scan
     * reads the map once per pass, so what landed is nudged into view through the repository's own
     * re-query trigger instead of waiting for the next MediaStore change, which is also what hands the
     * next walk an un-overridden date to measure a dropped file against; nothing written means nothing
     * to nudge. A persist failure is logged and dropped, and the next walk finds those files unrecorded
     * or still void and retries them.
     */
    private suspend fun persistDates(
        corrections: Map<String, CaptureDateOverride.Entry>,
        voided: Set<String>,
    ) {
        if (corrections.isEmpty() && voided.isEmpty()) return
        val persisted = runCatching {
            context.settingsDataStore.edit { prefs ->
                val merged = HashMap(
                    CaptureDateOverride.parse(prefs[SettingsKeys.DOWNLOAD_DATE_OVERRIDES])
                )
                merged.keys.removeAll(voided)
                merged.putAll(corrections)
                prefs[SettingsKeys.DOWNLOAD_DATE_OVERRIDES] = merged.mapTo(HashSet(merged.size)) {
                    CaptureDateOverride.encode(it.key, it.value.captureMs, it.value.modifiedSeconds)
                }
            }
        }.onFailure { Log.w(TAG, "date override persist failed: ${it.message}") }.isSuccess
        if (persisted) localMediaRepository.notifyMediaChanged()
    }

    /** The capture instant a video records in its own mvhd box, or null when it records none. Read
     *  through the descriptor the resolver hands out, because under scoped storage that is the only
     *  route to a MediaStore item's bytes. The plain URI is enough: Android redacts location, not
     *  timestamps. A file that cannot be opened reads as null, which leaves the stored date standing. */
    private fun mvhdCaptureDate(uri: String): Long? =
        try {
            context.contentResolver.openFileDescriptor(android.net.Uri.parse(uri), "r")?.use {
                Mp4CreationTime.read(it.fileDescriptor)
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.w(TAG, "mvhd read $uri failed: ${e.message}")
            null
        }

    private companion object {
        /** EXIF reads are light; a small pool clears a large library without disturbing scroll. */
        const val WORKER_COUNT = 4
    }
}
