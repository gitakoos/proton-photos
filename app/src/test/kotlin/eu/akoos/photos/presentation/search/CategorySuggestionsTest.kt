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

package eu.akoos.photos.presentation.search

import eu.akoos.photos.presentation.gallery.GalleryFilter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CategorySuggestionsTest {

    private fun folded(vararg names: Pair<GalleryFilter, String>) =
        names.associate { (filter, name) -> filter to SearchFilter.fold(name) }

    private val english = folded(
        GalleryFilter.Favorites to "Favorites",
        GalleryFilter.Screenshots to "Screenshots",
        GalleryFilter.Videos to "Videos",
        GalleryFilter.LivePhotos to "Live Photos",
        GalleryFilter.Selfies to "Selfies",
        GalleryFilter.Portraits to "Portraits",
        GalleryFilter.Bursts to "Bursts",
        GalleryFilter.Panoramas to "Panoramas",
        GalleryFilter.Raw to "RAW",
        GalleryFilter.Offline to "Offline",
    )

    private val hungarian = folded(
        GalleryFilter.Screenshots to "Képernyőképek",
        GalleryFilter.Videos to "Videók",
        GalleryFilter.LivePhotos to "Live fotók",
        GalleryFilter.Selfies to "Szelfik",
    )

    private fun match(query: String, names: Map<GalleryFilter, String> = english, selected: GalleryFilter = GalleryFilter.All) =
        CategorySuggestions.match(query, names, selected)

    private fun hit(filter: GalleryFilter, consumed: Int = 1) = CategorySuggestion(filter, consumed)

    @Test
    fun `the whole name selects the category`() {
        assertEquals(hit(GalleryFilter.Screenshots), match("screenshots"))
        assertEquals(hit(GalleryFilter.Videos), match("videos"))
        assertEquals(hit(GalleryFilter.Raw), match("raw"))
    }

    @Test
    fun `the singular and the plural both select it, one trailing s apart`() {
        assertEquals(hit(GalleryFilter.Screenshots), match("screenshot"))
        assertEquals(hit(GalleryFilter.Selfies), match("selfie"))
        assertEquals(hit(GalleryFilter.Raw), match("raws"))
        assertNull(match("screenshotsx"))
        assertNull(match("screenshotx"))
    }

    @Test
    fun `only the end of the query is read`() {
        assertEquals(hit(GalleryFilter.Videos), match("beach videos"))
        assertNull(match("videos beach"))
    }

    @Test
    fun `case and accents are folded`() {
        assertEquals(hit(GalleryFilter.Screenshots), match("SCREENSHOTS"))
        assertEquals(hit(GalleryFilter.Screenshots), match("kepernyokepek", hungarian))
        assertEquals(hit(GalleryFilter.Videos), match("Videók", hungarian))
        assertEquals(hit(GalleryFilter.Selfies), match("SZELFIK", hungarian))
    }

    @Test
    fun `a partial word selects nothing`() {
        assertNull(match("scre"))
        assertNull(match("vid"))
        assertNull(match("screens"))
        assertNull(match("kepernyokep", hungarian))
    }

    @Test
    fun `a blank or unrelated query selects nothing`() {
        assertNull(match(""))
        assertNull(match("   "))
        assertNull(match("airsoft"))
        assertNull(match("rawhide"))
    }

    @Test
    fun `a two-word name needs both words and takes them both`() {
        assertEquals(hit(GalleryFilter.LivePhotos, 2), match("live photos"))
        assertEquals(hit(GalleryFilter.LivePhotos, 2), match("beach live photo"))
        assertEquals(hit(GalleryFilter.LivePhotos, 2), match("live fotok", hungarian))
        assertEquals(
            hit(GalleryFilter.LivePhotos, 2),
            match("live fotos", folded(GalleryFilter.LivePhotos to "Live-Fotos")),
        )
        assertNull(match("live"))
        assertNull(match("photos"))
        assertNull(match("live pho"))
    }

    @Test
    fun `the longer phrase wins`() {
        val names = folded(GalleryFilter.Portraits to "Photos", GalleryFilter.LivePhotos to "Live Photos")
        assertEquals(hit(GalleryFilter.LivePhotos, 2), match("live photos", names))
        assertEquals(hit(GalleryFilter.Portraits, 1), match("beach photos", names))
    }

    @Test
    fun `the chip already picked and the All chip are never selected`() {
        assertNull(match("screenshots", selected = GalleryFilter.Screenshots))
        assertEquals(hit(GalleryFilter.Videos), match("videos", selected = GalleryFilter.Screenshots))
        assertNull(match("all", folded(GalleryFilter.All to "All")))
    }

    @Test
    fun `a chip hidden on the rail is not selected`() {
        val visible = english.filterKeys { it != GalleryFilter.Videos }
        assertNull(match("videos", visible))
    }

    @Test
    fun `selecting drops the words that spelled it`() {
        assertEquals("beach", SearchFilter.dropLastWords(" beach  live photos ", 2))
        assertEquals("", SearchFilter.dropLastWords("screenshots", 1))
    }
}
