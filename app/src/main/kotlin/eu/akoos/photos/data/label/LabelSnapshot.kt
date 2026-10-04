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

/** One photo's labels in a Drive snapshot, under the photo's gallery key. */
@Serializable
data class LabelSnapshotPhoto(
    @SerialName("k") val key: String,
    @SerialName("l") val labels: List<String>,
)

/** What one install saves of its labels: who wrote it, when, every labelled photo, and the labels with no photo. */
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

/** One label of a saved copy and how many of its photos carry it, as the restore drawer lists it. */
data class LabelCount(val label: String, val photos: Int)

/** What a restore takes from a snapshot. Pure, so it runs in a plain JVM test. */
object LabelSnapshotMerge {

    /**
     * True for a key that names the same photo on every device of the account: a Drive link id, which carries
     * no URI scheme. A device key (a `content://` or `file://` uri) names another photo on another phone.
     */
    fun isPortableKey(photoKey: String): Boolean = photoKey.isNotEmpty() && "://" !in photoKey

    /**
     * The photos of [snapshot] a restore on this install may put back: a Drive link id from any copy, a device
     * key only from the copy this install wrote itself ([ownDeviceId]), since the same URI names another photo
     * on another phone.
     */
    fun usablePhotos(snapshot: LabelSnapshot, ownDeviceId: String): List<LabelSnapshotPhoto> {
        val own = snapshot.deviceId == ownDeviceId
        return snapshot.photos.filter { it.key.isNotEmpty() && (own || isPortableKey(it.key)) }
    }

    /**
     * A photo's labels once a saved list comes back: the saved ones first, then the labels it has now that the
     * saved list does not, each label once (case and accents aside) and at most [LabelNormalizer.MAX_LABELS].
     * What was saved always returns, and a label added since is kept while there is room.
     */
    fun restoredList(saved: List<String>, current: List<String>): List<String> =
        LabelNormalizer.normalize(saved + current).labels

    /**
     * The labels of [snapshot] a restore would bring, each with the number of its usable photos
     * ([usablePhotos]), most used first; a saved label with no photo counts none. The first spelling seen wins.
     */
    fun counts(snapshot: LabelSnapshot, ownDeviceId: String): List<LabelCount> {
        val byKey = LinkedHashMap<String, Pair<String, Int>>()
        for (photo in usablePhotos(snapshot, ownDeviceId)) {
            for (label in LabelNormalizer.normalize(photo.labels).labels) {
                val key = LabelNormalizer.fold(label)
                val seen = byKey[key]
                byKey[key] = (seen?.first ?: label) to ((seen?.second ?: 0) + 1)
            }
        }
        for (name in snapshot.names) {
            val clean = LabelNormalizer.clean(name) ?: continue
            byKey.putIfAbsent(LabelNormalizer.fold(clean), clean to 0)
        }
        return byKey.entries
            .sortedWith(compareByDescending<Map.Entry<String, Pair<String, Int>>> { it.value.second }.thenBy { it.key })
            .map { LabelCount(it.value.first, it.value.second) }
    }
}
