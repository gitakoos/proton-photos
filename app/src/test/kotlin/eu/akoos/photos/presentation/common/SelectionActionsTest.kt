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

package eu.akoos.photos.presentation.common

import eu.akoos.photos.domain.entity.CloudPhoto
import eu.akoos.photos.domain.entity.GalleryItem
import eu.akoos.photos.domain.entity.LocalMediaItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the contract of the canonical multi-select gating predicates in SelectionActions, which the
 * timeline header and bottom dock use to decide which action pills to show. Several screens re-derive
 * the same `is LocalOnly` / `is CloudOnly` checks inline, so the invariants pinned here are the shared
 * source of truth: the `any*` predicates read the presence of a state, the `all*` predicates are false
 * on an empty selection (never vacuously true), and `hasDownloadable` mirrors `anyCloudOnly`. No
 * Android, no Context, no Robolectric: plain JVM assertions over hand-built GalleryItem selections.
 */
class SelectionActionsTest {

    private fun local(uri: String) = LocalMediaItem(
        uri = uri,
        dateTaken = 1_000L,
        displayName = "photo.jpg",
        mimeType = "image/jpeg",
        sizeBytes = 1024L,
        bucketName = "Camera",
    )

    private fun cloud(linkId: String) = CloudPhoto(
        linkId = linkId,
        shareId = "share1",
        volumeId = "vol1",
        captureTime = 1L,
        displayName = "photo.jpg",
        mimeType = "image/jpeg",
        sizeBytes = 1024L,
        thumbnailUrl = null,
        revisionId = "rev1",
    )

    private fun localOnly(uri: String) = GalleryItem.LocalOnly(local(uri))
    private fun cloudOnly(linkId: String) = GalleryItem.CloudOnly(cloud(linkId))
    private fun synced(linkId: String, uri: String) = GalleryItem.Synced(cloud(linkId), local(uri))

    @Test
    fun `an empty selection satisfies no predicate and no all-predicate is vacuously true`() {
        val empty = emptyList<GalleryItem>()
        assertFalse(anyLocalOnly(empty))
        assertFalse(anyOnDevice(empty))
        assertFalse(anyCloudOnly(empty))
        assertFalse(anySynced(empty))
        assertFalse(hasDownloadable(empty))
        // The all-predicates must be false on an empty selection, never vacuously true off `all {}`.
        assertFalse(allDeviceOnly(empty))
        assertFalse(allLocalOnly(empty))
    }

    @Test
    fun `an all local-only selection is any-local all-local and all-on-device`() {
        val sel = listOf(localOnly("uri://a"), localOnly("uri://b"))
        assertTrue(anyLocalOnly(sel))
        assertTrue(allLocalOnly(sel))
        assertTrue(anyOnDevice(sel))
        assertTrue(allDeviceOnly(sel))
        assertFalse(anyCloudOnly(sel))
        assertFalse(hasDownloadable(sel))
        assertFalse(anySynced(sel))
    }

    @Test
    fun `an all cloud-only selection is downloadable and never on-device`() {
        val sel = listOf(cloudOnly("link-a"), cloudOnly("link-b"))
        assertTrue(anyCloudOnly(sel))
        assertTrue(hasDownloadable(sel))
        assertFalse(anyLocalOnly(sel))
        assertFalse(allLocalOnly(sel))
        assertFalse(anyOnDevice(sel))
        assertFalse(allDeviceOnly(sel))
        assertFalse(anySynced(sel))
    }

    @Test
    fun `an all-synced selection is all on-device but not all local-only`() {
        val sel = listOf(synced("link-a", "uri://a"), synced("link-b", "uri://b"))
        assertTrue(allDeviceOnly(sel))
        assertTrue(anyOnDevice(sel))
        assertTrue(anySynced(sel))
        // Synced items are on-device but not device-ONLY, so the local-only predicates stay false.
        assertFalse(allLocalOnly(sel))
        assertFalse(anyLocalOnly(sel))
        assertFalse(anyCloudOnly(sel))
        assertFalse(hasDownloadable(sel))
    }

    @Test
    fun `a mixed selection is any-local any-cloud and any-synced but not all-anything`() {
        val sel = listOf(localOnly("uri://a"), synced("link-s", "uri://s"), cloudOnly("link-c"))
        assertTrue(anyLocalOnly(sel))
        assertTrue(anyCloudOnly(sel))
        assertTrue(anySynced(sel))
        assertTrue(anyOnDevice(sel))
        assertTrue(hasDownloadable(sel))
        // A cloud-only member breaks all-on-device; a synced/cloud member breaks all-local-only.
        assertFalse(allDeviceOnly(sel))
        assertFalse(allLocalOnly(sel))
    }

    @Test
    fun `hasDownloadable equals anyCloudOnly for every selection shape`() {
        val selections = listOf(
            emptyList<GalleryItem>(),
            listOf(localOnly("uri://a")),
            listOf(cloudOnly("link-a")),
            listOf(synced("link-s", "uri://s")),
            listOf(localOnly("uri://a"), synced("link-s", "uri://s"), cloudOnly("link-c")),
        )
        for (sel in selections) {
            assertEquals(
                "hasDownloadable must mirror anyCloudOnly for $sel",
                anyCloudOnly(sel),
                hasDownloadable(sel),
            )
        }
    }

    @Test
    fun `hideTargetFor sends device-backed photos to the vault and cloud-only client-side`() {
        // Device-only and synced both have a device file to move into the Hidden vault.
        assertEquals(HideTarget.VAULT, hideTargetFor(localOnly("uri://a")))
        assertEquals(HideTarget.VAULT, hideTargetFor(synced("link-s", "uri://s")))
        // A cloud-only photo has no device file, so it is hidden client-side by linkId.
        assertEquals(HideTarget.CLIENT_SIDE, hideTargetFor(cloudOnly("link-c")))
    }

    @Test
    fun `anyHideable is false only on an empty selection`() {
        assertFalse(anyHideable(emptyList()))
        assertTrue(anyHideable(listOf(localOnly("uri://a"))))
        assertTrue(anyHideable(listOf(cloudOnly("link-c"))))
        assertTrue(anyHideable(listOf(synced("link-s", "uri://s"))))
    }

    // ── Album membership (add-to-album picker indicator) ─────────────────────────────────────────

    @Test
    fun `selectionCloudLinkIds collects cloud and synced ids and drops device-only photos`() {
        val sel = listOf(localOnly("uri://a"), synced("link-s", "uri://s"), cloudOnly("link-c"))
        assertEquals(setOf("link-s", "link-c"), selectionCloudLinkIds(sel))
        // A wholly device-only selection has no Drive linkId to match an album against.
        assertEquals(emptySet<String>(), selectionCloudLinkIds(listOf(localOnly("uri://a"))))
        assertEquals(emptySet<String>(), selectionCloudLinkIds(emptyList()))
    }

    @Test
    fun `every selected photo in the album reads as All`() {
        assertEquals(
            AlbumMembership.All,
            albumMembershipState(setOf("a", "b", "c"), setOf("a", "b", "c", "other")),
        )
    }

    @Test
    fun `a partial overlap reports the fraction in the album`() {
        assertEquals(
            AlbumMembership.Some(inAlbum = 3, total = 5),
            albumMembershipState(setOf("a", "b", "c", "d", "e"), setOf("a", "b", "c", "z")),
        )
    }

    @Test
    fun `no overlap reads as None so the common case stays uncluttered`() {
        assertEquals(AlbumMembership.None, albumMembershipState(setOf("a", "b"), setOf("y", "z")))
        assertEquals(AlbumMembership.None, albumMembershipState(setOf("a", "b"), emptySet()))
    }

    @Test
    fun `an empty selection reads as None`() {
        assertEquals(AlbumMembership.None, albumMembershipState(emptySet(), setOf("a", "b")))
        assertEquals(AlbumMembership.None, albumMembershipState(emptySet(), emptySet()))
    }

    @Test
    fun `a selection with no cloud ids reads as None rather than a zero fraction`() {
        // Device-only photos yield no linkIds, so the album cannot hold any of them. Reporting
        // "0 / 2" here would imply an add could close a gap that no action can.
        val deviceOnly = listOf(localOnly("uri://a"), localOnly("uri://b"))
        assertEquals(
            AlbumMembership.None,
            albumMembershipState(selectionCloudLinkIds(deviceOnly), setOf("a", "b")),
        )
    }

    @Test
    fun `a single selected photo already in the album reads as All not a one-of-one fraction`() {
        assertEquals(AlbumMembership.All, albumMembershipState(setOf("a"), setOf("a")))
    }

    // ── Unfiled-only filter (album picker) ──────────────────────────────────────────────────────

    @Test
    fun `a cloud photo an album holds is filed and one no album holds is unfiled`() {
        val inAnyAlbum = setOf("link-filed")
        assertFalse(isUnfiled(cloudOnly("link-filed"), inAnyAlbum))
        assertTrue(isUnfiled(cloudOnly("link-loose"), inAnyAlbum))
    }

    @Test
    fun `a synced photo follows its cloud linkId`() {
        val inAnyAlbum = setOf("link-filed")
        assertFalse(isUnfiled(synced("link-filed", "uri://a"), inAnyAlbum))
        assertTrue(isUnfiled(synced("link-loose", "uri://b"), inAnyAlbum))
    }

    @Test
    fun `a device-only photo is always unfiled`() {
        // No cloud copy exists for an album to hold, so the local uri never matches a linkId and the
        // photo must stay visible under the filter rather than being read as filed.
        assertTrue(isUnfiled(localOnly("uri://a"), emptySet()))
        assertTrue(isUnfiled(localOnly("uri://a"), setOf("link-filed")))
        // Even were a linkId to collide with the uri text, a device-only photo is unfiled outright.
        assertTrue(isUnfiled(localOnly("uri://a"), setOf("uri://a")))
    }

    @Test
    fun `no album membership at all leaves every photo unfiled`() {
        val all = listOf(localOnly("uri://a"), synced("link-s", "uri://s"), cloudOnly("link-c"))
        assertTrue(all.all { isUnfiled(it, emptySet()) })
    }

    @Test
    fun `device-only photos are excluded from the denominator`() {
        // Three cloud-backed photos plus two device-only ones: the fraction counts the three that
        // can actually be in an album, so all three present reads All rather than "3 / 5".
        val mixed = listOf(
            synced("link-a", "uri://a"),
            synced("link-b", "uri://b"),
            cloudOnly("link-c"),
            localOnly("uri://d"),
            localOnly("uri://e"),
        )
        val ids = selectionCloudLinkIds(mixed)
        assertEquals(AlbumMembership.All, albumMembershipState(ids, setOf("link-a", "link-b", "link-c")))
        // And a partial overlap reports out of 3, never out of 5.
        assertEquals(
            AlbumMembership.Some(inAlbum = 2, total = 3),
            albumMembershipState(ids, setOf("link-a", "link-b")),
        )
    }
}
