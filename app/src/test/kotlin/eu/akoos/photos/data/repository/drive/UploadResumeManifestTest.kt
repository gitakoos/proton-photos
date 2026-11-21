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

package eu.akoos.photos.data.repository.drive

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import eu.akoos.photos.data.db.encryption.DatabaseKeyStoreTest.FakeWrapper
import eu.akoos.photos.data.localcrypto.CacheFiles
import eu.akoos.photos.data.localcrypto.LocalCrypto
import eu.akoos.photos.data.localcrypto.LocalCryptoFormat
import eu.akoos.photos.data.localcrypto.LocalKeyring
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import javax.crypto.KeyGenerator

/** The upload resume record holds the file's session key, so it is kept encrypted with the local keys. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UploadResumeManifestTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val keysDir get() = File(context.noBackupFilesDir, "test_keys")
    private val tempDir get() = File(context.cacheDir, "upload_test")
    private val sessionKeyB64 = "c2Vzc2lvbi1rZXktYnl0ZXMtMDEyMzQ1Njc4OWFi"

    private fun newKeyring(dir: File) =
        LocalKeyring(dir, FakeWrapper(KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()))

    private fun manifest() = UploadResumeManifest(
        completedBlocks = 1,
        totalBlocks = 2,
        blockSize = 4,
        sizeBytes = 8,
        fileId = "file",
        revisionId = "rev",
        useVolumeEndpoints = true,
        sessionKeyB64 = sessionKeyB64,
        verificationCodeB64 = "dmVyaWZ5",
        contentKeyPacketB64 = "a2V5",
        contentKeyPacketSignature = "sig",
        nodePublicKeyArmored = "pub",
        blocks = listOf(UploadResumeManifest.BlockManifest(0, "s", "aGFzaA==", "dG9r", 20, 4)),
    )

    private val file get() = File(tempDir, UploadResumeManifest.FILENAME)

    @Before
    fun setUp() {
        LocalCrypto.useKeyringForTest(newKeyring(keysDir))
        CacheFiles.init(context)
        tempDir.mkdirs()
        File(tempDir, "block_0.enc").writeBytes(byteArrayOf(1, 2, 3))
    }

    @After
    fun tearDown() {
        LocalCrypto.useKeyringForTest(null)
        keysDir.deleteRecursively()
        File(context.noBackupFilesDir, "other_keys").deleteRecursively()
        tempDir.deleteRecursively()
    }

    @Test
    fun `the resume record is written encrypted and reads back whole`() {
        UploadResumeManifest.save(tempDir, manifest())

        assertTrue(LocalCryptoFormat.isEncrypted(file))
        assertFalse(String(file.readBytes(), Charsets.ISO_8859_1).contains(sessionKeyB64))
        assertEquals(manifest(), UploadResumeManifest.load(tempDir))
        assertEquals(listOf("block_0.enc", UploadResumeManifest.FILENAME), tempDir.list()!!.sorted())
    }

    @Test
    fun `a record an earlier version wrote in plaintext is read once and written again encrypted`() {
        file.writeText(Json.encodeToString(manifest()))

        assertEquals(manifest(), UploadResumeManifest.load(tempDir))

        assertTrue(LocalCryptoFormat.isEncrypted(file))
        assertEquals(manifest(), UploadResumeManifest.load(tempDir))
    }

    @Test
    fun `a record whose key is gone reads as missing, so the upload starts from the first block`() {
        UploadResumeManifest.save(tempDir, manifest())
        LocalCrypto.useKeyringForTest(newKeyring(File(context.noBackupFilesDir, "other_keys")))

        assertNull(UploadResumeManifest.load(tempDir))
    }
}
