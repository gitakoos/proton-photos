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

package eu.akoos.photos.data.upload.compression

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.akoos.photos.data.upload.VideoUploadCompressor
import eu.akoos.photos.domain.entity.compression.CompressionConditions
import eu.akoos.photos.domain.entity.compression.CompressionOutcome
import eu.akoos.photos.domain.entity.compression.CompressionSkipReason
import eu.akoos.photos.domain.entity.compression.VideoCompressionPlan
import eu.akoos.photos.domain.entity.compression.VideoCompressionProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** Probes, plans and transcodes one video for the backup upload. */
@Singleton
class VideoUploadCompression @Inject constructor(
    @ApplicationContext private val context: Context,
    private val capabilities: VideoEncoderCapabilities,
) {

    /**
     * @property file the compressed temp to upload (caller-owned), or null to upload the original.
     * @property codecMime the codec that came out, or the planned one.
     * @property detail a short technical note on a failure, such as an encoder error code.
     */
    data class Attempt(
        val file: File?,
        val outcome: CompressionOutcome,
        val reason: CompressionSkipReason?,
        val sourceBytes: Long? = null,
        val codecMime: String? = null,
        val outputBytes: Long? = null,
        val detail: String? = null,
    )

    /**
     * Compress the clip at [sourceUri]. [onPlanned] fires once a transcode is decided, before the first
     * frame, then [onProgress] ticks while encoding. A cancellation deletes the temp and propagates.
     */
    suspend fun compress(
        sourceUri: String,
        profile: VideoCompressionProfile,
        captureDateMs: Long,
        onPlanned: (VideoCompressionPlan.Transcode) -> Unit = {},
        onProgress: (VideoUploadCompressor.Progress) -> Unit = {},
    ): Attempt = withContext(Dispatchers.IO) {
        val uri = Uri.parse(sourceUri)
        val source = VideoSourceProbe.probe(context, uri)
            ?: return@withContext Attempt(null, CompressionOutcome.SKIPPED, CompressionSkipReason.UNREADABLE)
        when (val plan = VideoCompressionPlanner.plan(source, profile, capabilities.snapshot(), conditions())) {
            is VideoCompressionPlan.Skip -> Attempt(null, CompressionOutcome.SKIPPED, plan.reason, source.sizeBytes)
            is VideoCompressionPlan.Transcode -> {
                onPlanned(plan)
                val result = VideoUploadCompressor.transcode(
                    context, uri, source, plan,
                    sourceDateEpochMs = captureDateMs,
                    onProgress = onProgress,
                )
                Attempt(
                    file = result.file,
                    outcome = result.outcome,
                    reason = result.reason,
                    sourceBytes = source.sizeBytes,
                    codecMime = result.outputMime ?: plan.codec.mimeType,
                    outputBytes = result.outputBytes,
                    detail = result.detail,
                )
            }
        }
    }

    /** A phone at SEVERE thermal status or worse doesn't start a transcode; the temp needs free space. */
    private fun conditions(): CompressionConditions {
        val thermal = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val power = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            (power?.currentThermalStatus ?: PowerManager.THERMAL_STATUS_NONE) >= PowerManager.THERMAL_STATUS_SEVERE
        } else {
            false
        }
        val free = runCatching { context.cacheDir.usableSpace }.getOrNull()?.takeIf { it > 0L }
        return CompressionConditions(thermalThrottled = thermal, freeBytes = free)
    }
}
