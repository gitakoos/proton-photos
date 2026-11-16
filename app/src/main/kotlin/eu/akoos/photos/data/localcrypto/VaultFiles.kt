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
import androidx.datastore.preferences.core.edit
import eu.akoos.photos.BuildConfig
import eu.akoos.photos.data.db.encryption.DatabaseKeyState
import eu.akoos.photos.data.preferences.SettingsKeys
import eu.akoos.photos.data.preferences.settingsDataStore
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Reading and writing the hidden vault's files, encrypted or not. Every vault file keeps its name and its
 * `file://` uri (they are its identity in every index); only its bytes are encrypted. Every reader here accepts
 * both forms, so a vault halfway through its migration reads whole.
 *
 * Scratch plaintext (for an editor or a decoder that needs a real file) lives in `no_backup/vault_work`, is
 * deleted after use and cleared on every start.
 */
object VaultFiles {
    const val VAULT_DIR = "hidden"
    private const val STAGING_DIR = ".hidden_staging"
    private const val WORK_DIR = "vault_work"
    private const val SEAL_SUFFIX = ".seal-tmp"

    /** Held for every change to a vault file's bytes or name, so the migration never races an edit or a reveal. */
    val lock = ReentrantLock()

    fun vaultDir(context: Context): File = File(context.applicationContext.filesDir, VAULT_DIR)

    fun stagingDir(context: Context): File = File(context.applicationContext.filesDir, STAGING_DIR)

    fun workDir(context: Context): File = File(context.applicationContext.noBackupFilesDir, WORK_DIR)

    /** The vault file [uri] names, or null when it names no file inside the vault. */
    fun fileOf(context: Context, uri: Uri): File? {
        if (uri.scheme != "file") return null
        val path = uri.path ?: return null
        val file = File(path)
        return file.takeIf { isInVault(context, it) }
    }

    fun fileOf(context: Context, uri: String): File? = runCatching { Uri.parse(uri) }.getOrNull()?.let { fileOf(context, it) }

    fun isInVault(context: Context, file: File): Boolean {
        val parent = file.absoluteFile.parentFile ?: return false
        return parent.absolutePath == vaultDir(context).absolutePath
    }

    fun isEncrypted(file: File): Boolean = LocalCryptoFormat.isEncrypted(file)

    /** Whether [uri] names a scratch copy of a vault file. */
    fun isScratch(context: Context, uri: String): Boolean {
        val parsed = runCatching { Uri.parse(uri) }.getOrNull() ?: return false
        if (parsed.scheme != "file") return false
        val parent = File(parsed.path ?: return false).absoluteFile.parentFile ?: return false
        return parent.absolutePath == workDir(context).absolutePath
    }

    /**
     * The uri a reader should open for [uri]: a file in an encrypted folder (the vault, the full-size and
     * thumbnail caches, the offline copies) is served decrypted by [VaultReadProvider]; any other uri comes back
     * as it is. For readers that open what they are given through the content resolver.
     */
    fun readableUri(context: Context, uri: Uri): Uri = LocalFiles.readableUri(context, uri)

    fun readableUri(context: Context, uri: String): String = readableUri(context, Uri.parse(uri)).toString()

    /** The plaintext of a vault file. Throws [LocalKeyLockedException], [LocalKeyUnavailableException] or
     *  [LocalFileDamagedException] for an encrypted file that does not open. */
    fun openPlain(context: Context, file: File): InputStream = LocalFiles.openPlain(context, file)

    fun openReader(context: Context, file: File): LocalFileReader {
        val keyring = LocalCrypto.keyringOrNull(context) ?: throw LocalKeyUnavailableException(null)
        return LocalCryptoFormat.openFile(file, keyring)
    }

    /** The size of a vault file's plaintext, from its header for an encrypted one. */
    fun plainLength(file: File): Long = if (isEncrypted(file)) LocalCryptoFormat.plainSize(file) ?: 0L else file.length()

    /** True for a plaintext file, false for an encrypted one; throws [LocalFileUnreadableException] when its
     *  start cannot be read, so nothing takes its raw bytes for the photo. */
    fun plainForm(file: File): Boolean = when (LocalCryptoFormat.formOf(file)) {
        LocalFileForm.PLAIN -> true
        LocalFileForm.ENCRYPTED -> false
        LocalFileForm.UNKNOWN -> throw LocalFileUnreadableException(file)
    }

    /** What an encrypted vault file says about itself, or null (plaintext, or it does not open). */
    fun meta(context: Context, file: File): LocalFileMeta? {
        if (!isEncrypted(file)) return null
        return runCatching { openReader(context, file).use { it.meta } }.getOrNull()
    }

    /** Whether [file] is encrypted with a key this phone cannot open now. */
    fun isLocked(context: Context, file: File): Boolean {
        if (!isEncrypted(file)) return false
        val keyring = LocalCrypto.keyringOrNull(context) ?: return true
        val keyId = LocalCryptoFormat.keyIdOf(file) ?: return true
        return keyring.state(keyId) !is DatabaseKeyState.Ready
    }

    /**
     * A plaintext copy of [file] in the scratch folder, proved whole and checked to hold no encrypted bytes; the
     * caller deletes it. Read from one open of [file], so an encryption in place at the same moment cannot mix the
     * two forms.
     */
    fun materialize(context: Context, file: File): File {
        val dir = workDir(context).apply { mkdirs() }
        val work = File(dir, "${UUID.randomUUID()}.${file.extension.ifBlank { "bin" }}")
        try {
            val copied = CacheFiles.inPlaceLock.readLock().withLock {
                FileOutputStream(work).use { LocalFiles.copyPlain(context, file, it) }
            }
            if (work.length() != copied) throw IOException("scratch copy incomplete")
            LocalCryptoFormat.requirePlain(work)
        } catch (e: Throwable) {
            work.delete()
            throw e
        }
        return work
    }

    /** Runs [block] with a plaintext scratch copy of [file] ([materialize]), deleted afterwards, never with [file]
     *  itself, which may be encrypted in place while it runs. */
    inline fun <T> withPlainFile(context: Context, file: File, block: (File) -> T): T {
        val work = materialize(context, file)
        try {
            return block(work)
        } finally {
            work.delete()
        }
    }

    /**
     * Encrypts [plain] into a new vault file named [name] (or replacing it): written to a temp of its own in the
     * staging folder, synced, decrypted again and compared, then moved into the vault in one step. The whole of it
     * runs under the vault lock, so two writes of the same vault file never share or interleave. Without a keyring
     * (no Keystore on this platform) the plaintext is moved in as it is. With [requireExisting] the file must still
     * be in the vault when the new one replaces it (an edit of a file renamed meanwhile writes nothing). Throws on
     * any failure; the vault is unchanged then.
     */
    fun sealInto(context: Context, plain: File, name: String, meta: LocalFileMeta, requireExisting: Boolean = false): File =
        lock.withLock {
            val keyring = LocalCrypto.keyringOrNull(context)
            val staging = stagingDir(context).apply { mkdirs() }
            val temp = File(staging, "${UUID.randomUUID()}$SEAL_SUFFIX")
            try {
                val written = FileOutputStream(temp).use { out ->
                    val digest = if (keyring == null) {
                        FileInputStream(plain).use { input -> PlainDigest.of(TeeInput(input, out)) }
                    } else {
                        val current = keyring.current()
                        FileInputStream(plain).use { input ->
                            LocalCryptoFormat.encrypt(
                                input,
                                out,
                                LocalCryptoFormat.Sealing(current.keyId, current.key, LocalFileKind.VAULT, meta),
                            )
                        }
                    }
                    out.flush()
                    out.fd.sync()
                    digest
                }
                val source = FileInputStream(plain).use { PlainDigest.of(it) }
                check(written.sameAs(source)) { "plaintext changed while sealing" }
                val readBack = if (keyring == null) FileInputStream(temp).use { PlainDigest.of(it) }
                else LocalCryptoFormat.decryptFile(temp, keyring, null)
                check(readBack.sameAs(source)) { "sealed copy did not verify" }
                val dest = File(vaultDir(context).apply { mkdirs() }, name)
                if (requireExisting && !dest.exists()) throw IOException("vault file went away")
                Files.move(temp.toPath(), dest.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                syncDirectory(dest.parentFile)
                dest
            } catch (e: Throwable) {
                temp.delete()
                throw e
            }
        }

    /**
     * Runs an in-place editor [edit] (EXIF, a new date) on [uri]. A uri outside the vault is handed to it as it is.
     * A vault file is edited as a plaintext scratch copy that is sealed back under the same name only when
     * [changed] says the result is a change, so the vault file is never written half way. A file that does not
     * open, or a sealing that fails, answers [onError] and leaves the vault file as it was.
     *
     * A changed vault file is marked edited ([SettingsKeys.HIDDEN_URI_EDITED]) before it is sealed: a Drive copy
     * recorded for it holds only the version before the change, so a sign-out must no longer count on that copy.
     * The mark goes first, so an interruption between the two only ever leaves an unchanged file marked.
     */
    fun <T> editingInPlace(context: Context, uri: String, changed: (T) -> Boolean, onError: (Exception) -> T, edit: (String) -> T): T {
        val vault = fileOf(context, uri) ?: return edit(uri)
        return try {
            val meta = meta(context, vault) ?: LocalFileMeta(captureTimeMs = eu.akoos.photos.util.HiddenCaptureTime.parse(vault.name))
            val work = materialize(context, vault)
            try {
                val result = edit(Uri.fromFile(work).toString())
                if (changed(result)) {
                    markEdited(context, setOf(uri, Uri.fromFile(vault).toString()))
                    sealInto(context, work, vault.name, meta, requireExisting = true)
                }
                result
            } finally {
                work.delete()
            }
        } catch (e: Exception) {
            onError(e)
        }
    }

    /** Records [uris] as changed since their photo was hidden. Blocking, as every caller of [editingInPlace] is. */
    private fun markEdited(context: Context, uris: Set<String>) {
        kotlinx.coroutines.runBlocking {
            context.settingsDataStore.edit { p ->
                p[SettingsKeys.HIDDEN_URI_EDITED] = (p[SettingsKeys.HIDDEN_URI_EDITED] ?: emptySet()) + uris
            }
        }
    }

    /**
     * Removes scratch and staging leftovers of an earlier process. Nothing in either is ever the only copy. A copy
     * handed to another app stays for [SHARE_KEEP_MS], so a receiver that reads it after a restart still can; an
     * unfinished one goes.
     */
    fun clearScratch(context: Context, now: Long = System.currentTimeMillis()) {
        workDir(context).deleteRecursively()
        stagingDir(context).deleteRecursively()
        val share = File(context.applicationContext.cacheDir, SHARE_DIR)
        File(share, SHARE_WORK_DIR).deleteRecursively()
        sweepShareCopies(share, now - SHARE_KEEP_MS)
        File(context.applicationContext.cacheDir, PLAIN_STAGING_DIR).deleteRecursively()
    }

    /** Removes the copies in the share folder [dir] (and its unfinished ones) made before [cutoff]. */
    fun sweepShareCopies(dir: File, cutoff: Long) {
        val files = dir.listFiles().orEmpty().toList() + File(dir, SHARE_WORK_DIR).listFiles().orEmpty()
        for (f in files) if (f.isFile && f.lastModified() < cutoff) runCatching { f.delete() }
    }

    /** How long a copy handed to another app is kept for it to read. */
    const val SHARE_KEEP_MS = 24L * 60 * 60 * 1000

    /** Where a share copy is made before it is sealed into the share folder. */
    const val SHARE_WORK_DIR = "work"

    /**
     * The one folder for the plaintext copies a library can only take as a file path (the EXIF writer, the media
     * muxer and transcoder, the frame grabber, the upload's hash and block reads): upload, import and editor-save
     * copies of a photo. Each is deleted right after use by its own step, and the folder is emptied at every start.
     */
    fun plainStagingDir(context: Context): File = File(context.cacheDir, PLAIN_STAGING_DIR).apply { mkdirs() }

    const val PLAIN_STAGING_DIR = "plain_staging"

    /** Name starts of the plaintext copies an earlier version made straight in the cache folder. */
    private val LEGACY_PLAIN_PREFIXES = listOf(
        "compressed_", "stripped_", "videocompress_", "upl_ts_", "motion_src_", "motion_primary_", "motion_out_",
        "motion_check_", "gainmap_src_", "gainmap_primary_", "gainmap_out_", "import_", "stage_",
    )

    /**
     * Removes the plaintext copies an earlier version made in the cache folder itself and in its editor folder,
     * written before [before] (the start of this process), so never one this process is still using.
     */
    fun sweepLegacyPlainCopies(context: Context, before: Long) {
        val cache = context.applicationContext?.cacheDir ?: context.cacheDir
        val files = cache.listFiles().orEmpty().filter { f -> LEGACY_PLAIN_PREFIXES.any { f.name.startsWith(it) } } +
            File(cache, "editor").listFiles().orEmpty()
        for (f in files) if (f.isFile && f.lastModified() < before) runCatching { f.delete() }
    }

    /** Where a copy handed to another app (share, set as) lives, encrypted, for [SHARE_KEEP_MS]. */
    const val SHARE_DIR = "vault_share"

    internal fun debugTrace(message: String) {
        if (BuildConfig.DEBUG) android.util.Log.d("VaultFiles", message)
    }
}

/** Copies what is read from [input] into [out] as it goes. */
internal class TeeInput(private val input: InputStream, private val out: java.io.OutputStream) : InputStream() {
    override fun read(): Int = input.read().also { if (it >= 0) out.write(it) }

    override fun read(b: ByteArray, off: Int, len: Int): Int = input.read(b, off, len).also { if (it > 0) out.write(b, off, it) }

    override fun close() = input.close()
}
