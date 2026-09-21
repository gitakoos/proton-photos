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

package eu.akoos.photos.data.semantic

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

/** The two verified model files semantic search runs on. */
data class SemanticModelFiles(val image: File, val text: File)

/** How a request to fetch one model over the network ended. */
sealed interface SemanticModelOutcome {

    data class Ready(val file: File) : SemanticModelOutcome

    /** Bytes arrived but failed the size or digest check; the partial file was removed. */
    data object Corrupt : SemanticModelOutcome

    /** Nothing arrived: no network, a bad response, or the request was dropped mid-flight. */
    data class Unreachable(val cause: Throwable) : SemanticModelOutcome
}

/** Whether semantic search can run right now, and what stands in the way when it cannot. */
sealed interface SemanticModelPreparation {

    /** Both models are on this device and a run can start. Carries the resolved files. */
    data class Ready(val files: SemanticModelFiles) : SemanticModelPreparation

    /** The models are pinned and downloadable, but the user has not agreed to the download yet. */
    data object NeedsConsent : SemanticModelPreparation

    /** The models could not be made available; [reason] says why. */
    data class Failed(val reason: String) : SemanticModelPreparation
}

/**
 * Resolves semantic search's two model files to paths the ONNX runtime can open.
 *
 * The twin of the face rail, generalised over the two files semantic search needs: the same
 * resolution order, the same consent gate, the same verify-then-promote fetch, only pointed at
 * [SemanticModelAssets] and carrying an image encoder and a text encoder rather than one file. A copy
 * side-loaded into `getExternalFilesDir(null)/semantic` is used first, so a build can be exercised
 * before the assets are published anywhere, then an already-verified copy in app-private storage. Only
 * when neither exists is a network fetch considered, and that fetch is gated twice: an asset has to be
 * pinned to exact bytes (see [SemanticModelAssets]) AND the user has to have agreed to the download.
 *
 * While an asset is not pinned that second path can never run: an unverifiable binary is never
 * fetched, because a download that cannot be size- and digest-checked is exactly what this rail exists
 * to refuse. So until the models are published and pinned, the only way onto a device is the side-load
 * path a developer uses now. The streaming download, the length cap, and the verify-then-promote-by-
 * rename are all in place regardless, so the moment the digests are pinned the network path is live
 * with no further change here.
 *
 * Work is per file: a copy already on disk is never re-fetched, so a run that only needs the encoder
 * it is missing pays for that one alone.
 */
class SemanticModelManager(context: Context) {

    private val appContext = context.applicationContext

    /** Serialises resolution so two requests in a row cannot start two downloads. */
    private val gate = Mutex()

    /**
     * The files that have already passed verification. Hashing hundreds of megabytes costs real time,
     * not worth paying on every request; each path is re-checked for existence before it is trusted.
     */
    private val verified = mutableMapOf<String, File>()

    /** App-private cache for the downloaded models. */
    private val modelsDir: File get() = File(appContext.filesDir, SemanticModelAssets.DIRECTORY)

    /**
     * Side-load root, matching the face and OCR rails' convention so one adb push serves the same way.
     * Exposed so a tester can drop the model files here before the assets are published.
     */
    val sideLoadDir: File?
        get() = appContext.getExternalFilesDir(null)?.let { File(it, SemanticModelAssets.DIRECTORY) }

    /**
     * The verified image-encoder file already on this device, or null when it would have to be
     * fetched. Touches no network, so it is what a caller asks before deciding whether consent is
     * needed.
     */
    suspend fun imageModelFile(): File? = withContext(Dispatchers.IO) {
        gate.withLock { resolveLocal(SemanticModelAssets.IMAGE_MODEL) }
    }

    /** The verified text-encoder file already on this device, or null when it would have to be fetched. */
    suspend fun textModelFile(): File? = withContext(Dispatchers.IO) {
        gate.withLock { resolveLocal(SemanticModelAssets.TEXT_MODEL) }
    }

    /**
     * Whether both model files are already present, checked by name and existence alone. It reads the
     * side-load root and the app-private cache and skips the byte-count and digest checks the resolving
     * accessors run, so it is cheap enough to answer a settings default off the main thread. A present
     * but stale file still counts here; the verified paths are what guard the bytes handed to the runtime.
     */
    fun areModelsPresent(): Boolean =
        listOf(SemanticModelAssets.IMAGE_MODEL, SemanticModelAssets.TEXT_MODEL).all { presentQuick(it) }

    /** A side-loaded copy first, then the app-private cache, by existence only. */
    private fun presentQuick(asset: SemanticModelAsset): Boolean {
        sideLoadDir?.let { if (File(it, asset.fileName).isFile) return true }
        return File(modelsDir, asset.fileName).isFile
    }

    /**
     * Gets both models in place. A local copy (side-loaded dev copy, then verified cache) is used with
     * no network touched; failing that, and only when the assets are pinned and the user has agreed, the
     * missing bytes are fetched, verified and promoted. [onDownloadStart] fires once, just before the
     * first byte moves, so a caller can label the wait as the download it is rather than a stalled read.
     * [onProgress] reports the running byte total across both files as it arrives, against
     * [SemanticModelAssets.TOTAL_DOWNLOAD_BYTES], so a caller can show a real bar instead of an
     * open-ended spinner.
     */
    suspend fun ensureDownloaded(
        onDownloadStart: () -> Unit = {},
        onProgress: (Long) -> Unit = {},
    ): SemanticModelPreparation =
        withContext(Dispatchers.IO) {
            gate.withLock {
                val assets = listOf(SemanticModelAssets.IMAGE_MODEL, SemanticModelAssets.TEXT_MODEL)

                resolveAll(assets)?.let { return@withLock SemanticModelPreparation.Ready(it) }

                // No full local set. A network fetch is only possible once the bytes can be verified.
                if (assets.any { !it.isPinned }) return@withLock SemanticModelPreparation.Failed(REASON_NOT_PINNED)
                if (!downloadAccepted()) return@withLock SemanticModelPreparation.NeedsConsent

                var announced = false
                var completedBytes = 0L
                val resolved = mutableMapOf<String, File>()
                for (asset in assets) {
                    val local = resolveLocal(asset)
                    if (local != null) {
                        resolved[asset.fileName] = local
                        completedBytes += asset.sizeBytes
                        continue
                    }
                    if (!announced) {
                        announced = true
                        onDownloadStart()
                    }
                    val base = completedBytes
                    when (val outcome = download(asset) { written -> onProgress(base + written) }) {
                        is SemanticModelOutcome.Ready -> {
                            resolved[asset.fileName] = outcome.file
                            completedBytes += asset.sizeBytes
                        }
                        SemanticModelOutcome.Corrupt ->
                            return@withLock SemanticModelPreparation.Failed(REASON_CORRUPT)
                        is SemanticModelOutcome.Unreachable ->
                            return@withLock SemanticModelPreparation.Failed(outcome.cause.message ?: REASON_UNREACHABLE)
                    }
                }
                SemanticModelPreparation.Ready(filesOf(resolved))
            }
        }

    /**
     * Removes both model roots from this device and forgets what was verified in memory.
     *
     * The app-private cache under [modelsDir] and the side-load root are the whole of the models'
     * on-disk footprint, so deleting both and clearing [verified] is a complete removal; the pinned
     * size-plus-digest rule means a later copy is verified from scratch regardless. Serialised on
     * [gate] so it cannot race a fetch promoting a file at the same moment.
     */
    suspend fun deleteAll() = withContext(Dispatchers.IO) {
        gate.withLock {
            listOfNotNull(modelsDir, sideLoadDir).forEach { dir ->
                if (dir.isDirectory) dir.deleteRecursively()
            }
            verified.clear()
        }
    }

    /** Both files when each is already usable, or null when one is not. Caller holds [gate]. */
    private fun resolveAll(assets: List<SemanticModelAsset>): SemanticModelFiles? {
        val resolved = mutableMapOf<String, File>()
        for (asset in assets) {
            resolved[asset.fileName] = resolveLocal(asset) ?: return null
        }
        return filesOf(resolved)
    }

    private fun filesOf(resolved: Map<String, File>): SemanticModelFiles = SemanticModelFiles(
        image = resolved.getValue(SemanticModelAssets.IMAGE_MODEL.fileName),
        text = resolved.getValue(SemanticModelAssets.TEXT_MODEL.fileName),
    )

    /** A side-loaded copy first, then the app-private cache. Caller holds [gate]. */
    private fun resolveLocal(asset: SemanticModelAsset): File? {
        verified[asset.fileName]?.let { if (it.isFile) return it }

        sideLoadDir?.let { dir ->
            val candidate = File(dir, asset.fileName)
            if (candidate.isFile) {
                when (val check = verify(candidate, asset)) {
                    SemanticModelCheck.Ok -> {
                        Log.i(TAG, "using the side-loaded ${asset.fileName}")
                        return remember(asset, candidate)
                    }
                    // Left alone: it is the developer's file, not this app's to delete.
                    else -> Log.w(TAG, "ignoring the side-loaded ${asset.fileName}, $check")
                }
            }
        }

        val cached = File(modelsDir, asset.fileName)
        if (!cached.isFile) return null
        return when (val check = verify(cached, asset)) {
            SemanticModelCheck.Ok -> {
                Log.i(TAG, "using the cached ${asset.fileName}")
                remember(asset, cached)
            }
            else -> {
                Log.w(TAG, "discarding the cached ${asset.fileName}, $check")
                cached.delete()
                null
            }
        }
    }

    private fun remember(asset: SemanticModelAsset, file: File): File =
        file.also { verified[asset.fileName] = it }

    /** Streams [asset] to a temporary file, verifies it, and promotes it. Caller holds [gate]. */
    private suspend fun download(asset: SemanticModelAsset, onProgress: (Long) -> Unit): SemanticModelOutcome {
        modelsDir.mkdirs()
        val target = File(modelsDir, asset.fileName)
        // Same directory as the target so the promotion is a rename within one filesystem.
        val partial = File(modelsDir, "${asset.fileName}.part")
        partial.delete()
        val url = SemanticModelAssets.downloadUrl(asset)
        return try {
            Log.i(TAG, "fetching ${asset.fileName} (${asset.sizeBytes} bytes)")
            streamTo(url, partial, asset.sizeBytes, onProgress)
            when (val check = verify(partial, asset)) {
                SemanticModelCheck.Ok -> {
                    target.delete()
                    if (!partial.renameTo(target)) throw IOException("could not store ${asset.fileName}")
                    Log.i(TAG, "downloaded and verified ${asset.fileName}")
                    remember(asset, target)
                    SemanticModelOutcome.Ready(target)
                }
                else -> {
                    Log.w(TAG, "rejecting the downloaded ${asset.fileName}, $check")
                    partial.delete()
                    SemanticModelOutcome.Corrupt
                }
            }
        } catch (t: Throwable) {
            partial.delete()
            currentCoroutineContext().ensureActive()
            Log.w(TAG, "could not fetch ${asset.fileName}: ${t.message}")
            SemanticModelOutcome.Unreachable(t)
        }
    }

    /**
     * Writes [url] into [into], stopping early on a response that claims more than [expectedBytes] so
     * a wrong or hostile URL cannot fill the disk. Cancellation is checked per chunk, which is what
     * lets a caller drop the fetch the moment the user backs out. [onProgress] is handed the running
     * byte total for this file after each chunk lands, so a caller can drive a determinate bar.
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

    private fun verify(file: File, asset: SemanticModelAsset): SemanticModelCheck = checkSemanticModel(
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
        appContext.settingsDataStore.data.first()[SettingsKeys.SEMANTIC_MODEL_DOWNLOAD_ALLOWED] == true

    private companion object {
        const val TAG = "SemanticModel"
        const val BUFFER_BYTES = 64 * 1024

        const val REASON_NOT_PINNED = "the semantic search models are not published yet"
        const val REASON_CORRUPT = "the downloaded semantic search model failed verification"
        const val REASON_UNREACHABLE = "the semantic search models could not be fetched"

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
