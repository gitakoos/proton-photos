package eu.akoos.photos.domain.repository

import kotlinx.coroutines.flow.Flow
import eu.akoos.photos.domain.entity.LocalMediaItem

interface LocalMediaRepository {
    fun observeLocalMedia(): Flow<List<LocalMediaItem>>
    fun observeTrashedMedia(): Flow<List<LocalMediaItem>>
    fun hasMediaPermission(): Flow<Boolean>
    suspend fun queryByUri(uri: String): LocalMediaItem?

    /**
     * Forces a re-query of MediaStore. Use after the user grants the media permission:
     * the MediaStore [android.database.ContentObserver] does not fire on a permission
     * change, so without this trigger the gallery would stay empty until the app restarts.
     */
    fun notifyPermissionChanged()
}
