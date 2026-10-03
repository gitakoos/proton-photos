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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PerItemChoiceTest {

    @Test
    fun `an item with no entry follows the default`() {
        assertNull(PerItemChoice.of(emptySet(), "a"))
        assertNull(PerItemChoice.of(setOf("1:b"), "a"))
    }

    @Test
    fun `a choice is read back for its own item only`() {
        val entries = setOf("1:a", "0:b")
        assertEquals(1, PerItemChoice.of(entries, "a"))
        assertEquals(0, PerItemChoice.of(entries, "b"))
    }

    @Test
    fun `an id that holds colons is read whole`() {
        val entries = PerItemChoice.with(emptySet(), "weird:id=:x", 2, 0)
        assertEquals(2, PerItemChoice.of(entries, "weird:id=:x"))
        assertNull(PerItemChoice.of(entries, "x"))
    }

    @Test
    fun `a new choice replaces the old one and leaves the others alone`() {
        val entries = PerItemChoice.with(setOf("1:a", "2:b"), "a", 0, 9)
        assertEquals(0, PerItemChoice.of(entries, "a"))
        assertEquals(2, PerItemChoice.of(entries, "b"))
        assertEquals(2, entries.size)
    }

    @Test
    fun `choosing the default again drops the entry`() {
        val entries = PerItemChoice.with(setOf("1:a", "2:b"), "a", 0, 0)
        assertNull(PerItemChoice.of(entries, "a"))
        assertEquals(setOf("2:b"), entries)
    }

    @Test
    fun `the view choices keep each album and each folder apart`() {
        val choices = PhotoViewChoices(
            albumSort = setOf("1:album-1"),
            folderSort = setOf("1:Camera"),
        )
        assertEquals(AlbumPhotoSortMode.OldestFirst, choices.sortOfAlbum("album-1"))
        assertEquals(AlbumPhotoSortMode.NewestFirst, choices.sortOfAlbum("album-2"))
        assertEquals(AlbumPhotoSortMode.OldestFirst, choices.sortOfFolder("Camera"))
        assertEquals(AlbumPhotoSortMode.NewestFirst, choices.sortOfFolder("Screenshots"))
    }

    @Test
    fun `the default is what an album with no choice of its own falls back to`() {
        val choices = PhotoViewChoices(albumSortDefault = AlbumPhotoSortMode.OldestFirst, albumSort = setOf("0:own"))
        assertEquals(AlbumPhotoSortMode.OldestFirst, choices.sortOfAlbum("other"))
        assertEquals(AlbumPhotoSortMode.NewestFirst, choices.sortOfAlbum("own"))
    }
}
