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
import android.database.sqlite.SQLiteFullException
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.test.core.app.ApplicationProvider
import eu.akoos.photos.data.db.dao.ImageEmbeddingDao
import eu.akoos.photos.data.db.dao.ImageEmbeddingRow
import eu.akoos.photos.data.db.entity.ImageEmbeddingEntity
import eu.akoos.photos.data.semantic.SEMANTIC_MODEL_VERSION
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlin.math.sqrt
import kotlin.random.Random

/** Just the embedding table, so these tests need nothing else from the app's schema. */
@Database(entities = [ImageEmbeddingEntity::class], version = 1, exportSchema = false)
abstract class SearchSnapshotTestDatabase : RoomDatabase() {
    abstract fun imageEmbeddingDao(): ImageEmbeddingDao
}

/**
 * A search index saved on one phone and restored on another phone of the same account, each with its own
 * in-memory database, through the real snapshot format and envelope and a fake OpenPGP: vectors come back
 * at half precision and tombstones as tombstones, only link-id rows of this build's model travel, Merge
 * and Replace meet the local rows as specified, and every refusal (another model or width, a damaged,
 * foreign or unrelated file, a snapshot cut short or malformed anywhere inside) leaves the restoring index
 * untouched. Rows land a chunk per transaction: a failure part-way keeps the chunks before it, and importing
 * again completes the index. The export's two passes are also raced against the indexer.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SearchSnapshotRoundTripTest {

    @get:Rule
    val temp = TemporaryFolder()

    /** The phone that saves the snapshot and the one that restores it. */
    private lateinit var phoneA: SearchSnapshotTestDatabase
    private lateinit var phoneB: SearchSnapshotTestDatabase
    private lateinit var scratchDir: File
    private val crypto = FakeCrypto()

    @Before
    fun setUp() {
        phoneA = newPhone()
        phoneB = newPhone()
        scratchDir = temp.newFolder("scratch")
    }

    private fun newPhone(): SearchSnapshotTestDatabase =
        Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), SearchSnapshotTestDatabase::class.java)
            .allowMainThreadQueries()
            .setQueryExecutor { it.run() }
            .build()

    @After
    fun tearDown() {
        phoneA.close()
        phoneB.close()
    }

    /** A random unit vector, the shape the indexer stores. */
    private fun unitVector(seed: Int): FloatArray {
        val random = Random(seed)
        val raw = FloatArray(SEARCH_EMBEDDING_DIM) { random.nextFloat() * 2f - 1f }
        val norm = sqrt(raw.sumOf { it.toDouble() * it }).toFloat()
        return FloatArray(raw.size) { raw[it] / norm }
    }

    private fun pack(vector: FloatArray): ByteArray {
        val buffer = ByteBuffer.allocate(vector.size * Float.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        vector.forEach { buffer.putFloat(it) }
        return buffer.array()
    }

    private fun unpack(blob: ByteArray): FloatArray {
        val buffer = ByteBuffer.wrap(blob).order(ByteOrder.LITTLE_ENDIAN)
        return FloatArray(blob.size / Float.SIZE_BYTES) { buffer.float }
    }

    private fun vector(seed: Int): ByteArray = pack(unitVector(seed))

    /** [seed]'s vector as it reads back from a snapshot: every component rounded to the nearest half. */
    private fun halfRounded(seed: Int): FloatArray =
        unitVector(seed).map { MlSnapshotFormat.halfToFloat(MlSnapshotFormat.floatToHalf(it)) }.toFloatArray()

    private val tombstone = ByteArray(SEARCH_EMBEDDING_BYTES)

    private suspend fun SearchSnapshotTestDatabase.put(
        key: String,
        blob: ByteArray,
        account: String = ACCOUNT,
        modelVersion: Int = SEMANTIC_MODEL_VERSION,
    ) = imageEmbeddingDao().upsert(ImageEmbeddingEntity(account, key, blob, modelVersion, indexedAt = 1_000L))

    private suspend fun SearchSnapshotTestDatabase.rows(): Map<String, ByteArray> =
        imageEmbeddingDao().allEmbeddingsForUser(ACCOUNT).associate { it.photoKey to it.embedding }

    /** The account's rows by a digest of their content, and which of them read as current, so a comparison
     *  of two readings shows that nothing moved. */
    private suspend fun SearchSnapshotTestDatabase.contents(): Pair<Map<String, String>, Set<String>> =
        rows().mapValues { (_, blob) -> "${blob.size} bytes, hash ${blob.contentHashCode()}" } to
            imageEmbeddingDao().currentSearchKeys(ACCOUNT, pageSize = 100)

    private fun sealKeys(owner: String) = EnvelopeSealKeys("pub:$owner", owner.toByteArray())

    private fun openKeys(vararg owners: String) =
        EnvelopeOpenKeys(owners.map { it.toByteArray() }, owners.map { "pub:$it" })

    /** Saves phone A's index, with pages small enough that every pass turns several of them. */
    private suspend fun exportSnapshot(
        owner: String = ALICE,
        modelId: String = SEARCH_MODEL_ID,
        embeddings: ImageEmbeddingDao = phoneA.imageEmbeddingDao(),
    ): Pair<SearchSnapshotExportResult, ByteArray> {
        val output = ByteArrayOutputStream()
        val engine = SearchSnapshotExportEngine(embeddings, crypto, scratchDir, modelId, keyPageSize = 2, rowPageSize = 3)
        val result = engine.run(ACCOUNT, { AUTHOR }, { sealKeys(owner) }, output, createdAtMillis = CREATED_AT)
        return result to output.toByteArray()
    }

    /** Restores [snapshot] on [phone], in chunks small enough that a few rows already fill several. */
    private suspend fun importSnapshot(
        snapshot: ByteArray,
        mode: SearchImportMode = SearchImportMode.Replace,
        expectedDeviceId: String? = null,
        phone: SearchSnapshotTestDatabase = phoneB,
        embeddings: ImageEmbeddingDao = phone.imageEmbeddingDao(),
        chunkSize: Int = 2,
        keys: (refreshAddresses: Boolean) -> EnvelopeOpenKeys = { openKeys(ALICE) },
    ): SearchSnapshotImportResult {
        val engine = SearchSnapshotImportEngine(phone, embeddings, crypto, scratchDir, keyPageSize = 2, chunkSize = chunkSize)
        return engine.run(
            ACCOUNT,
            mode,
            { keys(it) },
            { ByteArrayInputStream(snapshot) },
            importedAtMillis = IMPORTED_AT,
            expectedDeviceId = expectedDeviceId,
        )
    }

    /** A plaintext SEARCH snapshot of [rows], for the snapshots the export itself never writes. */
    private fun plaintext(
        rows: Map<String, ByteArray>,
        modelId: String = SEARCH_MODEL_ID,
        embeddingDim: Int = SEARCH_EMBEDDING_DIM,
    ): ByteArray {
        val output = ByteArrayOutputStream()
        val header = MlSnapshotHeader.Search("device-b", "Other phone", CREATED_AT, modelId, embeddingDim, rows.size, rows.size)
        val writer = MlSnapshotWriter(output, header)
        rows.keys.forEach { writer.write(MlSnapshotRecord.PhotoKey(it)) }
        rows.forEach { (key, blob) -> writer.write(MlSnapshotRecord.SearchEmbedding(key, blob)) }
        writer.finish()
        return output.toByteArray()
    }

    /** Seals [plaintext] the way the account's own export does. */
    private fun seal(plaintext: ByteArray): ByteArray {
        val output = ByteArrayOutputStream()
        MlSnapshotEnvelope.seal(ByteArrayInputStream(plaintext), output, crypto, sealKeys(ALICE))
        return output.toByteArray()
    }

    private fun openedHeader(sealed: ByteArray): MlSnapshotHeader {
        val plain = ByteArrayOutputStream()
        val opened = MlSnapshotEnvelope.open(ByteArrayInputStream(sealed), plain, crypto, openKeys(ALICE))
        assertTrue(opened is EnvelopeOpenResult.Opened)
        return MlSnapshotReader(ByteArrayInputStream(plain.toByteArray())).header
    }

    private fun failed(reason: SearchImportFailure) = SearchSnapshotImportResult.Failed(reason)

    /** Phone B's DAO, noting in [chunks] the size of every chunk an import writes. */
    private fun chunkRecorder(chunks: MutableList<Int>): ImageEmbeddingDao {
        val dao = phoneB.imageEmbeddingDao()
        return object : ImageEmbeddingDao by dao {
            override suspend fun upsert(rows: List<ImageEmbeddingEntity>) {
                chunks += rows.size
                dao.upsert(rows)
            }
        }
    }

    @Test
    fun `vectors and tombstones survive the round trip and only portable current rows travel`() = runTest {
        (1..5).forEach { phoneA.put("link-$it", vector(it)) }
        phoneA.put("link-t", tombstone)
        // Rows that stay behind: device keys, an older model, a wrong width, and another account.
        phoneA.put("content://media/external/images/media/41", vector(41))
        phoneA.put("file:///data/vault/42.jpg", vector(42))
        phoneA.put("link-stale", vector(43), modelVersion = SEMANTIC_MODEL_VERSION - 1)
        phoneA.put("link-short", ByteArray(8) { 1 })
        phoneA.put("link-other", vector(44), account = OTHER_ACCOUNT)

        val (exported, sealed) = exportSnapshot()

        assertEquals(SearchSnapshotExportResult.Exported(embeddings = 6, tombstones = 1), exported)
        assertEquals("sealed, not plaintext", MlSnapshotEnvelope.MAGIC, String(sealed.copyOf(8), Charsets.US_ASCII))
        assertEquals(
            MlSnapshotHeader.Search(AUTHOR.deviceId, AUTHOR.deviceLabel, CREATED_AT, SEARCH_MODEL_ID, SEARCH_EMBEDDING_DIM, 6, 6),
            openedHeader(sealed),
        )

        assertEquals(SearchSnapshotImportResult.Imported(imported = 6, kept = 0), importSnapshot(sealed))

        val restored = phoneB.rows()
        assertEquals((1..5).map { "link-$it" }.toSet() + "link-t", restored.keys)
        for (seed in 1..5) {
            val original = unitVector(seed)
            val back = unpack(restored.getValue("link-$seed"))
            assertArrayEquals("link-$seed reads back at half precision", halfRounded(seed), back, 0f)
            assertArrayEquals("and within half precision of the original", original, back, HALF_PRECISION)
        }
        assertArrayEquals("a tombstone stays a tombstone", tombstone, restored.getValue("link-t"))
        assertEquals("every row lands at this build's model", restored.keys, phoneB.contents().second)
        assertEquals("the plaintext scratch files are gone", 0, scratchDir.listFiles()!!.size)
    }

    @Test
    fun `a snapshot another install wrote than the one its name carries is refused and changes nothing`() = runTest {
        phoneA.put("link-1", vector(1))
        phoneB.put("link-9", vector(9))
        val sealed = exportSnapshot().second
        val before = phoneB.contents()

        val refused = importSnapshot(sealed, expectedDeviceId = "7c9e6679-7425-40de-944b-e07fc1f90ae7")

        assertEquals(failed(SearchImportFailure.DAMAGED), refused)
        assertEquals(before, phoneB.contents())
        assertEquals(
            SearchSnapshotImportResult.Imported(imported = 1, kept = 0),
            importSnapshot(sealed, expectedDeviceId = AUTHOR.deviceId),
        )
    }

    @Test
    fun `merge adds only photos this device has not embedded`() = runTest {
        phoneA.put("link-1", vector(1))
        phoneA.put("link-2", vector(2))
        phoneA.put("link-3", tombstone)
        phoneA.put("link-4", vector(4))
        phoneB.put("link-1", vector(101))
        phoneB.put("link-3", vector(103))
        // A row of an older model is stale and waits to be embedded again, so it counts as not embedded.
        phoneB.put("link-4", vector(104), modelVersion = SEMANTIC_MODEL_VERSION - 1)

        val result = importSnapshot(exportSnapshot().second, SearchImportMode.Merge)

        assertEquals(SearchSnapshotImportResult.Imported(imported = 2, kept = 2), result)
        val restored = phoneB.rows()
        assertArrayEquals("its own vector stays", vector(101), restored["link-1"])
        assertArrayEquals(halfRounded(2), unpack(restored.getValue("link-2")), 0f)
        assertArrayEquals("its own vector stays over the tombstone", vector(103), restored["link-3"])
        assertArrayEquals("the stale row is replaced", halfRounded(4), unpack(restored.getValue("link-4")), 0f)
    }

    @Test
    fun `replace overwrites this device's rows but never with a tombstone`() = runTest {
        phoneA.put("link-1", vector(1))
        phoneA.put("link-2", tombstone)
        phoneA.put("link-3", vector(3))
        phoneB.put("link-1", vector(101))
        phoneB.put("link-2", vector(102))
        phoneB.put("link-3", tombstone)
        phoneB.put("link-9", vector(109))

        val result = importSnapshot(exportSnapshot().second, SearchImportMode.Replace)

        assertEquals(SearchSnapshotImportResult.Imported(imported = 2, kept = 1), result)
        val restored = phoneB.rows()
        assertArrayEquals(halfRounded(1), unpack(restored.getValue("link-1")), 0f)
        assertArrayEquals("a tombstone never replaces a vector", vector(102), restored["link-2"])
        assertArrayEquals("a vector replaces a local tombstone", halfRounded(3), unpack(restored.getValue("link-3")), 0f)
        assertArrayEquals("a photo the snapshot lacks is untouched", vector(109), restored["link-9"])
    }

    @Test
    fun `a snapshot of another model or width is skipped and changes nothing`() = runTest {
        phoneA.put("link-1", vector(1))
        phoneB.put("link-9", vector(109))
        val before = phoneB.contents()

        val otherModel = exportSnapshot(modelId = "clip-other:1:00").second
        val narrower = seal(plaintext(mapOf("link-1" to pack(FloatArray(256) { 0.0625f })), embeddingDim = 256))

        assertEquals(
            SearchSnapshotImportResult.SkippedModelMismatch("clip-other:1:00", SEARCH_EMBEDDING_DIM),
            importSnapshot(otherModel),
        )
        assertEquals(SearchSnapshotImportResult.SkippedModelMismatch(SEARCH_MODEL_ID, 256), importSnapshot(narrower))
        assertEquals(before, phoneB.contents())
    }

    @Test
    fun `a snapshot cut short or malformed anywhere writes nothing`() = runTest {
        phoneB.put("link-9", vector(109))
        val before = phoneB.contents()
        val complete = plaintext((1..6).associate { "link-$it" to vector(it) })
        // Where the fifth of the six records starts: the last two records and the end marker follow it.
        val fifth = complete.size - 1 - 2 * VECTOR_RECORD_BYTES
        // Each envelope verifies, but the snapshot inside ends partway through its fourth record, carries a
        // key record in the fifth one's place, or lacks its end marker. Written as it is read, each would
        // have committed chunks of the rows before its fault.
        val faulty = listOf(
            complete.copyOf(fifth - VECTOR_RECORD_BYTES / 2),
            complete.copyOf().also { it[fifth] = MlSnapshotFormat.TAG_KEY.toByte() },
            complete.copyOf(complete.size - 1),
        )
        val chunks = mutableListOf<Int>()

        for (snapshot in faulty) {
            val result = importSnapshot(seal(snapshot), embeddings = chunkRecorder(chunks))
            assertEquals(failed(SearchImportFailure.DAMAGED), result)
        }

        assertEquals("not one chunk was written", emptyList<Int>(), chunks)
        assertEquals(before, phoneB.contents())
        // Whole, the same rows import, so each refusal above is its fault alone.
        assertEquals(
            SearchSnapshotImportResult.Imported(imported = 6, kept = 0),
            importSnapshot(seal(complete), embeddings = chunkRecorder(chunks)),
        )
        assertEquals(listOf(2, 2, 2), chunks)
    }

    @Test
    fun `a snapshot of several chunks round-trips a chunk at a time`() = runTest {
        val count = 2 * SEARCH_IMPORT_CHUNK_SIZE + 1
        val index = (1..count).map { ImageEmbeddingEntity(ACCOUNT, "link-$it", vector(it), SEMANTIC_MODEL_VERSION, 1_000L) }
        phoneA.imageEmbeddingDao().upsert(index)
        val chunks = mutableListOf<Int>()

        val (exported, sealed) = exportSnapshot()
        val result = importSnapshot(sealed, embeddings = chunkRecorder(chunks), chunkSize = SEARCH_IMPORT_CHUNK_SIZE)

        assertEquals(SearchSnapshotExportResult.Exported(embeddings = count, tombstones = 0), exported)
        assertEquals(SearchSnapshotImportResult.Imported(imported = count, kept = 0), result)
        assertEquals(listOf(SEARCH_IMPORT_CHUNK_SIZE, SEARCH_IMPORT_CHUNK_SIZE, 1), chunks)
        val restored = phoneB.rows()
        assertEquals(count, restored.size)
        for (seed in 1..count) {
            assertArrayEquals("link-$seed", halfRounded(seed), unpack(restored.getValue("link-$seed")), 0f)
        }
    }

    @Test
    fun `a failure part-way keeps the chunks already written and importing again completes the index`() = runTest {
        // This phone's own rows: a vector the snapshot's replaces, a vector its tombstone never replaces,
        // and a photo the snapshot lacks.
        suspend fun holdOwnRows(phone: SearchSnapshotTestDatabase) {
            phone.put("link-1", vector(101))
            phone.put("link-4", vector(104))
            phone.put("link-9", vector(109))
        }
        holdOwnRows(phoneB)
        val saved = (1..8).associate { "link-$it" to if (it == 3 || it == 4) tombstone else vector(it) }
        val snapshot = seal(plaintext(saved))
        val dao = phoneB.imageEmbeddingDao()
        var chunks = 0
        val fillsUp = object : ImageEmbeddingDao by dao {
            override suspend fun upsert(rows: List<ImageEmbeddingEntity>) {
                // Storage runs out halfway through the third chunk.
                if (++chunks == 3) {
                    dao.upsert(rows.take(1))
                    throw SQLiteFullException("database or disk is full")
                }
                dao.upsert(rows)
            }
        }

        assertEquals(failed(SearchImportFailure.NO_SPACE), importSnapshot(snapshot, embeddings = fillsUp))

        val afterFailure = phoneB.rows()
        assertEquals(
            "the first two chunks stay and the third is rolled back whole",
            setOf("link-1", "link-2", "link-3", "link-4", "link-5", "link-9"),
            afterFailure.keys,
        )
        assertArrayEquals(halfRounded(1), unpack(afterFailure.getValue("link-1")), 0f)
        assertArrayEquals(tombstone, afterFailure["link-3"])
        assertArrayEquals(vector(104), afterFailure["link-4"])
        // Imported again: the rows the failed run wrote count as this phone's own, the tombstone among them.
        assertEquals(SearchSnapshotImportResult.Imported(imported = 6, kept = 2), importSnapshot(snapshot))
        val uninterrupted = newPhone()
        try {
            holdOwnRows(uninterrupted)
            assertEquals(
                SearchSnapshotImportResult.Imported(imported = 7, kept = 1),
                importSnapshot(snapshot, phone = uninterrupted),
            )
            assertEquals("the index an uninterrupted import leaves", uninterrupted.contents(), phoneB.contents())
        } finally {
            uninterrupted.close()
        }
    }

    @Test
    fun `a snapshot sealed to a key this phone had not cached opens after the address refresh`() = runTest {
        phoneA.put("link-1", vector(1))
        val sealed = exportSnapshot(owner = BOB).second
        val refreshes = mutableListOf<Boolean>()

        val result = importSnapshot(sealed) { refresh ->
            refreshes += refresh
            if (refresh) openKeys(ALICE, BOB) else openKeys(ALICE)
        }

        assertEquals(SearchSnapshotImportResult.Imported(imported = 1, kept = 0), result)
        assertEquals(listOf(false, true), refreshes)
    }

    @Test
    fun `a damaged, foreign or unrelated file is refused and changes nothing`() = runTest {
        phoneA.put("link-1", vector(1))
        phoneB.put("link-9", vector(109))
        val before = phoneB.contents()
        val sealed = exportSnapshot().second
        val altered = sealed.copyOf().also { it[it.size / 2] = (it[it.size / 2].toInt() xor 1).toByte() }
        val faces = ByteArrayOutputStream().also { output ->
            MlSnapshotWriter(
                output,
                MlSnapshotHeader.Faces("device-b", "Other phone", CREATED_AT, 1, "00", 1, 128, 0, 0, 0, 0, 0, 0, 0),
            ).finish()
        }.toByteArray()
        val refreshes = mutableListOf<Boolean>()

        assertEquals(failed(SearchImportFailure.DAMAGED), importSnapshot(altered))
        assertEquals(failed(SearchImportFailure.DAMAGED), importSnapshot(sealed.copyOf(sealed.size - 1)))
        assertEquals(
            failed(SearchImportFailure.WRONG_ACCOUNT),
            importSnapshot(sealed) { refreshes += it; openKeys(MALLORY) },
        )
        assertEquals("the addresses are refetched once before giving up", listOf(false, true), refreshes)
        assertEquals(failed(SearchImportFailure.NOT_A_SEARCH_SNAPSHOT), importSnapshot(plaintext(mapOf("link-1" to vector(1)))))
        assertEquals(failed(SearchImportFailure.NOT_A_SEARCH_SNAPSHOT), importSnapshot(seal(faces)))
        assertEquals(before, phoneB.contents())
        assertEquals("the plaintext scratch files are gone", 0, scratchDir.listFiles()!!.size)
    }

    @Test
    fun `an index with nothing portable exports nothing`() = runTest {
        phoneA.put("content://media/external/images/media/41", vector(41))
        phoneA.put("link-stale", vector(43), modelVersion = SEMANTIC_MODEL_VERSION - 1)

        val (result, sealed) = exportSnapshot()

        assertEquals(SearchSnapshotExportResult.Empty, result)
        assertEquals(0, sealed.size)
    }

    @Test
    fun `a photo embedded during the export waits for the next one`() = runTest {
        (1..4).forEach { phoneA.put("link-$it", vector(it)) }
        val dao = phoneA.imageEmbeddingDao()
        var added = false
        val racing = object : ImageEmbeddingDao by dao {
            override suspend fun embeddingsAfter(
                userId: String,
                modelVersion: Int,
                embeddingBytes: Int,
                afterKey: String,
                limit: Int,
            ): List<ImageEmbeddingRow> {
                // The indexer lands a photo between the key pass and the row pass.
                if (!added) {
                    added = true
                    phoneA.put("link-0", vector(10))
                }
                return dao.embeddingsAfter(userId, modelVersion, embeddingBytes, afterKey, limit)
            }
        }

        val (result, sealed) = exportSnapshot(embeddings = racing)

        assertEquals(SearchSnapshotExportResult.Exported(embeddings = 4, tombstones = 0), result)
        importSnapshot(sealed)
        assertEquals((1..4).map { "link-$it" }.toSet(), phoneB.rows().keys)
    }

    @Test
    fun `a photo removed during the export starts it over`() = runTest {
        (1..4).forEach { phoneA.put("link-$it", vector(it)) }
        val dao = phoneA.imageEmbeddingDao()
        var rowPasses = 0
        val racing = object : ImageEmbeddingDao by dao {
            override suspend fun embeddingsAfter(
                userId: String,
                modelVersion: Int,
                embeddingBytes: Int,
                afterKey: String,
                limit: Int,
            ): List<ImageEmbeddingRow> {
                // A row disappears between the first key pass and its row pass.
                if (afterKey.isEmpty() && rowPasses++ == 0) dao.deleteByPhotoKey(ACCOUNT, "link-2")
                return dao.embeddingsAfter(userId, modelVersion, embeddingBytes, afterKey, limit)
            }
        }

        val (result, sealed) = exportSnapshot(embeddings = racing)

        assertEquals(SearchSnapshotExportResult.Exported(embeddings = 3, tombstones = 0), result)
        assertEquals("the short first pass was written again", 2, rowPasses)
        importSnapshot(sealed)
        assertEquals(setOf("link-1", "link-3", "link-4"), phoneB.rows().keys)
    }

    /**
     * A reversible stand-in for OpenPGP, after the one the envelope's own tests use, since the real one is
     * native and absent from a JVM test. A key is a name: "pub:" plus the name is its public half and the
     * name's bytes its private half. A key packet and a signature carry their key's name; a chunk is a
     * 4-byte check of the session key followed by the plaintext XOR that key; a signature's tag is SHA-256
     * over the signer's name and the data.
     */
    private class FakeCrypto : EnvelopeCrypto {
        private val random = java.util.Random(7)

        override fun generateSessionKey(): ByteArray = ByteArray(16).also { random.nextBytes(it) }

        override fun wrapSessionKey(sessionKey: ByteArray, recipientPublicKey: String): ByteArray =
            framed(recipientPublicKey.removePrefix("pub:").toByteArray(), sessionKey)

        override fun unwrapSessionKey(wrappedKey: ByteArray, decryptionKey: ByteArray): ByteArray? {
            val (recipient, sessionKey) = unframed(wrappedKey) ?: return null
            return if (recipient.contentEquals(decryptionKey)) sessionKey else null
        }

        override fun encryptChunk(plain: ByteArray, sessionKey: ByteArray): ByteArray =
            keyCheck(sessionKey) + xor(plain, sessionKey)

        override fun decryptChunk(cipher: ByteArray, sessionKey: ByteArray): ByteArray? {
            if (cipher.size < CHECK_BYTES || !cipher.copyOf(CHECK_BYTES).contentEquals(keyCheck(sessionKey))) return null
            return xor(cipher.copyOfRange(CHECK_BYTES, cipher.size), sessionKey)
        }

        override fun sign(data: ByteArray, signingKey: ByteArray): ByteArray =
            framed(signingKey, sha256(signingKey + data))

        override fun verify(data: ByteArray, signature: ByteArray, verificationKeys: List<String>): Boolean {
            val (signer, tag) = unframed(signature) ?: return false
            return "pub:${String(signer)}" in verificationKeys && tag.contentEquals(sha256(signer + data))
        }

        private fun keyCheck(sessionKey: ByteArray): ByteArray = sha256(sessionKey).copyOf(CHECK_BYTES)

        private fun xor(data: ByteArray, key: ByteArray): ByteArray =
            ByteArray(data.size) { i -> (data[i].toInt() xor key[i % key.size].toInt()).toByte() }

        private fun framed(field: ByteArray, rest: ByteArray): ByteArray = byteArrayOf(field.size.toByte()) + field + rest

        private fun unframed(bytes: ByteArray): Pair<ByteArray, ByteArray>? {
            if (bytes.isEmpty()) return null
            val length = bytes[0].toInt() and 0xFF
            if (1 + length > bytes.size) return null
            return bytes.copyOfRange(1, 1 + length) to bytes.copyOfRange(1 + length, bytes.size)
        }

        private fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)

        private companion object {
            const val CHECK_BYTES = 4
        }
    }

    private companion object {
        const val ACCOUNT = "user-1"
        const val OTHER_ACCOUNT = "user-2"

        /** The account's address keys, and one of another account. */
        const val ALICE = "alice"
        const val BOB = "bob"
        const val MALLORY = "mallory"

        const val CREATED_AT = 1_758_600_000_000L
        const val IMPORTED_AT = 1_758_700_000_000L

        /** The largest error rounding to half precision leaves on a component in -1..1: half a unit in the
         *  last place of 0.5..1. */
        const val HALF_PRECISION = 1f / 4096

        /** Bytes of a SEARCH_EMBEDDING record that carries a vector: tag, key reference, flags and halves. */
        const val VECTOR_RECORD_BYTES = 1 + 4 + 1 + SEARCH_EMBEDDING_DIM * 2

        val AUTHOR = SearchSnapshotAuthor("0f8fad5b-d9cb-469f-a165-70867728950e", "Test phone")
    }
}
