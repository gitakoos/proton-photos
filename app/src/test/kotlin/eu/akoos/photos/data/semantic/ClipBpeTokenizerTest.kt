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

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.io.FileInputStream

/**
 * The tokenizer is the correctness gate for semantic search: it has to reproduce the token ids the
 * CLIP text encoder was trained with, id-for-id, or a query lands in a different space from the
 * image embeddings and search returns noise.
 *
 * The parity fixtures are the reference input/output pairs from open_clip's Python SimpleTokenizer, run
 * over a synthetic vocabulary whose every merge line is "a b" so no real merge fires. That isolates the
 * parts a port most easily gets wrong, the byte encoding, the split pattern and the vocabulary order,
 * with expected ids that can be checked without shipping the whole rank table. This test rebuilds that
 * same synthetic vocabulary and asserts the ids match. It then loads the real bundled vocabulary from
 * the asset it ships in and checks the end-to-end shape and that its ranked merges actually collapse a
 * query, which the synthetic vocabulary cannot.
 */
class ClipBpeTokenizerTest {

    private val syntheticTokenizer: ClipBpeTokenizer by lazy {
        ClipBpeTokenizer.fromVocabularyText(buildSyntheticVocabulary())
    }

    @Test
    fun `encodes every parity fixture to the reference token ids`() {
        for ((query, expected) in FIXTURES) {
            val ids = syntheticTokenizer.encode(query)
            assertEquals("context length for \"$query\"", CONTEXT_LENGTH, ids.size)
            assertArrayEquals(
                "token ids for \"$query\"",
                expected,
                ids.copyOfRange(0, expected.size),
            )
            for (i in expected.size until ids.size) {
                assertEquals("pad id at $i for \"$query\"", PAD_ID, ids[i])
            }
        }
    }

    @Test
    fun `collapses tab and newline runs to single spaces`() {
        assertArrayEquals(
            syntheticTokenizer.encode("multiple spaces and lines"),
            syntheticTokenizer.encode("multiple    spaces\tand\nlines"),
        )
    }

    @Test
    fun `loads the bundled vocabulary and produces well-formed ids`() {
        val vocab = locateBundledVocabulary()
        val tokenizer = FileInputStream(vocab).use { ClipBpeTokenizer.fromVocabularyStream(it) }

        val query = "a red bicycle on the beach"
        val ids = tokenizer.encode(query)
        assertEquals(CONTEXT_LENGTH, ids.size)
        assertEquals("starts with start-of-text", SOT_ID, ids[0])

        val end = ids.indexOf(EOT_ID)
        assertTrue("ends with end-of-text before padding", end in 1 until CONTEXT_LENGTH)
        for (i in end + 1 until CONTEXT_LENGTH) {
            assertEquals("pad id at $i", PAD_ID, ids[i])
        }

        val realContent = end - 1
        val syntheticContent = syntheticTokenizer.encode(query).indexOf(EOT_ID) - 1
        assertTrue(
            "real merges collapse the query below the byte-per-character count ($realContent < $syntheticContent)",
            realContent < syntheticContent,
        )
    }

    /** The synthetic vocabulary of the Dart parity test: a version header then only "a b" merge lines. */
    private fun buildSyntheticVocabulary(): String {
        val builder = StringBuilder("#version: 0.2")
        repeat(SYNTHETIC_MERGE_LINES) { builder.append("\na b") }
        return builder.toString()
    }

    /**
     * The bundled vocabulary on disk. A Gradle unit test runs with the module as its working directory,
     * so the asset path resolves directly; the walk up from the working directory is a guard for other
     * runners. Fails loudly rather than skipping when the asset cannot be found.
     */
    private fun locateBundledVocabulary(): File {
        File(VOCAB_RELATIVE).let { if (it.isFile) return it }
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            File(dir, VOCAB_RELATIVE).let { if (it.isFile) return it }
            File(dir, "app/$VOCAB_RELATIVE").let { if (it.isFile) return it }
            dir = dir.parentFile
        }
        fail("bundled vocab not found at $VOCAB_RELATIVE (user.dir=${System.getProperty("user.dir")})")
        error("unreachable")
    }

    private companion object {
        const val CONTEXT_LENGTH = 77
        const val PAD_ID = 0
        const val SOT_ID = 49406
        const val EOT_ID = 49407

        /** Merge lines in the Dart synthetic vocab: 49152 - 256 - 2. */
        const val SYNTHETIC_MERGE_LINES = 49152 - 256 - 2

        const val VOCAB_RELATIVE = "src/main/assets/semantic/bpe_simple_vocab_16e6.txt"

        /**
         * Input phrase to reference id prefix, from open_clip's Python tokenizer over the synthetic vocab.
         * Each array is the query's ids through end-of-text; the rest of the 77 positions are pad ids.
         * The last case begins with U+0130, U+017F and U+212A to pin the invariant lowercasing of a
         * dotted capital, a long s and the Kelvin sign.
         */
        val FIXTURES: List<Pair<String, IntArray>> = listOf(
            "hello world" to intArrayOf(49406, 71, 68, 75, 75, 334, 86, 78, 81, 75, 323, 49407),
            "can't won't i'd we're" to intArrayOf(
                49406, 66, 64, 333, 6, 339, 86, 78, 333, 6, 339, 328, 6, 323, 86, 324, 6, 81, 324, 49407,
            ),
            "multiple    spaces\tand\nlines" to intArrayOf(
                49406, 76, 84, 75, 83, 72, 79, 75, 324, 82, 79, 64, 66, 68, 338, 64, 77, 323, 75, 72, 77,
                68, 338, 49407,
            ),
            "&amp;lt;html&amp;gt; entities &amp;amp; symbols" to intArrayOf(
                49406, 283, 71, 83, 76, 331, 285, 68, 77, 83, 72, 83, 72, 68, 338, 261, 82, 88, 76, 65,
                78, 75, 338, 49407,
            ),
            "numbers 1234567890 and punctuation !!! ???" to intArrayOf(
                49406, 77, 84, 76, 65, 68, 81, 338, 272, 273, 274, 275, 276, 277, 278, 279, 280, 271, 64,
                77, 323, 79, 84, 77, 66, 83, 84, 64, 83, 72, 78, 333, 0, 0, 256, 30, 30, 286, 49407,
            ),
            ("long query long query long query long query long query long query long query long query " +
                "long query long query") to intArrayOf(
                49406,
                75, 78, 77, 326, 80, 84, 68, 81, 344,
                75, 78, 77, 326, 80, 84, 68, 81, 344,
                75, 78, 77, 326, 80, 84, 68, 81, 344,
                75, 78, 77, 326, 80, 84, 68, 81, 344,
                75, 78, 77, 326, 80, 84, 68, 81, 344,
                75, 78, 77, 326, 80, 84, 68, 81, 344,
                75, 78, 77, 326, 80, 84, 68, 81, 344,
                75, 78, 77, 326, 80, 84, 68, 81, 344,
                75, 78, 77,
                49407,
            ),
            "İstanbul ſtyle Kelvin 1234" to intArrayOf(
                49406, 328, 136, 485, 82, 83, 64, 77, 65, 84, 331, 129, 123, 83, 88, 75, 324, 74, 68, 75,
                85, 72, 333, 272, 273, 274, 275, 49407,
            ),
        )
    }
}
