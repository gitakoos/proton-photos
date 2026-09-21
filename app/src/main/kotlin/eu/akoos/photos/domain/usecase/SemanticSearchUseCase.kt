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

package eu.akoos.photos.domain.usecase

import android.content.Context
import android.os.SystemClock
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.akoos.photos.data.db.dao.ImageEmbeddingDao
import eu.akoos.photos.data.db.entity.PhotoLocationEntity
import eu.akoos.photos.data.semantic.ClipBpeTokenizer
import eu.akoos.photos.data.semantic.ClipTextEncoder
import eu.akoos.photos.data.semantic.SemanticModelManager
import eu.akoos.photos.util.SemanticDiagnostics
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import me.proton.core.domain.entity.UserId
import java.io.File
import java.util.PriorityQueue
import javax.inject.Inject
import javax.inject.Singleton

/** One search result: the photo's [ImageEmbeddingDao] key, joinable straight back to the timeline, and
 *  its cosine [score] against the query (higher is closer). */
data class SemanticHit(val photoKey: String, val score: Float)

/** Photos scored per page against the query, so a large library streams through a bounded heap rather
 *  than loading every stored embedding at once. */
private const val PAGE_SIZE = 2000

/** Most matches a search returns, the top-K cap. Two hundred is more than a result grid ever shows at
 *  once yet keeps the returned list and its sort cheap. */
private const val DEFAULT_LIMIT = 200

/** Cosine floor a photo must clear to be a match, calibrated on device for this model (DataComp
 *  ViT-B/16): its cosines sit in a compressed band. A handful of "busy" photos weakly match many
 *  unrelated queries in a false-positive cluster around 0.22; weak-but-real matches begin just above it
 *  (about 0.228) and strong matches run to about 0.30. The floor threads just over the false-positive
 *  cluster, dropping those stray photos while keeping a lightly-represented query's real hits. The margin
 *  is narrow by nature of the compressed scale. The zero-vector tombstones the indexer writes for
 *  undecodable photos (which score exactly 0) fall well below it. Re-tune whenever the encoder changes. */
private const val MIN_SCORE = 0.225f

/**
 * Turns a natural-language query into a ranked list of photos by embedding the text with the CLIP
 * text encoder and comparing it to the image embeddings the indexer stored. Both vectors are 512-d and
 * L2-normalised in the same space, so a cosine is a plain dot product and the closest photos are the
 * best matches.
 *
 * Guest-safe: the embeddings are keyed by the account id, or [PhotoLocationEntity.LOCAL_USER] with no
 * account, exactly as the indexer keyed them.
 *
 * A search is dark until it can work: a blank query, the text model not yet on the device, or nothing
 * indexed for the account each return no results rather than an error. The heavy work runs on
 * [Dispatchers.Default], and the stored embeddings are scored a page at a time through a bounded top-K
 * heap so even a fifty-thousand-photo library never materialises every vector at once.
 *
 * The tokenizer and the text encoder are opened once and reused across searches (the encoder holds a
 * native session that costs far more to open than to run), and every use of them is serialised, since
 * neither is safe for concurrent calls.
 */
@Singleton
class SemanticSearchUseCase @Inject constructor(
    @ApplicationContext private val context: Context,
    private val imageEmbeddingDao: ImageEmbeddingDao,
) {
    private val modelManager by lazy { SemanticModelManager(context) }

    /** Serialises query embedding, so the shared tokenizer and text session are never entered
     *  concurrently, and guards the lazy build of both. */
    private val embedLock = Mutex()

    private var tokenizer: ClipBpeTokenizer? = null
    private var textEncoder: ClipTextEncoder? = null
    private var textEncoderFile: File? = null

    /**
     * The photos best matching [query] for [userId], closest first, at most [limit] and each clearing
     * [MIN_SCORE]. Empty for a blank query, when the text model is not on the device, or when nothing is
     * indexed for the account.
     */
    suspend fun search(query: String, userId: UserId?, limit: Int = DEFAULT_LIMIT): List<SemanticHit> {
        if (query.isBlank()) return emptyList()
        return withContext(Dispatchers.Default) {
            try {
                val account = userId?.id ?: PhotoLocationEntity.LOCAL_USER
                // The ranker scores every stored embedding for the account, so this count is exactly what a
                // search scans; it also short-circuits the model work when nothing is indexed yet.
                val scanned = imageEmbeddingDao.countForUser(account)
                if (scanned == 0) return@withContext emptyList()
                val textModel = modelManager.textModelFile() ?: return@withContext emptyList()
                val embedStart = SystemClock.elapsedRealtime()
                val queryVector = embedQuery(query, textModel)
                val embedMs = SystemClock.elapsedRealtime() - embedStart
                val rankStart = SystemClock.elapsedRealtime()
                val hits = rankStored(account, queryVector, limit)
                SemanticDiagnostics.recordSearch(embedMs, SystemClock.elapsedRealtime() - rankStart, scanned, hits.size)
                hits
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                emptyList()
            }
        }
    }

    /** [search] returning just the photo keys, for a caller that only needs the ranking. */
    suspend fun searchKeys(query: String, userId: UserId?, limit: Int = DEFAULT_LIMIT): List<String> =
        search(query, userId, limit).map { it.photoKey }

    /**
     * Scores every stored embedding for the account against [queryVector], a page at a time, keeping a
     * bounded top-K. Only [PAGE_SIZE] rows and the [limit]-sized heap are ever resident, so the working
     * set stays flat as the library grows. Cancellation is checked per page.
     */
    private suspend fun rankStored(account: String, queryVector: FloatArray, limit: Int): List<SemanticHit> {
        val heap = TopKAccumulator(limit, MIN_SCORE)
        var offset = 0
        while (true) {
            currentCoroutineContext().ensureActive()
            val page = imageEmbeddingDao.embeddingsPaged(account, PAGE_SIZE, offset)
            if (page.isEmpty()) break
            for (row in page) {
                val vector = unpackSemanticEmbedding(row.embedding)
                // A blob that is not this model's width (a stale or corrupt row) is skipped by the width
                // guard rather than ranked, keeping the semantic rail independent of the face model's dim.
                if (vector.size != queryVector.size) continue
                heap.offer(row.photoKey, cosineSimilarity(queryVector, vector))
            }
            if (page.size < PAGE_SIZE) break
            offset += PAGE_SIZE
        }
        return heap.sorted()
    }

    /** The L2-normalised text embedding of [query], through the reused tokenizer and text encoder.
     *  Serialised on [embedLock]: neither the merge cache nor the ONNX session is safe for concurrent use. */
    private suspend fun embedQuery(query: String, textModel: File): FloatArray = embedLock.withLock {
        val bpe = tokenizer ?: ClipBpeTokenizer.fromAssets(context).also { tokenizer = it }
        encoderFor(textModel).encode(bpe.encode(query))
    }

    /** The cached text encoder, or a fresh one when the resolved model file changed (a re-download or a
     *  first run). The old session is closed before a new one opens so a swap never leaks it. */
    private fun encoderFor(textModel: File): ClipTextEncoder {
        textEncoder?.let { if (textEncoderFile == textModel) return it }
        textEncoder?.let { runCatching { it.close() } }
        return ClipTextEncoder(textModel).also {
            textEncoder = it
            textEncoderFile = textModel
        }
    }
}

/**
 * Unpacks a stored embedding blob (little-endian float32) back to a vector, reading exactly the floats
 * the blob holds so it never depends on any other rail's embedding width. A blob that is not a whole
 * number of floats returns empty, so a corrupt row is skipped by the caller's width guard rather than
 * mis-read. Pure, so it is unit-tested without a database or a model.
 */
internal fun unpackSemanticEmbedding(bytes: ByteArray): FloatArray {
    if (bytes.isEmpty() || bytes.size % Float.SIZE_BYTES != 0) return FloatArray(0)
    val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    return FloatArray(bytes.size / Float.SIZE_BYTES) { buffer.float }
}

/**
 * Ranks [rows] against [query] and returns the top [limit] by cosine, highest first, each at least
 * [minScore]. The pure heart of the search: no Android, no database, no model, so the whole selection
 * and threshold rule runs in a plain JVM test. [query] and each row vector are unit-length in the same
 * space, so [cosineSimilarity] (a dot product) is their cosine; a row whose width does not match [query]
 * (an empty unpack of a stale-width blob) is skipped, and a zero vector scores 0 and so falls under any
 * positive [minScore], which is how the indexer's tombstones never rank. Ordering is deterministic:
 * score descending, then photo key ascending, independent of the order [rows] arrive in.
 */
internal fun topKByCosine(
    query: FloatArray,
    rows: Sequence<Pair<String, FloatArray>>,
    limit: Int,
    minScore: Float,
): List<SemanticHit> {
    val accumulator = TopKAccumulator(limit, minScore)
    for ((photoKey, embedding) in rows) {
        if (embedding.size != query.size) continue
        accumulator.offer(photoKey, cosineSimilarity(query, embedding))
    }
    return accumulator.sorted()
}

/**
 * A bounded top-K collector: holds at most [limit] hits that clear [minScore], as a min-heap keyed so the
 * head is the weakest hit held (lowest score, and among equal scores the largest photo key). Once full, a
 * stronger hit evicts the head, so the working set stays [limit]-sized however many rows are offered.
 * [sorted] returns the held hits highest score first, ties broken by photo key ascending, so the result
 * does not depend on the order rows were offered.
 */
private class TopKAccumulator(private val limit: Int, private val minScore: Float) {

    /** Weakest-first order (lowest score, then largest key), so the head is what a stronger hit evicts. */
    private val weakestFirst = compareBy<SemanticHit> { it.score }.thenByDescending { it.photoKey }

    private val heap = PriorityQueue(maxOf(limit, 1), weakestFirst)

    fun offer(photoKey: String, score: Float) {
        if (limit <= 0 || score < minScore) return
        if (heap.size < limit) {
            heap.add(SemanticHit(photoKey, score))
            return
        }
        val head = heap.peek() ?: return
        // Evict the weakest held hit only when this one truly outranks it, so equal scores keep the hit
        // already held and the cap never churns on ties.
        if (score > head.score || (score == head.score && photoKey < head.photoKey)) {
            heap.poll()
            heap.add(SemanticHit(photoKey, score))
        }
    }

    fun sorted(): List<SemanticHit> =
        heap.sortedWith(compareByDescending<SemanticHit> { it.score }.thenBy { it.photoKey })
}
