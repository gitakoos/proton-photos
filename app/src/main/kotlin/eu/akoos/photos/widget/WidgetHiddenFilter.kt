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

package eu.akoos.photos.widget

import androidx.datastore.preferences.core.Preferences
import eu.akoos.photos.data.db.entity.CloudAlbumEntity
import eu.akoos.photos.data.db.entity.PhotoPickerRow
import eu.akoos.photos.data.preferences.SettingsKeys
import eu.akoos.photos.domain.entity.LocalMediaItem

/**
 * Everything a home-screen widget may never show, read from the records the app's own hide filters
 * read.
 *
 * A widget draws on the launcher, outside the app and outside its lock, so it honours every way a
 * photo is put away: [cloudIds] are the hidden cloud photos (see [widgetHiddenCloudIds]), [albumIds]
 * the hidden albums and [folderNames] the hidden device folders, by bucket display name.
 */
data class WidgetHiddenSets(
    val cloudIds: Set<String> = emptySet(),
    val albumIds: Set<String> = emptySet(),
    val folderNames: Set<String> = emptySet(),
)

/**
 * Every cloud linkId a widget leaves out: the photos hidden one by one, the members of a hidden album,
 * and the Drive copies of vaulted photos, which stay in the account while the device file sits in the
 * vault. The gallery drops all three from every listing, and so does the widget.
 */
internal fun widgetHiddenCloudIds(
    individuallyHidden: Set<String>,
    hiddenAlbumMembers: Set<String>,
    vaultedCloudIds: Set<String>,
): Set<String> = individuallyHidden + hiddenAlbumMembers + vaultedCloudIds

/**
 * The Drive copies of vaulted photos, from both records that name one: the `"vaultUri|cloudLinkId"`
 * entries a hide writes to settings, and the cloud ids on the vault's own sync rows.
 */
internal fun vaultedCloudIds(
    hiddenUriCloudIdEntries: Set<String>,
    vaultedRowCloudIds: Collection<String>,
): Set<String> {
    val ids = HashSet<String>(hiddenUriCloudIdEntries.size + vaultedRowCloudIds.size)
    for (entry in hiddenUriCloudIdEntries) {
        val linkId = entry.substringAfter('|', "")
        if (linkId.isNotBlank()) ids += linkId
    }
    vaultedRowCloudIds.filterTo(ids) { it.isNotBlank() }
    return ids
}

/**
 * The hidden sets as of one read of the settings, given the two parts that live in the database
 * instead: the members of the hidden albums and the cloud ids on the vault's sync rows.
 */
internal fun widgetHiddenSets(
    prefs: Preferences,
    hiddenAlbumMembers: Set<String>,
    vaultedRowCloudIds: Collection<String>,
): WidgetHiddenSets = WidgetHiddenSets(
    cloudIds = widgetHiddenCloudIds(
        individuallyHidden = prefs[SettingsKeys.HIDDEN_CLOUD_PHOTO_IDS].orEmpty(),
        hiddenAlbumMembers = hiddenAlbumMembers,
        vaultedCloudIds = vaultedCloudIds(
            prefs[SettingsKeys.HIDDEN_URI_CLOUD_ID_MAP].orEmpty(),
            vaultedRowCloudIds,
        ),
    ),
    albumIds = prefs[SettingsKeys.HIDDEN_ALBUM_IDS].orEmpty(),
    folderNames = prefs[SettingsKeys.HIDDEN_FOLDER_NAMES].orEmpty(),
)

/** The modes that draw decrypted cloud photos, which need a signed-in account behind them. */
internal val WidgetMode.isCloud: Boolean
    get() = this == WidgetMode.CLOUD_SELECTED || this == WidgetMode.CLOUD_ALBUM

/** The picked cloud photos a widget may still cycle through, in their picked order. */
internal fun visibleCloudLinkIds(linkIds: List<String>, hidden: WidgetHiddenSets): List<String> =
    if (hidden.cloudIds.isEmpty()) linkIds else linkIds.filterNot { it in hidden.cloudIds }

/**
 * The members of a followed album a widget may show: none once the album itself is hidden, and
 * otherwise every member that is not hidden in its own right.
 */
internal fun followedAlbumLinkIds(
    albumLinkId: String?,
    memberLinkIds: List<String>,
    hidden: WidgetHiddenSets,
): List<String> =
    if (albumLinkId == null || albumLinkId in hidden.albumIds) emptyList()
    else visibleCloudLinkIds(memberLinkIds, hidden)

/** The cloud photos the picker offers. */
internal fun visiblePickerRows(rows: List<PhotoPickerRow>, hidden: WidgetHiddenSets): List<PhotoPickerRow> =
    if (hidden.cloudIds.isEmpty()) rows else rows.filterNot { it.linkId in hidden.cloudIds }

/**
 * The cloud albums the picker offers: never a hidden album, and never a hidden photo as a cover. A
 * hidden cover is dropped rather than swapped, so the row draws its neutral tile, which is what the
 * Albums grid shows when no visible cover resolves.
 */
internal fun visibleCloudAlbums(
    albums: List<CloudAlbumEntity>,
    hidden: WidgetHiddenSets,
): List<CloudAlbumEntity> = albums.mapNotNull { album ->
    when {
        album.linkId in hidden.albumIds -> null
        album.coverLinkId != null && album.coverLinkId in hidden.cloudIds -> album.copy(coverLinkId = null)
        else -> album
    }
}

/** Whether a device photo sits in a folder the user hid. A photo with no bucket sits in none. */
internal fun isInHiddenFolder(bucketName: String?, hiddenFolderNames: Set<String>): Boolean =
    bucketName != null && bucketName in hiddenFolderNames

/**
 * Device photos whose Drive copy is hidden. A photo in a hidden album keeps its device file, and the
 * gallery leaves that file out along with its cloud copy. [pairs] are (device uri, paired cloud
 * linkId) as the sync rows record them.
 */
internal fun hiddenTwinUris(
    pairs: Sequence<Pair<String, String?>>,
    hiddenCloudIds: Set<String>,
): Set<String> {
    if (hiddenCloudIds.isEmpty()) return emptySet()
    return pairs
        .filter { (_, cloudId) -> cloudId != null && cloudId in hiddenCloudIds }
        .mapTo(HashSet()) { it.first }
}

/** Every device photo the picker leaves out: the ones in a hidden folder and the ones whose Drive
 *  copy is hidden. */
internal fun hiddenDeviceUris(
    items: List<LocalMediaItem>,
    hiddenFolderNames: Set<String>,
    twinUris: Set<String>,
): Set<String> {
    if (hiddenFolderNames.isEmpty()) return twinUris
    val uris = HashSet(twinUris)
    for (item in items) if (isInHiddenFolder(item.bucketName, hiddenFolderNames)) uris += item.uri
    return uris
}

/**
 * The picked device photos a widget may still cycle through, in their picked order. [bucketByUri]
 * maps each picked photo still on the device to its folder: one missing from it is gone, and one in a
 * hidden folder is left out.
 */
internal fun visibleSelectedUris(
    selectedUris: List<String>,
    bucketByUri: Map<String, String?>,
    hiddenFolderNames: Set<String>,
): List<String> = selectedUris.filter {
    it in bucketByUri && !isInHiddenFolder(bucketByUri[it], hiddenFolderNames)
}

/** Up to [limit] indices into a list of [size], from [start] on and wrapping round, each at most once. */
internal fun windowIndices(size: Int, start: Int, limit: Int): List<Int> {
    if (size <= 0 || limit <= 0) return emptyList()
    val first = Math.floorMod(start, size)
    return List(minOf(size, limit)) { (first + it) % size }
}

/** The first of [windowIndices] whose photo [isVisible] allows, or null when none of them is. */
internal fun firstVisibleIndex(size: Int, start: Int, limit: Int, isVisible: (Int) -> Boolean): Int? =
    windowIndices(size, start, limit).firstOrNull(isVisible)

/**
 * The state with every hidden pick dropped, so a selection restored from a placed widget never draws a
 * hidden photo, album or folder in the picker, nor gets saved back. Unchanged while [hidden] is still
 * unread; the lists a pick could be drawn from stay empty until it is.
 */
internal fun WidgetConfigUiState.withoutHidden(
    hidden: WidgetHiddenSets?,
    hiddenDeviceUris: Set<String>,
): WidgetConfigUiState {
    if (hidden == null) return this
    return copy(
        selectedLinkIds = visibleCloudLinkIds(selectedLinkIds, hidden),
        selectedUris = if (hiddenDeviceUris.isEmpty()) selectedUris
            else selectedUris.filterNot { it in hiddenDeviceUris },
        selectedCloudAlbumLinkId = selectedCloudAlbumLinkId?.takeUnless { it in hidden.albumIds },
        selectedAlbum = selectedAlbum?.takeUnless { isInHiddenFolder(it, hidden.folderNames) },
    )
}
