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

package eu.akoos.photos.data.upload

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.akoos.photos.data.upload.compression.VideoUploadCompression
import eu.akoos.photos.domain.entity.compression.CompressionOutcome
import eu.akoos.photos.domain.entity.compression.CompressionSettings
import eu.akoos.photos.domain.entity.compression.CompressionSkipReason
import eu.akoos.photos.domain.entity.compression.VideoCompressionPlan
import eu.akoos.photos.domain.usecase.UploadStructuralStripper
import eu.akoos.photos.util.MetadataStripConfig
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Process-wide gate serialising video transcodes to one at a time. A device has a single hardware
 * video encoder, so two concurrent transcodes (several backup uploads in flight, or a backup and an
 * import) would fail or thrash. File-level so the limit holds whatever the number of instances.
 */
private val videoTranscodeGate = Semaphore(1)

/**
 * The compression an upload applies to the bytes it is about to send, shared by the backup and the
 * import. A photo skips compression when it is a Motion Photo (the re-encode would drop the clip) or an
 * Ultra HDR still without the HDR opt-in (it would drop the gain map); otherwise it goes through
 * [UploadImageCompressor]. A video is transcoded by [VideoUploadCompression] behind the process-wide
 * transcode gate. Every failure leaves the input to be sent as it is.
 */
@Singleton
class UploadCompressionStep @Inject constructor(
    @ApplicationContext private val context: Context,
    private val structuralStripper: UploadStructuralStripper,
    private val videoCompression: VideoUploadCompression,
) {

    /**
     * One photo's compression decision.
     *
     * @property file the smaller JPEG temp to send (caller-owned), or null to send the input.
     * @property outcome what was decided, or null when photo compression does not apply.
     * @property sourceBytes the input's size as the compressor measured it, or null for a skip decided
     *  before the compressor ran.
     * @property isMotionPhoto compression is on and the input is a Motion Photo.
     */
    data class PhotoResult(
        val file: File?,
        val outcome: CompressionOutcome?,
        val reason: CompressionSkipReason?,
        val sourceBytes: Long?,
        val isMotionPhoto: Boolean,
    ) {
        /** The format of [file], or null when nothing was re-encoded. */
        val producedMime: String? get() = file?.let { UploadImageCompressor.JPEG_MIME }
    }

    /** Compresses the photo at [uri] of type [mimeType]; [stripConfig] gates the EXIF copied onto the output. */
    fun photo(
        uri: String,
        mimeType: String,
        settings: CompressionSettings,
        stripConfig: MetadataStripConfig,
    ): PhotoResult {
        val isMotionPhoto = settings.photos &&
            mimeType.startsWith("image/") &&
            structuralStripper.isMotionPhotoUpload(uri)
        if (isMotionPhoto) {
            return PhotoResult(null, CompressionOutcome.SKIPPED, CompressionSkipReason.MOTION_PHOTO, null, true)
        }
        val hasGainMap = UploadImageCompressor.skipsCompressionForGainMap(
            mimeType,
            settings.photos,
            settings.hdr,
            hasGainMap = { structuralStripper.hasGainMapUpload(uri) },
        )
        if (hasGainMap) {
            return PhotoResult(null, CompressionOutcome.SKIPPED, CompressionSkipReason.ULTRA_HDR, null, false)
        }
        if (!settings.photos || !mimeType.startsWith("image/")) return PhotoResult(null, null, null, null, false)
        val outcome = UploadImageCompressor.compress(context, uri, settings.tier, stripConfig)
        return PhotoResult(outcome.file, outcome.outcome, outcome.reason, outcome.sourceBytes, false)
    }

    /** Whether [video] applies to a file of type [mimeType] under [settings]. */
    fun compressesVideo(mimeType: String, settings: CompressionSettings): Boolean =
        settings.videos && mimeType.startsWith("video/")

    /**
     * Transcodes the video at [uri] behind the process-wide gate, stamping [captureMs] into the output.
     * [onPlanned] and [onProgress] are [VideoUploadCompression.compress]'s; a cancellation propagates.
     */
    suspend fun video(
        uri: String,
        settings: CompressionSettings,
        captureMs: Long,
        onPlanned: (VideoCompressionPlan.Transcode) -> Unit = {},
        onProgress: (VideoUploadCompressor.Progress) -> Unit = {},
    ): VideoUploadCompression.Attempt = videoTranscodeGate.withPermit {
        videoCompression.compress(
            sourceUri = uri,
            profile = settings.videoProfile,
            captureDateMs = captureMs,
            onPlanned = onPlanned,
            onProgress = onProgress,
        )
    }
}
