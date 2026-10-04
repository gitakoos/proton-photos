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

import eu.akoos.photos.domain.entity.CloudPhoto
import eu.akoos.photos.domain.entity.GalleryItem
import eu.akoos.photos.domain.entity.LocalMediaItem
import org.junit.Assert.assertEquals
import org.junit.Test

class LabelKeyReconcilerTest {

    private fun local(uri: String) = LocalMediaItem(uri, 1_000L, "a.jpg", "image/jpeg", 10L, "Camera")

    private fun cloud(linkId: String) =
        CloudPhoto(linkId, "share", "vol", 1L, "a.jpg", "image/jpeg", 10L, null, "rev")

    private val localOnly = GalleryItem.LocalOnly(local(URI))
    private val cloudOnly = GalleryItem.CloudOnly(cloud(LINK))
    private val synced = GalleryItem.Synced(cloud(LINK), local(URI))

    @Test
    fun `keys are the stable id, plus the device uri for a synced item`() {
        assertEquals(listOf(URI), LabelKeyReconciler.keysFor(localOnly))
        assertEquals(listOf(LINK), LabelKeyReconciler.keysFor(cloudOnly))
        assertEquals(listOf(LINK, URI), LabelKeyReconciler.keysFor(synced))
    }

    @Test
    fun `a synced item reads labels under both keys, linkId first, deduped and capped`() {
        val byKey = mapOf(LINK to listOf("Beach", "a", "b", "c"), URI to listOf("beach", "x", "y"))
        assertEquals(listOf("Beach", "a", "b", "c", "x"), LabelKeyReconciler.labelsFor(synced, byKey))
    }

    @Test
    fun `a synced item with labels only under its uri still shows them`() {
        assertEquals(listOf("x"), LabelKeyReconciler.labelsFor(synced, mapOf(URI to listOf("x"))))
    }

    @Test
    fun `local and cloud items read only their own key`() {
        val byKey = mapOf(LINK to listOf("cloud"), URI to listOf("device"))
        assertEquals(listOf("device"), LabelKeyReconciler.labelsFor(localOnly, byKey))
        assertEquals(listOf("cloud"), LabelKeyReconciler.labelsFor(cloudOnly, byKey))
        assertEquals(emptyList<String>(), LabelKeyReconciler.labelsFor(cloudOnly, emptyMap()))
    }

    @Test
    fun `pending moves name only synced items whose uri still holds labels`() {
        val other = GalleryItem.Synced(cloud("link-2"), local("content://media/2"))
        val moves = LabelKeyReconciler.pendingMoves(listOf(localOnly, cloudOnly, synced, other), setOf(URI, LINK))
        assertEquals(listOf(URI to LINK), moves)
    }

    private companion object {
        const val URI = "content://media/external/images/media/1"
        const val LINK = "link-1"
    }
}
