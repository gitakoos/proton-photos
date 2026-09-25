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
import androidx.datastore.preferences.core.edit
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import eu.akoos.photos.data.db.AppDatabase
import eu.akoos.photos.data.db.dao.FaceDao
import eu.akoos.photos.data.db.entity.FaceEntity
import eu.akoos.photos.data.db.entity.FaceScanEntity
import eu.akoos.photos.data.db.entity.NotPersonEntity
import eu.akoos.photos.data.db.entity.PersonCoverEntity
import eu.akoos.photos.data.db.entity.PersonEntity
import eu.akoos.photos.data.db.entity.PersonManualPhotoEntity
import eu.akoos.photos.data.face.FaceModelAssets
import eu.akoos.photos.data.face.FaceReattachSnapshot
import eu.akoos.photos.data.preferences.settingsDataStore
import eu.akoos.photos.domain.usecase.FACE_EMBEDDING_DIM
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import me.proton.core.domain.entity.UserId
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.Random

/**
 * The face export's two read passes raced against the index, which no transaction holds still between
 * them, on the round-trip test's library: a face or scan marker added between the plan and the rows waits
 * for the next export; a planned face or marker that goes, or a name that moves, starts the export over
 * from a fresh plan; and an index that changes under every attempt fails the export after three without
 * sealing anything. Each file is imported into a second database to show it reads back whole.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FaceSnapshotExportRetryTest {

    private lateinit var context: Context
    private lateinit var source: AppDatabase
    private lateinit var target: AppDatabase
    private val user = UserId(ACCOUNT)

    /** Write passes the export has begun, one per attempt. */
    private var writePasses = 0

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

    private fun reattachFile(): File = FaceReattachSnapshot.file(File(context.filesDir, FaceModelAssets.DIRECTORY))

    private fun scratchFiles(): Set<String> =
        File(context.cacheDir, FaceSnapshotScratch.DIRECTORY).list().orEmpty().toSet()

    private fun sealing() = FaceSnapshotSealing(
        crypto = FakeCrypto(),
        sealKeysFor = { EnvelopeSealKeys(publicKey(ALICE), privateKey(ALICE)) },
        openKeysFor = { _, _ -> EnvelopeOpenKeys(listOf(privateKey(ALICE)), listOf(publicKey(ALICE))) },
        deviceFor = { FaceSnapshotDevice(DEVICE_ID, "Test phone") },
    )

    /**
     * [source]'s exporter, reading the face table through a stand-in that runs [betweenPasses] as each
     * attempt's write pass asks for its first page, after that attempt's plan: the indexer or the user
     * changing the index between the two passes.
     */
    private fun racingExporter(betweenPasses: suspend (attempt: Int) -> Unit): FaceSnapshotExporter {
        val dao = source.faceDao()
        val racing = object : FaceDao by dao {
            override suspend fun facesAfter(userId: String, afterId: String, limit: Int): List<FaceEntity> {
                if (afterId.isEmpty()) betweenPasses(++writePasses)
                return dao.facesAfter(userId, afterId, limit)
            }
        }
        return FaceSnapshotExporter(context, source, sealing(), racing)
    }

    private suspend fun export(exporter: FaceSnapshotExporter): Pair<FaceSnapshotExport, ByteArray> {
        val out = ByteArrayOutputStream()
        val exported = exporter.export(user, out)
        return exported to out.toByteArray()
    }

    private suspend fun import(sealed: ByteArray): FaceSnapshotImportResult =
        FaceSnapshotImporter(context, target, sealing()).import(user, ByteArrayInputStream(sealed), FaceSnapshotMode.Replace)

    /** A Replace import's result on the empty target, which never updates a face already there. */
    private fun expectedImport(faces: Int, people: Int = 2, scanMarkers: Int = 5) =
        FaceSnapshotImportResult.Imported(faces, facesUpdated = 0, people = people, scanMarkers = scanMarkers, regroupRequested = false)

    private fun embedding(seed: Int) = ByteArray(FACE_EMBEDDING_DIM * Float.SIZE_BYTES) { (it * 31 + seed * 7).toByte() }

    private fun face(
        id: String,
        photoKey: String,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        seed: Int,
        personId: Long? = null,
        manualName: String? = null,
        rejected: Boolean = false,
        blur: Float? = null,
        landmarks: String = PLAIN_LANDMARKS,
    ) = FaceEntity(
        id = id,
        userId = ACCOUNT,
        photoKey = photoKey,
        left = left,
        top = top,
        right = right,
        bottom = bottom,
        landmarks = landmarks,
        embedding = embedding(seed),
        personId = personId,
        score = 0.80f + seed / 100f,
        blur = blur,
        rejected = rejected,
        manualName = manualName,
    )

    /** The round-trip test's library: two named people, an unnamed group, a removed face, a device-only
     *  face, a face-free scanned photo, a manual photo, "not this person" marks and a chosen cover. */
    private suspend fun seedSource() {
        source.personDao().upsert(PersonEntity(id = 1, userId = ACCOUNT, displayName = "Alice", faceCount = 2))
        source.personDao().upsert(PersonEntity(id = 2, userId = ACCOUNT, displayName = null, faceCount = 1))
        source.personDao().upsert(PersonEntity(id = 3, userId = ACCOUNT, displayName = "Carol", faceCount = 1))
        source.faceDao().upsert(
            listOf(
                face("linkA#0", "linkA", 0.10f, 0.10f, 0.30f, 0.40f, seed = 1, personId = 1, manualName = "Alice", blur = 31.5f),
                face("linkA#1", "linkA", 0.60f, 0.10f, 0.80f, 0.40f, seed = 2, rejected = true),
                face("linkB#0", "linkB", 0.50f, 0.20f, 0.70f, 0.50f, seed = 3, personId = 1, landmarks = LANDMARKS),
                face("linkC#0", "linkC", 0.20f, 0.20f, 0.40f, 0.50f, seed = 4, personId = 2),
                face("linkG#0", "linkG", 0.30f, 0.30f, 0.50f, 0.60f, seed = 5, personId = 3, manualName = "Carol"),
                face("content://media/1#0", "content://media/1", 0.1f, 0.1f, 0.2f, 0.2f, seed = 6, personId = 1, manualName = "Alice"),
            ),
        )
        source.faceScanDao().upsert(
            listOf(
                FaceScanEntity(ACCOUNT, "linkA"),
                FaceScanEntity(ACCOUNT, "linkB"),
                FaceScanEntity(ACCOUNT, "linkC"),
                FaceScanEntity(ACCOUNT, "linkD", hiResScanned = true),
                FaceScanEntity(ACCOUNT, "linkG"),
                FaceScanEntity(ACCOUNT, "content://media/1"),
            ),
        )
        source.personManualPhotoDao().add(
            listOf(
                PersonManualPhotoEntity(ACCOUNT, "Alice", "linkE"),
                PersonManualPhotoEntity(ACCOUNT, "Alice", "content://media/2"),
            ),
        )
        source.notPersonDao().add(
            listOf(
                NotPersonEntity(ACCOUNT, "Bob", "linkA#0"),
                NotPersonEntity(ACCOUNT, "Alice", "linkC#0"),
                NotPersonEntity(ACCOUNT, "Alice", "linkZ#0"),
            ),
        )
        source.personCoverDao().set(PersonCoverEntity(ACCOUNT, "Alice", "linkB"))
        source.personCoverDao().set(PersonCoverEntity(ACCOUNT, "Dave", "content://media/3"))
    }

    /** Renames a person on [source] the way the person page does: the person, the curation and confirmed
     *  faces under the old name, and every face grouped under the person confirmed under the new one. */
    private suspend fun renamePerson(personId: Long, old: String, new: String) {
        source.personDao().updateName(personId, new)
        source.personManualPhotoDao().rename(ACCOUNT, old, new)
        source.faceDao().renameManualName(ACCOUNT, old, new)
        source.notPersonDao().rename(ACCOUNT, old, new)
        source.personCoverDao().rename(ACCOUNT, old, new)
        source.faceDao().labelFacesByIds(source.faceDao().faceIdsForPerson(ACCOUNT, personId), new)
    }

    private suspend fun faces(db: AppDatabase = target): Map<String, FaceEntity> =
        db.faceDao().observeFacesForUser(ACCOUNT).first().associateBy { it.id }

    private suspend fun people(): Map<String?, PersonEntity> =
        target.personDao().allForUser(ACCOUNT).associateBy { it.displayName }

    private suspend fun markers(): Set<Pair<String, Boolean>> =
        target.faceScanDao().markersAfter(ACCOUNT, "", 1000).mapTo(HashSet()) { it.photoKey to it.hiResScanned }

    private suspend fun manualPhotos(): Set<Pair<String, String>> =
        target.personManualPhotoDao().allForUser(ACCOUNT).mapTo(HashSet()) { it.personName to it.photoKey }

    private suspend fun notPersons(): Set<Pair<String, String>> =
        target.notPersonDao().allForUser(ACCOUNT).mapTo(HashSet()) { it.personName to it.faceId }

    private suspend fun covers(): Set<Pair<String, String>> =
        target.personCoverDao().allForUser(ACCOUNT).mapTo(HashSet()) { it.personName to it.photoKey }

    /** The imported face is the source face as it stands, apart from the person id, which is local. */
    private fun assertSameFace(expected: FaceEntity, actual: FaceEntity) {
        assertEquals(expected.id, actual.id)
        assertEquals(expected.photoKey, actual.photoKey)
        assertEquals(expected.left, actual.left, 0f)
        assertEquals(expected.top, actual.top, 0f)
        assertEquals(expected.right, actual.right, 0f)
        assertEquals(expected.bottom, actual.bottom, 0f)
        assertEquals(expected.landmarks, actual.landmarks)
        assertArrayEquals(expected.embedding, actual.embedding)
        assertEquals(expected.score, actual.score, 0f)
        assertEquals(expected.blur, actual.blur)
        assertEquals(expected.rejected, actual.rejected)
        assertEquals(expected.manualName, actual.manualName)
    }

    @Test
    fun `a face and a scan marker added between the passes wait for the next export, and the file imports whole`() = runTest {
        seedSource()
        val exporter = racingExporter { attempt ->
            if (attempt == 1) {
                // The walk finds a new photo, and a second face on a photo the plan already holds, under a
                // name its table lacks; it also marks the new photo scanned, and linkE, whose key the table
                // holds for a manual photo but with no marker.
                source.faceDao().upsert(
                    listOf(
                        face("linkN#0", "linkN", 0.1f, 0.1f, 0.3f, 0.3f, seed = 7, personId = 1, manualName = "Alice"),
                        face("linkA#2", "linkA", 0.35f, 0.1f, 0.5f, 0.4f, seed = 8, manualName = "Nora"),
                    ),
                )
                source.faceScanDao().upsert(listOf(FaceScanEntity(ACCOUNT, "linkN"), FaceScanEntity(ACCOUNT, "linkE")))
            }
        }

        val (exported, sealed) = export(exporter)

        assertEquals("the additions waited instead of starting over", 1, writePasses)
        assertEquals(5, exported.faces)
        assertEquals(2, exported.people)
        assertEquals(5, exported.scanMarkers)
        assertEquals(expectedImport(faces = 5), import(sealed))
        val imported = faces()
        assertEquals(setOf("linkA#0", "linkA#1", "linkB#0", "linkC#0", "linkG#0"), imported.keys)
        val original = faces(source)
        for (id in imported.keys) assertSameFace(original.getValue(id), imported.getValue(id))
        assertEquals(
            setOf("linkA" to false, "linkB" to false, "linkC" to false, "linkD" to true, "linkG" to false),
            markers(),
        )
        assertEquals(setOf("Alice", "Carol"), people().keys)
    }

    @Test
    fun `a planned face deleted between the passes starts the export over, and the file holds what is left`() = runTest {
        seedSource()
        val exporter = racingExporter { attempt ->
            if (attempt == 1) source.faceDao().deleteByPhotoKeys(ACCOUNT, listOf("linkC"))
        }

        val (exported, sealed) = export(exporter)

        assertEquals(2, writePasses)
        assertEquals(4, exported.faces)
        assertEquals(expectedImport(faces = 4), import(sealed))
        val imported = faces()
        assertEquals(setOf("linkA#0", "linkA#1", "linkB#0", "linkG#0"), imported.keys)
        val original = faces(source)
        for (id in imported.keys) assertSameFace(original.getValue(id), imported.getValue(id))
        // The "not Alice" mark stays behind with its face; linkC's scan marker still travels.
        assertEquals(setOf("Bob" to "linkA#0"), notPersons())
        assertEquals(
            setOf("linkA" to false, "linkB" to false, "linkC" to false, "linkD" to true, "linkG" to false),
            markers(),
        )
        assertEquals(setOf("Alice" to "linkE"), manualPhotos())
        assertEquals(setOf("Alice" to "linkB"), covers())
    }

    @Test
    fun `a planned scan marker deleted between the passes starts the export over`() = runTest {
        seedSource()
        val exporter = racingExporter { attempt ->
            if (attempt == 1) source.faceScanDao().deleteByPhotoKeys(ACCOUNT, listOf("linkD"))
        }

        val (exported, sealed) = export(exporter)

        assertEquals(2, writePasses)
        assertEquals(4, exported.scanMarkers)
        assertEquals(expectedImport(faces = 5, scanMarkers = 4), import(sealed))
        assertEquals(setOf("linkA" to false, "linkB" to false, "linkC" to false, "linkG" to false), markers())
    }

    @Test
    fun `a person renamed between the passes starts the export over under the new name`() = runTest {
        seedSource()
        val exporter = racingExporter { attempt ->
            if (attempt == 1) renamePerson(personId = 1, old = "Alice", new = "Alicia")
        }

        val (exported, sealed) = export(exporter)

        assertEquals(2, writePasses)
        assertEquals(5, exported.faces)
        assertEquals(expectedImport(faces = 5), import(sealed))
        val people = people()
        assertEquals(setOf("Alicia", "Carol"), people.keys)
        val imported = faces()
        for (id in listOf("linkA#0", "linkB#0")) {
            assertEquals(people.getValue("Alicia").id, imported.getValue(id).personId)
            assertEquals("Alicia", imported.getValue(id).manualName)
        }
        assertEquals(setOf("Alicia" to "linkE"), manualPhotos())
        assertEquals(setOf("Bob" to "linkA#0", "Alicia" to "linkC#0"), notPersons())
        assertEquals(setOf("Alicia" to "linkB"), covers())
    }

    @Test
    fun `a person renamed without touching its faces still starts the export over`() = runTest {
        seedSource()
        // Only the person row changes, so every face keeps names the planned table holds and the pass
        // itself reads nothing wrong; the person read that closes it does.
        val exporter = racingExporter { attempt ->
            if (attempt == 1) source.personDao().updateName(3, "Caroline")
        }

        val (_, sealed) = export(exporter)

        assertEquals(2, writePasses)
        assertEquals(expectedImport(faces = 5), import(sealed))
        val people = people()
        assertEquals(setOf("Alice", "Caroline"), people.keys)
        val linkG = faces().getValue("linkG#0")
        assertEquals(people.getValue("Caroline").id, linkG.personId)
        assertEquals("Carol", linkG.manualName)
    }

    @Test
    fun `an index that changes under every attempt fails after three and seals nothing`() = runTest {
        seedSource()
        // Each attempt plans a photo's faces and loses them before its write pass reaches them.
        val doomed = ArrayDeque(listOf("linkA", "linkC", "linkG"))
        val exporter = racingExporter { source.faceDao().deleteByPhotoKeys(ACCOUNT, listOf(doomed.removeFirst())) }
        val scratchBefore = scratchFiles()
        val out = ByteArrayOutputStream()

        val failure = runCatching { exporter.export(user, out) }.exceptionOrNull()

        assertTrue("failed with $failure", failure is FaceIndexChangedException)
        assertEquals(3, writePasses)
        assertEquals("nothing was sealed", 0, out.size())
        assertEquals("no plaintext is left behind", scratchBefore, scratchFiles())

        // Once the index holds still, the next export goes through with what is left.
        val (exported, sealed) = export(FaceSnapshotExporter(context, source, sealing()))
        assertEquals(1, exported.faces)
        assertEquals(expectedImport(faces = 1, people = 1), import(sealed))
        assertEquals(setOf("linkB#0"), faces().keys)
    }

    /**
     * A reversible stand-in for OpenPGP, the round-trip test's: a key is a name, a key packet names its
     * recipient, a chunk is the plaintext XOR the session key behind a 4-byte check of that key, and a
     * signature is SHA-256 over the signer's name and the data.
     */
    private class FakeCrypto : EnvelopeCrypto {
        private val random = Random(11)

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
            return publicKey(String(signer, Charsets.UTF_8)) in verificationKeys && tag.contentEquals(sha256(signer + data))
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
        const val ALICE = "alice"
        const val DEVICE_ID = "0b5f3c2e-1111-4a4a-9c9c-123456789abc"

        /** Five points in the exact form the indexer writes them. */
        const val LANDMARKS = "101.5,220.25;140.0,221.0;120.75,250.5;105.0,280.0;138.0,281.5"
        const val PLAIN_LANDMARKS = "1.0,2.0;3.0,4.0;5.0,6.0;7.0,8.0;9.0,10.0"

        fun publicKey(name: String): String = "pub:$name"

        fun privateKey(name: String): ByteArray = name.toByteArray(Charsets.UTF_8)
    }
}
