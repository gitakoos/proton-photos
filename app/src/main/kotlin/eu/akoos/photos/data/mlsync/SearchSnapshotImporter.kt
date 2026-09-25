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
import androidx.room.RoomDatabase
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.akoos.photos.data.db.AppDatabase
import eu.akoos.photos.data.db.dao.ImageEmbeddingDao
import eu.akoos.photos.data.db.entity.ImageEmbeddingEntity
import eu.akoos.photos.data.semantic.SEMANTIC_MODEL_VERSION
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.proton.core.domain.entity.UserId
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import javax.inject.Inject

private const val TAG = "SearchSnapshot"

/**
 * Rows an import writes per transaction: the write-ahead log grows by one chunk before it can be
 * checkpointed, not by a whole snapshot, and a failure part-way keeps the chunks before it.
 */
internal const val SEARCH_IMPORT_CHUNK_SIZE = 2_000

/** How a snapshot's embeddings meet the ones this device already holds. */
enum class SearchImportMode {
    /** The snapshot's vectors replace this device's rows for the photos it covers. */
    Replace,

    /** Rows are added only for photos this device has not embedded yet; every row it holds stays. */
    Merge,
}

/**
 * Why a search snapshot import failed. The snapshot is opened, judged and read through to its end marker
 * before the first row is written, so a failure up to there leaves the index exactly as it was. A failure
 * while the rows are written, such as storage running out, keeps the chunks committed before it, every row
 * of them valid on its own, and importing the snapshot again completes the index.
 */
enum class SearchImportFailure {
    /** No signed-in account, so there is no key to open the snapshot with. */
    NO_ACCOUNT,

    /** No key of this account opens it: another account's snapshot. */
    WRONG_ACCOUNT,

    /** Cut short, altered, not signed by the account, malformed inside, or not the file it was picked as. */
    DAMAGED,

    /** Not a sealed snapshot, or a snapshot of the face index. */
    NOT_A_SEARCH_SNAPSHOT,

    /** Written by a newer build, in a version this one does not read. */
    TOO_NEW,

    /**
     * Storage is too full: before the first row, when the free space the import needs is not there, or
     * while the rows were written.
     */
    NO_SPACE,

    /** Storage, the database or the account's keys failed. */
    ERROR,
}

/** Outcome of [SearchSnapshotImporter.importFrom]. */
sealed interface SearchSnapshotImportResult {
    /** Committed: [imported] rows written, and [kept] photos of the snapshot kept this device's own row. */
    data class Imported(val imported: Int, val kept: Int) : SearchSnapshotImportResult

    /**
     * The snapshot's vectors come from another embedding space ([modelId], [embeddingDim] components) and
     * do not compare with this device's, so nothing was imported; this device embeds those photos itself.
     */
    data class SkippedModelMismatch(val modelId: String, val embeddingDim: Int) : SearchSnapshotImportResult

    /** Nothing was imported, or, after a failure while the rows were written, only the chunks committed before it. */
    data class Failed(val reason: SearchImportFailure) : SearchSnapshotImportResult
}

/**
 * Whether one snapshot row is written into this device's index. A photo with no row here takes it in
 * either mode. For a photo this device has embedded, [SearchImportMode.Merge] keeps the local row and
 * [SearchImportMode.Replace] takes the snapshot's vector, except a tombstone: it only records that the
 * other device could not read the photo, so it never replaces a row this device holds.
 */
internal fun shouldImportSearchEmbedding(
    mode: SearchImportMode,
    embeddedHere: Boolean,
    tombstone: Boolean,
): Boolean = when {
    !embeddedHere -> true
    tombstone -> false
    else -> mode == SearchImportMode.Replace
}

/** Whether [header]'s vectors compare with this device's: the same embedding space at the same width. */
internal fun isCompatibleSearchSnapshot(
    header: MlSnapshotHeader.Search,
    modelId: String = SEARCH_MODEL_ID,
    embeddingDim: Int = SEARCH_EMBEDDING_DIM,
): Boolean = header.searchModelId == modelId && header.embeddingDim == embeddingDim

/**
 * Restores a SEARCH snapshot that [SearchSnapshotExporter] wrote on a device of the same account into this
 * device's semantic search index, so the photos it covers are searchable without being embedded here.
 *
 * The envelope is opened into a scratch file in the app cache, deleted afterwards, and nothing is read
 * from it unless it verified. A snapshot from another embedding space is skipped whole. The plaintext is
 * read through to its end marker before the first row is written, so a malformed one changes nothing, and
 * its rows then go in chunks of [SEARCH_IMPORT_CHUNK_SIZE], each its own transaction. Every row stands on
 * its own, so a failure part-way keeps the chunks already committed, and importing again completes the index.
 * The free space the rows need on the database's volume ([SEARCH_IMPORT_SPACE_FACTOR]) is checked before the
 * first one, and storage running full reads as [SearchImportFailure.NO_SPACE].
 */
class SearchSnapshotImporter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val appDatabase: AppDatabase,
    private val imageEmbeddingDao: ImageEmbeddingDao,
    private val envelopeCrypto: AddressKeyEnvelopeCrypto,
) {
    /**
     * Imports the sealed snapshot [openSnapshot] opens into [userId]'s index under [mode]. [openSnapshot]
     * returns a fresh stream from the start of the snapshot on every call, since a snapshot none of the
     * cached keys opens is read once more after the account's addresses are refetched. A snapshot whose
     * header names another install than [expectedDeviceId], the one its Drive name carries, is refused as
     * [SearchImportFailure.DAMAGED]. [releaseSnapshot] runs once the snapshot has opened and verified,
     * before the first row, when it is not read again: a caller can delete the file behind it, leaving that
     * space to the import. Runs on the IO dispatcher.
     */
    suspend fun importFrom(
        userId: UserId?,
        mode: SearchImportMode,
        expectedDeviceId: String? = null,
        releaseSnapshot: () -> Unit = {},
        openSnapshot: () -> InputStream,
    ): SearchSnapshotImportResult {
        if (userId == null) return SearchSnapshotImportResult.Failed(SearchImportFailure.NO_ACCOUNT)
        val engine = SearchSnapshotImportEngine(
            database = appDatabase,
            embeddings = imageEmbeddingDao,
            crypto = envelopeCrypto,
            scratchDir = searchScratchDir(context.cacheDir),
            freeBytes = { databaseFreeBytes(context, appDatabase) },
        )
        return engine.run(
            account = searchIndexAccount(userId),
            mode = mode,
            openKeys = { refresh -> envelopeCrypto.openKeys(userId, refreshAddresses = refresh) },
            openSnapshot = openSnapshot,
            expectedDeviceId = expectedDeviceId,
            releaseSnapshot = releaseSnapshot,
        )
    }
}

/**
 * The import itself, apart from where the account's keys and the free space are read, so it runs against
 * a fake envelope crypto and an in-memory database in tests. [freeBytes] reads the free space on the
 * database's volume, null when unknown.
 */
internal class SearchSnapshotImportEngine(
    private val database: RoomDatabase,
    private val embeddings: ImageEmbeddingDao,
    private val crypto: EnvelopeCrypto,
    private val scratchDir: File,
    private val modelId: String = SEARCH_MODEL_ID,
    private val embeddingDim: Int = SEARCH_EMBEDDING_DIM,
    private val keyPageSize: Int = SEARCH_KEY_PAGE_SIZE,
    private val chunkSize: Int = SEARCH_IMPORT_CHUNK_SIZE,
    private val freeBytes: () -> Long? = { null },
) {
    /**
     * Imports the snapshot into [account]'s index. [openKeys] hands out the keys to open it with, freshly
     * fetched from the server when its argument is true. [expectedDeviceId], when given, is the install the
     * snapshot has to be from ([snapshotFromExpectedDevice]). [releaseSnapshot] runs once the snapshot has
     * opened and verified, as [SearchSnapshotImporter.importFrom] describes.
     */
    suspend fun run(
        account: String,
        mode: SearchImportMode,
        openKeys: suspend (refreshAddresses: Boolean) -> EnvelopeOpenKeys,
        openSnapshot: () -> InputStream,
        importedAtMillis: Long = System.currentTimeMillis(),
        expectedDeviceId: String? = null,
        releaseSnapshot: () -> Unit = {},
    ): SearchSnapshotImportResult = withContext(Dispatchers.IO) {
        var plaintext: File? = null
        try {
            val scratch = newSearchScratchFile(scratchDir, "search-import-").also { plaintext = it }
            when (openInto(scratch, openKeys, openSnapshot)) {
                is EnvelopeOpenResult.Opened -> {
                    releaseSnapshot()
                    apply(account, mode, scratch, importedAtMillis, expectedDeviceId)
                }
                EnvelopeOpenResult.WrongKey -> failed(SearchImportFailure.WRONG_ACCOUNT)
                EnvelopeOpenResult.Corrupt -> failed(SearchImportFailure.DAMAGED)
                EnvelopeOpenResult.NotAnEnvelope -> failed(SearchImportFailure.NOT_A_SEARCH_SNAPSHOT)
                is EnvelopeOpenResult.UnsupportedVersion -> failed(SearchImportFailure.TOO_NEW)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: UnsupportedMlSnapshotVersionException) {
            failed(SearchImportFailure.TOO_NEW)
        } catch (e: MlSnapshotFormatException) {
            failed(SearchImportFailure.DAMAGED)
        } catch (e: Exception) {
            if (isStorageFull(e)) {
                Log.w(TAG, "search snapshot import stopped: storage full")
                failed(SearchImportFailure.NO_SPACE)
            } else {
                Log.w(TAG, "search snapshot import failed: ${e.javaClass.simpleName}")
                failed(SearchImportFailure.ERROR)
            }
        } finally {
            plaintext?.delete()
        }
    }

    /**
     * Opens the envelope into [scratch]. A snapshot no cached key opens is tried once more with the
     * account's addresses refetched, for a key made on another device since this one cached them.
     */
    private suspend fun openInto(
        scratch: File,
        openKeys: suspend (refreshAddresses: Boolean) -> EnvelopeOpenKeys,
        openSnapshot: () -> InputStream,
    ): EnvelopeOpenResult {
        val cached = openWith(scratch, openKeys(false), openSnapshot)
        if (cached != EnvelopeOpenResult.WrongKey) return cached
        return openWith(scratch, openKeys(true), openSnapshot)
    }

    private fun openWith(scratch: File, keys: EnvelopeOpenKeys, openSnapshot: () -> InputStream): EnvelopeOpenResult =
        keys.use { openingKeys ->
            openSnapshot().buffered().use { input ->
                FileOutputStream(scratch).use { output -> MlSnapshotEnvelope.open(input, output, crypto, openingKeys) }
            }
        }

    /**
     * Imports the verified plaintext in [plaintext]: surveyed whole first, then measured against the free
     * space, then its rows written.
     */
    private suspend fun apply(
        account: String,
        mode: SearchImportMode,
        plaintext: File,
        importedAtMillis: Long,
        expectedDeviceId: String?,
    ): SearchSnapshotImportResult {
        survey(plaintext, expectedDeviceId)?.let { return it }
        if (!importFits(plaintext.length(), SEARCH_IMPORT_SPACE_FACTOR, freeBytes())) {
            Log.w(TAG, "search snapshot refused: not enough free space")
            return failed(SearchImportFailure.NO_SPACE)
        }
        return FileInputStream(plaintext).use { write(account, mode, MlSnapshotReader(it), importedAtMillis) }
    }

    /**
     * Judges the header, then reads every record through to the end marker, so a snapshot cut short or
     * malformed anywhere throws [MlSnapshotFormatException] before a row is written. Returns the outcome
     * that ends the import here, or null when its rows are to be written.
     */
    private fun survey(plaintext: File, expectedDeviceId: String?): SearchSnapshotImportResult? {
        FileInputStream(plaintext).use { input ->
            val reader = MlSnapshotReader(input)
            val header = reader.header as? MlSnapshotHeader.Search
                ?: return failed(SearchImportFailure.NOT_A_SEARCH_SNAPSHOT)
            if (!snapshotFromExpectedDevice(header.deviceId, expectedDeviceId)) {
                Log.w(TAG, "search snapshot refused: another install wrote it")
                return failed(SearchImportFailure.DAMAGED)
            }
            if (!isCompatibleSearchSnapshot(header, modelId, embeddingDim)) {
                return SearchSnapshotImportResult.SkippedModelMismatch(header.searchModelId, header.embeddingDim)
            }
            while (reader.hasNext()) reader.next()
        }
        return null
    }

    /**
     * Writes the surveyed snapshot's rows in chunks of [chunkSize], each its own transaction. Every row
     * stands on its own: a failure keeps the chunks committed before it, and a rerun, which finds their
     * rows embedded here, leaves the index as one uninterrupted import would.
     */
    private suspend fun write(
        account: String,
        mode: SearchImportMode,
        reader: MlSnapshotReader,
        importedAtMillis: Long,
    ): SearchSnapshotImportResult {
        // A row of an older model or a wrong width is stale and counts as not embedded, so it is replaced.
        val embeddedHere = embeddings.currentSearchKeys(account, keyPageSize)
        var imported = 0
        var kept = 0
        val chunk = ArrayList<ImageEmbeddingEntity>(chunkSize)
        for (record in reader) {
            if (record !is MlSnapshotRecord.SearchEmbedding || !isPortableSearchKey(record.photoKey)) continue
            if (!shouldImportSearchEmbedding(mode, record.photoKey in embeddedHere, record.isTombstone)) {
                kept++
                continue
            }
            chunk += ImageEmbeddingEntity(
                userId = account,
                photoKey = record.photoKey,
                embedding = record.embedding,
                modelVersion = SEMANTIC_MODEL_VERSION,
                indexedAt = importedAtMillis,
            )
            embeddedHere += record.photoKey
            imported++
            if (chunk.size == chunkSize) writeChunk(chunk)
        }
        writeChunk(chunk)
        return SearchSnapshotImportResult.Imported(imported, kept)
    }

    /** Writes [chunk] in one transaction, so it lands whole or not at all, and empties it. */
    private suspend fun writeChunk(chunk: MutableList<ImageEmbeddingEntity>) {
        if (chunk.isEmpty()) return
        database.withStorageAwareTransaction { embeddings.upsert(chunk) }
        chunk.clear()
    }

    private fun failed(reason: SearchImportFailure) = SearchSnapshotImportResult.Failed(reason)
}
