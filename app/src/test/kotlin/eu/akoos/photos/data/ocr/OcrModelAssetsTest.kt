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

package eu.akoos.photos.data.ocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins what the text reader is allowed to load and where it comes from. Both halves matter: the
 * admission rule is the only thing standing between a half-finished download and the native runtime,
 * and the address is the one detail that would silently point a shipped build at somebody else's
 * server.
 */
class OcrModelAssetsTest {

    private val asset = OcrModelAssets.DETECTION

    @Test
    fun `the expected bytes are the ones this build was written against`() {
        assertEquals("det.onnx", asset.fileName)
        assertEquals(4_748_769L, asset.sizeBytes)
        assertEquals("d7fe3ea74652890722c0f4d02458b7261d9f5ae6c92904d05707c9eb155c7924", asset.sha256)
    }

    @Test
    fun `the reading models are the ones this build was written against`() {
        assertEquals("rec_latin.onnx", OcrModelAssets.RECOGNITION.fileName)
        assertEquals(8_064_539L, OcrModelAssets.RECOGNITION.sizeBytes)
        assertEquals(
            "995b0f5f28d2073896a78c03b5b863eae6af3744bafa0245b8522beea6994927",
            OcrModelAssets.RECOGNITION.sha256,
        )
        assertEquals("cls.onnx", OcrModelAssets.CLASSIFICATION.fileName)
        assertEquals(582_663L, OcrModelAssets.CLASSIFICATION.sizeBytes)
        assertEquals(
            "f4bb53707100c5f3d59ba834eb05bb400369f20aed35d4b26807b1bfadd2a70e",
            OcrModelAssets.CLASSIFICATION.sha256,
        )
    }

    @Test
    fun `the alphabet is pinned as tightly as the models are`() {
        // A mismatched dictionary does not fail: every photo simply comes back as the wrong
        // characters, which is the one failure nobody would think to look for.
        assertEquals("ppocrv5_latin_dict.txt", OcrModelAssets.DICTIONARY.fileName)
        assertEquals(2_616L, OcrModelAssets.DICTIONARY.sizeBytes)
        assertEquals(
            "ccbcc45730b3fbbd9050c5bc74db6a99067141ef1035e3d14889a84a6b9b1aff",
            OcrModelAssets.DICTIONARY.sha256,
        )
    }

    @Test
    fun `finding the words and reading them are separate downloads`() {
        // The split is the whole point: a caller that only wants outlines pays 4.5 MB, not 13.
        assertEquals(listOf(asset), OcrModelAssets.assetsOf(OcrModelComponent.Detection))
        assertEquals(
            setOf(OcrModelAssets.CLASSIFICATION, OcrModelAssets.DICTIONARY, OcrModelAssets.RECOGNITION),
            OcrModelAssets.assetsOf(OcrModelComponent.Recognition).toSet(),
        )
        assertEquals(asset.sizeBytes, OcrModelAssets.downloadBytesOf(OcrModelComponent.Detection))
        assertTrue(
            OcrModelAssets.downloadBytesOf(OcrModelComponent.Recognition) >
                OcrModelAssets.downloadBytesOf(OcrModelComponent.Detection),
        )
    }

    @Test
    fun `the prompt quotes every byte that will be fetched`() {
        assertEquals(OcrModelAssets.REQUIRED.size, OcrModelAssets.REQUIRED.distinct().size)
        assertEquals(13_398_587L, OcrModelAssets.TOTAL_DOWNLOAD_BYTES)
        assertEquals(
            OcrModelComponent.entries.sumOf { OcrModelAssets.downloadBytesOf(it) },
            OcrModelAssets.TOTAL_DOWNLOAD_BYTES,
        )
    }

    @Test
    fun `every file is fetched from this project's own releases`() {
        for (each in OcrModelAssets.REQUIRED) {
            val url = OcrModelAssets.downloadUrl(each)
            assertTrue(url, url.startsWith("https://github.com/gitakoos/ocr-models/releases/download/"))
            assertTrue(url, url.endsWith("/${each.fileName}"))
            assertFalse(url, url.contains("ente"))
        }
    }

    @Test
    fun `nothing on disk is nothing to load`() {
        assertEquals(
            OcrModelCheck.Missing,
            checkOcrModel(asset, present = false, actualSize = 0L, actualSha256 = { null }),
        )
    }

    @Test
    fun `a file of the wrong length is rejected without hashing it`() {
        var hashed = false
        val check = checkOcrModel(
            asset,
            present = true,
            actualSize = asset.sizeBytes - 1L,
            actualSha256 = { hashed = true; asset.sha256 },
        )
        assertEquals(OcrModelCheck.WrongSize, check)
        // A truncated download is settled by its length, and hashing several megabytes to reach the
        // same answer is time the gesture spends doing nothing.
        assertFalse(hashed)
    }

    @Test
    fun `the right length with the wrong content is rejected`() {
        assertEquals(
            OcrModelCheck.WrongHash,
            checkOcrModel(asset, present = true, actualSize = asset.sizeBytes, actualSha256 = { "00" }),
        )
    }

    @Test
    fun `a file that cannot be hashed is rejected`() {
        assertEquals(
            OcrModelCheck.WrongHash,
            checkOcrModel(asset, present = true, actualSize = asset.sizeBytes, actualSha256 = { null }),
        )
    }

    @Test
    fun `the expected bytes are admitted whichever case the digest is written in`() {
        assertEquals(
            OcrModelCheck.Ok,
            checkOcrModel(asset, present = true, actualSize = asset.sizeBytes, actualSha256 = { asset.sha256 }),
        )
        assertEquals(
            OcrModelCheck.Ok,
            checkOcrModel(
                asset,
                present = true,
                actualSize = asset.sizeBytes,
                actualSha256 = { asset.sha256.uppercase() },
            ),
        )
    }
}
