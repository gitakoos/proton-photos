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

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.akoos.photos.data.db.dao.ImageEmbeddingDao
import eu.akoos.photos.data.db.entity.PhotoLocationEntity
import eu.akoos.photos.data.semantic.SEMANTIC_MODEL_VERSION
import eu.akoos.photos.data.semantic.SemanticModelAssets
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.proton.core.domain.entity.UserId
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import javax.inject.Inject

private const val TAG = "SearchSnapshot"

/** Components of one search embedding: the width of the image encoder's output, which every row of the
 *  semantic index holds as little-endian f32 values (all zero bytes for a tombstone). */
internal const val SEARCH_EMBEDDING_DIM = 512

/** Bytes of one stored search embedding. */
internal const val SEARCH_EMBEDDING_BYTES = SEARCH_EMBEDDING_DIM * Float.SIZE_BYTES

/**
 * The embedding space this build's search index lives in, written into every search snapshot and matched
 * exactly on import: the indexer's model generation and the exact image encoder bytes that computed the
 * vectors. A vector under any other id does not compare with this device's, so it is never imported.
 */
internal val SEARCH_MODEL_ID: String =
    "clip-vit-b16-datacomp:$SEMANTIC_MODEL_VERSION:${SemanticModelAssets.IMAGE_MODEL.sha256}"

/** Keys read per page when a whole key set is collected. */
internal const val SEARCH_KEY_PAGE_SIZE = 2_000

/** Rows the export reads per page: 256 blobs of 2 KiB, well inside one cursor window. */
internal const val SEARCH_ROW_PAGE_SIZE = 256

/** Passes an export makes before it gives up on an index that keeps losing rows under it. */
private const val EXPORT_ATTEMPTS = 3

/** A scratch file this much older than a new run is a leftover of a run the process did not outlive. */
private const val STALE_SCRATCH_MS = 60 * 60 * 1000L

/**
 * Whether [photoKey] names the same photo on every device of the account: a Proton Drive link id, which
 * carries no URI scheme. A device key (a `content://` MediaStore uri or a `file://` path) names another
 * photo, or none, on another phone, so it never leaves this device and is never taken from a snapshot.
 */
internal fun isPortableSearchKey(photoKey: String): Boolean = photoKey.isNotEmpty() && "://" !in photoKey

/** The semantic index's partition for [userId]: the account id, or the guest partition without one. */
internal fun searchIndexAccount(userId: UserId?): String = userId?.id ?: PhotoLocationEntity.LOCAL_USER

/** Where the search snapshot engine keeps its short-lived plaintext files, under the app's [cacheDir]. */
internal fun searchScratchDir(cacheDir: File): File = File(File(cacheDir, MlSyncScratch.DIRECTORY), "search")

/**
 * A new empty file in [dir] for one run's plaintext. Any file there older than an hour is deleted first:
 * the leftover of a run that was killed before it could clean up.
 */
internal fun newSearchScratchFile(dir: File, prefix: String): File {
    dir.mkdirs()
    if (!dir.isDirectory) throw IOException("the scratch directory is unavailable")
    val now = System.currentTimeMillis()
    dir.listFiles()?.forEach { if (now - it.lastModified() > STALE_SCRATCH_MS) it.delete() }
    return File.createTempFile(prefix, ".tmp", dir)
}

/**
 * Every key of [account]'s index whose row holds an embedding of this build's model at the full width, in
 * index order, read in pages of [pageSize]; [keep] picks the keys to collect. Only keys are held, never a
 * vector.
 */
internal suspend fun ImageEmbeddingDao.currentSearchKeys(
    account: String,
    pageSize: Int,
    keep: (String) -> Boolean = { true },
): LinkedHashSet<String> {
    val keys = LinkedHashSet<String>()
    var after = ""
    do {
        val page = keysAfter(account, SEMANTIC_MODEL_VERSION, SEARCH_EMBEDDING_BYTES, after, pageSize)
        page.filterTo(keys, keep)
        after = page.lastOrNull() ?: after
    } while (page.size == pageSize)
    return keys
}

/** Why a search snapshot export failed. */
enum class SearchExportFailure {
    /** No signed-in account, so there is no address key to seal the snapshot to. */
    NO_ACCOUNT,

    /** Rows kept vanishing from the index while it was read, on every attempt. */
    INDEX_CHANGED,

    /** Storage, the database or the account's keys failed. */
    ERROR,
}

/** Outcome of [SearchSnapshotExporter.exportTo]. */
sealed interface SearchSnapshotExportResult {
    /** The sealed snapshot is in the output: [embeddings] photos, [tombstones] of them photos the indexer
     *  could not read. */
    data class Exported(val embeddings: Int, val tombstones: Int) : SearchSnapshotExportResult

    /** This device holds no embedding another device could use; nothing was written to the output. */
    data object Empty : SearchSnapshotExportResult

    /** Nothing usable was written; whatever reached the output must be discarded. */
    data class Failed(val reason: SearchExportFailure) : SearchSnapshotExportResult
}

/** Who wrote a snapshot, as its header names them. */
internal data class SearchSnapshotAuthor(val deviceId: String, val deviceLabel: String)

/**
 * Writes the account's semantic search index as a sealed SEARCH snapshot, the file another device of the
 * account restores with [SearchSnapshotImporter] instead of embedding every photo again.
 *
 * Only rows another device can use go out: those keyed by a Drive link id and computed by this build's
 * model ([SEARCH_MODEL_ID]), tombstones included, so the restoring device also skips the photos this one
 * could not read. The index is read in bounded pages, the plaintext is staged in a scratch file in the app
 * cache, sealed into the output under the account's address key, and deleted.
 */
class SearchSnapshotExporter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val imageEmbeddingDao: ImageEmbeddingDao,
    private val envelopeCrypto: AddressKeyEnvelopeCrypto,
    private val mlDriveStore: MlDriveStore,
) {
    /**
     * Seals [userId]'s search snapshot into [output], which is written to but never closed. Runs on the IO
     * dispatcher. Unless the result is [SearchSnapshotExportResult.Exported], the output holds nothing to
     * keep.
     */
    suspend fun exportTo(userId: UserId?, output: OutputStream): SearchSnapshotExportResult {
        if (userId == null) return SearchSnapshotExportResult.Failed(SearchExportFailure.NO_ACCOUNT)
        val engine = SearchSnapshotExportEngine(
            embeddings = imageEmbeddingDao,
            crypto = envelopeCrypto,
            scratchDir = searchScratchDir(context.cacheDir),
        )
        return engine.run(
            account = searchIndexAccount(userId),
            author = { SearchSnapshotAuthor(mlDriveStore.deviceId(), mlDriveStore.deviceLabel) },
            sealKeys = { envelopeCrypto.sealKeys(userId) },
            output = output,
        )
    }
}

/**
 * The export itself, apart from where the account's keys and this device's identity come from, so it runs
 * against a fake envelope crypto and an in-memory database in tests.
 *
 * The header states exact counts before the first record, so the index is read twice: a first pass
 * collects the keys to export (the key table is held in memory anyway, as the writer numbers every record
 * against it), and a second streams their rows page by page. A row the indexer adds between the passes
 * waits for the next export. A row that vanishes between them would leave the snapshot short, so the
 * export starts over from a fresh first pass, up to [EXPORT_ATTEMPTS] times.
 */
internal class SearchSnapshotExportEngine(
    private val embeddings: ImageEmbeddingDao,
    private val crypto: EnvelopeCrypto,
    private val scratchDir: File,
    private val modelId: String = SEARCH_MODEL_ID,
    private val keyPageSize: Int = SEARCH_KEY_PAGE_SIZE,
    private val rowPageSize: Int = SEARCH_ROW_PAGE_SIZE,
) {
    /** Exports [account]'s index into [output]. [sealKeys] is asked only once there is something to seal. */
    suspend fun run(
        account: String,
        author: suspend () -> SearchSnapshotAuthor,
        sealKeys: suspend () -> EnvelopeSealKeys,
        output: OutputStream,
        createdAtMillis: Long = System.currentTimeMillis(),
    ): SearchSnapshotExportResult = withContext(Dispatchers.IO) {
        var plaintext: File? = null
        try {
            val scratch = newSearchScratchFile(scratchDir, "search-export-").also { plaintext = it }
            val counts = writeWithRetries(scratch, account, author(), createdAtMillis)
                ?: return@withContext SearchSnapshotExportResult.Failed(SearchExportFailure.INDEX_CHANGED)
            if (counts.embeddings == 0) return@withContext SearchSnapshotExportResult.Empty
            sealKeys().use { keys ->
                FileInputStream(scratch).use { MlSnapshotEnvelope.seal(it, output, crypto, keys) }
            }
            SearchSnapshotExportResult.Exported(counts.embeddings, counts.tombstones)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "search snapshot export failed: ${e.javaClass.simpleName}")
            SearchSnapshotExportResult.Failed(SearchExportFailure.ERROR)
        } finally {
            plaintext?.delete()
        }
    }

    private suspend fun writeWithRetries(
        scratch: File,
        account: String,
        author: SearchSnapshotAuthor,
        createdAtMillis: Long,
    ): Counts? {
        repeat(EXPORT_ATTEMPTS) {
            FileOutputStream(scratch).use { writePlaintext(account, author, createdAtMillis, it) }?.let { return it }
        }
        return null
    }

    /**
     * Writes the plaintext snapshot to [output] and returns what it holds, or null, with the stream left
     * unfinished, when a key of the first pass no longer had a row by the second.
     */
    private suspend fun writePlaintext(
        account: String,
        author: SearchSnapshotAuthor,
        createdAtMillis: Long,
        output: OutputStream,
    ): Counts? {
        val keys = embeddings.currentSearchKeys(account, keyPageSize, ::isPortableSearchKey)
        if (keys.isEmpty()) return Counts(embeddings = 0, tombstones = 0)
        val writer = MlSnapshotWriter(
            output,
            MlSnapshotHeader.Search(
                deviceId = author.deviceId,
                deviceLabel = author.deviceLabel,
                createdAtMillis = createdAtMillis,
                searchModelId = modelId,
                embeddingDim = SEARCH_EMBEDDING_DIM,
                keyCount = keys.size,
                embeddingCount = keys.size,
            ),
        )
        for (key in keys) writer.write(MlSnapshotRecord.PhotoKey(key))
        var written = 0
        var tombstones = 0
        var after = ""
        do {
            val page = embeddings.embeddingsAfter(
                account, SEMANTIC_MODEL_VERSION, SEARCH_EMBEDDING_BYTES, after, rowPageSize,
            )
            for (row in page) {
                // Embedded after the first pass: not in the key table, so it waits for the next export.
                if (row.photoKey !in keys) continue
                val record = MlSnapshotRecord.SearchEmbedding(row.photoKey, row.embedding)
                if (record.isTombstone) tombstones++
                writer.write(record)
                written++
            }
            after = page.lastOrNull()?.photoKey ?: after
        } while (page.size == rowPageSize)
        if (written != keys.size) return null
        writer.finish()
        return Counts(written, tombstones)
    }

    private class Counts(val embeddings: Int, val tombstones: Int)
}
