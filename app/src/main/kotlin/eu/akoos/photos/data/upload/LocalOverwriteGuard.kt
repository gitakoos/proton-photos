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
     */
    fun <T> run(uri: String, expectedSize: Pair<Int, Int>? = null, mutate: () -> T): T {
        val parsed = Uri.parse(uri)
        val originalSize = imageSize(parsed)
        val wanted = expectedSize ?: originalSize
        return MirrorOverwriteJournal.guardedMutation(
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
    }

    private fun open(uri: Uri): InputStream? = context.contentResolver.openInputStream(uri)

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
