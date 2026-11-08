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
import android.net.Uri
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.edit
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import eu.akoos.photos.data.db.AppDatabase
import eu.akoos.photos.data.preferences.SettingsKeys
import eu.akoos.photos.data.preferences.settingsDataStore
import eu.akoos.photos.data.repository.SyncStateRepositoryImpl
import eu.akoos.photos.data.transfer.TransferCenter
import eu.akoos.photos.data.upload.UploadCompressionStep
import eu.akoos.photos.data.upload.UploadImageCompressor
import eu.akoos.photos.data.upload.compression.VideoUploadCompression
import eu.akoos.photos.domain.entity.LocalMediaItem
import eu.akoos.photos.domain.entity.QueueSource
import eu.akoos.photos.domain.entity.SyncState
import eu.akoos.photos.domain.entity.SyncStatus
import eu.akoos.photos.domain.entity.compression.CompressionOutcome
import eu.akoos.photos.domain.entity.compression.CompressionSettings
import eu.akoos.photos.domain.entity.compression.CompressionSkipReason
import eu.akoos.photos.domain.repository.DrivePhotoRepository
import eu.akoos.photos.domain.repository.LocalMediaRepository
import eu.akoos.photos.util.AlbumListEventBus
import eu.akoos.photos.util.ExifHelper
import eu.akoos.photos.util.MetadataStripConfig
import eu.akoos.photos.util.Mp4CreationTime
import eu.akoos.photos.util.NetworkObserver
import eu.akoos.photos.util.StripResult
import eu.akoos.photos.util.VideoMetadataStripper
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import me.proton.core.domain.entity.UserId
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.security.MessageDigest

/**
 * Pins what an upload sends and what it records when the bytes are prepared on the way out: metadata
 * strip (in place, through a structural rewrite, as a video remux, as a HEIC transcode), compression,
 * the mvhd floor of an uncompressed video, and the temps each of them leaves. The device file is a
 * real file behind a `file://` uri, so the hash, the overwrite and the recorded length are the real
 * bytes; the heavy encoders are stubbed to write known bytes.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UploadBytePreparationTest {

    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var syncRepo: SyncStateRepositoryImpl
    private lateinit var local: LocalMediaRepository
    private lateinit var cloud: DrivePhotoRepository
    private lateinit var stripper: UploadStructuralStripper
    private lateinit var compression: UploadCompressionStep
    private lateinit var useCase: UploadPendingUseCase
    private lateinit var deviceDir: File
    private val appScope = CoroutineScope(Dispatchers.Unconfined + CoroutineExceptionHandler { _, _ -> })
    private val userId = UserId("test-user")

    /** What one uploadFile call was handed: the item, the hash, the uri and the bytes behind it then. */
    private data class Sent(val item: LocalMediaItem, val hash: String, val uri: String, val bytes: ByteArray)

    private val sent = mutableListOf<Sent>()
    private var uploadThrows: Exception? = null

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        runBlocking { context.settingsDataStore.edit { it.clear() } }
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .setQueryExecutor { it.run() }
            .build()
        syncRepo = SyncStateRepositoryImpl(db.syncStateDao())
        deviceDir = File(context.filesDir, "device").apply { deleteRecursively(); mkdirs() }
        context.cacheDir.listFiles()?.forEach { it.deleteRecursively() }

        local = mockk()
        cloud = mockk(relaxed = true)
        coEvery { cloud.loadAlbums(any()) } returns emptyList()
        coEvery { cloud.uploadFile(userId, any(), any(), any(), any(), any()) } answers {
            val uri = arg<String>(3)
            sent += Sent(arg(1), arg(2), uri, context.contentResolver.openInputStream(Uri.parse(uri))!!.use { it.readBytes() })
            uploadThrows?.let { throw it }
            "cloud-" + sent.size
        }
        stripper = mockk(relaxed = true)
        every { stripper.stripImagePreservingMotion(any(), any()) } returns null
        every { stripper.stripImagePreservingGainMap(any(), any()) } returns null
        every { stripper.hasGainMapUpload(any()) } returns false
        every { stripper.isMotionPhotoUpload(any()) } returns false
        compression = mockk()
        every { compression.photo(any(), any(), any(), any()) } returns
            UploadCompressionStep.PhotoResult(null, null, null, null, false)
        every { compression.compressesVideo(any(), any()) } answers {
            secondArg<CompressionSettings>().videos && firstArg<String>().startsWith("video/")
        }
        val networkObserver = mockk<NetworkObserver>(relaxed = true)
        coEvery { networkObserver.currentlyOnWifi() } returns true

        useCase = UploadPendingUseCase(
            syncStateRepo = syncRepo,
            localRepo = local,
            cloudRepo = cloud,
            pendingDeleteNotif = mockk(relaxed = true),
            networkObserver = networkObserver,
            transferCenter = mockk<TransferCenter>(relaxed = true),
            uploadAlbumTargetDao = db.uploadAlbumTargetDao(),
            pendingMetadataEditDao = db.pendingMetadataEditDao(),
            photoLocationDao = db.photoLocationDao(),
            structuralStripper = stripper,
            compressionStep = compression,
            context = context,
            appScope = appScope,
            albumListEvents = AlbumListEventBus(),
        )
    }

    @After
    fun tearDown() {
        unmockkAll()
        appScope.cancel()
        db.close()
        runBlocking { context.settingsDataStore.edit { it.clear() } }
    }

    private suspend fun prefs(block: (MutablePreferences) -> Unit) {
        context.settingsDataStore.edit { p ->
            p[SettingsKeys.SYNC_WIFI_ONLY] = false
            block(p)
        }
    }

    /** A device file with [bytes] queued as a manual back-up, which bypasses the folder and listing gates. */
    private suspend fun deviceFile(name: String, mime: String, bytes: ByteArray): Pair<File, LocalMediaItem> {
        val file = File(deviceDir, name).apply { writeBytes(bytes) }
        val uri = Uri.fromFile(file).toString()
        val item = LocalMediaItem(
            uri = uri, dateTaken = 1_700_000_000_000L, displayName = name, mimeType = mime,
            sizeBytes = bytes.size.toLong(), bucketName = "Camera", width = 100, height = 80,
        )
        coEvery { local.queryByUri(uri) } returns item
        coEvery { local.freshMedia() } returns listOf(item)
        syncRepo.seedAndMarkQueued(
            listOf(SyncState(uri, null, "", null, SyncStatus.LOCAL_ONLY, 0L, null, null, bytes.size.toLong())),
            userId, QueueSource.MANUAL, System.currentTimeMillis(),
        )
        return file to item
    }

    private fun temp(prefix: String, suffix: String, bytes: ByteArray): File =
        File.createTempFile(prefix, suffix, context.cacheDir).apply { writeBytes(bytes) }

    private fun sha1(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-1").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun uploadTemps(): List<String> = context.cacheDir.listFiles().orEmpty().map { it.name }
        .filter { n -> listOf("stripped_", "compressed_", "upl_ts_", "motion_", "transcoded_", "remux_").any { n.startsWith(it) } }

    @Test
    fun `an in-place mirror strip uploads the device file itself and records its hash`() = runTest {
        prefs {
            it[SettingsKeys.STRIP_ON_UPLOAD] = true
            it[SettingsKeys.STRIP_GPS] = true
            it[SettingsKeys.MIRROR_STRIP_TO_LOCAL] = true
        }
        val (file, item) = deviceFile("IMG_1.jpg", "image/jpeg", byteArrayOf(1, 2, 3, 4))
        val stripped = byteArrayOf(1, 2, 3)
        mockkObject(ExifHelper)
        every { ExifHelper.stripFieldsInPlace(any(), any(), any()) } answers { file.writeBytes(stripped); StripResult.Stripped }

        useCase(userId)

        val s = sent.single()
        assertEquals(item.uri, s.uri)
        assertArrayEquals(stripped, s.bytes)
        val row = syncRepo.getByUri(item.uri)!!
        assertEquals(SyncStatus.SYNCED, row.status)
        assertEquals(sha1(stripped), row.localHash)
        assertEquals(sha1(stripped), s.hash)
    }

    @Test
    fun `a motion photo mirror rewrites the device file and records the device bytes`() = runTest {
        prefs {
            it[SettingsKeys.STRIP_ON_UPLOAD] = true
            it[SettingsKeys.STRIP_GPS] = true
            it[SettingsKeys.MIRROR_STRIP_TO_LOCAL] = true
        }
        val (file, item) = deviceFile("MOTION_1.jpg", "image/jpeg", ByteArray(40) { 7 })
        val rebuilt = ByteArray(25) { 5 }
        val motionTemp = temp("motion_", ".jpg", rebuilt)
        every { stripper.stripImagePreservingMotion(item.uri, any()) } returns motionTemp

        useCase(userId)

        val s = sent.single()
        assertEquals(Uri.fromFile(motionTemp).toString(), s.uri)
        assertArrayEquals(rebuilt, file.readBytes())
        val row = syncRepo.getByUri(item.uri)!!
        assertEquals(sha1(rebuilt), row.localHash)
        assertEquals(rebuilt.size.toLong(), row.sizeBytes)
        assertFalse(motionTemp.exists())
    }

    @Test
    fun `a video remux mirror followed by a compressed upload records the remuxed device file`() = runTest {
        prefs {
            it[SettingsKeys.STRIP_ON_UPLOAD] = true
            it[SettingsKeys.STRIP_GPS] = true
            it[SettingsKeys.MIRROR_STRIP_TO_LOCAL] = true
            it[SettingsKeys.COMPRESS_VIDEO_ON_UPLOAD] = true
        }
        val (file, item) = deviceFile("VID_1.mp4", "video/mp4", ByteArray(60) { 3 })
        val remuxed = ByteArray(50) { 4 }
        val compressed = ByteArray(20) { 9 }
        val compressedTemp = temp("videocompress_", ".mp4", compressed)
        mockkObject(VideoMetadataStripper)
        every { VideoMetadataStripper.remuxWithoutLocation(any(), any(), any()) } answers { thirdArg<File>().writeBytes(remuxed); true }
        coEvery { compression.video(any(), any(), any(), any(), any()) } returns
            VideoUploadCompression.Attempt(compressedTemp, CompressionOutcome.COMPRESSED, null, 50L, "video/avc", 20L)

        useCase(userId)

        val s = sent.single()
        assertArrayEquals(compressed, s.bytes)
        assertEquals(sha1(compressed), s.hash)
        assertArrayEquals(remuxed, file.readBytes())
        val row = syncRepo.getByUri(item.uri)!!
        assertEquals(sha1(remuxed), row.localHash)
        assertEquals(remuxed.size.toLong(), row.sizeBytes)
        assertFalse(compressedTemp.exists())
        assertEquals(emptyList<String>(), uploadTemps())
    }

    @Test
    fun `a stripped HEIC that cannot be rewritten uploads as its JPEG transcode`() = runTest {
        prefs {
            it[SettingsKeys.STRIP_ON_UPLOAD] = true
            it[SettingsKeys.STRIP_GPS] = true
        }
        val (_, item) = deviceFile("IMG_2.HEIC", "image/heic", ByteArray(30) { 2 })
        val jpeg = ByteArray(12) { 8 }
        val transcoded = temp("transcoded_", ".jpg", jpeg)
        mockkObject(ExifHelper)
        every { ExifHelper.stripToTempFile(any(), any(), any<MetadataStripConfig>()) } returns null
        mockkObject(UploadImageCompressor)
        every { UploadImageCompressor.transcodeStrippedJpeg(any(), item.uri, any()) } returns transcoded

        useCase(userId)

        val s = sent.single()
        assertEquals("IMG_2.JPG", s.item.displayName)
        assertEquals("image/jpeg", s.item.mimeType)
        assertArrayEquals(jpeg, s.bytes)
        assertEquals(jpeg.size.toLong(), s.item.sizeBytes)
        assertFalse(transcoded.exists())
    }

    @Test
    fun `strip then compress uploads the compressed copy and deletes the stripped temp first`() = runTest {
        prefs {
            it[SettingsKeys.STRIP_ON_UPLOAD] = true
            it[SettingsKeys.STRIP_GPS] = true
            it[SettingsKeys.COMPRESS_ON_UPLOAD] = true
        }
        val (_, item) = deviceFile("IMG_3.jpg", "image/jpeg", ByteArray(30) { 1 })
        val strippedTemp = temp("stripped_", ".jpg", ByteArray(28) { 6 })
        val compressedTemp = temp("compressed_", ".jpg", ByteArray(10) { 7 })
        mockkObject(ExifHelper)
        every { ExifHelper.stripToTempFile(any(), item.uri, any<MetadataStripConfig>()) } returns strippedTemp
        every { compression.photo(Uri.fromFile(strippedTemp).toString(), "image/jpeg", any(), any()) } returns
            UploadCompressionStep.PhotoResult(compressedTemp, CompressionOutcome.COMPRESSED, null, 28L, false)
        var strippedAliveAtUpload = true
        coEvery { cloud.uploadFile(userId, any(), any(), any(), any(), any()) } answers {
            strippedAliveAtUpload = strippedTemp.exists()
            val uri = arg<String>(3)
            sent += Sent(arg(1), arg(2), uri, context.contentResolver.openInputStream(Uri.parse(uri))!!.use { it.readBytes() })
            "cloud-1"
        }

        useCase(userId)

        val s = sent.single()
        assertEquals(Uri.fromFile(compressedTemp).toString(), s.uri)
        assertEquals(10L, s.item.sizeBytes)
        assertFalse(strippedAliveAtUpload)
        assertFalse(compressedTemp.exists())
        assertEquals(sha1(ByteArray(10) { 7 }), syncRepo.getByUri(item.uri)!!.localHash)
    }

    @Test
    fun `strip-timestamp on an uncompressed video stamps a temp copy and removes it`() = runTest {
        prefs {
            it[SettingsKeys.STRIP_ON_UPLOAD] = true
            it[SettingsKeys.STRIP_TIMESTAMP] = true
        }
        val original = ByteArray(40) { 3 }
        val (file, _) = deviceFile("VID_2.mp4", "video/mp4", original)
        mockkObject(Mp4CreationTime)
        every { Mp4CreationTime.stamp(any<File>(), any()) } returns true
        val before = System.currentTimeMillis()

        useCase(userId)

        val s = sent.single()
        val sentPath = Uri.parse(s.uri).path!!
        assertTrue(File(sentPath).name.startsWith("upl_ts_"))
        assertArrayEquals(original, s.bytes)
        assertArrayEquals(original, file.readBytes())
        verify { Mp4CreationTime.stamp(File(sentPath), match { it >= before }) }
        assertFalse(File(sentPath).exists())
        assertEquals(emptyList<String>(), uploadTemps())
    }

    @Test
    fun `a failed upload leaves no upload temps behind and returns the row to the queue`() = runTest {
        prefs {
            it[SettingsKeys.STRIP_ON_UPLOAD] = true
            it[SettingsKeys.STRIP_GPS] = true
            it[SettingsKeys.COMPRESS_ON_UPLOAD] = true
        }
        val (_, item) = deviceFile("IMG_4.jpg", "image/jpeg", ByteArray(30) { 1 })
        val strippedTemp = temp("stripped_", ".jpg", ByteArray(28) { 6 })
        val compressedTemp = temp("compressed_", ".jpg", ByteArray(10) { 7 })
        mockkObject(ExifHelper)
        every { ExifHelper.stripToTempFile(any(), item.uri, any<MetadataStripConfig>()) } returns strippedTemp
        every { compression.photo(any(), any(), any(), any()) } returns
            UploadCompressionStep.PhotoResult(compressedTemp, CompressionOutcome.COMPRESSED, null, 28L, false)
        uploadThrows = RuntimeException("network down")

        useCase(userId)

        assertEquals(1, sent.size)
        assertFalse(strippedTemp.exists())
        assertFalse(compressedTemp.exists())
        assertEquals(emptyList<String>(), uploadTemps())
        val row = syncRepo.getByUri(item.uri)!!
        assertEquals(SyncStatus.LOCAL_ONLY, row.status)
        assertTrue(row.queued)
        assertEquals(QueueSource.MANUAL, row.queueSource)
    }

    @Test
    fun `cancelling during a video transcode releases the claim and the next pass uploads`() = runBlocking {
        prefs { it[SettingsKeys.COMPRESS_VIDEO_ON_UPLOAD] = true }
        val (_, item) = deviceFile("VID_3.mp4", "video/mp4", ByteArray(40) { 2 })
        val transcoding = CompletableDeferred<Unit>()
        coEvery { compression.video(any(), any(), any(), any(), any()) } coAnswers {
            transcoding.complete(Unit)
            awaitCancellation()
        }

        val pass = CoroutineScope(Dispatchers.Default).launch { useCase(userId) }
        withTimeout(10_000L) { transcoding.await() }
        pass.cancelAndJoin()

        val released = syncRepo.getByUri(item.uri)!!
        assertEquals(SyncStatus.LOCAL_ONLY, released.status)
        assertTrue(released.queued)
        assertEquals(0, sent.size)

        coEvery { compression.video(any(), any(), any(), any(), any()) } returns
            VideoUploadCompression.Attempt(null, CompressionOutcome.SKIPPED, CompressionSkipReason.ALREADY_EFFICIENT)
        useCase(userId)

        assertEquals(1, sent.size)
        assertEquals(SyncStatus.SYNCED, syncRepo.getByUri(item.uri)!!.status)
    }
}
