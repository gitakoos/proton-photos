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

package eu.akoos.photos.util

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Cross-ViewModel signal that the album list state on the server has changed and the
 * gallery's album grid should re-fetch — most importantly after unshare actions in
 * AlbumDetailViewModel, since the server-side share state is what determines whether the
 * Albums tab keeps showing the "shared" badge. Without this, the gallery's AlbumsViewModel
 * keeps the pre-action snapshot until the user does something that already triggers a
 * refresh (pull-to-refresh, app cold start, …).
 *
 * Buffer = 1 with DROP_OLDEST so a late subscriber that missed a previous emit still gets
 * a wake-up the next time it collects (no replay history beyond the most recent signal).
 */
@Singleton
class AlbumListEventBus @Inject constructor() {
    private val _changes = MutableSharedFlow<Unit>(
        replay = 0,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val changes: SharedFlow<Unit> = _changes.asSharedFlow()

    fun notifyChanged() {
        _changes.tryEmit(Unit)
    }

    // Targeted cover update. Lets the album detail flip its own cover WITHOUT firing the generic
    // [changes] signal (which makes the detail refresh and reload + flash the whole album), and lets
    // the album grid patch a single card's thumbnail in place. Pair = (albumLinkId, coverUrl).
    private val _coverChanges = MutableSharedFlow<Pair<String, String?>>(
        replay = 0,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val coverChanges: SharedFlow<Pair<String, String?>> = _coverChanges.asSharedFlow()

    fun notifyCoverChanged(albumLinkId: String, coverThumbnailUrl: String?) {
        _coverChanges.tryEmit(albumLinkId to coverThumbnailUrl)
    }
}
