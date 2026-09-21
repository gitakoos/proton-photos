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
 * The detect, straighten, classify and read ordering below is derived from mobile_ocr, which is MIT
 * licensed:
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
import android.graphics.Bitmap
import androidx.datastore.preferences.core.edit
import eu.akoos.photos.data.preferences.SettingsKeys
import eu.akoos.photos.data.preferences.settingsDataStore
import eu.akoos.photos.domain.ocr.RecognizedTextBlock
import eu.akoos.photos.domain.ocr.TextReadStage
import eu.akoos.photos.domain.ocr.TextRecognizer
import eu.akoos.photos.domain.ocr.cleanTextBlocks
import eu.akoos.photos.domain.ocr.looksReadable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Whether the text reader can run right now, and what stands in the way when it cannot. */
sealed interface OcrModelPreparation {

    /** The models are on this device and a read can start. */
    data object Ready : OcrModelPreparation

    /** Nothing is on disk yet and the user has not agreed to the download. */
    data object NeedsConsent : OcrModelPreparation

    /** The download was attempted and did not produce usable models. */
    data object Failed : OcrModelPreparation
}

/**
 * Reads the text in a photo with the on-device models.
 *
 * Three stages in order. Detection says where the words are, each region is cut out and straightened
 * into a level strip, the angle classifier says whether that strip is upside down, and the reader
 * turns it into characters. A block's confidence is the reader's, not the detector's: the detector
 * only ever answered how sure it was that something was written there, which says nothing about
 * whether the characters that came back are right.
 *
 * Regions whose reading does not survive [looksReadable] are dropped rather than returned, because an
 * outline the user can tap and get nothing from is worse than no outline. What is left goes through
 * [cleanTextBlocks], which lays flat the runs that only look tilted and keeps one outline per run.
 *
 * Session lifetime is tied to the instance, not to a call: opening the three sessions costs far more
 * than a run, so they are opened on the first read and released by [close], which the surface that
 * owns this instance calls when it leaves the screen. [close] terminates a run that is already inside
 * the runtime before it releases anything, so a native session is never freed underneath one.
 */
class OnnxTextRecognizer(context: Context) : TextRecognizer {

    private val appContext = context.applicationContext
    private val models = OcrModelManager(appContext)

    /** One run at a time, and no release while a run holds a session. */
    private val sessionGate = Mutex()
    private var detector: OnnxTextDetector? = null
    private var detectorPath: String? = null
    private var classifier: OnnxAngleClassifier? = null
    private var reader: OnnxTextReader? = null
    private var readerPaths: String? = null

    @Volatile
    private var closed = false

    /**
     * Set the moment the user accepts the download, before the choice reaches storage, so the read
     * that the acceptance triggers does not race the write and ask again.
     */
    @Volatile
    private var downloadAccepted = false

    @Volatile
    private var activeRun: OnnxCancellation? = null

    /** Carries the two jobs that must outlive the caller: persisting consent, and releasing sessions. */
    private val background = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Gets the models in place, downloading them only once the user has agreed. Suspends for as long
     * as the download takes, so a caller shows progress around it; [onDownloadStart] fires just
     * before the first byte moves so that wait can be labelled as the download it is.
     */
    suspend fun prepare(onDownloadStart: () -> Unit = {}): OcrModelPreparation {
        if (closed) return OcrModelPreparation.Failed
        if (COMPONENTS.all { models.onDisk(it) != null }) return OcrModelPreparation.Ready
        if (!downloadAccepted()) return OcrModelPreparation.NeedsConsent
        for (component in COMPONENTS) {
            if (models.ensure(component, onDownloadStart) !is OcrModelOutcome.Ready) {
                return OcrModelPreparation.Failed
            }
        }
        return OcrModelPreparation.Ready
    }

    /** Records that the user agreed to fetch the models. Takes effect immediately and is remembered. */
    fun allowModelDownload() {
        downloadAccepted = true
        background.launch {
            appContext.settingsDataStore.edit { it[SettingsKeys.OCR_MODEL_DOWNLOAD_ALLOWED] = true }
        }
    }

    /** How many bytes accepting the prompt fetches. */
    val downloadBytes: Long get() = OcrModelAssets.TOTAL_DOWNLOAD_BYTES

    override suspend fun recognize(bitmap: Bitmap): List<RecognizedTextBlock> = recognize(bitmap) {}

    /** As [recognize], reporting which stage the read has reached so a caller can say so on screen. */
    suspend fun recognize(
        bitmap: Bitmap,
        onStage: (TextReadStage) -> Unit,
    ): List<RecognizedTextBlock> = withContext(Dispatchers.IO) {
        // Reached only after prepare() reported Ready. Resolving again rather than trusting that is
        // what keeps a model deleted between the two from being handed to the runtime.
        val detection = models.onDisk(OcrModelComponent.Detection) ?: return@withContext emptyList()
        val recognition = models.onDisk(OcrModelComponent.Recognition) ?: return@withContext emptyList()

        val run = OnnxCancellation()
        activeRun = run
        // A model run is one blocking native call, so cancelling this coroutine cannot interrupt it
        // on its own. Terminating the run from the completion handler is what makes a page swipe
        // drop work that has already started.
        val cancelHandle = currentCoroutineContext().job.invokeOnCompletion { run.cancel() }
        try {
            sessionGate.withLock {
                if (closed) return@withLock emptyList()
                onStage(TextReadStage.Detecting)
                val found = openDetector(detection).detect(bitmap, run)
                // The reading half is opened only once there is something to read: a photo with no
                // text on it should not pay for a seventeen megabyte model it will not run.
                if (found.isEmpty()) return@withLock emptyList()
                onStage(TextReadStage.Reading)
                openReader(recognition)
                readBlocks(bitmap, capped(found), run)
            }
        } finally {
            cancelHandle.dispose()
            if (activeRun === run) activeRun = null
        }
    }

    /**
     * Reads every region in [found], a group at a time.
     *
     * Cropping the whole photo's worth of regions up front is what the shape of the pipeline invites
     * and what this deliberately does not do: a dense page is hundreds of strips, and this runs while
     * the viewer is still holding the full-resolution photo they were cut from. A group is cut, read
     * and let go before the next one is cut, so the crops alive at any moment are one group's worth.
     */
    private fun readBlocks(
        frame: Bitmap,
        found: List<DetectedQuad>,
        cancellation: OnnxCancellation,
    ): List<RecognizedTextBlock> {
        val blocks = mutableListOf<RecognizedTextBlock>()
        var start = 0
        while (start < found.size) {
            // Between groups as well as inside a run: a swipe should not wait out the rest of a page.
            cancellation.ensureActive()
            val end = minOf(start + READ_MAX_BATCH, found.size)
            val group = found.subList(start, end)
            val crops = mutableListOf<Bitmap>()
            val quads = mutableListOf<DetectedQuad>()
            try {
                for (region in group) {
                    val crop = cropTextQuad(frame, region.quad, cropSizeFor(region.quad)) ?: continue
                    crops += crop
                    quads += region
                }
                if (crops.isEmpty()) {
                    start = end
                    continue
                }
                val upsideDown = requireNotNull(classifier).classify(crops, cancellation)
                val texts = requireNotNull(reader).read(crops, upsideDown, cancellation)
                texts.forEachIndexed { index, decoded ->
                    if (!looksReadable(decoded.text, decoded.confidence)) return@forEachIndexed
                    blocks += RecognizedTextBlock(
                        text = decoded.text,
                        confidence = decoded.confidence,
                        quad = quads[index].quad,
                    )
                }
            } finally {
                crops.forEach { if (!it.isRecycled) it.recycle() }
            }
            start = end
        }
        return cleanTextBlocks(blocks)
    }

    /**
     * At most [MAX_READ_REGIONS] regions, the surest ones, back in the reading order the detector
     * put them in. A photo of a printed page yields tens of lines; a photo of a wall of small print
     * can yield hundreds, and each one costs a crop and a share of a model run. The strongest
     * detections survive because the weakest are the ones that read as nothing anyway.
     *
     * Whatever is over the cap is dropped without a word, which is why the cap has to sit above what
     * a real page holds rather than at a round number: a user who photographs a page and gets three
     * quarters of it back has no way of telling that from a page the reader simply misread.
     */
    private fun capped(found: List<DetectedQuad>): List<DetectedQuad> {
        if (found.size <= MAX_READ_REGIONS) return found
        val kept = found.indices.sortedByDescending { found[it].score }.take(MAX_READ_REGIONS).toSet()
        return found.filterIndexed { index, _ -> index in kept }
    }

    /** Caller holds [sessionGate]. */
    private fun openDetector(files: OcrModelFiles): OnnxTextDetector {
        val path = files.path(OcrModelAssets.DETECTION)
        detector?.let { if (detectorPath == path) return it }
        detector?.close()
        return OnnxTextDetector(path).also {
            detector = it
            detectorPath = path
        }
    }

    /** Caller holds [sessionGate]. */
    private fun openReader(files: OcrModelFiles) {
        val classifierPath = files.path(OcrModelAssets.CLASSIFICATION)
        val readerPath = files.path(OcrModelAssets.RECOGNITION)
        val paths = "$classifierPath|$readerPath"
        if (readerPaths == paths && classifier != null && reader != null) return

        classifier?.close()
        reader?.close()
        classifier = OnnxAngleClassifier(classifierPath)
        reader = OnnxTextReader(readerPath, files.file(OcrModelAssets.DICTIONARY))
        readerPaths = paths
    }

    /** Caller holds [sessionGate]. */
    private fun releaseSessions() {
        detector?.close()
        classifier?.close()
        reader?.close()
        detector = null
        classifier = null
        reader = null
        detectorPath = null
        readerPaths = null
    }

    /**
     * Releases the runtime sessions. Any run still inside the runtime is terminated first, and the
     * release itself waits for that run to unwind, because closing a session under one is a native
     * crash rather than an exception.
     */
    fun close() {
        if (closed) return
        closed = true
        activeRun?.cancel()
        background.launch { sessionGate.withLock { releaseSessions() } }
    }

    private suspend fun downloadAccepted(): Boolean = downloadAccepted ||
        appContext.settingsDataStore.data.first()[SettingsKeys.OCR_MODEL_DOWNLOAD_ALLOWED] == true

    private companion object {
        /** Detection first, so the smaller half is on disk before the larger one is fetched. */
        val COMPONENTS = listOf(OcrModelComponent.Detection, OcrModelComponent.Recognition)

        /**
         * Ceiling on how many detected regions one photo is read from.
         *
         * A count and not a time budget on purpose: a budget would hand back a different amount of
         * the same photograph on a warm phone than on a cold one, and a page that reads differently
         * twice is worse than one that takes a moment longer. A dense A4 page runs to about fifty
         * lines and a newspaper page to a couple of hundred, so this sits above both, and it has to:
         * the detector now works at whatever resolution the frame and the device can carry, which
         * finds the small print that used to be missed and so hands more regions on than it did.
         *
         * The cost is linear and it is time, not memory, since the crops are cut and let go a group
         * at a time: a run of the reader carries six of them whatever the cap says.
         */
        const val MAX_READ_REGIONS = 256
    }
}
