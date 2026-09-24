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

import androidx.datastore.preferences.core.mutablePreferencesOf
import eu.akoos.photos.data.db.entity.CloudAlbumEntity
import eu.akoos.photos.data.db.entity.PhotoPickerRow
import eu.akoos.photos.data.preferences.SettingsKeys
import eu.akoos.photos.domain.entity.LocalMediaItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins what the home-screen widget may show: every way the app hides a photo (one by one, in a hidden
 * album, in the vault, in a hidden folder) keeps it out of the picker and off the launcher. The rules
 * are plain filters over the records, so they run without a device.
 */
class WidgetHiddenFilterTest {

    private fun local(uri: String, bucket: String?) = LocalMediaItem(
        uri = uri,
        dateTaken = 100,
        displayName = uri.substringAfterLast('/'),
        mimeType = "image/jpeg",
        sizeBytes = 1_000,
        bucketName = bucket,
    )

    private fun album(linkId: String, cover: String? = null) =
        CloudAlbumEntity(linkId = linkId, name = linkId, photoCount = 1, coverLinkId = cover)

    // ── the hidden cloud set ─────────────────────────────────────────────────────────────────────

    @Test
    fun `the hidden cloud set is the union of all three ways a cloud photo is hidden`() {
        assertEquals(
            setOf("one", "member", "vaulted"),
            widgetHiddenCloudIds(setOf("one"), setOf("member"), setOf("vaulted")),
        )
    }

    @Test
    fun `nothing hidden leaves the hidden cloud set empty`() {
        assertTrue(widgetHiddenCloudIds(emptySet(), emptySet(), emptySet()).isEmpty())
    }

    @Test
    fun `vaulted photos are named by the settings entries and by the vault's sync rows`() {
        val entries = setOf(
            "file:///data/vault/a.jpg|linkA",
            "file:///data/vault/b.jpg|",
            "no-separator",
        )
        assertEquals(setOf("linkA", "linkRow"), vaultedCloudIds(entries, listOf("linkRow", " ")))
    }

    @Test
    fun `the hidden sets are read from the same settings the gallery's hide filters read`() {
        val prefs = mutablePreferencesOf(
            SettingsKeys.HIDDEN_CLOUD_PHOTO_IDS to setOf("one"),
            SettingsKeys.HIDDEN_URI_CLOUD_ID_MAP to setOf("file:///vault/x.jpg|vaulted"),
            SettingsKeys.HIDDEN_ALBUM_IDS to setOf("albumH"),
            SettingsKeys.HIDDEN_FOLDER_NAMES to setOf("Private"),
        )
        val sets = widgetHiddenSets(prefs, hiddenAlbumMembers = setOf("member"), vaultedRowCloudIds = listOf("row"))
        assertEquals(setOf("one", "vaulted", "member", "row"), sets.cloudIds)
        assertEquals(setOf("albumH"), sets.albumIds)
        assertEquals(setOf("Private"), sets.folderNames)
    }

    @Test
    fun `no hidden settings at all read as nothing hidden`() {
        assertEquals(WidgetHiddenSets(), widgetHiddenSets(mutablePreferencesOf(), emptySet(), emptyList()))
    }

    // ── cloud modes ──────────────────────────────────────────────────────────────────────────────

    @Test
    fun `only the cloud modes need an account`() {
        assertTrue(WidgetMode.CLOUD_SELECTED.isCloud)
        assertTrue(WidgetMode.CLOUD_ALBUM.isCloud)
        assertFalse(WidgetMode.ALL_PHOTOS.isCloud)
        assertFalse(WidgetMode.SELECTED.isCloud)
        assertFalse(WidgetMode.ALBUM.isCloud)
    }

    @Test
    fun `a picked cloud photo that is hidden is never drawn, the rest keep their order`() {
        val hidden = WidgetHiddenSets(cloudIds = setOf("b"))
        assertEquals(listOf("a", "c"), visibleCloudLinkIds(listOf("a", "b", "c"), hidden))
    }

    @Test
    fun `a followed album that is hidden draws nothing`() {
        val hidden = WidgetHiddenSets(albumIds = setOf("album"))
        assertTrue(followedAlbumLinkIds("album", listOf("a", "b"), hidden).isEmpty())
    }

    @Test
    fun `a followed album drops its hidden members only`() {
        val hidden = WidgetHiddenSets(cloudIds = setOf("b"))
        assertEquals(listOf("a", "c"), followedAlbumLinkIds("album", listOf("a", "b", "c"), hidden))
    }

    @Test
    fun `no followed album draws nothing`() {
        assertTrue(followedAlbumLinkIds(null, listOf("a"), WidgetHiddenSets()).isEmpty())
    }

    // ── the picker's cloud lists ─────────────────────────────────────────────────────────────────

    @Test
    fun `the picker never offers a hidden cloud photo`() {
        val rows = listOf(PhotoPickerRow("a", null), PhotoPickerRow("b", "file:///b"), PhotoPickerRow("c", null))
        val visible = visiblePickerRows(rows, WidgetHiddenSets(cloudIds = setOf("b")))
        assertEquals(listOf("a", "c"), visible.map { it.linkId })
    }

    @Test
    fun `the picker never offers a hidden album`() {
        val visible = visibleCloudAlbums(listOf(album("open"), album("hidden")), WidgetHiddenSets(albumIds = setOf("hidden")))
        assertEquals(listOf("open"), visible.map { it.linkId })
    }

    @Test
    fun `a hidden photo is never drawn as an album cover`() {
        val visible = visibleCloudAlbums(
            listOf(album("a", cover = "hiddenPhoto"), album("b", cover = "shownPhoto")),
            WidgetHiddenSets(cloudIds = setOf("hiddenPhoto")),
        )
        assertNull(visible.single { it.linkId == "a" }.coverLinkId)
        assertEquals("shownPhoto", visible.single { it.linkId == "b" }.coverLinkId)
    }

    // ── device photos ────────────────────────────────────────────────────────────────────────────

    @Test
    fun `a photo is in a hidden folder only when its bucket is one`() {
        assertTrue(isInHiddenFolder("Private", setOf("Private")))
        assertFalse(isInHiddenFolder("Camera", setOf("Private")))
        assertFalse(isInHiddenFolder(null, setOf("Private")))
    }

    @Test
    fun `a device photo paired to a hidden cloud copy is a hidden twin`() {
        val pairs = sequenceOf(
            "content://media/external/images/media/1" to "memberOfHiddenAlbum",
            "content://media/external/images/media/2" to "visible",
            "content://media/external/images/media/3" to null,
        )
        assertEquals(
            setOf("content://media/external/images/media/1"),
            hiddenTwinUris(pairs, setOf("memberOfHiddenAlbum")),
        )
    }

    @Test
    fun `nothing hidden in the cloud means no hidden twins`() {
        assertTrue(hiddenTwinUris(sequenceOf("uri" to "link"), emptySet()).isEmpty())
    }

    @Test
    fun `the picker leaves out hidden-folder photos and hidden twins`() {
        val items = listOf(local("u1", "Camera"), local("u2", "Private"), local("u3", null))
        assertEquals(setOf("u2", "twin"), hiddenDeviceUris(items, setOf("Private"), setOf("twin")))
        assertEquals(setOf("twin"), hiddenDeviceUris(items, emptySet(), setOf("twin")))
    }

    @Test
    fun `a picked device photo in a hidden folder or no longer on the device is not drawn`() {
        val buckets = mapOf("u1" to "Camera", "u2" to "Private", "u4" to null)
        assertEquals(
            listOf("u1", "u4"),
            visibleSelectedUris(listOf("u1", "u2", "u3", "u4"), buckets, setOf("Private")),
        )
    }

    // ── picking the next photo past hidden twins ─────────────────────────────────────────────────

    @Test
    fun `the window starts at the due photo and wraps round`() {
        assertEquals(listOf(3, 4, 0, 1), windowIndices(size = 5, start = 3, limit = 4))
        assertEquals(listOf(1, 2, 0), windowIndices(size = 3, start = 1, limit = 32))
        assertTrue(windowIndices(size = 0, start = 0, limit = 32).isEmpty())
    }

    @Test
    fun `the due photo is drawn when it is visible`() {
        assertEquals(2, firstVisibleIndex(size = 5, start = 2, limit = 32) { true })
    }

    @Test
    fun `a hidden twin is passed over for the next photo along, wrapping round`() {
        val hidden = setOf(3, 4)
        assertEquals(0, firstVisibleIndex(size = 5, start = 3, limit = 32) { it !in hidden })
    }

    @Test
    fun `nothing visible within the window draws nothing`() {
        assertNull(firstVisibleIndex(size = 5, start = 0, limit = 3) { it >= 3 })
        assertNull(firstVisibleIndex(size = 0, start = 0, limit = 32) { true })
    }

    // ── a selection restored from a placed widget ────────────────────────────────────────────────

    @Test
    fun `a restored selection drops every hidden pick`() {
        val state = WidgetConfigUiState(
            selectedLinkIds = listOf("a", "hiddenPhoto"),
            selectedUris = listOf("u1", "hiddenUri"),
            selectedCloudAlbumLinkId = "hiddenAlbum",
            selectedAlbum = "Private",
        )
        val hidden = WidgetHiddenSets(
            cloudIds = setOf("hiddenPhoto"),
            albumIds = setOf("hiddenAlbum"),
            folderNames = setOf("Private"),
        )
        val pruned = state.withoutHidden(hidden, hiddenDeviceUris = setOf("hiddenUri"))
        assertEquals(listOf("a"), pruned.selectedLinkIds)
        assertEquals(listOf("u1"), pruned.selectedUris)
        assertNull(pruned.selectedCloudAlbumLinkId)
        assertNull(pruned.selectedAlbum)
    }

    @Test
    fun `visible picks are kept as they are`() {
        val state = WidgetConfigUiState(
            selectedLinkIds = listOf("a"),
            selectedUris = listOf("u1"),
            selectedCloudAlbumLinkId = "album",
            selectedAlbum = "Camera",
        )
        assertEquals(state, state.withoutHidden(WidgetHiddenSets(folderNames = setOf("Private")), emptySet()))
    }

    @Test
    fun `picks are left alone until the hidden records have been read`() {
        val state = WidgetConfigUiState(selectedLinkIds = listOf("a"))
        assertSame(state, state.withoutHidden(null, setOf("a")))
    }
}
