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

import android.content.Context
import android.util.Log
import eu.akoos.photos.data.db.encryption.AndroidKeystoreWrapper
import eu.akoos.photos.data.db.encryption.DatabaseKeyState
import eu.akoos.photos.data.db.encryption.DatabaseKeyStore
import eu.akoos.photos.data.db.encryption.DatabaseOpenGate
import eu.akoos.photos.data.db.encryption.writeWhole
import eu.akoos.photos.util.SyncDiagnostics
import java.io.File
import java.security.Security

/**
 * Where the local keys live on this install and the objects built on them, shared by every caller that is not
 * built by Hilt (the settings file is opened from a top-level delegate).
 *
 * Everything is under `no_backup/`, which Android never backs up or moves to another phone.
 */
object LocalCrypto {
    private const val TAG = "LocalCrypto"
    private const val KEYSTORE_ALIAS = "pfp_local_master_key"
    private const val ROOT = "local_keys"
    private const val MIGRATED_MARKER = "migrated"
    private const val NOTICE_MARKER = "notice_due"
    private const val LEGACY_MARKER = "legacy_data"
    private const val GUEST_HIDE_WARNED = "guest_hide_warned"

    @Volatile private var keyring: LocalKeyring? = null
    @Volatile private var supported: Boolean? = null

    fun root(context: Context): File = File(context.applicationContext.noBackupFilesDir, ROOT)

    /**
     * The keyring, or null where the platform has no Android Keystore (the JVM test runtime): there the files stay
     * as they are, read and written as plaintext.
     */
    fun keyringOrNull(context: Context): LocalKeyring? {
        keyring?.let { return it }
        val hasKeystore = supported ?: (Security.getProvider("AndroidKeyStore") != null).also { supported = it }
        if (!hasKeystore) return null
        return synchronized(this) {
            keyring ?: LocalKeyring(root(context), AndroidKeystoreWrapper(KEYSTORE_ALIAS)).also { keyring = it }
        }
    }

    /** Puts [keyring] in place of the Keystore one, for tests on the JVM, where there is no Keystore. */
    @androidx.annotation.VisibleForTesting
    internal fun useKeyringForTest(keyring: LocalKeyring?) {
        this.keyring = keyring
        supported = if (keyring != null) true else null
    }

    fun lockedStores(context: Context) = LockedStoreFiles(File(root(context), "locked"))

    fun masterCopies(context: Context) = KeyRecoveryCopies(File(root(context), "recovery"))

    fun custody(context: Context): LocalKeyCustody? {
        val keyring = keyringOrNull(context) ?: return null
        return LocalKeyCustody(keyring, masterCopies(context), AndroidDatabaseKeys(context.applicationContext), ::log)
    }

    /** Whether every vault file was found encrypted once (the one-time notice follows this). */
    fun isMigrated(context: Context): Boolean = File(root(context), MIGRATED_MARKER).exists()

    /** Marks every vault file found encrypted. The one-time notice is due only on an install that held data from
     *  a version without local encryption ([hadLegacyData]); a fresh install starts encrypted and is not told. */
    fun markMigrated(context: Context) {
        val marker = File(root(context), MIGRATED_MARKER)
        if (marker.exists()) return
        root(context).mkdirs()
        runCatching {
            writeWhole(marker, System.currentTimeMillis().toString().toByteArray())
            if (hadLegacyData(context)) writeWhole(File(root(context), NOTICE_MARKER), byteArrayOf(1))
        }
    }

    /**
     * Records, once, at the first start of a version with local encryption, whether the install already held data
     * from an earlier version: a plaintext database, a database from before this process started, a settings file
     * not yet encrypted, or files in the vault. Later starts keep the first answer.
     */
    fun recordLegacyData(context: Context) {
        val marker = File(root(context), LEGACY_MARKER)
        if (marker.exists()) return
        val app = context.applicationContext ?: context
        val processStartedAt = System.currentTimeMillis() - android.os.SystemClock.elapsedRealtime() +
            android.os.Process.getStartElapsedRealtime()
        val found = runCatching {
            legacyDataIn(
                filesDir = app.filesDir,
                databasesDir = app.getDatabasePath(DatabaseOpenGate.PLAIN_NAME).parentFile ?: File(app.dataDir, "databases"),
                vaultDir = VaultFiles.vaultDir(app),
                processStartedAt = processStartedAt,
            )
        }.getOrDefault(false)
        runCatching {
            root(context).mkdirs()
            writeWhole(marker, byteArrayOf(if (found) 1 else 0))
        }
    }

    fun hadLegacyData(context: Context): Boolean =
        runCatching { File(root(context), LEGACY_MARKER).readBytes().firstOrNull() == 1.toByte() }.getOrDefault(false)

    /** Whether a guest was already told that hidden photos without a password cannot be recovered. */
    fun isGuestHideWarned(context: Context): Boolean = File(root(context), GUEST_HIDE_WARNED).exists()

    fun markGuestHideWarned(context: Context) {
        runCatching {
            root(context).mkdirs()
            writeWhole(File(root(context), GUEST_HIDE_WARNED), byteArrayOf(1))
        }
    }

    fun isNoticeDue(context: Context): Boolean = File(root(context), NOTICE_MARKER).exists()

    fun clearNotice(context: Context) {
        File(root(context), NOTICE_MARKER).delete()
    }

    fun log(line: String) {
        Log.i(TAG, line)
        SyncDiagnostics.log(line)
    }
}

/** The database key and its set-aside copies, on the files the database open gate keeps. */
internal class AndroidDatabaseKeys(private val context: Context) : DatabaseKeyAccess {
    private val stateDir get() = File(context.noBackupFilesDir, "database_key")
    private val setAsideDir get() = File(context.noBackupFilesDir, "database_set_aside")

    override fun currentKey(): ByteArray? {
        val store = DatabaseKeyStore(stateDir, AndroidKeystoreWrapper())
        if (!store.hasWrappedKey()) return null
        return (store.load() as? DatabaseKeyState.Ready)?.key
    }

    override val copies: KeyRecoveryCopies
        get() = KeyRecoveryCopies(File(stateDir, DatabaseKeyStore.RECOVERY_DIR))

    override fun waitingSetAsides(): List<SetAsideDatabase> =
        setAsideDir.listFiles()
            ?.filter { dir ->
                dir.isDirectory &&
                    !File(dir, DatabaseOpenGate.RECOVERED_DIR).exists() &&
                    File(dir, DatabaseOpenGate.ENCRYPTED_NAME).exists() &&
                    (File(dir, DatabaseKeyStore.RECOVERY_DIR).listFiles()?.isNotEmpty() ?: false)
            }
            ?.map { SetAsideDatabase(it, KeyRecoveryCopies(File(it, DatabaseKeyStore.RECOVERY_DIR))) }
            ?: emptyList()

    override fun markRecovered(setAside: SetAsideDatabase, key: ByteArray, ownerHash: String?) {
        val target = File(setAside.dir, DatabaseOpenGate.RECOVERED_DIR)
        DatabaseKeyStore(target, AndroidKeystoreWrapper()).store(key)
        writeWhole(File(target, DatabaseOpenGate.RECOVERED_OWNER), (ownerHash ?: "").toByteArray())
    }
}

/**
 * Whether an install holds data from a version without local encryption: the plaintext database in [databasesDir],
 * an encrypted one written before [processStartedAt] (a version that encrypted only the database), a settings file
 * under [filesDir] that is not encrypted yet, or any file in [vaultDir].
 */
internal fun legacyDataIn(filesDir: File, databasesDir: File, vaultDir: File, processStartedAt: Long): Boolean {
    val plain = File(databasesDir, DatabaseOpenGate.PLAIN_NAME)
    if (plain.isFile && plain.length() > 0L) return true
    val encrypted = File(databasesDir, DatabaseOpenGate.ENCRYPTED_NAME)
    if (encrypted.isFile && encrypted.length() > 0L && encrypted.lastModified() < processStartedAt) return true
    val settings = File(filesDir, "datastore/settings.preferences_pb")
    if (settings.isFile && settings.length() > 0L && LocalCryptoFormat.formOf(settings) == LocalFileForm.PLAIN) return true
    return vaultDir.listFiles()?.any { it.isFile && !it.name.startsWith(".") } == true
}
