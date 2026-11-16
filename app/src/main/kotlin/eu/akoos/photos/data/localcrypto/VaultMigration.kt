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

import eu.akoos.photos.data.db.encryption.writeWhole
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Encrypts the plaintext files of the hidden vault one at a time, in place of each other, so a hidden photo is
 * never anywhere but in the vault.
 *
 * For each file: the encrypted copy is written beside the vault and synced, decrypted again and compared with the
 * plaintext, and only then moved over the plaintext in one atomic rename, so at every moment the vault holds one
 * whole copy of the photo, plaintext or encrypted. A process killed part way leaves at most a staging copy, which
 * the next run deletes, and the plaintext it was made from. A file is skipped while there is not room for its
 * encrypted copy plus a margin, and given up after [MAX_ATTEMPTS] failed tries (it stays readable as plaintext).
 */
class VaultMigration(
    private val vaultDir: File,
    private val stagingDir: File,
    private val stateDir: File,
    private val keyring: LocalKeyring,
    private val lock: ReentrantLock,
    private val metaFor: (File) -> LocalFileMeta,
    private val freeBytes: (File) -> Long = { it.usableSpace },
    private val log: (String) -> Unit = {},
    /** Called between steps, for tests that stop the run at a given point. */
    private val checkpoint: (Step, File) -> Unit = { _, _ -> },
    private val formOf: (File) -> LocalFileForm = { LocalCryptoFormat.formOf(it) },
) {
    enum class Step { STAGED, VERIFIED, MOVED }

    data class Outcome(
        val encrypted: Int,
        /** Plaintext files still in the vault (skipped for space, given up, or failed this time). */
        val remaining: Int,
        val stoppedForSpace: Boolean,
        val failed: Int,
    ) {
        val complete: Boolean get() = remaining == 0
    }

    fun run(): Outcome {
        stagingDir.listFiles()?.filter { it.name.endsWith(STAGING_SUFFIX) }?.forEach { it.delete() }
        val attempts = readAttempts()
        var encrypted = 0
        var failed = 0
        var stoppedForSpace = false
        val files = plaintextFiles()
        for (file in files) {
            if ((attempts[file.name] ?: 0) >= MAX_ATTEMPTS) continue
            val needed = file.length() + LocalCryptoFormat.overheadFor(file.length()) + SPACE_MARGIN_BYTES
            if (freeBytes(vaultDir) < needed) {
                log("vault encryption: paused, not enough free space")
                stoppedForSpace = true
                break
            }
            try {
                if (encryptOne(file)) encrypted++
            } catch (e: Exception) {
                failed++
                attempts[file.name] = (attempts[file.name] ?: 0) + 1
                writeAttempts(attempts)
                log("vault encryption: a file did not encrypt (${e.javaClass.simpleName})")
            }
        }
        // A file that could not be read is not done either: the run stays unfinished and looks again next time.
        val remaining = pendingFiles().size
        if (encrypted > 0 || failed > 0) log("vault encryption: $encrypted encrypted, $failed failed, $remaining left")
        return Outcome(encrypted, remaining, stoppedForSpace, failed)
    }

    /** The vault's files not known to be encrypted: plaintext ones and ones that could not be read. */
    fun pendingFiles(): List<File> =
        vaultDir.listFiles()
            ?.filter { it.isFile && !it.name.startsWith(".") && formOf(it) != LocalFileForm.ENCRYPTED }
            ?: emptyList()

    /** The vault's files that are plaintext, the ones to encrypt. */
    fun plaintextFiles(): List<File> =
        vaultDir.listFiles()
            ?.filter { it.isFile && !it.name.startsWith(".") && formOf(it) == LocalFileForm.PLAIN }
            ?.sortedBy { it.name }
            ?: emptyList()

    private fun encryptOne(file: File): Boolean = lock.withLock {
        // Under the vault lock: no edit, reveal or rename changes this file until it is replaced.
        if (!file.isFile || formOf(file) != LocalFileForm.PLAIN) return false
        stagingDir.mkdirs()
        val temp = File(stagingDir, file.name + STAGING_SUFFIX)
        try {
            val current = keyring.current()
            val digest = FileOutputStream(temp).use { out ->
                val d = FileInputStream(file).use { input ->
                    LocalCryptoFormat.encrypt(
                        input,
                        out,
                        LocalCryptoFormat.Sealing(current.keyId, current.key, LocalFileKind.VAULT, metaFor(file)),
                    )
                }
                out.flush()
                out.fd.sync()
                d
            }
            checkpoint(Step.STAGED, temp)
            check(digest.bytes == file.length()) { "plaintext changed while encrypting" }
            val readBack = LocalCryptoFormat.decryptFile(temp, keyring, null)
            check(readBack.sameAs(digest)) { "encrypted copy did not verify" }
            val again = FileInputStream(file).use { PlainDigest.of(it) }
            check(again.sameAs(digest)) { "plaintext changed while encrypting" }
            checkpoint(Step.VERIFIED, temp)
            temp.setLastModified(file.lastModified())
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            syncDirectory(vaultDir)
            checkpoint(Step.MOVED, file)
            true
        } catch (e: Exception) {
            temp.delete()
            throw e
        }
    }

    private val attemptsFile get() = File(stateDir, ATTEMPTS_FILE)

    private fun readAttempts(): MutableMap<String, Int> {
        val text = runCatching { attemptsFile.readText() }.getOrNull() ?: return mutableMapOf()
        return text.lineSequence()
            .mapNotNull { line ->
                val name = line.substringBeforeLast(' ', "")
                val count = line.substringAfterLast(' ').toIntOrNull()
                if (name.isBlank() || count == null) null else name to count
            }
            .toMap(mutableMapOf())
    }

    private fun writeAttempts(attempts: Map<String, Int>) {
        runCatching {
            stateDir.mkdirs()
            writeWhole(attemptsFile, attempts.entries.joinToString("\n") { "${it.key} ${it.value}" }.toByteArray())
        }
    }

    companion object {
        const val MAX_ATTEMPTS = 3
        const val SPACE_MARGIN_BYTES = 50L * 1024 * 1024
        const val STAGING_SUFFIX = ".enc-tmp"
        private const val ATTEMPTS_FILE = "vault_attempts"
    }
}
