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

package eu.akoos.photos.data.label

import eu.akoos.photos.util.LabelNormalizer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * One photo's labels in a Drive snapshot. [key] is the photo's gallery identity, [changedAt] the time its list
 * last changed, and an empty [labels] a photo whose last label was removed, which is what lets the removal
 * outrank an older list saved elsewhere.
 */
@Serializable
data class LabelSnapshotPhoto(
    @SerialName("k") val key: String,
    @SerialName("t") val changedAt: Long,
    @SerialName("l") val labels: List<String>,
)

/** What one install saves of its labels: who wrote it, when, and every photo's list. */
@Serializable
data class LabelSnapshot(
    @SerialName("v") val version: Int = VERSION,
    @SerialName("d") val deviceId: String,
    @SerialName("n") val deviceLabel: String,
    @SerialName("c") val createdAtMillis: Long,
    @SerialName("p") val photos: List<LabelSnapshotPhoto>,
    /** Labels created without a photo and not on one since. */
    @SerialName("m") val names: List<String> = emptyList(),
) {
    companion object {
        const val VERSION = 1
    }
}

/** The snapshot's plaintext form, before it is sealed. */
object LabelSnapshotCodec {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun encode(snapshot: LabelSnapshot): ByteArray = json.encodeToString(LabelSnapshot.serializer(), snapshot).toByteArray(Charsets.UTF_8)

    /** The format version [bytes] declares, or null when they are not a snapshot at all. */
    fun versionOf(bytes: ByteArray): Int? = runCatching {
        Json.parseToJsonElement(String(bytes, Charsets.UTF_8)).jsonObject["v"]?.jsonPrimitive?.int
    }.getOrNull()

    /** The snapshot in [bytes], or null when it is not one this build reads. */
    fun decode(bytes: ByteArray): LabelSnapshot? =
        runCatching { json.decodeFromString(LabelSnapshot.serializer(), String(bytes, Charsets.UTF_8)) }
            .getOrNull()
            ?.takeIf { it.version == LabelSnapshot.VERSION }
}

/** What a restore takes from a snapshot. Pure, so it runs in a plain JVM test. */
object LabelSnapshotMerge {

    /**
     * True for a key that names the same photo on every device of the account: a Drive link id, which carries
     * no URI scheme. A device key (a `content://` or `file://` uri) names another photo on another phone.
     */
    fun isPortableKey(photoKey: String): Boolean = photoKey.isNotEmpty() && "://" !in photoKey

    /**
     * The photos of [remote] to write here: those whose list changed after the one held here ([local], the
     * change time per key; absent means nothing is held). A tie keeps the local list. A device key is taken
     * only from the snapshot this install wrote itself ([ownDeviceId]). Each list is cleaned and capped as a
     * label write would, and a time later than [now] counts as [now], so one phone with a wrong clock cannot
     * outrank every later change.
     */
    fun plan(
        remote: LabelSnapshot,
        ownDeviceId: String,
        local: Map<String, Long>,
        now: Long,
    ): List<LabelSnapshotPhoto> {
        val own = remote.deviceId == ownDeviceId
        return remote.photos.mapNotNull { photo ->
            if (photo.key.isEmpty() || (!own && !isPortableKey(photo.key))) return@mapNotNull null
            val changedAt = photo.changedAt.coerceAtMost(now)
            val heldAt = local[photo.key]
            if (heldAt != null && changedAt <= heldAt) return@mapNotNull null
            LabelSnapshotPhoto(photo.key, changedAt, LabelNormalizer.normalize(photo.labels).labels)
        }
    }
}
