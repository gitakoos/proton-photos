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
}
