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

package eu.akoos.photos.data.updater

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.akoos.photos.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Streams an APK from a GitHub release asset URL onto disk, emitting progress percentages
 * as chunks land. Lives in `cacheDir/updates/<asset>` so the OS reclaims the bytes if the
 * device runs short on storage and the user never finishes the install.
 *
 * Why a dedicated OkHttpClient (instead of reusing the GitHub `@Named("GitHub")` one):
 * the asset URL on github.com 302-redirects to objects.githubusercontent.com (the S3-style
 * CDN) which neither expects nor allows the GitHub-API JSON Accept header. Re-using the
 * API client would have its interceptor stamp the wrong headers on the redirected request,
 * tripping signed-URL validation and producing an opaque 400. Keep this transport simple
 * and CDN-friendly.
 */
@Singleton
class UpdateDownloader @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    /**
     * Single OkHttpClient reused across downloads — connection pool warm-up costs are real
     * on first run, and the client is harmless to keep alive. Timeouts are tuned for the
     * mobile use case: a 30s connect catches dead DNS / blocked CDNs, a 60s read keeps a
     * slow LTE link alive long enough for the next chunk to arrive (without locking up
     * forever on a stuck socket).
     */
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30L, TimeUnit.SECONDS)
        .readTimeout(60L, TimeUnit.SECONDS)
        .build()

    /**
     * Streams [apkUrl] into `cacheDir/updates/[apkAssetName]`, emitting [DownloadProgress.Downloading]
     * after each 64 KB chunk and [DownloadProgress.Complete] on success. Any thrown exception
     * (network, IO, cancellation) is forwarded as [DownloadProgress.Failed] instead of
     * propagating — the caller treats the flow itself as the error channel so a single
     * collector handles both happy + sad paths uniformly.
     *
     * The flow runs on [Dispatchers.IO] so a Compose-scoped collector doesn't need to
     * worry about thread-marshalling the response body read.
     */
    fun download(
        apkUrl: String,
        apkAssetName: String,
    ): Flow<DownloadProgress> = flow {
        val updatesDir = File(context.cacheDir, "updates").apply { mkdirs() }
        val partFile = File(updatesDir, "$apkAssetName.part")
        try {
            // Clear stale/partial leftovers first so an earlier truncated download can never be
            // mistaken for a complete, installable APK.
            updatesDir.listFiles()?.forEach { runCatching { it.delete() } }
            val outFile = File(updatesDir, apkAssetName)

            val request = Request.Builder()
                .url(apkUrl)
                // The asset CDN doesn't require a UA, but tagging the traffic eases debugging a bad redirect.
                .header("User-Agent", "PhotosForProton/${BuildConfig.VERSION_NAME}")
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IOException("HTTP ${response.code}")
                }
                val body = response.body ?: throw IOException("Empty response body")
                val totalBytes = body.contentLength().takeIf { it > 0L } ?: -1L
                if (totalBytes > MAX_APK_BYTES) {
                    throw IOException("Update unexpectedly large ($totalBytes bytes)")
                }

                body.byteStream().use { input ->
                    FileOutputStream(partFile).use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var bytesRead: Int
                        var totalRead = 0L
                        var lastEmittedPercent = -1
                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            output.write(buffer, 0, bytesRead)
                            totalRead += bytesRead
                            // Bound the write even with no Content-Length so a misbehaving CDN can't fill the disk.
                            if (totalRead > MAX_APK_BYTES) {
                                throw IOException("Update exceeded the size limit")
                            }
                            if (totalBytes > 0L) {
                                // Only forward distinct integer-percent ticks (a 50 MB APK would emit ~800 times).
                                val percent = ((totalRead * 100L) / totalBytes).toInt().coerceIn(0, 100)
                                if (percent != lastEmittedPercent) {
                                    emit(DownloadProgress.Downloading(percent))
                                    lastEmittedPercent = percent
                                }
                            }
                        }
                        output.flush()
                    }
                }
                // Reject a truncated download: when the length was known, the bytes on disk must match.
                if (totalBytes > 0L && partFile.length() != totalBytes) {
                    throw IOException("Truncated download: ${partFile.length()} of $totalBytes")
                }
                // Promote .part to the real name only after a complete, size-checked write, so an
                // interrupted download never leaves an installable-looking file behind.
                if (outFile.exists()) outFile.delete()
                if (!partFile.renameTo(outFile)) {
                    throw IOException("Could not finalize the downloaded update")
                }
                emit(DownloadProgress.Complete(outFile))
            }
        } catch (t: Throwable) {
            runCatching { partFile.delete() }
            emit(DownloadProgress.Failed(t))
        }
    }.flowOn(Dispatchers.IO)

    private companion object {
        /** Generous ceiling (release APKs are ~24-39 MB); guards against a runaway/hostile response. */
        const val MAX_APK_BYTES = 300L * 1024L * 1024L
    }
}

sealed class DownloadProgress {
    data class Downloading(val percent: Int) : DownloadProgress()
    data class Complete(val file: File) : DownloadProgress()
    data class Failed(val cause: Throwable) : DownloadProgress()
}
