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
