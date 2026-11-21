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

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import eu.akoos.photos.R
import eu.akoos.photos.domain.entity.Album
import eu.akoos.photos.presentation.common.photoVideoCountText
import eu.akoos.photos.presentation.gallery.InAlbumMark
import eu.akoos.photos.presentation.theme.Accent
import eu.akoos.photos.presentation.theme.Bg0
import eu.akoos.photos.presentation.theme.ErrorColor
import eu.akoos.photos.presentation.theme.FgMute
import eu.akoos.photos.presentation.theme.FgPrimary
import eu.akoos.photos.presentation.theme.Line2

/** One album the photo is in. [pending] marks a device photo that joins it once it is backed up. */
internal data class DetailsAlbumEntry(val album: Album, val pending: Boolean)


/** How many album covers the main page's Albums box shows before the rest becomes a count. */
internal const val DETAILS_ALBUM_COVERS = 3

/**
 * The albums a photo is in ([albumIds]) or queued to join ([queuedIds]), by name. An album known only by id
 * (not in [albums]) is left out, since there is nothing to draw for it.
 */
internal fun detailsMemberAlbums(albums: List<Album>, albumIds: Set<String>, queuedIds: Set<String>): List<DetailsAlbumEntry> =
    albums.distinctBy { it.linkId }
        .filter { it.linkId in albumIds || it.linkId in queuedIds }
        .map { DetailsAlbumEntry(it, pending = it.linkId !in albumIds) }
        .sortedBy { it.album.name.lowercase() }

/** The covers the Albums box draws, at most [max], and how many albums are left over for the count tile. */
internal fun <T> coversAndRest(list: List<T>, max: Int = DETAILS_ALBUM_COVERS): Pair<List<T>, Int> =
    list.take(max) to (list.size - max).coerceAtLeast(0)

/**
 * One cover the Albums or the Folder box draws and one row of its page: [cover] is the image model (null
 * draws the plain tile), [pending] dims the cover and names the wait, [mark] is the row's button, and
 * [tappable] says whether a tap on the row does anything.
 */
internal data class DetailsCoverEntry(
    val key: String,
    val name: String,
    val cover: Any?,
    val count: Int,
    val videoCount: Int = 0,
    val pending: Boolean = false,
    val mark: DetailsRowMark? = null,
    val tappable: Boolean = mark == DetailsRowMark.Add || mark == DetailsRowMark.Remove,
)

internal enum class DetailsRowMark { Add, Remove, Current }

private fun DetailsAlbumEntry.asCover() = DetailsCoverEntry(
    key = album.linkId,
    name = album.name,
    cover = album.coverThumbnailUrl,
    count = album.photoCount,
    pending = pending,
    // Taking a photo out of an album takes the same right as adding to it.
    mark = if (album.canAddPhotos) DetailsRowMark.Remove else null,
)

/**
 * The main page's Albums section: up to three covers of the albums the photo is in and a count of the rest,
 * or the dashed Add to album chip when it is in none. A cover opens its album through [onOpenAlbum], Edit and
 * the count open the Albums page through [onOpen], and the chip goes straight to the album picker through
 * [onAdd].
 */
@Composable
internal fun DetailsAlbumsSection(
    member: List<DetailsAlbumEntry>,
    onOpen: () -> Unit,
    onOpenAlbum: (Album) -> Unit,
    onAdd: () -> Unit,
) {
    DetailsCoversSection(
        title = stringResource(R.string.gallery_tab_albums),
        addLabel = stringResource(R.string.gallery_add_to_album),
        entries = member.map { it.asCover() },
        onOpen = onOpen,
        onOpenEntry = { cover -> member.firstOrNull { it.album.linkId == cover.key }?.let { onOpenAlbum(it.album) } },
        onAdd = onAdd,
    )
}

/**
 * A main-page section of covers under [title]: up to three and a count of the rest, or the dashed [addLabel]
 * chip when there are none. A cover opens through [onOpenEntry], Edit and the count open the section's page
 * through [onOpen] (no Edit while it is null), and the chip runs [onAdd].
 */
@Composable
internal fun DetailsCoversSection(
    title: String,
    addLabel: String,
    entries: List<DetailsCoverEntry>,
    onOpen: (() -> Unit)?,
    onOpenEntry: (DetailsCoverEntry) -> Unit,
    onAdd: () -> Unit,
) {
    Column {
        DetailsSectionHeader(title, onEdit = onOpen)
        if (entries.isEmpty()) {
            Box(Modifier.padding(top = 6.dp)) {
                DetailsAddChip(addLabel, onClick = onAdd)
            }
            return@Column
        }
        val (covers, rest) = coversAndRest(entries)
        Row(modifier = Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            covers.forEach { entry ->
                AlbumTile(name = entry.name, onClick = { onOpenEntry(entry) }) {
                    AlbumCover(entry.cover, size = 64, modifier = if (entry.pending) Modifier.alpha(0.55f) else Modifier)
                }
            }
            if (rest > 0 && onOpen != null) {
                AlbumTile(name = stringResource(R.string.viewer_menu_more), onClick = onOpen) {
                    Box(
                        modifier = Modifier
                            .size(64.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Bg0)
                            .border(0.5.dp, Line2, RoundedCornerShape(12.dp)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("+$rest", color = Accent, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
        if (entries.any { it.pending }) {
            Text(
                stringResource(R.string.details_albums_pending),
                color = FgMute, fontSize = 11.5.sp, modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

/** A cover over its name, both tapping through to [onClick]. */
@Composable
private fun AlbumTile(name: String, onClick: () -> Unit, cover: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .width(64.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick),
    ) {
        cover()
        Text(
            name,
            color = FgPrimary, fontSize = 11.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

/** The cover image, or a plain tile while there is none. */
@Composable
private fun AlbumCover(model: Any?, size: Int, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(if (size >= 60) 12.dp else 10.dp)
    val base = modifier.size(size.dp).clip(shape).background(Bg0)
    if (model != null) {
        AsyncImage(
            model = model,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = base,
        )
    } else {
        Box(base)
    }
}

/**
 * The Albums page: Add to album, which opens the album picker through [onAdd], then the albums the photo is
 * in, each with a remove button where this user may take it out. While a removal runs, the buttons wait and
 * the header shows a spinner.
 */
@Composable
internal fun ColumnScope.DetailsAlbumsPage(
    member: List<DetailsAlbumEntry>,
    busy: Boolean,
    onBack: () -> Unit,
    onRemove: (Album) -> Unit,
    onAdd: () -> Unit,
) {
    DetailsCoversPage(
        title = stringResource(R.string.gallery_tab_albums),
        addLabel = stringResource(R.string.gallery_add_to_album),
        listTitle = stringResource(R.string.details_albums_in),
        entries = member.map { it.asCover() },
        busy = busy,
        onBack = onBack,
        onAdd = onAdd,
        onRow = { cover -> member.firstOrNull { it.album.linkId == cover.key }?.let { onRemove(it.album) } },
    )
}

/**
 * A section's page: the dashed [addLabel] row, which runs [onAdd], then [entries] under [listTitle], a tap on
 * a tappable row running [onRow]. The page itself does not scroll: the list takes the room left on the page
 * and scrolls inside it, and a scroll that reaches its top stays there instead of pulling the sheet down.
 * While [busy], the rows wait and the header shows a spinner.
 */
@Composable
internal fun ColumnScope.DetailsCoversPage(
    title: String,
    addLabel: String,
    listTitle: String,
    entries: List<DetailsCoverEntry>,
    busy: Boolean,
    onBack: () -> Unit,
    onAdd: () -> Unit,
    onRow: (DetailsCoverEntry) -> Unit,
) {
    DetailsPageHeader(title, onBack = onBack, busy = busy)
    DetailsCard {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = !busy, onClick = onAdd)
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .dashedOutline(Accent.copy(alpha = 0.7f), 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Default.Add, contentDescription = null, tint = Accent, modifier = Modifier.size(20.dp))
            }
            Text(addLabel, color = Accent, fontSize = 15.sp, fontWeight = FontWeight.Medium)
        }
    }
    if (entries.isEmpty()) return
    DetailsListTitle(listTitle)
    Box(Modifier.weight(1f, fill = false)) {
        DetailsCard {
            Column(modifier = Modifier.nestedScroll(KeepScrollInside).verticalScroll(rememberScrollState())) {
                entries.forEachIndexed { index, entry ->
                    if (index > 0) DetailsDivider()
                    AlbumRow(
                        entry = entry,
                        subtitle = if (entry.pending) stringResource(R.string.details_albums_pending) else null,
                        enabled = !busy,
                        onClick = { onRow(entry) },
                    )
                }
            }
        }
    }
}

/**
 * Keeps a list's scroll inside it. What the list does not use at its ends would otherwise go on to the bottom
 * sheet around it, and a scroll back past the list's top would pull the sheet down and close it.
 */
private val KeepScrollInside = object : NestedScrollConnection {
    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset = available
    override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity = available
}

/** A small capitals title over one of the page's cards. */
@Composable
private fun DetailsListTitle(text: String) {
    Text(
        text.uppercase(),
        color = FgMute, fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.8.sp,
        modifier = Modifier.padding(top = 4.dp),
    )
}

/** One row on the page: its cover, name and photo count (or [subtitle]), and its button. */
@Composable
private fun AlbumRow(
    entry: DetailsCoverEntry,
    subtitle: String?,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled && entry.tappable, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        AlbumCover(entry.cover, size = 44)
        Column(modifier = Modifier.weight(1f)) {
            Text(entry.name, color = FgPrimary, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                subtitle ?: photoVideoCountText(entry.count - entry.videoCount, entry.videoCount),
                color = FgMute, fontSize = 12.sp,
            )
        }
        when (entry.mark) {
            DetailsRowMark.Remove -> RoundButton(
                icon = Icons.Default.Remove,
                description = stringResource(R.string.album_remove_from_album),
                tint = ErrorColor,
                enabled = enabled,
            )
            DetailsRowMark.Add -> RoundButton(
                icon = Icons.Default.Add,
                description = stringResource(R.string.gallery_add_to_album),
                tint = Accent,
                enabled = enabled,
            )
            DetailsRowMark.Current -> InAlbumMark(description = null)
            null -> Unit
        }
    }
}

@Composable
private fun RoundButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    tint: androidx.compose.ui.graphics.Color,
    enabled: Boolean,
) {
    Box(
        modifier = Modifier
            .size(32.dp)
            .clip(CircleShape)
            .background(tint.copy(alpha = if (enabled) 0.16f else 0.08f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = description, tint = if (enabled) tint else FgMute, modifier = Modifier.size(18.dp))
    }
}
