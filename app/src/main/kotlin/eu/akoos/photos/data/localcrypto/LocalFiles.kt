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
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.FilterOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * The app folders whose files are kept encrypted: the hidden vault, the full-size and thumbnail caches, the
 * offline copies and the motion photo clips. A file keeps its name and `file://` uri; only its bytes are
 * encrypted. Every reader here takes both forms, so a file written by an earlier version still reads.
 */
object LocalFiles {
    const val FULLRES_DIR = "fullres"
    const val THUMBNAILS_DIR = "thumbnails"
    const val OFFLINE_DIR = "offline"
    const val MOTION_DIR = "motion"

    /** A folder of encrypted files. [code] names it in a [VaultReadProvider] uri; [regenerable] files may be
     *  dropped when they no longer open, since they are fetched or made again. */
    enum class Area(val code: String, val regenerable: Boolean) {
        VAULT("v", false),
        FULLRES("f", true),
        THUMBNAILS("t", true),
        OFFLINE("o", false),
        MOTION("m", true),
        ;

        companion object {
            fun of(code: String): Area? = entries.firstOrNull { it.code == code }
        }
    }

    fun dir(context: Context, area: Area): File {
        val app = context.applicationContext ?: context
        return when (area) {
            Area.VAULT -> VaultFiles.vaultDir(app)
            Area.FULLRES -> File(app.cacheDir, FULLRES_DIR)
            Area.THUMBNAILS -> File(app.cacheDir, THUMBNAILS_DIR)
            Area.OFFLINE -> File(app.filesDir, OFFLINE_DIR)
            Area.MOTION -> File(app.cacheDir, MOTION_DIR)
        }
    }

    /** The folder [file] sits directly in, or null when it is in none of them. */
    fun areaOf(context: Context, file: File): Area? {
        val parent = file.absoluteFile.parentFile?.absolutePath ?: return null
        return Area.entries.firstOrNull { dir(context, it).absolutePath == parent }
    }

    /** The file a `file://` [uri] names inside one of the folders, or null. */
    fun fileOf(context: Context, uri: Uri): File? {
        if (uri.scheme != "file") return null
        val file = File(uri.path ?: return null)
        return file.takeIf { areaOf(context, it) != null }
    }

    fun fileOf(context: Context, uri: String): File? = runCatching { Uri.parse(uri) }.getOrNull()?.let { fileOf(context, it) }

    /**
     * The uri a reader should open for [uri]: a file in one of the folders is served decrypted by
     * [VaultReadProvider]; any other uri comes back as it is.
     */
    fun readableUri(context: Context, uri: Uri): Uri {
        val file = runCatching { fileOf(context, uri) }.getOrNull() ?: return uri
        val area = areaOf(context, file) ?: return uri
        return VaultReadProvider.uriFor(area, file.name)
    }

    fun isEncrypted(file: File): Boolean = LocalCryptoFormat.isEncrypted(file)

    /** The plaintext of [file], decrypting it when it is encrypted. Throws when it cannot be read. */
    fun openPlain(context: Context?, file: File): InputStream {
        if (VaultFiles.plainForm(file)) return FileInputStream(file)
        val ctx = CacheFiles.contextOf(context) ?: throw LocalKeyUnavailableException(null)
        val keyring = LocalCrypto.keyringOrNull(ctx) ?: throw LocalKeyUnavailableException(null)
        return LocalCryptoFormat.openFile(file, keyring).inputStream()
    }

    fun readBytes(context: Context?, file: File): ByteArray = openPlain(context, file).use { it.readBytes() }

    /**
     * The plaintext of [file] for a reader that can do without it: null when the file is missing, empty, cannot
     * be read now or does not open. A regenerable cache file that does not open any more, or whose read reaches a
     * damaged part, is deleted, so it is fetched or made again.
     */
    fun openForRead(context: Context?, file: File): InputStream? {
        if (!file.isFile || file.length() <= 0L) return null
        return when (LocalCryptoFormat.formOf(file)) {
            LocalFileForm.PLAIN -> runCatching { FileInputStream(file) }.getOrNull()
            LocalFileForm.UNKNOWN -> null
            LocalFileForm.ENCRYPTED -> {
                val ctx = CacheFiles.contextOf(context) ?: return null
                try {
                    val stream = openPlain(ctx, file)
                    if (areaOf(ctx, file)?.regenerable == true) CacheFiles.droppingOnDamage(file, stream) else stream
                } catch (e: Exception) {
                    if (areaOf(ctx, file)?.regenerable == true) CacheFiles.dropIfUnreadable(file, e)
                    null
                }
            }
        }
    }

    /**
     * A decode of [file] for an indexer that has to tell a file it cannot read now from one it can make no image of.
     * [undecodable] is set only when the whole read went through and the decoder still made nothing (a format it
     * does not know, corrupt image bytes): that miss is for good. A file missing, locked or damaged part way is a
     * miss for now (a damaged cache file is deleted by [openForRead], so it is fetched again), and the decoder's
     * picture of a read cut short is never handed back.
     */
    class CheckedDecode(val bitmap: Bitmap?, val undecodable: Boolean)

    fun decodeChecked(
        context: Context?,
        file: File,
        options: BitmapFactory.Options,
        decode: (InputStream, BitmapFactory.Options) -> Bitmap? = { input, opts -> BitmapFactory.decodeStream(input, null, opts) },
    ): CheckedDecode {
        val stream = openForRead(context, file) ?: return CheckedDecode(null, undecodable = false)
        val watched = ReadWatch(stream)
        val bitmap = watched.use { decode(BufferedInputStream(it, DECODE_BUFFER), options) }
        if (watched.failed) {
            bitmap?.recycle()
            return CheckedDecode(null, undecodable = false)
        }
        val made = if (options.inJustDecodeBounds) options.outWidth > 0 && options.outHeight > 0 else bitmap != null
        return CheckedDecode(bitmap, undecodable = !made && file.exists())
    }

    /** Remembers whether a read of [source] threw (a decoder may swallow the error and take it as the end). */
    internal class ReadWatch(source: InputStream) : java.io.FilterInputStream(source) {
        @Volatile
        var failed = false
            private set

        private inline fun <T> watched(read: () -> T): T = try {
            read()
        } catch (e: IOException) {
            failed = true
            throw e
        }

        override fun read(): Int = watched { super.read() }

        override fun read(b: ByteArray, off: Int, len: Int): Int = watched { super.read(b, off, len) }

        override fun skip(n: Long): Long = watched { super.skip(n) }
    }

    /** [BitmapFactory.decodeFile] for a file that may be encrypted: null where [openForRead] has no stream. */
    fun decodeBitmap(context: Context?, file: File, options: BitmapFactory.Options? = null): Bitmap? =
        openForRead(context, file)?.use { BitmapFactory.decodeStream(BufferedInputStream(it, DECODE_BUFFER), null, options) }

    /**
     * A seekable stream of [file]'s plaintext, for readers such as [ExifInterface] that need to seek (HEIF): the file
     * itself when it is plain, else the decrypting provider's seekable descriptor. Null as for [openForRead].
     */
    fun openSeekable(context: Context?, file: File): InputStream? {
        if (!file.isFile || file.length() <= 0L) return null
        return when (LocalCryptoFormat.formOf(file)) {
            LocalFileForm.PLAIN -> runCatching { FileInputStream(file) }.getOrNull()
            LocalFileForm.UNKNOWN -> null
            LocalFileForm.ENCRYPTED -> {
                val ctx = CacheFiles.contextOf(context) ?: return null
                if (areaOf(ctx, file) == null) return null
                runCatching { ctx.contentResolver.openInputStream(readableUri(ctx, Uri.fromFile(file))) }.getOrNull()
            }
        }
    }

    /** The EXIF of a file that may be encrypted, or null when it cannot be read. */
    fun readExif(context: Context?, file: File): ExifInterface? =
        runCatching { openSeekable(context, file)?.use { ExifInterface(it) } }.getOrNull()

    /** The EXIF orientation of a file that may be encrypted, [ExifInterface.ORIENTATION_NORMAL] when unknown. */
    fun exifOrientation(context: Context?, file: File): Int =
        readExif(context, file)?.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            ?: ExifInterface.ORIENTATION_NORMAL

    private const val DECODE_BUFFER = 64 * 1024

    /** The size of [file]'s plaintext, from its header when it is encrypted. */
    fun plainLength(file: File): Long = if (isEncrypted(file)) LocalCryptoFormat.plainSize(file) ?: 0L else file.length()
}

/**
 * Writing and reading the regenerable caches (thumbnails, full-size copies, motion clips). A cache file is
 * encrypted with the current local key and written through a temp in its own folder, so a reader never sees half
 * of one. When the Keystore does not give a key, nothing is written ([LocalKeyUnavailableException]): the caller
 * goes without the cache file and fetches it again later. Only where the platform has no Keystore at all (the JVM
 * test runtime) are cache files written as they are, like every other file there. A cache file that does not open
 * any more is deleted and reads as missing, so it is fetched or made again; reading one is never an error for the
 * user.
 */
object CacheFiles {
    @Volatile private var appContext: Context? = null

    /** Lets writers without a context of their own reach the keyring. */
    fun init(context: Context) {
        appContext = context.applicationContext ?: context
    }

    @PublishedApi
    internal fun contextOf(context: Context?): Context? = context?.applicationContext ?: context ?: appContext

    /**
     * The sealing new cache files get. Null only where the platform has no Keystore, so nothing is encrypted
     * there; throws [LocalKeyUnavailableException] when there is a Keystore but no key can be had now.
     */
    fun sealing(context: Context? = null): LocalCryptoFormat.Sealing? {
        val keyring = contextOf(context)?.let { LocalCrypto.keyringOrNull(it) }
            ?: LocalCrypto.loadedKeyring()
            ?: if (LocalCrypto.platformHasKeystore()) throw LocalKeyUnavailableException(null) else return null
        val current = try {
            keyring.current()
        } catch (e: Exception) {
            throw LocalKeyUnavailableException(e)
        }
        return LocalCryptoFormat.Sealing(current.keyId, current.key, LocalFileKind.CACHE)
    }

    /**
     * Writes [bytes] as [target], replacing it in one step through a temp in the same folder named with
     * [tempPrefix] (a prefix the folder's own sweep already removes). Throws [LocalKeyUnavailableException],
     * writing nothing, when no key can be had.
     */
    fun write(target: File, bytes: ByteArray, tempPrefix: String = ".cache_", context: Context? = null) {
        val sealing = sealing(context)
        val dir = target.absoluteFile.parentFile ?: throw IOException("no folder")
        dir.mkdirs()
        val temp = File.createTempFile(tempPrefix, ".tmp", dir)
        try {
            FileOutputStream(temp).use { out ->
                if (sealing == null) out.write(bytes)
                else LocalCryptoFormat.encryptingStream(out, sealing).use { it.write(bytes) }
            }
            if (!temp.renameTo(target)) throw IOException("cache file not published")
        } finally {
            temp.delete()
        }
    }

    /**
     * A stream writing into [out] encrypted. Closing it finishes the file and closes [out].
     * [PlainCountingStream.plainBytes] counts what was written to it. Throws [LocalKeyUnavailableException] when
     * no key can be had; [out] is closed then.
     */
    fun encryptingStream(out: OutputStream, context: Context? = null): PlainCountingStream {
        val sealing = try {
            sealing(context)
        } catch (e: LocalKeyUnavailableException) {
            runCatching { out.close() }
            throw e
        }
        val inner = if (sealing == null) out else LocalCryptoFormat.encryptingStream(out, sealing)
        return PlainCountingStream(inner)
    }

    /** The plaintext of the cache file [file], or null when it is missing, empty or no longer opens (then it is
     *  deleted, so the next look finds it missing and makes it again). */
    fun readBytesOrNull(file: File, context: Context? = null): ByteArray? {
        if (!file.isFile || file.length() <= 0L) return null
        if (!LocalCryptoFormat.isEncrypted(file)) return runCatching { file.readBytes() }.getOrNull()
        val ctx = contextOf(context) ?: return null
        return try {
            LocalFiles.readBytes(ctx, file)
        } catch (e: Exception) {
            dropIfUnreadable(file, e)
            null
        }
    }

    /** Opens the cache file [file] decrypted, or null as for [readBytesOrNull]. A read that reaches a damaged
     *  part deletes it too. */
    fun openOrNull(file: File, context: Context? = null): InputStream? {
        if (!file.isFile || file.length() <= 0L) return null
        if (!LocalCryptoFormat.isEncrypted(file)) return runCatching { FileInputStream(file) }.getOrNull()
        val ctx = contextOf(context) ?: return null
        return try {
            droppingOnDamage(file, LocalFiles.openPlain(ctx, file))
        } catch (e: Exception) {
            dropIfUnreadable(file, e)
            null
        }
    }

    /** [stream] of the cache file [file], which deletes the file when a read reaches a damaged segment (the header
     *  opened, a later part does not), so the next look finds it missing and makes it again. */
    fun droppingOnDamage(file: File, stream: InputStream): InputStream = object : java.io.FilterInputStream(stream) {
        private var failure: IOException? = null

        private inline fun <T> dropping(read: () -> T): T = try {
            read()
        } catch (e: IOException) {
            failure = e
            dropIfUnreadable(file, e)
            throw e
        }

        // Deleted again once closed: a file still open may refuse to go until then.
        override fun close() {
            super.close()
            failure?.let { dropIfUnreadable(file, it) }
        }

        override fun read(): Int = dropping { super.read() }

        override fun read(b: ByteArray, off: Int, len: Int): Int = dropping { super.read(b, off, len) }

        override fun skip(n: Long): Long = dropping { super.skip(n) }
    }

    /** Deletes a cache file whose key is gone or whose bytes are damaged. A Keystore that is only slow to answer
     *  leaves it in place. */
    fun dropIfUnreadable(file: File, error: Throwable) {
        if (error is LocalKeyLockedException || error is LocalFileDamagedException) {
            runCatching { file.delete() }
        }
    }

    /**
     * Empties the regenerable folders once after keys went missing (the Keystore lost them), so no screen keeps
     * finding cache files it cannot open. Runs again only when the set of missing keys changes.
     */
    fun dropAfterKeyLoss(context: Context, keyring: LocalKeyring, log: (String) -> Unit) {
        val locked = keyring.lockedKeyIds().sorted().joinToString(",")
        val marker = File(LocalCrypto.root(context), CACHE_DROPPED_MARKER)
        if (locked.isEmpty()) {
            marker.delete()
            return
        }
        if (runCatching { marker.readText() }.getOrNull() == locked) return
        for (area in LocalFiles.Area.entries.filter { it.regenerable }) {
            runCatching { LocalFiles.dir(context, area).deleteRecursively() }
        }
        runCatching { marker.parentFile?.mkdirs(); marker.writeText(locked) }
        log("caches emptied after a key loss")
    }

    private const val CACHE_DROPPED_MARKER = "cache_dropped_for"
}

/** Passes writes through to [out], counting them. */
class PlainCountingStream internal constructor(out: OutputStream) : FilterOutputStream(out) {
    var plainBytes: Long = 0L
        private set

    override fun write(b: Int) {
        out.write(b)
        plainBytes++
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        out.write(b, off, len)
        plainBytes += len
    }

    private var closed = false

    /** Closes once: a channel wrapped around it may close it before its owner does. */
    override fun close() {
        if (closed) return
        closed = true
        super.close()
    }
}
