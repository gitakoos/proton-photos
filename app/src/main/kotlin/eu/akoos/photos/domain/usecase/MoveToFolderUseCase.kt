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

import android.app.RecoverableSecurityException
import android.content.ContentValues
import android.content.Context
import android.content.IntentSender
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.akoos.photos.util.ProtonPhotosStorage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject

private const val TAG = "MoveToFolderUseCase"

/**
 * The part of a move the OS refused to run silently. A file the app does not own needs one-shot user
 * consent, so its move is deferred: the caller launches the write request from
 * [MoveToFolderUseCase.Result.NeedsPermission] and, once granted, replays exactly these [uris] into
 * [folderName] through [MoveToFolderUseCase.completeAfterPermissionGranted].
 */
data class PendingMove(val uris: List<String>, val folderName: String)

/**
 * Moves device photos and videos into a real on-device folder under DCIM/&lt;name&gt;/ by updating each
 * MediaStore row's [MediaStore.MediaColumns.RELATIVE_PATH] IN PLACE. The file physically relocates yet
 * keeps the same content URI and _id, so the Drive `sync_state` row (keyed on the URI) stays paired to
 * it. The move is never a copy-and-delete: that would mint a new URI and orphan the pairing.
 *
 * An in-place RELATIVE_PATH update is a scoped-storage operation and needs Android 10+ (API 29). A row
 * the app owns updates silently; a foreign camera-roll file raises a recoverable [SecurityException],
 * which is collected and turned into a single system write request the caller launches before replaying
 * the move on the granted URIs. Every update runs on [Dispatchers.IO].
 */
class MoveToFolderUseCase @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    sealed interface Result {
        /** [count] rows were relocated in place. */
        data class Moved(val count: Int) : Result

        /** One or more foreign files need one-shot write consent. The caller launches [intentSender]
         *  and, on approval, calls [completeAfterPermissionGranted] with [pending]. */
        data class NeedsPermission(val intentSender: IntentSender, val pending: PendingMove) : Result

        data class Failed(val reason: String) : Result

        /** Nothing was eligible to move (no parseable URIs, or every update was declined silently). */
        data object NothingToDo : Result
    }

    suspend operator fun invoke(uris: List<String>, folderName: String): Result =
        withContext(Dispatchers.IO) {
            val relPath = runCatching { moveTargetRelativePath(folderName) }.getOrNull()
                ?: return@withContext Result.Failed("folder name is blank")
            // RELATIVE_PATH is a scoped-storage column (Android 10+). Below Q there is no in-place move,
            // and this use case is only reached from a Q+-gated action, so fail defensively rather than
            // silently do nothing.
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                return@withContext Result.Failed("in-place move needs Android 10+")
            }
            moveAll(uris, relPath, folderName)
        }

    /** Replays the deferred move after the user granted the system write request. */
    suspend fun completeAfterPermissionGranted(pending: PendingMove): Result =
        withContext(Dispatchers.IO) {
            val relPath = runCatching { moveTargetRelativePath(pending.folderName) }.getOrNull()
                ?: return@withContext Result.Failed("folder name is blank")
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                return@withContext Result.Failed("in-place move needs Android 10+")
            }
            moveAll(pending.uris, relPath, pending.folderName)
        }

    private fun moveAll(uris: List<String>, relPath: String, folderName: String): Result {
        // Parse defensively so one malformed URI string cannot abort the whole batch.
        val parsed = uris.mapNotNull { s -> runCatching { Uri.parse(s) }.getOrNull()?.let { s to it } }
        if (parsed.isEmpty()) return Result.NothingToDo

        Log.d(TAG, "move: uris=${uris.size} parsed=${parsed.size} -> $relPath")

        var moved = 0
        val foreign = mutableListOf<ForeignUri>()
        for ((uriString, uri) in parsed) {
            try {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.RELATIVE_PATH, relPath)
                }
                if (context.contentResolver.update(uri, values, null, null) > 0) moved++
            } catch (e: CancellationException) {
                throw e
            } catch (e: SecurityException) {
                // A file the app does not own refuses a silent scoped-storage write; defer it for the
                // one-shot system consent request built below.
                foreign += ForeignUri(uriString, uri, e)
            } catch (e: Exception) {
                Log.w(TAG, "Move failed for one uri", e)
            }
        }

        if (foreign.isNotEmpty()) {
            val sender = buildWriteConsent(foreign)
            if (sender != null) {
                Log.d(TAG, "move: $moved relocated silently, ${foreign.size} need write consent")
                return Result.NeedsPermission(sender, PendingMove(foreign.map { it.uriString }, folderName))
            }
            // Building the request failed on a platform that should support it; report whatever moved
            // silently rather than stranding the batch with no signal.
            Log.w(TAG, "Could not build write consent for ${foreign.size} foreign uri(s); moved=$moved")
        }

        return if (moved > 0) Result.Moved(moved) else Result.NothingToDo
    }

    /**
     * The system write-consent request covering the [foreign] files. Android 11+ batches them all into
     * one [MediaStore.createWriteRequest]; Android 10 has no batch API, so the first recoverable file's
     * own action is used and the caller replays the move, re-prompting for any file still refused.
     */
    private fun buildWriteConsent(foreign: List<ForeignUri>): IntentSender? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return runCatching {
                MediaStore.createWriteRequest(context.contentResolver, foreign.map { it.uri }).intentSender
            }.getOrNull()
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return foreign
                .mapNotNull { (it.cause as? RecoverableSecurityException)?.userAction?.actionIntent?.intentSender }
                .firstOrNull()
        }
        return null
    }

    private data class ForeignUri(
        val uriString: String,
        val uri: Uri,
        val cause: SecurityException,
    )
}

/**
 * The MediaStore RELATIVE_PATH a move targets: DCIM/&lt;sanitized name&gt;/ with a trailing slash.
 * Reuses [ProtonPhotosStorage.sanitize] so the folder is a single safe path segment, and rejects a name
 * that reduces to nothing. The DCIM literal equals [android.os.Environment.DIRECTORY_DCIM] and is kept
 * as a plain string so this stays SDK-free and unit-testable.
 */
internal fun moveTargetRelativePath(folderName: String): String {
    val clean = ProtonPhotosStorage.sanitize(folderName)
    require(clean.isNotEmpty()) { "folder name is blank after sanitizing" }
    return "DCIM/$clean/"
}
