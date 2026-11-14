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

package eu.akoos.photos.data.settingssync

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * One value of a settings copy: the setting's id, when it last changed, and its value. A null [value] is the setting
 * reset to its default, kept with its time so the reset reaches the other devices. Time 0 only fills a gap.
 */
@Serializable
data class SettingsScalarEntry(
    @SerialName("k") val key: String = "",
    @SerialName("t") val changedAtMillis: Long = 0L,
    @SerialName("x") val value: JsonElement? = null,
)

/**
 * One member of a settings copy that is a set: the set's id, the member, whether it is in the set, and when that last
 * changed. `h: false` is a member taken out again, kept so the removal reaches the other devices.
 */
@Serializable
data class SettingsMemberEntry(
    @SerialName("k") val key: String = "",
    @SerialName("e") val element: String = "",
    @SerialName("h") val present: Boolean? = null,
    @SerialName("t") val changedAtMillis: Long = 0L,
)

/**
 * One install's settings as it saves them to Drive, the format the desktop client reads and writes:
 * JSON v1 `{ v, d, p, n, c, s: [{ k, t, x }], l: [{ k, e, h, t }] }`, where [platform] is `android` or `desktop`.
 */
@Serializable
data class SettingsSnapshot(
    @SerialName("v") val version: Int = VERSION,
    @SerialName("d") val deviceId: String,
    @SerialName("p") val platform: String = "",
    @SerialName("n") val deviceLabel: String,
    @SerialName("c") val createdAtMillis: Long,
    @SerialName("s") val scalars: List<SettingsScalarEntry> = emptyList(),
    @SerialName("l") val members: List<SettingsMemberEntry> = emptyList(),
) {
    companion object {
        const val VERSION = 1

        /** The platform this build writes in [platform]. */
        const val PLATFORM_ANDROID = "android"
    }
}

/** The settings copy's plaintext form, before it is sealed. */
object SettingsSnapshotCodec {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun encode(snapshot: SettingsSnapshot): ByteArray =
        json.encodeToString(SettingsSnapshot.serializer(), snapshot).toByteArray(Charsets.UTF_8)

    /** The format version [bytes] declare, or null when they are not a settings copy at all. */
    fun versionOf(bytes: ByteArray): Int? = runCatching {
        Json.parseToJsonElement(String(bytes, Charsets.UTF_8)).jsonObject["v"]?.jsonPrimitive?.int
    }.getOrNull()

    /** The settings copy in [bytes], or null when it is not one this build reads. Entries that say nothing are dropped. */
    fun decode(bytes: ByteArray): SettingsSnapshot? =
        runCatching { json.decodeFromString(SettingsSnapshot.serializer(), String(bytes, Charsets.UTF_8)) }
            .getOrNull()
            ?.takeIf { it.version == SettingsSnapshot.VERSION }
            ?.let { copy ->
                copy.copy(
                    scalars = copy.scalars.filter { it.key.isNotBlank() }
                        .map { it.copy(changedAtMillis = it.changedAtMillis.coerceAtLeast(0L), value = it.value.takeUnless { v -> v is JsonNull }) },
                    members = copy.members.filter { it.key.isNotBlank() && it.element.isNotBlank() && it.present != null }
                        .map { it.copy(changedAtMillis = it.changedAtMillis.coerceAtLeast(0L)) },
                )
            }
}

/** A setting's value as this device holds it, with when it last changed here; a null value is a reset. */
data class SettingsScalarState(val at: Long, val value: JsonElement?)

/** A set member as this device holds it, with when it last changed here. */
data class SettingsMemberState(val at: Long, val present: Boolean)

/**
 * This device's synced settings with their times, as the merge reads and writes them: every setting that carries a
 * time, and every set member that does, in or out.
 */
class SettingsSyncState(
    val scalars: MutableMap<String, SettingsScalarState> = HashMap(),
    val members: MutableMap<String, MutableMap<String, SettingsMemberState>> = HashMap(),
) {
    val isEmpty: Boolean
        get() = scalars.isEmpty() && members.values.all { it.isEmpty() }
}

/**
 * The settings' merge rule, the same the desktop client keeps: per setting and per set member the later change wins,
 * here or there; an equal time goes to the larger device id; an entry without a time (0) only fills a gap, as a
 * setting this device never changed reads as -1. A setting scoped to a platform is taken only from this install's
 * own earlier copy. Taking the same copy twice changes nothing. Pure, so it runs in a plain JVM test.
 */
internal object SettingsCopyMerge {

    /** What taking copies in changed: the settings and set members now holding another device's value and time. */
    data class Changes(
        val scalars: Map<String, SettingsScalarState> = emptyMap(),
        val members: Map<String, Map<String, SettingsMemberState>> = emptyMap(),
    ) {
        val isEmpty: Boolean
            get() = scalars.isEmpty() && members.values.all { it.isEmpty() }

        operator fun plus(other: Changes) = Changes(
            scalars + other.scalars,
            (members.keys + other.members.keys).associateWith { (members[it] ?: emptyMap()) + (other.members[it] ?: emptyMap()) },
        )
    }

    /**
     * Takes another install's [copy] into [state]; returns what changed. A copy whose device id is [readsAs] is this
     * install's own and is passed over, except while the round reads its own earlier copy (then [readsAs] is blank
     * and the copy counts as another device's). [ownDeviceId] settles a tie and names the one copy a
     * platform-scoped setting may come from, when it is also on [ownPlatform].
     */
    fun takeIn(
        state: SettingsSyncState,
        copy: SettingsSnapshot,
        readsAs: String,
        ownDeviceId: String,
        ownPlatform: String = SettingsSnapshot.PLATFORM_ANDROID,
    ): Changes {
        if (copy.deviceId.isEmpty() || copy.deviceId == readsAs) return Changes()
        val wins = { at: Long, held: Long, differs: Boolean ->
            at > held || (at == held && differs && copy.deviceId > ownDeviceId)
        }
        val scalars = HashMap<String, SettingsScalarState>()
        for (entry in copy.scalars) {
            val setting = SettingsSyncRegistry.scalar(entry.key) ?: continue
            if (!SettingsSyncRegistry.takes(setting.scope, copy.deviceId, copy.platform, ownDeviceId, ownPlatform)) continue
            if (entry.value != null && !setting.accepts(entry.value)) continue
            val held = state.scalars[entry.key]
            if (!wins(entry.changedAtMillis, held?.at ?: -1L, held?.value != entry.value)) continue
            val taken = SettingsScalarState(entry.changedAtMillis, entry.value)
            state.scalars[entry.key] = taken
            scalars[entry.key] = taken
        }
        val members = HashMap<String, HashMap<String, SettingsMemberState>>()
        for (entry in copy.members) {
            val setting = SettingsSyncRegistry.set(entry.key) ?: continue
            if (!SettingsSyncRegistry.takes(setting.scope, copy.deviceId, copy.platform, ownDeviceId, ownPlatform)) continue
            if (!setting.accepts(entry.element)) continue
            val present = entry.present ?: continue
            val held = state.members[entry.key]?.get(entry.element)
            if (!wins(entry.changedAtMillis, held?.at ?: -1L, held?.present != present)) continue
            val taken = SettingsMemberState(entry.changedAtMillis, present)
            state.members.getOrPut(entry.key) { HashMap() }[entry.element] = taken
            members.getOrPut(entry.key) { HashMap() }[entry.element] = taken
        }
        return Changes(scalars, members)
    }

    /** [state] as this install's copy: every setting and member with its time, ordered so equal state gives equal bytes. */
    fun forDrive(
        state: SettingsSyncState,
        deviceId: String,
        deviceLabel: String,
        createdAt: Long,
        platform: String = SettingsSnapshot.PLATFORM_ANDROID,
    ): SettingsSnapshot = SettingsSnapshot(
        deviceId = deviceId,
        platform = platform,
        deviceLabel = deviceLabel,
        createdAtMillis = createdAt,
        scalars = state.scalars.map { (id, s) -> SettingsScalarEntry(id, s.at, s.value) }.sortedBy { it.key },
        members = state.members.flatMap { (id, byElement) ->
            byElement.map { (element, s) -> SettingsMemberEntry(id, element, s.present, s.at) }
        }.sortedWith(compareBy({ it.key }, { it.element })),
    )
}
