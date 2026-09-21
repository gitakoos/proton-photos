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

package eu.akoos.photos.presentation.whatsnew

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Face
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the "What's new" page packing: cards spread evenly across the fewest pages that fit, so a
 * tall screen never shows one crammed page and a lonely last one (six cards read as three plus three,
 * not five plus one).
 */
class WhatsNewPagingTest {

    private val card = 112.dp
    private val gap = 12.dp

    // A tall screen fits five cards; six of them must still split 3 + 3, not 5 + 1.
    @Test
    fun six_cards_on_a_tall_screen_split_three_and_three() {
        val pages = packFeaturePages(count = 6, pageHeight = 635.dp, cardHeight = card, spacing = gap)
        assertEquals(listOf(0 until 3, 3 until 6), pages)
    }

    // Whatever the height, the split stays as even as it can be and every card lands on exactly one
    // contiguous page.
    @Test
    fun pages_are_balanced_contiguous_and_complete() {
        for (count in 1..12) {
            for (pageHeightDp in intArrayOf(200, 360, 480, 635, 900)) {
                val pages = packFeaturePages(count, pageHeightDp.dp, card, gap)
                // Covers every index once, in order.
                assertEquals("count=$count h=$pageHeightDp", (0 until count).toList(), pages.flatMap { it.toList() })
                // Balanced: page sizes differ by at most one.
                val sizes = pages.map { it.count() }
                assertTrue("count=$count h=$pageHeightDp sizes=$sizes", sizes.max() - sizes.min() <= 1)
            }
        }
    }

    @Test
    fun a_short_screen_still_shows_at_least_one_card_per_page() {
        val pages = packFeaturePages(count = 4, pageHeight = 120.dp, cardHeight = card, spacing = gap)
        assertEquals(4, pages.size)
        assertTrue(pages.all { it.count() == 1 })
    }

    @Test
    fun no_features_means_no_pages() {
        assertTrue(packFeaturePages(count = 0, pageHeight = 635.dp, cardHeight = card, spacing = gap).isEmpty())
    }

    private fun feature(title: Int, category: WhatsNewCategory) =
        WhatsNewFeature(Icons.Default.Face, title, title + 1000, category)

    // A release with both New and Improved cards splits into two labeled sections, New before Improved,
    // each on its own page on a tall screen.
    @Test
    fun a_release_with_two_categories_splits_into_labeled_sections() {
        val release = WhatsNewRelease(
            version = "9.9.9",
            hero = null,
            features = listOf(
                feature(1, WhatsNewCategory.New),
                feature(2, WhatsNewCategory.New),
                feature(3, WhatsNewCategory.Improved),
                feature(4, WhatsNewCategory.Improved),
            ),
        )
        val pages = categoryFeaturePages(release, 900.dp, card, gap, 40.dp)
        assertEquals(2, pages.size)
        assertEquals(WhatsNewCategory.New.titleRes, pages[0].headerRes)
        assertEquals(listOf(1, 2), pages[0].features.map { it.titleRes })
        assertEquals(WhatsNewCategory.Improved.titleRes, pages[1].headerRes)
        assertEquals(listOf(3, 4), pages[1].features.map { it.titleRes })
    }

    // A section never shares a page with the next one, even when both would otherwise fit together.
    @Test
    fun a_section_boundary_always_starts_a_new_page() {
        val release = WhatsNewRelease(
            version = "9.9.8",
            hero = null,
            features = listOf(
                feature(1, WhatsNewCategory.New),
                feature(2, WhatsNewCategory.Improved),
            ),
        )
        val pages = categoryFeaturePages(release, 900.dp, card, gap, 40.dp)
        assertEquals(2, pages.size)
        assertTrue(pages.all { it.features.size == 1 })
    }

    // A release whose cards are all one category shows no section labels (older entries render as before).
    @Test
    fun a_single_category_release_has_no_section_labels() {
        val release = WhatsNewRelease(
            version = "9.9.7",
            hero = null,
            features = listOf(feature(1, WhatsNewCategory.New), feature(2, WhatsNewCategory.New)),
        )
        val pages = categoryFeaturePages(release, 900.dp, card, gap, 40.dp)
        assertTrue(pages.all { it.headerRes == null })
        assertEquals(listOf(1, 2), pages.flatMap { it.features }.map { it.titleRes })
    }
}
