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

package eu.akoos.photos.domain.entity

enum class SyncStatus {
    LOCAL_ONLY,
    SYNCED,
    CLOUD_ONLY,
    LOCAL_MODIFIED,
    CONFLICT,
    /**
     * The user moved this photo into the Hidden vault on the device. The local file lives in
     * app-private storage (not visible to MediaStore); the cloud copy stays put in Drive but
     * the gallery renders a crossed-out eye overlay so the user can see at a glance which
     * cloud rows have a hidden local twin.
     *
     * Critical: every reconcile / cleanup path must SKIP rows with this status — they must
     * not be demoted to CLOUD_ONLY, must not be re-uploaded, must not be trashed, and the
     * Hidden screen relies on them surviving across periodic refreshes.
     */
    HIDDEN,

    /**
     * An editor save just produced this local file and the cloud-fanout upload is in flight.
     * The editor owns this row until its upload completes — reconcile and SyncWorker must
     * NOT touch it, otherwise the background sync pipeline would race the editor and
     * upload the same edited bytes a second time (producing duplicate Drive entries).
     *
     * Transitions: editor sets UPLOADING right after the local insert lands, flips to
     * SYNCED with the real cloudFileId after the fanout upload succeeds, or demotes to
     * LOCAL_ONLY if the upload fails so SyncWorker eventually retries.
     */
    UPLOADING,
}

/**
 * Why a sync_state row was queued for upload. Recorded on the row's queueSource column so a
 * later pass (and diagnostics) can tell an auto-folder backup apart from an explicit user
 * action. Plain string constants rather than an enum so the value stores verbatim and a future
 * source can be added without a schema migration.
 *
 * - [MANUAL]: the user explicitly asked to back this photo up (a "back up now" with no album).
 * - [AUTO_FOLDER]: reconcile queued it because its folder is in the backup selection.
 * - [ALBUM_ADD]: the user added a not-yet-backed-up photo to a cloud album, forcing its upload.
 * - [EDITOR]: an editor save produced the local file and needs it backed up.
 */
object QueueSource {
    const val MANUAL = "MANUAL"
    const val AUTO_FOLDER = "AUTO_FOLDER"
    const val ALBUM_ADD = "ALBUM_ADD"
    const val EDITOR = "EDITOR"

    /**
     * True when the row is up for upload because the automatic folder backup put it there, rather
     * than because the user asked for this photo. [AUTO_FOLDER] is that case by definition, and a
     * null source is a legacy row from before the column existed, which only reconcile ever created.
     *
     * This is what the auto-backup switch turns off. Turning it off leaves the folder selection
     * intact so re-enabling restores it, which means the selection alone cannot say whether a photo
     * should upload; the queue source can. A row the user queued by hand ([MANUAL]), by adding a
     * device photo to a cloud album ([ALBUM_ADD]) or by saving an edit ([EDITOR]) is not automatic
     * and still uploads with the switch off, because each of those is an instruction about one
     * photo and the switch is a statement about the folder sweep.
     */
    fun isAutomatic(queueSource: String?): Boolean =
        queueSource == null || queueSource == AUTO_FOLDER
}
