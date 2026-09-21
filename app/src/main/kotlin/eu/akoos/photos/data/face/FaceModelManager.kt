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

/*
 * The fetch-verify-cache flow here is derived from mobile_ocr, which is MIT licensed:
 *
 *   Copyright (c) 2025 Laurens Priem
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this software and
 * associated documentation files (the "Software"), to deal in the Software without restriction,
 * including without limitation the rights to use, copy, modify, merge, publish, distribute,
 * sublicense, and/or sell copies of the Software, subject to the conditions of the MIT License.
 */

package eu.akoos.photos.data.face

import android.content.Context
import android.util.Log
import eu.akoos.photos.data.preferences.SettingsKeys
import eu.akoos.photos.data.preferences.settingsDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/** How a request to fetch the model over the network ended. */
sealed interface FaceModelOutcome {

    data class Ready(val file: File) : FaceModelOutcome

    /** Bytes arrived but failed the size or digest check; the partial file was removed. */
    data object Corrupt : FaceModelOutcome

    /** Nothing arrived: no network, a bad response, or the request was dropped mid-flight. */
    data class Unreachable(val cause: Throwable) : FaceModelOutcome
}

/** Whether the face detector can run right now, and what stands in the way when it cannot. */
sealed interface FaceModelPreparation {

    /** The model is on this device and a run can start. Carries the resolved file. */
    data class Ready(val file: File) : FaceModelPreparation

    /** The model is pinned and downloadable, but the user has not agreed to the download yet. */
    data object NeedsConsent : FaceModelPreparation

    /** The model could not be made available; [reason] says why. */
    data class Failed(val reason: String) : FaceModelPreparation
}

/**
 * Resolves the face detector's model file to a path the ONNX runtime can open.
 *
 * The order is deliberate and it is the whole point of this piece. A copy already verified in
 * app-private storage is used first, then a copy side-loaded into `getExternalFilesDir(null)/face`,
 * so a build can be exercised before the asset is published anywhere. Only when neither exists is a
 * network fetch considered, and that fetch is gated twice: the asset has to be pinned to exact bytes
 * (see [FaceModelAssets]) AND the user has to have agreed to the download.
 *
 * While the asset is not pinned, that second path can never run: an unverifiable binary is never
 * fetched, because a download that cannot be size- and digest-checked is exactly what this rail
 * exists to refuse. So until the detector asset is published and pinned, the only way onto a device is
 * the side-load path, which is what a developer uses now. The streaming download, the length cap, and
 * the verify-then-promote-by-rename are all in place regardless, so the moment the digest is pinned
 * the network path is live with no further change here.
 */
class FaceModelManager(context: Context) {

    private val appContext = context.applicationContext

    /** Serialises resolution so two requests in a row cannot start two downloads. */
    private val gate = Mutex()

    /**
     * The file that has already passed verification. Hashing several megabytes costs hundreds of
     * milliseconds, not worth paying on every request; the path is re-checked for existence before it
     * is trusted.
     */
    private var verified: File? = null

    /** App-private cache for the downloaded model. */
    private val modelsDir: File get() = File(appContext.filesDir, FaceModelAssets.DIRECTORY)

    /** Side-load root, matching the OCR rail's convention so one adb push serves the same way. */
    private val sideLoadDir: File?
        get() = appContext.getExternalFilesDir(null)?.let { File(it, FaceModelAssets.DIRECTORY) }

    /**
     * The verified model file already on this device, or null when it would have to be fetched.
     * Touches no network, so it is what a caller asks before deciding whether consent is needed.
     */
    suspend fun onDisk(): File? = withContext(Dispatchers.IO) {
        gate.withLock { resolveLocal(FaceModelAssets.MODEL) }
    }

    /**
     * Gets the model in place. A local copy (verified cache, then side-loaded dev copy) is used with
     * no network touched; failing that, and only when the asset is pinned and the user has agreed, the
     * bytes are fetched, verified and promoted. [onDownloadStart] fires once, just before the first
     * byte moves, so a caller can label the wait as the download it is rather than a stalled read.
     * [onProgress] reports the running byte count for this asset as it arrives, so a caller can show a
     * real bar instead of an open-ended spinner.
     */
    suspend fun prepare(
        onDownloadStart: () -> Unit = {},
        onProgress: (Long) -> Unit = {},
    ): FaceModelPreparation =
        withContext(Dispatchers.IO) {
            gate.withLock {
                val asset = FaceModelAssets.MODEL
                resolveLocal(asset)?.let { return@withLock FaceModelPreparation.Ready(it) }

                // No local copy. A network fetch is only possible once the bytes can be verified.
                if (!asset.isPinned) return@withLock FaceModelPreparation.Failed(REASON_NOT_PINNED)
                if (!downloadAccepted()) return@withLock FaceModelPreparation.NeedsConsent

                onDownloadStart()
                when (val outcome = download(asset, onProgress)) {
                    is FaceModelOutcome.Ready -> FaceModelPreparation.Ready(outcome.file)
                    FaceModelOutcome.Corrupt -> FaceModelPreparation.Failed(REASON_CORRUPT)
                    is FaceModelOutcome.Unreachable ->
                        FaceModelPreparation.Failed(outcome.cause.message ?: REASON_UNREACHABLE)
                }
            }
        }

    /**
     * Removes both model roots from this device and forgets what was verified in memory.
     *
     * The app-private cache under [modelsDir] and the side-load root are the whole of the detector's
     * on-disk footprint, so deleting both and clearing [verified] is a complete removal; the pinned
     * size-plus-digest rule means a later copy is verified from scratch regardless. Serialised on
     * [gate] so it cannot race a fetch promoting a file at the same moment.
     */
    suspend fun deleteAll() = withContext(Dispatchers.IO) {
        gate.withLock {
            listOfNotNull(modelsDir, sideLoadDir).forEach { dir ->
                if (dir.isDirectory) dir.deleteRecursively()
            }
            verified = null
        }
    }

    /** A verified cache copy first, then a side-loaded copy. Caller holds [gate]. */
    private fun resolveLocal(asset: FaceModelAsset): File? {
        verified?.let { if (it.isFile) return it }

        val cached = File(modelsDir, asset.fileName)
        if (cached.isFile) {
            when (val check = verify(cached, asset)) {
                FaceModelCheck.Ok -> {
                    Log.i(TAG, "using the cached ${asset.fileName}")
                    return remember(cached)
                }
                else -> {
                    Log.w(TAG, "discarding the cached ${asset.fileName}, $check")
                    cached.delete()
                }
            }
        }

        sideLoadDir?.let { dir ->
            val candidate = File(dir, asset.fileName)
            if (candidate.isFile) {
                when (val check = verify(candidate, asset)) {
                    FaceModelCheck.Ok -> {
                        Log.i(TAG, "using the side-loaded ${asset.fileName}")
                        return remember(candidate)
                    }
                    // Left alone: it is the developer's file, not this app's to delete.
                    else -> Log.w(TAG, "ignoring the side-loaded ${asset.fileName}, $check")
                }
            }
        }

        return null
    }

    private fun remember(file: File): File = file.also { verified = it }

    /** Streams [asset] to a temporary file, verifies it, and promotes it. Caller holds [gate]. */
    private suspend fun download(asset: FaceModelAsset, onProgress: (Long) -> Unit): FaceModelOutcome {
        modelsDir.mkdirs()
        val target = File(modelsDir, asset.fileName)
        // Same directory as the target so the promotion is a rename within one filesystem.
        val partial = File(modelsDir, "${asset.fileName}.part")
        partial.delete()
        val url = FaceModelAssets.downloadUrl(asset)
        return try {
            Log.i(TAG, "fetching ${asset.fileName} (${asset.sizeBytes} bytes)")
            streamTo(url, partial, asset.sizeBytes, onProgress)
            when (val check = verify(partial, asset)) {
                FaceModelCheck.Ok -> {
                    target.delete()
                    if (!partial.renameTo(target)) throw IOException("could not store ${asset.fileName}")
                    Log.i(TAG, "downloaded and verified ${asset.fileName}")
                    remember(target)
                    FaceModelOutcome.Ready(target)
                }
                else -> {
                    Log.w(TAG, "rejecting the downloaded ${asset.fileName}, $check")
                    partial.delete()
                    FaceModelOutcome.Corrupt
                }
            }
        } catch (t: Throwable) {
            partial.delete()
            currentCoroutineContext().ensureActive()
            Log.w(TAG, "could not fetch ${asset.fileName}: ${t.message}")
            FaceModelOutcome.Unreachable(t)
        }
    }

    /**
     * Writes [url] into [into], stopping early on a response that claims more than [expectedBytes] so
     * a wrong or hostile URL cannot fill the disk. Cancellation is checked per chunk, which is what
     * lets a caller drop the fetch the moment the user backs out. [onProgress] is handed the running
     * byte total after each chunk lands, so a caller can drive a determinate bar.
     */
    private suspend fun streamTo(
        url: String,
        into: File,
        expectedBytes: Long,
        onProgress: (Long) -> Unit,
    ) {
        val request = Request.Builder().url(url).build()
        val call = httpClient.newCall(request)
        call.execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            val body = response.body ?: throw IOException("empty response")
            body.byteStream().use { input ->
                FileOutputStream(into).use { output ->
                    val buffer = ByteArray(BUFFER_BYTES)
                    var written = 0L
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val read = input.read(buffer)
                        if (read <= 0) break
                        written += read
                        if (written > expectedBytes) throw IOException("response longer than expected")
                        output.write(buffer, 0, read)
                        onProgress(written)
                    }
                    output.flush()
                }
            }
        }
    }

    private fun verify(file: File, asset: FaceModelAsset): FaceModelCheck = checkFaceModel(
        asset = asset,
        present = file.isFile,
        actualSize = file.length(),
        actualSha256 = { runCatching { sha256(file) }.getOrNull() },
    )

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { stream ->
            val buffer = ByteArray(BUFFER_BYTES)
            while (true) {
                val read = stream.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private suspend fun downloadAccepted(): Boolean =
        appContext.settingsDataStore.data.first()[SettingsKeys.FACE_MODEL_DOWNLOAD_ALLOWED] == true

    private companion object {
        const val TAG = "FaceModel"
        const val BUFFER_BYTES = 64 * 1024

        const val REASON_NOT_PINNED = "the face model is not published yet"
        const val REASON_CORRUPT = "the downloaded face model failed verification"
        const val REASON_UNREACHABLE = "the face model could not be fetched"

        /**
         * Own transport rather than the GitHub API client: a release asset URL redirects to the
         * object CDN, which rejects the API's Accept header on the signed follow-up request.
         */
        val httpClient: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(30L, TimeUnit.SECONDS)
            .readTimeout(60L, TimeUnit.SECONDS)
            .build()
    }
}
