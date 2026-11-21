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

import android.content.Context
import android.net.Uri
import android.os.Build
import android.util.Log
import eu.akoos.photos.data.db.dao.PhotoLocationDao
import eu.akoos.photos.data.upload.MirrorSourceCheck
import eu.akoos.photos.data.upload.UploadCompressionStep
import eu.akoos.photos.data.upload.UploadDeviceFiles
import eu.akoos.photos.data.upload.UploadImageCompressor
import eu.akoos.photos.data.upload.VideoUploadCompressor
import eu.akoos.photos.domain.entity.LocalMediaItem
import eu.akoos.photos.domain.entity.SyncState
import eu.akoos.photos.domain.entity.compression.CompressionSettings
import eu.akoos.photos.domain.entity.compression.CompressionSkipReason
import eu.akoos.photos.util.ExifHelper
import eu.akoos.photos.util.MetadataStripConfig
import eu.akoos.photos.util.Mp4CreationTime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Semaphore
import me.proton.core.domain.entity.UserId
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * The temps and facts an upload's byte preparation leaves for the upload that follows. The caller owns
 * it and its `finally` deletes whatever temp is still set, so a temp made by a step that then throws is
 * cleaned up all the same.
 */
internal class UploadTemps {
    var strippedFile: File? = null
    var compressedFile: File? = null
    // A temp holding a timestamp-floored copy of an uncompressed video (Fix: backup video mvhd
    // floor). Tracked alongside the other temps so it is cleaned up on both success and failure.
    var tsFloorFile: File? = null
    // The format a re-encode wrote when it changed the file's format: JPEG from photo compression
    // or the HEIC strip transcode, MP4 from a video transcode. The upload's name and type follow it.
    var producedMime: String? = null
    // Set once a mirror step rewrote the on-device file, whose bytes may then differ from the upload's.
    // Only ever set, never cleared: a later step that leaves the file alone does not undo an earlier rewrite.
    var deviceRewritten = false
    // Set when the strip fell back to the untouched original while it still carries the location the
    // user asked to strip. Only a re-encode that leaves the location out (the compression) may then send
    // the file; otherwise [UploadBytePreparer.compress] holds the upload back.
    var locationStripOwed = false
}

/** The location strip failed and only the original, location included, was left to send. */
internal class LocationStripFailedException :
    IllegalStateException("location strip failed; the original is held back until it can be stripped")

/**
 * Whether a video whose strip remux and in-place erase both failed still owes its location strip:
 * always when its bytes could not be copied or are an MP4 / MOV the erase could not walk (the location
 * may sit anywhere in a tree it could not read), else only when the platform reads a location from it.
 */
internal inline fun videoLocationStripOwed(copied: Boolean, isoMedia: Boolean, hasLocation: () -> Boolean): Boolean =
    !copied || isoMedia || hasLocation()

/**
 * Whether a photo the strip left untouched still owes its location strip: when the user strips GPS
 * and the file carries a fix. An HEIC-family motion photo is the exception: its container cannot be
 * rewritten and re-encoding it would drop the clip, so it uploads as it is, as it always has.
 */
internal inline fun photoLocationStripOwed(
    stripGps: Boolean,
    hasLocation: () -> Boolean,
    keepsMotionAsIs: () -> Boolean,
): Boolean = stripGps && hasLocation() && !keepsMotionAsIs()

/** What [UploadBytePreparer.compress] settled on: the uri to hash and send, and whether compression met a Motion Photo. */
internal class PreparedUpload(val finalUploadUri: String, val compressIsMotionPhoto: Boolean)

/**
 * Prepares the bytes one backup upload sends: the metadata strip (with its mirror onto the device file),
 * the compression and the mvhd floor of an uncompressed video, and after the upload the mirror of the
 * compression onto the device file. Every temp and fact goes into the caller's [UploadTemps].
 */
internal class UploadBytePreparer(
    private val context: Context,
    private val structuralStripper: UploadStructuralStripper,
    private val compressionStep: UploadCompressionStep,
    private val deviceFiles: UploadDeviceFiles,
    private val photoLocationDao: PhotoLocationDao,
) {

    /** The metadata strip; returns the uri the compression step reads. */
    suspend fun strip(
        userId: UserId,
        state: SyncState,
        localItem: LocalMediaItem,
        stripOnUpload: Boolean,
        stripConfig: MetadataStripConfig,
        mirrorStripToLocal: Boolean,
        compression: CompressionSettings,
        temps: UploadTemps,
    ): String {
        // Strip metadata into a temp file if configured. Images go through EXIF tag
        // wiping; videos through a stream-copy re-mux that drops the container's GPS
        // location atom (EXIF wiping can't reach an MP4/MOV moov/udta). Both paths
        // fall back to the original URI when the temp couldn't be produced. When that
        // original still carries the location the user asked to strip, the fallback
        // marks it owed and [compress] holds the upload back unless a re-encode drops it.
        return if (stripOnUpload && localItem.mimeType.startsWith("image/")) {
            // Motion Photos carry an MP4 appended after the primary still. A plain ExifInterface
            // rewrite-strip drops that trailer (and the motion is lost), so detect first and, for
            // a motion photo, strip only the primary's EXIF into a temp while re-attaching the
            // original trailer byte-for-byte. This runs FIRST and authoritatively (MotionPhotoUtil
            // .detect) so a motion photo can never reach the in-place mirror wipe below.
            val motionTemp = structuralStripper.stripImagePreservingMotion(state.localUri, stripConfig)
            // An Ultra HDR still appends its gain map as a second image after the primary, the same
            // shape as a motion trailer, so every ordinary strip route below drops it. Decided HERE,
            // ahead of the in-place mirror wipe, because that wipe overwrites the on-device original
            // and cannot be undone. The route is taken only when the platform can prove the rebuilt
            // file still decodes WITH its gain map; on any doubt it yields null and the ordinary strip
            // runs, because a lost HDR rendition is recoverable and a shipped GPS tag is not.
            val gainMapTemp = if (motionTemp == null &&
                eu.akoos.photos.data.upload.UploadImageCompressor.attemptsGainMapPreservingStrip(
                    localItem.mimeType,
                    stripOnUpload,
                    Build.VERSION.SDK_INT,
                    hasGainMap = { structuralStripper.hasGainMapUpload(state.localUri) },
                )
            ) {
                structuralStripper.stripImagePreservingGainMap(state.localUri, stripConfig)
            } else {
                null
            }
            if (motionTemp != null) {
                temps.strippedFile = motionTemp
                Log.d(UPLOAD_TAG, "Motion Photo primary stripped, trailer preserved for ${localItem.displayName}")
                // A JPEG motion photo whose primary strip failed comes back byte-exact, fix included:
                // it waits like any other photo whose strip left its location. A HEIF one whose layout
                // the in-place GPS patch refused uploads as it is, which the stripper has logged.
                val motionKeepsLocation = stripConfig.stripGps &&
                    !eu.akoos.photos.util.HeifExifStrip.isHeif(motionTemp) &&
                    photoHasLocation(android.net.Uri.fromFile(motionTemp).toString())
                if (motionKeepsLocation) temps.locationStripOwed = true
                // Mirror: a motion photo can't be wiped in place (an EXIF rewrite drops its
                // trailer), so overwrite the on-device original with the whole motion-preserving
                // stripped file instead — the local loses its metadata too and byte-matches the
                // cloud. Silent with all-files; a refused write leaves the original intact.
                val motionMirrorLanded = !motionKeepsLocation && mirrorStripToLocal &&
                    deviceFiles.overwriteLocalInPlace(state.localUri, motionTemp, MirrorSourceCheck.Kind.MOTION_PHOTO)
                if (motionMirrorLanded) {
                    temps.deviceRewritten = true
                    Log.d(UPLOAD_TAG, "Mirror strip: on-device motion photo wiped for ${localItem.displayName}")
                }
                if (UploadPendingUseCase.mirrorRemovedDeviceGps(stripOnUpload && stripConfig.stripGps, mirrorStripToLocal, motionMirrorLanded)) {
                    invalidateMirroredLocation(userId, state.localUri)
                }
                android.net.Uri.fromFile(motionTemp).toString()
            } else if (gainMapTemp != null) {
                temps.strippedFile = gainMapTemp
                Log.d(UPLOAD_TAG, "Ultra HDR primary stripped, gain map preserved for ${localItem.displayName}")
                // Mirror: same reasoning as the motion photo. An in-place EXIF rewrite would drop the
                // appended gain map, so the on-device original is replaced with the whole verified
                // file instead. Silent with all-files; a refused write leaves the original intact.
                val gainMapMirrorLanded = mirrorStripToLocal && deviceFiles.overwriteLocalInPlace(state.localUri, gainMapTemp, MirrorSourceCheck.Kind.IMAGE)
                if (gainMapMirrorLanded) {
                    temps.deviceRewritten = true
                    Log.d(UPLOAD_TAG, "Mirror strip: on-device Ultra HDR photo wiped for ${localItem.displayName}")
                }
                if (UploadPendingUseCase.mirrorRemovedDeviceGps(stripOnUpload && stripConfig.stripGps, mirrorStripToLocal, gainMapMirrorLanded)) {
                    invalidateMirroredLocation(userId, state.localUri)
                }
                android.net.Uri.fromFile(gainMapTemp).toString()
            } else if (mirrorStripToLocal &&
                ExifHelper.stripFieldsInPlace(context, state.localUri, stripConfig, announce = false)
                    is eu.akoos.photos.util.StripResult.Stripped
            ) {
                // Confirmed neither a motion photo nor a verifiably preservable Ultra HDR (both
                // helpers above returned null) → safe to wipe the on-device original in place and
                // upload it as-is, so the local and the backed-up copy stay byte-identical and pair
                // by content hash. The wipe is the last step before the upload and is idempotent,
                // so a failed upload simply retries it.
                temps.deviceRewritten = true
                Log.d(UPLOAD_TAG, "Mirror strip: on-device original wiped for ${localItem.displayName}")
                if (UploadPendingUseCase.mirrorRemovedDeviceGps(stripOnUpload && stripConfig.stripGps, mirrorStripToLocal, deviceRewriteSucceeded = true)) {
                    invalidateMirroredLocation(userId, state.localUri)
                }
                state.localUri
            } else {
                // Mirror off, or the OS refused the in-place write (no MANAGE_MEDIA) → ordinary
                // temp-copy EXIF wipe, which leaves the original untouched. stripToTempFile
                // returning null means nothing to strip or a strip error → the original uploads.
                val stripped = ExifHelper.stripToTempFile(context, state.localUri, stripConfig)
                temps.strippedFile = stripped
                if (stripped != null) {
                    Log.d(UPLOAD_TAG, "Metadata stripped for ${localItem.displayName}")
                    android.net.Uri.fromFile(stripped).toString()
                } else if (eu.akoos.photos.data.upload.UploadImageCompressor
                        .needsStripTranscode(
                            localItem.mimeType,
                            stripOnUpload,
                            compression.photos,
                            isMotionPhoto = { structuralStripper.isMotionPhotoUpload(state.localUri) },
                        )
                ) {
                    // The container cannot be EXIF-rewritten in place (HEIC / HEIF / AVIF) and no
                    // compression pass will run to rebuild a gated JPEG, so stripToTempFile no-oped
                    // and the untouched original would ship carrying the very GPS / camera EXIF the
                    // user asked to strip. Transcode a stripped JPEG and upload that instead. A
                    // motion photo is excluded by the probe: re-encoding its primary frame would
                    // drop the appended clip, so its motion outweighs the unwritable container.
                    val transcoded = eu.akoos.photos.data.upload.UploadImageCompressor
                        .transcodeStrippedJpeg(context, state.localUri, stripConfig)
                    if (transcoded != null) {
                        temps.strippedFile = transcoded
                        temps.producedMime = UploadImageCompressor.JPEG_MIME
                        Log.d(UPLOAD_TAG, "Stripped JPEG transcoded for ${localItem.displayName} (unwritable container)")
                        android.net.Uri.fromFile(transcoded).toString()
                    } else {
                        temps.locationStripOwed = photoLocationOwed(state.localUri, localItem.mimeType, stripConfig)
                        state.localUri
                    }
                } else {
                    temps.locationStripOwed = photoLocationOwed(state.localUri, localItem.mimeType, stripConfig)
                    state.localUri
                }
            }
        } else if (stripOnUpload && stripConfig.stripGps && localItem.mimeType.startsWith("video/")) {
            val tmp = File.createTempFile("stripped_", ".mp4", eu.akoos.photos.data.localcrypto.VaultFiles.plainStagingDir(context))
            val remuxed = eu.akoos.photos.util.VideoMetadataStripper.remuxWithoutLocation(context, state.localUri, tmp)
            // The remux failed: erase the location atoms in place on a copy instead, which keeps every
            // other byte of the file. Null when that cannot vouch for the copy either.
            if (!remuxed) tmp.delete()
            val stripped = if (remuxed) tmp else eraseLocationOnCopy(state.localUri, temps)
            if (stripped != null) {
                temps.strippedFile = stripped
                Log.d(UPLOAD_TAG, "Video location atom stripped for ${localItem.displayName} (${if (remuxed) "remux" else "in place"})")
                // Mirror: overwrite the on-device video with the location-stripped remux so the
                // local loses its GPS too and matches the cloud. Silent with all-files; a refused
                // write leaves the original intact.
                val videoMirrorLanded = mirrorStripToLocal && deviceFiles.overwriteLocalInPlace(state.localUri, stripped, MirrorSourceCheck.Kind.VIDEO)
                if (videoMirrorLanded) {
                    temps.deviceRewritten = true
                    Log.d(UPLOAD_TAG, "Mirror strip: on-device video wiped for ${localItem.displayName}")
                }
                if (UploadPendingUseCase.mirrorRemovedDeviceGps(stripOnUpload && stripConfig.stripGps, mirrorStripToLocal, videoMirrorLanded)) {
                    invalidateMirroredLocation(userId, state.localUri)
                }
                android.net.Uri.fromFile(stripped).toString()
            } else {
                // Neither strip landed. The original goes on only when it carries no location; a video
                // that may still hold one waits for the compression to drop it or for a later pass.
                Log.w(UPLOAD_TAG, "Video location strip failed for ${localItem.displayName}")
                state.localUri
            }
        } else {
            state.localUri
        }
    }

    /**
     * A copy of the video at [uri] with its location atoms erased in place, or null when the copy or the
     * erase fails. A null also settles [UploadTemps.locationStripOwed] for the original.
     */
    private fun eraseLocationOnCopy(uri: String, temps: UploadTemps): File? {
        val copy = File.createTempFile("stripped_", ".mp4", eu.akoos.photos.data.localcrypto.VaultFiles.plainStagingDir(context))
        val copied = runCatching {
            context.contentResolver.openInputStream(eu.akoos.photos.data.localcrypto.VaultFiles.readableUri(context, Uri.parse(uri)))!!
                .use { input -> copy.outputStream().use { input.copyTo(it) } }
        }.isSuccess
        if (copied && eu.akoos.photos.util.Mp4LocationAtoms.erase(copy)) return copy
        val isoMedia = copied && eu.akoos.photos.util.Mp4LocationAtoms.isIsoMedia(copy)
        copy.delete()
        temps.locationStripOwed = videoLocationStripOwed(copied, isoMedia) {
            eu.akoos.photos.util.PhotoGpsResolver.videoGps(context, uri) != null
        }
        return null
    }

    /** Whether the untouched photo at [uri] still owes its location strip; read as the upload reads it. */
    private fun photoLocationOwed(uri: String, mimeType: String, stripConfig: MetadataStripConfig): Boolean =
        photoLocationStripOwed(
            stripGps = stripConfig.stripGps,
            hasLocation = { photoHasLocation(uri) },
            keepsMotionAsIs = {
                UploadImageCompressor.isExifUnwritable(mimeType) && structuralStripper.isMotionPhotoUpload(uri)
            },
        )

    /** Whether the photo at [uri] carries a usable GPS fix, read as the upload reads it. */
    private fun photoHasLocation(uri: String): Boolean {
        val meta = runCatching { ExifHelper.readMetadata(context, uri) }.getOrNull()
        val lat = meta?.gpsLatitude
        val lng = meta?.gpsLongitude
        return lat != null && lng != null && eu.akoos.photos.data.db.entity.isUsableGpsFix(lat, lng)
    }

    /** The compression and the uncompressed-video mvhd floor, reading [strippedUploadUri]. */
    suspend fun compress(
        state: SyncState,
        localItem: LocalMediaItem,
        stripOnUpload: Boolean,
        stripConfig: MetadataStripConfig,
        compression: CompressionSettings,
        strippedUploadUri: String,
        temps: UploadTemps,
        totalCount: Int,
        finishedCount: AtomicInteger,
        /** The batch's upload slots; this item holds one and hands it back while it transcodes. */
        uploadSlot: Semaphore,
        emit: (UploadProgress) -> Unit,
    ): PreparedUpload {
        // Opt-in image compression. Runs AFTER the strip fork so it composes: it recompresses
        // whatever [strippedUploadUri] resolved to (the stripped temp when stripping, else the
        // original), so a stripped + compressed photo keeps the strip and ends up as the lighter
        // JPEG. This branch is images only; video is handled by the video-compress branch below,
        // not uploaded here. On any failure (or when the
        // recompressed copy would not be smaller) the recompressor returns null and the upload
        // proceeds with the pre-compression bytes, so a compression failure never blocks a backup.
        // The bytes sent, hashed, and sized are the compressed copy, mirroring the strip handling.
        // A Motion Photo is a still with an MP4 appended after it; the image compressor decodes
        // only the primary frame and re-encodes a plain JPEG, which would silently drop the motion
        // trailer. Skip compression for one (the stripped/original bytes upload as-is, keeping the
        // motion). Detection reuses MotionPhotoUtil against the exact bytes about to be compressed.
        // An Ultra HDR still is the same story with a gain map in place of a clip: the compressor
        // decodes only the primary frame, so re-encoding drops the appended gain map and the photo
        // loses its HDR rendition, so one is skipped too unless the HDR opt-in is on. Both probes
        // read the exact bytes about to be compressed.
        //
        // The compressor rebuilds the output JPEG's EXIF from the source it recompresses, so it
        // must honour the strip directly: when the source is a format the strip step could not
        // rewrite (HEIC), strippedUploadUri fell back to the untouched original and copying its
        // EXIF unfiltered would re-inject the GPS / camera tags the user asked to remove. Pass
        // the effective config so those groups are dropped; a no-op when strip-on-upload is off,
        // so a non-stripping upload still carries the full EXIF byte-for-byte as before.
        val compressStripConfig = if (stripOnUpload) stripConfig else MetadataStripConfig()
        val photoCompression = compressionStep.photo(strippedUploadUri, localItem.mimeType, compression, compressStripConfig)
        val compressIsMotionPhoto = photoCompression.isMotionPhoto
        when (photoCompression.reason) {
            CompressionSkipReason.MOTION_PHOTO ->
                Log.d(UPLOAD_TAG, "Skipping image compression for motion photo ${localItem.displayName}; motion preserved")
            CompressionSkipReason.ULTRA_HDR ->
                Log.d(UPLOAD_TAG, "Skipping image compression for Ultra HDR ${localItem.displayName}; gain map preserved")
            else -> Unit
        }
        photoCompression.outcome?.let { outcome ->
            logCompression(
                state.localUri, "photo", outcome, photoCompression.reason,
                photoCompression.sourceBytes ?: localItem.sizeBytes, photoCompression.file?.length(),
            )
        }
        val photoCompressed = photoCompression.file
        val uploadUri: String = if (photoCompressed != null) {
            temps.compressedFile = photoCompressed
            temps.producedMime = photoCompression.producedMime
            // The stripped temp (if any) is now superseded by the compressed copy, so delete
            // it so a strip+compress pass doesn't leak the intermediate. The compressed file is
            // the only temp we still need, tracked for cleanup in uploadOne's finally.
            temps.strippedFile?.delete()
            temps.strippedFile = null
            Log.d(UPLOAD_TAG, "Compressed ${localItem.displayName} for upload (tier=${compression.tier.name})")
            android.net.Uri.fromFile(photoCompressed).toString()
        } else if (compressionStep.compressesVideo(localItem.mimeType, compression)) {
            // Probe, plan and transcode behind the process-wide gate, with this item's upload slot
            // handed back meanwhile so photos keep uploading. A stopped worker cancels the
            // transcode. Without a file the original uploads; the diagnostics line says why.
            fun emitPhase(status: UploadStatus, sizeBytes: Long, compress: VideoUploadCompressor.Progress? = null) {
                emit(
                    UploadProgress(
                        uri = state.localUri,
                        displayName = localItem.displayName,
                        status = status,
                        doneIdx = finishedCount.get(),
                        totalCount = totalCount,
                        sizeBytes = sizeBytes,
                        compressFraction = compress?.fraction ?: 0f,
                        compressCodecMime = compress?.codec?.mimeType,
                        compressSpeedX = compress?.speedX,
                    )
                )
            }
            val attempt = uploadSlot.releasedWhile {
                emitPhase(UploadStatus.Queued, localItem.sizeBytes)
                compressionStep.video(
                    uri = strippedUploadUri,
                    settings = compression,
                    captureMs = localItem.dateTaken,
                    // The strip in effect, as for the photo compressor: when the location strip above
                    // fell back to the original, the transcode still leaves the location out.
                    stripConfig = compressStripConfig,
                    onPlanned = { plan ->
                        emitPhase(UploadStatus.Compressing, localItem.sizeBytes, VideoUploadCompressor.Progress(0f, plan.codec, null))
                    },
                    onProgress = { emitPhase(UploadStatus.Compressing, localItem.sizeBytes, it) },
                ).also { emitPhase(UploadStatus.Queued, it.file?.length() ?: localItem.sizeBytes) }
            }
            // Back to the pre-network phase for the encrypt that follows.
            emitPhase(UploadStatus.Encrypting, attempt.file?.length() ?: localItem.sizeBytes)
            logCompression(
                state.localUri, "video", attempt.outcome, attempt.reason,
                attempt.sourceBytes ?: localItem.sizeBytes, attempt.outputBytes, attempt.codecMime, attempt.detail,
            )
            val compressed = attempt.file
            if (compressed != null) {
                temps.compressedFile = compressed
                temps.producedMime = UploadFormatNaming.MP4
                temps.strippedFile?.delete()
                temps.strippedFile = null
                android.net.Uri.fromFile(compressed).toString()
            } else {
                strippedUploadUri
            }
        } else {
            strippedUploadUri
        }

        // The strip fell back to an original that still carries its location, and no re-encode dropped
        // it: hold the upload back. The row returns to the queue and the next pass tries the strip again.
        if (temps.locationStripOwed && temps.compressedFile == null) {
            eu.akoos.photos.util.SyncDiagnostics.log(
                "upload ${eu.akoos.photos.util.uploadLogRef(state.localUri)}: location strip failed, original held back"
            )
            throw LocationStripFailedException()
        }

        // Strip-timestamp floors the cloud DISPLAY date (localItem.dateTaken is now), and the
        // compress path already stamps that floored time into the transcoded mvhd. The gap is an
        // UNCOMPRESSED video: its container's own mvhd creation time can still reveal the real
        // capture moment. When the bytes about to be uploaded are the original video (no compress),
        // floor the mvhd to localItem.dateTaken too so the file itself can't reconstruct it. When a
        // GPS-strip temp already exists, stamp that in place; otherwise copy the original URI's
        // bytes to a fresh temp, stamp that, and upload the temp. Best-effort: any failure falls
        // back to uploading the original bytes.
        var finalUploadUri = uploadUri
        if (localItem.mimeType.startsWith("video/") &&
            stripOnUpload && stripConfig.stripTimestamp && temps.compressedFile == null
        ) {
            if (temps.strippedFile != null) {
                Mp4CreationTime.stamp(temps.strippedFile!!, localItem.dateTaken)
            } else {
                runCatching {
                    val tmp = File(eu.akoos.photos.data.localcrypto.VaultFiles.plainStagingDir(context), "upl_ts_" + System.nanoTime() + ".mp4")
                    context.contentResolver.openInputStream(Uri.parse(uploadUri))!!.use { input ->
                        tmp.outputStream().use { output -> input.copyTo(output) }
                    }
                    Mp4CreationTime.stamp(tmp, localItem.dateTaken)
                    temps.tsFloorFile = tmp
                    finalUploadUri = Uri.fromFile(tmp).toString()
                }.onFailure {
                    Log.w(UPLOAD_TAG, "Video mvhd floor copy failed for ${localItem.displayName}; uploading original")
                }
            }
        }
        return PreparedUpload(finalUploadUri, compressIsMotionPhoto)
    }

    /** After a committed upload, the compression mirrored onto the device file. */
    fun mirrorCompressAfterUpload(
        state: SyncState,
        localItem: LocalMediaItem,
        stripOnUpload: Boolean,
        mirrorStripToLocal: Boolean,
        mirrorCompressToLocal: Boolean,
        compression: CompressionSettings,
        compressIsMotionPhoto: Boolean,
        temps: UploadTemps,
    ) {
        // Mirror-compress the on-device original, now that the upload is committed and never
        // before: like the rename in uploadOne, a failed upload must not leave the local shrunk with no
        // Drive copy. Reuses the compressed temp before uploadOne deletes it; the overwrite is
        // crash-safe (stage a backup, truncate-write, restore on failure), so the original is never
        // left partial. Independent of the strip mirror: the local ends up COMPRESSED, and stripped
        // only when mirrorStripToLocal is also on.
        val mirrorCompressImage = compression.photos && mirrorCompressToLocal &&
            localItem.mimeType.startsWith("image/")
        if (
            mirrorCompressImage &&
            !eu.akoos.photos.data.upload.UploadImageCompressor
                .canOverwriteLocalWithCompressedJpeg(localItem.mimeType)
        ) {
            // The compressor only ever encodes JPEG, so replacing a PNG / WebP / HEIC original with
            // its output would leave a file whose bytes contradict its name and its MediaStore mime.
            // Leave the on-device file untouched; the uploaded copy is compressed either way.
            Log.d(
                UPLOAD_TAG,
                "Mirror compress skipped for ${localItem.displayName}: ${localItem.mimeType} cannot hold a JPEG",
            )
        } else if (mirrorCompressImage) {
            if (mirrorStripToLocal || !stripOnUpload) {
                // compressedFile already holds the right local content: compressed from the stripped
                // temp when strip is mirrored, or from the original when strip-upload is off. A null
                // (compression failed or not smaller) skips silently.
                temps.compressedFile?.let { local ->
                    if (deviceFiles.overwriteLocalInPlace(state.localUri, local, MirrorSourceCheck.Kind.IMAGE)) {
                        temps.deviceRewritten = true
                        Log.d(UPLOAD_TAG, "Mirror compress: on-device original compressed for ${localItem.displayName}")
                    }
                }
            } else if (!compressIsMotionPhoto &&
                !eu.akoos.photos.data.upload.UploadImageCompressor.skipsCompressionForGainMap(
                    localItem.mimeType,
                    compression.photos,
                    compression.hdr,
                    hasGainMap = { structuralStripper.hasGainMapUpload(state.localUri) },
                )
            ) {
                // stripOnUpload && !mirrorStripToLocal: the upload's compressed temp is stripped, but
                // the user did not opt to strip the local. Compress a FRESH strip-free copy from the
                // untouched original and overwrite with that, then delete the fresh temp. Skipped for
                // a motion photo so the on-device motion is never re-encoded away, and for an Ultra
                // HDR still so its gain map is never flattened out of the local copy. That probe reads
                // the untouched original, which is exactly what this branch would overwrite, and not
                // the stripped temp the upload-side skip looks at.
                // A no-op strip config keeps this local copy's EXIF intact: the user opted to
                // compress the on-device original but not to strip it, so it must retain the full
                // metadata the untouched original carries.
                val localCompressed = eu.akoos.photos.data.upload.UploadImageCompressor
                    .compressToTemp(context, state.localUri, compression.tier, MetadataStripConfig())
                if (localCompressed != null) {
                    if (deviceFiles.overwriteLocalInPlace(state.localUri, localCompressed, MirrorSourceCheck.Kind.IMAGE)) {
                        temps.deviceRewritten = true
                        Log.d(UPLOAD_TAG, "Mirror compress: on-device original compressed (strip-free) for ${localItem.displayName}")
                    }
                    localCompressed.delete()
                }
            }
        }
        // Mirror-compress the on-device video: reuse the already-transcoded temp, never re-transcode.
        // Safe to mirror when the local content matches that temp: strip mirrored (both stripped) or
        // strip-upload off (neither stripped).
        if (compression.videos && mirrorCompressToLocal && localItem.mimeType.startsWith("video/")) {
            if (!UploadFormatNaming.isMp4(localItem.mimeType)) {
                // The transcode always writes MP4, so a .mov, .3gp or .webm original would end up with
                // bytes that contradict its name and MediaStore type. Leave it untouched.
                Log.d(UPLOAD_TAG, "Mirror compress skipped for ${localItem.displayName}: ${localItem.mimeType} cannot hold an MP4")
            } else if (mirrorStripToLocal || !stripOnUpload) {
                temps.compressedFile?.let { local ->
                    if (deviceFiles.overwriteLocalInPlace(state.localUri, local, MirrorSourceCheck.Kind.VIDEO)) {
                        temps.deviceRewritten = true
                        Log.d(UPLOAD_TAG, "Mirror compress: on-device video compressed for ${localItem.displayName}")
                    }
                }
            }
            // else (stripOnUpload && !mirrorStripToLocal): skip; a strip-free re-transcode is too costly.
        }
    }

    /**
     * Best-effort drop of the on-device [uri]'s stored GPS fix once a mirror strip has rewritten that
     * file GPS-free. The map, Search's place facet and the location screen all plot `photo_location`,
     * and the GPS backfill skips any file that already has a row, so a row left standing keeps every one
     * of them on a point the file no longer carries. Scoped to the account the upload runs under, by the
     * device content URI the fix is keyed under. A delete failure costs a stale point until the next
     * pass over the file and never fails the upload it rides on.
     */
    private suspend fun invalidateMirroredLocation(userId: UserId, uri: String) {
        try {
            photoLocationDao.deleteByIds(userId.id, listOf(uri))
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.w(UPLOAD_TAG, "mirror strip location drop for $uri failed: ${e.message}")
        }
    }
}
