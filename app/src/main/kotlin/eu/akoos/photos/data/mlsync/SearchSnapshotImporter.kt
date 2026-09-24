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
import androidx.room.withTransaction
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

/** Rows written per upsert inside the import's transaction. */
internal const val SEARCH_IMPORT_BATCH_SIZE = 256

/** How a snapshot's embeddings meet the ones this device already holds. */
enum class SearchImportMode {
    /** The snapshot's vectors replace this device's rows for the photos it covers. */
    Replace,

    /** Rows are added only for photos this device has not embedded yet; every row it holds stays. */
    Merge,
}

/** Why a search snapshot import failed. In every case the index is exactly as it was before. */
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

    /** Nothing was imported. */
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
 * from it unless it verified. A snapshot from another embedding space is skipped whole. The rows are
 * written in one database transaction that commits only after the reader passed the snapshot's end
 * marker, so a failure at any point leaves the index as it was.
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
     * [SearchImportFailure.DAMAGED]. Runs on the IO dispatcher.
     */
    suspend fun importFrom(
        userId: UserId?,
        mode: SearchImportMode,
        expectedDeviceId: String? = null,
        openSnapshot: () -> InputStream,
    ): SearchSnapshotImportResult {
        if (userId == null) return SearchSnapshotImportResult.Failed(SearchImportFailure.NO_ACCOUNT)
        val engine = SearchSnapshotImportEngine(
            database = appDatabase,
            embeddings = imageEmbeddingDao,
            crypto = envelopeCrypto,
            scratchDir = searchScratchDir(context.cacheDir),
        )
        return engine.run(
            account = searchIndexAccount(userId),
            mode = mode,
            openKeys = { refresh -> envelopeCrypto.openKeys(userId, refreshAddresses = refresh) },
            openSnapshot = openSnapshot,
            expectedDeviceId = expectedDeviceId,
        )
    }
}

/**
 * The import itself, apart from where the account's keys come from, so it runs against a fake envelope
 * crypto and an in-memory database in tests.
 */
internal class SearchSnapshotImportEngine(
    private val database: RoomDatabase,
    private val embeddings: ImageEmbeddingDao,
    private val crypto: EnvelopeCrypto,
    private val scratchDir: File,
    private val modelId: String = SEARCH_MODEL_ID,
    private val embeddingDim: Int = SEARCH_EMBEDDING_DIM,
    private val keyPageSize: Int = SEARCH_KEY_PAGE_SIZE,
    private val batchSize: Int = SEARCH_IMPORT_BATCH_SIZE,
) {
    /**
     * Imports the snapshot into [account]'s index. [openKeys] hands out the keys to open it with, freshly
     * fetched from the server when its argument is true. [expectedDeviceId], when given, is the install the
     * snapshot has to be from ([snapshotFromExpectedDevice]).
     */
    suspend fun run(
        account: String,
        mode: SearchImportMode,
        openKeys: suspend (refreshAddresses: Boolean) -> EnvelopeOpenKeys,
        openSnapshot: () -> InputStream,
        importedAtMillis: Long = System.currentTimeMillis(),
        expectedDeviceId: String? = null,
    ): SearchSnapshotImportResult = withContext(Dispatchers.IO) {
        var plaintext: File? = null
        try {
            val scratch = newSearchScratchFile(scratchDir, "search-import-").also { plaintext = it }
            when (openInto(scratch, openKeys, openSnapshot)) {
                is EnvelopeOpenResult.Opened ->
                    FileInputStream(scratch).use { apply(account, mode, it, importedAtMillis, expectedDeviceId) }
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
            Log.w(TAG, "search snapshot import failed: ${e.javaClass.simpleName}")
            failed(SearchImportFailure.ERROR)
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
     * Writes the verified plaintext's rows. The header is judged before a transaction opens; the rows go
     * in batches inside one transaction that commits only once the reader has passed the end marker, so a
     * snapshot found malformed anywhere rolls every row back.
     */
    private suspend fun apply(
        account: String,
        mode: SearchImportMode,
        plaintext: InputStream,
        importedAtMillis: Long,
        expectedDeviceId: String?,
    ): SearchSnapshotImportResult {
        val reader = MlSnapshotReader(plaintext)
        val header = reader.header as? MlSnapshotHeader.Search
            ?: return failed(SearchImportFailure.NOT_A_SEARCH_SNAPSHOT)
        if (!snapshotFromExpectedDevice(header.deviceId, expectedDeviceId)) {
            Log.w(TAG, "search snapshot refused: another install wrote it")
            return failed(SearchImportFailure.DAMAGED)
        }
        if (!isCompatibleSearchSnapshot(header, modelId, embeddingDim)) {
            return SearchSnapshotImportResult.SkippedModelMismatch(header.searchModelId, header.embeddingDim)
        }
        return database.withTransaction {
            // A row of an older model or a wrong width is stale and counts as not embedded, so it is replaced.
            val embeddedHere = embeddings.currentSearchKeys(account, keyPageSize)
            var imported = 0
            var kept = 0
            val batch = ArrayList<ImageEmbeddingEntity>(batchSize)
            for (record in reader) {
                if (record !is MlSnapshotRecord.SearchEmbedding || !isPortableSearchKey(record.photoKey)) continue
                if (!shouldImportSearchEmbedding(mode, record.photoKey in embeddedHere, record.isTombstone)) {
                    kept++
                    continue
                }
                batch += ImageEmbeddingEntity(
                    userId = account,
                    photoKey = record.photoKey,
                    embedding = record.embedding,
                    modelVersion = SEMANTIC_MODEL_VERSION,
                    indexedAt = importedAtMillis,
                )
                embeddedHere += record.photoKey
                imported++
                if (batch.size == batchSize) {
                    embeddings.upsert(batch)
                    batch.clear()
                }
            }
            if (batch.isNotEmpty()) embeddings.upsert(batch)
            SearchSnapshotImportResult.Imported(imported, kept)
        }
    }

    private fun failed(reason: SearchImportFailure) = SearchSnapshotImportResult.Failed(reason)
}
