/*
 * Photos for Proton
 * Copyright (C) 2026 Akoos <https://akoos.eu>
 *
 * Source:  https://github.com/gitakoos/proton-photos
 * Website: https://photos.akoos.eu
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

import android.util.Base64
import android.util.Log
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

private const val TAG = "UploadResume"

/**
 * Persistent sidecar that lets [PhotoUploadService] resume a partially-encrypted upload
 * after a transient network failure instead of restarting from block 0.
 *
 * # Why this exists
 * The per-file encrypt loop spills each 4 MB block to `cacheDir/upload_<id>/block_<idx>.enc`.
 * Previously, the outer `try/finally` wiped the whole tempDir on every exit path — including
 * retry-eligible failures like a DNS hiccup partway through the encrypt batch. For a
 * 100 MB photo at 4 MB blocks, the next attempt then re-encrypted all 25 blocks even
 * though 20 of them already sat fully encrypted, signed, hashed and verifier-tagged on disk.
 * Re-encrypt is the dominant CPU cost (PGP SEIPD + detached sign through the libgojni
 * Go runtime), so this was the most visible source of "the upload spinner won't go past
 * 30 %" reports on flaky mobile networks.
 *
 * # Stable upload identifier
 * The tempDir is keyed on a stable hash of the source URI + plaintext file size so a
 * subsequent [PhotoUploadService.uploadFile] call for the SAME source file finds and
 * reuses the cached blocks even though the caller's batch loop produces a fresh
 * `fileId` from the Drive server each time. The size component invalidates the cache
 * the moment the underlying media file changes (different bytes → different total size
 * is the cheap-to-check signal; anything subtler would need a content hash that we
 * already compute upstream in [UploadPendingUseCase]).
 *
 * # Why the session key is in the manifest
 * The encrypted blocks ARE the session key's ciphertext. To reuse them on the next
 * attempt we MUST keep the same session key, which means persisting it alongside the
 * blocks. The manifest lives in `context.cacheDir` (app-private storage), which Android
 * encrypts at rest on every supported device per file-based encryption (FBE). The
 * plaintext source media sits in the SAME sandbox the moment the user picks "Save",
 * so persisting the session key for the resume window does not move the threat model.
 *
 * # Schema versioning
 * The schema field is the single source of truth for compatibility. A future change
 * to the manifest layout bumps `version`, and [load] returns null on mismatch so the
 * read path falls back to "wipe + start fresh" rather than mis-interpreting stale data.
 */
@Serializable
internal data class UploadResumeManifest(
    @SerialName("version") val version: Int = SCHEMA_VERSION,
    /** Number of blocks already encrypted + spilled to disk. Index 0..completedBlocks-1
     *  have valid `block_<idx>.enc` files AND a matching entry in [blocks]. */
    @SerialName("completedBlocks") val completedBlocks: Int,
    /** Expected total block count for this file, computed from `sizeBytes / blockSize`.
     *  Used purely as a sanity check on load — if the persisted total disagrees with the
     *  freshly recomputed value, the source file changed and we must redo from scratch. */
    @SerialName("totalBlocks") val totalBlocks: Int,
    @SerialName("blockSize") val blockSize: Int,
    @SerialName("sizeBytes") val sizeBytes: Long,
    /** Server-issued file identifier from createPhoto / createFileByVolume. Reused on
     *  resume so the cached blocks correlate to the right Drive record. If the server
     *  has TTL-cleaned this stale uncommitted file by the time we resume, commit fails
     *  and the caller's next batch will start fresh (the wipe in the non-retryable
     *  branch of the finally takes care of it). */
    @SerialName("fileId") val fileId: String,
    @SerialName("revisionId") val revisionId: String,
    /** True iff createFileByVolume was used for [fileId]. Drives which commit endpoint
     *  the resumed upload calls — v2 (true) vs legacy share-based commit (false). */
    @SerialName("useVolumeEndpoints") val useVolumeEndpoints: Boolean,
    @SerialName("sessionKeyB64") val sessionKeyB64: String,
    @SerialName("verificationCodeB64") val verificationCodeB64: String,
    /** ContentKeyPacket base64 — needed only by the legacy share-based commit endpoint
     *  (`useVolumeEndpoints == false`). The v2 commit endpoint discovers it from the
     *  server-side fileId. Persisted unconditionally so a manifest produced on one path
     *  is also usable if the resume path picks a different commit endpoint. */
    @SerialName("contentKeyPacketB64") val contentKeyPacketB64: String,
    @SerialName("contentKeyPacketSignature") val contentKeyPacketSignature: String,
    /** Armored public key of the per-file node. Needed by [signBlockEncrypted] when
     *  resuming to sign blocks that haven't been encrypted yet. Public key material —
     *  safe to keep on disk. */
    @SerialName("nodePublicKeyArmored") val nodePublicKeyArmored: String,
    @SerialName("blocks") val blocks: List<BlockManifest>,
) {
    @Serializable
    internal data class BlockManifest(
        @SerialName("index") val index: Int,
        @SerialName("encSignature") val encSignature: String,
        /** Base64 of SHA-256(encryptedBlock). Stored b64 rather than raw bytes because
         *  the manifest is JSON. */
        @SerialName("hashB64") val hashB64: String,
        @SerialName("verifierTokenB64") val verifierTokenB64: String,
        @SerialName("encSize") val encSize: Long,
        @SerialName("plaintextSize") val plaintextSize: Long,
    )

    companion object {
        const val SCHEMA_VERSION: Int = 1

        /** Filename of the sidecar inside the upload tempDir. */
        const val FILENAME: String = "progress.json"

        /** Tempfile suffix used by the atomic write — file is written then renamed. */
        private const val TMP_SUFFIX: String = ".tmp"

        /** Tempdirs older than this on next sweep get wiped regardless of state. Generous
         *  because mobile users go offline for days at a time and we want resumption to
         *  still work when they come back — but bounded so a permanently-stale tempDir
         *  from a bug doesn't accumulate forever. */
        val STALE_TTL_MS: Long = TimeUnit.DAYS.toMillis(7)

        private val json: Json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            prettyPrint = false
        }

        /**
         * Stable per-upload directory name derived from the source URI + plaintext size.
         * Survives across separate [PhotoUploadService.uploadFile] calls for the same
         * file so the caller's retry loop reuses the cached encrypted blocks.
         *
         * The size component is the cheap-to-check invalidation signal: if the user
         * re-saves the source photo through the editor between attempts, its size is
         * almost certainly different and the hash mismatch directs us to the fresh-start
         * path. (A 1-in-2^48 collision on the truncated SHA-256 is acceptable for cache
         * keying — a collision would just mean we tried to resume the wrong photo,
         * which fails at the server commit and falls back to a fresh upload.)
         */
        fun stableTempDirName(uploadUri: String, sizeBytes: Long): String {
            val md = MessageDigest.getInstance("SHA-256")
            md.update(uploadUri.toByteArray(Charsets.UTF_8))
            md.update(":".toByteArray(Charsets.UTF_8))
            md.update(sizeBytes.toString().toByteArray(Charsets.UTF_8))
            val digest = md.digest()
            // 12 bytes / 24 hex chars is plenty for cache keying — the dir name lives
            // inside the app's private cache so cross-app collision isn't a concern.
            val hex = digest.take(12).joinToString("") { "%02x".format(it) }
            return "upload_$hex"
        }

        /**
         * Loads the manifest from [tempDir]. Returns null when:
         *  - the sidecar is missing
         *  - the JSON cannot be parsed
         *  - the schema version is unknown
         *  - the per-block file count on disk disagrees with [UploadResumeManifest.completedBlocks]
         *
         * Any null return signals the caller to wipe the tempDir and start fresh.
         */
        fun load(tempDir: File): UploadResumeManifest? {
            val file = File(tempDir, FILENAME)
            if (!file.isFile) return null
            return try {
                val text = file.readText(Charsets.UTF_8)
                val manifest = json.decodeFromString<UploadResumeManifest>(text)
                if (manifest.version != SCHEMA_VERSION) {
                    Log.d(TAG, "load: schema mismatch (${manifest.version} vs $SCHEMA_VERSION) — ignoring")
                    return null
                }
                // Defense in depth: confirm that every claimed-completed block file
                // actually exists on disk. A crash between the block writeBytes and the
                // sidecar rename can leave the count higher than the on-disk reality.
                val missing = (0 until manifest.completedBlocks).firstOrNull { idx ->
                    !File(tempDir, "block_$idx.enc").isFile
                }
                if (missing != null) {
                    Log.d(TAG, "load: claimed block $missing absent on disk — ignoring manifest")
                    return null
                }
                manifest
            } catch (e: Exception) {
                Log.w(TAG, "load: failed to parse ${file.absolutePath}: ${e.message}")
                null
            }
        }

        /**
         * Atomically writes [manifest] to [tempDir] via tempfile + rename. A crash mid-write
         * leaves either the old manifest intact or no manifest (handled by the load path);
         * never a corrupt half-written one.
         */
        fun save(tempDir: File, manifest: UploadResumeManifest) {
            tempDir.mkdirs()
            val finalFile = File(tempDir, FILENAME)
            val tmp = File(tempDir, FILENAME + TMP_SUFFIX)
            try {
                tmp.writeText(json.encodeToString(manifest), Charsets.UTF_8)
                // File.renameTo on Android is atomic within a single mount; the cache dir
                // sits on internal storage so we never cross mounts here.
                if (!tmp.renameTo(finalFile)) {
                    // Rare on Android (renameTo over an existing file is permitted on FAT/EXT4
                    // and the destination filesystem is ext4 in practice), but cover the
                    // theoretical "destination exists and rename refused" case by deleting
                    // and retrying once.
                    finalFile.delete()
                    if (!tmp.renameTo(finalFile)) {
                        Log.w(TAG, "save: rename to ${finalFile.name} failed twice — leaving tmp in place")
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "save: failed to write ${finalFile.absolutePath}: ${e.message}")
                runCatching { tmp.delete() }
            }
        }

        /** Encodes raw bytes to base64 NO_WRAP so the manifest stays single-line. */
        fun encodeB64(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)

        /** Decodes a base64 string emitted by [encodeB64]. */
        fun decodeB64(s: String): ByteArray = Base64.decode(s, Base64.DEFAULT)

        /**
         * Sweeps the app's cacheDir for `upload_*` tempDirs whose last-modified time is
         * older than [STALE_TTL_MS]. Called from [eu.akoos.photos.worker.CachePruneWorker]
         * so abandoned partial uploads don't accumulate indefinitely.
         *
         * The mtime check uses the directory itself plus the manifest sidecar (whichever
         * is newer) — the per-block files don't update once spilled, so they'd misreport
         * an actively-resumed upload as stale.
         */
        fun pruneStaleTempDirs(cacheDir: File) {
            if (!cacheDir.isDirectory) return
            val cutoff = System.currentTimeMillis() - STALE_TTL_MS
            var removed = 0
            cacheDir.listFiles()?.forEach { entry ->
                if (!entry.isDirectory) return@forEach
                if (!entry.name.startsWith("upload_")) return@forEach
                val manifestMtime = File(entry, FILENAME).takeIf { it.isFile }?.lastModified() ?: 0L
                val mostRecent = maxOf(entry.lastModified(), manifestMtime)
                if (mostRecent in 1..cutoff) {
                    if (entry.deleteRecursively()) removed++
                }
            }
            if (removed > 0) Log.d(TAG, "pruneStaleTempDirs: removed $removed stale upload tempDir(s)")
        }
    }
}
