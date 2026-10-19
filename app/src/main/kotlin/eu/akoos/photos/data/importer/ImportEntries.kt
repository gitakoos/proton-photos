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

package eu.akoos.photos.data.importer

import eu.akoos.photos.domain.importer.SidecarMeta
import eu.akoos.photos.domain.importer.TakeoutSidecar
import java.io.ByteArrayOutputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream

/** The rules every import source shares: which entries are media or sidecars, and how a sidecar is read. */
internal object ImportEntries {

    /** A real sidecar is a few kilobytes; anything past this is not one and is skipped. */
    const val MAX_SIDECAR_BYTES = 1_048_576

    private const val READ_CHUNK = 8_192

    /** Media containers an export can hold, including Google's `.mp` motion-photo track. */
    private val MEDIA_EXTENSIONS = setOf(
        "jpg", "jpeg", "png", "gif", "webp", "heic", "heif", "avif", "tif", "tiff",
        "bmp", "dng", "mp4", "mov", "m4v", "3gp", "3gpp", "mkv", "webm", "avi", "mp",
    )

    /** The file name of an entry [path], with any forward-slash directory prefix removed. */
    fun baseName(path: String): String {
        val slash = path.lastIndexOf('/')
        return if (slash >= 0) path.substring(slash + 1) else path
    }

    fun isJson(name: String): Boolean = baseName(name).endsWith(".json", ignoreCase = true)

    fun isMedia(name: String): Boolean = extensionOf(baseName(name)) in MEDIA_EXTENSIONS

    /** True when [error] or one of its causes is a [SourceReadException]. */
    fun isSourceFault(error: Throwable): Boolean {
        var cur: Throwable? = error
        var depth = 0
        while (cur != null && depth++ < 8) {
            if (cur is SourceReadException) return true
            cur = cur.cause
        }
        return false
    }

    /** All of [input], or null once it exceeds [cap] bytes. */
    fun readBounded(input: InputStream, cap: Int = MAX_SIDECAR_BYTES): ByteArray? {
        val out = ByteArrayOutputStream()
        val chunk = ByteArray(READ_CHUNK)
        var total = 0
        while (true) {
            val n = input.read(chunk)
            if (n < 0) break
            total += n
            if (total > cap) return null
            out.write(chunk, 0, n)
        }
        return out.toByteArray()
    }

    /** True when this record carries something worth importing rather than being an album title stub. */
    fun SidecarMeta.isUsable(): Boolean =
        takenMs != null || lat != null || lng != null || description != null

    private fun extensionOf(base: String): String {
        val dot = base.lastIndexOf('.')
        if (dot < 0 || dot == base.length - 1) return ""
        return base.substring(dot + 1).lowercase()
    }
}

/**
 * Pass 1's result as either reader builds it: the sidecar index keyed by [TakeoutSidecar.sidecarKey] and
 * the number of media entries pass 2 will hand back. An album folder's `metadata.json` holds just a title,
 * so it parses to an empty record and falls out of the index on its own.
 */
internal class SidecarIndexBuilder {
    private val index = HashMap<String, SidecarMeta>()
    private var mediaCount = 0

    /** Reads the sidecar entry [name] from [input] into the index when it is small and carries something. */
    fun offerSidecar(name: String, input: InputStream) {
        val bytes = ImportEntries.readBounded(input) ?: return
        val meta = TakeoutSidecar.parse(String(bytes, Charsets.UTF_8)) ?: return
        with(ImportEntries) {
            if (meta.isUsable()) index[TakeoutSidecar.sidecarKey(baseName(name))] = meta
        }
    }

    fun countMedia() {
        mediaCount++
    }

    fun build(walk: WalkResult): TakeoutScan = TakeoutScan(index, mediaCount, walk)
}

/** Raises any failure of the underlying source as a [SourceReadException]. */
internal class SourceStream(delegate: InputStream) : FilterInputStream(delegate) {
    override fun read(): Int = tagged { super.read() }

    override fun read(b: ByteArray, off: Int, len: Int): Int = tagged { super.read(b, off, len) }

    override fun skip(n: Long): Long = tagged { super.skip(n) }

    private inline fun <T> tagged(block: () -> T): T = try {
        block()
    } catch (e: IOException) {
        throw SourceReadException(e)
    }
}
