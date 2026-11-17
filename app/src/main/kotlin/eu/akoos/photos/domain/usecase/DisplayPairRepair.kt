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
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.akoos.photos.data.db.dao.PhotoListingDao
import eu.akoos.photos.data.preferences.settingsDataStore
import eu.akoos.photos.data.repository.SyncStateSnapshot
import eu.akoos.photos.domain.entity.LinkBasis
import eu.akoos.photos.domain.entity.LocalMediaItem
import eu.akoos.photos.domain.entity.SyncState
import eu.akoos.photos.domain.entity.SyncStatus
import eu.akoos.photos.domain.repository.DrivePhotoRepository
import eu.akoos.photos.domain.repository.LocalMediaRepository
import eu.akoos.photos.domain.repository.SyncStateRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import me.proton.core.accountmanager.domain.AccountManager
import me.proton.core.domain.entity.UserId
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "DisplayPairRepair"

/** Device files one run reads in full at most. */
internal const val PAIR_REPAIR_MAX_FILES = 400

/** Bytes one run reads at most; the first file is read whatever its size. */
internal const val PAIR_REPAIR_MAX_BYTES = 1024L * 1024 * 1024

/** Names, or Drive photos, one listing lookup carries, under SQLite's bind-variable limit. */
private const val PAIR_REPAIR_NAME_CHUNK = 500

/** Device files a run found not to hold their same-named Drive photo, so no later run reads them again. */
internal interface PairRepairMisses {
    /** What [uri] was last weighed as, see [PairRepairCandidate.fingerprint], or null. */
    fun fingerprintOf(uri: String): String?
    fun record(uri: String, fingerprint: String)
    /** Forgets every uri not in [uris]: files gone from the device. */
    fun keepOnly(uris: Set<String>)
}

/** [PairRepairMisses] in the app's private preferences. */
class PrefsPairRepairMisses @Inject constructor(@ApplicationContext context: Context) : PairRepairMisses {
    private val prefs = context.getSharedPreferences("display_pair_repair_misses", Context.MODE_PRIVATE)

    override fun fingerprintOf(uri: String): String? = prefs.getString(uri, null)

    override fun record(uri: String, fingerprint: String) {
        prefs.edit().putString(uri, fingerprint).apply()
    }

    override fun keepOnly(uris: Set<String>) {
        val gone = prefs.all.keys.filter { it !in uris }
        if (gone.isNotEmpty()) prefs.edit().apply { gone.forEach { remove(it) } }.apply()
    }
}

/**
 * A device file and the Drive photo it may hold, before the bytes are read: the one Drive photo of its
 * name for a file with no sync row, or the photo its row links by name alone ([upgrade]).
 */
internal data class PairRepairCandidate(val file: LocalMediaItem, val linkId: String, val upgrade: Boolean = false) {
    /** The file and its Drive photo as weighed: another photo or a rewritten file is weighed again. */
    val fingerprint: String get() = "$linkId|${file.sizeBytes}|${file.dateModified}"
}

/**
 * Links a device file to its Drive photo after a reinstall, when the file holds the photo's bytes. A
 * reinstall wipes the sync rows, and reconcile writes rows again only for the folders that are backed
 * up, so a photo in a folder that is not would show twice: once as a device file and once on Drive. In
 * the backed-up folders reconcile links a file with no recorded hash by its name and capture second
 * ([LinkBasis.NAME]), a link the gallery shows only when the sizes agree as well, which Drive's listing
 * does not report. The gallery pairs only through a sync row (see [GetGalleryItemsUseCase]), so this
 * writes the row, or marks the name link proven, and only on proof: the file's SHA-1, turned into
 * Drive's content hash, is the photo's.
 *
 * Runs in the background ([eu.akoos.photos.worker.CachePruneWorker]), never on the gallery path, and
 * weighs a device file whose SYNCED row links a Drive photo by name alone, or one that has no row yet
 * and whose name, case aside, is the name of exactly one Drive photo of the library that no row claims,
 * and of no other device file. Each run reads at most
 * [PAIR_REPAIR_MAX_FILES] files and [PAIR_REPAIR_MAX_BYTES], newest first, and remembers the files that
 * did not match, so a file is read once until it or its photo changes. It waits for a complete listing of
 * the library, so a photo not listed yet is never taken for a miss. A new row is written only where no
 * row exists, so one an upload, a download or reconcile wrote is never replaced; a name link is marked
 * proven only while its row still links that photo.
 *
 * A file whose bytes this app changed as it saved it (a date, a place or a video creation time stamped
 * in) no longer hashes to the Drive photo and stays apart.
 */
@Singleton
class DisplayPairRepair internal constructor(
    private val context: Context?,
    private val accountManager: AccountManager?,
    private val localRepo: LocalMediaRepository,
    private val cloudRepo: DrivePhotoRepository,
    private val syncStateRepo: SyncStateRepository,
    private val syncStateSnapshot: SyncStateSnapshot,
    private val photoListingDao: PhotoListingDao,
    private val misses: PairRepairMisses,
) {
    @Inject constructor(
        @ApplicationContext context: Context,
        accountManager: AccountManager,
        localRepo: LocalMediaRepository,
        cloudRepo: DrivePhotoRepository,
        syncStateRepo: SyncStateRepository,
        syncStateSnapshot: SyncStateSnapshot,
        photoListingDao: PhotoListingDao,
        misses: PrefsPairRepairMisses,
    ) : this(
        context as Context?, accountManager, localRepo, cloudRepo, syncStateRepo, syncStateSnapshot, photoListingDao,
        misses as PairRepairMisses,
    )

    /** One run for the signed-in account, none without one. Returns the files it linked. */
    suspend fun runForPrimaryUser(): Int {
        val userId = accountManager?.getPrimaryUserId()?.first() ?: return 0
        if (!listingComplete(userId)) return 0
        return run(userId)
    }

    private suspend fun listingComplete(userId: UserId): Boolean {
        val prefs = context?.settingsDataStore?.data?.first() ?: return false
        return prefs.asMap().any { (k, v) -> k.name.startsWith("photo_listing_ever_complete_${userId.id}_") && v == true }
    }

    /**
     * One run for [userId] over a library listed in full. Returns the files it linked. A candidate whose
     * Drive photo is not listed or carries no content hash is passed over before any byte is read, so it
     * never takes a place in the run's budget; only files actually read count against it.
     */
    internal suspend fun run(userId: UserId): Int = withContext(Dispatchers.IO) {
        val device = localRepo.lastKnownMedia()?.takeIf { it.isNotEmpty() } ?: return@withContext 0
        misses.keepOnly(device.mapTo(HashSet(device.size * 2)) { it.uri })
        val candidates = candidates(userId, device, syncStateSnapshot.current(userId))
        var linked = 0
        var files = 0
        var bytes = 0L
        budget@ for (window in candidates.chunked(PAIR_REPAIR_NAME_CHUNK)) {
            val photos = cloudRepo.observePhotosByLinkIds(window.map { it.linkId }.distinct()).first()
                .associateBy { it.linkId }
            for (candidate in window) {
                val photo = photos[candidate.linkId] ?: continue
                val driveHash = photo.contentHash?.takeIf { it.isNotEmpty() } ?: continue
                val size = candidate.file.sizeBytes.coerceAtLeast(0L)
                if (files >= PAIR_REPAIR_MAX_FILES || (files > 0 && bytes + size > PAIR_REPAIR_MAX_BYTES)) break@budget
                files++
                val sha1 = localRepo.sha1(candidate.file.uri) ?: continue
                bytes += size
                // No key to turn the SHA-1 into Drive's hash: nothing in this run can be compared.
                val keyed = cloudRepo.cloudContentHash(sha1) ?: break@budget
                if (keyed != driveHash) {
                    misses.record(candidate.file.uri, candidate.fingerprint)
                    continue
                }
                if (link(userId, candidate, photo.linkId, driveHash, sha1)) linked++
            }
        }
        if (linked > 0) Log.d(TAG, "linked $linked device file(s) to their Drive photo")
        linked
    }

    /** Records that [candidate]'s file holds the bytes of Drive photo [linkId]. True when a row changed. */
    private suspend fun link(
        userId: UserId,
        candidate: PairRepairCandidate,
        linkId: String,
        driveHash: String,
        sha1: String,
    ): Boolean {
        val now = System.currentTimeMillis()
        if (candidate.upgrade) {
            return syncStateRepo.markLinkProven(userId, candidate.file.uri, linkId, driveHash, sha1, now)
        }
        // Written like a download's row: the link is proven, the backup stamp lets free-up check the file,
        // and the recorded bytes are what free-up compares it with. The empty row hash keeps the automatic
        // sweep off it, as off any copy put on the device from Drive.
        val written = syncStateRepo.insertIfAbsent(
            SyncState(
                localUri = candidate.file.uri,
                cloudFileId = linkId,
                localHash = "",
                cloudHash = driveHash,
                status = SyncStatus.SYNCED,
                lastSyncAttemptMs = now,
                lastSyncSuccessMs = now,
                backedUpAtMs = now,
                sizeBytes = candidate.file.sizeBytes,
                linkBasis = LinkBasis.PROVEN,
            ),
            userId,
        )
        if (written) syncStateRepo.setOriginalHash(candidate.file.uri, sha1)
        return written
    }

    /**
     * The files of [device] whose SYNCED row in [rows] links a Drive photo by name alone, and those with no
     * row and one Drive photo of their name that no row claims, a name no other device file carries; each
     * not weighed before as it is now, newest first.
     */
    internal suspend fun candidates(
        userId: UserId,
        device: List<LocalMediaItem>,
        rows: SyncStateSnapshot.Rows,
    ): List<PairRepairCandidate> {
        val nameLinked = device.mapNotNull { file ->
            rows.byUri[file.uri]
                ?.takeIf { it.status == SyncStatus.SYNCED && it.linkBasis == LinkBasis.NAME }
                ?.cloudFileId?.takeIf { it.isNotBlank() }
                ?.let { PairRepairCandidate(file, it, upgrade = true) }
        }
        val deviceNames = device.groupingBy { it.displayName.lowercase() }.eachCount()
        val unrowed = device.filter { it.uri !in rows.byUri && deviceNames[it.displayName.lowercase()] == 1 }
        if (unrowed.isEmpty() && nameLinked.isEmpty()) return emptyList()
        val claimed = rows.rows.mapNotNullTo(HashSet()) { it.cloudFileId }
        val listed = unrowed.map { it.displayName.lowercase() }.distinct()
            .chunked(PAIR_REPAIR_NAME_CHUNK)
            .flatMap { photoListingDao.ownStreamByLowerNames(userId.id, it) }
        val onlyPhotoOfName = listed.groupBy { it.displayName.lowercase() }
            .filterValues { it.size == 1 }
            .mapValues { it.value.single().linkId }
        val named = unrowed.mapNotNull { file ->
            onlyPhotoOfName[file.displayName.lowercase()]
                ?.takeIf { it !in claimed }
                ?.let { PairRepairCandidate(file, it) }
        }
        return (nameLinked + named).asSequence()
            .filter { misses.fingerprintOf(it.file.uri) != it.fingerprint }
            .sortedByDescending { it.file.dateTaken }
            .toList()
    }
}
