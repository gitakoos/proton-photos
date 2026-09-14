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
import java.io.File
import java.io.FileInputStream

/**
 * End-to-end tokenizer check against the REAL bundled vocabulary (not the synthetic parity fixtures):
 * every id below is open_clip's SimpleTokenizer output for the same string, so a query encodes to exactly
 * the token space the CLIP text encoder was trained on. A drift here would put a query in a different
 * space from the image embeddings and search would return nothing, so these ids are pinned.
 */
class ClipBpeTokenizerRealVocabTest {

    private val tokenizer: ClipBpeTokenizer by lazy {
        val rel = "src/main/assets/semantic/bpe_simple_vocab_16e6.txt"
        val file = listOf(File(rel), File(System.getProperty("user.dir"), rel), File(System.getProperty("user.dir"), "app/$rel"))
            .firstOrNull { it.exists() } ?: error("bundled vocab not found for the real-vocab tokenizer test")
        FileInputStream(file).use { ClipBpeTokenizer.fromVocabularyStream(it) }
    }

    private fun assertEncodes(text: String, expectedContent: List<Int>) {
        val ids = tokenizer.encode(text)
        assertEquals("length", 77, ids.size)
        assertEquals("content of \"$text\"", expectedContent, ids.take(expectedContent.size))
        assertEquals("padded with 0 after the content", List(77 - expectedContent.size) { 0 }, ids.drop(expectedContent.size).toList())
    }

    @Test
    fun `encodes common queries to the open_clip reference ids`() {
        assertEncodes("a photo of a cat", listOf(49406, 320, 1125, 539, 320, 2368, 49407))
        assertEncodes("cat", listOf(49406, 2368, 49407))
        assertEncodes("dog", listOf(49406, 1929, 49407))
        assertEncodes("car", listOf(49406, 1615, 49407))
        assertEncodes("beach", listOf(49406, 2117, 49407))
        assertEncodes("sunset", listOf(49406, 3424, 49407))
        assertEncodes("a dog in the snow", listOf(49406, 320, 1929, 530, 518, 2583, 49407))
    }
}
