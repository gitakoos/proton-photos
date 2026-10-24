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

package eu.akoos.photos.data.sdk

import kotlinx.coroutines.flow.toList
import me.proton.core.domain.entity.UserId
import me.proton.drive.sdk.entity.DriveEvent
import me.proton.drive.sdk.entity.DriveEventId
import me.proton.drive.sdk.entity.LegacyNodeUid
import me.proton.drive.sdk.entity.ScopeId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What one pass over the photo volume's change feed found: the links to fetch again, the links gone from
 * the timeline (deleted or trashed), and the cursor to store once they are applied. [continuityLost] means
 * the server could not hand over the changes since the cursor, so the caller resyncs from a full listing.
 */
data class EventBatch(
    val upserts: List<String>,
    val deletes: List<String>,
    val cursor: String,
    val continuityLost: Boolean = false,
)

/**
 * The photo volume's change feed through the Drive SDK. The cursor is the same server event id the app's
 * own requests store, so either path picks up where the other left off. Run only through [SdkRouting]
 * ([SdkRoute.EVENTS]).
 */
@Singleton
class SdkEventOps @Inject constructor(
    private val clientProvider: SdkDriveClientProvider,
) {
    /** The newest event id of the volume, to start following its changes from now on. */
    suspend fun latestCursor(userId: UserId, volumeId: String): String =
        clientProvider.withPhotosClient(userId) { client ->
            val events = client.enumerateEvents(ScopeId(volumeId), cursorEventId = null).toList()
            events.lastOrNull()?.id?.value ?: throw SdkEventException("the feed named no starting point")
        } ?: throw SdkEventException("the SDK cannot run on this device")

    /** Every change since [cursor], gathered whole before anything is applied. */
    suspend fun changesSince(userId: UserId, volumeId: String, cursor: String): EventBatch =
        clientProvider.withPhotosClient(userId) { client ->
            eventBatchOf(client.enumerateEvents(ScopeId(volumeId), DriveEventId(cursor)).toList(), cursor)
        } ?: throw SdkEventException("the SDK cannot run on this device")
}

/** Thrown when the SDK cannot read the change feed, so the app's own request reads it instead. */
class SdkEventException(message: String) : IllegalStateException(message)

/**
 * Folds the SDK's events into the shape the timeline applies: a trashed or deleted link leaves it, any
 * other change is fetched again, and the last event's id is the next cursor ([startCursor] when there was
 * none). A link changed and then removed in the same pass only leaves.
 */
internal fun eventBatchOf(events: List<DriveEvent>, startCursor: String): EventBatch {
    val upserts = LinkedHashSet<String>()
    val deletes = LinkedHashSet<String>()
    var continuityLost = false
    for (event in events) {
        when (event) {
            is DriveEvent.NodeDeleted -> (event.nodeUid as? LegacyNodeUid)?.let { deletes += it.linkId }
            is DriveEvent.NodeUpdated -> (event.nodeUid as? LegacyNodeUid)?.let {
                if (event.isTrashed) deletes += it.linkId else upserts += it.linkId
            }
            is DriveEvent.ContinuityLost -> continuityLost = true
            is DriveEvent.CursorAdvanced, is DriveEvent.SharedWithMeUpdated, is DriveEvent.ScopeAccessLost -> Unit
        }
    }
    return EventBatch(
        upserts = upserts.filter { it !in deletes },
        deletes = deletes.toList(),
        cursor = events.lastOrNull()?.id?.value ?: startCursor,
        continuityLost = continuityLost,
    )
}
