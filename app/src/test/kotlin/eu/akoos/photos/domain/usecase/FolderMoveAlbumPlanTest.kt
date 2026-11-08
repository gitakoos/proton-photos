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

import eu.akoos.photos.data.preferences.AlbumMirrorFolders
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins [folderMoveAlbumPlan], the rule for the Drive albums when photos move between device folders: a photo
 * already on Drive leaves the album of the mirrored folder it left and joins the album of the folder it went
 * into, which starts mirroring; a device-only photo and a move between unmirrored folders change nothing.
 */
class FolderMoveAlbumPlanTest {

    private val cameraMirrored = AlbumMirrorFolders(optedIn = setOf("Camera"))

    @Test
    fun `a backed-up photo leaves the mirrored source album and joins the target album`() {
        val plan = folderMoveAlbumPlan(
            rule = cameraMirrored,
            sourceBucketByUri = mapOf("u1" to "Camera"),
            onDrive = mapOf("u1" to "L1"),
            targetBucket = "Trip",
        )!!
        assertEquals(mapOf("Camera" to mapOf("u1" to "L1")), plan.removals)
        assertEquals(mapOf("u1" to "L1"), plan.joins)
        assertTrue(plan.optInTarget)
    }

    @Test
    fun `a device-only photo is left to the upload, which mirrors by the folder it is in then`() {
        assertNull(
            folderMoveAlbumPlan(
                rule = cameraMirrored,
                sourceBucketByUri = mapOf("u1" to "Camera"),
                onDrive = emptyMap(),
                targetBucket = "Trip",
            ),
        )
    }

    @Test
    fun `a move between folders that do not mirror changes no album`() {
        assertNull(
            folderMoveAlbumPlan(
                rule = AlbumMirrorFolders(),
                sourceBucketByUri = mapOf("u1" to "Downloads"),
                onDrive = mapOf("u1" to "L1"),
                targetBucket = "Trip",
            ),
        )
    }

    @Test
    fun `moving into a mirrored folder joins its album without taking anything out`() {
        val plan = folderMoveAlbumPlan(
            rule = AlbumMirrorFolders(optedIn = setOf("Trip")),
            sourceBucketByUri = mapOf("u1" to "Downloads"),
            onDrive = mapOf("u1" to "L1"),
            targetBucket = "Trip",
        )!!
        assertTrue(plan.removals.isEmpty())
        assertEquals(mapOf("u1" to "L1"), plan.joins)
        assertFalse(plan.optInTarget)
    }

    @Test
    fun `a folder of the same name is the same album, so nothing moves between albums`() {
        assertNull(
            folderMoveAlbumPlan(
                rule = AlbumMirrorFolders(optedIn = setOf("Screenshots")),
                sourceBucketByUri = mapOf("u1" to "Screenshots"),
                onDrive = mapOf("u1" to "L1"),
                targetBucket = "screenshots",
            ),
        )
    }

    @Test
    fun `photos from several folders leave only the mirrored folders' albums and all join the target`() {
        val plan = folderMoveAlbumPlan(
            rule = AlbumMirrorFolders(allByDefault = true, optedOut = setOf("Downloads")),
            sourceBucketByUri = mapOf("u1" to "Camera", "u2" to "Downloads", "u3" to "Camera", "u4" to "Camera"),
            onDrive = mapOf("u1" to "L1", "u2" to "L2", "u3" to "L3"),
            targetBucket = "Trip",
        )!!
        assertEquals(mapOf("Camera" to mapOf("u1" to "L1", "u3" to "L3")), plan.removals)
        assertEquals(mapOf("u1" to "L1", "u2" to "L2", "u3" to "L3"), plan.joins)
        assertFalse(plan.optInTarget)
    }

    @Test
    fun `a photo whose source folder could not be read takes no part`() {
        assertNull(
            folderMoveAlbumPlan(
                rule = cameraMirrored,
                sourceBucketByUri = mapOf("u1" to null),
                onDrive = mapOf("u1" to "L1"),
                targetBucket = "Trip",
            ),
        )
    }

    private fun renamePlan() = folderMoveAlbumPlan(
        rule = cameraMirrored,
        sourceBucketByUri = mapOf("u1" to "Camera", "u2" to "Camera"),
        onDrive = mapOf("u1" to "L1", "u2" to "L2"),
        targetBucket = "Holiday",
    )!!

    @Test
    fun `a folder moved whole into a new name takes its album along`() {
        assertEquals(
            "Camera",
            folderAlbumToRename(renamePlan(), mapOf("u1" to "Camera", "u2" to "Camera"), setOf("Camera"), setOf("camera"), "Holiday"),
        )
    }

    @Test
    fun `the album follows photo by photo when the folder kept photos, the name has an album, or the photos came from several folders`() {
        val sources = mapOf("u1" to "Camera", "u2" to "Camera")
        assertNull(folderAlbumToRename(renamePlan(), sources, emptySet(), setOf("camera"), "Holiday"))
        assertNull(folderAlbumToRename(renamePlan(), sources, setOf("Camera"), setOf("camera", "holiday"), "Holiday"))
        assertNull(folderAlbumToRename(renamePlan(), sources, setOf("Camera"), emptySet(), "Holiday"))
        assertNull(folderAlbumToRename(renamePlan(), mapOf("u1" to "Camera", "u2" to "WhatsApp"), setOf("Camera", "WhatsApp"), setOf("camera"), "Holiday"))
        assertNull(folderAlbumToRename(renamePlan(), mapOf("u1" to "Camera", "u2" to null), setOf("Camera"), setOf("camera"), "Holiday"))
    }

    @Test
    fun `the album tick follows a folder renamed or moved whole, read from the rule before the move`() {
        val sources = mapOf("u1" to "Camera", "u2" to "Camera")
        assertEquals("Camera", albumTickToCarry(cameraMirrored, sources, setOf("Camera"), "Holiday"))
        assertNull(albumTickToCarry(cameraMirrored, sources, emptySet(), "Holiday"))
        assertNull(albumTickToCarry(AlbumMirrorFolders(), sources, setOf("Camera"), "Holiday"))
        assertNull(albumTickToCarry(AlbumMirrorFolders(optedIn = setOf("Camera", "Holiday")), sources, setOf("Camera"), "Holiday"))
        assertNull(albumTickToCarry(cameraMirrored, mapOf("u1" to "Camera", "u2" to "Downloads"), setOf("Camera", "Downloads"), "Holiday"))
    }
}
