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

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.proton.core.domain.entity.UserId
import eu.akoos.photos.data.db.AppDatabase
import eu.akoos.photos.data.db.dao.SyncStateDao
import eu.akoos.photos.data.db.dao.SyncStateLite
import eu.akoos.photos.di.AppScope
import eu.akoos.photos.domain.entity.SyncStatus
import eu.akoos.photos.util.FreshQueryGate
import eu.akoos.photos.util.retryBackoffMs
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "SyncStateSnapshot"

private const val SYNC_STATE_TABLE = "sync_state"

/** Wait after a change before the copy is read again, so the several writes one step of a backup
 *  makes land in one read. The device-media list waits as long, so a change that touches both
 *  reaches the gallery together. */
internal const val SYNC_SNAPSHOT_SETTLE_MS = 250L

/** Shortest time between the starts of two change-driven reads. A backup writes the table several
 *  times per photo; the whole-table read runs at most this often however fast the writes come. */
internal const val SYNC_SNAPSHOT_MIN_INTERVAL_MS = 1_000L

/** How long a copy outlives its last collector, so moving between screens reuses it instead of
 *  reading again. After that it is dropped. The gallery feed keeps the same window. */
internal const val SYNC_SNAPSHOT_STOP_TIMEOUT_MS = 5_000L

/**
 * One lean copy of a user's sync_state rows, shared by the whole app.
 *
 * Every [observe] collector of a user reads the same copy through one StateFlow: one invalidation
 * listener on the table drives it while anything collects, the writes of one burst cost a single
 * read, change-driven reads start at most once per [SYNC_SNAPSHOT_MIN_INTERVAL_MS], and the copy is
 * dropped [SYNC_SNAPSHOT_STOP_TIMEOUT_MS] after the last collector leaves. A row carries only the
 * [SyncStateLite] columns.
 *
 * [current] serves a one-shot reader that acts on the table as it is now. It reads through the
 * same gate as the shared copy, so two reads of one user's rows never run at once, and what it
 * reads also reaches the collectors.
 *
 * Keyed by user like the table itself: one account's copy never answers for another's.
 */
@Singleton
class SyncStateSnapshot @Inject constructor(
    dao: SyncStateDao,
    db: AppDatabase,
    @AppScope scope: CoroutineScope,
) {
    private val source = Source(
        scope = scope,
        changes = { db.invalidationTracker.createFlow(SYNC_STATE_TABLE, emitInitialState = true).map { } },
        read = { userId -> dao.getAllLite(userId) },
        onReadFailure = { Log.w(TAG, "sync state read failed: ${it.message}") },
    )

    /** [userId]'s rows, kept current as the table changes. A new collector gets the copy already
     *  held at once, which can trail a write still settling; a reader that acts on the table right
     *  now takes [current] instead. */
    fun observe(userId: UserId): Flow<Rows> = source.observe(userId.id)

    /** [userId]'s rows, read after this call began. Calls that overlap share one read. Throws when
     *  the read fails. */
    suspend fun current(userId: UserId): Rows = source.current(userId.id)

    /**
     * One read of a user's rows, with the lookups its readers share. Each lookup is built once per
     * read, on first use, so the reader that first touches one pays for it and should do so off the
     * main thread.
     *
     * Reads holding the same rows are equal, so an unchanged re-read never reaches a collector twice.
     */
    class Rows(val rows: List<SyncStateLite>) {

        /** Each row by its local uri. */
        val byUri: Map<String, SyncStateLite> by lazy { rows.associateBy { it.localUri } }

        /** Cloud ids on the rows at [SyncStatus.HIDDEN]: the Drive copies of vaulted photos, which
         *  every listing leaves out. */
        val hiddenCloudIds: Set<String> by lazy {
            rows.asSequence().filter { it.status == SyncStatus.HIDDEN }.mapNotNull { it.cloudFileId }.toSet()
        }

        /** Cloud ids on the rows at [SyncStatus.SYNCED]: the Drive copies paired to a device file. */
        val syncedCloudIds: Set<String> by lazy {
            rows.asSequence().filter { it.status == SyncStatus.SYNCED }.mapNotNull { it.cloudFileId }.toSet()
        }

        override fun equals(other: Any?): Boolean = other is Rows && other.rows == rows

        override fun hashCode(): Int = rows.hashCode()

        override fun toString(): String = "Rows(size=${rows.size})"
    }

    /**
     * The copies behind [SyncStateSnapshot], one per user. [changes] signals a write to the table and
     * also emits once as soon as it listens, the way Room's invalidation flow does; [read] loads one
     * user's rows.
     */
    internal class Source(
        private val scope: CoroutineScope,
        private val changes: () -> Flow<Unit>,
        private val read: suspend (userId: String) -> List<SyncStateLite>,
        private val onReadFailure: (Throwable) -> Unit = {},
    ) {
        private val users = ConcurrentHashMap<String, UserRows>()

        fun observe(userId: String): Flow<Rows> = rowsOf(userId).shared

        suspend fun current(userId: String): Rows = rowsOf(userId).gate.fresh()

        private fun rowsOf(userId: String): UserRows = users.computeIfAbsent(userId) { UserRows(it) }

        private inner class UserRows(userId: String) {
            val gate = FreshQueryGate(scope) { Rows(read(userId)) }

            private val latest: StateFlow<Rows?> = channelFlow<Rows> {
                // Subscribed before the first read can finish, so no result is missed, including
                // one a one-shot reader asked for.
                launch(start = CoroutineStart.UNDISPATCHED) { gate.results.collect { send(it) } }
                val pending = Channel<Unit>(Channel.CONFLATED)
                launch {
                    flow { emitAll(changes()) }
                        .retryWhen { cause, attempt ->
                            // A write made while nothing listens goes unsignalled, so read what the
                            // table holds now and listen again after a pause.
                            onReadFailure(cause)
                            pending.trySend(Unit)
                            delay(retryBackoffMs(attempt))
                            true
                        }
                        .collect { pending.trySend(Unit) }
                }
                // Runs from the start of each read for the minimum interval; null until the first.
                var interval: Job? = null
                var failedReads = 0L
                while (true) {
                    pending.receive()
                    // The first signal means the listener is in place, so the first read runs at once.
                    if (interval != null) {
                        delay(SYNC_SNAPSHOT_SETTLE_MS)
                        interval.join()
                        // A write signalled before the read begins is in what the read sees.
                        pending.tryReceive()
                    }
                    interval = launch { delay(SYNC_SNAPSHOT_MIN_INTERVAL_MS) }
                    try {
                        gate.fresh()
                        failedReads = 0L
                    } catch (e: Throwable) {
                        // Out of memory is no failure to retry: the next whole read would run out again.
                        if (e is VirtualMachineError || e is kotlinx.coroutines.CancellationException) throw e
                        ensureActive()
                        onReadFailure(e)
                        val pauseMs = retryBackoffMs(failedReads++)
                        launch {
                            delay(pauseMs)
                            pending.trySend(Unit)
                        }
                    }
                }
            }.stateIn(
                scope,
                SharingStarted.WhileSubscribed(SYNC_SNAPSHOT_STOP_TIMEOUT_MS, replayExpirationMillis = 0L),
                null,
            )

            val shared: Flow<Rows> = latest.filterNotNull()
        }
    }
}
