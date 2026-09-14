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

import android.content.Context
import java.io.InputStream

/**
 * The byte-level BPE tokenizer the CLIP text encoder expects: it turns a query string into the
 * exact int32 token ids [ClipTextEncoder] reads. It is a faithful port of open_clip's SimpleTokenizer,
 * the tokenizer the CLIP text encoder was trained with, so the ids match id-for-id; a divergence
 * would place a query in a different space from the image embeddings and search would return noise.
 *
 * The pipeline per query mirrors the reference exactly: HTML-unescape and trim, collapse whitespace runs
 * to single spaces, lowercase with the locale-invariant Unicode mapping, split on [TOKEN_PATTERN],
 * UTF-8 byte-encode each piece through the [bytesToUnicode] map, merge with [bpe] over the vocab's rank
 * table, map the merged pieces to ids, then wrap with start-of-text and end-of-text and pad or truncate
 * to [contextLength].
 *
 * Build one from the bundled vocabulary through [fromAssets]; the byte-encoding, rank table and
 * token->id map are all derived from that one file. [encode] is not safe for concurrent calls: it fills
 * a shared merge cache as it runs, and its owner serialises calls.
 */
class ClipBpeTokenizer private constructor(
    private val byteEncoder: Map<Int, String>,
    private val encoder: Map<String, Int>,
    private val bpeRanks: Map<Pair<String, String>, Int>,
    private val startOfText: Int,
    private val endOfText: Int,
    val contextLength: Int,
) {

    /** Memoised [bpe] results, seeded with the two special tokens so a literal one maps straight through. */
    private val cache: MutableMap<String, String> = HashMap<String, String>().apply {
        put(START_TOKEN, START_TOKEN)
        put(END_TOKEN, END_TOKEN)
    }

    /**
     * The [contextLength] token ids for [text]: start-of-text, then the query's merged ids truncated to
     * [contextLength] minus two, then end-of-text, then pad ids (0, per open_clip's zero-filled tensor)
     * to fill the length. Start-of-text is always first and end-of-text always terminates the query
     * before any padding.
     */
    fun encode(text: String): IntArray {
        val encoded = bpeEncode(text)
        val maxTextTokens = contextLength - 2
        val kept = if (encoded.size > maxTextTokens) maxTextTokens else encoded.size

        val ids = IntArray(contextLength)
        ids[0] = startOfText
        for (i in 0 until kept) ids[i + 1] = encoded[i]
        ids[kept + 1] = endOfText
        return ids
    }

    /** The query's merged token ids, before start/end wrapping and padding. */
    private fun bpeEncode(text: String): List<Int> {
        val ids = ArrayList<Int>()
        val clean = whitespaceClean(basicClean(text)).lowercase()
        for (match in TOKEN_REGEX.findAll(clean)) {
            val encoded = StringBuilder()
            for (byte in match.value.toByteArray(Charsets.UTF_8)) {
                encoded.append(byteEncoder[byte.toInt() and 0xFF] ?: error("missing byte encoding"))
            }
            for (piece in bpe(encoded.toString()).split(' ')) {
                ids.add(encoder[piece] ?: error("missing BPE token in encoder: $piece"))
            }
        }
        return ids
    }

    /**
     * The space-joined BPE merge of one byte-encoded token: every character becomes its own symbol, the
     * last gains the end-of-word marker, and the highest-ranked adjacent pair is merged repeatedly until
     * no pair has a rank. The result is cached.
     */
    private fun bpe(token: String): String {
        cache[token]?.let { return it }

        val word = ArrayList<String>(token.length)
        for (char in token) word.add(char.toString())
        if (word.isEmpty()) return ""
        word[word.size - 1] = word[word.size - 1] + END_OF_WORD

        var pairs = pairsOf(word)
        if (pairs.isEmpty()) return token + END_OF_WORD

        while (true) {
            var bigram = pairs[0]
            var bigramRank = bpeRanks[bigram] ?: Int.MAX_VALUE
            for (pair in pairs) {
                val rank = bpeRanks[pair] ?: Int.MAX_VALUE
                if (rank < bigramRank) {
                    bigram = pair
                    bigramRank = rank
                }
            }
            if (bigram !in bpeRanks) break

            val first = bigram.first
            val second = bigram.second
            val merged = ArrayList<String>(word.size)
            var i = 0
            while (i < word.size) {
                val at = indexOfFrom(word, first, i)
                if (at < 0) {
                    for (k in i until word.size) merged.add(word[k])
                    break
                }
                for (k in i until at) merged.add(word[k])
                i = at
                if (word[i] == first && i < word.size - 1 && word[i + 1] == second) {
                    merged.add(first + second)
                    i += 2
                } else {
                    merged.add(word[i])
                    i += 1
                }
            }

            word.clear()
            word.addAll(merged)
            if (word.size == 1) break
            pairs = pairsOf(word)
        }

        return word.joinToString(" ").also { cache[token] = it }
    }

    companion object {

        /** Token count the CLIP text encoder reads. */
        private const val CONTEXT_LENGTH = 77

        private const val END_OF_WORD = "</w>"

        private const val START_TOKEN = "<|startoftext|>"

        private const val END_TOKEN = "<|endoftext|>"

        /**
         * One past the last vocabulary line that is a BPE merge: 49152 - 256 - 2 + 1. The 49152-token
         * vocabulary is 256 byte tokens, the same 256 with the end-of-word marker, the merges, and the
         * two special tokens; this leaves exactly that many merge lines after the version header.
         */
        private const val BPE_MERGES_END_EXCLUSIVE = 49152 - 256 - 2 + 1

        /**
         * open_clip's SimpleTokenizer split pattern. The two special tokens match whole, the English
         * contraction suffixes match, then letter runs, single digits (numbers split digit by digit), and
         * runs of anything that is neither whitespace, letter nor number.
         */
        const val TOKEN_PATTERN =
            """(?i)<\|startoftext\|>|<\|endoftext\|>|'s|'t|'re|'ve|'m|'ll|'d|[\p{L}]+|[\p{N}]|[^\s\p{L}\p{N}]+"""

        private const val WHITESPACE_PATTERN = """\s+"""

        private val TOKEN_REGEX = Regex(TOKEN_PATTERN, RegexOption.IGNORE_CASE)

        private val WHITESPACE_REGEX = Regex(WHITESPACE_PATTERN)

        private val NAMED_ENTITIES = mapOf(
            "amp" to "&",
            "lt" to "<",
            "gt" to ">",
            "quot" to "\"",
            "apos" to "'",
            "nbsp" to " ",
        )

        private const val MAX_ENTITY_LENGTH = 32

        private val vocabAsset = "${SemanticModelAssets.DIRECTORY}/bpe_simple_vocab_16e6.txt"

        @Volatile
        private var assetTokenizer: ClipBpeTokenizer? = null

        /**
         * A tokenizer built from the bundled vocabulary, cached for the process. The vocabulary is a
         * stable asset, so one instance serves every query. It is packaged as plain text: the build
         * would strip the gzip wrapper from a compressed asset yet keep the source name, leaving the
         * runtime opening a path that does not exist, so the source itself is the decompressed text.
         */
        fun fromAssets(context: Context): ClipBpeTokenizer {
            assetTokenizer?.let { return it }
            return synchronized(this) {
                assetTokenizer ?: context.applicationContext.assets.open(vocabAsset).use { fromVocabularyStream(it) }
                    .also { assetTokenizer = it }
            }
        }

        /** A tokenizer from an open_clip vocabulary text stream. The stream is read in full and closed. */
        fun fromVocabularyStream(input: InputStream): ClipBpeTokenizer =
            fromVocabularyText(input.reader(Charsets.UTF_8).use { it.readText() })

        /**
         * A tokenizer from the decompressed vocabulary text. The first line is the version header; the
         * next [BPE_MERGES_END_EXCLUSIVE] minus one lines are the ranked merges. The token->id order is
         * fixed: the 256 byte tokens, then those with the end-of-word marker, then the merged pairs, then
         * start-of-text and end-of-text.
         */
        fun fromVocabularyText(vocabulary: String): ClipBpeTokenizer {
            val (byteEncoder, byteValues) = bytesToUnicode()

            val lines = vocabulary.split('\n')
            require(lines.size >= BPE_MERGES_END_EXCLUSIVE) {
                "invalid clip vocab: expected at least $BPE_MERGES_END_EXCLUSIVE lines, got ${lines.size}"
            }

            val merges = ArrayList<Pair<String, String>>(BPE_MERGES_END_EXCLUSIVE)
            for (index in 1 until BPE_MERGES_END_EXCLUSIVE) {
                val parts = lines[index].split(WHITESPACE_REGEX).filter { it.isNotEmpty() }
                if (parts.size < 2) continue
                merges.add(parts[0] to parts[1])
            }

            val vocab = ArrayList<String>(byteValues.size * 2 + merges.size + 2)
            vocab.addAll(byteValues)
            for (value in byteValues) vocab.add(value + END_OF_WORD)
            for ((first, second) in merges) vocab.add(first + second)
            vocab.add(START_TOKEN)
            vocab.add(END_TOKEN)

            val encoder = HashMap<String, Int>(vocab.size * 2)
            for (index in vocab.indices) encoder[vocab[index]] = index

            val bpeRanks = HashMap<Pair<String, String>, Int>(merges.size * 2)
            for (index in merges.indices) bpeRanks[merges[index]] = index

            val sot = encoder[START_TOKEN] ?: error("missing start token in vocab")
            val eot = encoder[END_TOKEN] ?: error("missing end token in vocab")

            return ClipBpeTokenizer(byteEncoder, encoder, bpeRanks, sot, eot, CONTEXT_LENGTH)
        }

        /** open_clip's basic_clean, minus the ftfy mojibake repair: HTML-unescape twice, then trim. */
        private fun basicClean(text: String): String = htmlUnescape(htmlUnescape(text)).trim()

        /** Collapse every whitespace run to a single space and trim. */
        private fun whitespaceClean(text: String): String = WHITESPACE_REGEX.replace(text, " ").trim()

        /** Replace the entities the reference decodes: the XML-predefined ones and numeric character references. */
        private fun htmlUnescape(text: String): String {
            if (text.indexOf('&') < 0) return text
            val out = StringBuilder(text.length)
            var i = 0
            while (i < text.length) {
                val char = text[i]
                if (char != '&') {
                    out.append(char)
                    i++
                    continue
                }
                val semicolon = text.indexOf(';', i + 1)
                val decoded = if (semicolon in (i + 2)..(i + MAX_ENTITY_LENGTH)) {
                    decodeEntity(text.substring(i + 1, semicolon))
                } else {
                    null
                }
                if (decoded != null) {
                    out.append(decoded)
                    i = semicolon + 1
                } else {
                    out.append(char)
                    i++
                }
            }
            return out.toString()
        }

        /** The replacement for one entity body (without the ampersand and semicolon), or null if unknown. */
        private fun decodeEntity(body: String): String? {
            if (body.isEmpty()) return null
            if (body[0] == '#') {
                val codePoint = if (body.length > 1 && (body[1] == 'x' || body[1] == 'X')) {
                    body.substring(2).toIntOrNull(16)
                } else {
                    body.substring(1).toIntOrNull()
                }
                if (codePoint == null || codePoint !in 0..0x10FFFF) return null
                return String(Character.toChars(codePoint))
            }
            return NAMED_ENTITIES[body]
        }

        /** Unique adjacent symbol pairs of [word], in first-seen order. */
        private fun pairsOf(word: List<String>): List<Pair<String, String>> {
            if (word.size < 2) return emptyList()
            val seen = HashSet<Pair<String, String>>()
            val pairs = ArrayList<Pair<String, String>>(word.size - 1)
            var previous = word[0]
            for (index in 1 until word.size) {
                val current = word[index]
                val pair = previous to current
                if (seen.add(pair)) pairs.add(pair)
                previous = current
            }
            return pairs
        }

        /** The first index at or after [from] where [word] holds [needle], or -1. */
        private fun indexOfFrom(word: List<String>, needle: String, from: Int): Int {
            for (index in from until word.size) {
                if (word[index] == needle) return index
            }
            return -1
        }

        /**
         * open_clip's bytes_to_unicode: a reversible map from each of the 256 byte values to a printable
         * Unicode character, so the BPE runs over text with no control characters or spaces. Returns the
         * byte->character map and the 256 characters in byte-token order.
         */
        private fun bytesToUnicode(): Pair<Map<Int, String>, List<String>> {
            val bytes = ArrayList<Int>()
            for (code in 0x21..0x7E) bytes.add(code)
            for (code in 0xA1..0xAC) bytes.add(code)
            for (code in 0xAE..0xFF) bytes.add(code)

            val printable = HashSet(bytes)
            val codes = ArrayList(bytes)
            var next = 0
            for (byte in 0..0xFF) {
                if (byte !in printable) {
                    bytes.add(byte)
                    codes.add(0x100 + next)
                    next++
                }
            }

            val values = codes.map { String(Character.toChars(it)) }
            val byteEncoder = HashMap<Int, String>(bytes.size * 2)
            for (index in bytes.indices) byteEncoder[bytes[index]] = values[index]
            return byteEncoder to values
        }
    }
}
