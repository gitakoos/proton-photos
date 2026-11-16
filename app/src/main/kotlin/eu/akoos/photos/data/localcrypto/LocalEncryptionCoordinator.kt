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
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.akoos.photos.data.hidden.StoredVaultPassword
import eu.akoos.photos.data.hidden.VaultPasswordStore
import eu.akoos.photos.data.mlsync.AddressKeyEnvelopeCrypto
import eu.akoos.photos.data.mlsync.EnvelopeCrypto
import eu.akoos.photos.data.mlsync.EnvelopeOpenKeys
import eu.akoos.photos.data.mlsync.EnvelopeOpenResult
import eu.akoos.photos.data.mlsync.EnvelopeSealKeys
import eu.akoos.photos.data.mlsync.MlSnapshotEnvelope
import eu.akoos.photos.data.preferences.SettingsKeys
import eu.akoos.photos.data.preferences.settingsDataStore
import eu.akoos.photos.util.HiddenCaptureTime
import eu.akoos.photos.util.mimeFromPath
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import me.proton.core.accountmanager.domain.AccountManager
import me.proton.core.domain.entity.UserId
import java.io.ByteArrayOutputStream
import java.io.Closeable
import javax.inject.Inject
import javax.inject.Singleton

/** What the settings row and the info sheet show about local encryption. */
data class LocalEncryptionState(
    /** False where the platform has no Android Keystore; nothing is encrypted then. */
    val available: Boolean = false,
    /** Vault files still waiting to be encrypted. */
    val pendingFiles: Int = 0,
    val pausedForSpace: Boolean = false,
    val lockedKeys: Int = 0,
    val lockedDatabases: Int = 0,
    val passwordCanUnlock: Boolean = false,
    val signedIn: Boolean = false,
    val passwordSet: Boolean = false,
) {
    val locked: Boolean get() = lockedKeys > 0 || lockedDatabases > 0
}

/**
 * Runs local encryption for the app's lifetime: encrypts the vault files left as plaintext by an earlier version,
 * moves the current key with the occupant of the app (a guest or one account), keeps the recovery copies of every
 * key written, and opens locked keys again when an account or the Hidden password can.
 */
@Singleton
class LocalEncryptionCoordinator @Inject constructor(
    @ApplicationContext private val context: Context,
    private val accountManager: AccountManager,
    private val envelopeCrypto: AddressKeyEnvelopeCrypto,
    private val passwordStore: VaultPasswordStore,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val keysLock = Mutex()
    private val _state = MutableStateFlow(LocalEncryptionState())
    val state: StateFlow<LocalEncryptionState> = _state.asStateFlow()

    /** Called once from the main process's start, before anything reads the vault. */
    fun start() {
        // Leftover scratch plaintext of an earlier process goes before any reader can make new scratch copies.
        runCatching { VaultFiles.clearScratch(context) }
        val keyring = LocalCrypto.keyringOrNull(context) ?: return
        keyring.onMinted = { scope.launch { afterMint() } }
        scope.launch {
            // The settings file is encrypted the first time it is opened, before any vault file is: an older
            // version installed over this one then stops at the settings rather than run with a vault it can
            // not read.
            runCatching { context.settingsDataStore.data.first() }
            runCatching { encryptVault() }.onFailure { logFailure("vault encryption", it) }
            runCatching { writeMissingPasswordCopies() }.onFailure { logFailure("password copies", it) }
            refresh()
        }
        scope.launch {
            accountManager.getPrimaryUserId().distinctUntilChanged().collect { userId ->
                runCatching { onOccupant(userId) }.onFailure { logFailure("occupant", it) }
                refresh()
            }
        }
        scope.launch {
            passwordStore.observeIsSet().collect { set -> _state.value = _state.value.copy(passwordSet = set) }
        }
    }

    /** Called by the sign-out cleanup once [userId] is gone: new files stop being written with its key. */
    suspend fun onSignedOut(userId: String) = withContext(Dispatchers.IO) {
        keysLock.withLock { LocalCrypto.custody(context)?.onSignedOut(userId) }
    }

    /**
     * Writes the password copies a key lacks when the stored password reads. A password that is not set or cannot
     * be read changes nothing: the copies are removed only when the user removes the password.
     */
    private suspend fun writeMissingPasswordCopies() {
        val stored = passwordStore.readState() as? StoredVaultPassword.Readable ?: return
        try {
            keysLock.withLock { LocalCrypto.custody(context)?.syncPasswordCopies(stored.password, rewrite = false) }
        } finally {
            stored.password.fill('\u0000')
        }
    }

    /** Whether [password] opens a password copy, for a stored password that cannot be read any more. */
    suspend fun passwordOpensCopies(password: CharArray): Boolean = withContext(Dispatchers.IO) {
        runCatching { keysLock.withLock { LocalCrypto.custody(context)?.passwordOpensCopies(password) } }
            .onFailure { logFailure("password check", it) }
            .getOrNull() == true
    }

    /** The stored password was stored again as [password]: only the copies a key lacks are written. */
    suspend fun onPasswordConfirmed(password: CharArray) = withContext(Dispatchers.IO) {
        runCatching {
            keysLock.withLock { LocalCrypto.custody(context)?.syncPasswordCopies(password, rewrite = false) }
        }.onFailure { logFailure("password copies", it) }
        refresh()
    }

    /** The user set the Hidden password to [password], or removed it (null): the password copies follow it. */
    suspend fun onPasswordChanged(password: CharArray?) = withContext(Dispatchers.IO) {
        runCatching {
            keysLock.withLock { LocalCrypto.custody(context)?.syncPasswordCopies(password, rewrite = true) }
        }.onFailure { logFailure("password copies", it) }
        refresh()
    }

    /** Opens what the password copies open with [password]. */
    suspend fun unlockWithPassword(password: CharArray): RecoveryOutcome = withContext(Dispatchers.IO) {
        val custody = LocalCrypto.custody(context) ?: return@withContext RecoveryOutcome(0, 0)
        val outcome = keysLock.withLock { custody.recoverWithPassword(password) }
        if (outcome.keys > 0) restoreLockedStores()
        // The stored password went with the same Keystore; the one that just opened a copy is stored again.
        if (outcome.any) runCatching { passwordStore.reseal(password) }.onFailure { logFailure("password reseal", it) }
        refresh()
        outcome
    }

    suspend fun refresh() {
        val status = withContext(Dispatchers.IO) { runCatching { LocalCrypto.custody(context)?.status() }.getOrNull() }
        val pending = withContext(Dispatchers.IO) { runCatching { migration()?.pendingFiles()?.size }.getOrNull() } ?: 0
        val signedIn = runCatching { accountManager.getPrimaryUserId().first() != null }.getOrDefault(false)
        _state.value = _state.value.copy(
            available = LocalCrypto.keyringOrNull(context) != null,
            pendingFiles = pending,
            lockedKeys = status?.lockedKeys ?: 0,
            lockedDatabases = status?.lockedDatabases ?: 0,
            passwordCanUnlock = status?.passwordCanUnlock ?: false,
            signedIn = signedIn,
        )
    }

    private suspend fun onOccupant(userId: UserId?) {
        val custody = LocalCrypto.custody(context) ?: return
        keysLock.withLock { custody.onOccupant(userId?.id) }
        if (userId == null) return
        // The address keys answer only once the account is ready; a failure here is tried again on the next start.
        var sealer = runCatching { EnvelopeAccountSealer.open(envelopeCrypto, userId) }.getOrNull() ?: return
        try {
            var outcome = recoverWithAccount(custody, userId, sealer)
            // A copy sealed to a key this phone has not listed yet (made on another device) needs a fresh list.
            if (outcome?.any != true && keysLock.withLock { runCatching { custody.lockedWithAccountCopy(userId.id) }.getOrDefault(false) }) {
                val fresh = runCatching { EnvelopeAccountSealer.open(envelopeCrypto, userId, refresh = true) }.getOrNull()
                if (fresh != null) {
                    sealer.close()
                    sealer = fresh
                    outcome = recoverWithAccount(custody, userId, sealer)
                }
            }
            writeAccountCopies(custody, userId, sealer)
            if ((outcome?.keys ?: 0) > 0) restoreLockedStores()
        } finally {
            sealer.close()
        }
    }

    private suspend fun recoverWithAccount(custody: LocalKeyCustody, userId: UserId, sealer: AccountSealer): RecoveryOutcome? =
        keysLock.withLock {
            runCatching { custody.recoverWithAccount(userId.id, sealer) }
                .onFailure { e -> logFailure("account recovery", e) }
                .getOrNull()
        }

    private suspend fun writeAccountCopies(custody: LocalKeyCustody, userId: UserId, sealer: AccountSealer) {
        keysLock.withLock {
            runCatching { custody.ensureAccountCopies(userId.id, sealer) }.onFailure { e -> logFailure("account copies", e) }
        }
    }

    /** A key was just made: its password copy and its account copy are written now, not on the next start. */
    private suspend fun afterMint() {
        runCatching { writeMissingPasswordCopies() }.onFailure { logFailure("password copies", it) }
        val userId = runCatching { accountManager.getPrimaryUserId().first() }.getOrNull() ?: return
        val custody = LocalCrypto.custody(context) ?: return
        val sealer = runCatching { EnvelopeAccountSealer.open(envelopeCrypto, userId) }.getOrNull() ?: return
        sealer.use { writeAccountCopies(custody, userId, it) }
    }

    private suspend fun restoreLockedStores() {
        val keyring = LocalCrypto.keyringOrNull(context) ?: return
        val locked = LocalCrypto.lockedStores(context)
        for (name in STORE_NAMES) {
            val store = LocalStores.named(name) ?: continue
            runCatching { LocalStores.restoreLocked(name, store, keyring, locked, LocalCrypto::log) }
                .onFailure { logFailure("$name restore", it) }
        }
    }

    private fun migration(): VaultMigration? {
        val keyring = LocalCrypto.keyringOrNull(context) ?: return null
        return VaultMigration(
            vaultDir = VaultFiles.vaultDir(context),
            stagingDir = VaultFiles.stagingDir(context),
            stateDir = LocalCrypto.root(context),
            keyring = keyring,
            lock = VaultFiles.lock,
            metaFor = { LocalFileMeta(captureTimeMs = HiddenCaptureTime.parse(it.name)) },
            log = LocalCrypto::log,
        )
    }

    private suspend fun encryptVault() {
        val base = migration() ?: return
        if (base.pendingFiles().isEmpty()) {
            LocalCrypto.markMigrated(context)
            return
        }
        // What the vault's records know about each file goes into its header, so the records can be rebuilt
        // from the files.
        val prefs = context.settingsDataStore.data.first()
        fun mapOf(entries: Set<String>?): Map<String, String> =
            entries.orEmpty().associate { it.substringBefore('|') to it.substringAfter('|', "") }
        val names = mapOf(prefs[SettingsKeys.HIDDEN_URI_ORIGINAL_NAME_MAP])
        val folders = mapOf(prefs[SettingsKeys.HIDDEN_URI_SOURCE_FOLDER_MAP])
        val cloudIds = mapOf(prefs[SettingsKeys.HIDDEN_URI_CLOUD_ID_MAP])
        val keyring = LocalCrypto.keyringOrNull(context) ?: return
        val migration = VaultMigration(
            vaultDir = VaultFiles.vaultDir(context),
            stagingDir = VaultFiles.stagingDir(context),
            stateDir = LocalCrypto.root(context),
            keyring = keyring,
            lock = VaultFiles.lock,
            metaFor = { file ->
                val uri = Uri.fromFile(file).toString()
                LocalFileMeta(
                    originalName = names[uri]?.takeIf { it.isNotBlank() },
                    mimeType = mimeFromPath(file.name),
                    sourceFolder = folders[uri]?.takeIf { it.isNotBlank() },
                    cloudLinkId = cloudIds[uri]?.takeIf { it.isNotBlank() },
                    captureTimeMs = HiddenCaptureTime.parse(file.name),
                )
            },
            log = LocalCrypto::log,
        )
        val outcome = migration.run()
        _state.value = _state.value.copy(pendingFiles = outcome.remaining, pausedForSpace = outcome.stoppedForSpace)
        if (outcome.complete) LocalCrypto.markMigrated(context)
    }

    private fun logFailure(what: String, e: Throwable) {
        if (e is CancellationException) throw e
        LocalCrypto.log("local encryption: $what failed (${e.javaClass.simpleName})")
    }

    companion object {
        /** The private stores whose files are encrypted, by their file name. */
        val STORE_NAMES = listOf("settings", "transfer_history")
    }
}

/** Seals recovery copies to an account's Proton address key with the envelope the ML data uses. */
internal class EnvelopeAccountSealer(
    private val crypto: EnvelopeCrypto,
    private val sealKeys: EnvelopeSealKeys,
    private val openKeys: EnvelopeOpenKeys,
) : AccountSealer, Closeable {

    override fun seal(payload: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        MlSnapshotEnvelope.seal(payload.inputStream(), out, crypto, sealKeys, chunkSize = CHUNK_BYTES)
        return out.toByteArray()
    }

    override fun open(blob: ByteArray): ByteArray? {
        val out = ByteArrayOutputStream()
        val result = MlSnapshotEnvelope.open(blob.inputStream(), out, crypto, openKeys)
        return if (result is EnvelopeOpenResult.Opened) out.toByteArray() else null
    }

    /** The current sealing key, named by a digest of its public key. */
    override val sealId: String by lazy {
        java.security.MessageDigest.getInstance("SHA-256")
            .digest(sealKeys.recipientPublicKey.toByteArray(Charsets.UTF_8))
            .take(SEAL_ID_BYTES).joinToString("") { "%02x".format(it) }
    }

    override fun close() {
        sealKeys.close()
        openKeys.close()
    }

    companion object {
        private const val CHUNK_BYTES = 4096
        private const val SEAL_ID_BYTES = 16

        /** Seals to the account's current address key and opens with every key of every address it has, the
         *  disabled ones and the old ones of a rotation included. [refresh] refetches the address list first. */
        suspend fun open(crypto: AddressKeyEnvelopeCrypto, userId: UserId, refresh: Boolean = false): EnvelopeAccountSealer {
            val seal = crypto.sealKeys(userId)
            val open = try {
                crypto.openKeys(userId, refreshAddresses = refresh, includeDisabled = true)
            } catch (e: Exception) {
                seal.close()
                throw e
            }
            return EnvelopeAccountSealer(crypto, seal, open)
        }
    }
}
