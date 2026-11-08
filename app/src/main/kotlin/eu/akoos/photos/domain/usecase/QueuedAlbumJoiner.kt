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
import androidx.datastore.preferences.core.edit
import eu.akoos.photos.data.preferences.SettingsKeys
import eu.akoos.photos.data.preferences.settingsDataStore
import eu.akoos.photos.domain.repository.DrivePhotoRepository
import eu.akoos.photos.domain.repository.SyncStateRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import me.proton.core.domain.entity.UserId
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Least gap between two album-changed signals sent for queued photos joining their albums. Each
 * signal reloads the album list from the server, so a batch landing a photo every second or two
 * shares one reload per gap instead of paying one per photo.
 */
private const val ALBUM_JOIN_SIGNAL_GAP_MS = 5_000L

/**
 * Settles the album-adds queued for photos that were not on Drive yet: once a photo has its cloud id,
 * it joins each album it was queued for, and the album screens are told to reload.
 */
internal class QueuedAlbumJoiner(
    private val cloudRepo: DrivePhotoRepository,
    private val syncStateRepo: SyncStateRepository,
    private val uploadAlbumTargetDao: eu.akoos.photos.data.db.dao.UploadAlbumTargetDao,
    private val albumListEvents: eu.akoos.photos.util.AlbumListEventBus,
    private val appScope: kotlinx.coroutines.CoroutineScope,
    private val context: Context,
) {

    /** Set from an album-changed signal until [ALBUM_JOIN_SIGNAL_GAP_MS] has passed (see [signalAlbumJoin]). */
    private val albumJoinSignalBusy = AtomicBoolean(false)

    /** A queued photo joined its album while [albumJoinSignalBusy] held, so one more signal is owed. */
    private val albumJoinSignalOwed = AtomicBoolean(false)

    /**
     * Joins a freshly uploaded photo, queued as [localUri] and now [cloudId] on Drive, to every album
     * it was queued for. Each pair is settled on its own by [joinQueuedAlbum], so one album refusing
     * the photo leaves its other albums unaffected.
     */
    suspend fun joinQueuedAlbums(userId: UserId, localUri: String, cloudId: String) {
        for (albumLinkId in uploadAlbumTargetDao.getTargetsFor(localUri)) {
            joinQueuedAlbum(userId, localUri, albumLinkId, cloudId)
        }
    }

    /**
     * Adds [cloudId] to [albumLinkId] for the photo queued as [localUri], and drops the queued pair
     * only once the album holds it. The add reports a photo it could not send, or one the server
     * refused, in its result rather than by throwing, so the join is read from that result; short of
     * it the pair stays for the next pass's drain and the membership is never lost. A refused add can
     * also be one that landed on an earlier try whose reply never arrived, so the album's own children
     * settle that case instead of a retry on every pass. A landed join goes out through [signalAlbumJoin].
     *
     * The one failure that drops the pair is [eu.akoos.photos.domain.entity.AlbumNotFoundException]:
     * the album is gone, so no retry could ever land it. Only for a photo this account holds, though,
     * because the drain also meets pairs another signed-in account queued, and an album of that
     * account reads as missing from this one's volume.
     *
     * An album shared with this user takes a copy under a new link id, so its children can never
     * confirm the join. A miss there is counted instead, and the pair is let go once
     * [FOREIGN_ALBUM_JOIN_ATTEMPTS] passes have missed.
     */
    suspend fun joinQueuedAlbum(userId: UserId, localUri: String, albumLinkId: String, cloudId: String) {
        val foreign = isSharedWithMe(albumLinkId)
        val added = runCatching { cloudRepo.addPhotosToAlbum(userId, albumLinkId, listOf(cloudId)) }
            .onFailure { if (it is CancellationException) throw it }
        val joined = cloudId in added.getOrNull()?.succeededLinkIds.orEmpty() ||
            (added.isSuccess && !foreign && albumHoldsPhoto(userId, albumLinkId, cloudId))
        if (added.exceptionOrNull() is eu.akoos.photos.domain.entity.AlbumNotFoundException &&
            holdsPairing(userId, localUri, cloudId)
        ) {
            uploadAlbumTargetDao.deleteTarget(localUri, albumLinkId)
            Log.w(UPLOAD_TAG, "Queued album-add for $albumLinkId dropped: the album no longer exists")
            eu.akoos.photos.util.SyncDiagnostics.log(
                "upload ${eu.akoos.photos.util.uploadLogRef(localUri)} album join dropped: album no longer exists",
            )
            return
        }
        if (!joined) {
            val reason = added.exceptionOrNull()?.let { eu.akoos.photos.util.describeUploadFailure(it) }
                ?: "not accepted"
            Log.w(UPLOAD_TAG, "Queued album-add for $albumLinkId did not land: $reason")
            eu.akoos.photos.util.SyncDiagnostics.log(
                "upload ${eu.akoos.photos.util.uploadLogRef(localUri)} album join deferred: $reason",
            )
            if (foreign) noteSharedAlbumMiss(localUri, albumLinkId)
            return
        }
        uploadAlbumTargetDao.deleteTarget(localUri, albumLinkId)
        if (foreign) editSharedAlbumMisses { ForeignAlbumJoinMisses.clear(it, localUri, albumLinkId) }
        signalAlbumJoin()
        Log.d(UPLOAD_TAG, "Added $cloudId to queued album $albumLinkId")
    }

    /** Whether [albumLinkId] is an album shared with this user, by the same cached lookup the add
     *  routes on. A lookup that fails reads as false, which keeps the owned album's handling. */
    private suspend fun isSharedWithMe(albumLinkId: String): Boolean =
        runCatching { cloudRepo.loadSharedAddableAlbumsCached().any { it.linkId == albumLinkId } }
            .onFailure { if (it is CancellationException) throw it }
            .getOrDefault(false)

    /** Counts one more pass the queued add of [localUri] to the shared [albumLinkId] missed, and lets
     *  the pair go once [FOREIGN_ALBUM_JOIN_ATTEMPTS] have. */
    private suspend fun noteSharedAlbumMiss(localUri: String, albumLinkId: String) {
        var misses = 0
        editSharedAlbumMisses { entries ->
            ForeignAlbumJoinMisses.bump(entries, localUri, albumLinkId).let { (next, count) ->
                misses = count
                next
            }
        }
        if (misses < FOREIGN_ALBUM_JOIN_ATTEMPTS) return
        uploadAlbumTargetDao.deleteTarget(localUri, albumLinkId)
        editSharedAlbumMisses { ForeignAlbumJoinMisses.clear(it, localUri, albumLinkId) }
        Log.w(UPLOAD_TAG, "Queued add to shared album $albumLinkId dropped after $misses missed passes")
        eu.akoos.photos.util.SyncDiagnostics.log(
            "upload ${eu.akoos.photos.util.uploadLogRef(localUri)} album join dropped: " +
                "shared album did not take it in $misses passes",
        )
    }

    /** Rewrites the shared-album miss counts through [change]. A failed write only loses a count. */
    private suspend fun editSharedAlbumMisses(change: (Set<String>) -> Set<String>) {
        runCatching {
            context.settingsDataStore.edit { p ->
                val current = p[SettingsKeys.FOREIGN_ALBUM_JOIN_MISSES] ?: emptySet()
                val next = change(current)
                if (next != current) p[SettingsKeys.FOREIGN_ALBUM_JOIN_MISSES] = next
            }
        }.onFailure { if (it is CancellationException) throw it }
    }

    /** Whether this account's own row pairs [localUri] to [cloudId]. A read that fails reads as false,
     *  which keeps the pair queued. */
    private suspend fun holdsPairing(userId: UserId, localUri: String, cloudId: String): Boolean =
        runCatching { syncStateRepo.cloudPairedLinkIds(userId, listOf(localUri))[localUri] == cloudId }
            .onFailure { if (it is CancellationException) throw it }
            .getOrDefault(false)

    /** Whether [albumLinkId] already lists [cloudId] among its photos. A listing that fails reads as
     *  false, which keeps the pair queued. */
    private suspend fun albumHoldsPhoto(userId: UserId, albumLinkId: String, cloudId: String): Boolean =
        runCatching { cloudRepo.loadAlbumChildren(userId, albumLinkId).any { it.linkId == cloudId } }
            .onFailure { if (it is CancellationException) throw it }
            .getOrDefault(false)

    /**
     * Tells the album grid and an open album that a queued photo joined its album, so they reload it.
     * Each reload fetches the album list from the server, so the first join signals at once and every
     * join inside the [ALBUM_JOIN_SIGNAL_GAP_MS] after it shares one more signal when the gap ends,
     * instead of a batch paying one reload per photo.
     */
    private fun signalAlbumJoin() {
        if (!albumJoinSignalBusy.compareAndSet(false, true)) {
            albumJoinSignalOwed.set(true)
            return
        }
        albumListEvents.notifyChanged()
        appScope.launch {
            kotlinx.coroutines.delay(ALBUM_JOIN_SIGNAL_GAP_MS)
            albumJoinSignalBusy.set(false)
            if (albumJoinSignalOwed.getAndSet(false)) signalAlbumJoin()
        }
    }
}
