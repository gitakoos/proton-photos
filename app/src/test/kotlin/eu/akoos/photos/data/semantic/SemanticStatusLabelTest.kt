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

package eu.akoos.photos.data.semantic

import org.junit.Assert.assertEquals
import org.junit.Test

class SemanticStatusLabelTest {

    private val bulk = SEMANTIC_INDEX_FOREGROUND_THRESHOLD
    private val small = SEMANTIC_INDEX_FOREGROUND_THRESHOLD - 1

    @Test
    fun `a genuine bulk index reports progress`() {
        assertEquals(SemanticStatusLabel.Indexing, semanticStatusLabel(SemanticIndexingState.Running, 0, bulk))
        assertEquals(SemanticStatusLabel.Indexing, semanticStatusLabel(SemanticIndexingState.Running, 500, bulk + 5000))
    }

    @Test
    fun `a small residual retrying in the background stays silent and ready`() {
        // The tester's case: the walk is running a pass for the last few un-downloadable photos, but the
        // backlog is tiny and the library is already searchable, so it must not show a stuck progress bar.
        assertEquals(SemanticStatusLabel.Ready, semanticStatusLabel(SemanticIndexingState.Running, 16556, 11))
        assertEquals(SemanticStatusLabel.Ready, semanticStatusLabel(SemanticIndexingState.Running, 500, small))
    }

    @Test
    fun `a small first trickle with nothing yet embedded is not indexed`() {
        assertEquals(SemanticStatusLabel.NotIndexed, semanticStatusLabel(SemanticIndexingState.Running, 0, small))
    }

    @Test
    fun `paused reports paused only during a bulk index`() {
        assertEquals(SemanticStatusLabel.Paused, semanticStatusLabel(SemanticIndexingState.Paused, 500, bulk))
        assertEquals(SemanticStatusLabel.Ready, semanticStatusLabel(SemanticIndexingState.Paused, 500, small))
    }

    @Test
    fun `waiting for the model reports waiting regardless of backlog`() {
        assertEquals(SemanticStatusLabel.WaitingModel, semanticStatusLabel(SemanticIndexingState.WaitingModel, 0, bulk))
        assertEquals(SemanticStatusLabel.WaitingModel, semanticStatusLabel(SemanticIndexingState.WaitingModel, 0, 0))
    }

    @Test
    fun `done reports ready`() {
        assertEquals(SemanticStatusLabel.Ready, semanticStatusLabel(SemanticIndexingState.Done, 500, 0))
    }

    @Test
    fun `a settled walk with embeddings reports ready`() {
        // Settled to Idle a few un-downloadable photos short, but 16557 are embedded and searchable.
        assertEquals(SemanticStatusLabel.Ready, semanticStatusLabel(SemanticIndexingState.Idle, 16557, 10))
        assertEquals(SemanticStatusLabel.Ready, semanticStatusLabel(SemanticIndexingState.Idle, 1, 0))
    }

    @Test
    fun `a settled walk with nothing embedded reports not indexed`() {
        // A fresh index, or right after a clear, genuinely has nothing to search.
        assertEquals(SemanticStatusLabel.NotIndexed, semanticStatusLabel(SemanticIndexingState.Idle, 0, 0))
    }
}
