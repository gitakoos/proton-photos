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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the shape of the release catalog rather than its wording. The rule these protect is the
 * one the flat pre-catalog list broke: a feature belongs to the release that introduced it and is
 * announced exactly once, so browsing the history reads as a changelog instead of the same cards
 * repeating under every version.
 */
class WhatsNewCatalogTest {

    private fun versionKey(v: String): List<Int> = v.split(".").map { it.toInt() }

    @Test
    fun the_catalog_is_not_empty() {
        assertTrue(WhatsNewReleases.isNotEmpty())
    }

    @Test
    fun every_release_announces_something() {
        for (release in WhatsNewReleases) {
            assertTrue(
                "${release.version} has no cards at all",
                release.hero != null || release.features.isNotEmpty(),
            )
        }
    }

    @Test
    fun every_release_can_supply_a_headline_for_the_version_list() {
        for (release in WhatsNewReleases) {
            // headlineRes falls back to the first feature, which throws on an empty list. The
            // version list calls this for every row, so a bad entry must fail here, not on screen.
            assertNotNull("${release.version} cannot produce a headline", release.headlineRes)
            assertTrue(release.headlineRes != 0)
        }
    }

    @Test
    fun versions_are_unique() {
        val versions = WhatsNewReleases.map { it.version }
        assertEquals("a version is listed twice: $versions", versions.size, versions.toSet().size)
    }

    @Test
    fun versions_run_newest_first() {
        val keys = WhatsNewReleases.map { versionKey(it.version) }
        for (i in 1 until keys.size) {
            val newer = keys[i - 1]
            val older = keys[i]
            assertTrue(
                "${WhatsNewReleases[i - 1].version} must sort above ${WhatsNewReleases[i].version}",
                compareVersions(newer, older) > 0,
            )
        }
    }

    private fun compareVersions(a: List<Int>, b: List<Int>): Int {
        for (i in 0 until maxOf(a.size, b.size)) {
            val d = (a.getOrElse(i) { 0 }).compareTo(b.getOrElse(i) { 0 })
            if (d != 0) return d
        }
        return 0
    }

    @Test
    fun no_feature_is_announced_under_two_releases() {
        val seen = mutableMapOf<Int, String>()
        for (release in WhatsNewReleases) {
            for (feature in release.features) {
                val previous = seen.put(feature.titleRes, release.version)
                assertEquals(
                    "a card is announced under both $previous and ${release.version}; " +
                        "a release lists only what it introduced",
                    null,
                    previous,
                )
            }
        }
    }

    @Test
    fun no_headline_card_is_reused_across_releases() {
        val heroes = WhatsNewReleases.mapNotNull { it.hero }
        assertEquals("a headline card is used by two releases", heroes.size, heroes.toSet().size)
    }

    @Test
    fun a_feature_never_pairs_a_title_with_another_cards_body() {
        // A copy-paste that changes the title but forgets the body is invisible until it is read
        // on screen, and the bodies are the part nobody re-reads.
        val bodies = WhatsNewReleases.flatMap { it.features }.map { it.bodyRes }
        assertEquals("two cards share one body string", bodies.size, bodies.toSet().size)
    }

    @Test
    fun no_two_releases_share_a_closing_line() {
        // The closing line is drawn on the last page of whichever release is open, so a shared one
        // would tell someone reading an old release about changes that shipped after it.
        val lines = WhatsNewReleases.mapNotNull { it.moreRes }
        assertEquals("two releases share a closing line", lines.size, lines.toSet().size)
    }

    @Test
    fun the_newest_release_is_the_one_the_post_update_screen_shows() {
        assertEquals(WhatsNewReleases.first(), LatestWhatsNewRelease)
    }

    @Test
    fun a_known_version_opens_that_release() {
        for (release in WhatsNewReleases) {
            assertEquals(release, whatsNewReleaseFor(release.version))
        }
    }

    @Test
    fun an_unknown_or_missing_version_falls_back_to_the_newest() {
        assertEquals(LatestWhatsNewRelease, whatsNewReleaseFor(null))
        assertEquals(LatestWhatsNewRelease, whatsNewReleaseFor(""))
        assertEquals(LatestWhatsNewRelease, whatsNewReleaseFor("0.0.1"))
        assertEquals(LatestWhatsNewRelease, whatsNewReleaseFor("not a version"))
    }

    @Test
    fun the_current_release_is_2_5_0_with_feature_cards() {
        // 2.5.0 ships as previews, so this card holds only the newest preview's delta rather than
        // the whole release. It leads with feature cards (no hero) and announces at least one.
        val latest = LatestWhatsNewRelease
        assertEquals("2.5.0", latest.version)
        assertTrue("2.5.0 leads with feature cards, not a hero card", latest.hero == null)
        assertTrue("2.5.0 announces its preview cards", latest.features.isNotEmpty())
    }
}
