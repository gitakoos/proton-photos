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

package eu.akoos.photos.crypto

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.util.Base64
import android.util.Log
import dagger.hilt.android.AndroidEntryPoint
import me.proton.core.crypto.common.context.CryptoContext
import me.proton.core.crypto.common.pgp.PGPHeader
import me.proton.core.crypto.common.pgp.SessionKey
import java.io.File
import java.util.concurrent.locks.ReentrantLock
import javax.inject.Inject
import kotlin.concurrent.withLock

private const val TAG = "CryptoService"

/**
 * Hosts the high-volume Drive thumbnail decrypts in a SEPARATE process (`:crypto`).
 *
 * The native libgojni OpenPGP runtime can panic with "slice bounds out of range [:-1]" and SIGABRT
 * the whole process when ProtonCore's key-unlock crypto (on Dispatchers.Default) races the app's
 * bulk decrypts in one Go runtime under Android-16's userfaultfd GC. Running them here isolates the
 * Go runtime so a residual panic only kills `:crypto`; the main-process UI survives and falls back.
 *
 * Only [CryptoContext] is injected — pulling in DriveCryptoHelper would drag Room/repositories into
 * this process. The binder methods replicate its leaf decrypt bodies verbatim, including the guards
 * that keep blank/too-short input (the panic triggers) out of the Go runtime.
 */
@AndroidEntryPoint
class CryptoService : Service() {

    @Inject
    lateinit var cryptoContext: CryptoContext

    // Serializes every libgojni-bound call in this process (mirrors DriveCryptoHelper.cryptoLock).
    // Fair-FIFO so no caller starves.
    private val cryptoLock = ReentrantLock(true)

    private val binder = object : ICryptoService.Stub() {

        override fun decryptNodeKey(
            nodeKeyArmored: String?,
            nodePassphraseArmored: String?,
            parentKeyBytes: ByteArray?,
        ): ByteArray? = cryptoLock.withLock {
            try {
                // Blank PGP armor makes the Go OpenPGP library panic with "slice bounds out of
                // range [:-1]" and SIGABRT the process (it strips a newline off a 0-length string).
                // Fail fast in Kotlin so the caller gets a null instead.
                require(!nodeKeyArmored.isNullOrBlank()) { "decryptNodeKey: nodeKeyArmored is blank" }
                require(!nodePassphraseArmored.isNullOrBlank()) { "decryptNodeKey: nodePassphraseArmored is blank" }
                require(parentKeyBytes != null) { "decryptNodeKey: parentKeyBytes is null" }
                val nodePassphraseBytes = cryptoContext.pgpCrypto.decryptData(nodePassphraseArmored, parentKeyBytes)
                val unlockedKey = cryptoContext.pgpCrypto.unlock(nodeKeyArmored, nodePassphraseBytes)
                val keyBytes = unlockedKey.value.copyOf()
                unlockedKey.close()
                keyBytes
            } catch (e: Exception) {
                Log.w(TAG, "decryptNodeKey failed: ${e.message}")
                null
            }
        }

        override fun decryptSessionKeyBytes(
            contentKeyPacketBase64: String?,
            nodeKeyBytes: ByteArray?,
        ): ByteArray? = cryptoLock.withLock {
            if (contentKeyPacketBase64.isNullOrBlank() || nodeKeyBytes == null) return@withLock null
            try {
                val keyPacketBytes = Base64.decode(contentKeyPacketBase64, Base64.DEFAULT)
                if (keyPacketBytes.isEmpty()) return@withLock null
                cryptoContext.pgpCrypto.decryptSessionKey(keyPacketBytes, nodeKeyBytes).key
            } catch (e: Exception) {
                Log.w(TAG, "decryptSessionKeyBytes failed: ${e.message}")
                null
            }
        }

        override fun decryptWithSessionKeyToFd(
            sessionKeyBytes: ByteArray?,
            encPath: String?,
            out: ParcelFileDescriptor?,
        ): Boolean = writePlain(out) {
            if (sessionKeyBytes == null || encPath == null) return@writePlain null
            val data = File(encPath).readBytes()
            // Empty bytes trigger the same [:-1] Go panic/SIGABRT, so they are refused first.
            require(data.isNotEmpty()) { "decryptWithSessionKeyToFd: encrypted file is empty or missing" }
            cryptoContext.pgpCrypto.decryptData(data, SessionKey(sessionKeyBytes))
        }

        override fun decryptBinaryPgpWithNodeKey(
            data: ByteArray?,
            nodeKeyBytes: ByteArray?,
        ): ByteArray? = cryptoLock.withLock {
            // Reject empty/too-short input before Go: a 0-byte buffer triggers the [:-1] panic/SIGABRT
            // while parsing the armor header. 16 is below the smallest real PGP message.
            if (data == null || nodeKeyBytes == null || data.size < 16) return@withLock null
            try {
                val armored = cryptoContext.pgpCrypto.getArmored(data, PGPHeader.Message)
                cryptoContext.pgpCrypto.decryptData(armored, nodeKeyBytes)
            } catch (e: Exception) {
                Log.w(TAG, "decryptBinaryPgpWithNodeKey failed: ${e.message}")
                null
            }
        }

        override fun decryptXAttr(
            xAttrArmored: String?,
            nodeKeyBytes: ByteArray?,
        ): String? = cryptoLock.withLock {
            // Blank armor triggers the same [:-1] panic/SIGABRT — keep it out of the Go runtime.
            if (xAttrArmored.isNullOrBlank() || nodeKeyBytes == null) return@withLock null
            try {
                cryptoContext.pgpCrypto.decryptText(xAttrArmored, nodeKeyBytes)
            } catch (e: Exception) {
                Log.w(TAG, "decryptXAttr failed: ${e.message}")
                null
            }
        }

        override fun decryptBinaryToFd(
            encPath: String?,
            out: ParcelFileDescriptor?,
            nodeKeyBytes: ByteArray?,
        ): Boolean = writePlain(out) {
            // For large download blocks, so a multi-MB block never crosses the binder as a byte[]. Same
            // size guard and getArmored path as the byte[] sibling.
            if (encPath == null || nodeKeyBytes == null) return@writePlain null
            val data = File(encPath).readBytes()
            if (data.size < 16) return@writePlain null
            val armored = cryptoContext.pgpCrypto.getArmored(data, PGPHeader.Message)
            cryptoContext.pgpCrypto.decryptData(armored, nodeKeyBytes)
        }

        /**
         * Decrypts with [decrypt] under the crypto lock and writes the plaintext into [out], the write end of the
         * caller's pipe, closing it in every case so the caller's read ends. False when there was nothing to write.
         */
        private fun writePlain(out: ParcelFileDescriptor?, decrypt: () -> ByteArray?): Boolean {
            if (out == null) return false
            return ParcelFileDescriptor.AutoCloseOutputStream(out).use { stream ->
                val plain = try {
                    cryptoLock.withLock { decrypt() }
                } catch (e: Exception) {
                    Log.w(TAG, "decrypt to the caller's pipe failed: ${e.message}")
                    null
                } ?: return@use false
                try {
                    stream.write(plain)
                    true
                } catch (e: Exception) {
                    Log.w(TAG, "writing to the caller's pipe failed: ${e.message}")
                    false
                }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder
}
