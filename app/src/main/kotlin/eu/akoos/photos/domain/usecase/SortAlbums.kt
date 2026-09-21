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

package eu.akoos.photos.domain.usecase

import eu.akoos.photos.domain.entity.Album
import java.text.Collator

/**
 * The order the Albums grid is presented in.
 *
 * Persisted as an ordinal in [eu.akoos.photos.data.preferences.SettingsKeys.ALBUMS_SORT_MODE], the
 * same way the Albums view filter is stored, so these values must keep their positions.
 */
enum class AlbumSortMode {
    /** The user's own arrangement. */
    Custom,

    /** A to Z by album name. */
    NameAsc,

    /** Most recently touched album first. */
    LastActivity,

    /** Largest album first. */
    PhotoCount;

    companion object {
        /** The order the album cache is read in, so an install that never picks a mode keeps the
         *  grid it already had. */
        val Default = LastActivity

        /** Read back a persisted ordinal, tolerating an absent or out-of-range value. */
        fun fromOrdinal(ordinal: Int?): AlbumSortMode =
            ordinal?.let { AlbumSortMode.entries.getOrNull(it) } ?: Default
    }
}

/**
 * Order [albums] for display.
 *
 * Total, not merely stable: every mode breaks ties on `linkId`, so two albums agreeing on the sort
 * key hold a fixed order instead of swapping between calls. The grid is painted twice, once from
 * cache and once from the network, and any disagreement between those two surfaces as the list
 * rearranging itself under the user.
 *
 * [customOrder] carries the user's own arrangement as album linkIds. While it is empty,
 * [AlbumSortMode.Custom] resolves to [AlbumSortMode.LastActivity]. The other modes ignore it.
 */
fun sortAlbums(
    albums: List<Album>,
    mode: AlbumSortMode,
    customOrder: List<String> = emptyList(),
): List<Album> = when (mode) {
    AlbumSortMode.Custom -> sortByCustomOrder(albums, customOrder)
    AlbumSortMode.NameAsc -> albums.sortedWith(byName())
    AlbumSortMode.LastActivity -> albums.sortedWith(byLastActivity)
    AlbumSortMode.PhotoCount -> albums.sortedWith(byPhotoCount)
}

/**
 * Separator for the persisted arrangement.
 *
 * A Drive linkId is an opaque base64 token, an alphabet that holds no pipe, so no id can be cut in
 * half by its own contents. Matches how
 * [eu.akoos.photos.data.preferences.SettingsKeys.PENDING_ORPHAN_DELETES] and the photo widget
 * already join Drive linkIds, which is the convention for this kind of value.
 */
private const val CUSTOM_ORDER_SEPARATOR = "|"

/**
 * Flatten an arrangement for [eu.akoos.photos.data.preferences.SettingsKeys.ALBUMS_CUSTOM_ORDER].
 *
 * Normalises to what [decodeAlbumOrder] can give back, so the pair round-trips: blanks are dropped
 * and a repeated id keeps only its first slot.
 */
fun encodeAlbumOrder(linkIds: List<String>): String =
    linkIds.asSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .distinct()
        .joinToString(CUSTOM_ORDER_SEPARATOR)

/**
 * Read back an arrangement, tolerating an absent, empty or malformed value.
 *
 * A duplicate id keeps its first slot and later repeats are dropped: without that, a corrupted
 * value would let one album claim two positions in the grid.
 *
 * Ids naming albums that are not on screen are kept. Hidden albums are filtered out after ordering
 * and shared albums render elsewhere, so an id the grid cannot currently place is normal and holds
 * that album's slot for its return. [sortAlbums] ignores an id it does not find, which makes a
 * genuinely deleted album's leftover id harmless.
 */
fun decodeAlbumOrder(stored: String?): List<String> {
    if (stored.isNullOrEmpty()) return emptyList()
    return stored.split(CUSTOM_ORDER_SEPARATOR)
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .distinct()
}

/** Nulls sink to the bottom of a newest-first list: an album with no recorded activity is not a
 *  recently touched one. */
private val byLastActivity: Comparator<Album> =
    compareByDescending<Album> { it.lastActivityTimeMs ?: Long.MIN_VALUE }.thenBy { it.linkId }

private val byPhotoCount: Comparator<Album> =
    compareByDescending<Album> { it.photoCount }.thenBy { it.linkId }

/**
 * Collation follows the device locale so accented names land where a reader of that language
 * expects them rather than after `z`. SECONDARY strength keeps accents significant while ignoring
 * case, which leaves names differing only in case to the linkId tie-break.
 */
private fun byName(): Comparator<Album> {
    val collator = Collator.getInstance().apply { strength = Collator.SECONDARY }
    return Comparator<Album> { a, b -> collator.compare(a.name, b.name) }.thenBy { it.linkId }
}

private fun sortByCustomOrder(albums: List<Album>, customOrder: List<String>): List<Album> {
    if (customOrder.isEmpty()) return albums.sortedWith(byLastActivity)
    val rank = customOrder.withIndex().associate { (index, linkId) -> linkId to index }
    // Albums the arrangement does not mention trail the ones it does, in the default order, so an
    // album created after the arrangement was saved lands somewhere predictable.
    return albums.sortedWith(
        compareBy<Album> { rank[it.linkId] ?: Int.MAX_VALUE }.then(byLastActivity)
    )
}
