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

package eu.akoos.photos.util

/**
 * Privacy-safe, in-memory diagnostics for the photo-import review flow (staging a picked export,
 * uploading the reviewed set, undoing a run), so an import that misbehaves on some device is legible in
 * the copied diagnostics instead of a blind spot. Mirrors [FaceDiagnostics] / [SyncDiagnostics]: a
 * handful of live fields each stage pushes and a numbers-only [snapshot] the user copies from Settings.
 * Nothing here is auto-sent.
 *
 * Every value is a NON-IDENTIFYING count, flag, or short fixed label. Never an archive name, photo name,
 * link id, sha1, caption, key, or account string. The recorded failure is an exception CLASS name only,
 * which names code, not the account or the photo; callers pass [Throwable.javaClass]'s simpleName, never
 * a raw message.
 */
object ImportDiagnostics {

    /** The pipeline's last reported stage (Idle / Staged / Uploaded / Undone / Error). */
    @Volatile
    var state: String = "Idle"

    /** Rows the last staging pass wrote from the picked export. */
    @Volatile
    var lastStaged: Int = 0

    /** Photos the last upload run sent. */
    @Volatile
    var lastUploaded: Int = 0

    /** Photos the last upload run resolved as already in Drive (deduped), so nothing was sent for them. */
    @Volatile
    var lastAlreadyInDrive: Int = 0

    /** Photos the last upload run skipped (kept out of the review, or no longer in the archive). */
    @Volatile
    var lastSkipped: Int = 0

    /** Photos the last upload run could not send. */
    @Volatile
    var lastFailed: Int = 0

    /** Albums the last upload run created from the export's folders. */
    @Volatile
    var lastAlbumsCreated: Int = 0

    /** Photo links the last upload run filed into albums, newly created and reused albums alike. */
    @Volatile
    var lastPhotosAddedToAlbums: Int = 0

    /** Photos the last undo moved to the Drive trash. */
    @Volatile
    var lastUndone: Int = 0

    /** Photos the last undo kept in place because their bytes no longer matched what the run uploaded. */
    @Volatile
    var lastUndoKeptChanged: Int = 0

    /** Photos the last undo could not verify or trash. */
    @Volatile
    var lastUndoFailed: Int = 0

    /** The last recorded failure's exception class name, or null when none. A class name only, never a
     *  message, so nothing about the account or a photo can leak here. */
    @Volatile
    var lastError: String? = null

    /** Note that a staging pass wrote [staged] rows. */
    fun recordStage(staged: Int) {
        lastStaged = staged
        state = "Staged"
    }

    /** Note an upload run's per-outcome tally: [uploaded] sent, [alreadyInDrive] resolved as deduped,
     *  [skipped] left out, [failed] could not send, plus the album work the run did ([albumsCreated] new
     *  albums, [photosAddedToAlbums] photo links filed into albums). */
    fun recordUpload(
        uploaded: Int,
        alreadyInDrive: Int,
        skipped: Int,
        failed: Int,
        albumsCreated: Int,
        photosAddedToAlbums: Int,
    ) {
        lastUploaded = uploaded
        lastAlreadyInDrive = alreadyInDrive
        lastSkipped = skipped
        lastFailed = failed
        lastAlbumsCreated = albumsCreated
        lastPhotosAddedToAlbums = photosAddedToAlbums
        state = "Uploaded"
    }

    /** Note an undo's per-outcome tally: [undone] trashed, [keptChanged] kept as changed, [failed] unresolved. */
    fun recordUndo(undone: Int, keptChanged: Int, failed: Int) {
        lastUndone = undone
        lastUndoKeptChanged = keptChanged
        lastUndoFailed = failed
        state = "Undone"
    }

    /** Note one run-level failure by its exception class name [kind]. Carries no message text. */
    fun recordError(kind: String) {
        lastError = kind
        state = "Error"
    }

    /** Reset to the signed-out baseline, so a new account does not inherit the previous one's counts. */
    fun clear() {
        state = "Idle"
        lastStaged = 0
        lastUploaded = 0
        lastAlreadyInDrive = 0
        lastSkipped = 0
        lastFailed = 0
        lastAlbumsCreated = 0
        lastPhotosAddedToAlbums = 0
        lastUndone = 0
        lastUndoKeptChanged = 0
        lastUndoFailed = 0
        lastError = null
    }

    /** True once any import stage has touched this since the last [clear], so the copied bundle can omit
     *  the whole section when no import has run rather than print a row of zeroes. */
    fun hasData(): Boolean =
        state != "Idle" || lastStaged != 0 || lastUploaded != 0 || lastAlreadyInDrive != 0 ||
            lastSkipped != 0 || lastFailed != 0 || lastAlbumsCreated != 0 ||
            lastPhotosAddedToAlbums != 0 || lastUndone != 0 || lastUndoKeptChanged != 0 ||
            lastUndoFailed != 0 || lastError != null

    /** Live numbers-only block for the copied diagnostics: the pipeline state, then the last staging,
     *  upload and undo tallies, then the last failure's class name. Every field is a count or a short
     *  fixed label, so the section identifies neither the account nor a photo. */
    fun snapshot(): String = buildString {
        append("state=").append(state).append('\n')
        append("lastStaged=").append(lastStaged).append('\n')
        append("lastUpload sent=").append(lastUploaded)
            .append(" alreadyInDrive=").append(lastAlreadyInDrive)
            .append(" skipped=").append(lastSkipped)
            .append(" failed=").append(lastFailed).append('\n')
        append("lastAlbums created=").append(lastAlbumsCreated)
            .append(" photosAdded=").append(lastPhotosAddedToAlbums).append('\n')
        append("lastUndo trashed=").append(lastUndone)
            .append(" keptChanged=").append(lastUndoKeptChanged)
            .append(" failed=").append(lastUndoFailed).append('\n')
        append("lastError=").append(lastError ?: "none")
    }
}
