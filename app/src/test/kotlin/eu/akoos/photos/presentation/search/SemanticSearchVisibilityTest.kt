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

package eu.akoos.photos.presentation.search

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SemanticSearchVisibilityTest {

    @Test
    fun `indexing on this phone makes content search available`() {
        assertTrue(semanticSearchAvailable(indexingHere = true, signedIn = true, accountHoldsEmbeddings = false))
        assertTrue(semanticSearchAvailable(indexingHere = true, signedIn = false, accountHoldsEmbeddings = false))
    }

    @Test
    fun `a signed in account with embeddings searches with indexing off`() {
        assertTrue(semanticSearchAvailable(indexingHere = false, signedIn = true, accountHoldsEmbeddings = true))
    }

    @Test
    fun `a signed in account with no embeddings and no indexing has no content search`() {
        assertFalse(semanticSearchAvailable(indexingHere = false, signedIn = true, accountHoldsEmbeddings = false))
    }

    @Test
    fun `a guest follows the indexing switch alone`() {
        assertFalse(semanticSearchAvailable(indexingHere = false, signedIn = false, accountHoldsEmbeddings = true))
    }

    @Test
    fun `the text model is fetched only on an unmetered connection when it is missing and idle`() {
        assertTrue(shouldFetchSearchTextModel(searchAvailable = true, textModelPresent = false, fetchRunning = false, unmetered = true))
        assertFalse(shouldFetchSearchTextModel(searchAvailable = true, textModelPresent = false, fetchRunning = false, unmetered = false))
        assertFalse(shouldFetchSearchTextModel(searchAvailable = true, textModelPresent = true, fetchRunning = false, unmetered = true))
        assertFalse(shouldFetchSearchTextModel(searchAvailable = true, textModelPresent = false, fetchRunning = true, unmetered = true))
        assertFalse(shouldFetchSearchTextModel(searchAvailable = false, textModelPresent = false, fetchRunning = false, unmetered = true))
    }
}
