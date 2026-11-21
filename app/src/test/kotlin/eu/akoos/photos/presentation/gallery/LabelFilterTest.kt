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

package eu.akoos.photos.presentation.gallery

import eu.akoos.photos.data.db.dao.LabelUsage
import eu.akoos.photos.domain.entity.CloudPhoto
import eu.akoos.photos.domain.entity.GalleryItem
import eu.akoos.photos.domain.entity.LocalMediaItem
import eu.akoos.photos.presentation.search.SearchFilter
import eu.akoos.photos.presentation.search.applyTo
import eu.akoos.photos.util.LabelNormalizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.Locale

/** The label filter both the timeline and Search narrow by: exact names, both keys of a photo, any or all. */
class LabelFilterTest {

    private val day = Calendar.getInstance().apply { clear(); set(2024, 5, 15, 12, 0, 0) }.timeInMillis

    private fun localMedia(name: String, mime: String = "image/jpeg") = LocalMediaItem(
        uri = "content://media/$name", dateTaken = day, displayName = name, mimeType = mime,
        sizeBytes = 1_000L, bucketName = null,
    )

    private fun cloudPhoto(name: String) = CloudPhoto(
        linkId = "link-$name", shareId = "share", volumeId = "vol", captureTime = day / 1000L,
        displayName = name, mimeType = "image/jpeg", sizeBytes = 1_000L, thumbnailUrl = null, revisionId = "rev",
    )

    private fun local(name: String, mime: String = "image/jpeg") = GalleryItem.LocalOnly(localMedia(name, mime))
    private fun cloud(name: String) = GalleryItem.CloudOnly(cloudPhoto(name))
    private fun synced(name: String) = GalleryItem.Synced(cloud = cloudPhoto(name), local = localMedia(name))

    private fun keys(vararg names: String) = names.mapTo(LinkedHashSet()) { LabelNormalizer.fold(it) }
    private fun names(items: List<GalleryItem>) = items.map { SearchFilter.displayNameOf(it) }
    private fun usage(vararg names: Pair<String, Int>) =
        names.map { (name, count) -> LabelUsage(LabelNormalizer.fold(name), name, count) }

    @Test
    fun `a label matches by its exact name, not as part of another`() {
        val a = local("a.jpg"); val b = local("b.jpg")
        val index = LabelFilter.index(mapOf(a.stableId to listOf("beach"), b.stableId to listOf("Be")))
        val out = LabelFilter.apply(listOf(a, b), ContentFilter().withLabels(keys("be")), index)
        assertEquals(listOf("b.jpg"), names(out))
    }

    @Test
    fun `case and accents do not matter`() {
        val a = local("a.jpg")
        val index = LabelFilter.index(mapOf(a.stableId to listOf("Nyaralás")))
        assertEquals(listOf("a.jpg"), names(LabelFilter.apply(listOf(a), ContentFilter().withLabels(keys("NYARALAS")), index)))
    }

    @Test
    fun `a backed-up photo is matched by the labels under both its keys`() {
        val s = synced("s.jpg")
        val index = LabelFilter.index(
            mapOf(s.cloud.linkId to listOf("beach"), s.local.uri to listOf("trip")),
        )
        assertTrue(LabelFilter.matches(s, keys("trip"), false, index))
        assertTrue(LabelFilter.matches(s, keys("beach"), false, index))
        // Every label, though they sit under two keys.
        assertTrue(LabelFilter.matches(s, keys("beach", "trip"), true, index))
        // Only under the device uri, from before the backup.
        val onlyUri = LabelFilter.index(mapOf(s.local.uri to listOf("trip")))
        assertTrue(LabelFilter.matches(s, keys("trip"), false, onlyUri))
    }

    @Test
    fun `any lets a photo with one of the labels through, all only one with every label`() {
        val a = local("a.jpg"); val b = local("b.jpg"); val c = cloud("c.jpg")
        val index = LabelFilter.index(
            mapOf(a.stableId to listOf("beach", "trip"), b.stableId to listOf("beach"), c.stableId to listOf("trip")),
        )
        val any = ContentFilter().withLabels(keys("beach", "trip"))
        val all = ContentFilter().withLabels(keys("beach", "trip"), matchAll = true)
        assertEquals(listOf("a.jpg", "b.jpg", "c.jpg"), names(LabelFilter.apply(listOf(a, b, c), any, index)))
        assertEquals(listOf("a.jpg"), names(LabelFilter.apply(listOf(a, b, c), all, index)))
    }

    @Test
    fun `no picked label leaves the list as it is`() {
        val items = listOf(local("a.jpg"))
        assertSame(items, LabelFilter.apply(items, ContentFilter(), emptyMap()))
        assertTrue(LabelFilter.apply(items, ContentFilter().withLabels(keys("x")), emptyMap()).isEmpty())
    }

    @Test
    fun `all is only kept with two or more labels, so clearing them clears the filter`() {
        val two = ContentFilter().withLabels(keys("a", "b"), matchAll = true)
        assertTrue(two.labelsMatchAll)
        assertFalse(two.withLabels(keys("a")).labelsMatchAll)
        assertEquals(ContentFilter(), two.withLabels(emptySet()))
    }

    @Test
    fun `labels make a filter active and the reset clears them`() {
        val filter = ContentFilter(year = 2024).withLabels(keys("beach"))
        assertFalse(filter == ContentFilter())
        assertFalse(SearchFilter.nothingAsked("", filter, GalleryFilter.All, personSelected = false))
        assertTrue(SearchFilter.nothingAsked("", ContentFilter(), GalleryFilter.All, personSelected = false))
    }

    @Test
    fun `the filter sheet keeps the picked labels when another choice changes, and its reset drops them`() {
        val picked = sheetFilter(MediaType.All, SyncStatusFilter.All, 2023, 5, null, null, null, null, keys("beach", "trip"), true)
        assertEquals(keys("beach", "trip"), picked.labelKeys)
        assertTrue(picked.labelsMatchAll)
        assertEquals(2023, picked.year)
        val reset = sheetFilter(MediaType.All, SyncStatusFilter.All, null, null, null, null, null, null, emptySet(), false)
        assertEquals(ContentFilter(), reset)
    }

    @Test
    fun `a date pill taken from the field keeps the picked labels`() {
        val filter = ContentFilter().withLabels(keys("beach", "trip"), matchAll = true)
        val term = eu.akoos.photos.presentation.search.DateSuggestions.term("june", SearchFilter.foldedMonthNames(Locale.ENGLISH))!!
        val dated = term.applyTo(filter)
        assertEquals(6, dated.month)
        assertEquals(keys("beach", "trip"), dated.labelKeys)
        assertTrue(dated.labelsMatchAll)
    }

    @Test
    fun `a label the account no longer has leaves the filter`() {
        val filter = ContentFilter().withLabels(keys("beach", "trip"), matchAll = true)
        val pruned = LabelFilter.prune(filter, usage("beach" to 2))
        assertEquals(keys("beach"), pruned.labelKeys)
        assertFalse(pruned.labelsMatchAll)
        assertSame(filter, LabelFilter.prune(filter, usage("beach" to 2, "trip" to 1)))
        assertEquals(ContentFilter(), LabelFilter.prune(ContentFilter().withLabels(keys("x")), emptyList()))
    }

    @Test
    fun `a picked label shows the photos hidden for being in an album`() {
        assertTrue(LabelFilter.bypassesAlbumHide(ContentFilter().withLabels(keys("beach"))))
        assertFalse(LabelFilter.bypassesAlbumHide(ContentFilter(year = 2024)))
    }

    @Test
    fun `the section shows the most used labels and any picked one beyond them`() {
        val labels = usage("a" to 9, "b" to 8, "c" to 7, "d" to 6, "e" to 5, "f" to 4, "g" to 3)
        assertEquals(listOf("a", "b", "c", "d", "e"), LabelFilter.shownInSection(labels, emptySet()).map { it.label })
        assertEquals(listOf("a", "b", "c", "d", "e", "g"), LabelFilter.shownInSection(labels, keys("g", "b")).map { it.label })
    }

    @Test
    fun `the pill names one label and counts several`() {
        val labels = usage("Beach" to 2, "Trip" to 1)
        assertNull(LabelFilter.summary(ContentFilter(), labels) { "$it labels" })
        assertEquals("Beach", LabelFilter.summary(ContentFilter().withLabels(keys("beach")), labels) { "$it labels" })
        assertEquals("2 labels", LabelFilter.summary(ContentFilter().withLabels(keys("beach", "trip")), labels) { "$it labels" })
    }

    @Test
    fun `a label change filters again with the new labels`() {
        val a = local("a.jpg"); val b = local("b.jpg")
        val filter = ContentFilter().withLabels(keys("beach"))
        val before = LabelFilter.index(mapOf(a.stableId to listOf("beach")))
        val after = LabelFilter.index(mapOf(a.stableId to listOf("beach"), b.stableId to listOf("Beach")))
        assertEquals(listOf("a.jpg"), names(LabelFilter.apply(listOf(a, b), filter, before)))
        assertEquals(listOf("a.jpg", "b.jpg"), names(LabelFilter.apply(listOf(a, b), filter, after)))
    }

    // ── Through Search's own narrowing ──────────────────────────────────────────────────────────────

    private val months = SearchFilter.foldedMonthNames(Locale.ENGLISH)

    private fun search(items: List<GalleryItem>, q: String, filter: ContentFilter, index: Map<String, Set<String>>) =
        names(
            SearchFilter.apply(
                items = items, q = q, filter = filter, category = GalleryFilter.All,
                foldedMonths = months, foldedCategoryNames = emptyMap(), labelIndex = index,
            ),
        )

    @Test
    fun `a label pill alone finds the labelled photos, and typed text narrows them with and and or`() {
        val beach = local("beach.jpg"); val sunset = local("sunset.jpg"); val dog = local("dog.jpg"); val other = local("beach2.jpg")
        val index = LabelFilter.index(
            mapOf(beach.stableId to listOf("trip"), sunset.stableId to listOf("trip"), dog.stableId to listOf("trip")),
        )
        val filter = ContentFilter().withLabels(keys("trip"))
        val all = listOf(beach, sunset, dog, other)
        assertEquals(listOf("beach.jpg", "sunset.jpg", "dog.jpg"), search(all, "", filter, index))
        assertEquals(listOf("beach.jpg"), search(all, "beach", filter, index))
        assertEquals(listOf("beach.jpg", "dog.jpg"), search(all, "beach, dog", filter, index))
        assertEquals(emptyList<String>(), search(all, "beach jpg dog", filter, index))
    }

    @Test
    fun `a label pill combines with the other filters`() {
        val photo = local("a.jpg"); val video = local("b.mp4", "video/mp4")
        val index = LabelFilter.index(mapOf(photo.stableId to listOf("trip"), video.stableId to listOf("trip")))
        val filter = ContentFilter(mediaType = MediaType.VideosOnly).withLabels(keys("trip"))
        assertEquals(listOf("b.mp4"), search(listOf(photo, video), "", filter, index))
        val facets = SearchFilter.applyFacets(listOf(photo, video), ContentFilter().withLabels(keys("trip")), GalleryFilter.Videos, labelIndex = index)
        assertEquals(listOf("b.mp4"), names(facets))
    }
}
