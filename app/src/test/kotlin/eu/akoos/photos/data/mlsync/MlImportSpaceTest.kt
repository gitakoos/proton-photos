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
import android.database.sqlite.SQLiteConstraintException
import android.database.sqlite.SQLiteFullException
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import eu.akoos.photos.data.db.AppDatabase
import eu.akoos.photos.data.db.dao.ImageEmbeddingDao
import eu.akoos.photos.data.db.entity.FaceEntity
import eu.akoos.photos.data.db.entity.FaceScanEntity
import eu.akoos.photos.data.db.entity.ImageEmbeddingEntity
import eu.akoos.photos.data.db.entity.PersonCoverEntity
import eu.akoos.photos.data.db.entity.PersonEntity
import eu.akoos.photos.data.face.FaceModelAssets
import eu.akoos.photos.data.face.FaceReattachSnapshot
import eu.akoos.photos.data.preferences.SettingsKeys
import eu.akoos.photos.data.preferences.settingsDataStore
import eu.akoos.photos.data.semantic.SEMANTIC_MODEL_VERSION
import eu.akoos.photos.domain.usecase.FACE_EMBEDDING_DIM
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import me.proton.core.domain.entity.UserId
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.Random

/**
 * Storage running short under a snapshot import. The estimate scales the verified plaintext by its kind's
 * factor and adds a fixed margin; a face or search import that does not fit is refused before it writes a
 * row, once the sealed download has been released; one that just fits goes through; and a face import whose
 * database fills up part-way rolls back whole and reads as not enough space, leaving a database that takes
 * the same import once there is room.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MlImportSpaceTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var context: Context
    private lateinit var source: AppDatabase
    private lateinit var target: AppDatabase
    private val user = UserId(ACCOUNT)
    private val crypto = FakeCrypto()

    @Before
    fun setUp() = runBlocking {
        context = ApplicationProvider.getApplicationContext()
        clearSettings()
        reattachFile().delete()
        source = database()
        target = database()
    }

    @After
    fun tearDown() = runBlocking {
        source.close()
        target.close()
        clearSettings()
        reattachFile().delete()
        Unit
    }

    private fun database(): AppDatabase =
        Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .setQueryExecutor { it.run() }
            .build()

    // The `by preferencesDataStore` instance is a process-wide singleton, so every case starts clean.
    private suspend fun clearSettings() {
        runCatching { context.settingsDataStore.edit { it.clear() } }
    }

    private suspend fun <T> settings(key: Preferences.Key<T>): T? = context.settingsDataStore.data.first()[key]

    private fun reattachFile(): File = FaceReattachSnapshot.file(File(context.filesDir, FaceModelAssets.DIRECTORY))

    private fun scratchFiles(): Set<String> =
        File(context.cacheDir, FaceSnapshotScratch.DIRECTORY).list().orEmpty().toSet()

    // Fresh key bytes on every call, as closing the keys wipes them.
    private fun sealKeys() = EnvelopeSealKeys(PUBLIC_KEY, privateKey())

    private fun openKeys() = EnvelopeOpenKeys(listOf(privateKey()), listOf(PUBLIC_KEY))

    private fun sealing() = FaceSnapshotSealing(
        crypto = crypto,
        sealKeysFor = { sealKeys() },
        openKeysFor = { _, _ -> openKeys() },
        deviceFor = { FaceSnapshotDevice(DEVICE_ID, "Test phone") },
    )

    /** A face importer into [target] that reads [free] bytes free on the database's volume. */
    private fun faceImporter(free: Long?) = FaceSnapshotImporter(context, target, sealing(), freeBytes = { free })

    private fun embedding(seed: Int) = ByteArray(FACE_EMBEDDING_DIM * Float.SIZE_BYTES) { (it * 31 + seed * 7).toByte() }

    private fun face(id: String, photoKey: String, seed: Int, personId: Long? = null, manualName: String? = null) =
        FaceEntity(
            id = id,
            userId = ACCOUNT,
            photoKey = photoKey,
            left = 0.1f + seed / 100f,
            top = 0.1f,
            right = 0.3f + seed / 100f,
            bottom = 0.4f,
            landmarks = LANDMARKS,
            embedding = embedding(seed),
            personId = personId,
            score = 0.8f + seed / 100f,
            manualName = manualName,
        )

    /** Three faces on three scanned cloud photos, two of them a named person's. */
    private suspend fun seedSource() {
        source.personDao().upsert(PersonEntity(id = 1, userId = ACCOUNT, displayName = "Alice", faceCount = 2))
        source.faceDao().upsert(
            listOf(
                face("linkA#0", "linkA", seed = 1, personId = 1, manualName = "Alice"),
                face("linkB#0", "linkB", seed = 2, personId = 1),
                face("linkC#0", "linkC", seed = 3),
            ),
        )
        source.faceScanDao().upsert(
            listOf("linkA", "linkB", "linkC").map { FaceScanEntity(ACCOUNT, it) },
        )
    }

    /** What the restoring device holds before: one named face on a cloud photo, a Replace clears it. */
    private suspend fun seedTarget() {
        target.personDao().upsert(PersonEntity(id = 50, userId = ACCOUNT, displayName = "Zed", faceCount = 1))
        target.faceDao().upsert(listOf(face("linkQ#0", "linkQ", seed = 40, personId = 50, manualName = "Zed")))
        target.faceScanDao().upsert(listOf(FaceScanEntity(ACCOUNT, "linkQ")))
        target.personCoverDao().set(PersonCoverEntity(ACCOUNT, "Zed", "linkQ"))
    }

    private suspend fun exportFaces(): Pair<FaceSnapshotExport, ByteArray> {
        val out = ByteArrayOutputStream()
        val exported = FaceSnapshotExporter(context, source, sealing()).export(user, out)
        return exported to out.toByteArray()
    }

    private suspend fun targetFaceIds(): Set<String> =
        target.faceDao().observeFacesForUser(ACCOUNT).first().mapTo(HashSet()) { it.id }

    /** The target's face data, in a form two readings compare by. */
    private suspend fun faceContents(): List<Set<Any?>> = listOf(
        target.faceDao().observeFacesForUser(ACCOUNT).first().mapTo(HashSet()) { it.id to it.manualName },
        target.faceScanDao().markersAfter(ACCOUNT, "", 1000).mapTo(HashSet()) { it.photoKey },
        target.personDao().allForUser(ACCOUNT).mapTo(HashSet()) { it.displayName },
        target.personCoverDao().allForUser(ACCOUNT).mapTo(HashSet()) { it.personName to it.photoKey },
    )

    private fun setMaxPageCount(pages: Long) {
        target.openHelper.writableDatabase.query("PRAGMA max_page_count = $pages").use { it.moveToFirst() }
    }

    // ---- The estimate --------------------------------------------------------------------------------------

    @Test
    fun `the estimate scales the plaintext by its factor and adds the margin`() {
        assertEquals(IMPORT_SPACE_MARGIN_BYTES, importSpaceNeeded(0L, FACE_IMPORT_SPACE_FACTOR))
        assertEquals(IMPORT_SPACE_MARGIN_BYTES, importSpaceNeeded(-10L, FACE_IMPORT_SPACE_FACTOR))
        assertEquals(250L * MIB + IMPORT_SPACE_MARGIN_BYTES, importSpaceNeeded(100L * MIB, FACE_IMPORT_SPACE_FACTOR))
        assertEquals(400L * MIB + IMPORT_SPACE_MARGIN_BYTES, importSpaceNeeded(100L * MIB, SEARCH_IMPORT_SPACE_FACTOR))
        // A part of a byte rounds up.
        assertEquals(3L + IMPORT_SPACE_MARGIN_BYTES, importSpaceNeeded(1L, FACE_IMPORT_SPACE_FACTOR))
        assertEquals(50L * MIB, IMPORT_SPACE_MARGIN_BYTES)
    }

    @Test
    fun `an import fits from the estimate up, and free space that cannot be read never refuses`() {
        val needed = importSpaceNeeded(PLAINTEXT, FACE_IMPORT_SPACE_FACTOR)

        assertTrue(importFits(PLAINTEXT, FACE_IMPORT_SPACE_FACTOR, needed))
        assertTrue(importFits(PLAINTEXT, FACE_IMPORT_SPACE_FACTOR, Long.MAX_VALUE))
        assertFalse(importFits(PLAINTEXT, FACE_IMPORT_SPACE_FACTOR, needed - 1))
        assertFalse(importFits(PLAINTEXT, FACE_IMPORT_SPACE_FACTOR, 0L))
        assertTrue(importFits(PLAINTEXT, FACE_IMPORT_SPACE_FACTOR, null))
        // Search rows grow more once stored, so the same plaintext asks for more.
        assertFalse(importFits(PLAINTEXT, SEARCH_IMPORT_SPACE_FACTOR, needed))
    }

    @Test
    fun `storage running full is told apart from other failures, along the causes too`() {
        val enospc = IOException("write failed: ENOSPC (No space left on device)")

        assertTrue(isStorageFull(SQLiteFullException("database or disk is full")))
        assertTrue(isStorageFull(enospc))
        assertTrue(isStorageFull(IllegalStateException("import stopped", enospc)))
        assertTrue(isStorageFull(IOException("wrapped", SQLiteFullException("database or disk is full"))))
        assertFalse(isStorageFull(IOException("read failed: EIO (I/O error)")))
        assertFalse(isStorageFull(SQLiteConstraintException("UNIQUE constraint failed")))
        assertFalse(isStorageFull(MlSnapshotFormatException("record out of place")))
    }

    // ---- Face import ---------------------------------------------------------------------------------------

    @Test
    fun `a face import that does not fit writes nothing, after the download is released`() = runTest {
        seedSource()
        seedTarget()
        val (exported, sealed) = exportFaces()
        val needed = importSpaceNeeded(exported.plaintextBytes, FACE_IMPORT_SPACE_FACTOR)
        val before = faceContents()
        val scratchBefore = scratchFiles()
        var released = 0

        val refused = faceImporter(free = needed - 1)
            .import(user, ByteArrayInputStream(sealed), FaceSnapshotMode.Replace) { released++ }

        assertEquals(FaceSnapshotImportResult.NotEnoughSpace, refused)
        assertEquals("the verified download was let go before the check", 1, released)
        assertEquals(before, faceContents())
        assertNull(settings(SettingsKeys.FACE_MODEL_VERSION_KEY))
        assertEquals("no plaintext is left behind", scratchBefore, scratchFiles())

        val taken = faceImporter(free = needed)
            .import(user, ByteArrayInputStream(sealed), FaceSnapshotMode.Replace) { released++ }

        assertEquals(
            FaceSnapshotImportResult.Imported(
                faces = 3,
                facesUpdated = 0,
                people = 1,
                scanMarkers = 3,
                regroupRequested = false,
            ),
            taken,
        )
        assertEquals(2, released)
        assertEquals(setOf("linkA#0", "linkB#0", "linkC#0"), targetFaceIds())
    }

    @Test
    fun `a face snapshot that fails to open is not released and reads as unreadable, not as no space`() = runTest {
        seedSource()
        val (_, sealed) = exportFaces()
        val cut = ByteArrayInputStream(sealed.copyOf(sealed.size - 1))
        var released = false

        val result = faceImporter(free = 0L).import(user, cut, FaceSnapshotMode.Replace) { released = true }

        assertEquals(FaceSnapshotImportResult.Unreadable, result)
        assertFalse(released)
    }

    @Test
    fun `storage filling up while the faces are written rolls the whole import back and reads as no space`() = runTest {
        seedSource()
        seedTarget()
        val (_, sealed) = exportFaces()
        val before = faceContents()
        // The database may not grow by one more page, so the first write that needs one fails as a full
        // disk does, after the Replace has already cleared the cloud photo's face.
        setMaxPageCount(1)

        val result = faceImporter(free = null).import(user, ByteArrayInputStream(sealed), FaceSnapshotMode.Replace)

        assertEquals(FaceSnapshotImportResult.NotEnoughSpace, result)
        assertEquals(before, faceContents())
        assertNull(settings(SettingsKeys.FACE_MODEL_VERSION_KEY))

        // With room again the same file imports whole into the database the rollback left.
        setMaxPageCount(DEFAULT_MAX_PAGE_COUNT)
        val taken = faceImporter(free = null).import(user, ByteArrayInputStream(sealed), FaceSnapshotMode.Replace)
        assertTrue("imported: $taken", taken is FaceSnapshotImportResult.Imported)
        assertEquals(setOf("linkA#0", "linkB#0", "linkC#0"), targetFaceIds())
    }

    // ---- Search import -------------------------------------------------------------------------------------

    /** A unit vector along one axis, packed the way the index stores it. */
    private fun vector(seed: Int): ByteArray {
        val buffer = ByteBuffer.allocate(SEARCH_EMBEDDING_DIM * Float.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until SEARCH_EMBEDDING_DIM) buffer.putFloat(if (i == seed % SEARCH_EMBEDDING_DIM) 1f else 0f)
        return buffer.array()
    }

    private fun searchPlaintext(rows: Map<String, ByteArray>): ByteArray {
        val output = ByteArrayOutputStream()
        val header = MlSnapshotHeader.Search(
            DEVICE_ID, "Other phone", 1_000L, SEARCH_MODEL_ID, SEARCH_EMBEDDING_DIM, rows.size, rows.size,
        )
        val writer = MlSnapshotWriter(output, header)
        rows.keys.forEach { writer.write(MlSnapshotRecord.PhotoKey(it)) }
        rows.forEach { (key, blob) -> writer.write(MlSnapshotRecord.SearchEmbedding(key, blob)) }
        writer.finish()
        return output.toByteArray()
    }

    private fun seal(plaintext: ByteArray): ByteArray {
        val output = ByteArrayOutputStream()
        MlSnapshotEnvelope.seal(ByteArrayInputStream(plaintext), output, crypto, sealKeys())
        return output.toByteArray()
    }

    @Test
    fun `a search import that does not fit writes no chunk, after the download is released`() = runTest {
        val dao = target.imageEmbeddingDao()
        dao.upsert(ImageEmbeddingEntity(ACCOUNT, "link-9", vector(9), SEMANTIC_MODEL_VERSION, indexedAt = 1_000L))
        val plaintext = searchPlaintext((1..3).associate { "link-$it" to vector(it) })
        val sealed = seal(plaintext)
        val needed = importSpaceNeeded(plaintext.size.toLong(), SEARCH_IMPORT_SPACE_FACTOR)
        val chunks = mutableListOf<Int>()
        val recorder = object : ImageEmbeddingDao by dao {
            override suspend fun upsert(rows: List<ImageEmbeddingEntity>) {
                chunks += rows.size
                dao.upsert(rows)
            }
        }
        val scratchDir = temp.newFolder("scratch")
        var released = 0

        suspend fun importWith(free: Long): SearchSnapshotImportResult =
            SearchSnapshotImportEngine(target, recorder, crypto, scratchDir, chunkSize = 2, freeBytes = { free }).run(
                ACCOUNT,
                SearchImportMode.Replace,
                { openKeys() },
                { ByteArrayInputStream(sealed) },
                releaseSnapshot = { released++ },
            )

        assertEquals(SearchSnapshotImportResult.Failed(SearchImportFailure.NO_SPACE), importWith(needed - 1))
        assertEquals("the verified download was let go before the check", 1, released)
        assertEquals("not one chunk was written", emptyList<Int>(), chunks)
        assertEquals(setOf("link-9"), dao.allEmbeddingsForUser(ACCOUNT).mapTo(HashSet()) { it.photoKey })
        assertEquals("the plaintext scratch files are gone", 0, scratchDir.listFiles()!!.size)

        assertEquals(SearchSnapshotImportResult.Imported(imported = 3, kept = 0), importWith(needed))
        assertEquals(listOf(2, 1), chunks)
        assertEquals(
            setOf("link-1", "link-2", "link-3", "link-9"),
            dao.allEmbeddingsForUser(ACCOUNT).mapTo(HashSet()) { it.photoKey },
        )
    }

    /**
     * A reversible stand-in for OpenPGP, as the round-trip tests use: a key is a name, a key packet names
     * its recipient, a chunk is the plaintext XOR the session key behind a 4-byte check of that key, and a
     * signature is SHA-256 over the signer's name and the data.
     */
    private class FakeCrypto : EnvelopeCrypto {
        private val random = Random(13)

        override fun generateSessionKey(): ByteArray = ByteArray(16).also { random.nextBytes(it) }

        override fun wrapSessionKey(sessionKey: ByteArray, recipientPublicKey: String): ByteArray =
            framed(recipientPublicKey.removePrefix("pub:").toByteArray(Charsets.UTF_8), sessionKey)

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

        override fun sign(data: ByteArray, signingKey: ByteArray): ByteArray = framed(signingKey, sha256(signingKey + data))

        override fun verify(data: ByteArray, signature: ByteArray, verificationKeys: List<String>): Boolean {
            val (signer, tag) = unframed(signature) ?: return false
            return "pub:${String(signer, Charsets.UTF_8)}" in verificationKeys && tag.contentEquals(sha256(signer + data))
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
        const val ACCOUNT = "acct"
        const val DEVICE_ID = "0b5f3c2e-1111-4a4a-9c9c-123456789abc"
        const val PUBLIC_KEY = "pub:alice"

        const val LANDMARKS = "1.0,2.0;3.0,4.0;5.0,6.0;7.0,8.0;9.0,10.0"

        const val MIB = 1024L * 1024

        /** A plaintext size for the estimate's cases. */
        const val PLAINTEXT = 40L * MIB

        /** SQLite's own page limit, which lifts the one a case set. */
        const val DEFAULT_MAX_PAGE_COUNT = 1_073_741_823L

        fun privateKey(): ByteArray = "alice".toByteArray(Charsets.UTF_8)
    }
}
