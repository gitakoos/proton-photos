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

package eu.akoos.photos.presentation.viewer

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import eu.akoos.photos.R
import eu.akoos.photos.domain.entity.GalleryItem
import eu.akoos.photos.domain.entity.LocalMediaItem
import eu.akoos.photos.presentation.gallery.DeviceFolderChoice
import eu.akoos.photos.presentation.gallery.NewFolderNameDialog
import eu.akoos.photos.presentation.gallery.deviceFolderChoices

/** The device folders for the details sheet's Folder box: the folder the file at [uri] is in now
 *  ([current], null while the device listing does not hold it) and every folder it can move to. */
data class DetailsFolders(val uri: String, val current: String?, val folders: List<DeviceFolderChoice>)

/** [DetailsFolders] for [uri] from the device listing, each folder covered the way the folders grid
 *  covers it: the photo pinned in [pinnedCovers] while the folder still holds it, else the newest. */
internal fun detailsFoldersFor(
    uri: String,
    items: List<LocalMediaItem>,
    pinnedCovers: Map<String, String>,
): DetailsFolders {
    val current = items.firstOrNull { it.uri == uri }?.bucketName?.takeIf { it.isNotBlank() }
    val folders = deviceFolderChoices(items).map { choice ->
        val pinned = pinnedCovers[choice.name]?.takeIf { p -> items.any { it.uri == p && it.bucketName == choice.name } }
        if (pinned != null) choice.copy(coverUri = pinned) else choice
    }
    return DetailsFolders(uri, current, folders)
}

/** The device file the details sheet's Folder box is about, or null for an item with none. */
internal fun detailsDeviceUri(item: GalleryItem?): String? = when (item) {
    is GalleryItem.LocalOnly -> item.local.uri
    is GalleryItem.Synced -> item.local.uri
    else -> null
}

/** The device folder the item's own listing row names, or null for an item with no device file. */
internal fun detailsDeviceBucket(item: GalleryItem?): String? = when (item) {
    is GalleryItem.LocalOnly -> item.local.bucketName
    is GalleryItem.Synced -> item.local.bucketName
    else -> null
}

/** Whether the Folder box takes the Albums box's place: only without an account (albums are Drive's),
 *  only for an item with a device file, and, like the Albums box, never in the trash, on a private photo
 *  or in someone else's album. */
internal fun detailsFolderBoxShown(
    item: GalleryItem?,
    isSignedIn: Boolean,
    trashMode: Boolean,
    private: Boolean,
    readOnlyAlbum: Boolean,
): Boolean = !isSignedIn && detailsDeviceUri(item) != null && !trashMode && !private && !readOnlyAlbum

/**
 * The folder the box shows for the file at [uri]: the live listing's folder once [live] answers for this
 * file, until then the item's own [bucket] with the photo itself as the cover. Null when neither names one.
 */
internal fun detailsShownFolder(uri: String, bucket: String?, live: DetailsFolders?): DeviceFolderChoice? {
    val state = live?.takeIf { it.uri == uri }
    val name = (state?.current ?: bucket)?.takeIf { it.isNotBlank() } ?: return null
    return state?.folders?.firstOrNull { it.name == name } ?: DeviceFolderChoice(name, uri, 0)
}

/** The Folder page's rows: every device folder, the one the photo is in marked and left without a tap. */
internal fun detailsFolderRows(folders: List<DeviceFolderChoice>, current: String?): List<DetailsCoverEntry> =
    folders.map { folder ->
        val isCurrent = folder.name == current
        DetailsCoverEntry(
            key = folder.name,
            name = folder.name,
            cover = folder.coverUri,
            count = folder.count,
            videoCount = folder.videoCount,
            mark = if (isCurrent) DetailsRowMark.Current else null,
            tappable = !isCurrent,
        )
    }

private fun DeviceFolderChoice.asCover() =
    DetailsCoverEntry(key = name, name = name, cover = coverUri, count = count, videoCount = videoCount)

/**
 * The main page's Folder section, the Albums box's place for a guest: the cover and name of the folder the
 * photo is in, opening that folder through [onOpenFolder]. Edit opens the Folder page through [onOpen] (no
 * Edit while it is null, where a photo cannot move), and so does the Move to folder chip when no folder is
 * known.
 */
@Composable
internal fun DetailsFolderSection(
    folder: DeviceFolderChoice?,
    onOpen: (() -> Unit)?,
    onOpenFolder: (String) -> Unit,
) {
    DetailsCoversSection(
        title = stringResource(R.string.album_card_folder_badge),
        addLabel = stringResource(R.string.move_to_folder),
        entries = listOfNotNull(folder?.asCover()),
        onOpen = onOpen,
        onOpenEntry = { onOpenFolder(it.key) },
        onAdd = { onOpen?.invoke() },
    )
}

/**
 * The Folder page: New folder, then every device folder with the photo's own marked. Picking another folder
 * moves the photo there through [onMove], a new one through [onCreate], and the page goes back.
 */
@Composable
internal fun ColumnScope.DetailsFolderPage(
    folders: List<DeviceFolderChoice>,
    current: String?,
    onBack: () -> Unit,
    onMove: (String) -> Unit,
    onCreate: (String) -> Unit,
) {
    var naming by remember { mutableStateOf(false) }
    DetailsCoversPage(
        title = stringResource(R.string.album_card_folder_badge),
        addLabel = stringResource(R.string.move_to_folder_new),
        listTitle = stringResource(R.string.device_folders_section),
        entries = detailsFolderRows(folders, current),
        busy = false,
        onBack = onBack,
        onAdd = { naming = true },
        onRow = { entry -> onMove(entry.key); onBack() },
    )
    if (naming) {
        NewFolderNameDialog(
            onConfirm = { name ->
                naming = false
                onCreate(name)
                onBack()
            },
            onDismiss = { naming = false },
        )
    }
}
