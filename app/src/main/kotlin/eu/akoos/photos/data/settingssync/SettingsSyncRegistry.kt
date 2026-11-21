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

import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import eu.akoos.photos.data.preferences.SettingsKeys
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull

/** Who takes a setting from another device's copy. */
enum class SettingsScope {
    /** Taken from any device's copy, whatever its platform. */
    SHARED,

    /** Restored only from this install's own earlier copy, never from another device's, on any platform. */
    PLATFORM,
}

/** What reading one registry setting out of the preferences gave. */
internal sealed interface SettingReading {
    /** Never chosen here. */
    data object Unset : SettingReading

    /** Set to something the format cannot carry; left out of the sync altogether. */
    data object Unsyncable : SettingReading

    data class Value(val json: JsonElement) : SettingReading
}

/**
 * A setting that is one value: a flag, a number, a word, or a whole list that is replaced as one. [toJson] turns the
 * stored value into the payload value (null when the stored value cannot be carried) and [fromJson] turns a payload
 * value into the stored one (null when it fails the check for this setting, so a bad value is ignored).
 */
internal class SyncScalar(
    val id: String,
    val scope: SettingsScope,
    val key: Preferences.Key<*>,
    private val toJson: (Any) -> JsonElement?,
    private val fromJson: (JsonElement) -> Any?,
) {
    @Suppress("UNCHECKED_CAST")
    private val typedKey: Preferences.Key<Any> get() = key as Preferences.Key<Any>

    fun read(prefs: Preferences): SettingReading {
        val stored = prefs[typedKey] ?: return SettingReading.Unset
        return toJson(stored)?.let { SettingReading.Value(it) } ?: SettingReading.Unsyncable
    }

    /** Whether [value] is one this setting accepts. */
    fun accepts(value: JsonElement): Boolean = fromJson(value) != null

    /** Stores [value], or clears the setting when it is null (a reset to the default). */
    fun write(prefs: MutablePreferences, value: JsonElement?) {
        val stored = value?.let(fromJson)
        if (stored == null) prefs.remove(typedKey) else prefs[typedKey] = stored
    }
}

/**
 * A setting that is a set whose members are kept one by one, so two devices adding different members merge. The
 * payload names a member by [toElement] of the stored entry (null for an entry this setting does not carry) and
 * [fromElement] gives the entry back.
 */
internal class SyncSet(
    val id: String,
    val scope: SettingsScope,
    val key: Preferences.Key<Set<String>>,
    private val toElement: (String) -> String?,
    private val fromElement: (String) -> String,
    private val validElement: (String) -> Boolean,
) {
    /** The members the preferences hold, as the payload names them. */
    fun members(prefs: Preferences): Set<String> =
        (prefs[key] ?: emptySet()).mapNotNullTo(HashSet()) { toElement(it)?.takeIf(::accepts) }

    fun accepts(element: String): Boolean = element.length <= MAX_ELEMENT_LENGTH && validElement(element)

    /** Adds or removes the members of [changes] (member to present), leaving the entries this setting does not carry. */
    fun write(prefs: MutablePreferences, changes: Map<String, Boolean>) {
        val next = (prefs[key] ?: emptySet()).toMutableSet()
        for ((element, present) in changes) {
            if (present) next += fromElement(element) else next -= fromElement(element)
        }
        if (next.isEmpty()) prefs.remove(key) else prefs[key] = next
    }

    private companion object {
        const val MAX_ELEMENT_LENGTH = 256
    }
}

/**
 * The settings that travel in the Drive backup, an allow list: a setting is synced only when it is named here, and
 * adding one is a deliberate edit. Ids are the shared names the desktop client uses too. The app lock, the vault,
 * the hidden lists (which have their own file), network and battery rules, folder choices, model consents,
 * notification and update flags and everything else that belongs to one device or one account are not here. Pure,
 * so it is unit-tested directly.
 */
internal object SettingsSyncRegistry {

    private val TIER_WORDS = listOf("high", "balanced", "small")
    private val THEMES = setOf("system", "light", "dark")
    private val PALETTES = setOf("default", "forest", "sunset", "sea", "sepia", "mono", "lavender", "rose", "mint", "gold", "ruby")
    private val CODECS = setOf("auto", "avc", "hevc", "av1", "keep")
    private val CLOCKS = setOf("auto", "24h", "12h")
    private val LANGUAGE_TAG = Regex("[A-Za-z]{2,3}(-[A-Za-z0-9]{2,8}){0,3}")
    private val ENUM_NAME = Regex("[A-Za-z][A-Za-z0-9_]{0,63}")
    private const val MAX_TEXT = 16_384
    private const val MAX_LIST_ITEMS = 4_000
    private const val MAX_LIST_ITEM_LENGTH = 512
    private const val MAX_ORDER_ITEMS = 20_000
    private const val ORDER_SEPARATOR = '|'

    /** The album list's sort modes as the shared words, in the order of their stored ordinals. */
    private val ALBUM_SORT_WORDS = listOf("custom", "name", "activity", "count", "newest")

    private fun bool(id: String, scope: SettingsScope, key: Preferences.Key<Boolean>) = SyncScalar(
        id, scope, key,
        toJson = { JsonPrimitive(it as Boolean) },
        fromJson = { (it as? JsonPrimitive)?.takeIf { p -> !p.isString }?.booleanOrNull },
    )

    private fun int(id: String, scope: SettingsScope, key: Preferences.Key<Int>, range: IntRange) = SyncScalar(
        id, scope, key,
        toJson = { (it as Int).takeIf { v -> v in range }?.let(::JsonPrimitive) },
        fromJson = { (it as? JsonPrimitive)?.takeIf { p -> !p.isString }?.intOrNull?.takeIf { v -> v in range } },
    )

    private fun word(id: String, scope: SettingsScope, key: Preferences.Key<String>, allowed: Set<String>) =
        text(id, scope, key) { it in allowed }

    private fun text(id: String, scope: SettingsScope, key: Preferences.Key<String>, valid: (String) -> Boolean) = SyncScalar(
        id, scope, key,
        toJson = { (it as String).takeIf(valid)?.let(::JsonPrimitive) },
        fromJson = { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content?.takeIf(valid) },
    )

    /** A stored tier ordinal (0, 1, 2) carried as the words `high`, `balanced`, `small`. */
    private fun tier(id: String, key: Preferences.Key<Int>) = SyncScalar(
        id, SettingsScope.PLATFORM, key,
        toJson = { TIER_WORDS.getOrNull(it as Int)?.let(::JsonPrimitive) },
        fromJson = { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content?.let(TIER_WORDS::indexOf)?.takeIf { v -> v >= 0 } },
    )

    /** A whole list of strings that the newest write replaces, such as the per-album overrides. */
    private fun list(id: String, scope: SettingsScope, key: Preferences.Key<Set<String>>) = SyncScalar(
        id, scope, key,
        toJson = { value ->
            @Suppress("UNCHECKED_CAST")
            (value as Set<String>).takeIf(::listOk)?.let { set -> JsonArray(set.sorted().map(::JsonPrimitive)) }
        },
        fromJson = { json ->
            val items = (json as? JsonArray)?.map { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
            if (items == null || null in items) null else items.filterNotNull().toSet().takeIf(::listOk)
        },
    )

    /** A stored ordinal carried as the word at that position of [words]. */
    private fun ordinalWord(id: String, scope: SettingsScope, key: Preferences.Key<Int>, words: List<String>) = SyncScalar(
        id, scope, key,
        toJson = { words.getOrNull(it as Int)?.let(::JsonPrimitive) },
        fromJson = { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content?.let(words::indexOf)?.takeIf { v -> v >= 0 } },
    )

    /**
     * An ordered list of ids stored as one '|'-joined string, carried as a JSON array in that order. Bounded by the
     * number of ids, not by the length of the joined string.
     */
    private fun orderedIds(id: String, scope: SettingsScope, key: Preferences.Key<String>) = SyncScalar(
        id, scope, key,
        toJson = { value ->
            val items = (value as String).split(ORDER_SEPARATOR).filter { it.isNotBlank() }
            items.takeIf(::orderOk)?.let { list -> JsonArray(list.map(::JsonPrimitive)) }
        },
        fromJson = { json ->
            val items = (json as? JsonArray)?.map { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
            if (items == null || null in items) null
            else items.filterNotNull().takeIf(::orderOk)?.joinToString(ORDER_SEPARATOR.toString())
        },
    )

    private fun orderOk(items: List<String>) =
        items.size <= MAX_ORDER_ITEMS && items.all { it.isNotBlank() && it.length <= MAX_LIST_ITEM_LENGTH && ORDER_SEPARATOR !in it }

    private fun listOk(items: Set<String>) = items.size <= MAX_LIST_ITEMS && items.all { it.length <= MAX_LIST_ITEM_LENGTH }

    private fun members(
        id: String,
        scope: SettingsScope,
        key: Preferences.Key<Set<String>>,
        prefix: String = "",
        valid: (String) -> Boolean = { it.isNotBlank() && "://" !in it },
    ) = SyncSet(
        id, scope, key,
        toElement = { entry -> entry.takeIf { it.startsWith(prefix) }?.removePrefix(prefix)?.takeIf { it.isNotEmpty() } },
        fromElement = { element -> prefix + element },
        validElement = valid,
    )

    private fun enumName(value: String) = ENUM_NAME.matches(value)

    /** A CSV of enum names, how the rail and the tile row keep their order. */
    private fun csvOfNames(value: String) = value.length <= MAX_TEXT && value.split(',').all { it.isEmpty() || enumName(it) }

    val scalars: List<SyncScalar> = listOf(
        // Shared by every device, the phone's and the PC's.
        word("appearance.theme", SettingsScope.SHARED, SettingsKeys.THEME_MODE, THEMES),
        word("appearance.palette", SettingsScope.SHARED, SettingsKeys.THEME_PALETTE, PALETTES),
        bool("appearance.amoled", SettingsScope.SHARED, SettingsKeys.AMOLED_BLACK),
        bool("albums.hidePhotosInAlbums", SettingsScope.SHARED, SettingsKeys.HIDE_PHOTOS_IN_ALBUMS),
        ordinalWord("albums.sortMode", SettingsScope.SHARED, SettingsKeys.ALBUMS_SORT_MODE, ALBUM_SORT_WORDS),
        orderedIds("albums.order", SettingsScope.SHARED, SettingsKeys.ALBUMS_CUSTOM_ORDER),

        // Restored from this install's own earlier copy only.
        text("appearance.language", SettingsScope.PLATFORM, SettingsKeys.LANGUAGE) { it == "system" || LANGUAGE_TAG.matches(it) },
        bool("upload.compressPhotos", SettingsScope.PLATFORM, SettingsKeys.COMPRESS_ON_UPLOAD),
        bool("upload.compressHdr", SettingsScope.PLATFORM, SettingsKeys.COMPRESS_HDR_PHOTOS),
        tier("upload.compressTier", SettingsKeys.COMPRESS_UPLOAD_TIER),
        bool("upload.compressVideos", SettingsScope.PLATFORM, SettingsKeys.COMPRESS_VIDEO_ON_UPLOAD),
        tier("upload.compressVideoTier", SettingsKeys.COMPRESS_UPLOAD_TIER_VIDEO),
        word("upload.compressCodec", SettingsScope.PLATFORM, SettingsKeys.COMPRESS_VIDEO_CODEC, CODECS),

        // The phone's own display and behaviour choices.
        word("android.clockFormat", SettingsScope.PLATFORM, SettingsKeys.CLOCK_FORMAT, CLOCKS),
        int("android.landingTab", SettingsScope.PLATFORM, SettingsKeys.LANDING_TAB, 0..2),
        bool("android.liquidGlass", SettingsScope.PLATFORM, SettingsKeys.LIQUID_GLASS),
        bool("android.tintCloudWithAccent", SettingsScope.PLATFORM, SettingsKeys.TINT_CLOUD_WITH_ACCENT),
        bool("android.gifAutoplayGrid", SettingsScope.PLATFORM, SettingsKeys.GIF_AUTOPLAY_GRID),
        bool("android.gifAutoplayCovers", SettingsScope.PLATFORM, SettingsKeys.GIF_AUTOPLAY_COVERS),
        bool("android.viewerLongPressMenu", SettingsScope.PLATFORM, SettingsKeys.VIEWER_LONG_PRESS_OPENS_MENU),
        bool("android.mapStyleOsm", SettingsScope.PLATFORM, SettingsKeys.MAP_STYLE_OSM),
        bool("android.keepScrollOnTabSwitch", SettingsScope.PLATFORM, SettingsKeys.KEEP_SCROLL_ON_TAB_SWITCH),
        bool("android.onThisDay.show", SettingsScope.PLATFORM, SettingsKeys.SHOW_ON_THIS_DAY),
        int("android.onThisDay.windowDays", SettingsScope.PLATFORM, SettingsKeys.ON_THIS_DAY_WINDOW_DAYS, 0..3),
        bool("android.timeline.showScrollDate", SettingsScope.PLATFORM, SettingsKeys.SHOW_SCROLL_DATE),
        bool("android.timeline.reverse", SettingsScope.PLATFORM, SettingsKeys.REVERSE_TIMELINE_ORDER),
        bool("android.timeline.mosaic", SettingsScope.PLATFORM, SettingsKeys.MOSAIC_GRID),
        bool("android.timeline.seamless", SettingsScope.PLATFORM, SettingsKeys.SEAMLESS_GRID),
        int("android.albums.defaultFilter", SettingsScope.PLATFORM, SettingsKeys.ALBUMS_DEFAULT_FILTER, 0..3),
        bool("android.albums.rememberLastFilter", SettingsScope.PLATFORM, SettingsKeys.ALBUMS_REMEMBER_LAST_FILTER),
        bool("android.albums.showCollection", SettingsScope.PLATFORM, SettingsKeys.ALBUMS_SHOW_COLLECTION),
        int("android.albums.viewMode", SettingsScope.PLATFORM, SettingsKeys.ALBUMS_VIEW_MODE, 0..1),
        bool("android.people.followAlbums", SettingsScope.PLATFORM, SettingsKeys.PEOPLE_FOLLOW_ALBUMS),
        int("android.people.viewMode", SettingsScope.PLATFORM, SettingsKeys.PEOPLE_VIEW_MODE, 0..1),
        int("android.albums.photoSortMode", SettingsScope.PLATFORM, SettingsKeys.ALBUM_PHOTO_SORT_MODE, 0..1),
        int("android.albums.photoGrouping", SettingsScope.PLATFORM, SettingsKeys.ALBUM_PHOTO_GROUPING, 0..2),
        int("android.folders.photoSortMode", SettingsScope.PLATFORM, SettingsKeys.DEVICE_FOLDER_PHOTO_SORT_MODE, 0..1),
        int("android.folders.photoGrouping", SettingsScope.PLATFORM, SettingsKeys.DEVICE_FOLDER_PHOTO_GROUPING, 0..2),
        list("android.albums.photoGroupingOverrides", SettingsScope.PLATFORM, SettingsKeys.ALBUM_PHOTO_GROUPING_OVERRIDES),
        text("android.rail.order", SettingsScope.PLATFORM, SettingsKeys.CATEGORY_RAIL_ORDER, ::csvOfNames),
        text("android.tiles.order", SettingsScope.PLATFORM, SettingsKeys.ALBUMS_TILE_ORDER, ::csvOfNames),

        // Metadata stripping on upload and on share.
        bool("android.strip.onUpload", SettingsScope.PLATFORM, SettingsKeys.STRIP_ON_UPLOAD),
        bool("android.strip.gps", SettingsScope.PLATFORM, SettingsKeys.STRIP_GPS),
        bool("android.strip.cameraInfo", SettingsScope.PLATFORM, SettingsKeys.STRIP_CAMERA_INFO),
        bool("android.strip.timestamp", SettingsScope.PLATFORM, SettingsKeys.STRIP_TIMESTAMP),
        bool("android.strip.softwareInfo", SettingsScope.PLATFORM, SettingsKeys.STRIP_SOFTWARE_INFO),
        bool("android.strip.onShare", SettingsScope.PLATFORM, SettingsKeys.STRIP_ON_SHARE),
        bool("android.strip.shareGps", SettingsScope.PLATFORM, SettingsKeys.STRIP_SHARE_GPS),
        bool("android.strip.shareCameraInfo", SettingsScope.PLATFORM, SettingsKeys.STRIP_SHARE_CAMERA_INFO),
        bool("android.strip.shareTimestamp", SettingsScope.PLATFORM, SettingsKeys.STRIP_SHARE_TIMESTAMP),
        bool("android.strip.shareSoftwareInfo", SettingsScope.PLATFORM, SettingsKeys.STRIP_SHARE_SOFTWARE_INFO),
        bool("android.strip.shareAuthorship", SettingsScope.PLATFORM, SettingsKeys.STRIP_SHARE_AUTHORSHIP),
        bool("android.upload.renameToCaptureDate", SettingsScope.PLATFORM, SettingsKeys.RENAME_TO_CAPTURE_DATE),
    )

    val sets: List<SyncSet> = listOf(
        // The cloud albums taken off the timeline and off the Albums grid; device folders stay out.
        members("timeline.excludedAlbums", SettingsScope.PLATFORM, SettingsKeys.TIMELINE_EXCLUDED_ALBUM_IDS),
        members("albums.gridExcludedAlbums", SettingsScope.PLATFORM, SettingsKeys.ALBUMS_GRID_EXCLUDED, prefix = "album:"),
        members("android.rail.hidden", SettingsScope.PLATFORM, SettingsKeys.CATEGORY_RAIL_HIDDEN, valid = ::enumName),
        members("android.tiles.hidden", SettingsScope.PLATFORM, SettingsKeys.ALBUMS_TILE_HIDDEN, valid = ::enumName),
    )

    /**
     * Ids an older build kept a setting under, before it moved to a shared id: the new id, and how the old payload
     * value reads as the new one (null when it does not). Taken only from this install's own earlier copy, and only
     * when that copy has no value under the new id.
     */
    internal class LegacyAlias(val legacyId: String, val id: String, val convert: (JsonElement) -> JsonElement?)

    val legacyAliases: List<LegacyAlias> = listOf(
        LegacyAlias("android.albums.sortMode", "albums.sortMode") { json ->
            (json as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull?.let(ALBUM_SORT_WORDS::getOrNull)?.let(::JsonPrimitive)
        },
        LegacyAlias("android.albums.customOrder", "albums.order") { json ->
            (json as? JsonPrimitive)?.takeIf { it.isString }?.content
                ?.split(ORDER_SEPARATOR)?.filter { it.isNotBlank() }?.takeIf(::orderOk)
                ?.let { list -> JsonArray(list.map(::JsonPrimitive)) }
        },
    )

    private val aliasByLegacyId = legacyAliases.associateBy { it.legacyId }

    fun legacyAlias(id: String): LegacyAlias? = aliasByLegacyId[id]

    /** The payload value [legacyId] held for the stored [value] in the older build, to tell whether it changed since. */
    fun legacyJson(legacyId: String, value: Any): JsonElement? = when (legacyId) {
        "android.albums.sortMode" -> (value as? Int)?.let(::JsonPrimitive)
        "android.albums.customOrder" -> (value as? String)?.let(::JsonPrimitive)
        else -> null
    }

    private val scalarById = scalars.associateBy { it.id }
    private val setById = sets.associateBy { it.id }

    fun scalar(id: String): SyncScalar? = scalarById[id]

    fun set(id: String): SyncSet? = setById[id]

    /**
     * Whether a setting of [scope] is taken from a copy written by device [copyDeviceId] on [copyPlatform], by the
     * install [ownDeviceId] on [ownPlatform]. A shared setting comes from any copy; a platform setting only from this
     * install's own earlier copy.
     */
    fun takes(
        scope: SettingsScope,
        copyDeviceId: String,
        copyPlatform: String,
        ownDeviceId: String,
        ownPlatform: String,
    ): Boolean =
        scope == SettingsScope.SHARED || (copyPlatform == ownPlatform && copyDeviceId == ownDeviceId)

    /** Every preference key a registry setting lives in, for the change watch. */
    val watchedKeys: List<Preferences.Key<*>> = scalars.map { it.key } + sets.map { it.key }
}
