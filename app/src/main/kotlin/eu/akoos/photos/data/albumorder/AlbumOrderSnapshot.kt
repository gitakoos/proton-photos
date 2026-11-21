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

package eu.akoos.photos.data.albumorder

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * One album of an album-order copy: its Drive link id [key], how it lists its photos ([mode] `newest`, `oldest` or
 * `custom`, changed at [modeAtMillis]) and its hand-made order ([order], photo link ids in display order, saved at
 * [orderAtMillis]). Either half may be absent; each travels with its own time.
 */
@Serializable
data class AlbumOrderSnapshotEntry(
    @SerialName("k") val key: String = "",
    @SerialName("m") val mode: String? = null,
    @SerialName("mt") val modeAtMillis: Long? = null,
    @SerialName("o") val order: List<String>? = null,
    @SerialName("ot") val orderAtMillis: Long? = null,
)

/**
 * One install's album photo orders as it saves them to Drive, the format the desktop client reads and writes too:
 * JSON v1 `{ v, d, p, n, c, a: [{ k, m, mt, o, ot }] }`, a field without a value left out.
 */
@Serializable
data class AlbumOrderSnapshot(
    @SerialName("v") val version: Int = VERSION,
    @SerialName("d") val deviceId: String,
    @SerialName("p") val platform: String = "",
    @SerialName("n") val deviceLabel: String,
    @SerialName("c") val createdAtMillis: Long,
    @SerialName("a") val albums: List<AlbumOrderSnapshotEntry> = emptyList(),
) {
    companion object {
        const val VERSION = 1
        const val PLATFORM_ANDROID = "android"
        const val MODE_NEWEST = "newest"
        const val MODE_OLDEST = "oldest"
        const val MODE_CUSTOM = "custom"
        val MODES = setOf(MODE_NEWEST, MODE_OLDEST, MODE_CUSTOM)
    }
}

/** The album orders' plaintext form, before it is sealed. */
@OptIn(ExperimentalSerializationApi::class)
object AlbumOrderSnapshotCodec {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    fun encode(snapshot: AlbumOrderSnapshot): ByteArray =
        json.encodeToString(AlbumOrderSnapshot.serializer(), snapshot).toByteArray(Charsets.UTF_8)

    /** The format version [bytes] declares, or null when they are not an album-order list at all. */
    fun versionOf(bytes: ByteArray): Int? = runCatching {
        Json.parseToJsonElement(String(bytes, Charsets.UTF_8)).jsonObject["v"]?.jsonPrimitive?.int
    }.getOrNull()

    /**
     * The album orders in [bytes], or null when they are not a list this build reads. A half with an unknown mode
     * or a missing time is dropped, blank and repeated photo ids are dropped, and an entry left with neither half
     * goes too.
     */
    fun decode(bytes: ByteArray): AlbumOrderSnapshot? =
        runCatching { json.decodeFromString(AlbumOrderSnapshot.serializer(), String(bytes, Charsets.UTF_8)) }
            .getOrNull()
            ?.takeIf { it.version == AlbumOrderSnapshot.VERSION }
            ?.let { copy -> copy.copy(albums = copy.albums.mapNotNull(::clean)) }

    private fun clean(entry: AlbumOrderSnapshotEntry): AlbumOrderSnapshotEntry? {
        if (entry.key.isBlank()) return null
        val modeOk = entry.mode in AlbumOrderSnapshot.MODES && entry.modeAtMillis != null
        val order = entry.order?.filter { it.isNotBlank() }?.distinct()
        val orderOk = order != null && entry.orderAtMillis != null
        if (!modeOk && !orderOk) return null
        return AlbumOrderSnapshotEntry(
            key = entry.key,
            mode = entry.mode.takeIf { modeOk },
            modeAtMillis = entry.modeAtMillis?.coerceAtLeast(0L)?.takeIf { modeOk },
            order = order.takeIf { orderOk },
            orderAtMillis = entry.orderAtMillis?.coerceAtLeast(0L)?.takeIf { orderOk },
        )
    }
}

/** An album's photo order choice and when it was made. */
data class AlbumModeState(val mode: String, val at: Long)

/** An album's hand-made order and when it was saved. */
data class AlbumArrangementState(val order: List<String>, val at: Long)

/** This device's album orders with their times, by album link id, as the merge reads and writes them. */
class AlbumOrderSyncState(
    val modes: MutableMap<String, AlbumModeState> = HashMap(),
    val orders: MutableMap<String, AlbumArrangementState> = HashMap(),
) {
    val isEmpty: Boolean get() = modes.isEmpty() && orders.isEmpty()
}

/**
 * The album orders' merge rule, the same the desktop client keeps: per album, the mode and the order each on their
 * own, the later time wins; on an equal time a differing value from the larger device id wins; a value absent here
 * counts as time -1, so time 0 only fills a gap. Taking the same copy twice changes nothing. Pure.
 */
object AlbumOrderCopyMerge {

    /** What taking copies in changed, by album link id, each value with the time it came with. */
    data class Changes(
        val modes: Map<String, AlbumModeState> = emptyMap(),
        val orders: Map<String, AlbumArrangementState> = emptyMap(),
    ) {
        val isEmpty: Boolean get() = modes.isEmpty() && orders.isEmpty()

        operator fun plus(other: Changes) = Changes(modes + other.modes, orders + other.orders)
    }

    /**
     * Takes [copy] into [state]; returns what changed. A copy written as [readsAs] is passed over: that is this
     * install's own, except on a restore, when the caller reads as an earlier install and takes its own copy back.
     */
    fun takeIn(state: AlbumOrderSyncState, copy: AlbumOrderSnapshot, readsAs: String, ownDeviceId: String): Changes {
        if (copy.deviceId.isEmpty() || copy.deviceId == readsAs) return Changes()
        val wins = { at: Long, held: Long, differs: Boolean ->
            at > held || (at == held && differs && copy.deviceId > ownDeviceId)
        }
        val modes = HashMap<String, AlbumModeState>()
        val orders = HashMap<String, AlbumArrangementState>()
        for (entry in copy.albums) {
            val mode = entry.mode
            val modeAt = entry.modeAtMillis
            if (mode != null && modeAt != null && mode in AlbumOrderSnapshot.MODES) {
                val held = state.modes[entry.key]
                if (wins(modeAt, held?.at ?: -1L, held?.mode != mode)) {
                    val taken = AlbumModeState(mode, modeAt)
                    state.modes[entry.key] = taken
                    modes[entry.key] = taken
                }
            }
            val order = entry.order
            val orderAt = entry.orderAtMillis
            if (order != null && orderAt != null) {
                val held = state.orders[entry.key]
                if (wins(orderAt, held?.at ?: -1L, held?.order != order)) {
                    val taken = AlbumArrangementState(order, orderAt)
                    state.orders[entry.key] = taken
                    orders[entry.key] = taken
                }
            }
        }
        return Changes(modes, orders)
    }

    /**
     * [state] as this install's copy, ordered by album link id. With [albums] given (the albums of a complete album
     * listing), an album outside it is left out, which is how a deleted album ages out of every copy.
     */
    fun forDrive(
        state: AlbumOrderSyncState,
        deviceId: String,
        deviceLabel: String,
        createdAt: Long,
        albums: Set<String>? = null,
        platform: String = AlbumOrderSnapshot.PLATFORM_ANDROID,
    ): AlbumOrderSnapshot {
        val keys = (state.modes.keys + state.orders.keys).filter { albums == null || it in albums }.sorted()
        return AlbumOrderSnapshot(
            deviceId = deviceId,
            platform = platform,
            deviceLabel = deviceLabel,
            createdAtMillis = createdAt,
            albums = keys.map { key ->
                val mode = state.modes[key]
                val order = state.orders[key]
                AlbumOrderSnapshotEntry(key, mode?.mode, mode?.at, order?.order, order?.at)
            },
        )
    }
}
