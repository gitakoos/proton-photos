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

package eu.akoos.photos.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore

val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

object SettingsKeys {
    val AUTO_SYNC = booleanPreferencesKey("auto_sync")
    val SYNC_WIFI_ONLY = booleanPreferencesKey("sync_wifi_only")

    /** When true (default), the viewer + editor will NOT auto-download cloud full-res
     *  blobs on metered networks. Wifi-only is the data-conscious default; users on
     *  unlimited mobile plans can flip it off in Settings → Sync. Does not affect the
     *  thumbnail layer (those are tiny and load freely). */
    val FULLRES_WIFI_ONLY = booleanPreferencesKey("fullres_wifi_only")
    /**
     * App-lock timeout in minutes — how long the app can be in the background before re-locking
     * on resume. 0 = lock immediately (the default before this option existed). Larger values mean the
     * user can quickly switch to another app and back without re-authenticating.
     * Common picks: 0 (immediate), 1, 5, 10, 15, 60.
     */
    val APP_LOCK_TIMEOUT_MINUTES = intPreferencesKey("app_lock_timeout_minutes")
    val AUTO_FREE_UP = booleanPreferencesKey("auto_free_up")
    val FREE_UP_INTERVAL = stringPreferencesKey("free_up_interval")
    val FREE_UP_WIFI_ONLY = booleanPreferencesKey("free_up_wifi_only")
    /**
     * 3-state theme mode: "system" | "light" | "dark". Replaces legacy [DARK_MODE] boolean.
     * If absent, falls back to the boolean DARK_MODE; if both absent, defaults to "system".
     */
    val THEME_MODE = stringPreferencesKey("theme_mode")
    /** Legacy — kept only so old installs migrate cleanly to [THEME_MODE]. */
    val DARK_MODE = booleanPreferencesKey("dark_mode")
    /**
     * Accent-color palette selection. Independent of [THEME_MODE] — palette only swaps
     * the accent/accent2 tokens, never the light/dark base surfaces. Default value is
     * the historical Proton purple so installs upgrading through this commit see no
     * visual change until they pick a different palette.
     */
    val THEME_PALETTE = stringPreferencesKey("theme_palette")
    val LAST_SYNC_MS = longPreferencesKey("last_sync_ms")
    val LANGUAGE = stringPreferencesKey("language")
    /** Folders selected for backup. null (key absent) = back up nothing (first-run default). */
    val SYNC_FOLDER_NAMES = stringSetPreferencesKey("sync_folder_names")

    /**
     * When true, the reconcile + upload pipeline ignores [SYNC_FOLDER_NAMES] entirely and
     * backs up EVERY MediaStore image / video the device has — matching the "back up
     * everything" mode of Google Photos / iCloud Photos. Users who don't want to think
     * about per-folder selection toggle this on once and forget. Default: false (the
     * existing folder-picker model stays the opt-in default so nothing changes for
     * users who already configured folders).
     */
    val BACKUP_EVERYTHING = booleanPreferencesKey("backup_everything")

    /**
     * Bucket names the user has carved out of [BACKUP_EVERYTHING]. When backup-everything
     * is ON, reconcile drops any local item whose bucket appears here — useful for
     * keeping Screenshots / Movies / WhatsApp-Status out of Drive without abandoning the
     * "everything else" guarantee. Empty set (or key absent) = back up everything,
     * no exclusions. Ignored entirely when [BACKUP_EVERYTHING] is OFF — the per-folder
     * picker is its own model.
     *
     * NOTE: matches MediaStore bucket display names, which can collide across paths
     * (two "Movies" folders on different mount points both get excluded). This is the
     * same limitation [SYNC_FOLDER_NAMES] has — disambiguating would mean tracking
     * BUCKET_ID, which loses meaning across device factory resets.
     */
    val EXCLUDED_FOLDER_NAMES = stringSetPreferencesKey("excluded_folder_names")

    /**
     * hiddenUri → cloudFileId mapping persisted as a set of "hiddenUri|cloudFileId"
     * strings (DataStore lacks a native Map type). Read at unhide time so the restored
     * MediaStore entry can inherit the original cloud linkId on its new SyncState row,
     * letting reconcile pair them via byId on the next pass instead of treating the
     * restored file as a fresh upload candidate. Without this mapping, a Synced photo
     * that round-trips through Hidden silently gets re-uploaded as a new Drive entry —
     * a visible duplicate in Drive.
     */
    val HIDDEN_URI_CLOUD_ID_MAP = stringSetPreferencesKey("hidden_uri_cloud_id_map")

    /**
     * User-declared local folder names that aren't backed by an existing MediaStore bucket yet.
     * Shown in Backup Folders so the user can pre-tick a folder and have future photos in it
     * sync automatically. Cleaned up when the same name appears as a populated bucket.
     */
    val MANUAL_LOCAL_FOLDER_NAMES = stringSetPreferencesKey("manual_local_folder_names")

    /**
     * Persists bucket-name → Drive-album-linkId mappings across sessions to prevent
     * duplicate album creation when name decryption fails on a subsequent run.
     * Each entry is "bucketName=albumLinkId". No userId prefix — one Drive account per app.
     */
    val ALBUM_BUCKET_MAP = stringSetPreferencesKey("album_bucket_map")

    fun eventAnchorKey(userId: String, volumeId: String) =
        stringPreferencesKey("event_anchor_${userId}_$volumeId")

    // Metadata stripping — which fields to strip when uploading photos
    val STRIP_GPS = booleanPreferencesKey("strip_gps")
    val STRIP_CAMERA_INFO = booleanPreferencesKey("strip_camera_info")
    val STRIP_TIMESTAMP = booleanPreferencesKey("strip_timestamp")
    val STRIP_SOFTWARE_INFO = booleanPreferencesKey("strip_software_info")
    val STRIP_ON_UPLOAD = booleanPreferencesKey("strip_on_upload")
    /** When true, the upload pipeline derives a new filename from the source's capture
     *  timestamp before sending bytes to Drive — e.g. `IMG_2841.jpg` → `2026-05-29_14-32-08.jpg`.
     *  Cloud-side `displayName` reflects the new name; the on-device file is untouched.
     *  Useful for users who want a consistent, sortable naming scheme on Drive Web
     *  regardless of which camera app captured the file. */
    val RENAME_TO_CAPTURE_DATE = booleanPreferencesKey("rename_to_capture_date")
    /** When true, the upload pipeline deletes the original MediaStore file as soon as
     *  Drive has the upload (SyncState marked SYNCED). Off by default — most users want
     *  the local copy to remain until Auto-free-up reclaims it on the next pass. Users
     *  who treat Drive as the primary store flip this on. Foreign-owned files on
     *  Android 11+ may refuse the delete without RecoverableSecurityException consent;
     *  the local copy then survives until the OS Manage-Media flow grants broader access. */
    val DELETE_LOCAL_AFTER_BACKUP = booleanPreferencesKey("delete_local_after_backup")

    /**
     * MediaStore URIs that the upload worker wanted to delete after a successful backup
     * but could not — typically Android 11+ foreign owned items where the worker has no
     * Activity to drive `MediaStore.createDeleteRequest` consent. The next time the
     * user is in the foreground we drain this set through a batched delete request so
     * the device file actually goes away instead of silently surviving.
     */
    val PENDING_DELETE_URIS = stringSetPreferencesKey("pending_delete_uris")

    // Hidden album — stores URIs of photos hidden from main gallery
    val HIDDEN_PHOTO_URIS = stringSetPreferencesKey("hidden_photo_uris")

    // Timeline grouping preference
    val TIMELINE_GROUPING = stringPreferencesKey("timeline_grouping")

    // App lock — biometric/device credential lock for the entire app
    val APP_LOCK_ENABLED = booleanPreferencesKey("app_lock_enabled")

    val MANAGE_MEDIA_PROMPTED = booleanPreferencesKey("manage_media_prompted")

    /**
     * Set to true the moment the user finishes the post login onboarding wizard
     * (welcome → backup mode → privacy → notifications → photo access → manage
     * media → done). NavGraph reads this after authentication and routes back
     * to the wizard if it's false so the user can never accidentally land on an
     * empty Gallery without the explanation step. Persists per install — a sign
     * out / sign back in by the same user does NOT replay the wizard.
     */
    val ONBOARDING_COMPLETE = booleanPreferencesKey("onboarding_complete")

    /** Privacy opt-in: when true, wipe the full-res blob cache every time the app
     *  process is backgrounded. Off by default — most users prefer the 30-min TTL
     *  + offline grace behaviour. Security-conscious users who want zero on-disk
     *  traces of viewed cloud photos can flip this on. Does NOT touch the encrypted
     *  Drive backups on Proton's side, only the local viewing cache. */
    val CLEAR_CACHE_ON_APP_CLOSE = booleanPreferencesKey("clear_cache_on_app_close")

    // Favorites — stores URIs (local) or linkIds (cloud) of favorited photos
    val FAVORITE_IDS = stringSetPreferencesKey("favorite_ids")

    /**
     * Names of local albums the user created manually from inside the app. These show up
     * alongside auto-discovered MediaStore buckets in AlbumsScreen, even when they contain
     * no photos yet — so the user can target them when moving files in later.
     */
    val MANUAL_LOCAL_ALBUMS = stringSetPreferencesKey("manual_local_albums")

    /**
     * Virtual local-album membership: which local photos belong to which user-created album
     * WITHOUT physically moving the file on disk. Each entry is `albumName||contentUri`.
     *
     * Background: MediaProvider on Android Q+ refuses to let an unprivileged third-party app
     * write DATE_TAKEN for a file it doesn't own (`W MediaProvider: Ignoring mutation of
     * datetaken`). That means any RELATIVE_PATH move resets DATE_TAKEN to NULL on files
     * authored by the camera app (or anything else) — the gallery then groups them under
     * "today". To preserve capture dates we leave files in their original bucket and track
     * album membership here instead, the same way cloud albums are references (not copies).
     *
     * `||` as separator because album names may contain `=` or `:` and URIs always contain `/`.
     */
    val LOCAL_ALBUM_VIRTUAL_MEMBERSHIP = stringSetPreferencesKey("local_album_virtual_membership")

    /**
     * Drive linkIds of photos uploaded by this client, encoded as `linkId|uploadedAtMs`.
     * Persisted so a process restart between upload and the photo stream catching up cannot
     * cause [DrivePhotoRepositoryImpl.refreshCloudPhotos] to delete a brand-new upload as
     * "stale". Entries older than [RECENT_UPLOAD_TTL_MS] are dropped on read.
     */
    val RECENT_UPLOAD_IDS = stringSetPreferencesKey("recent_upload_ids_v2")

    /** TTL after which a [RECENT_UPLOAD_IDS] entry is considered stale and dropped. */
    const val RECENT_UPLOAD_TTL_MS = 60L * 60L * 1000L
}
