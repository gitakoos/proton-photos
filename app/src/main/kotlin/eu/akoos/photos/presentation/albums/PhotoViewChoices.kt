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
 */

package eu.akoos.photos.presentation.albums

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import eu.akoos.photos.data.preferences.SettingsKeys
import eu.akoos.photos.data.preferences.settingsDataStore

/**
 * A choice kept per album or folder, as the entries "ordinal:id" of a string set. Only the items whose
 * choice differs from the default have an entry, so the set grows with what the user changed and not
 * with the library.
 */
internal object PerItemChoice {

    /** The ordinal stored for [id], or null when it follows the default. */
    fun of(entries: Set<String>, id: String): Int? {
        for (entry in entries) {
            val cut = entry.indexOf(':')
            if (cut > 0 && entry.substring(cut + 1) == id) return entry.substring(0, cut).toIntOrNull()
        }
        return null
    }

    /** [entries] with [id] set to [ordinal]; choosing the default again drops its entry. */
    fun with(entries: Set<String>, id: String, ordinal: Int, defaultOrdinal: Int): Set<String> {
        val kept = entries.filterNot { entry ->
            val cut = entry.indexOf(':')
            cut > 0 && entry.substring(cut + 1) == id
        }.toMutableSet()
        if (ordinal != defaultOrdinal) kept.add("$ordinal:$id")
        return kept
    }
}

/**
 * How each album and each device folder lists its photos: the direction and the span of dates its
 * headers gather. A choice made on one belongs to that one alone; the album or folder without one
 * follows the default, which is the value the older single setting held.
 */
data class PhotoViewChoices(
    val albumSort: Set<String> = emptySet(),
    val albumSortDefault: AlbumPhotoSortMode = AlbumPhotoSortMode.Default,
    val albumGrouping: Set<String> = emptySet(),
    val albumGroupingDefault: AlbumPhotoGrouping = AlbumPhotoGrouping.Default,
    val folderSort: Set<String> = emptySet(),
    val folderSortDefault: AlbumPhotoSortMode = AlbumPhotoSortMode.Default,
    val folderGrouping: Set<String> = emptySet(),
    val folderGroupingDefault: AlbumPhotoGrouping = AlbumPhotoGrouping.Default,
) {
    fun sortOfAlbum(albumLinkId: String): AlbumPhotoSortMode =
        PerItemChoice.of(albumSort, albumLinkId)?.let { AlbumPhotoSortMode.fromOrdinal(it) } ?: albumSortDefault

    fun groupingOfAlbum(albumLinkId: String): AlbumPhotoGrouping =
        PerItemChoice.of(albumGrouping, albumLinkId)?.let { AlbumPhotoGrouping.fromOrdinal(it) } ?: albumGroupingDefault

    fun sortOfFolder(folderName: String): AlbumPhotoSortMode =
        PerItemChoice.of(folderSort, folderName)?.let { AlbumPhotoSortMode.fromOrdinal(it) } ?: folderSortDefault

    fun groupingOfFolder(folderName: String): AlbumPhotoGrouping =
        PerItemChoice.of(folderGrouping, folderName)?.let { AlbumPhotoGrouping.fromOrdinal(it) } ?: folderGroupingDefault

    companion object {
        fun from(prefs: Preferences) = PhotoViewChoices(
            albumSort = prefs[SettingsKeys.ALBUM_PHOTO_SORT_OVERRIDES] ?: emptySet(),
            albumSortDefault = AlbumPhotoSortMode.fromOrdinal(prefs[SettingsKeys.ALBUM_PHOTO_SORT_MODE]),
            albumGrouping = prefs[SettingsKeys.ALBUM_PHOTO_GROUPING_OVERRIDES] ?: emptySet(),
            albumGroupingDefault = AlbumPhotoGrouping.fromOrdinal(prefs[SettingsKeys.ALBUM_PHOTO_GROUPING]),
            folderSort = prefs[SettingsKeys.DEVICE_FOLDER_PHOTO_SORT_OVERRIDES] ?: emptySet(),
            folderSortDefault = AlbumPhotoSortMode.fromOrdinal(prefs[SettingsKeys.DEVICE_FOLDER_PHOTO_SORT_MODE]),
            folderGrouping = prefs[SettingsKeys.DEVICE_FOLDER_PHOTO_GROUPING_OVERRIDES] ?: emptySet(),
            folderGroupingDefault = AlbumPhotoGrouping.fromOrdinal(prefs[SettingsKeys.DEVICE_FOLDER_PHOTO_GROUPING]),
        )
    }
}

/** Writes one album's or one folder's choice, leaving every other album and folder as it was. */
internal object PhotoViewChoiceStore {

    suspend fun setAlbumSort(context: Context, albumLinkId: String, mode: AlbumPhotoSortMode) {
        context.settingsDataStore.edit { prefs ->
            prefs[SettingsKeys.ALBUM_PHOTO_SORT_OVERRIDES] = PerItemChoice.with(
                prefs[SettingsKeys.ALBUM_PHOTO_SORT_OVERRIDES] ?: emptySet(), albumLinkId, mode.ordinal,
                AlbumPhotoSortMode.fromOrdinal(prefs[SettingsKeys.ALBUM_PHOTO_SORT_MODE]).ordinal,
            )
        }
    }

    suspend fun setAlbumGrouping(context: Context, albumLinkId: String, grouping: AlbumPhotoGrouping) {
        context.settingsDataStore.edit { prefs ->
            prefs[SettingsKeys.ALBUM_PHOTO_GROUPING_OVERRIDES] = PerItemChoice.with(
                prefs[SettingsKeys.ALBUM_PHOTO_GROUPING_OVERRIDES] ?: emptySet(), albumLinkId, grouping.ordinal,
                AlbumPhotoGrouping.fromOrdinal(prefs[SettingsKeys.ALBUM_PHOTO_GROUPING]).ordinal,
            )
        }
    }

    suspend fun setFolderSort(context: Context, folderName: String, mode: AlbumPhotoSortMode) {
        context.settingsDataStore.edit { prefs ->
            prefs[SettingsKeys.DEVICE_FOLDER_PHOTO_SORT_OVERRIDES] = PerItemChoice.with(
                prefs[SettingsKeys.DEVICE_FOLDER_PHOTO_SORT_OVERRIDES] ?: emptySet(), folderName, mode.ordinal,
                AlbumPhotoSortMode.fromOrdinal(prefs[SettingsKeys.DEVICE_FOLDER_PHOTO_SORT_MODE]).ordinal,
            )
        }
    }

    suspend fun setFolderGrouping(context: Context, folderName: String, grouping: AlbumPhotoGrouping) {
        context.settingsDataStore.edit { prefs ->
            prefs[SettingsKeys.DEVICE_FOLDER_PHOTO_GROUPING_OVERRIDES] = PerItemChoice.with(
                prefs[SettingsKeys.DEVICE_FOLDER_PHOTO_GROUPING_OVERRIDES] ?: emptySet(), folderName, grouping.ordinal,
                AlbumPhotoGrouping.fromOrdinal(prefs[SettingsKeys.DEVICE_FOLDER_PHOTO_GROUPING]).ordinal,
            )
        }
    }
}
