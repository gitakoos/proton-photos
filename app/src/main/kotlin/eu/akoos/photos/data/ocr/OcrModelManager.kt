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

package eu.akoos.photos.data.ocr

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
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

/** The verified files one component is made of, keyed by the asset that pinned them. */
class OcrModelFiles(private val files: Map<String, File>) {

    fun file(asset: OcrModelAsset): File = files.getValue(asset.fileName)

    fun path(asset: OcrModelAsset): String = file(asset).absolutePath
}

/** How a request for a component ended. */
sealed interface OcrModelOutcome {

    data class Ready(val files: OcrModelFiles) : OcrModelOutcome

    /** Bytes arrived but failed the size or digest check; the partial file was removed. */
    data object Corrupt : OcrModelOutcome

    /** Nothing arrived: no network, a bad response, or the request was dropped mid-flight. */
    data class Unreachable(val cause: Throwable) : OcrModelOutcome
}

/**
 * Resolves the text reader's model files to paths the ONNX runtime can open.
 *
 * Three sources, in order. A copy side-loaded into `getExternalFilesDir(null)/ocr` wins, so a build
 * can be exercised before the asset is published anywhere. Otherwise an already-cached copy in
 * app-private storage is used, and only when neither exists is anything fetched. Every source is
 * held to the same admission rule: the exact byte count and the exact SHA-256 in [OcrModelAssets],
 * checked on load and again on download, so a truncated fetch or a swapped file is never handed to
 * the runtime.
 *
 * Work is per [OcrModelComponent], never per file: asking for detection fetches 4.5 MB and asking
 * for recognition fetches the rest, so a caller that only wants outlines is never charged for the
 * alphabet it will not read.
 *
 * Downloads land on a temporary name and are promoted by a rename only after they verify, so an
 * interrupted fetch cannot leave something that looks loadable behind.
 */
class OcrModelManager(context: Context) {

    private val appContext = context.applicationContext

    /** Serialises resolution so two long-presses in a row cannot start two downloads. */
    private val gate = Mutex()

    /**
     * The files that have already passed verification. Hashing 21 MB costs hundreds of milliseconds,
     * which is not worth paying on every gesture; each path is re-checked for existence before it is
     * trusted.
     */
    private val verified = mutableMapOf<String, File>()

    /** App-private cache for downloaded models. */
    private val modelsDir: File get() = File(appContext.filesDir, OcrModelAssets.DIRECTORY)

    /** Side-load root, matching the debug spike's convention so one adb push serves both. */
    private val sideLoadDir: File?
        get() = appContext.getExternalFilesDir(null)?.let { File(it, OcrModelAssets.DIRECTORY) }

    /**
     * The verified files of [component] already on this device, or null when any of them has to be
     * fetched. Touches no network, so it is what a caller asks before deciding whether consent is
     * needed.
     */
    suspend fun onDisk(component: OcrModelComponent): OcrModelFiles? = withContext(Dispatchers.IO) {
        gate.withLock { resolveLocal(component) }
    }

    /**
     * Every file of [component], fetching what is neither side-loaded nor usably cached.
     * [onDownloadStart] fires once, just before the first byte moves, so a caller can say so on
     * screen rather than leaving a multi-megabyte wait looking like a stalled read.
     */
    suspend fun ensure(
        component: OcrModelComponent,
        onDownloadStart: () -> Unit = {},
    ): OcrModelOutcome = withContext(Dispatchers.IO) {
        gate.withLock {
            resolveLocal(component)?.let { return@withLock OcrModelOutcome.Ready(it) }

            var announced = false
            val resolved = mutableMapOf<String, File>()
            for (asset in OcrModelAssets.assetsOf(component)) {
                val local = resolveLocal(asset)
                if (local != null) {
                    resolved[asset.fileName] = local
                    continue
                }
                if (!announced) {
                    announced = true
                    onDownloadStart()
                }
                when (val outcome = download(asset)) {
                    is OcrModelOutcome.Ready -> resolved[asset.fileName] = outcome.files.file(asset)
                    else -> return@withLock outcome
                }
            }
            OcrModelOutcome.Ready(OcrModelFiles(resolved))
        }
    }

    /** Every file of [component] that is already usable, or null when one is not. Caller holds [gate]. */
    private fun resolveLocal(component: OcrModelComponent): OcrModelFiles? {
        val resolved = mutableMapOf<String, File>()
        for (asset in OcrModelAssets.assetsOf(component)) {
            resolved[asset.fileName] = resolveLocal(asset) ?: return null
        }
        return OcrModelFiles(resolved)
    }

    /** A side-loaded copy first, then the app-private cache. Caller holds [gate]. */
    private fun resolveLocal(asset: OcrModelAsset): File? {
        verified[asset.fileName]?.let { if (it.isFile) return it }

        sideLoadDir?.let { dir ->
            val candidate = File(dir, asset.fileName)
            if (candidate.isFile) {
                when (val check = verify(candidate, asset)) {
                    OcrModelCheck.Ok -> {
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
            OcrModelCheck.Ok -> {
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

    private fun remember(asset: OcrModelAsset, file: File): File =
        file.also { verified[asset.fileName] = it }

    /** Streams [asset] to a temporary file, verifies it, and promotes it. Caller holds [gate]. */
    private suspend fun download(asset: OcrModelAsset): OcrModelOutcome {
        modelsDir.mkdirs()
        val target = File(modelsDir, asset.fileName)
        // Same directory as the target so the promotion is a rename within one filesystem.
        val partial = File(modelsDir, "${asset.fileName}.part")
        partial.delete()
        val url = OcrModelAssets.downloadUrl(asset)
        return try {
            Log.i(TAG, "fetching ${asset.fileName} (${asset.sizeBytes} bytes)")
            streamTo(url, partial, asset.sizeBytes)
            when (val check = verify(partial, asset)) {
                OcrModelCheck.Ok -> {
                    target.delete()
                    if (!partial.renameTo(target)) throw IOException("could not store ${asset.fileName}")
                    Log.i(TAG, "downloaded and verified ${asset.fileName}")
                    remember(asset, target)
                    OcrModelOutcome.Ready(OcrModelFiles(mapOf(asset.fileName to target)))
                }
                else -> {
                    Log.w(TAG, "rejecting the downloaded ${asset.fileName}, $check")
                    partial.delete()
                    OcrModelOutcome.Corrupt
                }
            }
        } catch (t: Throwable) {
            partial.delete()
            currentCoroutineContext().ensureActive()
            Log.w(TAG, "could not fetch ${asset.fileName}: ${t.message}")
            OcrModelOutcome.Unreachable(t)
        }
    }

    /**
     * Writes [url] into [into], stopping early on a response that claims more than [expectedBytes]
     * so a wrong or hostile URL cannot fill the disk. Cancellation is checked per chunk, which is
     * what lets the viewer drop the fetch the moment the user swipes away.
     */
    private suspend fun streamTo(url: String, into: File, expectedBytes: Long) {
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
                    }
                    output.flush()
                }
            }
        }
    }

    private fun verify(file: File, asset: OcrModelAsset): OcrModelCheck = checkOcrModel(
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

    private companion object {
        const val TAG = "OcrModel"
        const val BUFFER_BYTES = 64 * 1024

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
