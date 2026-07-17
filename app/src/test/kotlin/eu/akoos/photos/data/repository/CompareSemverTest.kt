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

package eu.akoos.photos.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Version precedence for the in-app updater. [compareSemver] ranks the latest stable tag against
 * the installed version name, and performCheck() offers the update only when the remote ranks
 * strictly higher. Preview builds install a "-testN" name, so the suffix has to rank below its own
 * release without dragging the whole version under the previous one — a suffix that collapses to 0
 * offers the tester the older stable instead. Pure string compare → no DI / network / DataStore.
 */
class CompareSemverTest {

    /** Mirrors performCheck(): the remote tag is offered only when it ranks strictly newer. */
    private fun promptsUpdate(local: String, remote: String) = compareSemver(remote, local) > 0

    @Test
    fun `a preview build is not offered the older stable`() {
        assertFalse(promptsUpdate(local = "2.3.10-test12", remote = "2.3.9"))
    }

    @Test
    fun `a preview build is offered its own final release`() {
        // The tester has to reach the stable build without reinstalling.
        assertTrue(promptsUpdate(local = "2.3.10-test12", remote = "2.3.10"))
    }

    @Test
    fun `a preview build is offered a later stable`() {
        assertTrue(promptsUpdate(local = "2.3.10-test12", remote = "2.3.11"))
    }

    @Test
    fun `a stable build on the latest tag is not offered an update`() {
        assertFalse(promptsUpdate(local = "2.3.9", remote = "2.3.9"))
    }

    @Test
    fun `a stable build is offered the next stable`() {
        assertTrue(promptsUpdate(local = "2.3.9", remote = "2.3.10"))
    }

    @Test
    fun `a pre-release ranks below its own release`() {
        assertTrue(compareSemver("2.3.10-test12", "2.3.10") < 0)
        assertTrue(compareSemver("2.3.10", "2.3.10-test12") > 0)
    }

    @Test
    fun `a pre-release ranks above the previous version`() {
        assertTrue(compareSemver("2.3.10-test12", "2.3.9") > 0)
    }

    @Test
    fun `equal versions compare equal`() {
        assertEquals(0, compareSemver("2.3.9", "2.3.9"))
        assertEquals(0, compareSemver("2.3.10-test12", "2.3.10-test12"))
    }

    @Test
    fun `missing trailing segments count as zero`() {
        assertEquals(0, compareSemver("2.3", "2.3.0"))
        assertEquals(0, compareSemver("2", "2.0.0"))
    }

    @Test
    fun `a multi-digit segment is compared numerically`() {
        assertTrue(compareSemver("2.3.10", "2.3.9") > 0)
    }

    @Test
    fun `suffix ordering follows the trailing number`() {
        assertTrue(compareSemver("2.3.10-test9", "2.3.10-test12") < 0)
        assertTrue(compareSemver("2.3.10-test13", "2.3.10-test9") > 0)
    }

    @Test
    fun `unparseable input degrades instead of throwing`() {
        assertTrue(compareSemver("not-a-version", "2.3.9") < 0)
        assertTrue(compareSemver("2.3.9", "") > 0)
        assertEquals(0, compareSemver("", ""))
    }
}
