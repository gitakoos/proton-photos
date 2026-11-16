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

package eu.akoos.photos.presentation.common

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every screen that downloads goes through [GalleryDownloads], so no download can run on a screen's
 * own scope and die when the screen closes. Read from the sources: the screens are too wide to build
 * in a unit test, and a new screen that calls the use case directly is exactly what this must catch.
 */
class DownloadEntryPointsTest {

    private val presentation = File("src/main/kotlin/eu/akoos/photos/presentation")
    private val runner = File(presentation, "common/GalleryDownloads.kt")

    private fun sourcesCalling(pattern: Regex): List<String> =
        presentation.walkTopDown()
            .filter { it.isFile && it.extension == "kt" && it != runner }
            .filter { file -> file.readLines().any { line -> !line.trimStart().startsWith("//") && pattern.containsMatchIn(line) } }
            .map { it.relativeTo(presentation).invariantSeparatorsPath }
            .toList()

    private fun source(path: String) = File(presentation, path).readText()

    @Test
    fun `no screen saves photos to the device on its own`() {
        assertTrue(runner.isFile)
        assertEquals(emptyList<String>(), sourcesCalling(Regex("""\.download(GalleryItems|CloudPhotos)\(""")))
    }

    @Test
    fun `the runner's saves run as background work that outlives the process`() {
        val text = runner.readText()
        assertTrue("a save must be queued as background work", "PhotoSaveWorker.enqueue(context" in text)
        val worker = File("src/main/kotlin/eu/akoos/photos/worker/PhotoSaveWorker.kt").readText()
        assertTrue("the background work must run the kept batch", "downloads.runSaveBatch(" in worker)
    }

    @Test
    fun `no screen queues an album download on its own`() {
        assertEquals(emptyList<String>(), sourcesCalling(Regex("""AlbumDownloadWorker\.enqueue\(""")))
    }

    @Test
    fun `no screen writes an offline pin on its own`() {
        // Adding to the pin set is the start of a pin download; taking pins away needs no network.
        assertEquals(emptyList<String>(), sourcesCalling(Regex("""OFFLINE_PIN_IDS\] = current \+""")))
    }

    @Test
    fun `every download entry point hands its work to the shared runner`() {
        val expected = mapOf(
            // Search and places share the selection controller.
            "common/GalleryItemSelectionController.kt" to listOf("downloads.saveToDevice(", "downloads.pinOffline("),
            "gallery/GalleryViewModel.kt" to listOf("galleryDownloads.saveToDevice(", "galleryDownloads.pinOffline("),
            // The viewer is the per-photo entry of every screen: map, shared, hidden, device folders.
            "viewer/PhotoViewerViewModel.kt" to listOf("galleryDownloads.saveToDevice(", "galleryDownloads.pinOffline("),
            "albums/AlbumDetailViewModel.kt" to listOf("galleryDownloads.enqueueAlbumDownload(", "galleryDownloads.pinOffline("),
        )
        for ((path, calls) in expected) {
            val text = source(path)
            for (call in calls) assertTrue("$path must call $call", call in text)
        }
        for (path in listOf("search/SearchViewModel.kt", "location/LocationDetailViewModel.kt")) {
            val text = source(path)
            assertTrue("$path must download through its selection controller", "= sel.downloadSelected(" in text)
            assertTrue("$path must build its controller from the shared factory", "selectionFactory.create(" in text)
        }
    }
}
