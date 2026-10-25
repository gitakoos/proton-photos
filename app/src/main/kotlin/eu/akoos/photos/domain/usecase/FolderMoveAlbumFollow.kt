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

import eu.akoos.photos.data.preferences.AlbumMirrorFolders
import eu.akoos.photos.di.AppScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import me.proton.core.accountmanager.domain.AccountManager
import javax.inject.Inject
import javax.inject.Singleton

/** A photo a move relocated: its device uri, the folder it left and its file name, both read before the move. */
data class MovedPhoto(val uri: String, val sourceBucket: String?, val displayName: String?)

/**
 * What a move between device folders does to the Drive albums of the folders' mirrors. [removals] holds, per
 * mirrored source folder, the moved photos' Drive links (device uri to link) to take out of that folder's album;
 * [joins] holds every moved photo's Drive link to add to the target folder's album, created when missing; and
 * [optInTarget] says the target folder starts mirroring too, so a folder a mirrored photo moved into keeps its
 * album in step from then on.
 */
internal data class FolderMoveAlbumPlan(
    val removals: Map<String, Map<String, String>>,
    val joins: Map<String, String>,
    val optInTarget: Boolean,
)

/**
 * The album plan for photos that moved on the phone from their folders ([sourceBucketByUri], device uri to the
 * folder it left) into [targetBucket]. Only photos already on Drive ([onDrive], device uri to Drive link) take
 * part: a device-only photo joins the right album when it uploads, by the folder it is in then. Nothing happens
 * unless a source folder or the target mirrors to an album, and a photo whose folder name matches the target's
 * (the same album) is left as it is. Null means there is nothing to do.
 */
internal fun folderMoveAlbumPlan(
    rule: AlbumMirrorFolders,
    sourceBucketByUri: Map<String, String?>,
    onDrive: Map<String, String>,
    targetBucket: String,
): FolderMoveAlbumPlan? {
    val moved = sourceBucketByUri.filter { (uri, source) ->
        source != null && !source.equals(targetBucket, ignoreCase = true) && uri in onDrive
    }
    if (moved.isEmpty()) return null
    val mirroredSources = moved.values.filterNotNull().filter { it in rule }.toSet()
    val targetMirrored = targetBucket in rule
    if (mirroredSources.isEmpty() && !targetMirrored) return null
    val removals = moved.entries
        .filter { it.value in mirroredSources }
        .groupBy({ it.value!! }, { it.key to onDrive.getValue(it.key) })
        .mapValues { (_, pairs) -> pairs.toMap() }
    val joins = moved.keys.associateWith { onDrive.getValue(it) }
    return FolderMoveAlbumPlan(removals = removals, joins = joins, optInTarget = !targetMirrored)
}

/**
 * Keeps the Drive albums of mirrored folders in step when photos move between device folders: a photo already on
 * Drive leaves the album of the folder it left and joins the album of the folder it went into, created when
 * missing. Runs on the app scope, because the screen the move started from can close first; a guest has no Drive
 * and nothing to change.
 */
@Singleton
class FolderMoveAlbumFollow @Inject constructor(
    private val accountManager: AccountManager,
    private val upload: UploadPendingUseCase,
    private val drivePairs: DrivePairLookup,
    @AppScope private val appScope: CoroutineScope,
) {
    fun onPhotosMoved(moved: List<MovedPhoto>, targetBucket: String) {
        if (moved.isEmpty() || targetBucket.isEmpty()) return
        appScope.launch {
            val userId = accountManager.getPrimaryUserId().first() ?: return@launch
            runCatching {
                val onDrive = drivePairs.pairs(userId, moved.associate { it.uri to it.displayName })
                eu.akoos.photos.util.SyncDiagnostics.log(
                    "folder move: ${moved.size} photo(s) moved, ${onDrive.size} on Drive, " +
                        "${moved.count { it.sourceBucket == null }} with an unread source folder",
                )
                upload.followFolderMove(userId, moved.associate { it.uri to it.sourceBucket }, onDrive, targetBucket)
            }
                .onSuccess { plan ->
                    if (plan == null) eu.akoos.photos.util.SyncDiagnostics.log("folder move albums: nothing to change")
                }
                .onFailure { e ->
                    if (e is CancellationException) throw e
                    eu.akoos.photos.util.SyncDiagnostics.log(
                        "folder move albums FAILED: " + eu.akoos.photos.util.describeUploadFailure(e),
                    )
                }
        }
    }
}
