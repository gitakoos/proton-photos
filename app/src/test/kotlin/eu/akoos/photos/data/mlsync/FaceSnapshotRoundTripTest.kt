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
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import eu.akoos.photos.data.crypto.AddressSigningKey
import eu.akoos.photos.data.crypto.DriveCryptoHelper
import eu.akoos.photos.data.crypto.VerifiedBytes
import eu.akoos.photos.data.db.AppDatabase
import eu.akoos.photos.data.db.entity.FaceEntity
import eu.akoos.photos.data.db.entity.FaceScanEntity
import eu.akoos.photos.data.db.entity.NotPersonEntity
import eu.akoos.photos.data.db.entity.PersonCoverEntity
import eu.akoos.photos.data.db.entity.PersonEntity
import eu.akoos.photos.data.db.entity.PersonManualPhotoEntity
import eu.akoos.photos.data.face.FaceModelAssets
import eu.akoos.photos.data.face.FaceReattachData
import eu.akoos.photos.data.face.FaceReattachSnapshot
import eu.akoos.photos.data.face.ReattachBox
import eu.akoos.photos.data.face.ReattachManual
import eu.akoos.photos.data.preferences.SettingsKeys
import eu.akoos.photos.data.preferences.settingsDataStore
import eu.akoos.photos.domain.usecase.FACE_CLUSTER_PARAMS_VERSION
import eu.akoos.photos.domain.usecase.FACE_EMBEDDING_DIM
import eu.akoos.photos.domain.usecase.FACE_INDEX_VERSION_V3
import eu.akoos.photos.domain.usecase.FACE_MODEL_VERSION
import eu.akoos.photos.domain.usecase.FaceIndexImportOutcome
import eu.akoos.photos.domain.usecase.ImportFaceIndexUseCase
import eu.akoos.photos.domain.usecase.ParsedFace
import eu.akoos.photos.domain.usecase.ParsedPerson
import eu.akoos.photos.domain.usecase.accountTag
import eu.akoos.photos.domain.usecase.writeBody
import eu.akoos.photos.domain.usecase.writeFaceIndexEnvelope
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import me.proton.core.accountmanager.domain.AccountManager
import me.proton.core.domain.entity.UserId
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.Base64
import java.util.Random

private const val ACCOUNT = "acct"
private const val ALICE = "alice"
private const val BOB = "bob"
private const val DEVICE_ID = "0b5f3c2e-1111-4a4a-9c9c-123456789abc"

/** Five points in the exact form the indexer writes them. */
private const val LANDMARKS = "101.5,220.25;140.0,221.0;120.75,250.5;105.0,280.0;138.0,281.5"
private const val PLAIN_LANDMARKS = "1.0,2.0;3.0,4.0;5.0,6.0;7.0,8.0;9.0,10.0"

/**
 * A face index carried from one device to another through a sealed snapshot: exported from one in-memory
 * database and imported into another, through the real snapshot format and envelope, with the account's
 * OpenPGP keys stood in for by a reversible fake.
 *
 * The source library holds a named person with a confirmed and a merely grouped face, a removed face, an
 * unnamed group, a second named person, a device-only face that must not travel, a face-free photo's scan
 * marker, a manual photo, "not this person" marks and a chosen cover. A Replace brings it all across; a
 * Merge adds only the photos the target has not scanned and lands the rest on the target's own faces by
 * box overlap, the target's confirmed names winning; a snapshot of another face model brings the names
 * only. A photo in the hidden area never travels, a restore asks Merge or Replace unless the target is
 * fresh, and a snapshot another install wrote than its name says is refused. An import broken off part
 * way leaves the target as it was, forgetting an account takes all of its face data, and the Settings
 * entry point still reads a v3 file.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FaceSnapshotRoundTripTest {

    private lateinit var context: Context
    private lateinit var source: AppDatabase
    private lateinit var target: AppDatabase
    private val user = UserId(ACCOUNT)
    private val openedWithRefresh = ArrayList<Boolean>()

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

    private suspend fun settings(): Preferences = context.settingsDataStore.data.first()

    private fun reattachFile(): File = FaceReattachSnapshot.file(File(context.filesDir, FaceModelAssets.DIRECTORY))

    private fun sealing(opener: String = ALICE, refreshedOpener: String = opener) = FaceSnapshotSealing(
        crypto = FaceSnapshotTestCrypto(),
        sealKeysFor = { EnvelopeSealKeys(publicKey(ALICE), privateKey(ALICE)) },
        openKeysFor = { _, refresh ->
            openedWithRefresh += refresh
            val owner = if (refresh) refreshedOpener else opener
            EnvelopeOpenKeys(listOf(privateKey(owner)), listOf(publicKey(owner)))
        },
        deviceFor = { FaceSnapshotDevice(DEVICE_ID, "Test phone") },
    )

    private suspend fun export(db: AppDatabase = source): ByteArray {
        val out = ByteArrayOutputStream()
        FaceSnapshotExporter(context, db, sealing()).export(user, out)
        return out.toByteArray()
    }

    private suspend fun import(
        sealed: ByteArray,
        mode: FaceSnapshotMode,
        sealing: FaceSnapshotSealing = sealing(),
    ): FaceSnapshotImportResult = FaceSnapshotImporter(context, target, sealing).import(user, ByteArrayInputStream(sealed), mode)

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
                // A photo the indexer found no face on, already swept at the high resolution.
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
                // A mark on a face no longer in the index stays behind.
                NotPersonEntity(ACCOUNT, "Alice", "linkZ#0"),
            ),
        )
        source.personCoverDao().set(PersonCoverEntity(ACCOUNT, "Alice", "linkB"))
        source.personCoverDao().set(PersonCoverEntity(ACCOUNT, "Dave", "content://media/3"))
    }

    private suspend fun faces(db: AppDatabase = target): Map<String, FaceEntity> =
        db.faceDao().observeFacesForUser(ACCOUNT).first().associateBy { it.id }

    private suspend fun markers(): Set<Pair<String, Boolean>> =
        target.faceScanDao().markersAfter(ACCOUNT, "", 1000).mapTo(HashSet()) { it.photoKey to it.hiResScanned }

    private suspend fun manualPhotos(): Set<Pair<String, String>> =
        target.personManualPhotoDao().allForUser(ACCOUNT).mapTo(HashSet()) { it.personName to it.photoKey }

    private suspend fun notPersons(): Set<Pair<String, String>> =
        target.notPersonDao().allForUser(ACCOUNT).mapTo(HashSet()) { it.personName to it.faceId }

    private suspend fun covers(): Set<Pair<String, String>> =
        target.personCoverDao().allForUser(ACCOUNT).mapTo(HashSet()) { it.personName to it.photoKey }

    /** The imported face is the source face as it stood, apart from the person id, which is local. */
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
    fun `a replace carries faces, names, curation and every scan marker across`() = runTest {
        seedSource()
        // What the target held for its cloud photos gives way; its device-only face stays.
        target.personDao().upsert(PersonEntity(id = 50, userId = ACCOUNT, displayName = "Zed", faceCount = 1))
        target.faceDao().upsert(
            listOf(
                face("linkQ#0", "linkQ", 0.1f, 0.1f, 0.3f, 0.3f, seed = 40, personId = 50, manualName = "Zed"),
                face("content://media/9#0", "content://media/9", 0.1f, 0.1f, 0.3f, 0.3f, seed = 41, personId = 50, manualName = "Zed"),
            ),
        )
        target.faceScanDao().upsert(listOf(FaceScanEntity(ACCOUNT, "linkQ"), FaceScanEntity(ACCOUNT, "content://media/9")))
        target.personCoverDao().set(PersonCoverEntity(ACCOUNT, "Zed", "linkQ"))

        val result = import(export(), FaceSnapshotMode.Replace)

        assertEquals(
            FaceSnapshotImportResult.Imported(faces = 5, facesUpdated = 0, people = 2, scanMarkers = 5, regroupRequested = false),
            result,
        )
        val imported = faces()
        assertEquals(setOf("linkA#0", "linkA#1", "linkB#0", "linkC#0", "linkG#0", "content://media/9#0"), imported.keys)
        val original = faces(source)
        for (id in listOf("linkA#0", "linkA#1", "linkB#0", "linkC#0", "linkG#0")) {
            assertSameFace(original.getValue(id), imported.getValue(id))
        }
        assertEquals("Zed", imported.getValue("content://media/9#0").manualName)

        // The named people come back as groups, the removed face and the unnamed group wait ungrouped.
        val people = target.personDao().allForUser(ACCOUNT).associateBy { it.displayName }
        assertEquals(setOf("Alice", "Carol"), people.keys)
        val alice = people.getValue("Alice")
        assertEquals(alice.id, imported.getValue("linkA#0").personId)
        assertEquals(alice.id, imported.getValue("linkB#0").personId)
        assertNull(imported.getValue("linkA#1").personId)
        assertNull(imported.getValue("linkC#0").personId)
        assertEquals(people.getValue("Carol").id, imported.getValue("linkG#0").personId)
        // Photos linkA and linkB plus the manual linkE, and the cover on the chosen photo.
        assertEquals(3, alice.faceCount)
        assertEquals("linkB#0", alice.coverFaceId)

        assertEquals(
            setOf("linkA" to false, "linkB" to false, "linkC" to false, "linkD" to true, "linkG" to false, "content://media/9" to false),
            markers(),
        )
        assertEquals(setOf("Alice" to "linkE"), manualPhotos())
        assertEquals(setOf("Bob" to "linkA#0", "Alice" to "linkC#0"), notPersons())
        assertEquals(setOf("Alice" to "linkB"), covers())
        assertEquals(FACE_MODEL_VERSION, settings()[SettingsKeys.FACE_MODEL_VERSION_KEY])
    }

    @Test
    fun `a merge adds only unscanned photos and lands the rest by overlap, local confirmed names winning`() = runTest {
        seedSource()
        target.personDao().upsert(PersonEntity(id = 10, userId = ACCOUNT, displayName = "Ally", faceCount = 1))
        target.personDao().upsert(PersonEntity(id = 11, userId = ACCOUNT, displayName = null, faceCount = 1))
        target.faceDao().upsert(
            listOf(
                // The same face as the source's linkA#0, confirmed here under another name.
                face("linkA#0", "linkA", 0.10f, 0.10f, 0.30f, 0.40f, seed = 21, personId = 10, manualName = "Ally"),
                // The source's linkB#0, detected here under another index in a nearly equal box.
                face("linkB#3", "linkB", 0.52f, 0.20f, 0.70f, 0.50f, seed = 22, personId = 11),
                face("linkC#1", "linkC", 0.20f, 0.20f, 0.40f, 0.50f, seed = 23),
            ),
        )
        target.faceScanDao().upsert(
            listOf(FaceScanEntity(ACCOUNT, "linkA"), FaceScanEntity(ACCOUNT, "linkB"), FaceScanEntity(ACCOUNT, "linkC")),
        )
        target.personCoverDao().set(PersonCoverEntity(ACCOUNT, "Alice", "linkX"))

        val result = import(export(), FaceSnapshotMode.Merge)

        assertEquals(
            FaceSnapshotImportResult.Imported(faces = 1, facesUpdated = 1, people = 2, scanMarkers = 5, regroupRequested = false),
            result,
        )
        val merged = faces()
        // Only the photo this device had not scanned brought rows.
        assertEquals(setOf("linkA#0", "linkB#3", "linkC#1", "linkG#0"), merged.keys)
        assertEquals("Ally", merged.getValue("linkA#0").manualName)
        assertEquals("Alice", merged.getValue("linkB#3").manualName)
        assertNull(merged.getValue("linkC#1").manualName)
        assertSameFace(faces(source).getValue("linkG#0"), merged.getValue("linkG#0"))
        val carol = target.personDao().allForUser(ACCOUNT).single { it.displayName == "Carol" }
        assertEquals(carol.id, merged.getValue("linkG#0").personId)
        assertEquals(1, carol.faceCount)

        // The marks follow their faces onto the local faces they overlap.
        assertEquals(setOf("Bob" to "linkA#0", "Alice" to "linkC#1"), notPersons())
        // Every marker arrives, the face-free linkD's with its high-resolution flag.
        assertEquals(
            setOf("linkA" to false, "linkB" to false, "linkC" to false, "linkD" to true, "linkG" to false),
            markers(),
        )
        assertEquals(setOf("Alice" to "linkX"), covers())
        assertEquals(setOf("Alice" to "linkE"), manualPhotos())
    }

    @Test
    fun `a snapshot of another face model brings the names only, staged for the reattach path`() = runTest {
        seedSource()
        context.settingsDataStore.edit { it[SettingsKeys.FACE_MODEL_VERSION_KEY] = FACE_MODEL_VERSION - 1 }
        val sealed = export()
        clearSettings()

        val result = import(sealed, FaceSnapshotMode.Replace)

        assertEquals(FaceSnapshotImportResult.NamesStaged(people = 2, facesUpdated = 0), result)
        assertTrue(faces().isEmpty())
        // Every photo is left for this device's own model to detect.
        assertTrue(markers().isEmpty())
        assertEquals(setOf("Alice" to "linkB"), covers())
        assertEquals(setOf("Alice" to "linkE"), manualPhotos())
        val staged = FaceReattachSnapshot.read(reattachFile())
        assertNotNull(staged)
        assertEquals(
            setOf("linkA#0" to "Alice", "linkB#0" to "Alice", "linkG#0" to "Carol"),
            staged!!.members.mapTo(HashSet()) { it.faceId to it.name },
        )
        assertEquals(listOf(ReattachBox("linkA", 0.60f, 0.10f, 0.80f, 0.40f)), staged.rejected)
        assertEquals(setOf("linkA#0" to "Bob", "linkC#0" to "Alice"), staged.nots.mapTo(HashSet()) { it.faceId to it.name })
        assertEquals(FACE_MODEL_VERSION, settings()[SettingsKeys.FACE_MODEL_VERSION_KEY])
    }

    @Test
    fun `a key made since this device cached the account's keys opens on the refreshed retry`() = runTest {
        seedSource()
        val sealed = export()
        openedWithRefresh.clear()

        val result = import(sealed, FaceSnapshotMode.Replace, sealing(opener = BOB, refreshedOpener = ALICE))

        assertTrue(result is FaceSnapshotImportResult.Imported)
        assertEquals(listOf(false, true), openedWithRefresh)
        assertEquals(5, faces().size)
    }

    @Test
    fun `another account's snapshot is refused and changes nothing`() = runTest {
        seedSource()
        target.faceDao().upsert(listOf(face("linkQ#0", "linkQ", 0.1f, 0.1f, 0.3f, 0.3f, seed = 40)))
        val sealed = export()
        openedWithRefresh.clear()

        val result = import(sealed, FaceSnapshotMode.Replace, sealing(opener = BOB))

        assertEquals(FaceSnapshotImportResult.WrongAccount, result)
        assertEquals(listOf(false, true), openedWithRefresh)
        assertEquals(setOf("linkQ#0"), faces().keys)
    }

    @Test
    fun `an import broken off part way leaves the database as it was`() = runTest {
        seedSource()
        target.personDao().upsert(PersonEntity(id = 50, userId = ACCOUNT, displayName = "Zed", faceCount = 1))
        target.faceDao().upsert(listOf(face("linkQ#0", "linkQ", 0.1f, 0.1f, 0.3f, 0.3f, seed = 40, personId = 50)))
        target.faceScanDao().upsert(listOf(FaceScanEntity(ACCOUNT, "linkQ")))
        val plaintext = ByteArrayOutputStream().also { out ->
            FaceSnapshotExporter(context, source, sealing()).writePlaintext(
                user,
                out,
                FaceSnapshotOrigin(
                    device = FaceSnapshotDevice(DEVICE_ID, "Test phone"),
                    faceModelVersion = FACE_MODEL_VERSION,
                    faceModelSha256 = FaceModelAssets.EMBED_MODEL.sha256,
                    clusterParamsVersion = FACE_CLUSTER_PARAMS_VERSION,
                    createdAtMillis = 0L,
                ),
            )
        }.toByteArray()
        val survey = surveyFaceSnapshot(ByteArrayInputStream(plaintext))!!
        // Cut into the tail: every face is in, the markers and the end marker are not.
        val cut = plaintext.copyOf(plaintext.size - 40)

        // The Replace clears the cloud photos and writes the faces before the stream breaks; none of it stays.
        val failure = runCatching {
            target.withTransaction {
                FaceSnapshotApplier(target, ACCOUNT, FaceSnapshotMode.Replace, FaceSnapshotFit.Full, survey)
                    .run(MlSnapshotReader(ByteArrayInputStream(cut)))
            }
        }.exceptionOrNull()
        assertTrue(failure is MlSnapshotTruncatedException)
        assertUntouched()

        // The import itself reads a stream through to its end before writing anything.
        val file = File.createTempFile("cut", ".bin", context.cacheDir).apply { writeBytes(cut) }
        assertEquals(
            FaceSnapshotImportResult.Unreadable,
            FaceSnapshotImporter(context, target, sealing()).importPlaintext(user, file, FaceSnapshotMode.Replace),
        )
        assertUntouched()
        file.delete()
    }

    @Test
    fun `a database failure late in an import rolls every earlier write back`() = runTest {
        seedSource()
        target.personDao().upsert(PersonEntity(id = 50, userId = ACCOUNT, displayName = "Zed", faceCount = 1))
        target.faceDao().upsert(listOf(face("linkQ#0", "linkQ", 0.1f, 0.1f, 0.3f, 0.3f, seed = 40, personId = 50)))
        target.faceScanDao().upsert(listOf(FaceScanEntity(ACCOUNT, "linkQ")))
        // The markers come last in the stream, after the clear, the faces, the people and the curation.
        target.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER refuse_marker BEFORE INSERT ON face_scan WHEN NEW.photoKey = 'linkG' " +
                "BEGIN SELECT RAISE(ABORT, 'refused'); END",
        )

        val failure = runCatching { import(export(), FaceSnapshotMode.Replace) }.exceptionOrNull()

        assertNotNull(failure)
        assertUntouched()
        assertTrue(covers().isEmpty())
        assertTrue(manualPhotos().isEmpty())
        assertTrue(notPersons().isEmpty())
    }

    private suspend fun assertUntouched() {
        assertEquals(setOf("linkQ#0"), faces().keys)
        assertEquals(setOf("linkQ" to false), markers())
        assertEquals(listOf("Zed"), target.personDao().allForUser(ACCOUNT).map { it.displayName })
    }

    @Test
    fun `the settings import still reads a v3 file and hands a sealed snapshot to the new engine`() = runTest {
        val accountManager = mockk<AccountManager>()
        every { accountManager.getPrimaryUserId() } returns flowOf(user)
        val cryptoHelper = mockk<DriveCryptoHelper>()
        coEvery { cryptoHelper.getAddressSigningKey(user) } returns
            AddressSigningKey("owner@example.org", "address", byteArrayOf(7), "pub", "key")
        coEvery { cryptoHelper.getOwnPublicKeysArmored(user) } returns listOf("pub")
        every { cryptoHelper.decryptAndVerifyData(any<String>(), any<List<ByteArray>>(), any()) } answers {
            VerifiedBytes(Base64.getDecoder().decode(firstArg<String>()), verified = true)
        }
        val importFaceIndex = ImportFaceIndexUseCase(
            context,
            accountManager,
            cryptoHelper,
            target.faceDao(),
            target.faceScanDao(),
            target.personManualPhotoDao(),
            target.notPersonDao(),
            target.personCoverDao(),
            target,
            FaceSnapshotImporter(context, target, sealing()),
        )

        val v3 = v3File(
            faces = listOf(
                ParsedFace(
                    id = "linkV#0", photoKey = "linkV", left = 0.1f, top = 0.1f, right = 0.3f, bottom = 0.3f,
                    landmarks = PLAIN_LANDMARKS, score = 0.9f, blur = null, embedding = embedding(9), rejected = false,
                ),
            ),
            people = listOf(ParsedPerson("Vera", members = listOf("linkV#0"), manual = emptyList(), nots = emptyList(), cover = null)),
        )
        assertEquals(FaceIndexImportOutcome.Success(faces = 1, people = 1), importFaceIndex(ByteArrayInputStream(v3)))
        assertEquals("Vera", target.faceDao().faceById("linkV#0")?.manualName)
        assertTrue(target.faceScanDao().isScanned(ACCOUNT, "linkV"))

        // The current export goes through the same entry point. This device now holds a face of a cloud photo,
        // named or not, so an import that cannot ask merges: the file's faces join the one imported above.
        seedSource()
        assertEquals(FaceIndexImportOutcome.Success(faces = 5, people = 2), importFaceIndex(ByteArrayInputStream(export())))
        assertEquals(setOf("linkV#0", "linkA#0", "linkA#1", "linkB#0", "linkC#0", "linkG#0"), faces().keys)
        assertEquals("Vera", target.faceDao().faceById("linkV#0")?.manualName)
    }

    @Test
    fun `a face of a photo in the hidden area never travels, and a replace keeps this device's own`() = runTest {
        val hidden = "file:///data/user/0/eu.akoos.photos/files/hidden/IMG_0042.jpg"
        val hiddenHere = "file:///data/user/0/eu.akoos.photos/files/hidden/IMG_0077.jpg"
        source.faceDao().upsert(
            listOf(
                face("linkV#0", "linkV", 0.1f, 0.1f, 0.3f, 0.3f, seed = 1, manualName = "Vera"),
                face("$hidden#0", hidden, 0.1f, 0.1f, 0.3f, 0.3f, seed = 2, manualName = "Secret"),
            ),
        )
        source.faceScanDao().upsert(listOf(FaceScanEntity(ACCOUNT, "linkV"), FaceScanEntity(ACCOUNT, hidden)))
        source.personManualPhotoDao().add(listOf(PersonManualPhotoEntity(ACCOUNT, "Vera", hidden)))
        source.notPersonDao().add(listOf(NotPersonEntity(ACCOUNT, "Vera", "$hidden#0")))
        source.personCoverDao().set(PersonCoverEntity(ACCOUNT, "Secret", hidden))
        target.faceDao().upsert(listOf(face("$hiddenHere#0", hiddenHere, 0.2f, 0.2f, 0.4f, 0.4f, seed = 3, manualName = "Mine")))
        target.faceScanDao().upsert(listOf(FaceScanEntity(ACCOUNT, hiddenHere)))

        val result = import(export(), FaceSnapshotMode.Replace)

        assertEquals(
            FaceSnapshotImportResult.Imported(faces = 1, facesUpdated = 0, people = 1, scanMarkers = 1, regroupRequested = false),
            result,
        )
        assertEquals(setOf("linkV#0", "$hiddenHere#0"), faces().keys)
        assertEquals("Mine", faces().getValue("$hiddenHere#0").manualName)
        assertEquals(setOf("linkV" to false, hiddenHere to false), markers())
        assertTrue(manualPhotos().isEmpty())
        assertTrue(notPersons().isEmpty())
        assertTrue(covers().isEmpty())
    }

    @Test
    fun `a restore asks Merge or Replace unless this device is fresh`() = runTest {
        val importer = FaceSnapshotImporter(context, target, sealing())
        assertFalse(importer.needsModeChoice(user))
        // Faces of device photos, in the gallery or the hidden area, are not what a Replace clears.
        target.faceDao().upsert(
            listOf(
                face("content://media/9#0", "content://media/9", 0.1f, 0.1f, 0.3f, 0.3f, seed = 1, manualName = "Zed"),
                face("file:///hidden/a.jpg#0", "file:///hidden/a.jpg", 0.1f, 0.1f, 0.3f, 0.3f, seed = 2),
            ),
        )
        assertFalse(importer.needsModeChoice(user))
        // One face of a cloud photo asks, named or not.
        target.faceDao().upsert(listOf(face("linkQ#0", "linkQ", 0.1f, 0.1f, 0.3f, 0.3f, seed = 3)))
        assertTrue(importer.needsModeChoice(user))
        // So do names waiting to be put back, on a device with no face at all.
        target.faceDao().clearForUser(ACCOUNT)
        assertFalse(importer.needsModeChoice(user))
        FaceReattachSnapshot.write(
            reattachFile(),
            FaceReattachData(members = emptyList(), manual = listOf(ReattachManual("Vera", "linkV")), nots = emptyList()),
        )
        assertTrue(importer.needsModeChoice(user))
    }

    @Test
    fun `a snapshot another install wrote than the one its name carries is refused before anything is written`() = runTest {
        seedSource()
        val sealed = export()
        val importer = FaceSnapshotImporter(context, target, sealing())

        val refused = importer.import(
            user,
            ByteArrayInputStream(sealed),
            FaceSnapshotMode.Replace,
            expectedDeviceId = "7c9e6679-7425-40de-944b-e07fc1f90ae7",
        )

        assertEquals(FaceSnapshotImportResult.Unreadable, refused)
        assertTrue(faces().isEmpty())
        assertTrue(markers().isEmpty())
        assertNull(settings()[SettingsKeys.FACE_MODEL_VERSION_KEY])
        val taken = importer.import(user, ByteArrayInputStream(sealed), FaceSnapshotMode.Replace, expectedDeviceId = DEVICE_ID)
        assertTrue(taken is FaceSnapshotImportResult.Imported)
    }

    @Test
    fun `forgetting an account deletes its faces, people, curation and waiting names, and nothing else`() = runTest {
        seedSource()
        import(export(), FaceSnapshotMode.Replace)
        target.faceDao().upsert(
            listOf(
                FaceEntity(
                    id = "linkO#0", userId = "other", photoKey = "linkO",
                    left = 0.1f, top = 0.1f, right = 0.3f, bottom = 0.3f,
                    landmarks = PLAIN_LANDMARKS, embedding = embedding(8), personId = null,
                    score = 0.9f, blur = null, rejected = false, manualName = "Olga",
                ),
            ),
        )
        FaceReattachSnapshot.write(
            reattachFile(),
            FaceReattachData(members = emptyList(), manual = listOf(ReattachManual("Alice", "linkE")), nots = emptyList()),
        )

        FaceSnapshotImporter(context, target, sealing()).forgetAccount(user)

        assertTrue(faces().isEmpty())
        assertTrue(markers().isEmpty())
        assertTrue(target.personDao().allForUser(ACCOUNT).isEmpty())
        assertTrue(manualPhotos().isEmpty())
        assertTrue(notPersons().isEmpty())
        assertTrue(covers().isEmpty())
        assertFalse(reattachFile().exists())
        assertEquals(listOf("linkO#0"), target.faceDao().observeFacesForUser("other").first().map { it.id })
    }

    private fun v3File(faces: List<ParsedFace>, people: List<ParsedPerson>): ByteArray {
        val body = ByteArrayOutputStream().also { buffer ->
            DataOutputStream(buffer).use { writeBody(it, faces, people, FACE_INDEX_VERSION_V3) }
        }.toByteArray()
        return ByteArrayOutputStream().also { buffer ->
            DataOutputStream(buffer).use { out ->
                writeFaceIndexEnvelope(
                    out = out,
                    version = FACE_INDEX_VERSION_V3,
                    accountTag = accountTag(ACCOUNT),
                    modelFile = FaceModelAssets.EMBED_MODEL.fileName,
                    modelSha = FaceModelAssets.EMBED_MODEL.sha256,
                    bodyBytes = body,
                ) { plain -> Base64.getEncoder().encodeToString(plain) }
            }
        }.toByteArray()
    }
}

private fun publicKey(name: String): String = "pub:$name"

private fun privateKey(name: String): ByteArray = name.toByteArray(Charsets.UTF_8)

private fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)

/**
 * A reversible stand-in for OpenPGP, built like the envelope test's: a key is a name, a key packet names
 * its recipient, a chunk is the plaintext XOR the session key behind a 4-byte check of that key, and a
 * signature is SHA-256 over the signer's name and the data.
 */
private class FaceSnapshotTestCrypto : EnvelopeCrypto {
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

    private companion object {
        const val CHECK_BYTES = 4
    }
}
