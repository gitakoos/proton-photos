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
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
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
import eu.akoos.photos.data.transfer.transferHistoryDataStore
import eu.akoos.photos.util.HiddenCaptureTime
import eu.akoos.photos.util.mimeFromPath
import eu.akoos.photos.util.sweepLegacyShareCopies
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
import java.io.File
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
    /** A guest without a Hidden password just hid a photo and has not been told what that means yet. */
    val guestHideWarning: Boolean = false,
) {
    val locked: Boolean get() = lockedKeys > 0 || lockedDatabases > 0
}

/** What the rest of the app tells local encryption about. */
object LocalEncryptionEvents {
    private val _photoHidden = kotlinx.coroutines.flow.MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    internal val photoHidden: kotlinx.coroutines.flow.SharedFlow<Unit> = _photoHidden

    /** A photo was sealed into the vault by a hide. */
    fun photoHidden() {
        _photoHidden.tryEmit(Unit)
    }
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
        // Before anything here or elsewhere encrypts: whether this install held data from an earlier version.
        LocalCrypto.recordLegacyData(context)
        // Leftover scratch plaintext of an earlier process goes before any reader can make new scratch copies.
        runCatching { VaultFiles.clearScratch(context) }
        CacheFiles.init(context)
        // Motion photo clips and probe copies are only ever needed while one viewer page is open.
        runCatching { LocalFiles.dir(context, LocalFiles.Area.MOTION).deleteRecursively() }
        val keyring = LocalCrypto.keyringOrNull(context) ?: return
        keyring.onMinted = { scope.launch { afterMint() } }
        scope.launch {
            runCatching { CacheFiles.dropAfterKeyLoss(context, keyring, LocalCrypto::log) }
                .onFailure { logFailure("cache drop", it) }
            // A little before this process started, so a temp it is writing now is never taken.
            runCatching { CacheFiles.sweepWriteTemps(context, before = LocalCrypto.processStartedAt() - TEMP_SWEEP_MARGIN_MS) }
                .onFailure { logFailure("temp sweep", it) }
            runCatching { sweepLegacyShareCopies(context, before = LocalCrypto.processStartedAt() - TEMP_SWEEP_MARGIN_MS) }
                .onFailure { logFailure("share copy sweep", it) }
            runCatching { encryptPlainCaches() }.onFailure { logFailure("plain cache encryption", it) }
        }
        scope.launch {
            // The settings file is encrypted the first time it is opened, before any vault file is: an older
            // version installed over this one then stops at the settings rather than run with a vault it can
            // not read.
            runCatching { context.settingsDataStore.data.first() }
            // Settings kept aside while their key did not open come back as soon as it does, every start, not only
            // after a recovery.
            runCatching { restoreLockedStores() }.onFailure { logFailure("locked settings", it) }
            runCatching { encryptVault() }.onFailure { logFailure("vault encryption", it) }
            runCatching { encryptOffline() }.onFailure { logFailure("offline encryption", it) }
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
        scope.launch {
            LocalEncryptionEvents.photoHidden.collect { runCatching { checkGuestHideWarning() } }
        }
    }

    /**
     * A guest's hidden photos are encrypted with a key only this phone's secure storage holds: without a Hidden
     * password nothing can open them if that key is lost. Said once, after the first hide that finds it so.
     */
    private suspend fun checkGuestHideWarning() {
        if (_state.value.guestHideWarning) return
        // An answer that cannot be read counts as signed in or set: the warning is only for a sure case.
        val signedIn = runCatching { accountManager.getPrimaryUserId().first() != null }.getOrDefault(true)
        val passwordSet = runCatching { passwordStore.observeIsSet().first() }.getOrDefault(true)
        if (guestHideWarningDue(signedIn, passwordSet, LocalCrypto.isGuestHideWarned(context))) {
            _state.value = _state.value.copy(guestHideWarning = true)
        }
    }

    fun dismissGuestHideWarning() {
        _state.value = _state.value.copy(guestHideWarning = false)
        scope.launch { LocalCrypto.markGuestHideWarned(context) }
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
        for ((name, store) in encryptedStores(context)) {
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

    /**
     * Encrypts, in place and with the vault's verify-then-swap, the offline copies an earlier version pinned in
     * plain. Nothing is downloaded again; a copy that does not encrypt stays readable as it is.
     */
    private fun encryptOffline() {
        val keyring = LocalCrypto.keyringOrNull(context) ?: return
        val dir = LocalFiles.dir(context, LocalFiles.Area.OFFLINE)
        if (!dir.isDirectory) return
        offlineMigration(context, keyring).run()
    }

    /** Encrypts the plain cache files an earlier version left, until a run finds none (then never again). */
    private fun encryptPlainCaches() {
        val marker = File(LocalCrypto.root(context), PLAIN_CACHES_DONE_MARKER)
        if (marker.exists()) return
        val before = LocalCrypto.processStartedAt() - TEMP_SWEEP_MARGIN_MS
        if (CacheFiles.encryptPlainLeftovers(context, before)) {
            runCatching { eu.akoos.photos.data.db.encryption.writeWhole(marker, byteArrayOf(1)) }
            LocalCrypto.log("plain cache files encrypted")
        }
    }

    private fun logFailure(what: String, e: Throwable) {
        if (e is CancellationException) throw e
        LocalCrypto.log("local encryption: $what failed (${e.javaClass.simpleName})")
    }

    companion object {
        /** The private stores whose files are encrypted, by their file name. */
        val STORE_NAMES = listOf("settings", "transfer_history")

        private const val TEMP_SWEEP_MARGIN_MS = 2_000L
        private const val PLAIN_CACHES_DONE_MARKER = "plain_caches_encrypted"
    }
}

/**
 * Every settings file kept encrypted, by name, opened here (which registers it), so a locked copy of each comes back
 * on the start its key answers, even before anything else has opened that file.
 */
internal fun encryptedStores(context: Context): Map<String, DataStore<Preferences>> = mapOf(
    "settings" to context.settingsDataStore,
    "transfer_history" to context.transferHistoryDataStore,
)

/** The migration that encrypts the plaintext offline copies in place, sharing the offline store's lock. */
internal fun offlineMigration(context: Context, keyring: LocalKeyring, log: (String) -> Unit = LocalCrypto::log) = VaultMigration(
    vaultDir = LocalFiles.dir(context, LocalFiles.Area.OFFLINE),
    stagingDir = File(context.applicationContext.filesDir, LocalFiles.OFFLINE_STAGING_DIR),
    stateDir = LocalCrypto.root(context),
    keyring = keyring,
    lock = LocalFiles.offlineLock,
    metaFor = { LocalFileMeta(mimeType = mimeFromPath(it.name)) },
    log = log,
    kind = LocalFileKind.CACHE,
    accept = { !it.name.startsWith(".") && !it.name.endsWith(".tmp") },
    attemptsName = "offline_attempts",
    label = "offline",
)

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

/** A hide warns only a guest without a Hidden password who was not warned before. */
internal fun guestHideWarningDue(signedIn: Boolean, passwordSet: Boolean, warned: Boolean): Boolean =
    !signedIn && !passwordSet && !warned
