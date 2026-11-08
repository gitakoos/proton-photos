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

/** A photo a move relocated: its device uri, the folder it left, its file name and when it was taken, read before
 *  the move. */
data class MovedPhoto(val uri: String, val sourceBucket: String?, val displayName: String?, val takenMs: Long? = null)

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
 * The folder whose album tick moves to [targetBucket]: every moved photo left that one folder ([sourceBucketByUri]),
 * nothing is left in it ([emptiedSources]), it mirrors to an album and the target does not yet. That is a rename,
 * or all of the folder moved into a new one, so the new folder keeps the album choice the old one had.
 */
internal fun albumTickToCarry(
    rule: AlbumMirrorFolders,
    sourceBucketByUri: Map<String, String?>,
    emptiedSources: Set<String>,
    targetBucket: String,
): String? {
    val source = sourceBucketByUri.values.toSet().singleOrNull() ?: return null
    if (source !in emptiedSources || source !in rule || targetBucket in rule) return null
    return source
}

/**
 * The mirrored folder whose Drive album takes the target's name instead of being emptied into a new album: every
 * moved photo left that one folder ([sourceBucketByUri]), nothing is left in it ([emptiedSources]), its album
 * exists and no album carries the target's name yet ([albumNames], lower-cased). That is a rename of the folder,
 * or all of it moved into a new one, and the album keeps its photos, cover, sharing and the photos added to it by
 * hand. Null when the album should follow photo by photo instead.
 */
internal fun folderAlbumToRename(
    plan: FolderMoveAlbumPlan,
    sourceBucketByUri: Map<String, String?>,
    emptiedSources: Set<String>,
    albumNames: Set<String>,
    targetBucket: String,
): String? {
    val sources = sourceBucketByUri.values.toSet()
    val source = sources.singleOrNull() ?: return null
    if (source !in plan.removals || source !in emptiedSources) return null
    if (targetBucket.lowercase() in albumNames || source.lowercase() !in albumNames) return null
    return source
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
    private val localRepo: eu.akoos.photos.domain.repository.LocalMediaRepository,
    @AppScope private val appScope: CoroutineScope,
) {
    fun onPhotosMoved(moved: List<MovedPhoto>, targetBucket: String) {
        if (moved.isEmpty() || targetBucket.isEmpty()) return
        appScope.launch {
            val userId = accountManager.getPrimaryUserId().first() ?: return@launch
            runCatching {
                val onDrive = drivePairs.pairs(
                    userId,
                    moved.associate { it.uri to it.displayName },
                    moved.mapNotNull { m -> m.takenMs?.let { m.uri to it } }.toMap(),
                )
                // The folders the move left empty, read now that the files are in their new place.
                val emptied = moved.mapNotNull { it.sourceBucket }.toSet()
                    .filter { localRepo.queryByBucket(it).isEmpty() }
                    .toSet()
                eu.akoos.photos.util.SyncDiagnostics.log(
                    "folder move: ${moved.size} photo(s) moved, ${onDrive.size} on Drive, " +
                        "${moved.count { it.sourceBucket == null }} with an unread source folder",
                )
                upload.followFolderMove(userId, moved.associate { it.uri to it.sourceBucket }, onDrive, targetBucket, emptied)
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
