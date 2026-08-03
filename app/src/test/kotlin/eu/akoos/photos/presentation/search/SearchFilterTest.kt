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

package eu.akoos.photos.presentation.search

import eu.akoos.photos.domain.entity.CloudPhoto
import eu.akoos.photos.domain.entity.GalleryItem
import eu.akoos.photos.domain.entity.LocalMediaItem
import eu.akoos.photos.presentation.gallery.ContentFilter
import eu.akoos.photos.presentation.gallery.GalleryFilter
import eu.akoos.photos.presentation.gallery.MediaType
import eu.akoos.photos.presentation.gallery.SyncStatusFilter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.Locale

/**
 * Everything the Search screen narrows the library down to, pinned as list-in / list-out.
 *
 * The screen is the only place a photo can be found by something other than scrolling, so each of
 * these decisions is the difference between a photo existing and not. The folding matters most on a
 * Hungarian library, where every second filename carries an accent and a query typed without one
 * would otherwise find nothing. The date filter matters because it is what the "jump to month" grid
 * hands over. And the category chips matter because the same chip row drives the timeline, so a
 * disagreement between the two surfaces reads as a missing photo.
 */
class SearchFilterTest {

    // ── fixtures ────────────────────────────────────────────────────────────────────────────────

    /** Local wall-clock instants, built through Calendar so the filter's own zone is the test's. */
    private fun at(year: Int, month: Int, day: Int, hour: Int = 12): Long =
        Calendar.getInstance().apply {
            clear()
            set(year, month - 1, day, hour, 0, 0)
        }.timeInMillis

    private val juneDay = at(2024, 6, 15)
    private val julyDay = at(2024, 7, 4)
    private val lastJune = at(2023, 6, 15)

    private val englishMonths = SearchFilter.foldedMonthNames(Locale.ENGLISH)
    private val hungarianMonths = SearchFilter.foldedMonthNames(Locale.forLanguageTag("hu"))

    /** The pre-folded category names the view model resolves from resources. */
    private val categoryNames = mapOf(
        0 to "favourites", 1 to "screenshots", 2 to "videos", 3 to "live photos",
        4 to "motion photos", 5 to "selfies", 6 to "portraits", 7 to "bursts",
        8 to "panoramas", 9 to "raw",
    )

    private fun local(
        name: String,
        mime: String = "image/jpeg",
        at: Long = juneDay,
        uri: String = "content://media/$name",
        bucket: String? = null,
        tags: Set<Int> = emptySet(),
    ) = GalleryItem.LocalOnly(
        LocalMediaItem(
            uri = uri, dateTaken = at, displayName = name, mimeType = mime,
            sizeBytes = 1_000L, bucketName = bucket, tags = tags,
        ),
    )

    private fun cloudPhoto(
        name: String,
        mime: String = "image/jpeg",
        at: Long = juneDay,
        linkId: String = "link-$name",
        tags: Set<Int> = emptySet(),
    ) = CloudPhoto(
        linkId = linkId, shareId = "share", volumeId = "vol", captureTime = at / 1000L,
        displayName = name, mimeType = mime, sizeBytes = 1_000L, thumbnailUrl = null,
        revisionId = "rev", tags = tags,
    )

    private fun cloud(
        name: String,
        mime: String = "image/jpeg",
        at: Long = juneDay,
        linkId: String = "link-$name",
        tags: Set<Int> = emptySet(),
    ) = GalleryItem.CloudOnly(cloudPhoto(name, mime, at, linkId, tags))

    private fun synced(
        name: String,
        mime: String = "image/jpeg",
        at: Long = juneDay,
        linkId: String = "link-$name",
        tags: Set<Int> = emptySet(),
    ) = GalleryItem.Synced(
        cloud = cloudPhoto(name, mime, at, linkId, tags),
        local = LocalMediaItem(
            uri = "content://media/$name", dateTaken = at, displayName = name,
            mimeType = mime, sizeBytes = 1_000L, bucketName = null,
        ),
    )

    private fun search(
        items: List<GalleryItem>,
        q: String = "",
        filter: ContentFilter = ContentFilter(),
        category: GalleryFilter = GalleryFilter.All,
        offlinePinIds: Set<String> = emptySet(),
        favoriteIds: Set<String> = emptySet(),
        months: List<String> = englishMonths,
    ): List<GalleryItem> = SearchFilter.apply(
        items = items, q = q, filter = filter, category = category, offlinePinIds = offlinePinIds,
        favoriteIds = favoriteIds, foldedMonths = months, foldedCategoryNames = categoryNames,
    )

    private fun namesFrom(items: List<GalleryItem>): List<String> = items.map { SearchFilter.displayNameOf(it) }

    // ── nothing asked for ───────────────────────────────────────────────────────────────────────

    @Test
    fun `an untouched search page shows nothing rather than the whole library`() {
        assertTrue(search(listOf(local("a.jpg"), cloud("b.jpg"))).isEmpty())
    }

    @Test
    fun `a query of nothing but spaces is the same as no query`() {
        assertTrue(search(listOf(local("a.jpg")), q = "   ").isEmpty())
    }

    @Test
    fun `a filter on its own is enough to produce results without a query`() {
        val items = listOf(local("a.jpg"), local("clip.mp4", mime = "video/mp4"))
        assertEquals(
            listOf("clip.mp4"),
            namesFrom(search(items, filter = ContentFilter(mediaType = MediaType.VideosOnly))),
        )
    }

    @Test
    fun `a category chip on its own is enough to produce results without a query`() {
        val items = listOf(local("a.jpg"), cloud("shot.png", tags = setOf(1)))
        assertEquals(listOf("shot.png"), namesFrom(search(items, category = GalleryFilter.Screenshots)))
    }

    // ── diacritic folding ───────────────────────────────────────────────────────────────────────

    @Test
    fun `an accented name is found by a query typed without the accents`() {
        val items = listOf(local("Nyári vakáció.jpg"), local("winter.jpg"))
        assertEquals(listOf("Nyári vakáció.jpg"), namesFrom(search(items, q = "nyari")))
        assertEquals(listOf("Nyári vakáció.jpg"), namesFrom(search(items, q = "vakacio")))
    }

    @Test
    fun `an unaccented name is found by a query typed with the accents`() {
        val items = listOf(local("Tukor.jpg"), local("other.jpg"))
        assertEquals(listOf("Tukor.jpg"), namesFrom(search(items, q = "tükör")))
    }

    @Test
    fun `the two Hungarian long vowels fold to their bare letters`() {
        assertEquals("oszi kepek", SearchFilter.fold("Őszi Képek"))
        assertEquals("udvozlet", SearchFilter.fold("Üdvözlet"))
        assertEquals("arvizturo tukorfurogep", SearchFilter.fold("Árvíztűrő tükörfúrógép"))
    }

    @Test
    fun `folding is case-insensitive in both directions`() {
        assertEquals(listOf("BEACH.JPG"), namesFrom(search(listOf(local("BEACH.JPG")), q = "beach")))
        assertEquals(listOf("beach.jpg"), namesFrom(search(listOf(local("beach.jpg")), q = "BEACH")))
    }

    // ── every word has to match, name or haystack ───────────────────────────────────────────────

    @Test
    fun `a multi-word query needs every word, but they may come from different places`() {
        val items = listOf(
            local("beach.mp4", mime = "video/mp4"),
            local("beach.jpg"),
            local("forest.mp4", mime = "video/mp4"),
        )
        // "beach" comes off the name, "video" off the media-type word in the haystack.
        assertEquals(listOf("beach.mp4"), namesFrom(search(items, q = "beach video")))
    }

    @Test
    fun `a word that matches nothing removes the item however well the others match`() {
        assertTrue(search(listOf(local("beach.jpg")), q = "beach mountain").isEmpty())
    }

    @Test
    fun `a query is matched anywhere in the name, not only at its start`() {
        assertEquals(listOf("IMG_20240615_beach.jpg"), namesFrom(search(listOf(local("IMG_20240615_beach.jpg")), q = "beach")))
    }

    @Test
    fun `runs of spaces in a query do not become empty words that match everything`() {
        val items = listOf(local("beach.jpg"), local("forest.jpg"))
        assertEquals(listOf("beach.jpg"), namesFrom(search(items, q = "  beach   ")))
    }

    // ── what the haystack makes findable ────────────────────────────────────────────────────────

    @Test
    fun `a photo is findable by the year it was taken`() {
        val items = listOf(local("a.jpg", at = juneDay), local("b.jpg", at = lastJune))
        assertEquals(listOf("a.jpg"), namesFrom(search(items, q = "2024")))
    }

    @Test
    fun `a photo is findable by its month name in the language the app is running in`() {
        val items = listOf(local("a.jpg", at = juneDay), local("b.jpg", at = julyDay))
        assertEquals(listOf("a.jpg"), namesFrom(search(items, q = "june")))
        assertEquals(listOf("b.jpg"), namesFrom(search(items, q = "july")))
    }

    @Test
    fun `a Hungarian month name is found with or without its accents`() {
        assertEquals("junius", hungarianMonths[5])
        val items = listOf(local("a.jpg", at = juneDay), local("b.jpg", at = julyDay))
        assertEquals(listOf("a.jpg"), namesFrom(search(items, q = "június", months = hungarianMonths)))
        assertEquals(listOf("a.jpg"), namesFrom(search(items, q = "junius", months = hungarianMonths)))
    }

    @Test
    fun `a month and a year narrow together`() {
        val items = listOf(local("a.jpg", at = juneDay), local("b.jpg", at = lastJune))
        assertEquals(listOf("a.jpg"), namesFrom(search(items, q = "june 2024")))
    }

    @Test
    fun `every video answers to the word video and every photo to the word photo`() {
        val items = listOf(local("a.jpg"), local("clip.mp4", mime = "video/mp4"))
        assertEquals(listOf("clip.mp4"), namesFrom(search(items, q = "video")))
        assertEquals(listOf("a.jpg"), namesFrom(search(items, q = "photo")))
    }

    @Test
    fun `a photo is findable by the mime subtype, which is not always its file extension`() {
        val items = listOf(local("a.jpg", mime = "image/jpeg"), local("b.png", mime = "image/png"))
        assertEquals(listOf("a.jpg"), namesFrom(search(items, q = "jpeg")))
        // "jpg" only reaches this photo through its name; the haystack carries "jpeg".
        assertEquals(listOf("a.jpg"), namesFrom(search(items, q = "jpg")))
        assertTrue(namesFrom(search(listOf(local("holiday", mime = "image/jpeg")), q = "jpg")).isEmpty())
    }

    @Test
    fun `a backed-up photo is findable by the name of a category the server gave it`() {
        val items = listOf(cloud("a.jpg", tags = setOf(8)), cloud("b.jpg"))
        assertEquals(listOf("a.jpg"), namesFrom(search(items, q = "panoramas")))
    }

    @Test
    fun `a device-only photo has no category words to be found by`() {
        // Its detected tags are not part of the haystack, only a cloud photo's server tags are.
        assertTrue(search(listOf(local("a.jpg", tags = setOf(8))), q = "panoramas").isEmpty())
    }

    // ── the media-type filter ───────────────────────────────────────────────────────────────────

    @Test
    fun `the photos filter keeps everything that is not a video`() {
        val items = listOf(local("a.jpg"), local("clip.mp4", mime = "video/mp4"), local("odd", mime = ""))
        assertEquals(
            listOf("a.jpg", "odd"),
            namesFrom(search(items, filter = ContentFilter(mediaType = MediaType.PhotosOnly))),
        )
    }

    @Test
    fun `the videos filter keeps only the video mime`() {
        val items = listOf(local("a.jpg"), local("clip.mp4", mime = "video/mp4"), cloud("c.mov", mime = "video/quicktime"))
        assertEquals(
            listOf("clip.mp4", "c.mov"),
            namesFrom(search(items, filter = ContentFilter(mediaType = MediaType.VideosOnly))),
        )
    }

    @Test
    fun `a backed-up video is judged by its device copy's mime`() {
        // The Synced branch reads the LOCAL mime, so a blank one there hides the item from the chip.
        val blankLocal = GalleryItem.Synced(
            cloud = cloudPhoto("clip.mp4", mime = "video/mp4"),
            local = LocalMediaItem(
                uri = "content://media/clip.mp4", dateTaken = juneDay, displayName = "clip.mp4",
                mimeType = "", sizeBytes = 1L, bucketName = null,
            ),
        )
        assertTrue(search(listOf(blankLocal), filter = ContentFilter(mediaType = MediaType.VideosOnly)).isEmpty())
        assertEquals(
            listOf("clip.mp4"),
            namesFrom(search(listOf(blankLocal), filter = ContentFilter(mediaType = MediaType.PhotosOnly))),
        )
    }

    // ── the sync-status filter ──────────────────────────────────────────────────────────────────

    @Test
    fun `the not-backed-up filter keeps only what lives on the device alone`() {
        val items = listOf(local("a.jpg"), synced("b.jpg"), cloud("c.jpg"))
        assertEquals(
            listOf("a.jpg"),
            namesFrom(search(items, filter = ContentFilter(syncStatus = SyncStatusFilter.LocalOnly))),
        )
    }

    @Test
    fun `the backed-up filter keeps both halves of a backed-up library`() {
        val items = listOf(local("a.jpg"), synced("b.jpg"), cloud("c.jpg"))
        assertEquals(
            listOf("b.jpg", "c.jpg"),
            namesFrom(search(items, filter = ContentFilter(syncStatus = SyncStatusFilter.BackedUp))),
        )
    }

    @Test
    fun `the media type and the sync status narrow together`() {
        val items = listOf(
            local("a.jpg"), local("clip.mp4", mime = "video/mp4"),
            cloud("c.mp4", mime = "video/mp4"),
        )
        assertEquals(
            listOf("clip.mp4"),
            namesFrom(
                search(
                    items,
                    filter = ContentFilter(mediaType = MediaType.VideosOnly, syncStatus = SyncStatusFilter.LocalOnly),
                ),
            ),
        )
    }

    // ── the year / month / day filter ───────────────────────────────────────────────────────────

    @Test
    fun `a year on its own keeps every photo from that year`() {
        val items = listOf(local("a.jpg", at = juneDay), local("b.jpg", at = julyDay), local("c.jpg", at = lastJune))
        assertEquals(listOf("a.jpg", "b.jpg"), namesFrom(search(items, filter = ContentFilter(year = 2024))))
    }

    @Test
    fun `a month narrows within its year and is counted from one`() {
        val items = listOf(local("a.jpg", at = juneDay), local("b.jpg", at = julyDay))
        assertEquals(listOf("a.jpg"), namesFrom(search(items, filter = ContentFilter(year = 2024, month = 6))))
        assertEquals(listOf("b.jpg"), namesFrom(search(items, filter = ContentFilter(year = 2024, month = 7))))
    }

    @Test
    fun `a day narrows within its month`() {
        val items = listOf(local("a.jpg", at = juneDay), local("b.jpg", at = at(2024, 6, 16)))
        assertEquals(
            listOf("a.jpg"),
            namesFrom(search(items, filter = ContentFilter(year = 2024, month = 6, day = 15))),
        )
    }

    @Test
    fun `a month without a year keeps that month in every year`() {
        // Each date part narrows on its own, the way the timeline's own filter has always read them.
        // A month that narrowed nothing while still counting as "something was asked for" made the
        // page answer with the whole library.
        val items = listOf(local("a.jpg", at = juneDay), local("b.jpg", at = julyDay), local("c.jpg", at = lastJune))
        assertEquals(listOf("a.jpg", "c.jpg"), namesFrom(search(items, filter = ContentFilter(month = 6))))
    }

    @Test
    fun `a day without a year or a month keeps that day number across the library`() {
        val items = listOf(
            local("a.jpg", at = juneDay),
            local("b.jpg", at = at(2024, 6, 16)),
            local("c.jpg", at = lastJune),
        )
        assertEquals(listOf("a.jpg", "c.jpg"), namesFrom(search(items, filter = ContentFilter(day = 15))))
    }

    @Test
    fun `a month and a day without a year keep that date in every year`() {
        val items = listOf(
            local("a.jpg", at = juneDay),
            local("b.jpg", at = at(2024, 7, 15)),
            local("c.jpg", at = lastJune),
        )
        assertEquals(listOf("a.jpg", "c.jpg"), namesFrom(search(items, filter = ContentFilter(month = 6, day = 15))))
    }

    @Test
    fun `a date filter that matches nothing answers with nothing, not with everything`() {
        val items = listOf(local("a.jpg", at = juneDay), local("b.jpg", at = julyDay))
        assertTrue(search(items, filter = ContentFilter(month = 2)).isEmpty())
    }

    @Test
    fun `a day without a month still narrows to that day number in every month of the year`() {
        val items = listOf(local("a.jpg", at = juneDay), local("b.jpg", at = at(2024, 7, 15)), local("c.jpg", at = julyDay))
        assertEquals(listOf("a.jpg", "b.jpg"), namesFrom(search(items, filter = ContentFilter(year = 2024, day = 15))))
    }

    @Test
    fun `a backed-up photo is dated by the effective capture time, not the raw cloud one`() {
        // A cloud captureTime below the sanity floor falls back to the device copy's date, and the
        // date filter has to see the same day the timeline shows.
        val item = GalleryItem.Synced(
            cloud = cloudPhoto("a.jpg").copy(captureTime = 0L),
            local = LocalMediaItem(
                uri = "content://media/a.jpg", dateTaken = juneDay, displayName = "a.jpg",
                mimeType = "image/jpeg", sizeBytes = 1L, bucketName = null,
            ),
        )
        assertEquals(
            listOf("a.jpg"),
            namesFrom(search(listOf(item), filter = ContentFilter(year = 2024, month = 6, day = 15))),
        )
    }

    // ── the category chips ──────────────────────────────────────────────────────────────────────

    @Test
    fun `the live photos chip accepts either the iOS tag or the Android one`() {
        val livePhoto = cloud("live.heic", tags = setOf(3))
        val motionPhoto = cloud("motion.jpg", tags = setOf(4))
        val plain = cloud("plain.jpg")
        val items = listOf(livePhoto, motionPhoto, plain)
        assertEquals(listOf("live.heic", "motion.jpg"), namesFrom(search(items, category = GalleryFilter.LivePhotos)))
        assertEquals(listOf("live.heic", "motion.jpg"), namesFrom(search(items, category = GalleryFilter.MotionPhotos)))
    }

    @Test
    fun `the two motion chips agree with each other exactly`() {
        val items = listOf(cloud("a.jpg", tags = setOf(3)), local("MVIMG_1.jpg"), local("b.jpg", bucket = "Live Photos"))
        assertEquals(
            namesFrom(search(items, category = GalleryFilter.LivePhotos)),
            namesFrom(search(items, category = GalleryFilter.MotionPhotos)),
        )
    }

    @Test
    fun `a tag chip falls back to the local heuristics for a photo the server has not seen`() {
        val items = listOf(local("Screenshot_1.png", mime = "image/png"), local("IMG_1.jpg"))
        assertEquals(listOf("Screenshot_1.png"), namesFrom(search(items, category = GalleryFilter.Screenshots)))
    }

    @Test
    fun `the offline chip only ever matches a photo that lives in the cloud alone`() {
        val cloudPinned = cloud("c.jpg", linkId = "pinned")
        val syncedPinned = synced("s.jpg", linkId = "pinned-too")
        val items = listOf(cloudPinned, syncedPinned)
        assertEquals(
            listOf("c.jpg"),
            namesFrom(search(items, category = GalleryFilter.Offline, offlinePinIds = setOf("pinned", "pinned-too"))),
        )
    }

    @Test
    fun `the offline chip with nothing pinned finds nothing`() {
        assertTrue(search(listOf(cloud("c.jpg")), category = GalleryFilter.Offline).isEmpty())
    }

    @Test
    fun `the favourites chip counts both stores a heart can live in`() {
        // A backed-up photo's heart is Drive PhotoTag 0; a device-only photo's is its MediaStore uri
        // in the local set. The chip has to answer for both, or a photo hearted here on this very
        // page is then not findable by it.
        val cloudFavourite = cloud("c.jpg", tags = setOf(0))
        val deviceFavourite = local("d.jpg", uri = "content://media/d")
        val plainCloud = cloud("e.jpg")
        val plainDevice = local("f.jpg", uri = "content://media/f")
        val items = listOf(cloudFavourite, deviceFavourite, plainCloud, plainDevice)
        assertEquals(
            listOf("c.jpg", "d.jpg"),
            namesFrom(search(items, category = GalleryFilter.Favorites, favoriteIds = setOf("content://media/d"))),
        )
    }

    @Test
    fun `the device-side heart never speaks for a backed-up photo`() {
        // A backed-up photo's heart is the server's alone, so a stale local id for one changes
        // nothing: another client can drop the Drive tag and the page has to follow.
        val synced = synced("s.jpg")
        assertTrue(
            search(
                listOf(synced),
                category = GalleryFilter.Favorites,
                favoriteIds = setOf("content://media/s.jpg"),
            ).isEmpty(),
        )
    }

    @Test
    fun `the favourites chip with nothing hearted finds nothing`() {
        val items = listOf(local("a.jpg"), cloud("b.jpg"), synced("c.jpg"))
        assertTrue(search(items, category = GalleryFilter.Favorites).isEmpty())
    }

    @Test
    fun `the selfies and portraits chips are answered by the server alone`() {
        val items = listOf(local("selfie.jpg"), cloud("c.jpg", tags = setOf(5)))
        assertEquals(listOf("c.jpg"), namesFrom(search(items, category = GalleryFilter.Selfies)))
        assertTrue(search(listOf(local("portrait.jpg")), category = GalleryFilter.Portraits).isEmpty())
    }

    // ── the whole chain together ────────────────────────────────────────────────────────────────

    @Test
    fun `a query, a filter and a chip all narrow the same list`() {
        val items = listOf(
            cloud("beach.mp4", mime = "video/mp4", at = juneDay, tags = setOf(2)),
            cloud("beach.jpg", at = juneDay, tags = setOf(2)),
            cloud("forest.mp4", mime = "video/mp4", at = juneDay, tags = setOf(2)),
            cloud("beach.mp4", mime = "video/mp4", at = lastJune, linkId = "old", tags = setOf(2)),
        )
        val out = search(
            items,
            q = "beach",
            filter = ContentFilter(mediaType = MediaType.VideosOnly, year = 2024),
            category = GalleryFilter.Videos,
        )
        assertEquals(1, out.size)
        assertEquals("beach.mp4", SearchFilter.displayNameOf(out.first()))
    }

    @Test
    fun `the results keep the order the library handed them over in`() {
        val items = listOf(local("c.jpg"), local("a.jpg"), local("b.jpg"))
        assertEquals(listOf("c.jpg", "a.jpg", "b.jpg"), namesFrom(search(items, q = "jpg")))
    }

    @Test
    fun `an empty library survives every filter`() {
        assertTrue(search(emptyList(), q = "beach").isEmpty())
        assertTrue(search(emptyList(), filter = ContentFilter(year = 2024)).isEmpty())
        assertTrue(search(emptyList(), category = GalleryFilter.Videos).isEmpty())
    }

    // ── the month names the view model hands over ───────────────────────────────────────────────

    @Test
    fun `the month names are twelve, folded, and in calendar order`() {
        assertEquals(12, englishMonths.size)
        assertEquals("january", englishMonths.first())
        assertEquals("december", englishMonths.last())
        assertFalse("a folded name never keeps an accent", hungarianMonths.any { it.any { c -> c.code > 127 } })
    }
}
