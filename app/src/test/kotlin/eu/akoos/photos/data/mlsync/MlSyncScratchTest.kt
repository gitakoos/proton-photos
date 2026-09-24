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

package eu.akoos.photos.data.mlsync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Coverage for [MlSyncScratch]: the start-up sweep takes the plaintext an earlier process left in the ML
 * sync scratch folders and spares what this process is writing, the sign-out sweep takes everything there,
 * and neither touches the rest of the cache.
 */
class MlSyncScratchTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val start = System.currentTimeMillis()

    private fun file(cache: File, path: String, modifiedAt: Long): File =
        File(cache, path).apply {
            parentFile?.mkdirs()
            writeText("plaintext")
            setLastModified(modifiedAt)
        }

    @Test
    fun `the start-up sweep takes only what an earlier process left`() {
        val cache = temp.newFolder("cache")
        val oldFaces = file(cache, "${FaceSnapshotScratch.DIRECTORY}/faces1.tmp", start - HOUR)
        val oldSearch = file(cache, "${MlSyncScratch.DIRECTORY}/search/search-import-1.tmp", start - HOUR)
        val oldTransfer = file(cache, "${MlSyncScratch.DIRECTORY}/transfer/restore-faces-1.pfpml", start - 1_000L)
        val ownFaces = file(cache, "${FaceSnapshotScratch.DIRECTORY}/faces2.tmp", start + 1_000L)
        val ownSearch = file(cache, "${MlSyncScratch.DIRECTORY}/search/search-export-2.tmp", start + 2_000L)
        val thumbnail = file(cache, "thumbnails/thumb_link.jpg", start - HOUR)

        assertEquals(3, MlSyncScratch.sweep(cache, before = start))

        listOf(oldFaces, oldSearch, oldTransfer).forEach { assertFalse(it.path, it.exists()) }
        listOf(ownFaces, ownSearch, thumbnail).forEach { assertTrue(it.path, it.exists()) }
    }

    @Test
    fun `the sign-out sweep takes every file of the scratch folders`() {
        val cache = temp.newFolder("cache")
        val faces = file(cache, "${FaceSnapshotScratch.DIRECTORY}/faces1.tmp", start + 1_000L)
        val search = file(cache, "${MlSyncScratch.DIRECTORY}/search/search-import-1.tmp", start - HOUR)
        val transfer = file(cache, "${MlSyncScratch.DIRECTORY}/transfer/save-search-1.pfpml", start)
        val thumbnail = file(cache, "thumbnails/thumb_link.jpg", start)

        assertEquals(3, MlSyncScratch.sweep(cache))

        listOf(faces, search, transfer).forEach { assertFalse(it.path, it.exists()) }
        assertTrue(thumbnail.exists())
    }

    @Test
    fun `a cache with no scratch folders sweeps nothing`() {
        val cache = temp.newFolder("cache")
        assertEquals(0, MlSyncScratch.sweep(cache, before = start))
        assertEquals(0, MlSyncScratch.sweep(cache))
    }

    private companion object {
        const val HOUR = 60 * 60 * 1000L
    }
}
