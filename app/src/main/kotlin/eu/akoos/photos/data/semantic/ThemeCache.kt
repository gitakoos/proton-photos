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

package eu.akoos.photos.data.semantic

import android.content.Context
import eu.akoos.photos.domain.usecase.ThemeAssignment
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream

/** Magic number and layout version of the cache files, so a file of another layout reads as absent. */
private const val CACHE_MAGIC = 0x54484D31
private const val CACHE_FORMAT = 1

/** Cache files live under this directory of the app-private files dir. */
private const val CACHE_DIR = "themes"

/** What a cache file was built from. A file is reused only when all three match the running build. */
data class ThemeCacheKey(val modelVersion: Int, val tableVersion: Int)

/** True when a cache built for [stored] is still valid for the [current] model and table generation. */
internal fun isThemeCacheCurrent(stored: ThemeCacheKey, current: ThemeCacheKey): Boolean = stored == current

/** Writes the per-photo classification for [key] to [out]. Pure over streams, so a plain JVM test covers it. */
internal fun writeThemeAssignments(out: OutputStream, key: ThemeCacheKey, entries: Map<String, ThemeAssignment>) {
    DataOutputStream(out.buffered()).use { d ->
        d.writeInt(CACHE_MAGIC)
        d.writeInt(CACHE_FORMAT)
        d.writeInt(key.modelVersion)
        d.writeInt(key.tableVersion)
        d.writeInt(entries.size)
        for ((photoKey, a) in entries) {
            d.writeUTF(photoKey)
            d.writeByte(a.first)
            d.writeFloat(a.firstScore)
            d.writeByte(a.second)
            d.writeFloat(a.secondScore)
        }
    }
}

/** Reads a classification written by [writeThemeAssignments], or null when the stream is of another
 *  layout, was built for another model or table generation than [current], or is truncated or corrupt. */
internal fun readThemeAssignments(input: InputStream, current: ThemeCacheKey): HashMap<String, ThemeAssignment>? =
    try {
        DataInputStream(input.buffered()).use { d ->
            if (d.readInt() != CACHE_MAGIC || d.readInt() != CACHE_FORMAT) return null
            val stored = ThemeCacheKey(d.readInt(), d.readInt())
            if (!isThemeCacheCurrent(stored, current)) return null
            val count = d.readInt()
            if (count < 0) return null
            val map = HashMap<String, ThemeAssignment>(count * 2)
            repeat(count) {
                val photoKey = d.readUTF()
                val first = d.readByte().toInt()
                val firstScore = d.readFloat()
                val second = d.readByte().toInt()
                val secondScore = d.readFloat()
                map[photoKey] = ThemeAssignment(first, firstScore, second, secondScore)
            }
            map
        }
    } catch (e: java.io.IOException) {
        null
    }

/** Writes the prompt embeddings (flat, [dim] floats each) for [key]. */
internal fun writeThemePrompts(out: OutputStream, key: ThemeCacheKey, vectors: List<FloatArray>) {
    DataOutputStream(out.buffered()).use { d ->
        d.writeInt(CACHE_MAGIC)
        d.writeInt(CACHE_FORMAT)
        d.writeInt(key.modelVersion)
        d.writeInt(key.tableVersion)
        d.writeInt(vectors.size)
        d.writeInt(vectors.firstOrNull()?.size ?: 0)
        for (v in vectors) for (f in v) d.writeFloat(f)
    }
}

/** Reads the prompt embeddings, or null when the file does not match [current] or [expectedCount]. */
internal fun readThemePrompts(input: InputStream, current: ThemeCacheKey, expectedCount: Int): List<FloatArray>? =
    try {
        DataInputStream(input.buffered()).use { d ->
            if (d.readInt() != CACHE_MAGIC || d.readInt() != CACHE_FORMAT) return null
            val stored = ThemeCacheKey(d.readInt(), d.readInt())
            if (!isThemeCacheCurrent(stored, current)) return null
            val count = d.readInt()
            val dim = d.readInt()
            if (count != expectedCount || dim <= 0) return null
            List(count) { FloatArray(dim) { d.readFloat() } }
        }
    } catch (e: java.io.IOException) {
        null
    }

/** The on-disk locations of the theme caches, one assignments file per account. */
object ThemeCacheFiles {

    private fun dir(context: Context): File = File(context.filesDir, CACHE_DIR)

    fun promptsFile(context: Context): File = File(dir(context), "prompts.bin")

    fun assignmentsFile(context: Context, account: String): File =
        File(dir(context), "assign_" + account.replace(Regex("[^A-Za-z0-9_-]"), "_") + ".bin")

    /** Drops the account's cached classification, for the sign-out wipe. */
    fun deleteAssignments(context: Context, account: String) {
        runCatching { assignmentsFile(context, account).delete() }
    }

    /** Writes [file] through a temporary sibling and a rename, so a crash never leaves a half file. */
    fun writeAtomically(file: File, write: (OutputStream) -> Unit) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.outputStream().use(write)
        if (!tmp.renameTo(file)) {
            file.delete()
            tmp.renameTo(file)
        }
    }
}
