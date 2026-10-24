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

/**
 * Per-file upload event surfaced to UI (Settings sync progress).
 *
 * Status transitions per file: `Queued` → `Encrypting` → `Uploading` → (`Done` | `Failed`). A video
 * that is compressed goes `Queued` while it waits for the transcode, `Compressing` while it runs, and
 * `Queued` again until it gets an upload slot back.
 * `done` and `total` are batch-level counters so the UI can render a single linear bar without
 * keeping its own state.
 *
 * `Encrypting` was split out from `Uploading` so the user sees the pre-network phase distinctly
 * — multi-hundred-MB videos can spend minutes in encrypt/sign/spill before the first byte hits
 * the CDN, and folding that into "Uploading" was the most common cause of "stuck at 0 %" reports.
 *
 * `Idle` is a synthetic frame the use-case emits when it finishes (or finds nothing to upload)
 * so observers can clear any in-flight view without watching a separate signal.
 *
 * `WaitingForWifi` and `PreparingBackup` are synthetic frames emitted when the auto-sync drain is
 * deferred (Wi-Fi-only on but off Wi-Fi / cloud listing not settled after a reinstall), so the
 * queued-but-idle state reads as "waiting", not "broken". They carry no per-file payload.
 *
 * `StorageFull` is the same kind of frame for the one deferral the user has to act on: the Drive is
 * out of space, so the batch stopped and no later trigger can get past it either. It replaces the
 * closing `Idle` frame rather than preceding it, because `Idle` is what observers clear the panel
 * on, and a reason wiped in the same breath would leave the backup looking merely finished.
 */
enum class UploadStatus { Queued, Compressing, Encrypting, Uploading, Done, Failed, Idle, WaitingForWifi, PreparingBackup, StorageFull }

data class UploadProgress(
    val uri: String,
    val displayName: String,
    val status: UploadStatus,
    val doneIdx: Int,
    val totalCount: Int,
    /** Plaintext byte count of the file this event is about. Used by the Sync card
     *  to compute a running bytes/sec speed. 0 for non-per-file events (Idle frames). */
    val sizeBytes: Long = 0L,
    /**
     * Live byte counter for the current file's phase (Encrypting or Uploading). 0 outside
     * those phases. SettingsViewModel reads this for the live MB/s speed read-out so the
     * meter updates DURING a single large upload instead of only ticking on file completion.
     */
    val doneBytes: Long = 0L,
    /** `Compressing` only: the transcode's 0..1 progress, the codec MIME type it produces, and its
     *  speed in seconds of video per second. */
    val compressFraction: Float = 0f,
    val compressCodecMime: String? = null,
    val compressSpeedX: Float? = null,
)
