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

package eu.akoos.photos.data.mlsync

import eu.akoos.photos.data.semantic.SEMANTIC_MODEL_VERSION
import eu.akoos.photos.data.semantic.SemanticModelAssets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The search snapshot's pure rules: which local rows an import writes over in each mode, which snapshots
 * are close enough to import at all, and which photo keys may travel between devices. Plain JVM.
 */
class SearchSnapshotRulesTest {

    private fun header(modelId: String = SEARCH_MODEL_ID, embeddingDim: Int = SEARCH_EMBEDDING_DIM) =
        MlSnapshotHeader.Search(
            deviceId = "device-a",
            deviceLabel = "Test phone",
            createdAtMillis = 1_758_600_000_000L,
            searchModelId = modelId,
            embeddingDim = embeddingDim,
            keyCount = 0,
            embeddingCount = 0,
        )

    @Test
    fun `merge adds only photos this device has not embedded`() {
        val mode = SearchImportMode.Merge
        assertTrue(shouldImportSearchEmbedding(mode, embeddedHere = false, tombstone = false))
        assertTrue("a tombstone still fills a photo with no row", shouldImportSearchEmbedding(mode, false, true))
        assertFalse(shouldImportSearchEmbedding(mode, embeddedHere = true, tombstone = false))
        assertFalse(shouldImportSearchEmbedding(mode, embeddedHere = true, tombstone = true))
    }

    @Test
    fun `replace overwrites this device's rows but never with a tombstone`() {
        val mode = SearchImportMode.Replace
        assertTrue(shouldImportSearchEmbedding(mode, embeddedHere = false, tombstone = false))
        assertTrue(shouldImportSearchEmbedding(mode, embeddedHere = false, tombstone = true))
        assertTrue("a vector replaces the local row", shouldImportSearchEmbedding(mode, true, false))
        assertFalse("a tombstone keeps the local row", shouldImportSearchEmbedding(mode, true, true))
    }

    @Test
    fun `only a snapshot of this model id and width is imported`() {
        assertTrue(isCompatibleSearchSnapshot(header()))
        assertFalse("another model", isCompatibleSearchSnapshot(header(modelId = "clip-vit-b16-datacomp:1:ab")))
        assertFalse("another width", isCompatibleSearchSnapshot(header(embeddingDim = SEARCH_EMBEDDING_DIM * 2)))
        assertTrue(isCompatibleSearchSnapshot(header(modelId = "other"), modelId = "other"))
    }

    @Test
    fun `the model id follows the model generation and the encoder bytes`() {
        assertTrue(SEARCH_MODEL_ID.contains(":$SEMANTIC_MODEL_VERSION:"))
        assertTrue(SEARCH_MODEL_ID.endsWith(SemanticModelAssets.IMAGE_MODEL.sha256))
        assertEquals(SEARCH_EMBEDDING_DIM * 4, SEARCH_EMBEDDING_BYTES)
    }

    @Test
    fun `only drive link ids travel between devices`() {
        assertTrue(isPortableSearchKey("Wz6kFGdwAQ0ey5lEcf5nNpVcqaBb9QZMMM4WIfrfXqjuK0fs3GqRiK5VHXZIBbcfSrnUjvdmRZl4Fw43ytsZoQ=="))
        assertFalse(isPortableSearchKey("content://media/external/images/media/1000012591"))
        assertFalse(isPortableSearchKey("file:///data/user/0/eu.akoos.photos/files/vault/a.jpg"))
        assertFalse(isPortableSearchKey(""))
    }
}
