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

package eu.akoos.photos.data.repository.drive

import android.content.ContentProviderOperation
import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import androidx.annotation.RequiresApi
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.akoos.photos.BuildConfig
import eu.akoos.photos.data.db.dao.PhotoListingDao
import eu.akoos.photos.data.db.entity.PhotoListingEntity
import eu.akoos.photos.util.ExifDateFormat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.proton.core.accountmanager.domain.AccountManager
import me.proton.core.domain.entity.UserId
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Calendar
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToLong

private const val TAG = "LargeLibrarySim"

/**
 * Sentinel + on-disk layout shared by [LargeLibrarySimulator] and the DEBUG-only branch in
 * [ThumbnailHelpers.downloadAndDecryptBinary]. Kept out of the simulator class so the helper can
 * reference it without depending on the (debug-only) simulator graph.
 *
 * A synthetic row's [PhotoListingEntity.serverThumbnailUrl] is `simlocal://<seedIndex>`. The decrypt
 * path recognises that scheme and reads the seed's already-fetched ENCRYPTED blob off disk instead
 * of doing a CDN GET — so the real session-key/node-key decrypt still runs, with zero network.
 */
object LargeLibrarySim {
    /** linkId prefix for every synthetic row. `Clear simulation` deletes by this prefix. */
    const val LINK_ID_PREFIX = "SIMULATED_"

    /** Thumbnail-source scheme that resolves to a seed's local encrypted blob. */
    const val LOCAL_SCHEME = "simlocal://"

    /** Stable per-seed encrypted-blob file: `cache/sim_blobs/seed_<index>.bin`. */
    fun seedBlobFile(context: Context, seedIndex: Int): File =
        File(File(context.cacheDir, "sim_blobs").also { it.mkdirs() }, "seed_$seedIndex.bin")

    /** Parse the seed index out of a `simlocal://<index>` url, or null if it isn't one. */
    fun seedIndexOf(url: String?): Int? =
        url?.removePrefix(LOCAL_SCHEME)?.takeIf { url.startsWith(LOCAL_SCHEME) }?.toIntOrNull()
}

/**
 * DEBUG-only large-library simulator. Generates N synthetic [PhotoListingEntity] rows that run the
 * REAL lazy-thumbnail decrypt pipeline (`:crypto`, the adaptive cache, eviction, the grid treadmill)
 * with ZERO Proton CDN traffic, so the ~21k-photo experience can be exercised on demand.
 *
 * How it stays faithful: each synthetic row CLONES a real already-synced seed row's crypto material
 * verbatim (encNodeKey, encNodePassphrase, contentKeyPacket, parentLinkId, shareId, volumeId, userId).
 * Only the identity/spread fields change — linkId becomes a unique `SIMULATED_<i>`, captureTime is
 * spread across 2002→now, mimeType alternates image/video seeds, and serverThumbnailUrl becomes a
 * `simlocal://<seedIndex>` sentinel that the decrypt path resolves to the seed's local encrypted
 * blob. Decrypting a clone with the cloned keys therefore succeeds exactly like its seed.
 *
 * Entirely inert unless invoked from the BuildConfig.DEBUG-gated settings entry — nothing here runs
 * on the normal listing/refresh path.
 */
@Singleton
class LargeLibrarySimulator @Inject constructor(
    @ApplicationContext private val context: Context,
    private val accountManager: AccountManager,
    private val photoListingDao: PhotoListingDao,
    private val cdnBlockFetcher: CdnBlockFetcher,
    private val thumbnailDecryptScheduler: ThumbnailDecryptScheduler,
) {

    /** Outcome of a [populate] run, surfaced to the debug settings UI. */
    data class Result(val inserted: Int, val seedCount: Int, val message: String)

    /**
     * Generate [count] synthetic rows from up to [MAX_SEEDS] real seed rows.
     *
     * Steps: pick seeds with full crypto material + a server thumbnail → cache each seed's encrypted
     * blob to a stable local file (one CDN GET per seed, reused for all clones) → clone in batches.
     */
    suspend fun populate(count: Int): Result = withContext(Dispatchers.IO) {
        if (count <= 0) return@withContext Result(0, 0, "Count must be > 0")
        val userId = accountManager.getPrimaryUserId().first()
            ?: return@withContext Result(0, 0, "No signed-in account")

        val seeds = pickSeeds(userId)
        if (seeds.isEmpty()) {
            return@withContext Result(0, 0, "No synced photos with a thumbnail to seed from")
        }

        // Ensure each seed's ENCRYPTED thumbnail blob is on disk (fetch once via the normal CDN path).
        // A seed whose blob can't be fetched is dropped so its clones don't render blanks.
        val usableSeeds = seeds.filterIndexed { index, seed -> ensureSeedBlob(userId, seed, index) }
        if (usableSeeds.isEmpty()) {
            return@withContext Result(0, 0, "Could not cache any seed thumbnail blob")
        }
        // Remap to dense indices that line up with the blob files written above.
        val seedBlobIndex = seeds.withIndex().filter { it.value in usableSeeds }.map { it.index }

        val nowMs = System.currentTimeMillis()
        val startMs = epochMsForYear(2002)
        val span = (nowMs - startMs).coerceAtLeast(1L)

        var inserted = 0
        val batch = ArrayList<PhotoListingEntity>(BATCH_SIZE)
        for (i in 0 until count) {
            val poolPos = i % usableSeeds.size
            val seed = usableSeeds[poolPos]
            val seedIndex = seedBlobIndex[poolPos]
            // Spread captureTime evenly across the window, newest-ish last, with light jitter so the
            // day grouping isn't perfectly uniform.
            val t = startMs + (span * i / count) + (i % 86_400_000L)
            // ~12% videos: clone a video seed's mime if available, else tag the row as video.
            val isVideo = i % 8 == 0
            val mime = when {
                isVideo && seed.mimeType.startsWith("video/") -> seed.mimeType
                isVideo -> "video/mp4"
                seed.mimeType.startsWith("video/") -> "image/jpeg"
                else -> seed.mimeType.ifEmpty { "image/jpeg" }
            }
            batch += seed.copy(
                linkId = "${LargeLibrarySim.LINK_ID_PREFIX}$i",
                // captureTime is stored in SECONDS (Proton wire format), not millis.
                captureTime = t.coerceAtMost(nowMs) / 1000L,
                mimeType = mime,
                // Force the lazy path: null thumbnailUrl + sentinel source. The decrypt path reads
                // the seed's local blob and decrypts with the cloned keys below.
                thumbnailUrl = null,
                serverThumbnailUrl = "${LargeLibrarySim.LOCAL_SCHEME}$seedIndex",
                // Crypto material is carried over verbatim by copy(): encNodeKey, encNodePassphrase,
                // contentKeyPacket, parentLinkId, shareId, volumeId, userId.
            )
            if (batch.size >= BATCH_SIZE) {
                photoListingDao.upsertAll(batch)
                inserted += batch.size
                batch.clear()
            }
        }
        if (batch.isNotEmpty()) {
            photoListingDao.upsertAll(batch)
            inserted += batch.size
        }
        Log.d(TAG, "populate: inserted $inserted synthetic rows from ${usableSeeds.size} seed(s)")
        Result(inserted, usableSeeds.size, "Inserted $inserted photos from ${usableSeeds.size} seed(s)")
    }

    /**
     * Remove every synthetic row + its decrypted thumbnail and the cached seed blobs. The crypto
     * material lived only on the synthetic rows, so this leaves the real library untouched.
     */
    suspend fun clear(): Int = withContext(Dispatchers.IO) {
        val userId = accountManager.getPrimaryUserId().first()
        val ids = (userId?.let { photoListingDao.getAllLinkIds(it.id) } ?: emptyList())
            .filter { it.startsWith(LargeLibrarySim.LINK_ID_PREFIX) }
        if (userId != null && ids.isNotEmpty()) {
            ids.chunked(BATCH_SIZE).forEach { photoListingDao.deleteByLinkIds(userId.id, it) }
            // Drop the decrypted thumbnails the scheduler wrote for these rows.
            val thumbDir = File(context.cacheDir, "thumbnails")
            ids.forEach { File(thumbDir, "thumb_$it.jpg").delete() }
        }
        thumbnailDecryptScheduler.clear()
        File(context.cacheDir, "sim_blobs").deleteRecursively()
        Log.d(TAG, "clear: removed ${ids.size} synthetic rows")
        ids.size
    }

    /** Up to [MAX_SEEDS] real own-stream rows that carry the full decrypt material + a CDN thumbnail. */
    private suspend fun pickSeeds(userId: UserId): List<PhotoListingEntity> =
        photoListingDao.observeOwnStream(userId.id).first()
            .filter { row ->
                !row.linkId.startsWith(LargeLibrarySim.LINK_ID_PREFIX) &&
                    row.serverThumbnailUrl?.startsWith("http") == true &&
                    row.contentKeyPacket != null &&
                    row.encNodeKey != null &&
                    row.encNodePassphrase != null &&
                    row.parentLinkId != null
            }
            .take(MAX_SEEDS)

    /** Fetch + cache a seed's encrypted thumbnail blob once. True when the blob is on disk.
     *  The seed's STORED CDN url is a signed url that may have expired (HTTP 404), so refresh it
     *  first — the same on-demand refresh the lazy decrypt does for a stale url. */
    private suspend fun ensureSeedBlob(userId: UserId, seed: PhotoListingEntity, seedIndex: Int): Boolean {
        val out = LargeLibrarySim.seedBlobFile(context, seedIndex)
        if (out.exists() && out.length() > 0) return true
        val fresh = runCatching {
            thumbnailDecryptScheduler.fetchFreshThumbnailInfo(userId, seed.linkId, seed.volumeId)
        }.getOrNull()
        val url = fresh?.bareUrl ?: seed.serverThumbnailUrl ?: return false
        val token = fresh?.token ?: seed.serverThumbnailToken
        return runCatching {
            val bytes = cdnBlockFetcher.fetchBlock(url = url, token = token, maxAttempts = 3)
            if (bytes.isEmpty()) return false
            out.writeBytes(bytes)
            true
        }.getOrElse { e ->
            Log.w(TAG, "ensureSeedBlob: seed $seedIndex fetch failed: ${e.message}")
            false
        }
    }

    private fun epochMsForYear(year: Int): Long =
        Calendar.getInstance().apply {
            clear(); set(year, Calendar.JANUARY, 1)
        }.timeInMillis

    private companion object {
        /** Seed pool size — enough variety in capture metadata while keeping the one-time CDN
         *  fetch (one per seed) tiny. */
        const val MAX_SEEDS = 8

        /** DB upsert batch size for the bulk insert. */
        const val BATCH_SIZE = 500
    }
}

/**
 * Pure pieces of [LargeLibraryDeviceSimulator]: the folder and file name that bound every write and
 * delete, and each file's content (a pixel mosaic, a capture date, an optional GPS fix and the EXIF block
 * carrying both), all derived from the file's index so no two files of a run are alike.
 */
object LargeLibraryDeviceSim {

    /** The one folder the device simulator writes to and deletes from, exactly (never a subfolder). */
    const val RELATIVE_PATH = "Pictures/PFP-Sim/"

    /** File-name prefix of every simulated device photo, the second half of the ownership guard. */
    const val NAME_PREFIX = "pfpsim-"

    /** Edge of the square image in pixels: a 4 x 4 mosaic of 4-pixel cells. */
    const val EDGE = 16
    private const val CELL = 4
    private const val GRID = EDGE / CELL

    /** Earliest capture instant handed out; dates spread evenly from here to the moment of the run. */
    val SPAN_START_MS: Long = LocalDate.of(2005, 1, 1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

    private const val DATE_SALT = 0x2545F491
    private const val GPS_SALT = 0x6A09E667

    // Eight base tints, each drawn at one of four brightness levels. Every (tint, level) pair is its own
    // colour, so two different seeds can never draw the same mosaic.
    private val TINTS = intArrayOf(
        0xE0584C, 0x4CB86A, 0x4C7CE0, 0xE0B04C, 0x4CC8D8, 0xA05CE0, 0xD8D8D8, 0x8CE04C,
    )

    // City centres across several countries and every hemisphere, so the places views split the
    // simulated library into more than one country.
    private val PLACES = arrayOf(
        doubleArrayOf(47.4979, 19.0402),
        doubleArrayOf(48.2082, 16.3738),
        doubleArrayOf(48.8566, 2.3522),
        doubleArrayOf(38.7223, -9.1393),
        doubleArrayOf(64.1466, -21.9426),
        doubleArrayOf(40.7128, -74.0060),
        doubleArrayOf(19.4326, -99.1332),
        doubleArrayOf(-34.6037, -58.3816),
        doubleArrayOf(-33.9249, 18.4241),
        doubleArrayOf(13.7563, 100.5018),
        doubleArrayOf(35.6762, 139.6503),
        doubleArrayOf(-33.8688, 151.2093),
    )

    private const val TIFF_HEADER_BYTES = 8
    private const val TYPE_BYTE = 1
    private const val TYPE_ASCII = 2
    private const val TYPE_LONG = 4
    private const val TYPE_RATIONAL = 5
    private val EXIF_IDENTIFIER = byteArrayOf(0x45, 0x78, 0x69, 0x66, 0, 0)

    /** One TIFF directory entry: [value] holds the raw big-endian bytes, [count] the TIFF item count. */
    private class Tag(val id: Int, val type: Int, val count: Int, val value: ByteArray)

    /**
     * WHERE clause matching only the simulator's own files: the exact folder AND the name prefix. Every
     * query and delete the simulator issues is built on it; [extra] only narrows it further.
     */
    fun ownSelection(extra: String? = null): String {
        val guard = "${MediaStore.MediaColumns.RELATIVE_PATH} = ? AND ${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?"
        return if (extra == null) guard else "$guard AND ($extra)"
    }

    /** Arguments for [ownSelection], followed by the ones its extra clause binds. */
    fun ownSelectionArgs(extra: Array<String> = emptyArray()): Array<String> =
        arrayOf(RELATIVE_PATH, "$NAME_PREFIX%") + extra

    /** `pfpsim-<run>-<index>.jpg`; the run tag keeps a later top-up from reusing an earlier run's name. */
    fun displayName(runTag: String, index: Int): String =
        NAME_PREFIX + runTag + "-" + index.toString().padStart(6, '0') + ".jpg"

    /** The murmur3 32-bit finaliser: a bijection, so two different inputs never share an output. */
    fun mix32(value: Int): Int {
        var h = value
        h = h xor (h ushr 16)
        h *= 0x85EBCA6B.toInt()
        h = h xor (h ushr 13)
        h *= 0xC2B2AE35.toInt()
        return h xor (h ushr 16)
    }

    /** Per-file seed, a bijection of [index] for a given [runSalt]: one run never repeats a seed. */
    fun itemSeed(runSalt: Int, index: Int): Int = mix32(index xor runSalt)

    /** Colour of a mosaic cell: [tint] (0..7) drawn at brightness [level] (0..3). */
    internal fun cellColor(tint: Int, level: Int): Int {
        val base = TINTS[tint]
        val quarters = level + 1
        val r = ((base shr 16) and 0xFF) * quarters / 4
        val g = ((base shr 8) and 0xFF) * quarters / 4
        val b = (base and 0xFF) * quarters / 4
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    /**
     * Fill [out] (EDGE * EDGE opaque ARGB pixels) with the mosaic for [seed]. Two bits of the seed set
     * each of the 16 cells' brightness, so the cells spell out the whole seed and distinct seeds always
     * give distinct pixels; the tint only adds variety to the thumbnails.
     */
    fun fillPixels(seed: Int, out: IntArray) {
        require(out.size == EDGE * EDGE) { "expected ${EDGE * EDGE} pixels" }
        val tint = mix32(seed) ushr 29
        for (cell in 0 until GRID * GRID) {
            val color = cellColor(tint, (seed ushr (cell * 2)) and 0x3)
            val x0 = (cell % GRID) * CELL
            val y0 = (cell / GRID) * CELL
            for (y in y0 until y0 + CELL) {
                for (x in x0 until x0 + CELL) out[y * EDGE + x] = color
            }
        }
    }

    /** A whole-second capture instant in [startMs, endMs), spread evenly over the span by [seed]. */
    fun captureTimeMs(seed: Int, startMs: Long, endMs: Long): Long {
        val spanSeconds = ((endMs - startMs) / 1000L).coerceAtLeast(1L)
        val unit = mix32(seed xor DATE_SALT).toLong() and 0xFFFFFFFFL
        return (startMs / 1000L + ((unit * spanSeconds) ushr 32)) * 1000L
    }

    /** GPS fix for [seed] as (latitude, longitude) within ~5 km of one of the [PLACES], or null for about
     *  one file in four, as a real library always holds some unlocated photos. */
    fun gpsFor(seed: Int): DoubleArray? {
        val h = mix32(seed xor GPS_SALT)
        if ((h and 0x3) == 0) return null
        val place = PLACES[((h ushr 2) and 0xFFFF) % PLACES.size]
        val latJitter = (((h ushr 18) and 0x7F) - 64) / 64.0 * 0.05
        val lonJitter = (((h ushr 25) and 0x7F) - 64) / 64.0 * 0.05
        return doubleArrayOf(place[0] + latJitter, place[1] + lonJitter)
    }

    /**
     * The APP1 EXIF segment for a simulated file: DateTimeOriginal with OffsetTimeOriginal in UTC, so the
     * MediaStore scan reads the exact instant back as DATE_TAKEN, plus a GPS directory when [gps] is set.
     * Big-endian TIFF: the directories first, then every value too long to sit inside its entry.
     */
    fun exifSegment(captureMs: Long, gps: DoubleArray?): ByteArray {
        val exifIfd = listOf(
            asciiTag(0x9003, ExifDateFormat.toExifLocal(captureMs, ZoneOffset.UTC)),
            asciiTag(0x9011, ExifDateFormat.toExifOffset(captureMs, ZoneOffset.UTC)),
        )
        val gpsIfd = gps?.let { (lat, lon) ->
            listOf(
                Tag(0x0000, TYPE_BYTE, 4, byteArrayOf(2, 2, 0, 0)),
                asciiTag(0x0001, if (lat < 0) "S" else "N"),
                dmsTag(0x0002, lat),
                asciiTag(0x0003, if (lon < 0) "W" else "E"),
                dmsTag(0x0004, lon),
            )
        }
        // IFD0 carries only the pointers; the Exif directory follows it, then the GPS one.
        val exifOffset = TIFF_HEADER_BYTES + ifdBytes(if (gpsIfd == null) 1 else 2)
        val ifd0 = listOfNotNull(
            longTag(0x8769, exifOffset),
            gpsIfd?.let { longTag(0x8825, exifOffset + ifdBytes(exifIfd.size)) },
        )
        val ifds = listOfNotNull(ifd0, exifIfd, gpsIfd)

        val tiff = ByteArrayOutputStream()
        val out = DataOutputStream(tiff)
        out.writeShort(0x4D4D)
        out.writeShort(42)
        out.writeInt(TIFF_HEADER_BYTES)
        var dataOffset = TIFF_HEADER_BYTES + ifds.sumOf { ifdBytes(it.size) }
        val overflow = ArrayList<ByteArray>()
        for (ifd in ifds) {
            out.writeShort(ifd.size)
            for (tag in ifd) {
                out.writeShort(tag.id)
                out.writeShort(tag.type)
                out.writeInt(tag.count)
                if (tag.value.size <= 4) {
                    out.write(tag.value)
                    repeat(4 - tag.value.size) { out.writeByte(0) }
                } else {
                    out.writeInt(dataOffset)
                    overflow += tag.value
                    dataOffset += tag.value.size + tag.value.size % 2
                }
            }
            out.writeInt(0)
        }
        for (value in overflow) {
            out.write(value)
            if (value.size % 2 != 0) out.writeByte(0)
        }
        out.flush()
        val body = tiff.toByteArray()
        val length = 2 + EXIF_IDENTIFIER.size + body.size
        val segment = ByteArrayOutputStream(2 + length)
        segment.write(0xFF)
        segment.write(0xE1)
        segment.write(length ushr 8)
        segment.write(length and 0xFF)
        segment.write(EXIF_IDENTIFIER)
        segment.write(body)
        return segment.toByteArray()
    }

    /** [jpeg] with [segment] inserted straight after its SOI marker, where a camera puts its EXIF. */
    fun withExif(jpeg: ByteArray, segment: ByteArray): ByteArray {
        require(jpeg.size >= 2 && jpeg[0] == 0xFF.toByte() && jpeg[1] == 0xD8.toByte()) { "not a JPEG" }
        val out = ByteArray(jpeg.size + segment.size)
        jpeg.copyInto(out, destinationOffset = 0, startIndex = 0, endIndex = 2)
        segment.copyInto(out, destinationOffset = 2)
        jpeg.copyInto(out, destinationOffset = 2 + segment.size, startIndex = 2)
        return out
    }

    private fun ifdBytes(entries: Int): Int = 2 + 12 * entries + 4

    private fun asciiTag(id: Int, text: String): Tag {
        val bytes = (text + '\u0000').toByteArray(Charsets.US_ASCII)
        return Tag(id, TYPE_ASCII, bytes.size, bytes)
    }

    private fun longTag(id: Int, value: Int): Tag = Tag(
        id,
        TYPE_LONG,
        1,
        byteArrayOf((value ushr 24).toByte(), (value ushr 16).toByte(), (value ushr 8).toByte(), value.toByte()),
    )

    /** Degrees, minutes and seconds (to a thousandth) as three unsigned rationals. */
    private fun dmsTag(id: Int, coordinate: Double): Tag {
        val degrees = abs(coordinate)
        val whole = degrees.toLong()
        val minutesExact = (degrees - whole) * 60.0
        val minutes = minutesExact.toLong()
        val milliSeconds = ((minutesExact - minutes) * 60_000.0).roundToLong()
        val raw = ByteArrayOutputStream(24)
        DataOutputStream(raw).apply {
            writeInt(whole.toInt())
            writeInt(1)
            writeInt(minutes.toInt())
            writeInt(1)
            writeInt(milliSeconds.toInt())
            writeInt(1000)
            flush()
        }
        return Tag(id, TYPE_RATIONAL, 3, raw.toByteArray())
    }
}

/**
 * DEBUG-only device-library generator: writes N tiny real JPEGs into [LargeLibraryDeviceSim.RELATIVE_PATH]
 * through MediaStore, so the device scan, reconcile, backup, tag scan and EXIF walk all meet a library of
 * that size, and removes exactly those files again. Each file is a 16 x 16 mosaic unique to it, captured
 * somewhere across the years and, for most, placed by GPS (see [LargeLibraryDeviceSim]).
 *
 * Every query and delete is bound to the simulator's own folder and file-name prefix, so nothing else on
 * the device is touched. Work runs in this singleton's scope rather than a screen's, so it carries on
 * after Settings closes; [cancel] stops it at the next batch and drops the unpublished rows. Inert outside
 * debug builds, and needs Android 11 for the pending and trashed query arguments.
 */
@Singleton
class LargeLibraryDeviceSimulator @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    /** The running job's progress, and the last count of simulated files on the device (-1 before the
     *  first count). */
    data class State(
        val running: Boolean = false,
        val done: Int = 0,
        val total: Int = 0,
        val onDevice: Int = -1,
        val message: String? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var job: Job? = null

    private val collection: Uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI

    /** Top the folder up to [target] simulated photos; a target at or below the current count does nothing. */
    fun generate(target: Int) {
        if (!BuildConfig.DEBUG) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) start { runGenerate(target) } else unsupported()
    }

    /** Delete every simulated photo, and nothing else. */
    fun removeAll() {
        if (!BuildConfig.DEBUG) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) start { runRemove() } else unsupported()
    }

    /** Stop the running generate or remove at its next batch. */
    fun cancel() {
        job?.cancel()
    }

    /** Count the simulated photos on the device for the card's readout. */
    fun refreshCount() {
        if (!BuildConfig.DEBUG) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            scope.launch {
                val onDevice = runCatching { countOwn(includeUnfinished = false) }.getOrNull() ?: return@launch
                _state.update { if (it.running) it else it.copy(onDevice = onDevice) }
            }
        }
    }

    private fun unsupported() = _state.update { it.copy(message = "Needs Android 11 or newer") }

    @RequiresApi(Build.VERSION_CODES.R)
    @Synchronized
    private fun start(work: suspend () -> Unit) {
        if (job?.isActive == true) return
        _state.update { it.copy(running = true, done = 0, total = 0, message = null) }
        job = scope.launch {
            try {
                work()
            } catch (e: CancellationException) {
                withContext(NonCancellable) {
                    runCatching { dropUnfinished() }
                    _state.update { it.copy(message = "Cancelled after ${it.done} of ${it.total}") }
                }
                throw e
            } catch (e: Exception) {
                runCatching { dropUnfinished() }
                Log.w(TAG, "device simulation failed", e)
                _state.update { it.copy(message = "Failed: ${e.javaClass.simpleName}: ${e.message}") }
            } finally {
                withContext(NonCancellable) {
                    val onDevice = runCatching { countOwn(includeUnfinished = false) }.getOrNull()
                    _state.update { it.copy(running = false, onDevice = onDevice ?: it.onDevice) }
                }
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private suspend fun runGenerate(target: Int) {
        // Rows a cancelled or killed run left unpublished are neither counted nor kept.
        dropUnfinished()
        val existing = countOwn(includeUnfinished = false)
        val toWrite = target - existing
        if (toWrite <= 0) {
            _state.update { it.copy(onDevice = existing, message = "Already $existing simulated photos on the device") }
            return
        }
        _state.update { it.copy(done = 0, total = toWrite, onDevice = existing) }
        val runSeconds = System.currentTimeMillis() / 1000L
        val runTag = runSeconds.toString(36)
        val runSalt = LargeLibraryDeviceSim.mix32(runSeconds.toInt())
        val edge = LargeLibraryDeviceSim.EDGE
        val bitmap = Bitmap.createBitmap(edge, edge, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(edge * edge)
        val encoded = ByteArrayOutputStream(4096)
        try {
            var written = 0
            while (written < toWrite) {
                currentCoroutineContext().ensureActive()
                val size = min(DEVICE_BATCH, toWrite - written)
                writeBatch(runTag, runSalt, existing + written, size, runSeconds * 1000L, bitmap, pixels, encoded)
                written += size
                _state.update { it.copy(done = written, onDevice = existing + written) }
            }
            Log.d(TAG, "device simulation: wrote $written photos")
            _state.update { it.copy(message = "Wrote $written photos, ${existing + written} on the device") }
        } finally {
            bitmap.recycle()
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun writeBatch(
        runTag: String,
        runSalt: Int,
        firstIndex: Int,
        size: Int,
        endMs: Long,
        bitmap: Bitmap,
        pixels: IntArray,
        encoded: ByteArrayOutputStream,
    ) {
        val resolver = context.contentResolver
        val seeds = IntArray(size) { LargeLibraryDeviceSim.itemSeed(runSalt, firstIndex + it) }
        val captures = LongArray(size) {
            LargeLibraryDeviceSim.captureTimeMs(seeds[it], LargeLibraryDeviceSim.SPAN_START_MS, endMs)
        }
        // One transaction inserts the whole batch as pending rows, hidden from other apps until published.
        val inserts = ArrayList<ContentProviderOperation>(size)
        for (k in 0 until size) {
            inserts += ContentProviderOperation.newInsert(collection)
                .withValue(MediaStore.MediaColumns.DISPLAY_NAME, LargeLibraryDeviceSim.displayName(runTag, firstIndex + k))
                .withValue(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                .withValue(MediaStore.MediaColumns.RELATIVE_PATH, LargeLibraryDeviceSim.RELATIVE_PATH)
                .withValue(MediaStore.MediaColumns.DATE_TAKEN, captures[k])
                .withValue(MediaStore.MediaColumns.IS_PENDING, 1)
                .build()
        }
        val uris = resolver.applyBatch(MediaStore.AUTHORITY, inserts).map { result ->
            result.uri ?: throw IOException("MediaStore insert returned no uri")
        }
        for (k in uris.indices) {
            val jpeg = encode(seeds[k], captures[k], bitmap, pixels, encoded)
            val stream = resolver.openOutputStream(uris[k]) ?: throw IOException("MediaStore gave no output stream")
            stream.use { it.write(jpeg) }
        }
        // Clearing IS_PENDING publishes the batch; MediaStore then scans each file and takes DATE_TAKEN from
        // its EXIF block.
        val publish = ArrayList<ContentProviderOperation>(uris.size)
        for (uri in uris) {
            publish += ContentProviderOperation.newUpdate(uri)
                .withValue(MediaStore.MediaColumns.IS_PENDING, 0)
                .build()
        }
        resolver.applyBatch(MediaStore.AUTHORITY, publish)
    }

    private fun encode(
        seed: Int,
        captureMs: Long,
        bitmap: Bitmap,
        pixels: IntArray,
        encoded: ByteArrayOutputStream,
    ): ByteArray {
        val edge = LargeLibraryDeviceSim.EDGE
        LargeLibraryDeviceSim.fillPixels(seed, pixels)
        bitmap.setPixels(pixels, 0, edge, 0, 0, edge, edge)
        encoded.reset()
        if (!bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, encoded)) {
            throw IOException("JPEG encode failed")
        }
        val exif = LargeLibraryDeviceSim.exifSegment(captureMs, LargeLibraryDeviceSim.gpsFor(seed))
        return LargeLibraryDeviceSim.withExif(encoded.toByteArray(), exif)
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private suspend fun runRemove() {
        val ids = ownIds()
        _state.update { it.copy(done = 0, total = ids.size, onDevice = ids.size) }
        val resolver = context.contentResolver
        var removed = 0
        var from = 0
        while (from < ids.size) {
            currentCoroutineContext().ensureActive()
            val to = min(from + DELETE_CHUNK, ids.size) - 1
            // The id range only narrows the guarded selection; the guard still decides which rows go.
            removed += resolver.delete(
                collection,
                ownArgs(
                    includeUnfinished = true,
                    extra = "${MediaStore.MediaColumns._ID} >= ? AND ${MediaStore.MediaColumns._ID} <= ?",
                    extraArgs = arrayOf(ids[from].toString(), ids[to].toString()),
                ),
            )
            from = to + 1
            _state.update { it.copy(done = from) }
        }
        val left = countOwn(includeUnfinished = true)
        _state.update {
            it.copy(
                message = if (left == 0) {
                    "Removed $removed simulated photos"
                } else {
                    "Removed $removed; $left left that this install does not own. Grant All files access " +
                        "or delete ${LargeLibraryDeviceSim.RELATIVE_PATH} in a file manager."
                },
            )
        }
    }

    /** Ids of every simulated file, unpublished and trashed ones included, ascending. */
    @RequiresApi(Build.VERSION_CODES.R)
    private fun ownIds(): LongArray =
        context.contentResolver.query(
            collection,
            arrayOf(MediaStore.MediaColumns._ID),
            ownArgs(includeUnfinished = true),
            null,
        )?.use { cursor ->
            val column = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            LongArray(cursor.count) { position ->
                cursor.moveToPosition(position)
                cursor.getLong(column)
            }
        }?.apply { sort() } ?: LongArray(0)

    @RequiresApi(Build.VERSION_CODES.R)
    private fun countOwn(includeUnfinished: Boolean): Int =
        context.contentResolver.query(
            collection,
            arrayOf(MediaStore.MediaColumns._ID),
            ownArgs(includeUnfinished),
            null,
        )?.use { it.count } ?: 0

    /** Delete this simulator's pending rows: the unpublished batch of a cancelled run, or of a process
     *  that died mid-batch. */
    @RequiresApi(Build.VERSION_CODES.R)
    private fun dropUnfinished(): Int = context.contentResolver.delete(
        collection,
        ownArgs(includeUnfinished = true, extra = "${MediaStore.MediaColumns.IS_PENDING} = 1"),
    )

    /** Query and delete arguments bound to the simulator's own files. [includeUnfinished] also matches
     *  the pending and trashed rows a plain query leaves out. */
    @RequiresApi(Build.VERSION_CODES.R)
    private fun ownArgs(
        includeUnfinished: Boolean,
        extra: String? = null,
        extraArgs: Array<String> = emptyArray(),
    ): Bundle = Bundle().apply {
        putString(ContentResolver.QUERY_ARG_SQL_SELECTION, LargeLibraryDeviceSim.ownSelection(extra))
        putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, LargeLibraryDeviceSim.ownSelectionArgs(extraArgs))
        if (includeUnfinished) {
            putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_INCLUDE)
            putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_INCLUDE)
        }
    }

    private companion object {
        /** Files per MediaStore transaction; insert, write and publish run batch by batch. */
        const val DEVICE_BATCH = 250

        /** Rows per guarded delete. */
        const val DELETE_CHUNK = 500

        const val JPEG_QUALITY = 85
    }
}
