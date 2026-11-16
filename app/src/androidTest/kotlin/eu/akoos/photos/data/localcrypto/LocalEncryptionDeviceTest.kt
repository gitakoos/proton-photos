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

package eu.akoos.photos.data.localcrypto

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.os.storage.StorageManager
import android.system.Os
import android.system.OsConstants
import android.util.Log
import androidx.datastore.preferences.core.PreferencesSerializer
import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.exifinterface.media.ExifInterface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import eu.akoos.photos.data.db.encryption.AndroidKeystoreWrapper
import kotlinx.coroutines.runBlocking
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.locks.ReentrantLock
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Local encryption on the real Android Keystore, on a device: keys sealed by a scratch Keystore alias, a vault
 * folder encrypted in place and read back byte for byte through a seekable descriptor, the settings file's move,
 * and every way back after the alias is deleted (the Keystore losing its key). Every file lives in a scratch
 * folder under the cache and the alias is a test one; the installed app's vault, settings and keys are never
 * touched.
 */
@RunWith(AndroidJUnit4::class)
class LocalEncryptionDeviceTest {

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var root: File
    private val alias = "pfp_local_master_key_test"

    @Before
    fun setUp() {
        root = File(ctx.cacheDir, "localenc-test-${System.nanoTime()}").apply { mkdirs() }
        deleteAlias()
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
        deleteAlias()
    }

    private fun deleteAlias() {
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.let { if (it.containsAlias(alias)) it.deleteEntry(alias) }
    }

    private fun keyring() = LocalKeyring(File(root, "keys"), AndroidKeystoreWrapper(alias))

    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)

    /** A real JPEG with EXIF, the kind of file the vault holds. */
    private fun photo(): ByteArray {
        val bitmap = Bitmap.createBitmap(640, 480, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(30, 120, 200)) }
        val file = File(root, "src.jpg")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        ExifInterface(file.absolutePath).apply {
            setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, "2024:05:06 07:08:09")
            setAttribute(ExifInterface.TAG_MAKE, "TestMake")
            setLatLong(47.5, 19.04)
            saveAttributes()
        }
        return file.readBytes().also { file.delete() }
    }

    private class Sealer(private val key: SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()) : AccountSealer {
        override fun seal(payload: ByteArray): ByteArray {
            val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
            return iv + Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv)) }.doFinal(payload)
        }

        override fun open(blob: ByteArray): ByteArray? = runCatching {
            Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, blob.copyOfRange(0, 12))) }
                .doFinal(blob, 12, blob.size - 12)
        }.getOrNull()
    }

    private class Db(root: File) : DatabaseKeyAccess {
        override fun currentKey(): ByteArray? = null
        override val copies = KeyRecoveryCopies(File(root, "dbcopies"))
        override fun waitingSetAsides(): List<SetAsideDatabase> = emptyList()
        override fun markRecovered(setAside: SetAsideDatabase, key: ByteArray, ownerHash: String?) = Unit
    }

    @Test
    fun vaultFolderEncryptsInPlaceAndReadsBackByteForByte() {
        val vault = File(root, "hidden").apply { mkdirs() }
        val photos = (0 until 3).associate { i -> "uuid-$i.jpg" to (photo() + ByteArray(i * 70_000) { it.toByte() }) }
        photos.forEach { (name, bytes) -> File(vault, name).writeBytes(bytes) }
        val ring = keyring()
        val outcome = VaultMigration(
            vaultDir = vault,
            stagingDir = File(root, "staging"),
            stateDir = File(root, "keys"),
            keyring = ring,
            lock = ReentrantLock(),
            metaFor = { LocalFileMeta(originalName = "IMG_${it.name}", mimeType = "image/jpeg") },
            log = { Log.i(TAG, it) },
        ).run()
        assertTrue(outcome.complete)
        for ((name, bytes) in photos) {
            val file = File(vault, name)
            assertTrue(LocalCryptoFormat.isEncrypted(file))
            val out = ByteArrayOutputStream()
            LocalCryptoFormat.decryptFile(file, keyring(), out)
            assertArrayEquals(name, sha(bytes), sha(out.toByteArray()))
            assertEquals("IMG_$name", LocalCryptoFormat.openFile(file, ring).use { it.meta.originalName })
        }
    }

    @Test
    fun seekableDescriptorServesDecryptedBytesToFileReaders() {
        val bytes = photo()
        val file = File(root, "v.jpg")
        val ring = keyring()
        val current = ring.current()
        file.writeBytes(LocalCryptoFormat.encryptBytes(bytes, LocalCryptoFormat.Sealing(current.keyId, current.key, LocalFileKind.VAULT)))

        val thread = HandlerThread("vault-read-test").apply { start() }
        try {
            val storage = ctx.getSystemService(StorageManager::class.java)
            val pfd = storage.openProxyFileDescriptor(
                ParcelFileDescriptor.MODE_READ_ONLY,
                VaultReadProvider.ReaderCallback(LocalCryptoFormat.openFile(file, ring)),
                Handler(thread.looper),
            )
            pfd.use {
                assertEquals(bytes.size.toLong(), it.statSize)
                // Read whole, then seek like an EXIF reader or a player does.
                val all = FileInputStream(it.fileDescriptor).readBytes()
                assertArrayEquals(sha(bytes), sha(all))
                // A reader given this descriptor starts where the last one stopped, so each goes back to the start.
                Os.lseek(it.fileDescriptor, 0L, OsConstants.SEEK_SET)
                val exif = ExifInterface(it.fileDescriptor)
                assertEquals("TestMake", exif.getAttribute(ExifInterface.TAG_MAKE))
                assertNotNull(exif.latLong)
                Os.lseek(it.fileDescriptor, 0L, OsConstants.SEEK_SET)
                val decoded = BitmapFactory.decodeFileDescriptor(it.fileDescriptor)
                assertEquals(640, decoded.width)
            }
        } finally {
            thread.quitSafely()
        }
    }

    @Test
    fun settingsFileMovesAndReadsBack() {
        val prefs = preferencesOf(stringPreferencesKey("theme") to "dark", stringSetPreferencesKey("hidden") to setOf("a", "b"))
        val plain = Buffer().also { runBlocking { PreferencesSerializer.writeTo(prefs, it) } }.readByteArray()
        val file = File(root, "settings.preferences_pb").apply { writeBytes(plain) }
        assertTrue(LocalStoreMigration.migrate(file, "settings", keyring()) { Log.i(TAG, it) })
        assertTrue(LocalCryptoFormat.looksEncrypted(file.readBytes()))
        val serializer = EncryptedPreferencesSerializer("settings", keyring(), LockedStoreFiles(File(root, "locked")), { Log.i(TAG, it) })
        assertEquals(prefs, runBlocking { serializer.readFrom(Buffer().write(file.readBytes())) })
    }

    @Test
    fun deletedKeystoreKeyComesBackWithTheAccountOrThePassword() {
        val ring = keyring()
        val custody = LocalKeyCustody(ring, KeyRecoveryCopies(File(root, "keys/recovery")), Db(root)) { Log.i(TAG, it) }
        custody.onOccupant("scratch-user")
        val old = ring.current()
        val bytes = photo()
        val sealed = File(root, "locked.jpg").apply {
            writeBytes(LocalCryptoFormat.encryptBytes(bytes, LocalCryptoFormat.Sealing(old.keyId, old.key, LocalFileKind.VAULT)))
        }
        val sealer = Sealer()
        custody.ensureAccountCopies("scratch-user", sealer)
        custody.syncPasswordCopies("scratch password 123".toCharArray(), rewrite = false)

        // The Keystore loses the key.
        deleteAlias()
        val lost = keyring()
        val after = LocalKeyCustody(lost, KeyRecoveryCopies(File(root, "keys/recovery")), Db(root)) { Log.i(TAG, it) }
        assertEquals(listOf(old.keyId), lost.lockedKeyIds())
        assertTrue(runCatching { LocalCryptoFormat.decryptFile(sealed, lost, null) }.exceptionOrNull() is LocalKeyLockedException)
        assertTrue(after.status().passwordCanUnlock)

        // The password copy opens it.
        assertFalse(after.recoverWithPassword("wrong".toCharArray()).any)
        assertEquals(1, after.recoverWithPassword("scratch password 123".toCharArray()).keys)
        val out = ByteArrayOutputStream()
        LocalCryptoFormat.decryptFile(sealed, keyring(), out)
        assertArrayEquals(sha(bytes), sha(out.toByteArray()))

        // Lost again; this time the account copy opens it.
        deleteAlias()
        val lostAgain = keyring()
        val third = LocalKeyCustody(lostAgain, KeyRecoveryCopies(File(root, "keys/recovery")), Db(root)) { Log.i(TAG, it) }
        assertTrue(lostAgain.lockedKeyIds().contains(old.keyId))
        assertTrue(third.recoverWithAccount("scratch-user", sealer).keys >= 1)
        val again = ByteArrayOutputStream()
        LocalCryptoFormat.decryptFile(sealed, keyring(), again)
        assertArrayEquals(sha(bytes), sha(again.toByteArray()))
    }

    private companion object {
        const val TAG = "LocalEncryptionDeviceTest"
    }
}
