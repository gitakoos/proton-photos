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

package eu.akoos.photos.data.upload

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import eu.akoos.photos.data.repository.DeviceFileStamps
import java.io.File
import java.io.InputStream

/**
 * Runs an in-place rewrite of one of the user's own photos so an interruption cannot leave the only
 * copy of it truncated.
 *
 * A content uri cannot be replaced by renaming a finished file over it: every route to new bytes opens
 * the original and empties it first (the editor's overwrite, and the way ExifInterface saves into a
 * descriptor). Writing to a temp file first only moves the truncating step to a copy. What protects the
 * original is therefore a durable backup taken before the write, which is put back when the write
 * throws, fails to produce a readable image, or is cut short by the process dying (the next launch
 * replays it, see [MirrorOverwriteJournal]).
 *
 * Used for photos only. A video is never rewritten this way, because backing up one costs more than the
 * few timestamp bytes its edit touches.
 */
class LocalOverwriteGuard(private val context: Context) {

    private val journal by lazy {
        MirrorOverwriteJournal(File(context.filesDir, MirrorOverwriteJournal.DIR_NAME))
    }

    /**
     * Runs [mutate], which rewrites the file behind [uri], and returns its result. When it throws the
     * file is back to its original bytes before the exception reaches the caller, so a write the OS
     * refused (a consent request for a file the app does not own) behaves exactly as without the guard.
     * Throws [MirrorOverwriteJournal.StagingFailedException] without touching anything when the
     * original cannot be backed up.
     *
     * The rewritten file must still decode as an image of [expectedSize], or of the original's size when
     * that is null; one that does not is rolled back and reported as a failure. A container the platform
     * cannot decode to bounds is not checked.
     *
     * A rewrite that keeps the photo itself (a metadata write or strip) is announced through
     * [OwnDeviceWrites] once it landed, with the file's SHA-1 before and after, so its backup row records the
     * new bytes as backed up when the old ones were. [announce] is false for one that changes the picture or
     * whose bytes go to Drive on their own (a pixel edit, an upload's mirror strip): it must not be taken
     * for the backed-up photo.
     */
    fun <T> run(
        uri: String,
        expectedSize: Pair<Int, Int>? = null,
        announce: Boolean = true,
        mutate: () -> T,
    ): T {
        val parsed = Uri.parse(uri)
        val originalSize = imageSize(parsed)
        val wanted = expectedSize ?: originalSize
        // A hidden photo is edited as a scratch copy whose vault file stays untouched until the edited copy is
        // sealed back, so the vault file is the backup: no second plaintext copy is written for it.
        if (eu.akoos.photos.data.localcrypto.VaultFiles.isScratch(context, uri)) {
            val result = mutate()
            if (wanted != null && imageSize(parsed) != wanted) throw java.io.IOException("rewritten image does not decode")
            return result
        }
        val before = if (announce && OwnDeviceWrites.listening) sha1(uri) else null
        val beforeStamp = before?.let { runCatching { DeviceFileStamps.read(context, uri) }.getOrNull() }
        val result = MirrorOverwriteJournal.guardedMutation(
            stage = {
                journal.stage(uri) { backup ->
                    open(parsed)?.use { input ->
                        backup.outputStream().use { input.copyTo(it) }
                        true
                    } ?: false
                }
            },
            mutate = mutate,
            untouched = { entry -> sameBytes(parsed, entry.backup) },
            restore = { entry ->
                context.contentResolver.openOutputStream(parsed, "wt")?.use { out ->
                    entry.backup.inputStream().use { it.copyTo(out) }
                } != null
            },
            commit = { entry -> journal.commit(entry) },
            verify = { wanted == null || imageSize(parsed) == wanted },
        )
        fileLength(parsed)?.let { LocalRewrites.note(uri, it) }
        if (before != null) {
            sha1(uri)?.let { after -> OwnDeviceWrites.wrote(OwnDeviceWrite(uri, before, beforeStamp, after)) }
        }
        return result
    }

    private fun sha1(uri: String): String? = runCatching {
        eu.akoos.photos.data.localcrypto.PlainContent.sha1Hex(context, uri)
    }.getOrNull()

    private fun open(uri: Uri): InputStream? = context.contentResolver.openInputStream(uri)

    private fun fileLength(uri: Uri): Long? = runCatching {
        context.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize }
    }.getOrNull()?.takeIf { it > 0L }

    private fun imageSize(uri: Uri): Pair<Int, Int>? = runCatching {
        open(uri)?.use { input ->
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeStream(input, null, bounds)
            if (bounds.outWidth > 0 && bounds.outHeight > 0) bounds.outWidth to bounds.outHeight else null
        }
    }.getOrNull()

    private fun sameBytes(uri: Uri, backup: File): Boolean = runCatching {
        open(uri)?.use { current ->
            backup.inputStream().use { saved -> current.buffered().readBytesEqual(saved.buffered()) }
        } ?: false
    }.getOrDefault(false)

    private fun InputStream.readBytesEqual(other: InputStream): Boolean {
        val a = ByteArray(BUFFER)
        val b = ByteArray(BUFFER)
        while (true) {
            val n = readFill(a)
            val m = other.readFill(b)
            if (n != m) return false
            if (n == 0) return true
            if (!a.sameStart(b, n)) return false
        }
    }

    private fun InputStream.readFill(into: ByteArray): Int {
        var filled = 0
        while (filled < into.size) {
            val n = read(into, filled, into.size - filled)
            if (n < 0) break
            filled += n
        }
        return filled
    }

    private fun ByteArray.sameStart(other: ByteArray, length: Int): Boolean {
        for (i in 0 until length) if (this[i] != other[i]) return false
        return true
    }

    private companion object {
        const val BUFFER = 64 * 1024
    }
}

/**
 * The device files this process rewrote on purpose through [LocalOverwriteGuard], each with its byte
 * length right after the rewrite. Reconcile asks here when a backed-up photo's file changed size, so the
 * app's own strip or metadata write keeps the photo's backup stamp while a change made by another app
 * does not. A note counts only for the size it recorded and is spent by the one question that matches
 * it. In memory only: after a restart an own rewrite is treated like any other change, which only
 * keeps the photo from being freed.
 */
object LocalRewrites {
    private const val MAX_NOTES = 20_000

    private val notes = object : LinkedHashMap<String, Long>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?): Boolean = size > MAX_NOTES
    }

    fun note(uri: String, sizeBytes: Long) {
        if (sizeBytes <= 0L) return
        synchronized(notes) {
            notes.remove(uri)
            notes[uri] = sizeBytes
        }
    }

    /** Whether [uri] was rewritten here and is [sizeBytes] long as that rewrite left it. Either way the
     *  note is spent. */
    fun consume(uri: String, sizeBytes: Long): Boolean = synchronized(notes) {
        notes.remove(uri) == sizeBytes
    }

    internal fun clear() = synchronized(notes) { notes.clear() }
}
