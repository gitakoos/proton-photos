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

package eu.akoos.photos.presentation.editor

import eu.akoos.photos.domain.repository.DrivePhotoRepository.AddPhotosToAlbumResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pins the registry an open viewer reads editor copies from, and when an album add counts as done. */
class EditorSavedCopiesTest {

    @Test
    fun `a recorded copy is listed with the next seq`() {
        val before = EditorSavedCopies.latestSeq()

        EditorSavedCopies.record(setOf("src-1"), "copy-1", copyIsLocal = false)

        val last = EditorSavedCopies.all.value.last()
        assertEquals(before + 1, last.seq)
        assertEquals(setOf("src-1"), last.sourceKeys)
        assertEquals("copy-1", last.copyKey)
    }

    @Test
    fun `an overwrite in place is not recorded as a copy`() {
        val before = EditorSavedCopies.latestSeq()

        EditorSavedCopies.record(
            setOf("content://media/external/images/media/5"),
            "content://media/external/images/media/5",
            copyIsLocal = true,
        )

        assertEquals(before, EditorSavedCopies.latestSeq())
    }

    @Test
    fun `a copy without a source key is not recorded`() {
        val before = EditorSavedCopies.latestSeq()

        EditorSavedCopies.record(setOf(""), "copy-2", copyIsLocal = false)

        assertEquals(before, EditorSavedCopies.latestSeq())
    }

    @Test
    fun `an album add that returned without the link failing succeeded`() {
        assertTrue(albumAddSucceeded(Result.success(AddPhotosToAlbumResult(listOf("n"), emptyList())), "n"))
    }

    @Test
    fun `an album add that reports the link as failed did not succeed`() {
        assertFalse(albumAddSucceeded(Result.success(AddPhotosToAlbumResult(emptyList(), listOf("n"))), "n"))
    }

    @Test
    fun `an album add that threw did not succeed`() {
        assertFalse(albumAddSucceeded(Result.failure(IllegalStateException("offline")), "n"))
    }
}
