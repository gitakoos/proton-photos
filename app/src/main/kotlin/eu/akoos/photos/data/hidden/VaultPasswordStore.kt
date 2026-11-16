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

package eu.akoos.photos.data.hidden

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.datastore.preferences.core.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.akoos.photos.data.db.encryption.KeystoreErrors
import eu.akoos.photos.data.preferences.SettingsKeys
import eu.akoos.photos.data.preferences.settingsDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The optional vault password. A forced sign-out has no screen to ask for it, so it is kept where the
 * app can read it unattended: encrypted with an AES-GCM key held by the Android Keystore, which is
 * bound to this app and this device and cannot be exported. The stored value alone is useless
 * without that key.
 */
@Singleton
class VaultPasswordStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val localEncryption: javax.inject.Provider<eu.akoos.photos.data.localcrypto.LocalEncryptionCoordinator>,
) {

    /** Emits whether a password is set, and again whenever that changes. */
    fun observeIsSet(): Flow<Boolean> =
        context.settingsDataStore.data.map { it[SettingsKeys.VAULT_PASSWORD_BLOB] != null }.distinctUntilChanged()

    suspend fun isSet(): Boolean = context.settingsDataStore.data.first()[SettingsKeys.VAULT_PASSWORD_BLOB] != null

    suspend fun set(password: CharArray) {
        val blob = VaultPasswordCodec.encrypt(password, keystoreKey(create = true))
        context.settingsDataStore.edit { it[SettingsKeys.VAULT_PASSWORD_BLOB] = blob }
        // The local keys get a copy sealed with the password, which opens them on a phone whose Keystore lost them.
        localEncryption.get().onPasswordChanged(password)
    }

    suspend fun clear() {
        context.settingsDataStore.edit { it.remove(SettingsKeys.VAULT_PASSWORD_BLOB) }
        localEncryption.get().onPasswordChanged(null)
    }

    /** The password, or null when none is set or the stored value can no longer be decrypted. */
    suspend fun read(): CharArray? = (readState() as? StoredVaultPassword.Readable)?.password

    /**
     * What is stored: no password, the password, or one that is set but cannot be read now (its Keystore key is
     * gone). The last is never the same as no password: the password copies of the local keys stay for it.
     */
    suspend fun readState(): StoredVaultPassword {
        val blob = context.settingsDataStore.data.first()[SettingsKeys.VAULT_PASSWORD_BLOB] ?: return StoredVaultPassword.None
        return runCatching { VaultPasswordCodec.decrypt(blob, keystoreKey(create = false)) }
            .fold({ StoredVaultPassword.Readable(it) }, { StoredVaultPassword.Unreadable })
    }

    /**
     * Whether [typed] is the stored password. False when none is set. A stored password that cannot be read is
     * checked against the local keys' password copies instead, and stored again under a working Keystore key when
     * [typed] opens one, so the user is never locked out of their own password.
     */
    suspend fun matches(typed: CharArray): Boolean {
        return when (val stored = readState()) {
            StoredVaultPassword.None -> false
            is StoredVaultPassword.Readable -> try {
                vaultPasswordMatches(stored.password, typed)
            } finally {
                stored.password.fill('\u0000')
            }
            StoredVaultPassword.Unreadable -> {
                val confirmed = localEncryption.get().passwordOpensCopies(typed)
                if (confirmed) reseal(typed)
                confirmed
            }
        }
    }

    /**
     * Stores [password] again when the stored value cannot be read, after a password copy proved it. The copies
     * stay as they are; only missing ones are added.
     */
    suspend fun reseal(password: CharArray) {
        if (readState() != StoredVaultPassword.Unreadable) return
        val blob = VaultPasswordCodec.encrypt(password, keystoreKey(create = true))
        context.settingsDataStore.edit { it[SettingsKeys.VAULT_PASSWORD_BLOB] = blob }
        localEncryption.get().onPasswordConfirmed(password)
    }

    /**
     * The Keystore key the password is sealed with, made when missing and [create] is set. With [create] a key that
     * is broken for good (it throws rather than answers) is replaced: what it sealed cannot be read either way, and
     * a password is being stored under the new one. Nothing but this key's own entry is touched.
     */
    private fun keystoreKey(create: Boolean): SecretKey {
        val store = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val existing = try {
            store.getKey(KEY_ALIAS, null) as? SecretKey
        } catch (e: Exception) {
            if (!create || !KeystoreErrors.isPermanent(e)) throw e
            store.deleteEntry(KEY_ALIAS)
            null
        }
        existing?.let { return it }
        check(create) { "no password key" }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "pfp_vault_password"
    }
}

/** What the password store holds. */
sealed interface StoredVaultPassword {
    data object None : StoredVaultPassword

    class Readable(val password: CharArray) : StoredVaultPassword

    /** Set, but its Keystore key cannot open it now. */
    data object Unreadable : StoredVaultPassword
}

/** AES-GCM sealing of the password under a given key, apart from the Keystore so it runs in a unit test. */
internal object VaultPasswordCodec {
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val TAG_BITS = 128

    fun encrypt(password: CharArray, key: SecretKey): String {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key) }
        val bytes = Charsets.UTF_8.encode(CharBuffer.wrap(password)).let { ByteArray(it.remaining()).also { b -> it.get(b) } }
        try {
            val sealed = cipher.doFinal(bytes)
            val iv = cipher.iv
            val out = ByteBuffer.allocate(1 + iv.size + sealed.size).put(iv.size.toByte()).put(iv).put(sealed).array()
            return Base64.getEncoder().encodeToString(out)
        } finally {
            bytes.fill(0)
        }
    }

    fun decrypt(blob: String, key: SecretKey): CharArray {
        val raw = Base64.getDecoder().decode(blob)
        val ivLength = raw[0].toInt()
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, raw, 1, ivLength))
        }
        val bytes = cipher.doFinal(raw, 1 + ivLength, raw.size - 1 - ivLength)
        try {
            return Charsets.UTF_8.decode(ByteBuffer.wrap(bytes)).let { CharArray(it.remaining()).also { c -> it.get(c) } }
        } finally {
            bytes.fill(0)
        }
    }
}

/** Whether two passwords are the same, compared in constant time so the answer does not leak how
 *  much of a guess was right. */
internal fun vaultPasswordMatches(stored: CharArray, typed: CharArray): Boolean =
    java.security.MessageDigest.isEqual(
        String(stored).toByteArray(Charsets.UTF_8),
        String(typed).toByteArray(Charsets.UTF_8),
    )
