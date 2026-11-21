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

package eu.akoos.photos.util

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.ParcelFileDescriptor
import eu.akoos.photos.data.db.dao.PhotoLocationDao
import kotlinx.coroutines.CancellationException

/**
 * Single source of the coordinate ladder the viewer's details place row and the metadata editor's
 * place field both resolve: a device image keeps GPS in its EXIF, a device video in its ISO 6709
 * container tag, and a backed-up / cloud photo in the stored fix the map backfill records
 * ([PhotoLocationEntity]). Pure or context-taking, so it holds no ViewModel state.
 */
object PhotoGpsResolver {

    private val ISO_6709 = Regex("""([+-]\d+(?:\.\d+)?)([+-]\d+(?:\.\d+)?)""")

    /**
     * Coordinates for a device file. A video reads its container ([videoGps]); anything else reads
     * its image EXIF ([imageExifGps]). Branching on [mime] resolves the same coordinates as probing
     * both sources for any real file, without opening a retriever on an image. [preRead] is an EXIF
     * read the caller already made for the same file, reused on the image path so it parses once.
     */
    fun localGps(
        context: Context,
        uri: String,
        mime: String,
        preRead: PhotoMetadata? = null,
    ): Pair<Double, Double>? =
        if (mime.startsWith("video/")) videoGps(context, uri)
        else imageExifGps(context, uri, preRead)

    /**
     * GPS from an image's EXIF: [preRead] when the caller already read it, otherwise a read now. Null
     * when the file carries no fix or the read fails. Only the read taken here opens the file, so
     * only it goes through [originalUriForExif]; a [preRead] is used exactly as the caller took it.
     */
    fun imageExifGps(
        context: Context,
        uri: String,
        preRead: PhotoMetadata? = null,
    ): Pair<Double, Double>? {
        val meta = preRead ?: runCatching {
            ExifHelper.readMetadata(context, originalUriForExif(context, uri))
        }.getOrNull()
        val lat = meta?.gpsLatitude ?: return null
        val lng = meta?.gpsLongitude ?: return null
        return (lat to lng).takeIf { eu.akoos.photos.data.db.entity.isUsableGpsFix(lat, lng) }
    }

    /**
     * GPS from a video container. ExifInterface cannot read it, so the ISO 6709 location tag is
     * pulled through [MediaMetadataRetriever] and parsed by [parseIso6709], and when the platform finds
     * none the container's own atoms are read ([Mp4LocationAtoms.readIso6709], which knows `loci` too).
     * A [CancellationException] propagates so a cancelled coroutine unwinds; any other read failure
     * yields null, and the retriever is always released.
     */
    fun videoGps(context: Context, uri: String): Pair<Double, Double>? {
        val readable = eu.akoos.photos.data.localcrypto.VaultFiles.readableUri(context, Uri.parse(uri))
        return platformVideoGps(context, readable) ?: containerVideoGps(context, readable)
    }

    private fun containerVideoGps(context: Context, readable: Uri): Pair<Double, Double>? = try {
        context.contentResolver.openFileDescriptor(readable, "r")
            ?.let { pfd -> ParcelFileDescriptor.AutoCloseInputStream(pfd).use { Mp4LocationAtoms.readIso6709(it.channel) } }
            ?.let { parseIso6709(it) }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    private fun platformVideoGps(context: Context, readable: Uri): Pair<Double, Double>? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, readable)
            val loc = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_LOCATION) ?: return null
            parseIso6709(loc)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    /** [videoGps] that throws when the file cannot be opened, so a caller can tell that apart from a file
     *  that opens but holds no location or is not a video the platform reads. */
    fun videoGpsOrThrow(context: Context, uri: String): Pair<Double, Double>? {
        val readable = eu.akoos.photos.data.localcrypto.VaultFiles.readableUri(context, Uri.parse(uri))
        context.contentResolver.openFileDescriptor(readable, "r")?.close()
            ?: throw java.io.IOException("$uri could not be opened")
        return videoGps(context, uri)
    }

    /**
     * Pulls the leading latitude and longitude out of an ISO 6709 location string, e.g.
     * `"+37.7749-122.4194/"` or `"+37.7749-122.4194+010.5/"`: the first two signed numbers are
     * latitude and longitude, any trailing altitude is ignored. Null when the string carries no such
     * pair. Pure, so it is the unit-testable core of [videoGps].
     */
    fun parseIso6709(value: String): Pair<Double, Double>? {
        val m = ISO_6709.find(value) ?: return null
        val lat = m.groupValues[1].toDoubleOrNull() ?: return null
        val lng = m.groupValues[2].toDoubleOrNull() ?: return null
        return (lat to lng).takeIf { eu.akoos.photos.data.db.entity.isUsableGpsFix(lat, lng) }
    }

    /**
     * The map row to store for a downloaded cloud photo whose file carries a fix: only when the photo has
     * no row yet, so a place its Drive attributes gave (or a correction) is never overwritten. A photo
     * another uploader sent without a Location attribute is plotted this way once it is downloaded.
     */
    fun fileFixToStore(
        existing: eu.akoos.photos.data.db.entity.PhotoLocationEntity?,
        fix: Pair<Double, Double>?,
        userId: String,
        linkId: String,
    ): eu.akoos.photos.data.db.entity.PhotoLocationEntity? {
        if (existing != null || fix == null) return null
        if (!eu.akoos.photos.data.db.entity.isUsableGpsFix(fix.first, fix.second)) return null
        return eu.akoos.photos.data.db.entity.PhotoLocationEntity(linkId, userId, fix.first, fix.second)
    }

    /**
     * The stored GPS fix for a cloud photo, keyed by [userId] and its cloud [linkId], or null when it
     * has not been located yet. The caller resolves the primary user, so no account state lives here.
     */
    suspend fun cloudGps(
        dao: PhotoLocationDao,
        userId: String,
        linkId: String,
    ): Pair<Double, Double>? {
        val row = runCatching { dao.getById(userId, linkId) }.getOrNull() ?: return null
        return (row.latitude to row.longitude)
            .takeIf { eu.akoos.photos.data.db.entity.isUsableGpsFix(row.latitude, row.longitude) }
    }
}
